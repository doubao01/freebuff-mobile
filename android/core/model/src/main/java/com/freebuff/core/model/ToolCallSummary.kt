package com.freebuff.core.model

import org.json.JSONObject

/**
 * 写工具的调用摘要:**只给结构,不给文案**。
 *
 * 为什么要单独抽:写操作是唯一「用户点「允许」之前必须看清要发生什么」的一类工具 ——
 * 确认弹窗与工具卡片上直接铺原始 JSON(以前的做法)等于让用户自己解析参数。
 * 但文案要跟随界面语言,而 core:model 没有 `Context`(见 docs/i18n-and-accessibility.md §2),
 * 所以这里只产出字段(target/branch/files/note),由有 Context 的一侧拼成人话。
 *
 * 参数一律走 [DefaultTools.prepare] 归一后再取:模型把 `file` 写成别名、把数字写成字符串,
 * 摘要仍然对得上(与执行器看到的是同一份归一结果)。参数不合法时返回 null ——
 * 不编摘要,卡片照旧显示原始参数,免得摘要比实际执行的东西更好看。
 */
data class ToolCallSummary(
    /** 目标:`owner/repo`。 */
    val target: String,
    /** 主分支:提交/新建分支的目标分支;开 PR 时是 head(源分支)。 */
    val branch: String,
    /** 开 PR 的目标分支(仅 PR 有值)。 */
    val base: String = "",
    /** 会被写入/涉及的文件路径(没有文件语义的工具为空)。 */
    val files: List<String> = emptyList(),
    /** 一句话说明:提交信息 / PR 标题 / 来源分支。 */
    val note: String = "",
    /** 是否真的会改远端内容(读工具为 false,用于弹窗措辞)。 */
    val writes: Boolean = false,
)

/** 写工具的调用摘要;非写工具或参数不合法时返回 null。 */
fun toolCallSummary(tool: String, argsJson: String): ToolCallSummary? {
    val spec = DefaultTools.find(tool) ?: return null
    val raw = when (val r = JsonArgs.parse(argsJson)) {
        is JsonArgs.Result.Ok -> r.obj
        is JsonArgs.Result.Invalid -> return null
    }
    val (a, problems) = DefaultTools.prepare(spec, raw)
    if (problems.isNotEmpty()) return null
    val target = a.optString("owner").trim() + "/" + a.optString("repo").trim()
    if (target == "/") return null
    fun s(k: String): String = a.optString(k).trim()

    return when (tool) {
        "github_list_branches" -> ToolCallSummary(
            target = target, branch = "", note = s("limit"),
        )
        "github_create_branch" -> ToolCallSummary(
            target = target, branch = s("branch"), note = s("from"), writes = true,
        )
        "github_commit_files" -> ToolCallSummary(
            target = target,
            branch = s("branch"),
            files = a.optJSONArray("files")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("path")?.takeIf { p -> p.isNotBlank() } }
            }.orEmpty(),
            note = s("message"),
            writes = true,
        )
        "github_create_pull_request" -> ToolCallSummary(
            target = target, branch = s("head"), base = s("base"), note = s("title"), writes = true,
        )
        else -> null
    }
}

/** 参数对象的只读小工具:给 UI 侧复用(避免各处重复 optString/trim 拼装)。 */
fun JSONObject.trimmed(key: String): String = optString(key).trim()
