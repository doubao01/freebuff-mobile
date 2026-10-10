package com.freebuff.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freebuff.core.data.network.ApiResult
import com.freebuff.core.data.network.ProbeResult
import com.freebuff.core.data.repository.CustomModelRepository
import com.freebuff.core.data.repository.GitAuthRepository
import com.freebuff.core.data.repository.GitConnectStep
import com.freebuff.core.data.repository.ModelCatalogRepository
import com.freebuff.core.data.repository.SessionRepository
import com.freebuff.core.data.repository.SettingsRepository
import com.freebuff.core.data.repository.UpdateDownloader
import com.freebuff.core.data.repository.UpdateRepository
import com.freebuff.core.model.CustomModel
import com.freebuff.core.model.GitState
import com.freebuff.core.model.Reasoning
import com.freebuff.core.model.RemoteVersion
import com.freebuff.core.model.RepairReport
import com.freebuff.core.model.ToolPermission
import com.freebuff.core.model.mergedModelList
import com.freebuff.core.model.uid
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.AppNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/** 设置页:主题 / Git 集成 / 自定义模型 / 版本更新。 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val sessionRepo: SessionRepository,
    private val customModelRepo: CustomModelRepository,
    private val catalog: ModelCatalogRepository,
    private val gitAuth: GitAuthRepository,
    private val updateRepo: UpdateRepository,
    private val updateDownloader: UpdateDownloader,
    /** 下载安装包落在应用缓存目录(与 app 的 file_paths.xml 对应)。 */
    @ApplicationContext private val context: Context,
    private val navigator: AppNavigator,
    /** 已安装版本号(BuildConfig.VERSION_NAME)。版本显示以实际安装的包为准,不落库、不可被覆盖。 */
    @Named("appVersion") val appVersion: String,
) : ViewModel() {

    val themeMode: StateFlow<String> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "dark")

    val git: StateFlow<GitState> = settings.git
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GitState())

    val repoParse: StateFlow<String> = settings.repoParse
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "strict")

    val customModels: StateFlow<List<CustomModel>> = customModelRepo.models
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val modelId: StateFlow<String> = settings.modelId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "deepseek-v4-flash")

    /** 工具调用开关(对话请求携带 function calling 工具定义)。 */
    val toolsEnabled: StateFlow<Boolean> = settings.toolsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** 上下文记忆开关(Letta 式记忆块 + save_memory 自编辑)。 */
    val memoryEnabled: StateFlow<Boolean> = settings.memoryEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** 计划模式:开启后写工具对模型不可见(对话页顶部显示状态与退出入口)。 */
    val planMode: StateFlow<Boolean> = settings.planMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 深度思考(思维链)模式:off 不思考 / auto 按模型识别 / on 强制开启。 */
    val reasoningMode: StateFlow<String> = settings.reasoningMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Reasoning.MODE_AUTO)

    /** 工具权限覆写(用户改过的工具;生效分级 = 覆写 ∪ 默认)。 */
    val toolPermissionOverrides: StateFlow<Map<String, ToolPermission>> = settings.toolPermissionOverrides
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 单工具权限变更(与默认一致时自动清除覆写)。 */
    fun setToolPermission(tool: String, p: ToolPermission) {
        viewModelScope.launch { settings.setToolPermission(tool, p) }
    }

    fun setMemoryEnabled(v: Boolean) {
        viewModelScope.launch { settings.setMemoryEnabled(v) }
    }

    /** 切换计划模式(立即生效:下一轮对话请求里就不再包含写工具)。 */
    fun setPlanMode(v: Boolean) {
        viewModelScope.launch {
            settings.setPlanMode(v)
            if (v) navigator.showSnack(context.getString(R.string.set_plan_mode_on_toast))
        }
    }

    /** 切换深度思考模式(立即生效,下一轮请求即携带对应参数)。 */
    fun setReasoningMode(mode: String) {
        viewModelScope.launch {
            settings.setReasoningMode(mode)
            navigator.showSnack(
                when (mode) {
                    Reasoning.MODE_OFF -> context.getString(R.string.set_reasoning_off_toast)
                    Reasoning.MODE_ON -> context.getString(R.string.set_reasoning_on_toast)
                    else -> context.getString(R.string.set_reasoning_auto_toast)
                },
            )
        }
    }

    /* ---------------- 模型 ---------------- */

    /** 当前模型展示名(官方实时/内置 + 自定义),供设置页「模型」组显示。 */
    val modelName: StateFlow<String> = combine(
        catalog.official,
        customModelRepo.models,
        settings.modelId,
    ) { official, customs, id ->
        mergedModelList(official, customs).firstOrNull { it.id == id }?.name ?: id
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun openModelSheet() { navigator.openSheet(AppNavState.SHEET_MODEL) }

    /* ---------------- 会话与数据 ---------------- */

    val sessionCount: StateFlow<Int> = sessionRepo.sessions
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _clearArmed = MutableStateFlow(false)
    val clearArmed: StateFlow<Boolean> = _clearArmed.asStateFlow()

    private var disarmJob: Job? = null

    /**
     * 首次点击只「武装」并在 2.2s 后自动解除(与原型 armedClear 一致),
     * 期间再次点击才真正清除——避免误触直接抹掉全部会话。
     */
    fun armClear() {
        if (_clearArmed.value) return
        _clearArmed.value = true
        disarmJob?.cancel()
        disarmJob = viewModelScope.launch {
            delay(CLEAR_ARM_MS)
            _clearArmed.value = false
        }
    }

    /** 真正清除全部会话(仅在 [clearArmed] 为 true 时由 UI 调用)。 */
    fun clearAllSessions() {
        disarmJob?.cancel()
        _clearArmed.value = false
        viewModelScope.launch {
            sessionRepo.clearAll()
            navigator.showSnack(context.getString(R.string.set_clear_sessions_done))
        }
    }

    /* ---------------- 官方模型目录 ---------------- */

    val catalogLoaded: StateFlow<Boolean> = catalog.loaded
    val catalogConfigured: Boolean get() = catalog.isGatewayConfigured

    private val _catalogError = MutableStateFlow<String?>(null)
    val catalogError: StateFlow<String?> = _catalogError.asStateFlow()

    /** 手动刷新官方目录:成功提示数量,失败提示分类原因。 */
    fun refreshCatalog() {
        viewModelScope.launch {
            when (val r = catalog.refresh()) {
                is ApiResult.Ok -> {
                    _catalogError.value = null
                    navigator.showSnack(context.getString(R.string.set_catalog_updated) + r.data.size + context.getString(R.string.set_models_count_suffix))
                }
                is ApiResult.Err -> {
                    _catalogError.value = r.error.userMessage
                    navigator.showSnack(r.error.userMessage)
                }
            }
        }
    }

    private val _repairReport = MutableStateFlow<RepairReport?>(null)
    val repairReport: StateFlow<RepairReport?> = _repairReport.asStateFlow()

    private val _repairRestored = MutableStateFlow(false)
    val repairRestored: StateFlow<Boolean> = _repairRestored.asStateFlow()

    /** 启动数据迁移完成后,由 app 层注入清洗报告。 */
    fun setRepairReport(report: RepairReport?) {
        _repairReport.value = report
        _repairRestored.value = false
    }

    /* ---------------- 外观 / 解析 ---------------- */

    fun setTheme(mode: String) = viewModelScope.launch { settings.setThemeMode(mode) }

    fun setRepoParse(mode: String) {
        viewModelScope.launch {
            settings.setRepoParse(mode)
            navigator.showSnack(if (mode == "strict") context.getString(R.string.set_repo_strict_switched) else context.getString(R.string.set_repo_loose_switched))
        }
    }

    fun setToolsEnabled(v: Boolean) {
        viewModelScope.launch {
            settings.setToolsEnabled(v)
        }
    }

    fun setModelId(id: String) {
        viewModelScope.launch {
            settings.setModelId(id)
            navigator.showSnack(context.getString(R.string.set_model_switched))
        }
    }

    /* ---------------- Git 集成 ---------------- */

    /** 当前授权阶段:null 表示未在授权中。 */
    private val _gitConnect = MutableStateFlow<GitConnectStep?>(null)
    val gitConnect: StateFlow<GitConnectStep?> = _gitConnect.asStateFlow()

    /**
     * 授权失败原因(未配置 client_id / 网络失败 / 授权被拒)。
     * 在弹层里就地展示:一闪而过的 toast 会与弹层面板重叠,用户容易以为按钮没响应。
     */
    private val _gitError = MutableStateFlow<String?>(null)
    val gitError: StateFlow<String?> = _gitError.asStateFlow()

    private var gitConnectJob: Job? = null

    /**
     * 发起 Git 授权:GitHub 设备流,先上报待授权阶段(用户码 + 验证地址),
     * 轮询到 token 后写入账号状态;未配置 client_id 时以失败阶段结束并提示原因。
     */
    fun connectGit() {
        gitConnectJob?.cancel()
        _gitConnect.value = null
        _gitError.value = null
        gitConnectJob = viewModelScope.launch {
            gitAuth.connect().collect { step ->
                _gitConnect.value = step
                when (step) {
                    is GitConnectStep.Done ->
                        navigator.showSnack(context.getString(R.string.set_git_linked_prefix) + step.state.name)
                    is GitConnectStep.Failed -> {
                        _gitConnect.value = null
                        _gitError.value = step.message
                    }
                    is GitConnectStep.AwaitingUser -> Unit
                }
            }
        }
    }

    /** 取消进行中的授权轮询。 */
    fun cancelGitConnect() {
        gitConnectJob?.cancel()
        gitConnectJob = null
        _gitConnect.value = null
    }

    fun revokeGit() {
        cancelGitConnect()
        _gitError.value = null
        viewModelScope.launch {
            gitAuth.revoke()
            navigator.showSnack(context.getString(R.string.set_git_unlinked))
        }
    }

    /* ---------------- 自定义模型 ---------------- */

    fun addCustomModel(m: CustomModel) {
        viewModelScope.launch { customModelRepo.add(m) }
    }

    fun updateCustomModel(m: CustomModel) {
        viewModelScope.launch { customModelRepo.update(m) }
    }

    fun deleteCustomModel(id: String) {
        viewModelScope.launch { customModelRepo.delete(id) }
    }

    /** 真实测试连接 + 拉取 /v1/models 快照。 */
    fun probeModel(m: CustomModel, onDone: (Pair<CustomModel, ProbeResult>) -> Unit) {
        viewModelScope.launch {
            val pair = customModelRepo.probe(m)
            if (pair.second is ProbeResult.Success && (pair.second as ProbeResult.Success).note.isNotBlank()) {
                navigator.showSnack((pair.second as ProbeResult.Success).note)
            }
            onDone(pair)
        }
    }

    /** 仅拉取端点 /v1/models 列表(不做连接测试),供表单「拉取列表」快速选模型。 */
    fun fetchModelList(m: CustomModel, onResult: (ApiResult<List<String>>) -> Unit) {
        viewModelScope.launch { onResult(customModelRepo.fetchModels(m)) }
    }

    /**
     * 恢复原始记录(清洗前),仅当迁移时报告过修复。
     * 非对象记录(raw 里的 null)无法落库,自动跳过;ID 为空的原始记录在落库前
     * 重新生成 ID,避免空主键破坏后续的更新/删除。
     */
    fun restoreRaw() {
        viewModelScope.launch {
            val raw = _repairReport.value?.raw ?: return@launch
            raw.filterIsInstance<CustomModel>()
                .forEach { customModelRepo.add(it.copy(id = it.id.ifBlank { "cm-" + uid() })) }
            _repairRestored.value = true
            navigator.showSnack(context.getString(R.string.set_repair_restored))
        }
    }

    /* ---------------- 版本更新 ---------------- */

    val updateConfigured: Boolean get() = updateRepo.isConfigured

    /** 当前更新源主机名(失败提示里指明实际请求的地址)。 */
    val updateSource: String get() = updateRepo.sourceLabel

    /** 应用内下载状态(更新面板只读它)。 */
    private val _download = MutableStateFlow<UpdateDownload>(UpdateDownload.Idle)
    val download: StateFlow<UpdateDownload> = _download.asStateFlow()

    private var downloadJob: Job? = null

    /**
     * 检查更新(真实拉取更新源)。
     *
     * 瞬时失败(超时/连接/DNS/5xx)已在数据层静默重试:一次网络抖动不该让用户先看到红字,
     * 大部分情况下重试一次就好了。配置类错误仍然立刻报出来。
     *
     * @param onResult (远程版本, 是否有更新, 失败文案;失败时前两项为 null/false)
     */
    fun checkForUpdate(onResult: (RemoteVersion?, Boolean, String?) -> Unit) {
        viewModelScope.launch {
            when (val r = updateRepo.checkWithRetry()) {
                is ApiResult.Ok -> onResult(r.data, r.data.isNewerThan(appVersion), null)
                is ApiResult.Err -> onResult(null, false, r.error.userMessage)
            }
        }
    }

    /**
     * 下载本次更新到缓存目录并校验指纹(校验不过不会把文件交出去)。
     * 重复点击直接忽略 —— 正在下的那次算数,免得两份大包同时写盘。
     */
    fun startUpdateDownload(remote: RemoteVersion) {
        if (downloadJob?.isActive == true) return
        val url = remote.apkUrl
        if (url.isBlank()) return
        _download.value = UpdateDownload.Running(0L, remote.apkSize)
        downloadJob = viewModelScope.launch {
            // 文件名取自直链:与发布资产的命名一致,用户去缓存目录看也知道是哪个版本
            val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "freebuff-update.apk" }
            val dest = File(File(context.cacheDir, UPDATE_DIR), name)
            val r = updateDownloader.download(url, remote.apkSha256, dest) { received, total ->
                _download.value = UpdateDownload.Running(received, if (total > 0) total else remote.apkSize)
            }
            _download.value = when (r) {
                is ApiResult.Ok -> UpdateDownload.Ready(r.data)
                is ApiResult.Err -> UpdateDownload.Failed(r.error.userMessage)
            }
        }
    }

    /** 取消下载并清掉状态(面板重新打开时用,不然会一直停在上一轮的进度或错误上)。 */
    fun resetUpdateDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _download.value = UpdateDownload.Idle
    }

    private companion object {
        /** 「清除全部会话」二次确认的自动解除时长(毫秒)。 */
        const val CLEAR_ARM_MS = 2200L

        /** 下载好的安装包放这里(与 app 的 file_paths.xml 一致)。 */
        const val UPDATE_DIR = "update"
    }
}
