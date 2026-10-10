package com.freebuff.core.model

/**
 * 长任务通知的文案与标识(纯函数)。
 *
 * 为什么把文案放在 core:model 而不是 :app 的服务里:通知是给人看的一句话,长度/换行/回落规则
 * 值得单测(超长会话名、多行回复、空回复),而 Android 侧的 `Notification` 组装没法在 JVM 上测。
 * 于是这里只管「说什么」,`:app` 的 `AgentForegroundService` 只管「怎么说出去」。
 */
object AgentNotify {

    /** 常驻通知渠道:任务运行中。低优先级 + 静音 —— 它只是一个「还在跑」的状态牌。 */
    const val CHANNEL_RUNNING = "freebuff_agent_running"

    /** 完成通知渠道:任务跑完/失败时提醒一次(默认优先级,有声音)。 */
    const val CHANNEL_DONE = "freebuff_agent_done"

    /** 常驻通知 id(固定:同一时刻只有一个 agent 任务在跑)。 */
    const val ID_RUNNING = 4201

    /** 完成通知 id(固定:重复完成覆盖上一次,不在通知栏堆积)。 */
    const val ID_DONE = 4202

    /** 通知标题/正文的长度上限:太长的会话名与回复会撑爆通知栏(尤其锁屏)。 */
    private const val MAX_TITLE = 40
    private const val MAX_TEXT = 140

    /**
     * 固定说法的默认值(中文)。
     *
     * 多语言的做法:`:app` 的 `AgentForegroundService` 有 `Context`,把资源里的说法当参数传进来;
     * 这里保留可直接调用的默认值,好处是这套规则(截断/首行/回落)依旧能在 JVM 上单测,
     * 而通知本身不会在英文设备上冒出中文外壳。
     */
    const val LABEL_RUNNING = "正在生成"
    const val LABEL_CONNECTING = "连接模型并开始生成…"
    const val LABEL_DONE_OK = "回复已完成"
    const val LABEL_DONE_FAIL = "本轮没有完成"

    /** 运行中通知标题:会话名(空则回落产品名)。runningLabel 由调用方按当前语言传入。 */
    fun runningTitle(sessionTitle: String, runningLabel: String = LABEL_RUNNING): String =
        runningLabel + " · " + cut(sessionTitle.trim().ifBlank { "Freebuff" }, MAX_TITLE)

    /** 运行中通知正文:最近一次进度(如「第 2 轮 · 调用 联网搜索、读取文件」);为空时回落连接提示。 */
    fun runningText(detail: String, connecting: String = LABEL_CONNECTING): String =
        cut(detail.trim().ifBlank { connecting }, MAX_TEXT)

    /** 完成通知标题:成功/失败分开说清楚,不写「任务已结束」这种含糊话。 */
    fun doneTitle(
        ok: Boolean,
        okLabel: String = LABEL_DONE_OK,
        failLabel: String = LABEL_DONE_FAIL,
    ): String = if (ok) okLabel else failLabel

    /**
     * 完成通知正文:会话名 + 回复首行(取第一个非空行,单行展示)。
     * 正文为空(模型什么都没说)时只显示会话名,不留一个孤零零的分隔符。
     */
    fun doneText(sessionTitle: String, summary: String): String {
        val head = summary.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return cut(
            listOf(sessionTitle.trim(), head.trim()).filter { it.isNotBlank() }.joinToString(" · "),
            MAX_TEXT,
        )
    }

    /** 进度文案:轮次 + 本轮在做什么(复用消息步骤的措辞,通知与界面说法一致)。 */
    fun progressDetail(round: Int, toolNames: List<String>): String =
        MsgSteps.round(round) + " · " + MsgSteps.calling(toolNames)

    /** 截断到 [max] 字符,末位留一个省略号(不改变短文本)。 */
    private fun cut(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1) + "…"
}
