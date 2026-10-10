package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 写工具调用摘要:确认弹窗与卡片靠它说清「要改哪个仓库的哪个分支的哪些文件」。 */
class ToolCallSummaryTest {

    @Test
    fun `提交摘要给出仓库 分支 文件与提交信息`() {
        val s = toolCallSummary(
            "github_commit_files",
            """
            {"owner":"me","repo":"notes","branch":"agent/x","message":"fix: 拼写",
             "files":[{"path":"README.md","content":"# hi\n"},{"path":"src/a.kt","content":"class A"}]}
            """.trimIndent(),
        )!!
        assertEquals("me/notes", s.target)
        assertEquals("agent/x", s.branch)
        assertEquals(listOf("README.md", "src/a.kt"), s.files)
        assertEquals("fix: 拼写", s.note)
        assertTrue("提交会改远端内容", s.writes)
    }

    @Test
    fun `别名与字符串数字也能归一出摘要`() {
        // 弱模型常见写法:仓库写 repository、文件写 file、多文件包成单对象
        val s = toolCallSummary(
            "github_commit_files",
            """{"账号":"me","repository":"notes","ref":"agent/x","msg":"fix","files":[{"file":"a.txt","text":"hi"}]}""",
        )!!
        assertEquals("me/notes", s.target)
        assertEquals("agent/x", s.branch)
        assertEquals(listOf("a.txt"), s.files)
    }

    @Test
    fun `开 PR 摘要给出源分支与目标分支`() {
        val s = toolCallSummary(
            "github_create_pull_request",
            """{"owner":"me","repo":"notes","head":"agent/x","base":"main","title":"fix: 拼写"}""",
        )!!
        assertEquals("agent/x", s.branch)
        assertEquals("main", s.base)
        assertEquals("fix: 拼写", s.note)
        assertTrue(s.writes)
    }

    @Test
    fun `新建分支摘要说明从哪拉`() {
        val s = toolCallSummary(
            "github_create_branch",
            """{"owner":"me","repo":"notes","branch":"agent/x","from":"develop"}""",
        )!!
        assertEquals("agent/x", s.branch)
        assertEquals("develop", s.note)
        assertTrue(s.writes)
    }

    @Test
    fun `列分支是读操作 摘要标成非写入`() {
        // 字符串 "50" 会被归一成整数 50(说明书里的上下界 1~100 生效)
        val s = toolCallSummary("github_list_branches", """{"owner":"me","repo":"notes","limit":"50"}""")!!
        assertEquals("50", s.note)
        assertTrue("只读工具没有文件语义", s.files.isEmpty())
        assertFalse("列分支不改远端内容", s.writes)
    }

    @Test
    fun `参数不合法时不编摘要`() {
        // 缺 files:与其编一个好看的摘要,不如让卡片显示原始参数
        assertNull(toolCallSummary("github_commit_files", """{"owner":"me","repo":"notes"}"""))
        assertNull(toolCallSummary("github_commit_files", "not json"))
        // 非写工具没有摘要
        assertNull(toolCallSummary("calculator", """{"expression":"1+1"}"""))
        assertNull(toolCallSummary("no_such_tool", "{}"))
    }
}
