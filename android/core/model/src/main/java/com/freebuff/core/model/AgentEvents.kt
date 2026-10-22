package com.freebuff.core.model

/* ---------------- Agent 事件流(与 agent-architecture.md §4 契约对应) ---------------- */

/**
 * 后端会话事件的 App 端表示。按到达顺序处理即可——工具串行执行保证
 * tool_call / tool_result 成对有序,无需排序或合并。
 */
sealed interface AgentEvent {
    /** 文本增量:追加渲染当前助手消息(Markdown)。 */
    data class Text(val chunk: String) : AgentEvent

    /**
     * 思考(思维链)增量:渲染为可折叠的「思考过程」块,不计入正文。
     * 来源字段:`delta.reasoning_content`(事实标准)/ `reasoning` / `thinking`。
     */
    data class Reasoning(val chunk: String) : AgentEvent

    /**
     * 工具调用开始或更新。OpenAI 兼容流的 arguments 增量会按 callId 多次到达,
     * App 端按 callId upsert 卡片,以最后一次到达的 input 为准。
     */
    data class ToolCall(val callId: String, val tool: String, val input: String) : AgentEvent

    /** 工具收尾。isError=true 时卡片标红,output 为归因信息。 */
    data class ToolResult(val callId: String, val output: String, val isError: Boolean = false) : AgentEvent

    /** 后端 error 事件(参数校验/权限/分类错误已自带归因语境),原样展示。 */
    data class Failure(val message: String) : AgentEvent

    /**
     * 非致命提示:适配层/网关的说明(如「该端点不接受工具定义,已按纯对话继续」)。
     * 与 [Failure] 不同,它不终止本轮;UI 以提示条形式展示,调用方按 [Kind] 决定后续行为。
     */
    data class Notice(val message: String, val kind: Kind = Kind.INFO) : AgentEvent {
        enum class Kind {
            /** 一般信息。 */
            INFO,

            /** 端点不接受工具定义:本会话后续轮次不要再带 tools。 */
            TOOLS_DROPPED,

            /** 端点不认识思考参数:本会话后续轮次不要再带思考字段。 */
            REASONING_DROPPED,
        }
    }

    /** 子代理流式输出(如 context-pruner / thinker 的后台过程)。 */
    data class SubagentChunk(val agent: String, val chunk: String) : AgentEvent

    /** 回合结束:消息落定;suggest_followups 卡片在此之后渲染。 */
    object EndTurn : AgentEvent

    /**
     * 一轮流结束时模型请求的全部工具调用(完整参数 JSON)。
     * 仅在请求携带 tools 且模型触发 function calling 时出现;agent 循环据此执行并回传。
     */
    data class Calls(val calls: List<ToolCallReq>) : AgentEvent
}

/**
 * 消息内嵌的工具卡片(随消息持久化)。state: waiting | running | done | error | reused。
 *
 * [todos] 是 `write_todos` 的结构化清单:卡片不只存一段文字摘要,而是把逐项状态一起落库 ——
 * 重开会话仍能按原样渲染「哪几项做完了」。其它工具恒为空表。
 */
data class ToolCard(
    val callId: String,
    val tool: String,
    val input: String = "",
    val output: String = "",
    val state: String = "running",
    val todos: List<TodoList.Item> = emptyList(),
) {
    val isRunning: Boolean get() = state == "running"
    val isError: Boolean get() = state == "error"

    /** 完全重复的调用:结果复用上一次,未真正执行(模型重放时避免重复副作用与等待)。 */
    val isReused: Boolean get() = state == "reused"
    /** 等待用户确认(CONFIRM 级工具已拦截,弹窗未决)。 */
    val isWaiting: Boolean get() = state == "waiting"
    /** 子代理卡片:tool 以 subagent: 前缀携带代理名。 */
    val subagentName: String? get() = if (tool.startsWith(SUBAGENT_PREFIX)) tool.removePrefix(SUBAGENT_PREFIX) else null

    companion object {
        const val SUBAGENT_PREFIX = "subagent:"
    }
}

/**
 * 常见工具的展示名。
 *
 * 分两层:端侧**已实现**的工具直接读注册表([AgentTool.label]) —— 加工具时只改 `Tools.kt`,标签跟着走;
 * 下面这张表服务**后端 agent 运行时**的工具名(agent-architecture.md §1.1 的 36 个,端侧还没实现),
 * 属于远端会话事件流里的展示名;两处同名时注册表优先。
 */
private val BACKEND_TOOL_LABELS: Map<String, String> = mapOf(
    "read_files" to "读取文件",
    "read_subtree" to "读取目录树",
    "list_directory" to "列目录",
    "glob" to "文件匹配",
    "find_files" to "查找文件",
    "code_search" to "搜索代码",
    "read_docs" to "读取文档",
    "read_url" to "读取网页",
    "write_file" to "写入文件",
    "str_replace" to "编辑文件",
    "apply_patch" to "应用补丁",
    "propose_write_file" to "提议写入",
    "propose_str_replace" to "提议编辑",
    "run_terminal_command" to "运行命令",
    "run_file_change_hooks" to "触发变更钩子",
    "write_todos" to "更新任务清单",
    "add_subgoal" to "新增子目标",
    "update_subgoal" to "更新子目标",
    "create_plan" to "制定计划",
    "think_deeply" to "深入思考",
    "spawn_agents" to "派发子代理",
    "spawn_agent_inline" to "后台代理",
    "lookup_agent_info" to "查询代理信息",
    "task_completed" to "任务完成",
    "web_search" to "联网搜索",
    "web_fetch" to "抓取网页",
    "github_search_repositories" to "搜索仓库",
    "github_get_file" to "读取文件",
    "github_get_readme" to "读取 README",
    "calculator" to "计算器",
    "current_time" to "当前时间",
    "end_turn" to "结束回合",
    "ask_user" to "等待输入",
    "suggest_followups" to "推荐后续",
    "set_output" to "设置输出",
    "browser_logs" to "浏览器日志",
    "skill" to "调用技能",
    "gravity_index" to "代码索引",
)

/** 展示名:注册表(端侧已实现)优先,其次后端工具名表,最后回退原名。 */
fun toolDisplayName(tool: String): String =
    DefaultTools.find(tool)?.label?.takeIf { it.isNotBlank() } ?: BACKEND_TOOL_LABELS[tool] ?: tool

/** 工具图标:按类别取字形,未识别用通用齿轮。 */
fun toolGlyph(tool: String): String = when (tool) {
    "read_files", "read_subtree", "list_directory", "glob", "find_files", "read_docs" -> "▤"
    "code_search", "web_search", "gravity_index" -> "⌕"
    "read_url", "web_fetch" -> "⌘"
    "github_search_repositories", "github_get_file", "github_get_readme", "github_list_branches" -> "⌥"
    "github_create_branch" -> "⑂"
    "github_commit_files" -> "✎"
    "github_create_pull_request" -> "⇪"
    "save_memory" -> "❖"
    "memory_recall" -> "❒"
    "write_file", "str_replace", "apply_patch", "propose_write_file", "propose_str_replace" -> "✎"
    "run_terminal_command", "run_file_change_hooks" -> "›_"
    "spawn_agents", "spawn_agent_inline", "spawn_subagent" -> "✷"
    "write_todos", "add_subgoal", "update_subgoal", "create_plan", "think_deeply" -> "☰"
    "ask_user", "suggest_followups" -> "?"
    "end_turn", "task_completed" -> "✓"
    else -> "⚙"
}
