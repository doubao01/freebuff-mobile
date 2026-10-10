package com.freebuff.core.data.repository

import com.freebuff.core.data.tools.ToolExecutors
import com.freebuff.core.model.ChatTarget
import com.freebuff.core.model.Subagent
import com.freebuff.core.model.ToolPermission
import kotlinx.coroutines.test.runTest
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

/**
 * 子代理执行引擎 P0 的行为契约(见 docs/subagent-design.md):
 * 独立上下文、只读白名单随权限降级、白名单外调用不执行、轮数用尽强制收敛、失败不静默。
 *
 * 全部用 `analyst`(白名单只有 calculator)跑:calculator 是纯本地解析,
 * 因此这些用例**不产生任何外网请求**,只与 MockWebServer 说要。
 *
 * 注意:所有用例都传 `idleTimeoutMs = 0, deadlineMs = 0` —— `runTest` 的虚拟时钟在等真实 I/O 时会快进,
 * 开着看门狗/总时限会变成「请求还没发出去就被判超时」(仓库内既有用例同款取舍,见 ChatRepositoryTest)。
 */
class SubagentRunnerTest {

    private lateinit var server: MockWebServer
    private val runner = SubagentRunner(ChatRepository(), ToolExecutors(OkHttpClient()))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun target() = ChatTarget(endpoint = server.url("/v1").toString(), model = "m", name = "m")

    /** 一帧 SSE:模型请求一个工具调用,并以 finish_reason=tool_calls 落定。 */
    private fun toolCallResponse(id: String, name: String, argsJson: String): MockResponse {
        val delta = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "delta",
                    JSONObject().put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject().put("index", 0).put("id", id)
                                .put("function", JSONObject().put("name", name).put("arguments", argsJson)),
                        ),
                    ),
                ),
            ),
        )
        val finish = JSONObject().put(
            "choices",
            JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", "tool_calls")),
        )
        return MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: $delta\n\ndata: $finish\n\ndata: [DONE]\n\n")
    }

    /** 一帧 SSE:纯文本回复。 */
    private fun textResponse(text: String): MockResponse {
        val o = JSONObject().put(
            "choices",
            JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text))),
        )
        return MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: $o\n\ndata: [DONE]\n\n")
    }

    private suspend fun runAnalyst(
        task: String = "算 (12+8)*3.5",
        permissions: (String) -> ToolPermission = { ToolPermission.ALLOW },
    ): SubagentRunner.Result = runner.run(
        target(), Subagent.Request("analyst", task),
        permissions = permissions, idleTimeoutMs = 0, deadlineMs = 0,
    )

    @Test
    fun `analyst 走完一轮工具后拿到结论且结果按协议回填`() = runTest {
        server.enqueue(toolCallResponse("call-1", "calculator", """{"expression":"(12+8)*3.5"}"""))
        server.enqueue(textResponse("计算结果是 70"))

        val result = runAnalyst(task = "算 (12+8)*3.5")

        val ok = result as SubagentRunner.Result.Ok
        assertEquals("计算结果是 70", ok.text)
        assertEquals(1, ok.rounds)
        assertEquals(listOf("calculator"), ok.toolsUsed)

        // 第一次请求:子代理自己的上下文(系统剧本 + 任务),工具清单只含白名单
        val first = JSONObject(server.takeRequest().body.readUtf8())
        val tools = first.getJSONArray("tools")
        assertEquals(1, tools.length())
        assertEquals("calculator", tools.getJSONObject(0).getJSONObject("function").getString("name"))
        val msgs = first.getJSONArray("messages")
        assertEquals(2, msgs.length())
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertTrue("剧本要表明这是子代理", msgs.getJSONObject(0).getString("content").contains("子代理"))
        assertEquals("user", msgs.getJSONObject(1).getString("role"))
        assertTrue("任务要进上下文", msgs.getJSONObject(1).getString("content").contains("(12+8)*3.5"))

        // 第二次请求:上一轮的 assistant(tool_calls) 与工具结果按 OpenAI 协议回填
        val second = JSONObject(server.takeRequest().body.readUtf8())
        val msgs2 = second.getJSONArray("messages")
        val toolMsg = msgs2.getJSONObject(msgs2.length() - 1)
        assertEquals("tool", toolMsg.getString("role"))
        assertEquals("call-1", toolMsg.getString("tool_call_id"))
        assertTrue("工具真的执行了", toolMsg.getString("content").contains("= 70"))
    }

    @Test
    fun `需确认的工具不进子代理清单,剧本也不提它`() = runTest {
        // researcher 原本是 web_search + web_fetch;把 web_fetch 设成需确认后应被整体摘掉
        // (后台任务不能阻塞在弹窗上,见 subagent-design §5)。这里让模型直接给结论,不触发任何工具。
        server.enqueue(textResponse("结论:只用 web_search 就够"))

        val result = runner.run(
            target(),
            Subagent.Request("researcher", "调研一下"),
            permissions = { name -> if (name == "web_fetch") ToolPermission.CONFIRM else ToolPermission.ALLOW },
            idleTimeoutMs = 0,
            deadlineMs = 0,
        )

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val toolNames = (0 until body.getJSONArray("tools").length())
            .map { body.getJSONArray("tools").getJSONObject(it).getJSONObject("function").getString("name") }
        assertEquals(listOf("web_search"), toolNames)
        val systemMsg = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertFalse("剧本要按实际可用工具说实情", systemMsg.contains("web_fetch"))
        assertEquals("结论:只用 web_search 就够", (result as SubagentRunner.Result.Ok).text)
    }

    @Test
    fun `researcher 的白名单是联网只读工具`() = runTest {
        server.enqueue(textResponse("结论:compose 1.8 加了 …"))

        val result = runner.run(
            target(),
            Subagent.Request("researcher", "查 compose 1.8 新特性"),
            idleTimeoutMs = 0,
            deadlineMs = 0,
        )

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val toolNames = (0 until body.getJSONArray("tools").length())
            .map { body.getJSONArray("tools").getJSONObject(it).getJSONObject("function").getString("name") }
        assertEquals(listOf("web_search", "web_fetch"), toolNames)
        // 白名单外的工具(如 github_get_file / spawn_subagent)不得下发给子代理
        assertFalse(toolNames.contains("spawn_subagent"))
        assertEquals("结论:compose 1.8 加了 …", (result as SubagentRunner.Result.Ok).text)
    }

    @Test
    fun `白名单外的调用不执行,只回可修正的错误信封`() = runTest {
        // analyst 只能算数,模型却去调 save_memory(有副作用/不在白名单)
        server.enqueue(toolCallResponse("call-9", "save_memory", """{"block":"user","content":"x"}"""))
        server.enqueue(textResponse("我没有记忆工具,直接用已有信息作答"))

        val result = runAnalyst()

        server.takeRequest() // 第一次请求:子代理拿任务
        val second = JSONObject(server.takeRequest().body.readUtf8()) // 第二次请求:带着上一轮的工具结果
        val msgs = second.getJSONArray("messages")
        val toolMsg = msgs.getJSONObject(msgs.length() - 1)
        assertTrue("要回错误信封", toolMsg.getString("content").contains("[工具错误]"))
        assertTrue("要说清是无权调用", toolMsg.getString("content").contains("无权"))
        assertTrue("并给出可用工具", toolMsg.getString("content").contains("calculator"))
        // 子代理仍能收敛出结论
        assertEquals("我没有记忆工具,直接用已有信息作答", (result as SubagentRunner.Result.Ok).text)
    }

    @Test
    fun `工具轮数用尽后去掉 tools 强制收敛`() = runTest {
        repeat(Subagent.MAX_TOOL_ROUNDS) { i ->
            server.enqueue(toolCallResponse("call-$i", "calculator", """{"expression":"$i+1"}"""))
        }
        server.enqueue(textResponse("按已有计算结果给出结论"))

        val result = runAnalyst(task = "多轮算数后给结论")

        val ok = result as SubagentRunner.Result.Ok
        assertEquals(Subagent.MAX_TOOL_ROUNDS, ok.rounds)
        assertEquals("按已有计算结果给出结论", ok.text)
        assertEquals(Subagent.MAX_TOOL_ROUNDS + 1, server.requestCount)
        // 最后一次请求必须不带 tools:否则模型可以继续索要工具,永远收敛不了
        repeat(Subagent.MAX_TOOL_ROUNDS) { server.takeRequest() }
        val last = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse("收敛请求不带工具定义", last.has("tools"))
    }

    @Test
    fun `端点确定性失败直接失败且不重试`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))

        val result = runAnalyst()

        assertTrue(result is SubagentRunner.Result.Fail)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `瞬时失败自动重试一次后成功`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        server.enqueue(textResponse("重试后拿到了结论"))

        val result = runAnalyst()

        assertEquals("重试后拿到了结论", (result as SubagentRunner.Result.Ok).text)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `一个字都没返回时给出失败归因而不是空结论`() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n"))

        val result = runAnalyst()

        assertTrue(result is SubagentRunner.Result.Fail)
        assertTrue((result as SubagentRunner.Result.Fail).reason.contains("没有给出结论"))
    }

    @Test
    fun `模型未配置端点时不发请求`() = runTest {
        val result = runner.run(
            ChatTarget(endpoint = "", model = ""),
            Subagent.Request("analyst", "算 1+2"),
            idleTimeoutMs = 0,
            deadlineMs = 0,
        )

        assertTrue(result is SubagentRunner.Result.Fail)
        assertEquals(0, server.requestCount)
    }
}
