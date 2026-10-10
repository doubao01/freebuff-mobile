package com.freebuff.core.model

import org.json.JSONObject

/**
 * 端侧子代理协议层(spawn_subagent,见 docs/subagent-design.md):
 * - 编排工具定义与参数解析(主模型 → 端侧)
 * - 三种内置只读剧本(agent_type → system prompt / 工具白名单 / 输出预算)
 * - 深度固定 1:子代理工具集不含 spawn_subagent;CONFIRM 级工具在子代理内不可用
 *
 * 纯函数层,无协程/Android 依赖;单测见 SubagentTest。
 */
object Subagent {

    /** 编排工具名(主循环据此分派)。 */
    const val TOOL_NAME = "spawn_subagent"

    /** 单轮最多派出的子代理数(超出部分立即 error 回填,不排队)。 */
    const val MAX_PER_ROUND = 3

    /** 并发上限(P0 串行,P1 用;常量先行)。 */
    const val MAX_CONCURRENT = 2

    /** 单个子代理的总时限(ms),含其全部轮次与工具调用。 */
    const val DEADLINE_MS = 120_000L

    /** 子代理流式空闲看门狗阈值:比主循环(120s)更激进。 */
    const val IDLE_TIMEOUT_MS = 60_000L

    /** 子代理内 LLM 调用失败的最大重试次数(比主循环 2 次更保守)。 */
    const val MAX_RETRIES = 1

    /** 子代理循环内工具执行的最大轮数。 */
    const val MAX_TOOL_ROUNDS = 3

    /**
     * 一次子代理最多发起的 LLM 请求数(兜底上限)。
     * 正常路径是「工具轮数用尽 → 去掉 tools 收敛一次 → 拿到结论」,即 MAX_TOOL_ROUNDS + 1;
     * 余量留给模型空回复等异常,避免任何情况下出现无界循环。
     */
    const val MAX_REQUESTS = MAX_TOOL_ROUNDS + 2

    /** 子代理类型:剧本名。 */
    const val TYPE_RESEARCHER = "researcher"
    const val TYPE_CODE_READER = "code_reader"
    const val TYPE_ANALYST = "analyst"

    /** 各类型的工具白名单(只读;不含 spawn_subagent —— 深度固定 1)。 */
    val TOOL_WHITELIST: Map<String, Set<String>> = mapOf(
        TYPE_RESEARCHER to setOf("web_search", "web_fetch"),
        TYPE_CODE_READER to setOf("github_get_file", "github_get_readme"),
        TYPE_ANALYST to setOf("calculator"),
    )

    /** agent_type 是否有效。 */
    fun isValidType(type: String): Boolean = TOOL_WHITELIST.containsKey(type)

    /**
     * 解析 spawn_subagent 的 arguments JSON。
     * @return 成功 → [Request];失败 → null(非法 agent_type/空 task 都算失败,调用方 error 回填)
     */
    fun parseRequest(argsJson: String): Request? {
        return try {
            val o = JSONObject(argsJson)
            val type = o.optString("agent_type").trim()
            val task = o.optString("task").trim()
            if (!isValidType(type) || task.isEmpty()) return null
            Request(
                type = type,
                task = task,
                context = o.optString("context").trim(),
                expectedOutput = o.optString("expected_output").trim(),
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 一次子代理委托(解析后的参数)。context 与 expected_output 在工具 schema 里都是可选的,
     * 缺省即空串(与 [parseRequest] 的取值一致)。
     */
    data class Request(
        val type: String,
        val task: String,
        val context: String = "",
        val expectedOutput: String = "",
    )

    /**
     * 子代理的 system 剧本:身份目标 + 工作方式 + 硬性约束 + 输出格式四段。
     * @param toolNames 实际可用的工具名 —— 由执行引擎按权限过滤后传入(用户把某工具设成
     *   需确认/禁止时,子代理的工具清单里就不该再有它,剧本也要跟着说实情)
     */
    fun systemPrompt(type: String, toolNames: List<String> = TOOL_WHITELIST[type].orEmpty().toList()): String {
        val tools = toolNames.joinToString("、")
        return buildString {
            appendLine("你是主 agent 派出的子代理,类型 $type,只负责完成被委托的单个子任务,不与用户直接对话。")
            appendLine("可用工具:$tools。围绕子任务少量多次地使用工具(最多 $MAX_TOOL_ROUNDS 轮),不要做子任务之外的探索。")
            appendLine("硬性约束:除了列出的工具外不得尝试任何其他工具;不得编造数据与来源;工具失败就如实说明,不要假装成功。")
            appendLine("输出格式:直接给出结论正文,分点陈述、精炼自包含(读者看不到你的工具过程);不要客套,不要复述任务。")
        }.trim()
    }

    /** 子代理的首条 user 消息:任务 + 最小背景 + 期望产出形态。 */
    fun userPrompt(req: Request): String = buildString {
        append("子任务:").append(req.task)
        if (req.context.isNotBlank()) append("\n背景(主 agent 提供的最小上下文):").append(req.context.take(2000))
        if (req.expectedOutput.isNotBlank()) append("\n期望产出:").append(req.expectedOutput)
    }

    /** 按类型取子代理可用的工具定义(只读白名单硬过滤;未知类型返回空)。 */
    fun toolsFor(type: String): List<AgentTool> {
        val allow = TOOL_WHITELIST[type] ?: return emptyList()
        return DefaultTools.ALL.filter { it.name in allow }
    }

    /**
     * 子代理结论回填主循环的格式:带来源标签(类型/轮数/用过的工具),让主模型知道
     * 这段文字来自一个只读子代理、以及它是靠什么得来的 —— 主模型据此决定是否需要复核。
     */
    fun formatResult(type: String, rounds: Int, toolsUsed: List<String>, text: String): String = buildString {
        append("[子代理 ").append(type).append(" 完成")
        if (rounds > 0) append(" · ").append(rounds).append(" 轮工具")
        if (toolsUsed.isNotEmpty()) append(" · ").append(toolsUsed.joinToString("、"))
        append("]\n").append(text.trim())
    }

    /**
     * 子代理失败回填:说清这一路没有结果,**并给主模型自己的替代路径** ——
     * 失败不该让主循环原地重派同一个子代理。
     */
    fun formatFailure(type: String, reason: String): String =
        "[子代理 " + type + " 未能完成] " + reason.take(200) +
            "\n怎么改: 这一路没有产出。改用 web_search / web_fetch / github_get_* 分步自行完成," +
            "或直接告知用户这部分暂时拿不到结果。"

    /** spawn_subagent 参数缺失/非法时给主模型的可修正错误文案。 */
    fun invalidArgsMessage(argsJson: String): String =
        "spawn_subagent 参数无效(需要 agent_type=$TYPE_RESEARCHER|$TYPE_CODE_READER|$TYPE_ANALYST 与非空 task)。原始参数:${argsJson.take(200)}"
}
