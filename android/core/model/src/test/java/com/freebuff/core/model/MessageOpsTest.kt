package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 消息级操作的计划规则:重新生成只限末条、编辑重发的截断边界、单条删除,以及追加取号。 */
class MessageOpsTest {

    private fun user(id: String, text: String) = ChatMsg(id, "user", text = text)
    private fun agent(id: String, text: String = "好的") = ChatMsg(id, "agent", text = text)

    // u1 / a1 / u2 / a2 —— 中间回复后面压着用户的追问
    private val thread = listOf(user("u1", "第一个问题"), agent("a1"), user("u2", "追问"), agent("a2"))

    @Test
    fun `只有最后一条回复能重新生成`() {
        assertTrue(MessageOps.canRegenerate(thread, "a2"))
        // a1 后面还有 u2/a2:重跑 a1 会让新回答与「追问」对不上
        assertFalse(MessageOps.canRegenerate(thread, "a1"))
        // 正在请求中的场景:最后一条是用户消息(回复还没落库)
        assertFalse(MessageOps.canRegenerate(listOf(user("u1", "x")), "u1"))
        assertFalse(MessageOps.canRegenerate(emptyList(), "a1"))
    }

    @Test
    fun `重新生成只丢弃那一条回复`() {
        val plan = MessageOps.planRegenerate(thread, "a2")
        assertEquals(listOf("a2"), plan?.dropIds)
        // 关键:不产生改写,也不丢弃用户消息 —— 上下文原样保留
        assertNull(plan?.rewrite)
    }

    @Test
    fun `重新生成对非末条返回空计划`() {
        assertNull(MessageOps.planRegenerate(thread, "a1"))
        assertNull(MessageOps.planRegenerate(thread, "u2"))
    }

    @Test
    fun `编辑重发丢弃该条之后的全部消息`() {
        val plan = MessageOps.planEditAndResend(thread, "u1", "换个问法")
        assertEquals(MessageOps.Rewrite("u1", "换个问法"), plan?.rewrite)
        // 它后面的 a1/u2/a2 都是针对旧问题产生的,全部丢弃
        assertEquals(listOf("a1", "u2", "a2"), plan?.dropIds)
    }

    @Test
    fun `编辑最后一条用户消息时不丢弃任何消息`() {
        // 上一轮失败/被停止时的情形:历史以用户消息结尾,重发只是补一条回复
        val msgs = listOf(user("u1", "问题"), agent("a1"), user("u2", "再问"))
        val plan = MessageOps.planEditAndResend(msgs, "u2", "再问一次")
        assertEquals(MessageOps.Rewrite("u2", "再问一次"), plan?.rewrite)
        assertEquals(emptyList<String>(), plan?.dropIds)
    }

    @Test
    fun `编辑重发去掉首尾空白并把空文本判为不可用`() {
        val plan = MessageOps.planEditAndResend(thread, "u1", "  换个问法  ")
        assertEquals("换个问法", plan?.rewrite?.text)
        // 与发送入口一致:空消息不进历史,否则会多出一条空用户轮
        assertNull(MessageOps.planEditAndResend(thread, "u1", "   "))
        assertNull(MessageOps.planEditAndResend(thread, "u1", ""))
    }

    @Test
    fun `编辑只对用户消息成立`() {
        assertNull(MessageOps.planEditAndResend(thread, "a2", "改回复"))
        assertNull(MessageOps.planEditAndResend(thread, "不存在", "x"))
    }

    @Test
    fun `单条删除只丢弃指定的一条`() {
        assertEquals(listOf("u2"), MessageOps.planDelete(thread, "u2")?.dropIds)
        assertEquals(listOf("a1"), MessageOps.planDelete(thread, "a1")?.dropIds)
        assertNull(MessageOps.planDelete(thread, "不存在"))
    }

    @Test
    fun `追加取号用最大序号加一而不是条数`() {
        assertEquals(0L, MessageOps.nextSort(emptyList()))
        assertEquals(3L, MessageOps.nextSort(listOf(0L, 1L, 2L)))
        // 删过中间一条(sort=1)之后:按条数会取到 2 与既有行撞号,必须取 max+1
        assertEquals(4L, MessageOps.nextSort(listOf(0L, 2L, 3L)))
        assertEquals(2L, MessageOps.nextSort(listOf(1L)))
    }
}
