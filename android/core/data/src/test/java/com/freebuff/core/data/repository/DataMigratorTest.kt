package com.freebuff.core.data.repository

import android.content.SharedPreferences
import com.freebuff.core.data.db.CustomModelEntity
import com.freebuff.core.data.db.FreebuffDao
import com.freebuff.core.data.db.MessageEntity
import com.freebuff.core.data.db.SessionEntity
import com.freebuff.core.data.db.SessionWithMessages
import com.freebuff.core.data.db.MemoryEntity
import com.freebuff.core.data.db.MemoryEntryEntity
import com.freebuff.core.data.db.SettingEntity
import com.freebuff.core.data.security.CryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一次性迁移器:守卫只执行一次、旧偏好平移、历史演示会话清理、
 * 非对象条目解析为 drop、空 ID 自动生成。
 *
 * 替身:[FakePrefs] 模拟 SharedPreferences 的最小面(布尔/字符串/编辑器),
 * [MemDao] 是全内存 DAO;CryptoManager 用恒等替身,不触碰 Android Keystore。
 */
class DataMigratorTest {

    /* ---------------- 守卫:migrate 只执行一次 ---------------- */

    @Test
    fun `已迁移过时直接返回 null 且不触碰数据`() = runTest {
        val prefs = FakePrefs()
        val dao = MemDao()
        val m = DataMigrator(dao, IdentityCrypto())

        m.migrate(prefs)
        dao.resetCounters()

        // 第二次调用:已打过标志
        val second = m.migrate(prefs)

        assertNull(second)
        assertEquals(0, dao.clearCustomModelsCalls)
        assertEquals(0, dao.upsertCustomModelCalls)
        assertEquals(0, dao.upsertSessionCalls)
        assertEquals(0, dao.upsertSettingCalls)
        assertTrue(prefs.getBoolean(DataMigrator.KEY_MIGRATED, false))
    }

    @Test
    fun `迁移只写一次标志`() = runTest {
        val prefs = FakePrefs()
        val m = DataMigrator(MemDao(), IdentityCrypto())

        assertFalse(prefs.getBoolean(DataMigrator.KEY_MIGRATED, false))
        m.migrate(prefs)
        assertTrue(prefs.getBoolean(DataMigrator.KEY_MIGRATED, false))

        // 多次重复调用不改变标志
        m.migrate(prefs)
        m.migrate(prefs)
        assertTrue(prefs.getBoolean(DataMigrator.KEY_MIGRATED, false))
    }

    @Test
    fun `首启不灌入任何示例会话`() = runTest {
        val prefs = FakePrefs()
        val dao = MemDao()

        DataMigrator(dao, IdentityCrypto()).migrate(prefs)

        assertTrue(dao.sessions.isEmpty())
    }

    @Test
    fun `升级安装清掉历史演示会话但保留真实会话`() = runTest {
        val prefs = FakePrefs()
        val dao = MemDao()
        // 旧版首启灌入的演示会话 + 用户自己的一条真实会话
        DataMigrator.LEGACY_DEMO_SESSION_IDS.forEach { id ->
            dao.sessions[id] = SessionEntity(id, "演示", "刚刚", "", 0L)
        }
        dao.sessions["s7f3k2"] = SessionEntity("s7f3k2", "真实会话", "刚刚", "", 1L)

        DataMigrator(dao, IdentityCrypto()).migrate(prefs)

        assertEquals(listOf("s7f3k2"), dao.sessions.keys.toList())

        // 只清一次:用户之后手动删除同 id 会话不会被再触发(标志已置位)
        assertTrue(prefs.getBoolean(DataMigrator.KEY_DEMO_PURGED, false))
        dao.sessions["s1"] = SessionEntity("s1", "用户自建", "刚刚", "", 2L)
        DataMigrator(dao, IdentityCrypto()).migrate(prefs)
        assertEquals(2, dao.sessions.size)
    }

    @Test
    fun `迁移后修改的设置不被旧偏好回滚`() = runTest {
        val prefs = FakePrefs()
        val dao = MemDao()
        val m = DataMigrator(dao, IdentityCrypto())

        // 旧存档:主题 dark、模型 deepseek
        prefs.putString("themeMode", "dark")
        prefs.putString("modelId", "deepseek-v4-flash")
        m.migrate(prefs)
        assertEquals("dark", dao.setting("themeMode")?.value)

        // 用户在 App 内改了主题与模型
        dao.upsertSetting(SettingEntity("themeMode", "light"))
        dao.upsertSetting(SettingEntity("modelId", "glm-5.3-flash"))

        // 重启:守卫生效,旧偏好不再覆写
        m.migrate(prefs)
        assertEquals("light", dao.setting("themeMode")?.value)
        assertEquals("glm-5.3-flash", dao.setting("modelId")?.value)
    }

    /* ---------------- 首启行为 ---------------- */

    @Test
    fun `首启平移设置键`() = runTest {
        val prefs = FakePrefs()
        prefs.putString("themeMode", "light")
        prefs.putString("modelId", "glm-5.3-flash")
        prefs.putString("repoParse", "loose")
        prefs.putString("signedIn", "true")
        val dao = MemDao()

        val report = DataMigrator(dao, IdentityCrypto()).migrate(prefs)

        assertNull(report) // 没有自定义模型 → 无修复报告
        assertEquals("light", dao.setting("themeMode")?.value)
        assertEquals("glm-5.3-flash", dao.setting("modelId")?.value)
        assertEquals("loose", dao.setting("repoParse")?.value)
        assertEquals("true", dao.setting("signedIn")?.value)
        assertTrue(dao.sessions.isEmpty())
    }

    @Test
    fun `迁移后报告只产出一次`() = runTest {
        val prefs = FakePrefs()
        prefs.putString(
            "customModels",
            """[{"id":"","name":"X"}]""",
        )
        val dao = MemDao()
        val m = DataMigrator(dao, IdentityCrypto())

        val first = m.migrate(prefs)
        requireNotNull(first)
        assertEquals(1, first.fixed)

        // 守卫:第二次不再产出报告,也不再触碰数据
        assertNull(m.migrate(prefs))
    }

    /* ---------------- 自定义模型迁移 ---------------- */

    @Test
    fun `非对象条目记为 drop 且不落库`() = runTest {
        val prefs = FakePrefs()
        // 旧存档里混入了非对象条目:字符串 / 数字 / JSON null
        prefs.putString(
            "customModels",
            """["stray",42,null,{"id":"m1","name":"Mine","apiId":"api-1","base":"https://x/v1"}]""",
        )
        val dao = MemDao()

        val report = DataMigrator(dao, IdentityCrypto()).migrate(prefs)

        // 报告可见:3 条非对象全部报告,原始 4 条 → 保留 1 条
        requireNotNull(report)
        assertEquals(3, report.fixed)
        assertEquals(4, report.rawN)
        assertEquals(1, report.nowN)
        val dropItem = report.items.first { it.fixes.any { f -> f.code == "drop" } }
        assertNull(dropItem.raw)

        // 只有 1 条对象记录落库,key 保持明文(恒等加密)
        assertEquals(1, dao.customModels.size)
        assertEquals("m1", dao.customModels.values.single().id)
        // 修复后立即回写存档(与原型一致),重启后不会重复修复
        assertTrue(prefs.getString("customModels", "").orEmpty().contains("\"id\":\"m1\""))
    }

    @Test
    fun `空 ID 生成新 ID 后落库且可恢复`() = runTest {
        val prefs = FakePrefs()
        prefs.putString(
            "customModels",
            """[{"id":"","name":"NoId","apiId":"a","base":"https://y/v1"}]""",
        )
        val dao = MemDao()

        val report = DataMigrator(dao, IdentityCrypto()).migrate(prefs)

        requireNotNull(report)
        assertTrue(report.items[0].fixes.any { it.code == "id" })
        assertEquals(1, dao.customModels.size)
        val saved = dao.customModels.values.single()
        assertTrue(saved.id.startsWith("cm-"))
        // 原始记录仍在报告 raw 里(空 ID 原样),按恢复语义可重新生成 ID 落库
        val raw = report.raw?.filterIsInstance<com.freebuff.core.model.CustomModel>()?.single()
        assertEquals("", raw?.id)
        assertEquals("NoId", raw?.name)
    }

    @Test
    fun `干净记录迁移无报告`() = runTest {
        val prefs = FakePrefs()
        prefs.putString(
            "customModels",
            """[{"id":"ok","name":"Good","apiId":"a","base":"https://z/v1","models":["m1"]}]""",
        )

        val report = DataMigrator(FakeDao(), IdentityCrypto()).migrate(prefs)

        assertNull(report)
    }

    @Test
    fun `非 JSON 的存档原样跳过不崩溃`() = runTest {
        val prefs = FakePrefs()
        prefs.putString("customModels", "not-json")
        val dao = MemDao()

        val report = DataMigrator(dao, IdentityCrypto()).migrate(prefs)

        assertNull(report)
        assertEquals(0, dao.customModels.size)
        assertTrue(dao.sessions.isEmpty()) // 其余迁移照常完成
    }

    /* ---------------- 恒等加解密 ---------------- */

    private class IdentityCrypto : CryptoManager() {
        override fun encrypt(plain: String): String = plain
        override fun decrypt(encoded: String): String = encoded
    }

    /* ---------------- 最小 SharedPreferences 替身 ---------------- */

    private class FakePrefs : SharedPreferences {
        val data = LinkedHashMap<String, Any?>()

        fun putString(key: String, value: String) {
            data[key] = value
        }

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            (data[key] as? Boolean) ?: defValue

        override fun getString(key: String, defValue: String?): String =
            (data[key] as? String) ?: defValue.orEmpty()

        override fun getAll(): Map<String, *> = data
        override fun getInt(key: String, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String> =
            (data[key] as? MutableSet<String>) ?: defValues ?: mutableSetOf()

        override fun contains(key: String): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removals.add(key) }
            override fun clear() = apply { clearAll = true }

            override fun commit(): Boolean = true

            override fun apply() {
                // 与生产 apply() 一致:异步语义对本测试无影响,直接同步落
                if (clearAll) data.clear()
                removals.forEach { data.remove(it) }
                pending.forEach { (k, v) -> data[k] = v }
                clearAll = false
                removals.clear()
            }
        }
    }

    /* ---------------- 全内存 DAO ---------------- */

    private class MemDao : FreebuffDao {
        val sessions = LinkedHashMap<String, SessionEntity>()
        val messages = LinkedHashMap<String, List<MessageEntity>>()
        val customModels = LinkedHashMap<String, CustomModelEntity>()
        val settings = LinkedHashMap<String, String>()
        val memories = LinkedHashMap<String, MemoryEntity>()

        var clearCustomModelsCalls = 0
        var upsertCustomModelCalls = 0
        var upsertSessionCalls = 0
        var upsertSettingCalls = 0

        fun resetCounters() {
            clearCustomModelsCalls = 0
            upsertCustomModelCalls = 0
            upsertSessionCalls = 0
            upsertSettingCalls = 0
        }

        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(emptyList())
        override fun observeSessionWithMessages(id: String): Flow<SessionWithMessages?> = flowOf(null)
        override suspend fun sessionIdsMatching(keyword: String): List<String> = emptyList()
        override suspend fun lastMessageOfRole(sessionId: String, role: String): MessageEntity? = null
        override suspend fun messageSort(sessionId: String, id: String): Long? = null
        override suspend fun sessionWithMessages(id: String): SessionWithMessages? = null
        override suspend fun allSessions(): List<SessionEntity> = sessions.values.toList()
        override suspend fun session(id: String): SessionEntity? = sessions[id]
        override suspend fun upsertSession(session: SessionEntity) {
            upsertSessionCalls++
            sessions[session.id] = session
        }

        override suspend fun deleteSession(id: String) {
            sessions.remove(id)
        }

        override suspend fun clearSessions() {
            sessions.clear()
        }

        override fun observeMessages(sessionId: String): Flow<List<MessageEntity>> =
            flowOf(messages[sessionId].orEmpty())

        override suspend fun upsertMessages(list: List<MessageEntity>) {
            val bySession = list.groupBy { it.sessionId }
            bySession.forEach { (sid, msgs) ->
                messages[sid] = (messages[sid].orEmpty() + msgs).sortedBy { it.sort }
            }
        }

        override suspend fun deleteMessages(sessionId: String) {
            messages.remove(sessionId)
        }

        override suspend fun deleteMessageIds(sessionId: String, ids: List<String>) {
            messages[sessionId]?.let { cur -> messages[sessionId] = cur.filterNot { it.id in ids } }
        }

        override suspend fun clearMessages() {
            messages.clear()
        }

        override fun observeCustomModels(): Flow<List<CustomModelEntity>> =
            flowOf(customModels.values.toList())

        override suspend fun customModel(id: String): CustomModelEntity? = customModels[id]
        override suspend fun allCustomModels(): List<CustomModelEntity> = customModels.values.toList()

        override suspend fun upsertCustomModel(model: CustomModelEntity) {
            upsertCustomModelCalls++
            customModels[model.id] = model
        }

        override suspend fun deleteCustomModel(id: String) {
            customModels.remove(id)
        }

        override suspend fun clearCustomModels() {
            clearCustomModelsCalls++
            customModels.clear()
        }

        override suspend fun allSettings(): List<SettingEntity> =
            settings.map { (k, v) -> SettingEntity(k, v) }

        override suspend fun setting(key: String): SettingEntity? =
            settings[key]?.let { SettingEntity(key, it) }

        override fun observeSetting(key: String): Flow<SettingEntity?> =
            flow { emit(setting(key)) }

        override suspend fun upsertSetting(setting: SettingEntity) {
            upsertSettingCalls++
            settings[setting.key] = setting.value
        }

        override suspend fun deleteSetting(key: String) {
            settings.remove(key)
        }

        override suspend fun clearSettings() {
            settings.clear()
        }

        override suspend fun allMemories(): List<MemoryEntity> = memories.values.sortedBy { it.sort }
        override fun observeMemories(): Flow<List<MemoryEntity>> = flowOf(memories.values.sortedBy { it.sort })
        override suspend fun upsertMemory(memory: MemoryEntity) { memories[memory.block] = memory }
        override suspend fun deleteMemory(block: String) { memories.remove(block) }
        override suspend fun clearMemories() = memories.clear()
        override suspend fun memoryEntries(userId: String): List<MemoryEntryEntity> = emptyList()
        override fun observeMemoryEntries(): Flow<List<MemoryEntryEntity>> = flowOf(emptyList())
        override suspend fun upsertMemoryEntry(entry: MemoryEntryEntity): Long = 0L
        override suspend fun deleteMemoryEntry(id: Long) = Unit
        override suspend fun clearMemoryEntries() = Unit
    }

    /** 空实现 DAO:只关心 migrate 返回值,不检查落库。 */
    private class FakeDao : FreebuffDao {
        override fun observeSessions(): Flow<List<SessionEntity>> = flowOf(emptyList())
        override fun observeSessionWithMessages(id: String): Flow<SessionWithMessages?> = flowOf(null)
        override suspend fun sessionIdsMatching(keyword: String): List<String> = emptyList()
        override suspend fun lastMessageOfRole(sessionId: String, role: String): MessageEntity? = null
        override suspend fun messageSort(sessionId: String, id: String): Long? = null
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
