package com.freebuff.core.data.repository

import com.freebuff.core.data.db.FreebuffDao
import com.freebuff.core.data.db.MessageEntity
import com.freebuff.core.data.db.SessionEntity
import com.freebuff.core.data.db.SettingEntity
import com.freebuff.core.data.db.SessionWithMessages
import com.freebuff.core.data.db.toDomain
import com.freebuff.core.data.db.toEntity
import com.freebuff.core.model.ChatMsg
import com.freebuff.core.model.MessageOps
import com.freebuff.core.model.MsgSteps
import com.freebuff.core.model.Session
import com.freebuff.core.model.uid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 被删除会话的快照。除会话内容外还带着原始列表位次 [sort],
 * 让「撤销删除」把会话放回原位,而不是当作新会话顶到最前。
 */
data class DeletedSession(val session: Session, val sort: Long)

/** 会话与消息仓库:Room 持久化,支持创建/追加/删除(可撤销)/清空。 */
@Singleton
class SessionRepository @Inject constructor(
    private val dao: FreebuffDao,
) {
    /**
     * 会话列表(只含会话行,不带消息)。
     *
     * 首页/设置页只需要标题、预览、时间;它们用这条流而不是「会话 + 全部消息」的聚合查询 ——
     * 否则流式回复的每一次消息写入都会让整张列表连全库消息一起重查(见 MessageEntity 的索引注释)。
     * 需要消息的页面用 [observeSession](单个会话)或 [get]。
     */
    val sessionSummaries: Flow<List<Session>> = dao.observeSessions()
        .map { list -> list.map { it.toDomain(emptyList()) } }
        .distinctUntilChanged()

    /**
     * 订阅**单个**会话与它的消息(会话页专用)。
     * 只依赖这一条会话的行与消息:别的会话在写、别的消息在落库,都不会重查本会话的历史。
     */
    fun observeSession(id: String): Flow<Session?> = dao.observeSessionWithMessages(id)
        .map { it?.toDomain() }
        .distinctUntilChanged()

    /**
     * 正文命中关键词的会话 id。搜索是按需动作(见 HomeViewModel):
     * 列表本身不为搜索常驻加载全库消息。
     */
    suspend fun sessionIdsMatching(keyword: String): Set<String> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptySet()
        return dao.sessionIdsMatching(kw).toSet()
    }

    suspend fun get(id: String): Session? = dao.sessionWithMessages(id)?.toDomain()

    /** 会话里最后一条指定角色的消息(流式写入用:只取一行,不加载整个会话)。 */
    suspend fun lastMessage(sessionId: String, role: String): ChatMsg? =
        dao.lastMessageOfRole(sessionId, role)?.toDomain()

    /** 创建空会话,返回带 id 的 Session(记下创建时刻,列表按它分时间组)。 */
    suspend fun create(title: String): Session {
        val id = uid()
        val maxSort = dao.allSessions().maxOfOrNull { it.sort } ?: 0L
        val now = System.currentTimeMillis()
        dao.upsertSession(SessionEntity(id, title, "刚刚", "", maxSort + 1, now))
        return Session(id, title, "刚刚", "", emptyList(), now)
    }

    /** 追加消息并刷新会话预览。返回更新后的会话。 */
    suspend fun appendMessages(sessionId: String, messages: List<ChatMsg>): Session? {
        val cur = dao.session(sessionId) ?: return null
        // 取号用「最大序号 + 1」而不是条数:单条删除会留下序号空洞(见 MessageOps.nextSort)
        val existing = dao.sessionWithMessages(sessionId)?.messages.orEmpty().map { it.sort }
        val start = MessageOps.nextSort(existing)
        val entities = messages.mapIndexed { i, m -> m.toEntity(sessionId, start + i) }
        dao.upsertMessages(entities)
        // 预览取「最后一条用户消息」;这批里没有用户消息时退到批内末条:列表页不再加载消息,
        // 预览列就是首页唯一的正文来源(见 SessionRepository.sessionSummaries)
        val lastText = messages.lastOrNull { it.role == "user" }?.text?.takeIf { it.isNotBlank() }
            ?: messages.lastOrNull()?.text.orEmpty()
        val preview = if (lastText.isNotBlank()) lastText.take(30) + (if (lastText.length > 30) "…" else "") else cur.preview
        dao.upsertSession(cur.copy(preview = preview))
        return dao.sessionWithMessages(sessionId)?.toDomain()
    }

    /**
     * 替换某条消息(流式回复写入 —— 全应用写入最密的路径)。
     *
     * 只查这一条的排序号,不再为一次替换加载整个会话的消息并解析其 JSON:
     * 节流后仍每秒写数次,旧写法把「写一个 token」变成了 O(会话消息数)。
     */
    suspend fun replaceMessage(sessionId: String, messageId: String, updated: ChatMsg) {
        val sort = dao.messageSort(sessionId, messageId) ?: return
        dao.upsertMessages(listOf(updated.toEntity(sessionId, sort)))
    }

    /** 批量删除消息(消息级操作:单条删除、编辑重发时的截断)。序号留空洞,不重排。 */
    suspend fun deleteMessages(sessionId: String, ids: List<String>) {
        if (ids.isNotEmpty()) dao.deleteMessageIds(sessionId, ids)
    }

    /**
     * 按剩余消息重算会话预览。
     *
     * 消息被删/被改写后,列表页预览可能还指着已经不存在的正文(实测观感是「点进去找不到那句话」)。
     * 规则与 [appendMessages] 一致:取最后一条用户消息;会话里没有用户消息时退到末条。
     */
    suspend fun refreshPreview(sessionId: String) {
        val cur = dao.session(sessionId) ?: return
        val msgs = dao.sessionWithMessages(sessionId)?.messages.orEmpty().sortedBy { it.sort }
        val src = msgs.lastOrNull { it.role == "user" }?.text ?: msgs.lastOrNull()?.text.orEmpty()
        val preview = if (src.isBlank()) "" else src.take(30) + (if (src.length > 30) "…" else "")
        if (preview != cur.preview) dao.upsertSession(cur.copy(preview = preview))
    }

    suspend fun updateTitle(sessionId: String, title: String) {
        dao.session(sessionId)?.let { dao.upsertSession(it.copy(title = title)) }
    }

    suspend fun delete(sessionId: String) {
        dao.deleteMessages(sessionId)
        dao.deleteSession(sessionId)
    }

    /**
     * 删除会话(连带消息),返回删除前的快照供「撤销」使用。
     * 会话不存在时返回 null —— 调用方据此不展示空撤销条。
     */
    suspend fun deleteWithSnapshot(sessionId: String): DeletedSession? {
        val row = dao.session(sessionId) ?: return null
        val session = dao.sessionWithMessages(sessionId)?.toDomain() ?: return null
        dao.deleteMessages(sessionId)
        dao.deleteSession(sessionId)
        return DeletedSession(session, row.sort)
    }

    /**
     * 恢复被删除的会话:沿用原 [DeletedSession.sort] 回到列表原位,
     * 消息按 0..n 重写序号,内容与删除前一致。
     */
    suspend fun restore(deleted: DeletedSession) {
        val s = deleted.session
        dao.upsertSession(s.toEntity(deleted.sort))
        val entities = s.messages.mapIndexed { i, m -> m.toEntity(s.id, i.toLong()) }
        if (entities.isNotEmpty()) dao.upsertMessages(entities)
    }

    suspend fun clearAll() {
        dao.clearMessages()
        dao.clearSessions()
    }

    /**
     * 冷启动修复:上一次进程被杀/崩溃时留在库里的「半条消息」。
     *
     * 流式回复边收边写库,所以被杀那一刻的消息会停在半途(空正文、步骤停在「连接模型并开始生成…」、
     * 工具卡片停在 running/waiting)。**只在进程启动时调用** —— 那时库里不可能有真在写入的消息;
     * 修复后再落库,历史展示与后续上下文都不再被这条半成品污染(空正文会变成一条空助手轮)。
     *
     * 扫多少:首次(本功能上线后的第一次冷启动)全量扫 —— 老版本留下的半成品可能已经被后续消息压在历史中间;
     * 之后只扫每条会话的**最后一条** —— 进程被杀只可能把最后一条留在半途,没必要每次启动都解析全库 JSON。
     *
     * @return 被修复的消息条数(0 = 没有残留)
     */
    suspend fun repairAbandonedMessages(): Int {
        val swept = dao.setting(KEY_REPAIR_SWEPT)?.value == "1"
        var fixed = 0
        for (row in dao.allSessions()) {
            val entities = dao.sessionWithMessages(row.id)?.messages.orEmpty()
            val targets = if (swept) entities.takeLast(1) else entities
            val changed = targets.mapNotNull { e ->
                val msg = e.toDomain()
                val repaired = MsgSteps.repairAbandoned(msg)
                if (repaired == msg) null else repaired.toEntity(row.id, e.sort)
            }
            if (changed.isNotEmpty()) {
                dao.upsertMessages(changed)
                fixed += changed.size
            }
        }
        if (!swept) dao.upsertSetting(SettingEntity(KEY_REPAIR_SWEPT, "1"))
        return fixed
    }

    private companion object {
        /** 内部标记(不是用户可配的设置项):冷启动修复的全量扫描是否已经做过。 */
        const val KEY_REPAIR_SWEPT = "abandonedRepairSwept"
    }
}
