package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 确认弹窗摘要:用户点「允许」前要知道在批什么(仓库 / 分支 / 文件 / 提交信息)。 */
class ToolConfirmPreviewTest {

    @Test
    fun `提交多个文件能读出仓库 分支 文件清单与提交信息`() {
        val p = ConfirmPreview.of(
            "github_commit_files",
            """
            {"owner":"me","repo":"notes","branch":"agent/fix",
             "message":"fix: 拼写",
             "files":[{"path":"README.md","content":"# hi"},{"path":"src/a.kt","content":"class A"}]}
            """.trimIndent(),
        )!!
        assertEquals("me/notes", p.repo)
        assertEquals("agent/fix", p.branch)
        assertEquals(listOf("README.md", "src/a.kt"), p.files)
        assertEquals("fix: 拼写", p.message)
    }

    @Test
    fun `别名也能读出来 —— 模型常把 branch 写成 ref 把 path 写成 file`() {
        val p = ConfirmPreview.of(
            "github_commit_files",
            """{"owner":"me","repo":"notes","ref":"agent/x","msg":"chore","files":[{"file":"a.txt","content":"x"}]}""",
        )!!
        assertEquals("agent/x", p.branch)
        assertEquals(listOf("a.txt"), p.files)
        assertEquals("chore", p.message)
    }

    @Test
    fun `建分支给出目标与起点 PR 给出 head 到 base`() {
        val branch = ConfirmPreview.of(
            "github_create_branch",
            """{"owner":"me","repo":"notes","new_branch":"agent/x","from":"main"}""",
        )!!
        assertEquals("me/notes", branch.repo)
        assertEquals("agent/x", branch.branch)
        assertEquals("main", branch.base)

        val pr = ConfirmPreview.of(
            "github_create_pull_request",
            """{"owner":"me","repo":"notes","source":"agent/x","target":"main","name":"fix: 拼写"}""",
        )!!
        assertEquals("agent/x", pr.branch)
        assertEquals("main", pr.base)
        assertEquals("fix: 拼写", pr.message)
    }

    @Test
    fun `只读工具与坏参数不给摘要 —— 确认框回落成只看参数 JSON`() {
        assertNull(ConfirmPreview.of("github_get_file", """{"owner":"me","repo":"notes","path":"README.md"}"""))
        assertNull(ConfirmPreview.of("github_commit_files", "not json"))
        // 缺 owner/repo 时不能拼出一个半截的「/repo」出来
        assertNull(ConfirmPreview.of("github_commit_files", """{"repo":"notes","branch":"x"}"""))
    }

    @Test
    fun `文件清单有展示上限`() {
        val files = (1..20).joinToString(",") { """{"path":"f$it.txt","content":"x"}""" }
        val p = ConfirmPreview.of(
            "github_commit_files",
            """{"owner":"me","repo":"notes","branch":"b","message":"m","files":[$files]}""",
        )!!
        assertEquals(ConfirmPreview.MAX_FILES, p.files.size)
        assertTrue(p.files.first() == "f1.txt")
    }
}
