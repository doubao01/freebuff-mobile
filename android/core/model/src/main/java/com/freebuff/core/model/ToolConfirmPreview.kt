package com.freebuff.core.model

import org.json.JSONArray

/**
 * 确认弹窗的「你要批的是什么」摘要 —— **纯函数,不拼任何中文**。
 *
 * 为什么单独抽一层:写工具会真的改仓库,用户点「允许」前必须看清目标与范围。参数 JSON 里
 * 虽然什么都有(owner/repo/branch/files),但要用户自己从一段 JSON 里读出来;而且参数可能
 * 用的是别名(`ref`/`source`/`from`、files 里的 `file`),读起来更糊。
 *
 * 这里只做**结构化**(仓库、分支、文件清单、提交信息),显示文案由有 `Context` 的 UI 侧
 * 从资源组装 —— 与 `AgentNotify` 同款做法:i18n 的边界留在 UI,纯模块继续好单测。
 *
 * 非写工具返回 null:确认框回落到「只显示参数 JSON」的现有形态。
 */
data class ConfirmPreview(
    /** `owner/repo`。 */
    val repo: String,
    /** 目标分支(PR 为 head)。 */
    val branch: String = "",
    /** PR 的目标分支(空 = 不显示箭头)。 */
    val base: String = "",
    /** 要落地的文件路径(提交类)。 */
    val files: List<String> = emptyList(),
    /** 提交信息 / PR 标题。 */
    val message: String = "",
) {
    companion object {
        /** 展示上限:预览是给人扫一眼的,再多就直接去看参数 JSON。 */
        const val MAX_FILES = 12

        fun of(tool: String, argsJson: String): ConfirmPreview? {
            val o = when (val r = JsonArgs.parse(argsJson)) {
                is JsonArgs.Result.Ok -> r.obj
                is JsonArgs.Result.Invalid -> return null
            }
            fun pick(vararg names: String): String {
                for (n in names) {
                    val v = o.optString(n).trim()
                    if (v.isNotBlank()) return v
                }
                return ""
            }
            val owner = pick("owner", "user", "org", "organization")
            val repoName = pick("repo", "repository", "project")
            if (owner.isBlank() || repoName.isBlank()) return null
            val repo = owner + "/" + repoName
            return when (tool) {
                "github_create_branch" -> ConfirmPreview(
                    repo = repo,
                    branch = pick("branch", "name", "new_branch"),
                    base = pick("from", "base", "from_branch", "source"),
                )
                "github_commit_files" -> ConfirmPreview(
                    repo = repo,
                    branch = pick("branch", "ref"),
                    files = filePaths(o.optJSONArray("files")),
                    message = pick("message", "commit_message", "msg"),
                )
                "github_create_pull_request" -> ConfirmPreview(
                    repo = repo,
                    branch = pick("head", "source", "from"),
                    base = pick("base", "target", "into"),
                    message = pick("title", "name"),
                )
                else -> null
            }
        }

        /** 取 files 数组里的路径(条目可能是对象;`path` 与 `file`/`filename` 别名都认)。 */
        private fun filePaths(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                if (out.size >= MAX_FILES) break
                val item = arr.optJSONObject(i) ?: continue
                val p = listOf("path", "file", "filename", "filepath")
                    .map { item.optString(it).trim() }
                    .firstOrNull { it.isNotBlank() }
                    ?: continue
                out += p
            }
            return out
        }
    }
}
