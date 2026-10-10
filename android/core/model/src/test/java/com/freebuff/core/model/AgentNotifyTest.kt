package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长任务通知的文案规则:渠道/长度/首行/回落,以及进度措辞与界面一致。 */
class AgentNotifyTest {

    @Test
    fun `运行中标题带会话名并对空名回落`() {
        assertEquals("正在生成 · 排查构建失败", AgentNotify.runningTitle("排查构建失败"))
        assertEquals("正在生成 · Freebuff", AgentNotify.runningTitle(""))
        assertEquals("正在生成 · Freebuff", AgentNotify.runningTitle("   "))
    }

    @Test
    fun `超长标题截断并留省略号`() {
        val long = "会话".repeat(60)
        val title = AgentNotify.runningTitle(long)
        assertTrue(title.endsWith("…"))
        assertEquals(40, title.removePrefix("正在生成 · ").length)
    }

    @Test
    fun `运行中正文空值回落为连接提示`() {
        assertEquals("连接模型并开始生成…", AgentNotify.runningText(""))
        assertEquals("第 2 轮 · 调用 web_search", AgentNotify.runningText("  第 2 轮 · 调用 web_search  "))
    }

    @Test
    fun `完成标题区分成功与失败`() {
        assertEquals("回复已完成", AgentNotify.doneTitle(true))
        assertEquals("本轮没有完成", AgentNotify.doneTitle(false))
    }

    @Test
    fun `完成正文取回复首个非空行`() {
        val text = AgentNotify.doneText("排查构建失败", "\n\n  第一步已经定位到 Gradle 版本 \n第二行不该出现")
        assertEquals("排查构建失败 · 第一步已经定位到 Gradle 版本", text)
        assertFalse(text.contains("第二行"))
    }

    @Test
    fun `回复为空时只显示会话名不留分隔符`() {
        assertEquals("排查构建失败", AgentNotify.doneText("排查构建失败", ""))
        assertEquals("排查构建失败", AgentNotify.doneText("排查构建失败", "\n \n"))
        // 会话名也空(极端情况)时退化为空串,而不是「 · 」
        assertEquals("", AgentNotify.doneText("", ""))
    }

    @Test
    fun `超长完成正文被截断`() {
        val text = AgentNotify.doneText("t", "x".repeat(500))
        assertEquals(140, text.length)
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun `进度措辞与消息步骤一致`() {
        assertEquals("第 2 轮 · 正在生成回答…", AgentNotify.progressDetail(2, emptyList()))
        assertEquals(
            "第 1 轮 · 调用 web_search、calculator",
            AgentNotify.progressDetail(1, listOf("web_search", "calculator")),
        )
        // 与界面状态牌用的是同一套措辞(MsgSteps),不另造一套说法
        assertEquals(MsgSteps.round(3) + " · " + MsgSteps.calling(emptyList()), AgentNotify.progressDetail(3, emptyList()))
    }
}
