package com.freebuff.core.data.tools

import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.ApiResult
import com.freebuff.core.data.network.FreebuffApi
import com.freebuff.core.data.network.GithubApi
import com.freebuff.core.data.repository.GitTokenProvider
import com.freebuff.core.data.repository.SearchKeyProvider
import com.freebuff.core.model.AgentLoop
import com.freebuff.core.model.AgentTool
import com.freebuff.core.model.DefaultTools
import com.freebuff.core.model.GitRef
import com.freebuff.core.model.JsonArgs
import com.freebuff.core.model.ToolCallReq
import com.freebuff.core.model.ToolErrors
import com.freebuff.core.model.ToolHost
import com.freebuff.core.model.ToolOutcome
import com.freebuff.core.model.ToolRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 端侧工具执行器:把模型的 function calling 分派到真实实现。
 *
 * 执行环境隔离:HTTP 用共享 OkHttp,全部通过 [withContext] 移出主线程;
 * 任何异常归一化为 [ToolOutcome] 错误文案(模型收到的是可读错误,可自行决定重试或换路)。
 */
@Singleton
class ToolExecutors @Inject constructor(
    private val client: OkHttpClient,
    /** 已授权 Git token(写工具带鉴权)。未连接时为空串 —— 写工具会回「先连接 Git 账号」。 */
    private val tokens: GitTokenProvider = GitTokenProvider { "" },
    /** GitHub REST 客户端(写路径复用它的 base 与鉴权信封,单测可指向 MockWebServer)。 */
    private val github: GithubApi = GithubApi(client),
    /**
     * 搜索 API(Exa)密钥来源:未配置时为空串 —— web_search 会自动降级到免钥通道,
     * 而不是拿一张「缺少配置」的错误信封把轮次卡死。
     */
    private val searchKey: SearchKeyProvider = SearchKeyProvider { "" },
) {
    private val io: CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /**
     * 搜索接口地址。生产恒为默认值;单测把它指向 MockWebServer(见 ToolExecutorsTest),
     * 这样「主路 / 降级 / 降级原因」都能在无网络的前提下验证。
     */
    internal var exaEndpoint: String = EXA_SEARCH_URL
    internal var fallbackEndpoint: String = DUCKDUCKGO_ANSWER_URL

    private companion object {
        /** 真实搜索 API(Exa):POST + `x-api-key`,响应是 results[](title/url/highlights)。 */
        const val EXA_SEARCH_URL = "https://api.exa.ai/search"

        /** 取几条结果:5 条够模型拼答案,再多只是白烧上下文。 */
        const val EXA_RESULTS = 5

        /** 免钥降级通道(DuckDuckGo Instant Answer):只有主题摘要与相关条目。 */
        const val DUCKDUCKGO_ANSWER_URL = "https://api.duckduckgo.com/"

        /** JSON 请求体(与 GithubApi 用同一个 content-type,部分网关对 charset 敏感)。 */
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** 未配置密钥时的说明:写进结果 —— 模型据此知道结果集不完整,用户也知道去哪儿补密钥。 */
        const val NO_KEY_NOTE =
            "(未配置搜索 API 密钥,以下为免钥摘要通道的结果,可能不完整;" +
                "可在「设置 → 集成 → 联网搜索」填入密钥以启用完整搜索)"
    }

    /** 写工具集合(注册表里声明,这里只取用):鉴权失败时的处方与只读工具不同。 */
    private val writeTools: Set<String> = DefaultTools.WRITE_TOOLS

    /** 看到这两个码,写工具应先想到「没连账号 / 授权失效」。 */
    private val authCodes = setOf(401, 403)

    /**
     * DATA 路由的分派表:工具名 → 实现。
     *
     * 用表而不用 `when`,是为了把「注册表里每个 DATA 工具都有真实分派」变成**可断言的不变式**
     * (见 [dispatchedTools] 与 ToolExecutorsTest):过去漏写一个 case,调用会静默落进「未知工具」
     * 分支 —— 工具在说明书里存在、模型也能调,却永远执行不了。
     */
    private val dispatch: Map<String, suspend (String) -> ToolOutcome> = mapOf(
        "web_search" to { args -> webSearch(args) },
        "web_fetch" to { args -> webFetch(args) },
        "github_search_repositories" to { args -> ghSearchRepos(args) },
        "github_get_file" to { args -> ghGetFile(args) },
        "github_get_readme" to { args -> ghGetReadme(args) },
        "github_list_branches" to { args -> ghListBranches(args) },
        "github_create_branch" to { args -> ghCreateBranch(args) },
        "github_commit_files" to { args -> ghCommitFiles(args) },
        "github_create_pull_request" to { args -> ghCreatePullRequest(args) },
        "calculator" to { args -> calculator(args) },
        "current_time" to { ToolOutcome(currentTime()) },
        AgentLoop.COMPLETION_TOOL to { args -> completion(args) },
    )

    /** 已实现真实分派的工具名(DATA 路由覆盖度自检用)。 */
    val dispatchedTools: Set<String> get() = dispatch.keys

    /**
     * 单次执行入口。
     *
     * 给模型的每一次回应都要带「下一步怎么改」,顺序如下:
     * 1. **工具名**必须在当前可用清单里(未注册的名字给近邻建议,见 [ToolErrors.unknownTool]);
     * 2. **宽容解析参数**([JsonArgs],整个链路只做这一次):空参数(Anthropic 经兼容层对无参工具发 `""`)
     *    归一为 `{}`(否则 `JSONObject("")` 抛异常、无参工具永远执行不了 — vercel/ai#6687);
     *    栅栏/尾逗号/单引号等手写瑕疵自动修复;修不好则回「不是合法 JSON」+ 正确调用示例;
     * 3. **参数体检 + 归一**([DefaultTools.prepare]):未知参数名 / 缺必填 / 越界 / 取值不在闭集 →
     *    有处方的信封;别名与类型顺手归一到规范形;
     * 4. 真正执行;任何异常都变成有处方的错误信封 —— 绝不抛给调用方,也绝不把堆栈丢给模型。
     *
     * @param tools 当前会话可用工具集(记忆关闭时不含记忆工具),未知名字的提示据此生成
     * @param attempt 同一工具在本轮会话里连续第几次失败(≥2 时信封劝「换路」而不是重试)
     * @param host HOST 路由工具的执行回调(记忆 / 子代理需要仓库、会话与 UI);不传则 HOST 工具回未知工具信封
     */
    suspend fun execute(
        call: ToolCallReq,
        tools: List<AgentTool> = DefaultTools.ALL,
        attempt: Int = 1,
        host: ToolHost? = null,
    ): ToolOutcome = withContext(io) {
        val tool = DefaultTools.find(call.name, tools)
            ?: return@withContext ToolOutcome(
                ToolErrors.unknownTool(call.name, DefaultTools.names(tools)),
                isError = true,
            )
        val raw = when (val r = JsonArgs.parse(call.argsJson)) {
            is JsonArgs.Result.Ok -> r.obj
            is JsonArgs.Result.Invalid -> return@withContext ToolOutcome(
                ToolErrors.report(
                    ToolErrors.Kind.INVALID_ARGS, tool.name,
                    "参数不是合法 JSON:" + r.reason + ";收到的是:" + r.raw.take(120),
                    "按说明书重发一次:参数必须是 JSON 对象",
                    attempt, tool.example,
                ),
                isError = true,
            )
        }
        val (argsObj, problems) = DefaultTools.prepare(tool, raw)
        if (problems.isNotEmpty()) {
            return@withContext ToolOutcome(ToolErrors.forProblems(tool, problems, attempt), isError = true)
        }
        val args = argsObj.toString()
        // HOST 路由:交给会话宿主(记忆 / 子代理要仓库、会话与 UI 回调)。
        // 宿主返回 null 说明它不认识这个工具 —— 回未知工具信封,绝不静默成功。
        if (tool.route == ToolRoute.HOST) {
            val hosted = try {
                host?.executeHostTool(call, attempt)
            } catch (e: CancellationException) {
                // 用户停止(或宿主协程被取消)不能改写成一张「工具失败」信封:那会让主循环
                // 以为还能继续,把轮次接着跑下去 —— 取消必须原样穿透执行器
                throw e
            } catch (e: Exception) {
                errorFor(tool.name, e, attempt)
            }
            return@withContext hosted
                ?: ToolOutcome(ToolErrors.unknownTool(call.name, DefaultTools.names(tools)), isError = true)
        }
        // DATA 路由:注册表里有、分派表里没有 = 声明与实现分叉(测试会红,这里兜底不静默)
        val impl = dispatch[call.name] ?: return@withContext ToolOutcome(
            ToolErrors.internalError(tool.name, "工具 " + tool.name + " 没有执行实现", attempt),
            isError = true,
        )
        val outcome = try {
            impl(args)
        } catch (e: CancellationException) {
            throw e // 用户停止:同上,取消穿透执行器,由操作协程统一收尾
        } catch (e: Exception) {
            errorFor(tool.name, e, attempt)
        }
        // 统一兜底:任何错误分支在「同一工具第 2 次失败」时都要劝换路(各分支不一定拿得到计数)
        if (outcome.isError) {
            outcome.copy(content = ToolErrors.escalateIfNeeded(outcome.content, attempt))
        } else {
            outcome
        }
    }

    /**
     * 工具失败 → 有处方的错误信封:
     * HTTP 状态码分开给下一步(401/403 别重试、404 先核对名字、429 等一会儿、5xx 可重试一次),
     * 超时与其它异常也有各自的处方;堆栈只进日志、不进模型上下文。
     */
    private fun errorFor(tool: String, e: Throwable, attempt: Int): ToolOutcome {
        // 工具特有的下一步建议:通用处方之外再给一条「怎么找对目标」
        val hint = when (tool) {
            "github_get_file", "github_get_readme" ->
                "先核对 owner/repo/path:可用 github_search_repositories 搜到正确仓库名与路径"
            "github_list_branches", "github_create_branch", "github_commit_files" ->
                "先核对 owner/repo 与分支名:github_list_branches 能列出真实存在的分支"
            "github_create_pull_request" ->
                "先确认 head 分支上已有提交,且 base 分支名正确(github_list_branches 可查)"
            "web_fetch" -> "链接可能已失效或站点拒绝抓取:换一个来源,或先用 web_search 找新链接"
            "github_search_repositories" -> "搜索接口不可用时可改用 web_search 查该项目主页"
            else -> ""
        }
        val envelope = when (e) {
            is ApiError.Http -> if (tool in writeTools && e.code in authCodes) {
                // 写工具撞鉴权:99% 是没连账号或 token 失效 —— 直接说这一步要用户配合,
                // 而不是回一句「HTTP 403」让模型自己猜(它猜不出来,只会重试)
                ToolErrors.report(
                    ToolErrors.Kind.AUTH, tool,
                    "GitHub 拒绝了这次写操作(HTTP " + e.code + "),通常是还没连接 Git 账号或授权已失效",
                    "让用户到「设置 → 集成 → Git 账号」完成授权后再试;没有授权的仓库只能读不能写",
                    attempt, hint,
                )
            } else {
                ToolErrors.http(tool, e.code, e.body.take(160), attempt, hint)
            }
            is ApiError.Timeout -> ToolErrors.report(
                ToolErrors.Kind.NETWORK, tool, "请求超时",
                "等一会儿重试一次;仍超时就换其它来源或直接告诉用户没取到", attempt,
            )
            is ApiError.NotConfigured -> ToolErrors.report(
                ToolErrors.Kind.INTERNAL, tool, e.userMessage,
                "该能力当前不可用(缺少配置);换其它工具完成", attempt,
            )
            else -> ToolErrors.internalError(
                tool,
                ((e as? ApiError)?.userMessage ?: e.message ?: e::class.java.simpleName).take(160),
                attempt,
            )
        }
        return ToolOutcome(envelope, isError = true)
    }

    /** 兜底:必填参数缺失(正常路径由 [DefaultTools.validate] 拦住,这里防执行器被单独调用)。 */
    private fun missing(tool: String, param: String): ToolOutcome = ToolOutcome(
        ToolErrors.report(
            ToolErrors.Kind.MISSING_PARAM, tool, "必填参数 " + param + " 缺失",
            "补上 " + param + " 后重试一次", 1, DefaultTools.find(tool)?.example.orEmpty(),
        ),
        isError = true,
    )

    /**
     * 任务完成声明:模型用它在目标达成后结束本轮循环(Cline 的 attempt_completion 同构)。
     * 这里不调外部能力,只把 summary 作为执行结果回填,让卡片与协议消息都有据可查。
     */
    private fun completion(argsJson: String): ToolOutcome {
        val summary = try {
            JSONObject(argsJson.ifBlank { "{}" }).optString("summary")
        } catch (e: Exception) {
            ""
        }
        return ToolOutcome(if (summary.isBlank()) "任务已标记完成。" else "任务已标记完成:" + summary.take(500))
    }

    /* ---------------- 联网搜索(Exa 主路 + 免钥降级) ---------------- */

    /** 一条搜索结果(格式化后交给模型与卡片展示)。 */
    private data class SearchHit(
        val title: String,
        val url: String,
        val snippet: String,
        /** 发布日期(仅日期部分);Exa 不一定给,新闻类查询算它有价值。 */
        val published: String = "",
    )

    /**
     * 联网搜索。
     *
     * 主路是真实搜索 API(Exa,[EXA_SEARCH_URL]):配置了密钥就用它,拿到的是
     * **标题 + 链接 + 与查询相关的命中片段**(highlights),而不是某百科页面的一段摘要。
     *
     * 三条降级路径(结果里会说明为什么没走完整搜索):
     * 1. 未配置密钥 → 免钥通道(DuckDuckGo Instant Answer);
     * 2. 密钥被端点拒 / 限流 / 服务不可用 → 免钥通道 + 原因(用户能据此去修密钥);
     * 3. 主路返回空结果 → 免钥通道补一次。
     *
     * 为什么不直接回错误信封:搜索是「查资料」的第一步,一条启发式失败就让整轮停下
     * 对用户毫无价值;降级结果即便不完整,也带着可以继续追的链接(web_fetch 接着读)。
     * 只有**两条通道都拿不到东西**时才升级成错误信封(见 [fallbackSearch])。
     * 取消(CancellationException)原样穿透 —— 用户停止不能被降级逻辑吞掉。
     */
    private suspend fun webSearch(argsJson: String): ToolOutcome {
        val q = JSONObject(argsJson).optString("query").trim()
        if (q.isEmpty()) return missing("web_search", "query")
        val key = searchKey.apiKey()
        if (key.isBlank()) {
            return fallbackSearch(q, note = NO_KEY_NOTE)
        }
        val hits = try {
            exaSearch(q, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 端点抛出的一切(401/429/5xx/超时)都降级:原因写进结果,用户看得到、模型也不至于重试同一把密钥
            return fallbackSearch(
                q,
                note = "(搜索 API 暂不可用(" + searchFailureReason(e) +
                    "),已降级到免钥通道;可在「设置 → 集成 → 联网搜索」检查密钥)",
                primary = e,
            )
        }
        if (hits.isEmpty()) {
            return fallbackSearch(q, note = "(搜索 API 没有返回结果,以下为免钥通道的结果)")
        }
        return ToolOutcome(formatHits(q, hits))
    }

    /**
     * Exa 搜索:自然语言查询 + highlights(每条结果按查询挑出最相关的片段)。
     * 只看 `results[]`,别的字段(成本、耗时)不进模型上下文。
     */
    private fun exaSearch(q: String, key: String): List<SearchHit> {
        val body = JSONObject()
            .put("query", q)
            // type=auto:Exa 自己选检索方式(关键词/语义),比固定 neural 在「版本号/报错文本」类查询上稳
            .put("type", "auto")
            .put("numResults", EXA_RESULTS)
            .put("contents", JSONObject().put("highlights", true))
            .toString()
        val req = Request.Builder()
            .url(exaEndpoint)
            .header("x-api-key", key)
            .header("Accept", "application/json")
            .header("User-Agent", FreebuffApi.USER_AGENT)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(200))
            val arr = JSONObject(text).optJSONArray("results") ?: JSONArray()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.text("url").ifBlank { o.text("id") }
                if (url.isBlank()) return@mapNotNull null
                val excerpts = o.optJSONArray("highlights")
                    ?.let { h -> (0 until h.length()).mapNotNull { (h.opt(it) as? String)?.takeIf { s -> s.isNotBlank() } } }
                    .orEmpty()
                SearchHit(
                    title = o.text("title").ifBlank { url },
                    url = url,
                    snippet = excerpts.joinToString(" … ").replace(Regex("\\s+"), " "),
                    published = o.text("publishedDate").take(10),
                )
            }
        }
    }

    /**
     * 免钥通道(DuckDuckGo Instant Answer):只有主题摘要 + 相关条目,覆盖不了普通关键词,
     * 所以它现在只当降级,/web_search 的主路是 Exa。
     *
     * 只在**两条通道都不通**时升级成错误信封([primary] 非空说明主路也失败过):
     * 那时手上一条结果、一条链接都没有,给模型留一段空话不如给一张带处方的信封 ——
     * 它会去 web_fetch 已知链接或换关键词,而不是把同样的搜索再发一遍。
     */
    private fun fallbackSearch(q: String, note: String, primary: Exception? = null): ToolOutcome {
        val req = Request.Builder()
            .url(fallbackEndpoint + "?q=" + urlEncode(q) + "&format=json&no_html=1&skip_disambig=1")
            .header("User-Agent", FreebuffApi.USER_AGENT)
            .get()
            .build()
        val sb = StringBuilder(note).append("\n联网搜索「").append(q).append("」:")
        var found = false
        try {
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(200))
                val o = JSONObject(text)
                o.optString("AbstractText").takeIf { it.isNotBlank() }?.let {
                    sb.append("\n").append(it.take(600))
                    found = true
                }
                val related = o.optJSONArray("RelatedTopics") ?: JSONArray()
                var n = 0
                for (i in 0 until related.length()) {
                    if (n >= 5) break
                    val t = related.optJSONObject(i) ?: continue
                    val txt = t.optString("Text")
                    val url = t.optString("FirstURL")
                    if (txt.isBlank() || url.isBlank()) continue
                    sb.append("\n").append(n + 1).append(". ").append(txt.take(160)).append("  ").append(url)
                    n++
                    found = true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 降级通道也失败:主路也失败过就没什么可给的了 → 错误信封;否则(未配置密钥 / 主路空结果)
            // 仍是一条非错误结果,末尾说明两条路都没拿到内容 + 下一步
            if (primary == null) {
                return ToolOutcome(
                    sb.append("\n降级通道也失败了(").append(searchFailureReason(e))
                        .append(");可直接用 web_fetch 打开已知链接,或换关键词再试。").toString(),
                )
            }
            return ToolOutcome(
                ToolErrors.report(
                    kind = searchErrorKind(primary),
                    tool = "web_search",
                    problem = "主路搜索 API 失败(" + searchFailureReason(primary) + ")," +
                        "免钥降级通道也失败了(" + searchFailureReason(e) + "),这次没有任何结果",
                    fix = "换一个更短、更具体的关键词重试一次,或直接用 web_fetch 打开已知链接",
                    example = "{\"query\":\"okhttp 5 migration\"}",
                ),
                isError = true,
            )
        }
        if (!found) sb.append("\n找不到「").append(q).append("」的条目:换个关键词,或用 web_fetch 打开已知链接。")
        return ToolOutcome(sb.toString())
    }

    /** 搜索失败按状态码分级:密钥错要用户去修,限流等一会儿,其余算服务不可用。 */
    private fun searchErrorKind(e: Throwable): ToolErrors.Kind = when (e) {
        is ApiError.Http -> when {
            e.code == 401 || e.code == 403 -> ToolErrors.Kind.AUTH
            e.code == 429 -> ToolErrors.Kind.RATE_LIMIT
            else -> ToolErrors.Kind.NETWORK
        }
        else -> ToolErrors.Kind.NETWORK
    }

    /**
     * 取字符串字段,把 JSON null(org.json 会拼成字面量 "null")归为空白。
     * 不处理的话,`title: null` 的结果会以标题「null」出现在结果里;`url: null` 更糟 ——
     * 它会被当成「有链接」的命中。
     */
    private fun JSONObject.text(key: String): String =
        optString(key).takeIf { it.isNotBlank() && it != "null" }.orEmpty()

    /** 降级原因只给短标签:不带 URL、不带响应体,避免把密钥或大段 HTML 带进上下文。 */
    private fun searchFailureReason(e: Throwable): String = when (e) {
        is ApiError.Http -> "HTTP " + e.code
        is ApiError.Timeout -> "请求超时"
        is ApiError.NotConfigured -> "缺少配置"
        else -> (e as? ApiError)?.userMessage ?: e.message ?: e::class.java.simpleName
    }

    /** 主路结果的统一格式:序号 + 标题(+ 日期) + 链接 + 命中片段。 */
    private fun formatHits(q: String, hits: List<SearchHit>): String {
        val sb = StringBuilder("联网搜索「").append(q).append("」:")
        hits.forEachIndexed { i, h ->
            sb.append("\n").append(i + 1).append(". ").append(h.title.take(140))
            if (h.published.isNotBlank()) sb.append(" (").append(h.published).append(")")
            sb.append("\n   ").append(h.url)
            if (h.snippet.isNotBlank()) sb.append("\n   ").append(h.snippet.take(280))
        }
        return sb.toString()
    }

    /* ---------------- 网页抓取 ---------------- */

    private fun webFetch(argsJson: String): ToolOutcome {
        val url = JSONObject(argsJson).optString("url").trim()
        if (url.isEmpty()) return missing("web_fetch", "url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolOutcome(
                ToolErrors.report(
                    ToolErrors.Kind.BAD_VALUE, "web_fetch",
                    "url「" + url.take(80) + "」缺少协议头",
                    "url 必须以 http:// 或 https:// 开头", 1,
                    "web_fetch({\"url\": \"https://example.com/post\"})",
                ),
                isError = true,
            )
        }
        val req = Request.Builder().url(url)
            .header("User-Agent", FreebuffApi.USER_AGENT)
            .get().build()
        val body = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiError.Http(r.code, r.body?.string()?.take(200).orEmpty())
            r.body?.string().orEmpty()
        }
        return ToolOutcome(htmlToText(body).take(4000))
    }

    /* ---------------- GitHub(匿名 REST,公开数据) ---------------- */

    private fun ghSearchRepos(argsJson: String): ToolOutcome {
        val a = JSONObject(argsJson)
        val q = a.optString("query").trim()
        if (q.isEmpty()) return missing("github_search_repositories", "query")
        val limit = a.optInt("limit", 5).coerceIn(1, 10)
        val req = Request.Builder()
            .url("https://api.github.com/search/repositories?q=" + urlEncode(q) + "&per_page=" + limit)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", FreebuffApi.USER_AGENT)
            .get().build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(200))
            val items = JSONObject(text).optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) return ToolOutcome("GitHub 未搜到「" + q + "」相关仓库。")
            val sb = StringBuilder("GitHub 仓库搜索「").append(q).append("」:\n")
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                sb.append(i + 1).append(". ").append(o.optString("full_name"))
                    .append(" ⭐").append(o.optInt("stargazers_count"))
                    .append(" · ").append(o.optString("language").ifBlank { "未知语言" })
                    .append(" · ").append(o.optString("description").take(100))
                    .append("\n")
            }
            return ToolOutcome(sb.toString().trim())
        }
    }

    private suspend fun ghGetFile(argsJson: String): ToolOutcome {
        val a = JSONObject(argsJson)
        val owner = a.optString("owner").trim()
        val repo = a.optString("repo").trim()
        val path = a.optString("path").trim()
        if (owner.isBlank() || repo.isBlank() || path.isBlank()) {
            val absent = listOf("owner" to owner, "repo" to repo, "path" to path)
                .filter { it.second.isBlank() }.joinToString("、") { it.first }
            return ToolOutcome(
                ToolErrors.report(
                    ToolErrors.Kind.MISSING_PARAM, "github_get_file", "必填参数 " + absent + " 缺失",
                    "owner / repo / path 三个都要给(仓库名与文件路径分开写)", 1,
                    DefaultTools.find("github_get_file")?.example.orEmpty(),
                ),
                isError = true,
            )
        }
        val ref = a.optString("ref").trim()
        // 已连接 Git 账号:走带鉴权的 contents API —— 私有仓库与 raw 匿名通道读不到
        val token = tokens.token()
        if (token.isNotBlank()) {
            return ToolOutcome(github.fileContent(token, owner, repo, path, ref).orThrow().take(4000))
        }
        val url = buildString {
            append("https://raw.githubusercontent.com/")
            append(owner).append('/').append(repo).append('/')
            if (ref.isNotBlank()) append(urlEncode(ref)) else append("HEAD")
            append('/').append(path.split("/").joinToString("/") { urlEncode(it) })
        }
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                return ToolOutcome(
                    ToolErrors.http(
                        "github_get_file", r.code, "", 1,
                        hint = "核对 " + owner + "/" + repo + "/" + path +
                            " 是否写错;仓库名可用 github_search_repositories 搜",
                    ),
                    isError = true,
                )
            }
            return ToolOutcome(text.take(4000))
        }
    }

    private suspend fun ghGetReadme(argsJson: String): ToolOutcome {
        val a = JSONObject(argsJson)
        val owner = a.optString("owner").trim()
        val repo = a.optString("repo").trim()
        if (owner.isBlank() || repo.isBlank()) return missing("github_get_readme", "owner/repo")
        return ghGetFile(
            JSONObject().put("owner", owner).put("repo", repo).put("path", "README.md").toString(),
        )
    }

    /* ---------------- GitHub 写路径(需要已授权的 repo scope) ---------------- */

    /**
     * 列分支:公开仓库匿名可读,已连接账号时可读私有仓库 —— 所以**不要求 token**。
     * 它是写流程的前置查询(先从哪拉、往哪提交),计划模式下也保留可用。
     */
    private suspend fun ghListBranches(argsJson: String): ToolOutcome {
        val a = JSONObject(argsJson)
        val owner = a.optString("owner").trim()
        val repo = a.optString("repo").trim()
        if (owner.isBlank() || repo.isBlank()) return missing("github_list_branches", "owner/repo")
        val limit = a.optInt("limit", 30)
        val branches = github.listBranches(tokens.token(), owner, repo, limit).orThrow()
        if (branches.isEmpty()) return ToolOutcome("仓库 " + owner + "/" + repo + " 没有任何分支(或不可访问)。")
        val sb = StringBuilder(owner).append('/').append(repo).append(" 的分支:" + branches.size + " 个\n")
        branches.forEach { sb.append("- ").append(it.name).append(" @").append(it.sha.take(7)).append("\n") }
        return ToolOutcome(sb.toString().trim())
    }

    /** 新建分支:from 缺省用仓库默认分支;起点的 sha 先查出来,不猜。 */
    private suspend fun ghCreateBranch(argsJson: String): ToolOutcome {
        val a = JSONObject(argsJson)
        val owner = a.optString("owner").trim()
        val repo = a.optString("repo").trim()
        val branch = a.optString("branch").trim()
        if (owner.isBlank() || repo.isBlank() || branch.isBlank()) {
            return missing("github_create_branch", "owner/repo/branch")
        }
        GitRef.branchProblem(branch)?.let { p ->
            return badValue("github_create_branch", "新分支名「" + branch + "」" + p, "换一个合法分支名,如 agent/fix-typo")
        }
        gitTokenOrNull("github_create_branch")?.let { return it }
        val token = tokens.token()
        val from = a.optString("from").trim().ifBlank { github.defaultBranch(token, owner, repo).orThrow() }
        GitRef.branchProblem(from)?.let { p ->
            return badValue("github_create_branch", "起点分支「" + from + "」" + p, "from 要用仓库里真实存在的分支名(github_list_branches 可查)")
        }
        val sha = github.branchSha(token, owner, repo, from).orThrow()
        github.createBranch(token, owner, repo, branch, sha).orThrow()
        return ToolOutcome(
            "已从 " + from + " 新建分支 " + branch + "(" + owner + "/" + repo + ",起点 " + sha.take(7) + ")。" +
                "下一步用 github_commit_files 往这个分支提交。",
        )
    }

    /**
     * 多文件原子提交。
     *
     * 两条安全边界在此强制(不靠提示词自觉):
     * 1. **不直推默认分支** —— 目标分支就是仓库默认分支时直接拒绝,让它先建分支;
     * 2. **路径不许逃出仓库**([GitRef.pathProblem],端上先判,不用等 GitHub 回 404)。
     */
    private suspend fun ghCommitFiles(argsJson: String): ToolOutcome {
        val a = JSONObject(argsJson)
        val owner = a.optString("owner").trim()
        val repo = a.optString("repo").trim()
        val branch = a.optString("branch").trim()
        val message = a.optString("message").trim()
        if (owner.isBlank() || repo.isBlank() || branch.isBlank() || message.isBlank()) {
            return missing("github_commit_files", "owner/repo/branch/message")
        }
        GitRef.branchProblem(branch)?.let { p ->
            return badValue("github_commit_files", "目标分支名「" + branch + "」" + p, "用 github_list_branches 里的真实分支名")
        }
        val arr = a.optJSONArray("files") ?: return missing("github_commit_files", "files")
        val edits = mutableListOf<GithubApi.FileEdit>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return badValue(
                "github_commit_files", "files[" + i + "] 不是对象",
                "每个元素要写成 {\"path\": \"src/a.kt\", \"content\": \"文件全文\"}",
            )
            val path = o.optString("path").trim()
            val content = o.optString("content")
            if (path.isBlank() || content.isBlank()) {
                return badValue(
                    "github_commit_files", "files[" + i + "] 缺少 path 或 content",
                    "每个文件都要给仓库内相对路径与完整内容",
                )
            }
            GitRef.pathProblem(path)?.let { p ->
                return badValue("github_commit_files", "文件路径「" + path + "」" + p, "path 是仓库内的相对路径,如 src/main.kt")
            }
            edits += GithubApi.FileEdit(path = path, content = content)
        }
        if (edits.isEmpty()) return missing("github_commit_files", "files")
        gitTokenOrNull("github_commit_files")?.let { return it }
        val token = tokens.token()
        // 不直推默认分支:写默认分支会把「可评审的改动」变成「既成事实」。
        // 默认分支查不到(网络/权限问题)就不阻塞提交 —— 真正的拒绝留给 GitHub 的推送保护。
        val default = (github.defaultBranch(token, owner, repo) as? ApiResult.Ok)?.data
        if (default != null && default == branch) {
            return badValue(
                "github_commit_files",
                "目标分支 " + branch + " 是仓库默认分支,端上不允许直接往里提交",
                "先 github_create_branch 建一个分支,提交后再 github_create_pull_request 开 PR",
            )
        }
        val res = github.commitFiles(token, owner, repo, branch, message, edits).orThrow()
        val paths = edits.joinToString("、") { it.path }
        return ToolOutcome(
            "已提交 " + res.files + " 个文件到 " + owner + "/" + repo + "@" + branch + ":" + paths + "\n" +
                "提交 " + res.sha.take(7) + (if (res.url.isBlank()) "" else " " + res.url) + "\n" +
                "下一步可用 github_create_pull_request 开 PR。",
        )
    }

    /** 开 PR:head 上必须已有提交,否则 GitHub 会回「没有差异」。 */
    private suspend fun ghCreatePullRequest(argsJson: String): ToolOutcome {
        val a = JSONObject(argsJson)
        val owner = a.optString("owner").trim()
        val repo = a.optString("repo").trim()
        val head = a.optString("head").trim()
        val base = a.optString("base").trim()
        val title = a.optString("title").trim()
        if (owner.isBlank() || repo.isBlank() || head.isBlank() || base.isBlank() || title.isBlank()) {
            return missing("github_create_pull_request", "owner/repo/head/base/title")
        }
        GitRef.branchProblem(head)?.let { p ->
            return badValue("github_create_pull_request", "head 分支名「" + head + "」" + p, "head 是你提交改动所在的分支")
        }
        GitRef.branchProblem(base)?.let { p ->
            return badValue("github_create_pull_request", "base 分支名「" + base + "」" + p, "base 通常是 main 或 master")
        }
        gitTokenOrNull("github_create_pull_request")?.let { return it }
        val token = tokens.token()
        val pr = github.createPullRequest(
            token, owner, repo, head, base, title,
            body = a.optString("body"), draft = a.optBoolean("draft", false),
        ).orThrow()
        return ToolOutcome(
            "已开 PR #" + pr.number + ":" + title + "\n" + pr.url + "\n(" + head + " → " + base + ")",
        )
    }

    /** 写操作的共同前置:没连 Git 账号就别往下走(回一张指名去处的处方)。 */
    private suspend fun gitTokenOrNull(tool: String): ToolOutcome? =
        if (tokens.token().isBlank()) {
            ToolOutcome(
                ToolErrors.report(
                    ToolErrors.Kind.AUTH, tool,
                    "还没有连接 Git 账号,写操作无法执行(读公开仓库不受影响)",
                    "让用户到「设置 → 集成 → Git 账号」完成授权;期间改用只读工具,或直接把要改的内容告诉用户",
                ),
                isError = true,
            )
        } else {
            null
        }

    /** 端上先判出来的取值问题(分支名/路径),比 GitHub 的 422/404 更指向具体怎么改。 */
    private fun badValue(tool: String, problem: String, fix: String, attempt: Int = 1): ToolOutcome = ToolOutcome(
        ToolErrors.report(
            ToolErrors.Kind.BAD_VALUE, tool, problem, fix, attempt,
            DefaultTools.find(tool)?.example.orEmpty(),
        ),
        isError = true,
    )

    /** [ApiResult] → 值或抛出归一的 [ApiError](上层 [errorFor] 再转成带处方的信封)。 */
    private fun <T> ApiResult<T>.orThrow(): T = when (this) {
        is ApiResult.Ok -> data
        is ApiResult.Err -> throw error
    }

    /* ---------------- 本地工具 ---------------- */

    /** 四则运算:递归下降解析,避免 eval 类安全/兼容问题。 */
    private fun calculator(argsJson: String): ToolOutcome {
        val expr = JSONObject(argsJson).optString("expression").trim()
        if (expr.isEmpty()) return missing("calculator", "expression")
        return try {
            val v = CalcParser(expr).parse()
            val out = if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) {
                v.toLong().toString()
            } else {
                v.toString()
            }
            ToolOutcome(expr + " = " + out)
        } catch (e: Exception) {
            ToolOutcome(
                ToolErrors.report(
                    ToolErrors.Kind.BAD_VALUE, "calculator",
                    "表达式「" + expr.take(60) + "」无法解析",
                    "只支持数字与 + - * / 与括号;换一个合法算式重试", 1,
                    "calculator({\"expression\": \"(12+8)*3.5\"})",
                ),
                isError = true,
            )
        }
    }

    private fun currentTime(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss (EEEE)", java.util.Locale.CHINA)
            .format(java.util.Date())

    /* ---------------- 杂项 ---------------- */

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** HTML → 纯文本:去 script/style 与标签,解实体,压缩空白。 */
    private fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?is)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>"), "\n")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
        s = s.replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex("\\n\\s*\\n+"), "\n")
        return s.trim()
    }
}

/** 四则运算递归下降解析器:expr → term → factor → number/(expr) 一遍扫描。 */
private class CalcParser(private val src: String) {
    private var i = 0

    fun parse(): Double {
        val v = expr()
        skipWs()
        if (i < src.length) throw IllegalArgumentException("多余字符:" + src.substring(i))
        return v
    }

    private fun expr(): Double {
        var v = term()
        while (true) {
            skipWs()
            when (src.getOrNull(i)) {
                '+' -> { i++; v += term() }
                '-' -> { i++; v -= term() }
                else -> return v
            }
        }
    }

    /** 乘除:除零得 Infinity/NaN,由调用方格式化输出(不抛异常,行为可预期)。 */
    private fun term(): Double {
        var v = factor()
        while (true) {
            skipWs()
            when (src.getOrNull(i)) {
                '*' -> { i++; v *= factor() }
                '/' -> { i++; v /= factor() }
                else -> return v
            }
        }
    }

    private fun factor(): Double {
        skipWs()
        when (src.getOrNull(i)) {
            '(' -> {
                i++
                val v = expr()
                skipWs()
                if (src.getOrNull(i) != ')') throw IllegalArgumentException("括号不匹配")
                i++
                return v
            }
            '+' -> { i++; return factor() }
            '-' -> { i++; return -factor() }
            else -> {
                val start = i
                while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                if (start == i) throw IllegalArgumentException("缺少数字")
                return src.substring(start, i).toDouble()
            }
        }
    }

    private fun skipWs() {
        while (i < src.length && src[i].isWhitespace()) i++
    }
}
