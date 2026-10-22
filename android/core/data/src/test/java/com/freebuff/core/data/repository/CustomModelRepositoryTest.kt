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
import com.freebuff.core.data.network.ProbeResult
import com.freebuff.core.data.security.CryptoManager
import com.freebuff.core.model.CustomModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 自定义模型仓库:真实测试连接、/v1/models 拉取与错误映射。 */
class CustomModelRepositoryTest {
    private lateinit var server: MockWebServer
    private val repo = CustomModelRepository(NoopDao(), CryptoManager())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun model(base: String = server.url("/v1").toString(), apiId: String = "m1", key: String = "k") =
        CustomModel(id = "c1", name = "Test", apiId = apiId, base = base, key = key)

    // ---------- testConnection ----------
    @Test
    fun `测试连接成功返回耗时`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val r = repo.testConnection(model())
        assertTrue("应成功,实际 $r", r is ProbeResult.Success)
    }

    /** 记录阻塞请求经哪个 dispatcher 执行。 */
    private class RecordingDispatcher : CoroutineDispatcher() {
        var dispatches = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            block.run()
        }
    }

    @Test
    fun `测试连接的阻塞请求切到注入的 IO dispatcher`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val rec = RecordingDispatcher()
        val isolated = CustomModelRepository(NoopDao(), CryptoManager(), rec)
        val r = isolated.testConnection(model())
        assertTrue("应成功,实际 $r", r is ProbeResult.Success)
        // 漏切线程时 OkHttp 的 execute() 会跑在调用线程上:真机(Main)会抛 NetworkOnMainThreadException,
        // 而 JVM 单测不会有任何报错 —— 所以这条断言只能盯「有没有经过 io dispatcher」
        assertTrue("阻塞请求必须经 io dispatcher 执行,实际 dispatches=${rec.dispatches}", rec.dispatches >= 1)
    }

    @Test
    fun `测试连接 401 映射鉴权文案`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        val r = repo.testConnection(model())
        assertTrue(r is ProbeResult.Fail)
        assertTrue((r as ProbeResult.Fail).reason.contains("401"))
        assertEquals(401, (r as ProbeResult.Fail).code)
    }

    @Test
    fun `测试连接 404 映射端点文案`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        val r = repo.testConnection(model())
        assertTrue(r is ProbeResult.Fail)
        assertTrue((r as ProbeResult.Fail).reason.contains("404"))
    }

    // ---------- fetchModels ----------
    @Test
    fun `拉取模型 id 列表`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"a"},{"id":"b"}]}"""))
        val r = repo.fetchModels(model())
        assertTrue(r is ApiResult.Ok)
        assertEquals(listOf("a", "b"), (r as ApiResult.Ok).data)
    }

    @Test
    fun `模型列表为空时返回空列表而非错误`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        val r = repo.fetchModels(model())
        assertTrue(r is ApiResult.Ok)
        assertEquals(emptyList<String>(), (r as ApiResult.Ok).data)
    }

    @Test
    fun `模型接口非成功时归类 HTTP 错误`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
        val r = repo.fetchModels(model())
        assertTrue(r is ApiResult.Err)
        val e = (r as ApiResult.Err).error
        assertTrue(e is ApiError.Http)
        assertEquals(403, (e as ApiError.Http).code)
    }

    @Test
    fun `模型接口响应不是 JSON 时归类 Parse`() = runTest {
        server.enqueue(MockResponse().setBody("<html>oops</html>"))
        val r = repo.fetchModels(model())
        assertTrue((r as ApiResult.Err).error is ApiError.Parse)
    }

    // ---------- probe 集成 ----------
    @Test
    fun `probe 成功时更新快照与探测时间`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"a"},{"id":"b"}]}"""))
        val (updated, result) = repo.probe(model(apiId = "a"))
        assertTrue(result is ProbeResult.Success)
        assertEquals(listOf("a", "b"), updated.models)
        assertEquals(2, updated.probe.size)
        assertTrue(updated.probe.containsKey("a"))
    }

    @Test
    fun `probe 失败时模型原样返回`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        val m = model()
        val (updated, result) = repo.probe(m)
        assertTrue(result is ProbeResult.Fail)
        assertEquals(m.models, updated.models)
        assertTrue(updated.probe.isEmpty())
    }

    // ---------- 纯函数 ----------
    @Test
    fun `originOf 提取 scheme host 与端口`() {
        assertEquals("https://api.example.com", repo.originOf("https://api.example.com/v1"))
        assertEquals("http://localhost:8080", repo.originOf("http://localhost:8080/chat/completions"))
        assertEquals("", repo.originOf(""))
    }

    @Test
    fun `resolveHeaders 解析 JSON 请求头`() {
        assertEquals(mapOf("X-A" to "1", "X-B" to "2"), repo.resolveHeaders("""{"X-A":"1","X-B":"2"}"""))
        assertEquals(emptyMap<String, String>(), repo.resolveHeaders("not json"))
        assertEquals(emptyMap<String, String>(), repo.resolveHeaders(""))
    }

    @Test
    fun `chatTarget 完整映射自定义模型`() {
        val m = CustomModel(
            id = "c1", name = "N", apiId = "m", base = "https://x.com/v1", key = "k",
            headers = """{"X":"1"}""", skipTLS = true,
        )
        val t = repo.chatTarget(m)
        assertEquals("https://x.com/v1", t.endpoint)
        assertEquals("m", t.model)
        assertEquals("k", t.apiKey)
        assertEquals(mapOf("X" to "1"), t.headers)
        assertTrue(t.skipTLS)
        assertTrue(t.isConfigured)
    }

    // ---------- DAO 桩:本测试仅覆盖不触碰数据库的网络/纯逻辑方法 ----------
    private class NoopDao : FreebuffDao {
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
        override suspend fun allSettings(): List<SettingEntity> = emptyList()
        override suspend fun setting(key: String): SettingEntity? = null
        override fun observeSetting(key: String): Flow<SettingEntity?> = flowOf(null)
        override suspend fun upsertSetting(setting: SettingEntity) = unsupported()
        override suspend fun deleteSetting(key: String) = unsupported()
        override suspend fun clearSettings() = unsupported()

        override suspend fun allMemories(): List<MemoryEntity> = emptyList()
        override fun observeMemories(): Flow<List<MemoryEntity>> = flowOf(emptyList())
        override suspend fun upsertMemory(memory: MemoryEntity) = unsupported()
        override suspend fun deleteMemory(block: String) = unsupported()
        override suspend fun clearMemories() = unsupported()
        override suspend fun memoryEntries(userId: String): List<MemoryEntryEntity> = unsupported()
        override fun observeMemoryEntries(): Flow<List<MemoryEntryEntity>> = flowOf(emptyList())
        override suspend fun upsertMemoryEntry(entry: MemoryEntryEntity): Long = unsupported()
        override suspend fun deleteMemoryEntry(id: Long) = unsupported()
        override suspend fun clearMemoryEntries() = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("网络测试不应触碰 DAO")
    }
}
