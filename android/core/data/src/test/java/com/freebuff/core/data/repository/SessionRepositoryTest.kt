package com.freebuff.core.data.repository

import com.freebuff.core.data.db.CustomModelEntity
import com.freebuff.core.data.db.FreebuffDao
import com.freebuff.core.data.db.MemoryEntity
import com.freebuff.core.data.db.MemoryEntryEntity
import com.freebuff.core.data.db.MessageEntity
import com.freebuff.core.data.db.SessionEntity
import com.freebuff.core.data.db.SessionWithMessages
import com.freebuff.core.data.db.SettingEntity
import com.freebuff.core.data.db.toEntity
import com.freebuff.core.model.ChatMsg
import com.freebuff.core.model.MsgStep
import com.freebuff.core.model.MsgSteps
import com.freebuff.core.model.ToolCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话删除与撤销恢复:级联、位次、消息顺序必须前后对称。 */
class SessionRepositoryTest {

    private val dao = MemDao()
    private val repo = SessionRepository(dao)

    @Test
    fun `创建会话写入创建时刻与初始位次`() = runTest {
        val s = repo.create("新会话")
        assertTrue("创建时刻必须落库(列表按它分组)", s.createdAt > 0L)
        assertEquals(s.createdAt, dao.sessions[s.id]?.createdAt)
        assertEquals("刚刚", s.time)
        assertEquals(1L, dao.sessions[s.id]?.sort)
    }

    @Test
    fun `删除会话连带清掉消息`() = runTest {
        val s = repo.create("A")
        repo.appendMessages(s.id, listOf(ChatMsg("m1", "user", text = "hi")))
        val snapshot = repo.deleteWithSnapshot(s.id)
        assertNotNull(snapshot)
        assertEquals(s.id, snapshot!!.session.id)
        assertNull("会话行应已删除", dao.sessions[s.id])
        assertTrue("消息必须一起清掉,否则残留孤儿数据", dao.messages[s.id].orEmpty().isEmpty())
    }

    @Test
    fun `删除不存在的会话返回空快照`() = runTest {
        assertNull(repo.deleteWithSnapshot("no-such-session"))
    }

    @Test
    fun `撤销恢复沿用原位次与消息顺序`() = runTest {
        val a = repo.create("A")
        val b = repo.create("B")
        repo.appendMessages(
            a.id,
            listOf(ChatMsg("m1", "user", text = "一"), ChatMsg("m2", "agent", text = "二")),
        )
        val sortA = dao.sessions[a.id]!!.sort
        val sortB = dao.sessions[b.id]!!.sort

        val snapshot = repo.deleteWithSnapshot(a.id)!!
        // 删除期间新建的会话应当排在更前,恢复不能把 A 顶到最上面
        val c = repo.create("C")
        assertTrue(dao.sessions[c.id]!!.sort > sortA)

        repo.restore(snapshot)
        assertEquals("恢复必须回到原列表位次", sortA, dao.sessions[a.id]!!.sort)
        assertEquals(3, dao.sessions.size)   // A 恢复、B 原位、C 保留
        assertEquals(listOf("m1", "m2"), dao.messages[a.id]!!.map { it.id })
        assertEquals(listOf("一", "二"), repo.get(a.id)!!.messages.map { it.text })
        assertEquals(sortB, dao.sessions[b.id]!!.sort)
    }

    @Test
    fun `恢复后撤销快照里的创建时刻不变`() = runTest {
        val s = repo.create("A")
        val snapshot = repo.deleteWithSnapshot(s.id)!!
        repo.restore(snapshot)
        assertEquals(s.createdAt, dao.sessions[s.id]?.createdAt)
        assertEquals(s.createdAt, snapshot.session.createdAt)
    }

    @Test
    fun `无消息的会话删除后可原样恢复`() = runTest {
        val s = repo.create("空会话")
        val snapshot = repo.deleteWithSnapshot(s.id)!!
        repo.restore(snapshot)
        assertEquals(0, repo.get(s.id)!!.messages.size)
        assertEquals("空会话", dao.sessions[s.id]?.title)
    }

    @Test
    fun `冷启动修复半条消息 —— 空正文补提示、过程性步骤与未跑完的卡片都收干净`() = runTest {
        val s = repo.create("被中断的会话")
        repo.appendMessages(
            s.id,
            listOf(
                ChatMsg("m1", "user", text = "帮我查一下时间"),
                ChatMsg(
                    "m2", "agent", text = "", time = MsgSteps.GENERATING,
                    steps = listOf(
                        MsgStep(MsgSteps.CONNECT, "连接模型并开始生成…"),
                        MsgStep(MsgSteps.retry(1), "上次失败:连接超时"),
                    ),
                    tools = listOf(
                        ToolCard("c1", "get_time", state = "running"),
                        ToolCard("c2", "read_url", state = "waiting"),
                    ),
                ),
                ChatMsg("m3", "agent", text = "已经答完的一条", time = "10:00"),
            ),
        )

        assertEquals("只有被中断的那条需要修", 1, repo.repairAbandonedMessages())

        val msgs = repo.get(s.id)!!.messages
        val broken = msgs.first { it.id == "m2" }
        assertEquals(MsgSteps.INTERRUPTED_NOTE, broken.text)
        assertEquals("假时间戳要清掉", "", broken.time)
        assertEquals(
            "过程性步骤收尾清掉;重试记录是历史性的,保留",
            listOf(MsgSteps.retry(1)),
            broken.steps.map { it.name },
        )
        assertTrue("没跑完的工具卡片要标成未完成", broken.tools.all { it.isError })
        assertEquals("已经答完的消息不能动(否则会误伤历史)", "已经答完的一条", msgs.first { it.id == "m3" }.text)
        assertEquals("修完再跑一次应无改动(幂等)", 0, repo.repairAbandonedMessages())
    }

    @Test
    fun `全量扫过之后只扫末尾一条 —— 冷启动不该每次解析全库消息`() = runTest {
        val s = repo.create("长会话")
        repo.appendMessages(s.id, listOf(ChatMsg("m1", "user", text = "开始")))
        assertEquals("首次没有半成品", 0, repo.repairAbandonedMessages())

        // 伪装成修复功能上线前就写坏、又被后续消息压在历史中间的那条(全量扫已经过了,不再翻它)
        // 位次 -1 保证它在 m1 之前 —— 写在末尾就不是「压在历史中间」了
        dao.upsertMessages(
            listOf(
                ChatMsg("m9", "agent", text = "", steps = listOf(MsgStep(MsgSteps.CONNECT, "连接模型并开始生成…")))
                    .toEntity(s.id, -1L),
            ),
        )
        assertEquals("全量扫已做过:历史中间的不再处理", 0, repo.repairAbandonedMessages())

        // 进程被杀留下的半成品总在末尾:这条必须修
        repo.appendMessages(
            s.id,
            listOf(ChatMsg("m2", "agent", text = "", steps = listOf(MsgStep(MsgSteps.CONNECT, "连接模型并开始生成…")))),
        )
        assertEquals(1, repo.repairAbandonedMessages())
        assertEquals(MsgSteps.INTERRUPTED_NOTE, repo.get(s.id)!!.messages.last().text)
        assertEquals(
            "历史中间的半成品保持原样(不进主流程,不值得每次冷启动解析全库)",
            "",
            repo.get(s.id)!!.messages.first { it.id == "m9" }.text,
        )
    }

    @Test
    fun `删除中间一条后追加不会与既有行撞号`() = runTest {
        // 消单条删除会留下序号空洞:取号若用「消息条数」就会与既有行同序号,
        // 两条同 sort 时会话内顺序不再确定(历史显示错乱)
        val s = repo.create("A")
        repo.appendMessages(
            s.id,
            listOf(
                ChatMsg("m1", "user", text = "问"),
                ChatMsg("m2", "agent", text = "答"),
                ChatMsg("m3", "user", text = "再问"),
                ChatMsg("m4", "agent", text = "再答"),
            ),
        )
        repo.deleteMessages(s.id, listOf("m2"))
        assertEquals(listOf("m1", "m3", "m4"), repo.get(s.id)!!.messages.map { it.id })
        assertEquals("只删指定的那一条", listOf(0L, 2L, 3L), dao.messages[s.id]!!.map { it.sort })

        repo.appendMessages(s.id, listOf(ChatMsg("m5", "agent", text = "重跑")))
        assertEquals(
            "新消息要排在最后(取 max+1 = 4,不撞到 m3 的 3)",
            listOf("m1", "m3", "m4", "m5"),
            repo.get(s.id)!!.messages.map { it.id },
        )
        assertEquals(listOf(0L, 2L, 3L, 4L), dao.messages[s.id]!!.map { it.sort })
    }

    @Test
    fun `消息改写与删除后会话预览按剩余消息重算`() = runTest {
        val s = repo.create("A")
        repo.appendMessages(
            s.id,
            listOf(
                ChatMsg("m1", "user", text = "第一个问题"),
                ChatMsg("m2", "agent", text = "回答"),
                ChatMsg("m3", "user", text = "第二个问题"),
            ),
        )
        assertEquals("第二个问题", dao.sessions[s.id]!!.preview)

        repo.deleteMessages(s.id, listOf("m3"))
        repo.refreshPreview(s.id)
        assertEquals("预览不能还指着已删掉的消息", "第一个问题", dao.sessions[s.id]!!.preview)

        repo.replaceMessage(s.id, "m1", ChatMsg("m1", "user", text = "改写后的问题"))
        repo.refreshPreview(s.id)
        assertEquals("改写后的问题", dao.sessions[s.id]!!.preview)
    }

    /* ---------------- 全内存 DAO(会话/消息真实读写,其余无需关心) ---------------- */
    private class MemDao : FreebuffDao {
        val sessions = LinkedHashMap<String, SessionEntity>()
        val messages = LinkedHashMap<String, List<MessageEntity>>()
        val settings = LinkedHashMap<String, SettingEntity>()

        override fun observeSessionsWithMessages(): Flow<List<SessionWithMessages>> =
            flowOf(sessions.values.map { SessionWithMessages(it, messages[it.id].orEmpty()) })

        override suspend fun sessionWithMessages(id: String): SessionWithMessages? {
            val s = sessions[id] ?: return null
            return SessionWithMessages(s, messages[id].orEmpty().sortedBy { it.sort })
        }

        override suspend fun allSessions(): List<SessionEntity> = sessions.values.toList()
        override suspend fun session(id: String): SessionEntity? = sessions[id]
        override suspend fun upsertSession(session: SessionEntity) { sessions[session.id] = session }
        override suspend fun deleteSession(id: String) { sessions.remove(id) }
        override suspend fun clearSessions() { sessions.clear() }

        override fun observeMessages(sessionId: String): Flow<List<MessageEntity>> =
            flowOf(messages[sessionId].orEmpty())

        override suspend fun upsertMessages(messages: List<MessageEntity>) {
            messages.groupBy { it.sessionId }.forEach { (sid, incoming) ->
                val kept = this.messages[sid].orEmpty().filter { old -> incoming.none { it.id == old.id } }
                this.messages[sid] = (kept + incoming).sortedBy { it.sort }
            }
        }

        override suspend fun deleteMessages(sessionId: String) { messages.remove(sessionId) }

        override suspend fun deleteMessageIds(sessionId: String, ids: List<String>) {
            messages[sessionId]?.let { cur -> messages[sessionId] = cur.filterNot { it.id in ids } }
        }
        override suspend fun clearMessages() { messages.clear() }

        override fun observeCustomModels(): Flow<List<CustomModelEntity>> = flowOf(emptyList())
        override suspend fun customModel(id: String): CustomModelEntity? = null
        override suspend fun allCustomModels(): List<CustomModelEntity> = emptyList()
        override suspend fun upsertCustomModel(model: CustomModelEntity) = Unit
        override suspend fun deleteCustomModel(id: String) = Unit
        override suspend fun clearCustomModels() = Unit

        override suspend fun allSettings(): List<SettingEntity> = settings.values.toList()
        override suspend fun setting(key: String): SettingEntity? = settings[key]
        override fun observeSetting(key: String): Flow<SettingEntity?> = flowOf(settings[key])
        override suspend fun upsertSetting(setting: SettingEntity) { settings[setting.key] = setting }
        override suspend fun deleteSetting(key: String) { settings.remove(key) }
        override suspend fun clearSettings() { settings.clear() }

        override suspend fun allMemories(): List<MemoryEntity> = emptyList()
        override fun observeMemories(): Flow<List<MemoryEntity>> = flowOf(emptyList())
        override suspend fun upsertMemory(memory: MemoryEntity) = Unit
        override suspend fun deleteMemory(block: String) = Unit
        override suspend fun clearMemories() = Unit

        override suspend fun memoryEntries(userId: String): List<MemoryEntryEntity> = emptyList()
        override fun observeMemoryEntries(): Flow<List<MemoryEntryEntity>> = flowOf(emptyList())
        override suspend fun upsertMemoryEntry(entry: MemoryEntryEntity): Long = 0L
        override suspend fun deleteMemoryEntry(id: Long) = Unit
        override suspend fun clearMemoryEntries() = Unit
    }
}
