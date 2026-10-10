package com.freebuff.core.data.network

import com.freebuff.core.model.RepoItem
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 设备流挑战:用户在浏览器输入 [userCode] 完成授权。 */
data class DeviceChallenge(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSec: Int,
    val expiresInSec: Int,
)

/** 设备流单次轮询结果。 */
sealed interface DevicePoll {
    data class Granted(val accessToken: String) : DevicePoll
    data object Pending : DevicePoll
    data class SlowDown(val intervalSec: Int) : DevicePoll
    data class Denied(val reason: String) : DevicePoll
}

/** GitHub 账号。 */
data class GitAccount(val login: String, val name: String)

/**
 * GitHub REST 接入:OAuth 设备流 + 账号/仓库只读查询。
 *
 * 选设备流而非回调授权的原因:无需自定义 scheme 回调与后端中转小服务,
 * 只需一枚 OAuth App 的 client_id,适合纯客户端原型/早期版本。
 *
 * 端点可在构造时覆盖(单测指向 MockWebServer)。
 *
 * 通过 [com.freebuff.core.data.di.DataModule] 提供单例;测试可直接构造并指向 MockWebServer。
 */
class GithubApi(
    private val client: OkHttpClient,
    private val webBase: String = GITHUB_WEB,
    private val apiBase: String = GITHUB_API,
) {
    /** 1) 申请设备码与用户码。 */
    suspend fun startDeviceFlow(
        clientId: String,
        scope: String = "repo read:user",
    ): ApiResult<DeviceChallenge> = apiCallIo {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", scope)
            .build()
        val req = Request.Builder()
            .url("$webBase/login/device/code")
            .header("Accept", "application/json")
            .header("User-Agent", FreebuffApi.USER_AGENT)
            .post(form)
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(300))
            val o = JSONObject(text)
            val deviceCode = o.optString("device_code")
            val userCode = o.optString("user_code")
            if (deviceCode.isBlank() || userCode.isBlank()) throw ApiError.Parse("设备码响应缺少字段")
            DeviceChallenge(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUri = o.optString("verification_uri").ifBlank { "$webBase/login/device" },
                intervalSec = o.optInt("interval", 5).coerceAtLeast(1),
                expiresInSec = o.optInt("expires_in", 900).coerceAtLeast(30),
            )
        }
    }

    /** 2) 轮询换取 access_token。 */
    suspend fun pollToken(clientId: String, deviceCode: String): ApiResult<DevicePoll> = apiCallIo {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val req = Request.Builder()
            .url("$webBase/login/oauth/access_token")
            .header("Accept", "application/json")
            .header("User-Agent", FreebuffApi.USER_AGENT)
            .post(form)
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(300))
            val o = JSONObject(text)
            val token = o.optString("access_token")
            if (token.isNotBlank()) {
                DevicePoll.Granted(token)
            } else {
                when (val err = o.optString("error")) {
                    "authorization_pending" -> DevicePoll.Pending
                    "slow_down" -> DevicePoll.SlowDown(o.optInt("interval", 10).coerceAtLeast(1))
                    "expired_token" -> DevicePoll.Denied("设备码已过期")
                    "access_denied" -> DevicePoll.Denied("授权被拒绝")
                    "" -> throw ApiError.Parse("授权响应缺少 access_token")
                    else -> DevicePoll.Denied(err)
                }
            }
        }
    }

    /** 3) 查询当前账号。 */
    suspend fun currentUser(token: String): ApiResult<GitAccount> = apiCallIo {
        val req = apiRequest("$apiBase/user", token).get().build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(300))
            val o = JSONObject(text)
            val login = o.optString("login")
            if (login.isBlank()) throw ApiError.Parse("用户信息缺少 login")
            GitAccount(login = login, name = o.optString("name").ifBlank { login })
        }
    }

    /** 4) 读取账号仓库(按最近更新排序,单页 100)。 */
    suspend fun repos(token: String, perPage: Int = 100): ApiResult<List<RepoItem>> = apiCallIo {
        val url = "$apiBase/user/repos?per_page=$perPage&sort=updated&affiliation=owner,collaborator,organization_member"
        val req = apiRequest(url, token).get().build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(300))
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val full = o.optString("full_name")
                if (full.isBlank()) return@mapNotNull null
                RepoItem(
                    name = full,
                    branch = o.optString("default_branch").ifBlank { "main" },
                    desc = o.optString("description").ifBlank {
                        if (o.optBoolean("private")) "私有仓库" else "公开仓库"
                    },
                )
            }
        }
    }

    /* ---------------- 写路径(需要 repo scope 的 token) ---------------- */

    /** 分支信息:名称 + 头提交 sha。 */
    data class BranchInfo(val name: String, val sha: String)

    /** 一次提交的结果。 */
    data class CommitResult(val sha: String, val url: String, val files: Int)

    /** 开的 PR。 */
    data class PullRequestInfo(val number: Int, val url: String)

    /** 一个待提交的文件(全文内容)。 */
    data class FileEdit(val path: String, val content: String)

    /** 列分支(按仓库的字母序首页,单页上限 100)。 */
    suspend fun listBranches(
        token: String,
        owner: String,
        repo: String,
        limit: Int = 30,
    ): ApiResult<List<BranchInfo>> = apiCallIo {
        val url = "$apiBase/repos/$owner/$repo/branches?per_page=" + limit.coerceIn(1, 100)
        client.newCall(apiRequest(url, token).get().build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(300))
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name")
                if (name.isBlank()) return@mapNotNull null
                BranchInfo(name, o.optJSONObject("commit")?.optString("sha").orEmpty())
            }
        }
    }

    /** 仓库默认分支名([github_create_branch] 的 from 缺省值)。 */
    suspend fun defaultBranch(token: String, owner: String, repo: String): ApiResult<String> = apiCallIo {
        client.newCall(apiRequest("$apiBase/repos/$owner/$repo", token).get().build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(300))
            JSONObject(text).optString("default_branch").ifBlank { "main" }
        }
    }

    /**
     * 分支头提交的 sha;分支不存在时抛 404(调用方据此给「先列分支」的处方)。
     * 用 `/branches/{branch}` 而不是 `/git/ref/heads/{branch}`:前者同时接受含 `/` 的分支名。
     */
    suspend fun branchSha(token: String, owner: String, repo: String, branch: String): ApiResult<String> = apiCallIo {
        val url = "$apiBase/repos/$owner/$repo/branches/" + refPath(branch)
        client.newCall(apiRequest(url, token).get().build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(300))
            JSONObject(text).optJSONObject("commit")?.optString("sha").orEmpty()
        }
    }

    /** 新建分支(从 [sha] 指向的提交拉出)。 */
    suspend fun createBranch(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        sha: String,
    ): ApiResult<Unit> = apiCallIo {
        val body = JSONObject()
            .put("ref", "refs/heads/$branch")
            .put("sha", sha)
            .toString()
        client.newCall(apiRequest("$apiBase/repos/$owner/$repo/git/refs", token).post(jsonBody(body)).build())
            .execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(300))
            }
    }

    /**
     * 多文件**一次原子提交**:blobs → tree → commit → 更新 ref。
     *
     * 为什么要一个提交而不是逐个文件提交:每个文件一次提交会让历史变成噪声,
     * 也让「这次改动做了啥」无法作为整体回滚/评审(与后端的 str_replace / write_file
     * 一次改动一次提交的语义对齐)。
     */
    suspend fun commitFiles(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        message: String,
        files: List<FileEdit>,
    ): ApiResult<CommitResult> = apiCallIo {
        fun call(url: String, method: String, body: JSONObject?): JSONObject {
            val b = Request.Builder().url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", FreebuffApi.USER_AGENT)
                .header("Authorization", "Bearer $token")
            val req = if (method == "PATCH") b.patch(jsonBody(body.toString()))
            else b.post(jsonBody((body ?: JSONObject()).toString()))
            // 块体函数必须显式 return(写成最后一个表达式不构成返回值)
            return client.newCall(req.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(300))
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        }

        // 1) 目标分支的当前头提交(必须已存在)
        var head = ""
        client.newCall(apiRequest("$apiBase/repos/$owner/$repo/branches/" + refPath(branch), token).get().build())
            .execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(300))
                head = JSONObject(text).optJSONObject("commit")?.optString("sha").orEmpty()
            }
        if (head.isBlank()) throw ApiError.Parse("分支 " + branch + " 缺少头提交 sha")

        // 2) 基线 tree(在该 tree 上增量改文件,不动仓库里的其他文件)
        val baseTree = call("$apiBase/repos/$owner/$repo/git/commits/$head", "GET", null)
            .optJSONObject("tree")?.optString("sha").orEmpty()

        // 3) 逐个上传 blob
        val entries = JSONArray()
        files.forEach { f ->
            val blob = call(
                "$apiBase/repos/$owner/$repo/git/blobs", "POST",
                JSONObject().put("content", f.content).put("encoding", "utf-8"),
            ).optString("sha")
            if (blob.isBlank()) throw ApiError.Parse("blob 上传失败:" + f.path)
            entries.put(
                JSONObject().put("path", f.path).put("mode", "100644").put("type", "blob").put("sha", blob),
            )
        }
        if (entries.length() == 0) throw ApiError.Parse("没有要提交的文件")

        // 4) 新 tree
        val treeBody = JSONObject().put("tree", entries)
        if (baseTree.isNotBlank()) treeBody.put("base_tree", baseTree)
        val tree = call("$apiBase/repos/$owner/$repo/git/trees", "POST", treeBody).optString("sha")
        if (tree.isBlank()) throw ApiError.Parse("tree 创建失败")

        // 5) 提交(单个 parent = 分支原头提交)
        val commit = call(
            "$apiBase/repos/$owner/$repo/git/commits", "POST",
            JSONObject().put("message", message).put("tree", tree)
                .put("parents", JSONArray().put(head)),
        )
        val commitSha = commit.optString("sha")
        if (commitSha.isBlank()) throw ApiError.Parse("commit 创建失败")

        // 6) 把分支指到新提交(非 fast-forward 时会被拒:分支在期间被推新)
        call(
            "$apiBase/repos/$owner/$repo/git/refs/heads/" + refPath(branch), "PATCH",
            JSONObject().put("sha", commitSha).put("force", false),
        )
        CommitResult(sha = commitSha, url = commit.optString("html_url"), files = files.size)
    }

    /** 开 Pull Request。 */
    suspend fun createPullRequest(
        token: String,
        owner: String,
        repo: String,
        head: String,
        base: String,
        title: String,
        body: String = "",
        draft: Boolean = false,
    ): ApiResult<PullRequestInfo> = apiCallIo {
        val payload = JSONObject()
            .put("title", title)
            .put("head", head)
            .put("base", base)
            .put("draft", draft)
        if (body.isNotBlank()) payload.put("body", body)
        client.newCall(apiRequest("$apiBase/repos/$owner/$repo/pulls", token).post(jsonBody(payload.toString())).build())
            .execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiError.Http(r.code, text.take(300))
                val o = JSONObject(text)
                PullRequestInfo(number = o.optInt("number"), url = o.optString("html_url"))
            }
    }

    /**
     * 已授权读取文件原文(可读私有仓库);未授权时调用方走匿名 raw 通道。
     * 用 contents API + `Accept: raw`,而不是拼 raw.githubusercontent(X-GitHub 只认前者带鉴权)。
     */
    suspend fun fileContent(
        token: String,
        owner: String,
        repo: String,
        path: String,
        ref: String = "",
    ): ApiResult<String> = apiCallIo {
        val url = StringBuilder("$apiBase/repos/$owner/$repo/contents/")
            .append(path.split("/").joinToString("/") { urlEncode(it) })
            .apply { if (ref.isNotBlank()) append("?ref=").append(urlEncode(ref)) }
            .toString()
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github.raw")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", FreebuffApi.USER_AGENT)
            .header("Authorization", "Bearer $token")
            .get().build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError.Http(r.code, "")
            text
        }
    }

    /** 路径片段编码:分支名里的 `/` 要保留(它就是路径分隔符),其余逐段转义。 */
    private fun refPath(ref: String): String = ref.split("/").joinToString("/") { urlEncode(it) }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun jsonBody(json: String): okhttp3.RequestBody =
        json.toRequestBody("application/json; charset=utf-8".toMediaType())

    private fun apiRequest(url: String, token: String): Request.Builder {
        val b = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", FreebuffApi.USER_AGENT)
        // 公开仓库的只读接口允许匿名访问:空 token 时**不发** Authorization 头
        // (发一个 "Bearer " 空值会被 GitHub 当成坏凭据回 401,把本来能读的公开数据也堵住)
        if (token.isNotBlank()) b.header("Authorization", "Bearer $token")
        return b
    }

    companion object {
        const val GITHUB_WEB = "https://github.com"
        const val GITHUB_API = "https://api.github.com"
    }
}
