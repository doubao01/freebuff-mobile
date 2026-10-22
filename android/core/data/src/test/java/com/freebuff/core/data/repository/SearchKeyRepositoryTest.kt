package com.freebuff.core.data.repository

import com.freebuff.core.data.db.CustomModelEntity
import com.freebuff.core.data.db.FreebuffDao
import com.freebuff.core.data.db.MemoryEntity
import com.freebuff.core.data.db.MemoryEntryEntity
import com.freebuff.core.data.db.MessageEntity
import com.freebuff.core.data.db.SessionEntity
import com.freebuff.core.data.db.SessionWithMessages
import com.freebuff.core.data.db.SettingEntity
import com.freebuff.core.data.security.CryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索 API 密钥的存放方式:明文只进内存,落库的是密文,且只对外暴露「有没有配置」。
 *
 * 用假 Keystore(反转字符串)代替真加密 —— 这里要验的是**读写路径与不泄露**,
 * 不是 AES/GCM 本身(那是 CryptoManager 的职责)。
 */
class SearchKeyRepositoryTest {

    private val dao = SettingsDao()
    private val settings = SettingsRepository(dao)
    private val repo = SearchKeyRepository(settings, ReverseCrypto())

    @Test
    fun `保存后落库的是密文, 明文不写库`() = runTest {
        repo.set("exa-secret-key")

        val stored = dao.values[SettingsRepository.KEY_SEARCH_API_KEY].orEmpty()
        assertNotEquals("库里的值不能是明文", "exa-secret-key", stored)
        assertEquals("rev:yek-terces-axe", stored)
        assertEquals("读回来是明文(只进内存)", "exa-secret-key", repo.apiKey())
        assertTrue(repo.configured.first())
    }

    @Test
    fun `首尾空白被裁掉, 避免粘进来就 401`() = runTest {
        repo.set("  exa-secret-key\n")

        assertEquals("exa-secret-key", repo.apiKey())
    }

    @Test
    fun `清除后回调为未配置`() = runTest {
        repo.set("k")
        assertTrue(repo.configured.first())

        repo.clear()

        assertEquals("", dao.values[SettingsRepository.KEY_SEARCH_API_KEY].orEmpty())
        assertEquals("", repo.apiKey())
        assertFalse(repo.configured.first())
    }

    @Test
    fun `密文解不开时返回空串, 由调用方降级而不是报错`() = runTest {
        // 换机 / Keystore 被清 / 库被直接拷走:密文在,但解不出来
        repo.set("k")
        dao.values[SettingsRepository.KEY_SEARCH_API_KEY] = "not-a-ciphertext"

        assertEquals("", repo.apiKey())
    }

    @Test
    fun `未配置时读密钥是空串而不是抛异常`() = runTest {
        assertEquals("", repo.apiKey())
        assertFalse(repo.configured.first())
    }

    /** 假 Keystore:可逆变换,用来断言「落库的不是明文」。 */
    private class ReverseCrypto : CryptoManager() {
        override fun encrypt(plain: String): String = "rev:" + plain.reversed()

        override fun decrypt(encoded: String): String =
            if (encoded.startsWith("rev:")) encoded.removePrefix("rev:").reversed() else ""
    }

    /** 只把 settings 表做实,其余表用不到。 */
    private class SettingsDao : FreebuffDao {
        val values = LinkedHashMap<String, String>()

        override suspend fun setting(key: String): SettingEntity? =
            values[key]?.let { SettingEntity(key, it) }

        // flow { } 而不是 flowOf() —— 后者在建流时就固定了快照,与 Room 边写边发的行为不符
        override fun observeSetting(key: String): Flow<SettingEntity?> = flow {
            emit(values[key]?.let { SettingEntity(key, it) })
        }

        override suspend fun upsertSetting(setting: SettingEntity) {
            values[setting.key] = setting.value
        }

        override suspend fun allSettings(): List<SettingEntity> =
            values.map { SettingEntity(it.key, it.value) }

        override suspend fun deleteSetting(key: String) { values.remove(key) }
        override suspend fun clearSettings() { values.clear() }

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(emptyList())
        override fun observeSessionWithMessages(id: String): Flow<SessionWithMessages?> = flowOf(null)
        override suspend fun sessionIdsMatching(keyword: String): List<String> = emptyList()
        override suspend fun lastMessageOfRole(sessionId: String, role: String): MessageEntity? = null
        override suspend fun messageSort(sessionId: String, id: String): Long? = null
        override suspend fun sessionWithMessages(id: String): SessionWithMessages? = null
        override suspend fun allSessions(): List<SessionEntity> = emptyList()
        override suspend fun session(id: String): SessionEntity? = null
        override suspend fun upsertSession(session: SessionEntity) = unsupported()
        override suspend fun deleteSession(id: String) = unsupported()
        override suspend fun clearSessions() = unsupported()
        override fun observeMessages(sessionId: String): Flow<List<MessageEntity>> = flowOf(emptyList())
        override suspend fun upsertMessages(messages: List<MessageEntity>) = unsupported()
        override suspend fun deleteMessages(sessionId: String) = unsupported()
        override suspend fun deleteMessageIds(sessionId: String, ids: List<String>) = unsupported()
        override suspend fun clearMessages() = unsupported()
        override fun observeCustomModels(): Flow<List<CustomModelEntity>> = flowOf(emptyList())
        override suspend fun customModel(id: String): CustomModelEntity? = null
        override suspend fun allCustomModels(): List<CustomModelEntity> = emptyList()
        override suspend fun upsertCustomModel(model: CustomModelEntity) = unsupported()
        override suspend fun deleteCustomModel(id: String) = unsupported()
        override suspend fun clearCustomModels() = unsupported()
        override suspend fun allMemories(): List<MemoryEntity> = emptyList()
        override fun observeMemories(): Flow<List<MemoryEntity>> = flowOf(emptyList())
        override suspend fun upsertMemory(memory: MemoryEntity) = unsupported()
        override suspend fun deleteMemory(block: String) = unsupported()
        override suspend fun clearMemories() = unsupported()
        override suspend fun memoryEntries(userId: String): List<MemoryEntryEntity> = emptyList()
        override fun observeMemoryEntries(): Flow<List<MemoryEntryEntity>> = flowOf(emptyList())
        override suspend fun upsertMemoryEntry(entry: MemoryEntryEntity): Long = unsupported()
        override suspend fun deleteMemoryEntry(id: Long) = unsupported()
        override suspend fun clearMemoryEntries() = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("本测试只碰 settings 表")
    }
}
