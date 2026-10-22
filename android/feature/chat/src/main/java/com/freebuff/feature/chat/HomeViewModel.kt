package com.freebuff.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freebuff.core.data.repository.CustomModelRepository
import com.freebuff.core.data.repository.DeletedSession
import com.freebuff.core.data.repository.ModelCatalogRepository
import com.freebuff.core.data.repository.SessionRepository
import com.freebuff.core.data.repository.SettingsRepository
import com.freebuff.core.model.Session
import com.freebuff.core.model.mergedModelList
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.AppNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** 会话列表页:读取会话流,支持按标题/预览搜索,打开/删除(可撤销)。 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sessionRepo: SessionRepository,
    private val customModelRepo: CustomModelRepository,
    private val catalog: ModelCatalogRepository,
    private val settings: SettingsRepository,
    private val navigator: AppNavigator,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** 首页模型胶囊显示的当前模型名(官方网关实时 + 自定义);未选择时为空串。 */
    val modelName: StateFlow<String> = combine(
        catalog.official,
        customModelRepo.models,
        settings.modelId,
    ) { official, customs, id ->
        mergedModelList(official, customs).firstOrNull { it.id == id }?.name ?: id
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun openModelSheet() { navigator.openSheet(AppNavState.SHEET_MODEL) }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** 正文命中关键词的会话 id。只在用户真的输入了关键词后才查一次(见 [updateQuery])。 */
    private val messageMatches = MutableStateFlow<Set<String>>(emptySet())
    private var searchJob: Job? = null

    /** 官方目录启动拉取:与对话页一致,网关未配置时静默跳过。 */
    init {
        viewModelScope.launch { catalog.refreshIfNeeded() }
    }

    /**
     * 更新搜索词。
     *
     * 标题/预览的匹配是纯内存过滤(列表本身只订会话行),**正文**的匹配改为按需查一次:
     * 早期实现让首页常驻订阅「全部会话 + 全部消息」,只为在输入框里搜正文 ——
     * 于是流式回复每写一次消息,首页都要把全库消息重查一遍。
     */
    fun updateQuery(q: String) {
        _query.value = q
        searchJob?.cancel()
        val kw = q.trim()
        if (kw.isEmpty()) {
            messageMatches.value = emptySet()
            return
        }
        searchJob = viewModelScope.launch {
            // 防抖:逐字输入时不必每个键都扫一遍正文(LIKE 查询用不上索引)
            delay(SEARCH_DEBOUNCE_MS)
            messageMatches.value = sessionRepo.sessionIdsMatching(kw)
        }
    }

    /** 会话流 × 搜索词:标题/预览(内存)+ 正文(按需查询过的命中集)。 */
    val sessions: StateFlow<List<Session>> = combine(
        sessionRepo.sessionSummaries,
        _query,
        messageMatches,
    ) { list, q, hits ->
        val kw = q.trim()
        if (kw.isEmpty()) list
        else list.filter {
            it.title.contains(kw, ignoreCase = true) ||
                it.preview.contains(kw, ignoreCase = true) ||
                it.id in hits
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun openChat(sessionId: String) {
        navigator.openSession(sessionId)
        navigator.navigate(AppNavState.ROUTE_CHAT)
    }

    /** 最近一次删除的会话;非空时列表底部浮出「撤销」条,超时自动收起。 */
    private val _undo = MutableStateFlow<DeletedSession?>(null)
    val undo: StateFlow<DeletedSession?> = _undo.asStateFlow()
    private var undoTimer: Job? = null

    /**
     * 删除会话(连带消息)并留出撤销窗口。
     * 不做二次确认弹窗:删除是「先删后可撤销」的轻动作(左滑即出删除钮),
     * 误删一键即可原样恢复。
     */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            val snapshot = sessionRepo.deleteWithSnapshot(sessionId) ?: return@launch
            _undo.value = snapshot
            undoTimer?.cancel()
            undoTimer = viewModelScope.launch {
                delay(UNDO_WINDOW_MS)
                _undo.value = null
            }
        }
    }

    /** 撤销删除:按原列表位次恢复会话与全部消息。 */
    fun undoDelete() {
        val snapshot = _undo.value ?: return
        _undo.value = null
        undoTimer?.cancel()
        viewModelScope.launch {
            sessionRepo.restore(snapshot)
            navigator.showSnack(context.getString(R.string.chat_snack_session_restored))
        }
    }

    companion object {
        /**
         * 撤销窗口:足够抬手点一下「撤销」(真机上 6s 偏紧,手慢就点不到了),
         * 又不至于让提示条长期压在列表底部。
         */
        const val UNDO_WINDOW_MS = 8000L

        /** 正文搜索的防抖窗口:输入停顿后再查,不跟着每个按键扫描正文。 */
        const val SEARCH_DEBOUNCE_MS = 150L
    }
}
