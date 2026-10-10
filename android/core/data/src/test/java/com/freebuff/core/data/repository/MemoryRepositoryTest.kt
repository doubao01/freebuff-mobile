package com.freebuff.core.data.repository

import com.freebuff.core.data.db.CustomModelEntity
import com.freebuff.core.data.db.FreebuffDao
import com.freebuff.core.data.db.MemoryEntity
import com.freebuff.core.data.db.MemoryEntryEntity
import com.freebuff.core.data.db.MessageEntity
import com.freebuff.core.data.db.SessionEntity
import com.freebuff.core.data.db.SessionWithMessages
import com.freebuff.core.data.db.SettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 记忆仓库:块播种、save_memory 落库、追加压缩、清空。 */
class MemoryRepositoryTest {

    /** 内存假 DAO:只实现 memories 相关,其余空实现。 */
    private class FakeDao : FreebuffDao {
        val memories = linkedMapOf<String, MemoryEntity>()

        override suspend fun allMemories(): List<MemoryEntity> = memories.values.sortedBy { it.sort }
        override fun observeMemories(): Flow<List<MemoryEntity>> = flowOf(memories.values.sortedBy { it.sort })
        override suspend fun upsertMemory(memory: MemoryEntity) { memories[memory.block] = memory }
        override suspend fun deleteMemory(block: String) { memories.remove(block) }
        override suspend fun clearMemories() = memories.clear()

        // 检索式记忆库(内存模拟):id 自增、按 userId 过滤、updatedAt 降序
        val entries = linkedMapOf<Long, MemoryEntryEntity>()
        private var nextEntryId = 1L

        override suspend fun memoryEntries(userId: String): List<MemoryEntryEntity> =
            entries.values.filter { it.userId == userId }.sortedByDescending { it.updatedAt }

        override fun observeMemoryEntries(): Flow<List<MemoryEntryEntity>> =
            flowOf(entries.values.sortedByDescending { it.updatedAt })

        override suspend fun upsertMemoryEntry(entry: MemoryEntryEntity): Long {
            val id = if (entry.id > 0) entry.id else nextEntryId++
            entries[id] = entry.copy(id = id)
            return id
        }

        override suspend fun deleteMemoryEntry(id: Long) { entries.remove(id) }
        override suspend fun clearMemoryEntries() = entries.clear()

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
    fun `首次 load 播种默认块`() = runTest {
        val dao = FakeDao()
        val repo = MemoryRepository(dao)
        val blocks = repo.load()
        assertEquals(listOf("persona", "user", "project"), blocks.map { it.name })
        // 再 load 不重复播种
        dao.upsertMemory(MemoryEntity("user", "已修改", 600, 1))
        val again = repo.load()
        assertEquals("已修改", again.first { it.name == "user" }.content)
    }

    @Test
    fun `applySave replace 覆盖块内容`() = runTest {
        val repo = MemoryRepository(FakeDao())
        repo.load()
        val r = repo.applySave("user", "喜欢中文", replace = true)
        assertTrue(r.contains("已保存"))
        val blocks = repo.load()
        assertEquals("喜欢中文", blocks.first { it.name == "user" }.content)
    }

    @Test
    fun `applySave append 追加并压缩超限内容`() = runTest {
        val repo = MemoryRepository(FakeDao())
        repo.load()
        repo.applySave("project", "A".repeat(500), replace = true)
        val r = repo.applySave("project", "B".repeat(200), replace = false)
        assertTrue(r.contains("已保存"))
        val content = repo.load().first { it.name == "project" }.content
        assertTrue("追加段应在末尾", content.trimEnd().endsWith("BBB"))
        assertTrue("应保留头部", content.startsWith("A"))
    }

    @Test
    fun `applySave 空内容清空块`() = runTest {
        val repo = MemoryRepository(FakeDao())
        repo.load()
        repo.applySave("user", "临时", replace = true)
        val r = repo.applySave("user", "  ", replace = true)
        assertTrue(r.contains("已清空"))
        assertTrue(repo.load().none { it.name == "user" })
    }

    @Test
    fun `applySave 空 block 返回错误文案`() = runTest {
        val repo = MemoryRepository(FakeDao())
        val r = repo.applySave("", "内容", replace = true)
        assertTrue(r.contains("失败"))
    }

    @Test
    fun `clearAll 清空全部块`() = runTest {
        val dao = FakeDao()
        val repo = MemoryRepository(dao)
        repo.load()
        repo.clearAll()
        assertTrue(dao.memories.isEmpty())
    }
}
