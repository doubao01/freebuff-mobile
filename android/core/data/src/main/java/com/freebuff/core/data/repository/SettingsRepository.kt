package com.freebuff.core.data.repository

import com.freebuff.core.data.db.FreebuffDao
import com.freebuff.core.data.db.SettingEntity
import com.freebuff.core.model.GitState
import com.freebuff.core.model.Reasoning
import com.freebuff.core.model.ToolPermission
import com.freebuff.core.model.ToolPermissions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** 应用级键值设置(主题/当前模型/解析模式/Git 状态/登录态)。 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dao: FreebuffDao,
) {
    companion object {
        const val KEY_THEME_MODE = "themeMode"
        const val KEY_MODEL_ID = "modelId"
        const val KEY_REPO_PARSE = "repoParse"
        const val KEY_SIGNED_IN = "signedIn"
        const val KEY_GIT = "git"

        /** 工具调用开关(对话请求是否携带 function calling 工具定义)。 */
        const val KEY_TOOLS_ENABLED = "toolsEnabled"

        /** 上下文记忆开关(Letta 式记忆块注入 + save_memory 自编辑)。 */
        const val KEY_MEMORY_ENABLED = "memoryEnabled"

        /** 计划模式:开启时写工具对模型不可见(与后端 plan 模式同构)。 */
        const val KEY_PLAN_MODE = "planMode"

        /** 深度思考(思维链)模式:off / auto / on,取值见 core:model 的 Reasoning。 */
        const val KEY_REASONING_MODE = "reasoningMode"

        /** 工具权限映射(工具名 → allow/confirm/deny,JSON;见 ToolPermissions)。 */
        const val KEY_TOOL_PERMISSIONS = ToolPermissions.SETTINGS_KEY

        /** Git OAuth access_token(Keystore 加密后的密文)。 */
        const val KEY_GIT_TOKEN = "gitToken"
    }

    /** Git 状态:直接响应 DB 变更。 */
    val git: Flow<GitState> = dao.observeSetting(KEY_GIT)
        .map { it?.value.orEmpty() }
        .map { s ->
            if (s.isBlank()) GitState()
            else try {
                val o = JSONObject(s)
                GitState(o.optBoolean("connected"), o.optString("provider"), o.optString("login"), o.optString("name"))
            } catch (e: Exception) {
                GitState()
            }
        }
        .distinctUntilChanged()

    suspend fun setGit(g: GitState) {
        dao.upsertSetting(
            SettingEntity(
                KEY_GIT,
                JSONObject().apply {
                    put("connected", g.connected)
                    put("provider", g.provider)
                    put("login", g.login)
                    put("name", g.name)
                }.toString(),
            ),
        )
    }

    suspend fun connectGit(provider: String, login: String, name: String) =
        setGit(GitState(true, provider, login, name))

    suspend fun revokeGit() = setGit(GitState())

    /** 保存/清空 Git access_token 密文(加解密由 RealGitAuthRepository 负责)。 */
    suspend fun setGitToken(cipher: String) = setString(KEY_GIT_TOKEN, cipher)

    suspend fun getGitToken(): String = getString(KEY_GIT_TOKEN, "")

    // ---------- 简单键值 ----------
    suspend fun setString(key: String, value: String) = dao.upsertSetting(SettingEntity(key, value))

    fun getStringFlow(key: String, default: String): Flow<String> =
        dao.observeSetting(key).map { it?.value ?: default }
            .distinctUntilChanged()

    suspend fun getString(key: String, default: String): String =
        dao.setting(key)?.value ?: default

    suspend fun setBool(key: String, value: Boolean) = setString(key, value.toString())

    suspend fun getBool(key: String, default: Boolean): Boolean =
        getString(key, default.toString()).toBoolean()

    // ---------- 便捷访问 ----------
    val themeMode = getStringFlow(KEY_THEME_MODE, "dark")
    // 默认空 = 尚未选择模型(官方模型来自网关实时目录,自定义模型由用户添加)
    val modelId = getStringFlow(KEY_MODEL_ID, "")
    val repoParse = getStringFlow(KEY_REPO_PARSE, "strict")
    val signedIn = getStringFlow(KEY_SIGNED_IN, "false").map { it.toBoolean() }

    /** 工具调用开关:开启后对话请求携带工具定义,模型可触发 function calling(默认开)。 */
    val toolsEnabled = getStringFlow(KEY_TOOLS_ENABLED, "true").map { it.toBoolean() }

    /** 上下文记忆开关:开启后注入核心记忆块并允许模型 save_memory(默认开)。 */
    val memoryEnabled = getStringFlow(KEY_MEMORY_ENABLED, "true").map { it.toBoolean() }

    /**
     * 计划模式开关:开启后写工具(github_create_branch / github_commit_files /
     * github_create_pull_request)从模型可见的工具集里物理剔除(默认关)。
     */
    val planMode = getStringFlow(KEY_PLAN_MODE, "false").map { it.toBoolean() }

    /** 深度思考(思维链)模式:默认自动 —— 按模型名识别思考参数,不认识的模型不送字段。 */
    val reasoningMode = getStringFlow(KEY_REASONING_MODE, Reasoning.MODE_AUTO)

    /** 工具权限覆写(仅用户显式改过的工具;生效分级 = 覆写 ∪ 默认,见 ToolPermissions.effective)。 */
    val toolPermissionOverrides = getStringFlow(KEY_TOOL_PERMISSIONS, "")
        .map { ToolPermissions.decode(it) }
        .distinctUntilChanged()

    suspend fun setThemeMode(mode: String) = setString(KEY_THEME_MODE, mode)
    suspend fun setModelId(id: String) = setString(KEY_MODEL_ID, id)
    suspend fun setRepoParse(mode: String) = setString(KEY_REPO_PARSE, mode)
    suspend fun setSignedIn(v: Boolean) = setBool(KEY_SIGNED_IN, v)
    suspend fun setToolsEnabled(v: Boolean) = setBool(KEY_TOOLS_ENABLED, v)

    suspend fun setMemoryEnabled(v: Boolean) = setBool(KEY_MEMORY_ENABLED, v)

    suspend fun setPlanMode(v: Boolean) = setBool(KEY_PLAN_MODE, v)

    suspend fun setReasoningMode(mode: String) = setString(KEY_REASONING_MODE, mode)

    /** 写入单工具的权限覆写;与默认一致时清除条目(设置回归默认,存储保持最小)。 */
    suspend fun setToolPermission(tool: String, p: ToolPermission) {
        val cur = ToolPermissions.decode(getString(KEY_TOOL_PERMISSIONS, "")).toMutableMap()
        if (p == ToolPermissions.defaultFor(tool)) cur.remove(tool) else cur[tool] = p
        setString(KEY_TOOL_PERMISSIONS, ToolPermissions.encode(cur))
    }

    /** 清空全部权限覆写(恢复默认分级)。 */
    suspend fun resetToolPermissions() = setString(KEY_TOOL_PERMISSIONS, "")
}
