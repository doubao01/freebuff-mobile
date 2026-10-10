package com.freebuff.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freebuff.core.data.network.ApiResult
import com.freebuff.core.data.repository.CustomModelRepository
import com.freebuff.core.data.repository.GitAuthRepository
import com.freebuff.core.data.repository.ModelCatalogRepository
import com.freebuff.core.data.repository.SessionRepository
import com.freebuff.core.data.repository.SettingsRepository
import com.freebuff.core.model.CustomModel
import com.freebuff.core.model.GitState
import com.freebuff.core.model.OfficialModel
import com.freebuff.core.model.RepoItem
import com.freebuff.core.model.TaskDraft
import com.freebuff.core.model.mergedModelList
import com.freebuff.core.model.parseClone
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.AppNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * 发起任务三步向导:仓库 → 模型 → 描述。草稿状态本模块持有,
 * 最终提交时创建会话并交给对话页(根层触发真实对话)。
 */
@HiltViewModel
class TaskWizardViewModel @Inject constructor(
    private val sessionRepo: SessionRepository,
    private val customModelRepo: CustomModelRepository,
    private val catalog: ModelCatalogRepository,
    private val gitAuth: GitAuthRepository,
    private val settings: SettingsRepository,
    private val navigator: AppNavigator,
    // 提示条文案在多语言下要跟着系统语言走;ViewModel 只能靠注入的 Context 读资源
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _draft = MutableStateFlow(TaskDraft())
    val draft: StateFlow<TaskDraft> = _draft.asStateFlow()

    /** Git 账号仓库(GitHub OAuth 实时查询;未关联/未配置时为空并给出错误提示)。 */
    private val reposFlow = MutableStateFlow<List<RepoItem>>(emptyList())
    val repos: StateFlow<List<RepoItem>> = reposFlow.asStateFlow()

    private val _reposLoading = MutableStateFlow(false)
    val reposLoading: StateFlow<Boolean> = _reposLoading.asStateFlow()

    private val _reposError = MutableStateFlow<String?>(null)
    val reposError: StateFlow<String?> = _reposError.asStateFlow()

    init {
        loadRepos()
    }

    /** 拉取 Git 仓库列表;失败时保留为空并给出可展示的错误文案。 */
    fun loadRepos() {
        viewModelScope.launch {
            _reposLoading.value = true
            _reposError.value = null
            when (val r = gitAuth.repos()) {
                is ApiResult.Ok -> reposFlow.value = r.data
                is ApiResult.Err -> {
                    reposFlow.value = emptyList()
                    _reposError.value = r.error.userMessage
                }
            }
            _reposLoading.value = false
        }
    }

    val customModels: StateFlow<List<CustomModel>> = customModelRepo.models
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 当前对话模型(设置里 modelId 的即时值)。
     * 向导草稿默认应以它为准;初值留空 —— [TaskDraft] 不再带演示模型,
     * 未配置任何模型时向导就应显示「未选择」而不是一个并不存在的模型名。
     */
    val currentModelId: StateFlow<String> = settings.modelId
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val modelList: StateFlow<List<OfficialModel>> = combine(catalog.official, customModels) { official, customs ->
        mergedModelList(official, customs)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        mergedModelList(catalog.official.value, customModels.value),
    )

    val repoParse: StateFlow<String> = settings.repoParse
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "strict")

    val git: StateFlow<GitState> = settings.git
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GitState())

    /** 提交互斥:launch 的 viewModelScope.launch 有挂起点,双击可在同一帧内双双通过 isBlank 校验。 */
    private val launching = AtomicBoolean(false)

    fun update(transform: (TaskDraft) -> TaskDraft) { _draft.update(transform) }

    /**
     * 草稿模型默认沿用当前对话模型:向导里没显式改模型时,不应把用户在设置/对话页
     * 选好的模型悄悄换掉(TaskDraft 的硬编码默认值只在读不到设置时兜底)。
     */
    fun syncDraftModel() {
        val current = currentModelId.value
        if (current.isNotBlank() && _draft.value.modelId != current) {
            _draft.update { it.copy(modelId = current) }
        }
    }

    fun prev() { _draft.update { it.copy(step = it.step - 1) } }

    fun next() {
        val d = _draft.value
        when (d.step) {
            0 -> {
                val ok = when (d.repoMode) {
                    "manual" -> d.repoUrl.isNotBlank() &&
                        (repoParse.value != "strict" || parseClone(d.repoUrl) != null || d.repoUrl.contains("/"))
                    "git" -> d.repoName.isNotBlank()
                    else -> true
                }
                if (ok) _draft.update { it.copy(step = 1) }
                else navigator.showSnack(
                    context.getString(
                        if (d.repoMode == "git") R.string.chat_wizard_err_no_repo
                        else R.string.chat_wizard_err_repo_incomplete,
                    ),
                )
            }
            else -> _draft.update { it.copy(step = (it.step + 1).coerceAtMost(2)) }
        }
    }

    /**
     * 提交:创建会话 → 关闭向导 → 切到对话页,并通过 [onLaunched] 通知根层
     * 触发首条消息的真实对话。
     */
    fun launch(onLaunched: (sessionId: String, desc: String, ctxRepo: String, ctxModel: String) -> Unit) {
        val d = _draft.value
        if (d.desc.isBlank()) {
            navigator.showSnack(context.getString(R.string.chat_wizard_err_no_desc))
            return
        }
        if (d.desc.length > DESC_MAX) {
            navigator.showSnack(context.getString(R.string.chat_wizard_err_desc_too_long, DESC_MAX))
            return
        }
        // 快速双击守卫:提交中有挂起点,不加锁会创建两个会话
        if (!launching.compareAndSet(false, true)) return
        val ctxRepo = when (d.repoMode) {
            "manual" -> d.repoUrl.ifBlank { d.repoName }
            "git" -> d.repoName
            else -> ""
        }
        val ctxModel = modelList.value.firstOrNull { it.id == d.modelId }?.name ?: d.modelId
        viewModelScope.launch {
            try {
                // 把向导所选模型写回设置,首条对话按此模型真实请求。
                // 没有选过模型时不写:否则会把空/无效 id 盖掉设置里已选好的模型。
                if (d.modelId.isNotBlank()) settings.setModelId(d.modelId)
                val s = sessionRepo.create(context.getString(R.string.chat_title_new))
                val desc = d.desc
                // 重置草稿但保留刚用过的模型:下次开向导仍默认同一个模型
                _draft.value = TaskDraft(modelId = d.modelId)
                navigator.closeSheet()
                navigator.openSession(s.id)
                navigator.navigate(AppNavState.ROUTE_CHAT)
                onLaunched(s.id, desc, ctxRepo, ctxModel)
            } finally {
                launching.set(false)
            }
        }
    }

    private companion object {
        /** 任务描述上限(与 UI 字数计数一致)。 */
        const val DESC_MAX = 500
    }
}
