package com.freebuff.core.data.repository

/**
 * 流式正文的落库节流(纯逻辑,便于单测)。
 *
 * 为什么要它:模型按 token 吐字,早期实现是**每个增量写一次库**。一次写入要读回整行、重写
 * JSON 列,再触发 Room 的失效通知;界面又订阅着这个消息流,于是「界面刷新频率」被绑死在
 * 「模型吐字速度」上 —— 弱网/慢端点下写入堆积,流式反而比一次性回复还卡。
 *
 * 现在的规则:
 * - 增量先进缓冲;**每 [intervalMs] 或攒到 [maxPendingChars] 才落一次库**(首块立即落,气泡不会晚出现);
 * - 局部边界(写工具卡片、换状态牌、轮次收尾)由调用方显式 flush,顺序不会乱;
 * - 验收靠 [drain] 取走内容,节流器自己不碰数据库、不碰时间。
 *
 * 时钟由调用方传入(毫秒时间戳):这样节流规则可以在纯 JVM 单测里逐帧验证,不用等真实时间。
 */
class StreamTextThrottle(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val maxPendingChars: Int = DEFAULT_MAX_PENDING_CHARS,
) {

    private val pending = StringBuilder()
    private var lastFlushAt: Long? = null

    /** 缓冲里待落库的字符数(排空后为 0)。 */
    val pendingLength: Int get() = pending.length

    /**
     * 追加一个流式增量。
     * @return true = 现在就该 [drain] 落库(首块、超过间隔、或攒到上限)
     */
    fun append(chunk: String, nowMs: Long): Boolean {
        if (chunk.isEmpty()) return false
        pending.append(chunk)
        val last = lastFlushAt
        return last == null ||
            nowMs - last >= intervalMs ||
            pending.length >= maxPendingChars
    }

    /** 取走待落库的内容并记下这次落库时刻;没有待写内容时不动时钟、返回空串。 */
    fun drain(nowMs: Long): String {
        if (pending.isEmpty()) return ""
        val out = pending.toString()
        pending.setLength(0)
        lastFlushAt = nowMs
        return out
    }

    companion object {
        /**
         * 落库间隔:人眼对 80ms 的延迟无感(约 12 帧/秒的刷新),
         * 而写入次数相对「每 token 一次」降了一到两个数量级。
         */
        const val DEFAULT_INTERVAL_MS = 80L

        /** 缓冲上限:长块(端点一次吐一大段)不再等计时器,攒够就落。 */
        const val DEFAULT_MAX_PENDING_CHARS = 800
    }
}
