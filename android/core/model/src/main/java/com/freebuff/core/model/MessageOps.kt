package com.freebuff.core.model

/**
 * 消息级操作的纯逻辑:重新生成回复、编辑用户消息后重发、单条删除。
 *
 * 三件事的本质都是「对消息列表做一次改写」——保留哪些、改写哪条、丢弃哪些。
 * 提成纯函数的好处:
 * - 不启动 Android/Room 就能单测(尤其是截断边界,写错会把用户历史吃掉);
 * - 三个入口共用一份规则,不会各自长出一套截断逻辑。
 * ViewModel 只负责把 [Plan] 落到仓库(见 ChatViewModel.applyPlan)。
 */
object MessageOps {

    /** 改写目标:哪条消息、改写成什么正文。 */
    data class Rewrite(val id: String, val text: String)

    /** 一次改写的执行计划:先改写([rewrite],可选),再删除([dropIds])。 */
    data class Plan(val rewrite: Rewrite? = null, val dropIds: List<String> = emptyList())

    /**
     * 能否重新生成:只对「最后一条消息、且它是 agent 回复」成立。
     *
     * 为什么限定最后一条:中间的回复后面还压着用户的追问,重跑这一条会让新回答与
     * 那些追问对不上(它们问的是旧回答)。要改中间的,正确做法是编辑那条用户消息重发。
     */
    fun canRegenerate(messages: List<ChatMsg>, id: String): Boolean {
        val last = messages.lastOrNull() ?: return false
        return last.id == id && last.role == "agent"
    }

    /**
     * 重新生成的计划:删掉这条回复,上下文(用户消息与更早历史)原样保留 ——
     * 所以不产生新的用户消息,调用方删完后直接补一条占位回复重跑。
     */
    fun planRegenerate(messages: List<ChatMsg>, id: String): Plan? =
        if (canRegenerate(messages, id)) Plan(dropIds = listOf(id)) else null

    /**
     * 编辑重发的计划:改写这条用户消息的正文,并丢弃它之后的**全部**消息。
     *
     * 为什么要截断而不是原地改字:后面的回复与工具执行都是针对旧问题产生的,
     * 留着它们等于让模型看到「新问题 + 旧答案」,下一轮必然自我矛盾。
     *
     * 只允许 user 消息(改 agent 回复没有「重发」的语义,那叫重新生成)。
     * 空文本返回 null:与发送入口一致,空消息不进入历史。
     */
    fun planEditAndResend(messages: List<ChatMsg>, id: String, newText: String): Plan? {
        val i = messages.indexOfFirst { it.id == id }
        if (i < 0 || messages[i].role != "user") return null
        val text = newText.trim()
        if (text.isBlank()) return null
        return Plan(
            rewrite = Rewrite(id, text),
            // 被编辑的消息本身保留;它后面的一条都不留(含正在生成的占位 —— 调用方会重新补一条)
            dropIds = messages.drop(i + 1).map { it.id },
        )
    }

    /**
     * 单条删除的计划:只丢这一条,不牵连它的回复。
     * 用户点的是某一条消息的「删除」,那就精确删这一条;连带清理会删掉他没想到的东西。
     */
    fun planDelete(messages: List<ChatMsg>, id: String): Plan? =
        if (messages.any { it.id == id }) Plan(dropIds = listOf(id)) else null

    /**
     * 追加消息的起始序号 = 当前最大序号 + 1。
     *
     * 这里必须用「最大序号」而不是「消息条数」:单条删除会留下序号空洞,
     * 按条数取号会与已存在的行撞号 —— 两条消息同序号后,会话内顺序不再确定
     * (Room 按 sort 排序,同值时顺序由存储引擎决定),历史可能显示错乱。
     */
    fun nextSort(existing: List<Long>): Long = (existing.maxOrNull() ?: -1L) + 1L
}
