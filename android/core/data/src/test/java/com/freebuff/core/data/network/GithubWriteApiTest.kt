package com.freebuff.core.data.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GitHub 写路径:分支 → 提交 → PR 的请求形状、响应解析与错误分类。
 * 「一次提交多文件是**一个** commit」这条语义在这里被断言死(blobs → tree → commit → 更新 ref)。
 */
class GithubWriteApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: GithubApi
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        api = GithubApi(client, webBase = base, apiBase = base)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun ok(body: String) = MockResponse().setBody(body)

    @Test
    fun `列分支匿名不带 Authorization 且解析名称与 sha`() = runTest {
        server.enqueue(ok("""[{"name":"main","commit":{"sha":"aaaa111"}},{"name":"dev","commit":{"sha":"bbbb222"}}]"""))
        val branches = (api.listBranches("", "me", "notes", 30) as ApiResult.Ok).data
        val req = server.takeRequest()
        assertEquals("/repos/me/notes/branches?per_page=30", req.path)
        assertNull("空 token 不该发 Authorization(否则公开仓库也 401)", req.getHeader("Authorization"))
        assertEquals(listOf("main", "dev"), branches.map { it.name })
        assertEquals("aaaa111", branches[0].sha)
    }

    @Test
    fun `已连接账号时列分支带 Bearer`() = runTest {
        server.enqueue(ok("[]"))
        api.listBranches("tok", "me", "notes", 5)
        assertEquals("Bearer tok", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `默认分支从仓库信息取`() = runTest {
        server.enqueue(ok("""{"default_branch":"develop"}"""))
        assertEquals("develop", (api.defaultBranch("tok", "me", "notes") as ApiResult.Ok).data)
    }

    @Test
    fun `分支名里的斜杠保留为路径分隔符`() = runTest {
        server.enqueue(ok("""{"commit":{"sha":"deadbeef"}}"""))
        val sha = (api.branchSha("tok", "me", "notes", "agent/fix-typo") as ApiResult.Ok).data
        assertEquals("deadbeef", sha)
        assertEquals("/repos/me/notes/branches/agent/fix-typo", server.takeRequest().path)
    }

    @Test
    fun `新建分支提交 ref 与起点 sha`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ref":"refs/heads/agent/x"}"""))
        assertTrue(api.createBranch("tok", "me", "notes", "agent/x", "sha1") is ApiResult.Ok)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/repos/me/notes/git/refs", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("refs/heads/agent/x", body.getString("ref"))
        assertEquals("sha1", body.getString("sha"))
    }

    @Test
    fun `多文件提交是一个原子提交 blobs 到 tree 到 commit 再更新 ref`() = runTest {
        // 1) 分支头提交 2) 该提交的 tree 3~4) 两个 blob 5) 新 tree 6) 新 commit 7) 更新分支
        server.enqueue(ok("""{"commit":{"sha":"head1"}}"""))
        server.enqueue(ok("""{"tree":{"sha":"tree0"}}"""))
        server.enqueue(ok("""{"sha":"blobA"}"""))
        server.enqueue(ok("""{"sha":"blobB"}"""))
        server.enqueue(ok("""{"sha":"tree1"}"""))
        server.enqueue(ok("""{"sha":"commit1","html_url":"https://github.com/me/notes/commit/commit1"}"""))
        server.enqueue(ok("""{"ref":"refs/heads/agent/x","object":{"sha":"commit1"}}"""))

        val res = api.commitFiles(
            "tok", "me", "notes", "agent/x", "fix: 拼写",
            listOf(
                GithubApi.FileEdit("README.md", "# hi\n"),
                GithubApi.FileEdit("src/a.kt", "class A"),
            ),
        )
        val r = (res as ApiResult.Ok).data
        assertEquals("commit1", r.sha)
        assertEquals(2, r.files)
        assertEquals("https://github.com/me/notes/commit/commit1", r.url)
        assertEquals("只应有 7 次请求(1 读头 + 1 读 tree + 2 blob + 1 tree + 1 commit + 1 更新 ref)", 7, server.requestCount)

        val headReq = server.takeRequest()
        assertEquals("/repos/me/notes/branches/agent/x", headReq.path)

        val baseTreeReq = server.takeRequest()
        assertEquals("/repos/me/notes/git/commits/head1", baseTreeReq.path)

        // RecordedRequest 的 body 只能读一次:先读进变量再断言
        val blobA = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("# hi\n", blobA.getString("content"))
        assertEquals("utf-8", blobA.getString("encoding"))

        val blobB = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("class A", blobB.getString("content"))

        val treeReq = server.takeRequest()
        assertEquals("/repos/me/notes/git/trees", treeReq.path)
        val treeBody = JSONObject(treeReq.body.readUtf8())
        assertEquals("增量改:新 tree 必须挂在基线 tree 上", "tree0", treeBody.getString("base_tree"))
        val entries = treeBody.getJSONArray("tree")
        assertEquals(2, entries.length())
        assertEquals("README.md", entries.getJSONObject(0).getString("path"))
        assertEquals("blobA", entries.getJSONObject(0).getString("sha"))
        assertEquals("100644", entries.getJSONObject(0).getString("mode"))

        val commitReq = server.takeRequest()
        assertEquals("/repos/me/notes/git/commits", commitReq.path)
        val commitBody = JSONObject(commitReq.body.readUtf8())
        assertEquals("fix: 拼写", commitBody.getString("message"))
        assertEquals("tree1", commitBody.getString("tree"))
        assertEquals("父子关系指向分支原头提交", "head1", commitBody.getJSONArray("parents").getString(0))

        val refReq = server.takeRequest()
        assertEquals("PATCH", refReq.method)
        assertEquals("/repos/me/notes/git/refs/heads/agent/x", refReq.path)
        val refBody = JSONObject(refReq.body.readUtf8())
        assertEquals("commit1", refBody.getString("sha"))
        assertEquals("绝不做强推", false, refBody.getBoolean("force"))
    }

    @Test
    fun `开 PR 提交 head base 与草稿标记并解析链接`() = runTest {
        server.enqueue(ok("""{"number":7,"html_url":"https://github.com/me/notes/pull/7"}"""))
        val pr = (api.createPullRequest("tok", "me", "notes", "agent/x", "main", "fix: 拼写", body = "说明", draft = true) as ApiResult.Ok).data
        assertEquals(7, pr.number)
        assertEquals("https://github.com/me/notes/pull/7", pr.url)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/repos/me/notes/pulls", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("agent/x", body.getString("head"))
        assertEquals("main", body.getString("base"))
        assertEquals("fix: 拼写", body.getString("title"))
        assertEquals("说明", body.getString("body"))
        assertEquals(true, body.getBoolean("draft"))
    }

    @Test
    fun `已授权读文件走 contents API 并带 raw 接受头`() = runTest {
        server.enqueue(MockResponse().setBody("file body"))
        val text = (api.fileContent("tok", "me", "secret", "docs/a b.md", "main") as ApiResult.Ok).data
        assertEquals("file body", text)
        val req = server.takeRequest()
        assertEquals("/repos/me/secret/contents/docs/a%20b.md?ref=main", req.path)
        assertEquals("application/vnd.github.raw", req.getHeader("Accept"))
        assertEquals("Bearer tok", req.getHeader("Authorization"))
    }

    /** 六种状态码都要归类成 Http(带码),执行器据此给不同处方。 */
    @Test
    fun `六种错误状态码都归类为 Http 并保留码`() = runTest {
        listOf(401, 403, 404, 409, 422, 429).forEach { code ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"message":"nope"}"""))
            val r = api.listBranches("tok", "me", "notes", 5)
            val e = (r as ApiResult.Err).error
            assertTrue("$code 应归类 Http,实际 $e", e is ApiError.Http)
            assertEquals(code, (e as ApiError.Http).code)
        }
    }
}
