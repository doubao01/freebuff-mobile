package com.freebuff.core.model

/**
 * Git 引用与仓库内路径的合法性(纯函数,便于单测)。
 *
 * 为什么端上要先判一遍:GitHub 对非法引用名回的是 422「Invalid request」,
 * 对越界路径回 404 —— 这两种反馈对模型都没有指向性,它只会原地重发。端上先判出来,
 * 就能回一张带处方的错误信封(「分支名不能有空格,试试 agent/fix-typo」)。
 *
 * 规则取自 `git check-ref-format` 的可移植子集(不依赖本地 git 可执行文件):
 * 空、控制字符、空格、`~ ^ : ? * [ \`、`..`、`@{`、首尾 `/`、连续 `/`、
 * 结尾 `.` 或 `.lock`、以 `-` 开头 —— 这些都非法。
 */
object GitRef {

    /** 分支/标签名长度上限(与 GitHub 的 ref 长度限制取同一守卫值)。 */
    const val MAX_REF_LEN = 200

    /** 仓库内路径长度上限。 */
    const val MAX_PATH_LEN = 1000

    /** 是否可作为分支名(在 GitHub 上引用名规则比 git 略紧,这里取交集)。 */
    fun isValidBranch(name: String): Boolean = branchProblem(name) == null

    /** 分支名的问题描述(null = 合法);直接进错误信封的「问题」一行。 */
    fun branchProblem(name: String): String? {
        val n = name.trim()
        return when {
            n.isEmpty() -> "分支名不能为空"
            n.length > MAX_REF_LEN -> "分支名过长(超过 $MAX_REF_LEN 字符)"
            name.any { it.code < 0x20 || it.code == 0x7F } -> "分支名不能包含控制字符"
            name != n -> "分支名首尾不能有空白"
            name.contains(' ') -> "分支名不能包含空格(用 agent/fix-typo 这种写法)"
            name.any { it in "~^:?*[\\" } -> "分支名不能包含 ~ ^ : ? * [ \\ 这几个字符"
            name.contains("..") -> "分支名不能包含连续的 .."
            name.contains("@{") -> "分支名不能包含 @{"
            name.startsWith("/") || name.endsWith("/") -> "分支名不能以 / 开头或结尾"
            name.contains("//") -> "分支名不能包含连续的 //"
            name.startsWith("-") -> "分支名不能以 - 开头"
            name.endsWith(".") -> "分支名不能以 . 结尾"
            name.endsWith(".lock") -> "分支名不能以 .lock 结尾"
            name == "@" -> "分支名不能是单个 @"
            else -> null
        }
    }

    /** 是否可作为仓库内的文件路径(相对路径,不允许逃出仓库)。 */
    fun isValidRepoPath(path: String): Boolean = pathProblem(path) == null

    /** 路径的问题描述(null = 合法)。 */
    fun pathProblem(path: String): String? {
        val p = path.trim()
        val segs = p.split("/")
        return when {
            p.isEmpty() -> "文件路径不能为空"
            p.length > MAX_PATH_LEN -> "文件路径过长(超过 $MAX_PATH_LEN 字符)"
            p.any { it.code < 0x20 || it.code == 0x7F } -> "文件路径不能包含控制字符"
            p != path -> "文件路径首尾不能有空白"
            p.contains('\\') -> "文件路径要用 / 分隔,不要用 \\"
            p.startsWith("/") -> "文件路径必须是仓库内的相对路径,不能以 / 开头"
            segs.any { it == ".." } -> "文件路径不能包含 ..(不能写到仓库外)"
            segs.any { it == "." } -> "文件路径不能包含 . 段"
            // 结尾 / 先判:它比「连续 /」更具体,模型照这句话就能改对
            p.endsWith("/") -> "文件路径要指向文件,不能以 / 结尾"
            segs.any { it.isEmpty() } -> "文件路径不能包含连续的 /"
            else -> null
        }
    }
}
