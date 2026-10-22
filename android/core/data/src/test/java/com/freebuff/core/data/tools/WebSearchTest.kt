package com.freebuff.core.data.tools

import com.freebuff.core.data.repository.SearchKeyProvider
import com.freebuff.core.model.ToolErrors
import com.freebuff.core.model.ToolCallReq
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * web_search 的两条通道:主路是真实搜索 API(Exa,需要密钥),降级是免钥摘要通道。
 *
 * 两个端点都指向 MockWebServer(见 [ToolExecutors.exaEndpoint] / [ToolExecutors.fallbackEndpoint]),
 * 所以这些用例**不产生任何外网请求**,却能覆盖「主路成功 / 未配置 / 被拒 / 空结果 / 两条都不通」。
 */
class WebSearchTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** @param key 空串 = 未配置密钥(执行器应改走免钥通道) */
    private fun executors(key: String = ""): ToolExecutors =
        ToolExecutors(
            client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build(),
            searchKey = SearchKeyProvider { key },
        ).apply {
            exaEndpoint = server.url("/search").toString()
            fallbackEndpoint = server.url("/ddg").toString()
        }

    /** Exa 的成功响应:每条含 title / url / publishedDate / highlights。 */
    private fun exaResponse(vararg results: Triple<String, String, String>): MockResponse {
        val arr = JSONArray()
        results.forEach { (title, url, snippet) ->
            arr.put(
                JSONObject()
                    .put("title", title)
                    .put("url", url)
                    .put("publishedDate", "2026-08-14T00:00:00.000Z")
                    .put("highlights", JSONArray().put(snippet))
                    // 成本/耗时这类字段不进模型上下文,这里放上以确认解析器只挑需要的
                    .put("costDollars", JSONObject().put("total", 0.007)),
            )
        }
        return MockResponse().setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("requestId", "r1").put("results", arr).toString())
    }

    /** DuckDuckGo Instant Answer 的响应(免钥通道)。 */
    private fun ddgResponse(abstract: String, vararg related: Pair<String, String>): MockResponse {
        val arr = JSONArray()
        related.forEach { (text, url) -> arr.put(JSONObject().put("Text", text).put("FirstURL", url)) }
        return MockResponse().setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("AbstractText", abstract).put("RelatedTopics", arr).toString())
    }

    @Test
    fun `配置了密钥就走真实搜索接口, 解析标题链接与命中片段`() = runBlocking {
        server.enqueue(
            exaResponse(
                Triple("Compose 1.9 发布", "https://example.com/compose-19", "1.9 带来新的布局稳定器"),
                Triple("迁移指南", "https://example.com/migrate", "从 1.8 升到 1.9 的注意事项"),
            ),
        )

        val r = executors(key = "exa-secret-key")
            .execute(ToolCallReq("c1", "web_search", """{"query":"compose 1.9"}"""))

        assertFalse("主路成功不该是错误结果", r.isError)
        assertTrue(r.content.contains("Compose 1.9 发布"))
        assertTrue("每条都要带链接,模型接着 web_fetch 追问", r.content.contains("https://example.com/compose-19"))
        assertTrue("要带上命中片段", r.content.contains("新的布局稳定器"))
        assertTrue("日期对「最新动态」类查询有用", r.content.contains("2026-08-14"))
        assertFalse("密钥不得出现在给模型/用户看的正文里", r.content.contains("exa-secret-key"))

        assertEquals("只打了一次搜索接口", 1, server.requestCount)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/search", req.path)
        assertEquals("密钥走 x-api-key", "exa-secret-key", req.getHeader("x-api-key"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("compose 1.9", body.getString("query"))
        assertEquals(5, body.getInt("numResults"))
        assertTrue("highlights 是给模型的证据片段", body.getJSONObject("contents").getBoolean("highlights"))
    }

    @Test
    fun `字段为 JSON null 时不把字面量 null 当成标题或链接`() = runBlocking {
        // Exa 偶尔对某些结果给 title: null;org.json 的 optString 会把它拼成 "null",
        // 不处理就会以标题「null」出现在结果里
        val body = JSONObject().put(
            "results",
            JSONArray()
                .put(JSONObject().put("title", JSONObject.NULL).put("url", "https://example.com/untitled"))
                .put(JSONObject().put("title", "正常标题").put("url", "https://example.com/ok")),
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString()),
        )

        val r = executors(key = "k")
            .execute(ToolCallReq("c1", "web_search", """{"query":"q"}"""))

        assertFalse("不能出现字面量 null", r.content.contains("null"))
        assertTrue("没标题就用链接当标题", r.content.contains("- https://example.com/untitled") || r.content.contains("https://example.com/untitled"))
        assertTrue(r.content.contains("正常标题"))
    }

    @Test
    fun `未配置密钥时降级到免钥通道, 并在结果里说明`() = runBlocking {
        server.enqueue(
            ddgResponse("Compose 是 Kotlin 的声明式 UI 工具包", "官方文档" to "https://developer.android.com/compose"),
        )

        val r = executors(key = "")
            .execute(ToolCallReq("c1", "web_search", """{"query":"compose"}"""))

        assertFalse("降级不是失败:模型该继续往下走", r.isError)
        assertTrue("要说清结果为什么不完整", r.content.contains("未配置搜索 API 密钥"))
        assertTrue(r.content.contains("免钥摘要通道"))
        assertTrue(r.content.contains("Compose 是 Kotlin 的声明式 UI 工具包"))
        assertTrue(r.content.contains("https://developer.android.com/compose"))
        assertTrue("要让用户知道去哪儿补密钥", r.content.contains("设置 → 集成 → 联网搜索"))

        assertEquals("没配置密钥就不该碰搜索接口", 1, server.requestCount)
        assertEquals("/ddg?q=compose&format=json&no_html=1&skip_disambig=1", server.takeRequest().path)
    }

    @Test
    fun `密钥被拒(401)时降级, 并把状态码写进结果`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid api key"}"""))
        server.enqueue(ddgResponse("降级拿到的摘要"))

        val r = executors(key = "wrong-key")
            .execute(ToolCallReq("c1", "web_search", """{"query":"kotlin"}"""))

        assertFalse("降级后仍是有用的结果", r.isError)
        assertTrue("要有可诊断的原因", r.content.contains("HTTP 401"))
        assertTrue(r.content.contains("已降级到免钥通道"))
        assertTrue("降级内容也要在", r.content.contains("降级拿到的摘要"))
        assertFalse("响应体/密钥不进上下文", r.content.contains("wrong-key"))
        assertFalse("端点返回的错误细节不进上下文", r.content.contains("invalid api key"))
        assertEquals("两条通道各打一次", 2, server.requestCount)
    }

    @Test
    fun `被限流(429)时同样降级`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"rate limit"}"""))
        server.enqueue(ddgResponse("限流后的免钥结果"))

        val r = executors(key = "k")
            .execute(ToolCallReq("c1", "web_search", """{"query":"okhttp"}"""))

        assertTrue(r.content.contains("HTTP 429"))
        assertFalse(r.isError)
    }

    @Test
    fun `主路返回空结果时用免钥通道补一次`() = runBlocking {
        server.enqueue(exaResponse())
        server.enqueue(ddgResponse("免钥通道的补充结果"))

        val r = executors(key = "k")
            .execute(ToolCallReq("c1", "web_search", """{"query":"冷门词"}"""))

        assertFalse(r.isError)
        assertTrue("要说明主路没给结果", r.content.contains("没有返回结果"))
        assertTrue("补回来的内容要在", r.content.contains("免钥通道的补充结果"))
    }

    @Test
    fun `两条通道都不通时升级成带处方的错误信封`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("also down"))

        val r = executors(key = "k")
            .execute(ToolCallReq("c1", "web_search", """{"query":"whatever"}"""))

        assertTrue("一条结果一条链接都没有时,带处方的信封比一段空话有用", r.isError)
        assertTrue(r.content.startsWith(ToolErrors.MARK))
        assertTrue(r.content.contains("降级通道也失败了"))
        assertTrue("要给出主路状态码,用户才知道该修密钥还是等服务恢复", r.content.contains("HTTP 500"))
        assertTrue("要给下一步", r.content.contains("web_fetch"))
        assertFalse("端点响应体不进上下文", r.content.contains("also down"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `没配密钥且免钥通道也没结果时, 仍是可以继续往下走的结果`() = runBlocking {
        // 主路根本没打(无密钥),降级通道返回一张空摘要 —— 不算错误:
        // 模型该换关键词或改用 web_fetch,而不是被一次搜索卡住整轮
        server.enqueue(ddgResponse(""))

        val r = executors(key = "")
            .execute(ToolCallReq("c1", "web_search", """{"query":"nobody-indexed-this"}"""))

        assertFalse(r.isError)
        assertTrue("要说清为什么没有内容", r.content.contains("找不到"))
        assertTrue(r.content.contains("web_fetch"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `缺少查询词在跑到网络之前就被拦下`() = runBlocking {
        val r = executors(key = "k").execute(ToolCallReq("c1", "web_search", """{}"""))

        assertTrue(r.isError)
        assertTrue(r.content.contains("query"))
        assertEquals("不该产生任何请求", 0, server.requestCount)
    }
}
