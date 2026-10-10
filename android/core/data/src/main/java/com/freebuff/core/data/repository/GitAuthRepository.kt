package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.ApiResult
import com.freebuff.core.data.network.DevicePoll
import com.freebuff.core.data.network.GithubApi
import com.freebuff.core.data.security.CryptoManager
import com.freebuff.core.model.GitState
import com.freebuff.core.model.RepoItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** 授权流程阶段。 */
sealed interface GitConnectStep {
    /** 等待用户在浏览器完成授权(展示用户码与验证地址)。 */
    data class AwaitingUser(val userCode: String, val verificationUri: String) : GitConnectStep

    /** 授权完成,已写入账号状态。 */
    data class Done(val state: GitState) : GitConnectStep

    /** 授权失败(可展示文案)。 */
    data class Failed(val message: String) : GitConnectStep
}

/**
 * Git 账号接入抽象:GitHub OAuth 设备流。
 * 未配置 client_id 时 [connect] 直接以 [GitConnectStep.Failed] 报「未配置」,
 * 不做任何账号/仓库的假数据回退。
 */
interface GitAuthRepository {
    /** 发起授权并以流上报阶段;取消收集即中止轮询。 */
    fun connect(): Flow<GitConnectStep>

    suspend fun revoke()

    /** 读取账号仓库;未授权时返回 [ApiError.NotConfigured]。 */
    suspend fun repos(): ApiResult<List<RepoItem>>
}

/**
 * 实现:GitHub OAuth 设备流。
 *
 * 1. POST /login/device/code 取 device_code + user_code
 * 2. 上报 [GitConnectStep.AwaitingUser],按 interval 轮询 /login/oauth/access_token
 * 3. 取得 token → 加密落库 → GET /user 校验并写入账号状态
 */
@Singleton
class RealGitAuthRepository @Inject constructor(
    @Named("githubClientId") private val clientId: String,
    private val github: GithubApi,
    private val settings: SettingsRepository,
    private val crypto: CryptoManager,
) : GitAuthRepository, GitTokenProvider {

    /** 执行层(写工具)读 token 的口:未授权时返回空串。 */
    override suspend fun token(): String = readToken()

    override fun connect(): Flow<GitConnectStep> = flow {
        if (clientId.isBlank()) {
            emit(GitConnectStep.Failed(ApiError.NotConfigured("GitHub OAuth client_id").userMessage))
            return@flow
        }
        val challenge = when (val r = github.startDeviceFlow(clientId)) {
            is ApiResult.Ok -> r.data
            is ApiResult.Err -> {
                emit(GitConnectStep.Failed(r.error.userMessage))
                return@flow
            }
        }
        emit(GitConnectStep.AwaitingUser(challenge.userCode, challenge.verificationUri))

        var interval = challenge.intervalSec
        val deadline = System.currentTimeMillis() + challenge.expiresInSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            when (val poll = github.pollToken(clientId, challenge.deviceCode)) {
                is ApiResult.Err -> {
                    emit(GitConnectStep.Failed(poll.error.userMessage))
                    return@flow
                }
                is ApiResult.Ok -> when (val v = poll.data) {
                    is DevicePoll.Granted -> {
                        saveToken(v.accessToken)
                        when (val u = github.currentUser(v.accessToken)) {
                            is ApiResult.Ok -> {
                                val st = GitState(true, "GitHub", u.data.login, u.data.name)
                                settings.setGit(st)
                                emit(GitConnectStep.Done(st))
                            }
                            is ApiResult.Err -> emit(GitConnectStep.Failed(u.error.userMessage))
                        }
                        return@flow
                    }
                    is DevicePoll.SlowDown -> interval = v.intervalSec.coerceAtLeast(1)
                    DevicePoll.Pending -> Unit
                    is DevicePoll.Denied -> {
                        emit(GitConnectStep.Failed("授权失败:" + v.reason))
                        return@flow
                    }
                }
            }
        }
        emit(GitConnectStep.Failed("授权超时,请重新发起"))
    }

    override suspend fun revoke() {
        settings.setGitToken("")
        settings.revokeGit()
    }

    override suspend fun repos(): ApiResult<List<RepoItem>> {
        val token = readToken()
        if (token.isBlank()) return ApiResult.Err(ApiError.NotConfigured("Git 账号授权"))
        return github.repos(token)
    }

    /* ---------------- token 存取(Keystore 加密) ---------------- */

    private suspend fun saveToken(token: String) {
        settings.setGitToken(if (token.isBlank()) "" else crypto.encrypt(token))
    }

    private suspend fun readToken(): String {
        val stored = settings.getGitToken()
        if (stored.isBlank()) return ""
        return crypto.decrypt(stored)
    }
}
