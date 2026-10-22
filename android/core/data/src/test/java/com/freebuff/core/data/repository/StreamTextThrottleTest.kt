package com.freebuff.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式正文的落库节奏:首块立即可见、间隔内合并、攒够就落、验收靠 drain 取走。
 *
 * 时钟是参数,所以这些用例是确定的(不 sleep、不看真实时间)。
 */
class StreamTextThrottleTest {

    @Test
    fun `首块立即落库, 气泡不会晚出现`() {
        val t = StreamTextThrottle(intervalMs = 80)

        assertTrue("第一个增量就该写", t.append("你好", nowMs = 1_000))
        assertEquals("你好", t.drain(1_000))
        assertEquals(0, t.pendingLength)
    }

    @Test
    fun `间隔内的增量合并成一次写入`() {
        val t = StreamTextThrottle(intervalMs = 80)
        t.append("A", 1_000); t.drain(1_000)

        // 10ms / 30ms 后各来一块:都不到 80ms,不该触发落库
        assertFalse(t.append("B", 1_010))
        assertFalse(t.append("C", 1_030))
        assertEquals("两块都还在缓冲里", 2, t.pendingLength)

        // 80ms 到点:一次把这两块写出去
        assertTrue(t.append("D", 1_090))
        assertEquals("BCD", t.drain(1_090))
    }

    @Test
    fun `攒到上限不等计时器就直接落库`() {
        val t = StreamTextThrottle(intervalMs = 10_000, maxPendingChars = 10)
        t.append("start", 1_000); t.drain(1_000)

        assertFalse(t.append("1234", 1_001))
        assertTrue("攒够上限就该落", t.append("5678901", 1_002))
        assertEquals("12345678901", t.drain(1_002))
    }

    @Test
    fun `空增量与空缓冲都不算写入`() {
        val t = StreamTextThrottle(intervalMs = 80)

        assertFalse("空块不触发落库", t.append("", 1_000))
        assertEquals("没有待写内容时 drain 是空操作", "", t.drain(1_000))
        assertEquals(0, t.pendingLength)
    }

    @Test
    fun `按 token 流入时的写入次数是确定的, 比 token 数少一个数量级`() {
        val t = StreamTextThrottle(intervalMs = 80)
        var nowMs = 10_000L
        var writes = 0

        // 模拟 2 秒的流式输出:每 10ms 一个增量,共 200 个(真实弱网/慢端点只会更密)
        repeat(200) { i ->
            nowMs += 10
            if (t.append("tok$i ", nowMs)) {
                t.drain(nowMs)
                writes++
            }
        }

        // 首块立即落,之后每 80ms 合并一次:1 + 24 = 25 次,而不是 200 次
        assertEquals("写入次数要被闸门压住", 25, writes)
        assertTrue("最后不到一个周期的尾巴仍在缓冲,由收尾 flush 落库", t.pendingLength > 0)
    }

    @Test
    fun `边界 flush 后重新计时, 不会把已写内容重复写一遍`() {
        val t = StreamTextThrottle(intervalMs = 80)
        t.append("轮次一", 1_000)
        assertEquals("轮次一", t.drain(1_000)) // 工具卡片/状态牌之前的边界 flush

        // 边界刚过,紧接着的增量按新间隔计算
        assertFalse(t.append("轮次二", 1_020))
        assertTrue(t.append("继续", 1_100))
        assertEquals("轮次二继续", t.drain(1_100))
        assertEquals("再 drain 不重复写", "", t.drain(1_120))
    }
}
