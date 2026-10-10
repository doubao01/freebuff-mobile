package com.freebuff.core.data.repository

import com.freebuff.core.data.db.CustomModelEntity
import com.freebuff.core.data.db.FreebuffDao
import com.freebuff.core.data.db.MessageEntity
import com.freebuff.core.data.db.SessionEntity
import com.freebuff.core.data.db.SessionWithMessages
import com.freebuff.core.data.db.MemoryEntity
import com.freebuff.core.data.db.MemoryEntryEntity
import com.freebuff.core.data.db.SettingEntity
import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.ApiResult
import com.freebuff.core.data.network.GithubApi
import com.freebuff.core.data.security.CryptoManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 真实 Git 接入:设备流编排(等待码 → 轮询 → 账号)、token 加密落库、仓库读取与失败提示。
 * 加解密用恒等替身,DAO 只实现 settings 相关方法。
 */
class RealGitAuthRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var settings: SettingsRepository
    private lateinit var dao: SettingsDao

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dao = SettingsDao()
        settings = SettingsRepository(dao)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(): GithubApi {
        val base = server.url("/").toString().trimEnd('/')
        return GithubApi(OkHttpClient(), webBase = base, apiBase = base)
    }

    private fun repo(clientId: String = "cid") =
        RealGitAuthRepository(clientId, api(), settings, IdentityCrypto())

    private fun enqueueDeviceCode(interval: Int = 1) {
        server.enqueue(
            MockResponse().setBody(
                """{"device_code":"dev","user_code":"AB-12","verification_uri":"https://github.com/login/device","interval":$interval,"expires_in":900}""",
            ),
        )
    }

    @Test
    fun `未配置 client_id 直接失败且不发请求`() = runTest {
        val steps = repo(clientId = "").connect().toList()
        assertEquals(1, steps.size)
        assertTrue((steps[0] as GitConnectStep.Failed).message.contains("未配置"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `设备流一次授权成功并写入账号与 token`() = runTest {
        enqueueDeviceCode()
        server.enqueue(MockResponse().setBody("""{"access_token":"tok-1"}"""))
        server.enqueue(MockResponse().setBody("""{"login":"octocat","name":"The Octocat"}"""))

        val steps = repo().connect().toList()

        assertEquals(2, steps.size)
        val awaiting = steps[0] as GitConnectStep.AwaitingUser
        assertEquals("AB-12", awaiting.userCode)
        assertEquals("https://github.com/login/device", awaiting.verificationUri)

        val done = steps[1] as GitConnectStep.Done
        assertEquals("GitHub", done.state.provider)
        assertEquals("octocat", done.state.login)
        assertEquals("The Octocat", done.state.name)
        assertTrue(done.state.connected)

        // token 经 crypto 处理后落库(恒等替身 → 明文),并写入 Git 状态
        assertEquals("tok-1", settings.getGitToken())
        assertEquals("octocat", settings.git.first().login)
    }

    @Test
    fun `轮询先 pending 再取到 token`() = runTest {
        enqueueDeviceCode(interval = 1)
        server.enqueue(MockResponse().setBody("""{"error":"authorization_pending"}"""))
        server.enqueue(MockResponse().setBody("""{"error":"slow_down","interval":1}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"tok-2"}"""))
        server.enqueue(MockResponse().setBody("""{"login":"octocat"}"""))

        val steps = repo().connect().toList()
        assertEquals(2, steps.size)
        assertTrue(steps[1] is GitConnectStep.Done)
        assertEquals("tok-2", settings.getGitToken())
    }

    @Test
    fun `授权被拒绝时上报失败原因`() = runTest {
        enqueueDeviceCode()
        server.enqueue(MockResponse().setBody("""{"error":"access_denied"}"""))

        val steps = repo().connect().toList()
        assertEquals(2, steps.size)
        val failed = steps[1] as GitConnectStep.Failed
        assertTrue(failed.message.contains("授权被拒绝"))
        assertEquals("", settings.getGitToken())
    }

    @Test
    fun `设备码申请失败时上报分类文案`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val steps = repo().connect().toList()
        assertEquals(1, steps.size)
        assertTrue((steps[0] as GitConnectStep.Failed).message.contains("500"))
    }

    @Test
    fun `未授权时读取仓库返回 NotConfigured`() = runTest {
        val r = repo().repos()
        assertTrue(r is ApiResult.Err)
        assertTrue((r as ApiResult.Err).error is ApiError.NotConfigured)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `已授权时读取仓库映射为 RepoItem`() = runTest {
        settings.setGitToken("tok")
        server.enqueue(
            MockResponse().setBody(
                """[{"full_name":"octocat/freebuff","default_branch":"main","description":"官方仓库"}]""",
            ),
        )
        val repos = (repo().repos() as ApiResult.Ok).data
        assertEquals(1, repos.size)
        assertEquals("octocat/freebuff", repos[0].name)
        assertEquals("main", repos[0].branch)
    }

    @Test
    fun `断开连接清空 token 与账号`() = runTest {
        settings.setGitToken("tok")
        settings.connectGit("GitHub", "octocat", "Octocat")
        repo().revoke()
        assertEquals("", settings.getGitToken())
        assertTrue(!settings.git.first().connected)
    }

    /* ---------------- 替身 ---------------- */

    /** 恒等加解密:避免单测触碰 Android Keystore。 */
    private class IdentityCrypto : CryptoManager() {
        override fun encrypt(plain: String): String = plain
        override fun decrypt(encoded: String): String = encoded
    }

    /** 只实现 settings 的内存 DAO。 */
    private class SettingsDao : FreebuffDao {
        private val store = LinkedHashMap<String, String>()

        override suspend fun setting(key: String): SettingEntity? = store[key]?.let { SettingEntity(key, it) }

        // 每次收集时读取当前值(不能在构造期快照,否则读到的永远是历史状态)
        override fun observeSetting(key: String): Flow<SettingEntity?> = flow { emit(setting(key)) }
        override suspend fun upsertSetting(setting: SettingEntity) {
            store[setting.key] = setting.value
        }

        override suspend fun deleteSetting(key: String) {
            store.remove(key)
        }

        override suspend fun clearSettings() {
            store.clear()
        }

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

        override suspend fun allSettings(): List<SettingEntity> = store.map { SettingEntity(it.key, it.value) }
        override fun observeSessionsWithMessages(): Flow<List<SessionWithMessages>> = flowOf(emptyList())
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

        private fun unsupported(): Nothing = throw UnsupportedOperationException("测试不应触碰该表")
    }
}
