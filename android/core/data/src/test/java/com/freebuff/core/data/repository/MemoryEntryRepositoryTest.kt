package com.freebuff.core.data.repository

import com.freebuff.core.data.db.CustomModelEntity
import com.freebuff.core.data.db.FreebuffDao
import com.freebuff.core.data.db.MemoryEntity
import com.freebuff.core.data.db.MemoryEntryEntity
import com.freebuff.core.data.db.MessageEntity
import com.freebuff.core.data.db.SessionEntity
import com.freebuff.core.data.db.SessionWithMessages
import com.freebuff.core.data.db.SettingEntity
import com.freebuff.core.model.MemoryExtraction
import com.freebuff.core.model.MemoryStore
import com.freebuff.core.model.MemoryType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检索式记忆库:写入去重、相关度检索、类型过滤、命中热度、容量淘汰。 */
class MemoryEntryRepositoryTest {

    /** 内存假 DAO:只实现 memory_entries 相关,其余空实现。 */
    private class FakeDao : FreebuffDao {
        val entries = linkedMapOf<Long, MemoryEntryEntity>()
        private var nextId = 1L

        override suspend fun memoryEntries(userId: String): List<MemoryEntryEntity> =
            entries.values.filter { it.userId == userId }.sortedByDescending { it.updatedAt }

        override fun observeMemoryEntries(): Flow<List<MemoryEntryEntity>> =
            flowOf(entries.values.sortedByDescending { it.updatedAt })

        override suspend fun upsertMemoryEntry(entry: MemoryEntryEntity): Long {
            val id = if (entry.id > 0) entry.id else nextId++
            entries[id] = entry.copy(id = id)
            return id
        }

        override suspend fun deleteMemoryEntry(id: Long) { entries.remove(id) }
        override suspend fun clearMemoryEntries() = entries.clear()

        override suspend fun allMemories(): List<MemoryEntity> = emptyList()
        override fun observeMemories(): Flow<List<MemoryEntity>> = flowOf(emptyList())
        override suspend fun upsertMemory(memory: MemoryEntity) = Unit
        override suspend fun deleteMemory(block: String) = Unit
        override suspend fun clearMemories() = Unit

        override fun observeSessionsWithMessages(): Flow<List<SessionWithMessages>> = flowOf(emptyList())
        override suspend fun sessionWithMessages(id: String): SessionWithMessages? = null
        override suspend fun allSessions(): List<SessionEntity> = emptyList()
        override suspend fun session(id: String): SessionEntity? = null
        override suspend fun upsertSession(session: SessionEntity) = Unit
        override suspend fun deleteSession(id: String) = Unit
        override suspend fun clearSessions() = Unit
        override fun observeMessages(sessionId: String): Flow<List<MessageEntity>> = flowOf(emptyList())
        override suspend fun upsertMessages(list: List<MessageEntity>) = Unit
        override suspend fun deleteMessages(sessionId: String) = Unit
        override suspend fun deleteMessageIds(sessionId: String, ids: List<String>) = Unit
        override suspend fun clearMessages() = Unit
        override fun observeCustomModels(): Flow<List<CustomModelEntity>> = flowOf(emptyList())
        override suspend fun customModel(id: String): CustomModelEntity? = null
        override suspend fun allCustomModels(): List<CustomModelEntity> = emptyList()
        override suspend fun upsertCustomModel(model: CustomModelEntity) = Unit
        override suspend fun deleteCustomModel(id: String) = Unit
        override suspend fun clearCustomModels() = Unit
        override suspend fun allSettings(): List<SettingEntity> = emptyList()
        override suspend fun setting(key: String): SettingEntity? = null
        override fun observeSetting(key: String): Flow<SettingEntity?> = flowOf(null)
        override suspend fun upsertSetting(setting: SettingEntity) = Unit
        override suspend fun deleteSetting(key: String) = Unit
        override suspend fun clearSettings() = Unit
    }

    @Test
    fun `写入后可检索到 相关条目优先`() = runTest {
        val repo = MemoryEntryRepository(FakeDao())
        val now = 1_700_000_000_000L
        repo.add("用户偏好:喜欢简洁的回复", MemoryType.LONG_TERM, now = now)
        repo.add("用户使用的设备是 Android 模拟器", MemoryType.LONG_TERM, now = now)
        repo.add("项目进度:记忆库已接入", MemoryType.SHORT_TERM, now = now)

        val hits = repo.search("用户喜欢什么样的回复", topK = 3, now = now)
        assertTrue("应有命中,实际=" + hits.map { it.content }, hits.isNotEmpty())
        assertTrue("偏好条目应排第一,实际=" + hits.first().content, hits.first().content.contains("简洁的回复"))
        assertTrue("与查询无特征重叠的条目应被排除", hits.none { it.content.contains("记忆库已接入") })
    }

    @Test
    fun `重复内容只刷新 不重复插入`() = runTest {
        val dao = FakeDao()
        val repo = MemoryEntryRepository(dao)
        repo.add("用户偏好简洁回复", MemoryType.LONG_TERM, now = 1000L)
        repo.add("  用户偏好简洁回复  ", MemoryType.SHORT_TERM, now = 2000L)

        val all = dao.memoryEntries(MemoryStore.LOCAL_USER_ID)
        assertEquals(1, all.size)
        assertEquals("类型应刷新为最新一次", MemoryType.SHORT_TERM, all.first().type)
        assertEquals(2000L, all.first().updatedAt)
        assertEquals(1000L, all.first().createdAt)
    }

    @Test
    fun `近似重复表述只刷新 不新增条目`() = runTest {
        val dao = FakeDao()
        val repo = MemoryEntryRepository(dao)
        repo.add("用户偏好简洁回复", MemoryType.LONG_TERM, now = 1000L)
        repo.add("用户偏好简洁回复。", MemoryType.LONG_TERM, now = 2000L)

        val all = dao.memoryEntries(MemoryStore.LOCAL_USER_ID)
        assertEquals("换句话重复表述不应新增条目", 1, all.size)
        assertEquals(2000L, all.first().updatedAt)

        // 表述不同(重合度低)则正常新增
        repo.add("部署环境:自建机房", MemoryType.SHORT_TERM, now = 3000L)
        assertEquals(2, dao.memoryEntries(MemoryStore.LOCAL_USER_ID).size)
    }

    @Test
    fun `类型过滤与命中热度`() = runTest {
        val repo = MemoryEntryRepository(FakeDao())
        val now = 1_700_000_000_000L
        repo.add("任务进度:正在跑端到端验证", MemoryType.SHORT_TERM, now = now)
        repo.add("任务进度:长期目标保持记录", MemoryType.LONG_TERM, now = now)

        // 自动注入(bumpHits=false)不写库
        val noBump = repo.search("任务进度", topK = 5, now = now, bumpHits = false)
        assertTrue("自动注入不应累加热度", noBump.all { it.hits == 0 })

        val shortOnly = repo.search("任务进度", topK = 5, memoryType = MemoryType.SHORT_TERM, now = now)
        assertEquals(1, shortOnly.size)
        assertEquals(MemoryType.SHORT_TERM, shortOnly.first().type)
        assertEquals("显式检索应累加热度", 1, shortOnly.first().hits)

        val bumped = repo.search("任务进度", topK = 5, now = now)
        assertEquals("再次检索热度继续累加", 2, bumped.first { it.type == MemoryType.SHORT_TERM }.hits)
        assertEquals("同批命中的其他条目同样累加", 1, bumped.first { it.type == MemoryType.LONG_TERM }.hits)
    }

    @Test
    fun `批量写入提取结果 过短内容被拒`() = runTest {
        val repo = MemoryEntryRepository(FakeDao())
        val stored = repo.addExtracted(
            items = listOf(
                MemoryExtraction.Item(MemoryType.LONG_TERM, "用户是中文使用者"),
                MemoryExtraction.Item(MemoryType.SHORT_TERM, "任务:验证记忆检索"),
            ),
            sessionId = "s1",
        )
        assertEquals(2, stored)
        assertNull("过短内容不应入库", repo.add("x"))
        assertNull(repo.add("   "))
    }

    @Test
    fun `超量写入按上限淘汰最旧`() = runTest {
        val dao = FakeDao()
        val repo = MemoryEntryRepository(dao)
        repeat(MemoryStore.PRUNE_KEEP + 3) { i ->
            // 每条内容用独立 token,避免被「近似重复」判定合并
            repo.add("alpha$i beta$i 条目", MemoryType.LONG_TERM, now = 1_000L + i)
        }
        val all = dao.memoryEntries(MemoryStore.LOCAL_USER_ID)
        assertEquals(MemoryStore.PRUNE_KEEP, all.size)
        assertTrue("最旧条目应被淘汰", all.none { it.content == "alpha0 beta0 条目" })
    }

    @Test
    fun `按用户隔离与清空`() = runTest {
        val dao = FakeDao()
        val repo = MemoryEntryRepository(dao)
        repo.add("用户偏好:中文回复", userId = MemoryStore.LOCAL_USER_ID, now = 1000L)
        repo.add("ZX-9 代号与外部账户", userId = "other", now = 1000L)

        assertEquals(1, repo.count(MemoryStore.LOCAL_USER_ID))
        assertTrue(repo.search("ZX-9 代号", topK = 5, userId = "other", now = 1000L).isNotEmpty())
        assertTrue("默认命名空间不应看到其他用户条目", repo.search("ZX-9 代号", topK = 5, now = 1000L).isEmpty())

        repo.clear()
        assertEquals(0, repo.count(MemoryStore.LOCAL_USER_ID))
    }
}
