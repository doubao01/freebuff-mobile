package com.freebuff.feature.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freebuff.core.data.context.ContextBuilder
import com.freebuff.core.data.network.ApiError
import com.freebuff.core.data.network.ChatMessage
import com.freebuff.core.data.network.RetryPolicy
import com.freebuff.core.data.network.toApiError
import com.freebuff.core.data.repository.AgentLoopHost
import com.freebuff.core.data.repository.AgentLoopRunner
import com.freebuff.core.data.repository.ChatRepository
import com.freebuff.core.data.repository.CustomModelRepository
import com.freebuff.core.data.repository.MemoryEntryRepository
import com.freebuff.core.data.repository.MemoryRepository
import com.freebuff.core.data.repository.ModelCatalogRepository
import com.freebuff.core.data.repository.RoundSetup
import com.freebuff.core.data.repository.SessionRepository
import com.freebuff.core.data.repository.SettingsRepository
import com.freebuff.core.data.repository.SubagentRunner
import com.freebuff.core.model.AgentEvent
import com.freebuff.core.model.ChatMsg
import com.freebuff.core.model.ChatTarget
import com.freebuff.core.model.ContextBudget
import com.freebuff.core.model.ContextPolicy
import com.freebuff.core.model.ContextStats
import com.freebuff.core.model.DefaultTools
import com.freebuff.core.model.CustomModel
import com.freebuff.core.model.MemoryCodec
import com.freebuff.core.model.MemoryExtraction
import com.freebuff.core.model.MemoryRecallCodec
import com.freebuff.core.model.MemoryStore
import com.freebuff.core.model.MessageOps
import com.freebuff.core.model.MsgStep
import com.freebuff.core.model.MsgSteps
import com.freebuff.core.model.OfficialModel
import com.freebuff.core.model.Session
import com.freebuff.core.model.Subagent
import com.freebuff.core.model.TodoList
import com.freebuff.core.model.ToolCallReq
import com.freebuff.core.model.ToolCard
import com.freebuff.core.model.ToolOutcome
import com.freebuff.core.model.ToolPermission
import com.freebuff.core.model.ToolPermissions
import com.freebuff.core.model.fmtT
import com.freebuff.core.model.mergedModelList
import com.freebuff.core.model.uid
import com.freebuff.core.model.AgentNotify
import com.freebuff.core.ui.navigation.AppNavigator
import com.freebuff.core.ui.platform.AgentTaskNotifier
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/**
 * 对话页:管理当前会话、输入框、真实 SSE 流式发送与停止。
 * 会话数据经 Room Flow 驱动,流式回复逐步写回占位消息。
 * 流式带空闲看门狗(连接存活但长时间不吐数据时自动停止,见 ChatRepository.idleWatchdog)。
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessionRepo: SessionRepository,
    private val customModelRepo: CustomModelRepository,
    private val catalog: ModelCatalogRepository,
    private val settings: SettingsRepository,
    private val chatRepo: ChatRepository,
    private val tools: com.freebuff.core.data.tools.ToolExecutors,
    private val subagentRunner: SubagentRunner,
    /** agent 循环本体(core:data):只做控制流,副作用经 [AgentLoopHost] 回到本类。 */
    private val agentLoopRunner: AgentLoopRunner,
    private val memoryRepo: MemoryRepository,
    private val memoryEntryRepo: MemoryEntryRepository,
    private val contextBuilder: ContextBuilder,
    private val navigator: AppNavigator,
    private val notifier: AgentTaskNotifier,
    @ApplicationContext private val context: Context,
    @Named("gatewayBaseUrl") private val gatewayBaseUrl: String,
) : ViewModel() {

    init {
        // 启动时后台尝试拉取官方目录:网关未配置则静默跳过,失败保留内置目录
        viewModelScope.launch { catalog.refreshIfNeeded() }
    }

    /** Eagerly 常驻订阅,保证任务向导提交后(对话页尚未组合时) value 已是最新会话。 */
    private val activeSessionId: StateFlow<String?> = navigator.state
        .map { it.activeSessionId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 当前打开的会话 —— **只订阅这一个会话**。
     *
     * 这里曾订阅「全部会话 + 全部消息」再从中挑出当前会话:于是流式回复每写一次消息,
     * 整库的会话与消息都要重查一遍(写一条、读全库),会话一多就线性变慢。
     * 现在只有切换会话才换订阅:本会话的消息写入只重查本会话,别的会话在写完全不影响。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeSession: StateFlow<Session?> = activeSessionId
        .flatMapLatest { id -> if (id.isNullOrBlank()) flowOf(null) else sessionRepo.observeSession(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Eagerly 常驻订阅:向导提交后立刻 sendMessage 时这两个值必须已就绪,
    // WhileSubscribed 会在首页(聊天页未组合)期间停留在初始值,导致自定义模型被误判为官方模型
    val modelId: StateFlow<String> = settings.modelId
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val customModels: StateFlow<List<CustomModel>> = customModelRepo.models
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 模型目录:官方(网关实时)+ 自定义。 */
    val modelList: StateFlow<List<OfficialModel>> = combine(catalog.official, customModels) { official, customs ->
        mergedModelList(official, customs)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        mergedModelList(catalog.official.value, customModels.value),
    )

    /** 官方网关是否已配置(未配置时官方模型列表为空,模型面板明确提示)。 */
    val gatewayConfigured: Boolean get() = catalog.isGatewayConfigured

    /** 是否已成功拉到过网关目录(用于面板区分「未配置」与「尚未拉取」)。 */
    val catalogLoaded: StateFlow<Boolean> = catalog.loaded

    /** 最近一次目录拉取失败原因(网关未配置时为 null)。 */
    val catalogError: StateFlow<ApiError?> = catalog.lastError

    /** 手动刷新官方目录(模型面板下拉/重试入口)。 */
    fun refreshCatalog() {
        viewModelScope.launch {
            when (val r = catalog.refresh()) {
                is com.freebuff.core.data.network.ApiResult.Ok ->
                    navigator.showSnack(context.getString(R.string.chat_snack_catalog_updated, r.data.size))
                is com.freebuff.core.data.network.ApiResult.Err ->
                    navigator.showSnack(r.error.userMessage)
            }
        }
    }

    private val _chatInput = MutableStateFlow("")
    val chatInput: StateFlow<String> = _chatInput.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _contextStats = MutableStateFlow<ContextStats?>(null)

    /** 最近一轮上下文构建统计:会话页据此提示「已压缩早期上下文」(见 docs/context-engineering.md §3)。 */
    val contextStats: StateFlow<ContextStats?> = _contextStats.asStateFlow()

    /**
     * 流式正文的缓冲与落库节流(见 [com.freebuff.core.data.repository.StreamTextThrottle])。
     *
     * 落库频率不再跟着模型吐字走:界面刷新由这里定节奏,写入是聚合后的。
     * 每次操作开始清空(见 launchAgentOp),停止时同步取走(见 stopStreaming)。
     */
    private val streamText = com.freebuff.core.data.repository.StreamTextThrottle()

    /** 上面这段缓冲属于哪个会话(停止时要按它落库,不能落到当前激活会话)。 */
    private var streamTextOwner: String? = null

    /**
     * 整个对话操作的句柄 —— **唯一的取消入口**。
     *
     * 一次发送/重新生成/编辑重发 = 一个操作协程:循环、流式收包、工具执行、上下文摘要全在它内部,
     * 它之外不再有第二个句柄。曾经内层的「每轮流式」会另起 job 并覆盖这个字段,于是运行到
     * 工具执行阶段时,它指的是一个**已经跑完**的 job —— 点停止什么也取消不掉(见 stopStreaming)。
     */
    private var streamJob: Job? = null

    /**
     * 用户按下停止的标记:agent 循环在每个**轮次边界**查它,保证停止之后绝不会再开新的一轮。
     *
     * 为什么不只靠取消协程:取消是协作式的,工具层若有 `catch (e: Exception)` 把
     * CancellationException 当普通失败吞掉(端侧工具与子代理都真实存在这类分支,现已逐处修掉),
     * 取消就可能延迟到下一轮才被观察到。标记与取消两条腿一起走才叫「一定停得下来」。
     */
    private var stopRequested = false

    /**
     * 操作序号:每次对话操作自增,收尾时据此判断「共享状态还是不是自己的」。
     *
     * 为什么需要:停止之后马上编辑重发是常见动作,而旧操作可能还在收尾(卡在一次阻塞的工具调用上)。
     * 没有序号时,旧操作的 finally 会把新操作刚置上的「正在生成」抹掉,界面变回可发送 →
     * 又放行第三个操作并发写同一条消息。只有「当前这一次操作」能清共享状态。
     */
    private var opSeq = 0

    /** 当前流式回复所属会话;停止时按它收尾,避免会话切换后落到错误会话。 */
    private var streamSessionId: String? = null

    /** 被端点拒过工具定义的会话:该会话后续轮次不再附带 tools(降级为纯对话,不再反复碰壁)。 */
    private var toolsRejectedSession: String? = null

    private companion object {
        const val TAG = "ChatViewModel"

        /** 诊断日志开关:排查「后段消息不落库」这类流式写库时序问题时打开,定位后可关。 */
        const val LOG_DB_TRACE = true

        /** 记忆提取提示词:严格 JSON,便于端侧解析入库。 */
        const val EXTRACT_PROMPT = """从本轮用户消息中提取值得长期记住的信息:用户偏好与习惯、关键事实与约定、任务进度。
只记用户明确说出或确认过的内容;绝不要记录助手自己的推测、道歉、工具调用过程,也不要记「无记录/为空/未能找到」这类状态描述。
用户本轮没有提供新信息时,输出 []。
用 JSON 数组输出,元素形如 {"type":"long_term"|"short_term","content":"..."};偏好与事实用 long_term,任务进度用 short_term。
每条 content 写成一句简短陈述,且与用户语言一致(中文对话用中文),最多 5 条;没有值得记的就输出 []。只输出 JSON,不要解释。"""
    }

    /** 工具开关(设置页「工具调用」):开启时请求携带工具定义,模型可触发 function calling。 */
    private val toolsEnabledState: StateFlow<Boolean> = settings.toolsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val toolsEnabled: Boolean
        get() = toolsEnabledState.value

    /** 记忆开关(设置页「上下文记忆」):关闭时不注入记忆块、模型不可 save_memory。 */
    private val memoryEnabledState: StateFlow<Boolean> = settings.memoryEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val memoryEnabled: Boolean
        get() = memoryEnabledState.value

    /**
     * 计划模式(设置页开关):开启时写工具从模型可见的工具集里**物理剔除**。
     *
     * 为什么是「剔除」而不是提示词劝阻:模型看不到写入口,就不可能写出提交 ——
     * 与后端 agent 运行时的 plan 模式同构(见 docs/tool-capability-roadmap.md T3)。
     * 它由对话页顶部的状态条展示,并在那里提供退出入口。
     */
    val planMode: StateFlow<Boolean> = settings.planMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 退出计划模式(对话页顶部一键退出):恢复写工具可见,下一轮请求立刻生效。 */
    fun exitPlanMode() {
        viewModelScope.launch { settings.setPlanMode(false) }
    }

    /** 当前能力对应的工具集:关记忆时不含记忆工具,计划模式下不含写工具。 */
    private fun activeTools(): List<com.freebuff.core.model.AgentTool> =
        com.freebuff.core.model.DefaultTools.forCapabilities(memory = memoryEnabled, planMode = planMode.value)

    /** 深度思考(思维链)模式:off 不思考 / auto 按模型识别 / on 强制开启(设置页三态)。 */
    private val reasoningModeState: StateFlow<String> = settings.reasoningMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.freebuff.core.model.Reasoning.MODE_AUTO)

    private val reasoningMode: String
        get() = reasoningModeState.value

    /**
     * 按当前模型生成思维链请求参数。
     * 只送该模型所属族认得的字段;识别不了的模型在自动模式下不送(严格端点会对未知字段 400)。
     */
    private fun reasoningPlan(target: ChatTarget): com.freebuff.core.model.ReasoningPlan? =
        com.freebuff.core.model.Reasoning.plan(reasoningMode, target.model)

    /** 工具权限覆写(设置页可配置;生效分级 = 覆写 ∪ 默认)。 */
    private val permissionOverridesState: StateFlow<Map<String, ToolPermission>> =
        settings.toolPermissionOverrides.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** 挂起的确认请求:同一时刻至多一个(CONFIRM 级工具串行执行);deferred 即用户决定,await 真挂起。 */
    private class PendingConfirm(
        val req: com.freebuff.core.model.ToolCallReq,
        /** 发起该确认的会话:批准/拒绝时据此更新卡片 —— 不依赖可变的 streamSessionId。 */
        val sessionId: String,
        val deferred: kotlinx.coroutines.CompletableDeferred<Boolean>,
    )

    private val _pendingConfirmation = MutableStateFlow<PendingConfirm?>(null)

    /** 会话级权限记忆:「本次会话记住选择」——纯内存,会话切换/流结束即失效。 */
    private val sessionPermissionMemory = com.freebuff.core.model.SessionPermissionMemory()

    /** 对话页据此弹「是否允许执行该工具」确认框。 */
    val pendingConfirmation: StateFlow<com.freebuff.core.model.ToolCallReq?> =
        _pendingConfirmation.map { it?.req }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 用户批准当前挂起的工具调用:卡片回 running,门闩放行;勾选记住时写入会话记忆。 */
    fun approvePendingTool(remember: Boolean = false) {
        val p = _pendingConfirmation.value ?: return
        viewModelScope.launch { upsertToolCardState(p.sessionId, p.req.callId, "running") }
        if (remember) sessionPermissionMemory.remember(p.req.name, ToolPermission.ALLOW)
        p.deferred.complete(true)
        _pendingConfirmation.value = null
    }

    /** 用户拒绝当前挂起的工具调用:卡片标红,拒绝作为执行结果回传给模型;勾选记住时本会话内后续同工具直接拒绝。 */
    fun denyPendingTool(remember: Boolean = false) {
        val p = _pendingConfirmation.value ?: return
        if (remember) sessionPermissionMemory.remember(p.req.name, ToolPermission.DENY)
        p.deferred.complete(false)
        _pendingConfirmation.value = null
        viewModelScope.launch {
            completeToolCard(p.sessionId, p.req.callId, context.getString(R.string.chat_note_user_denied_tool), isError = true)
        }
    }

    fun updateInput(v: String) { _chatInput.value = v }

    /** 切换会话时丢弃残留草稿:记录上一次输入归属,只有归属变化才清。 */
    private var inputOwnerSession: String? = null

    fun clearInputIfStale() {
        val cur = activeSession.value?.id
        if (cur != inputOwnerSession) {
            _chatInput.value = ""
            inputOwnerSession = cur
            _contextStats.value = null // 统计属于上一个会话,切换后不再展示
        }
    }

    fun modelById(id: String): OfficialModel? = modelList.value.firstOrNull { it.id == id }

    fun setModel(id: String) {
        viewModelScope.launch {
            settings.setModelId(id)
            navigator.showSnack(context.getString(R.string.chat_snack_model_switched, modelById(id)?.name ?: id))
        }
    }

    /** 复制代码到系统剪贴板。 */
    fun copyText(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("freebuff", text))
        navigator.showSnack(context.getString(R.string.chat_snack_copied))
    }

    /** 普通发送:把输入框内容发给当前会话。 */
    fun sendInput() {
        val text = _chatInput.value
        if (text.isBlank() || _streaming.value) return
        sendMessage(text.trim())
    }

    /** 发起一次对话。ctxRepo/ctxModel 由任务向导首条消息携带;sessionIdHint 用于向导刚创建、Flow 尚未传播的会话。 */
    fun sendMessage(text: String, ctxRepo: String = "", ctxModel: String = "", sessionIdHint: String? = null) {
        if (_streaming.value) return
        // 优先用向导传入的会话 id,避免 activeSession Flow 未更新导致首条消息丢失
        val sessionId = sessionIdHint ?: activeSession.value?.id ?: return
        val userMsg = ChatMsg(uid(), "user", text = text, time = fmtT(System.currentTimeMillis()),
            ctxRepo = ctxRepo, ctxModel = ctxModel)
        viewModelScope.launch {
            val session = sessionRepo.get(sessionId) ?: return@launch
            // 会话确认存在后才清输入框,避免向导提交被会话失效吞字
            _chatInput.value = ""
            inputOwnerSession = session.id
            sessionRepo.appendMessages(session.id, listOf(userMsg, newAgentPlaceholder()))
            autoTitle(session.id, text)
            launchAgentOp(session.id, ctxRepo, ctxModel)
        }
    }

    /** 占位 agent 消息:正文与工具卡片随后由事件流写入;起手状态牌让用户立刻看到「已经收到」。 */
    private fun newAgentPlaceholder() = ChatMsg(
        uid(), "agent", text = "", time = MsgSteps.GENERATING,
        steps = listOf(MsgStep(MsgSteps.CONNECT, context.getString(R.string.chat_step_connecting))),
    )

    /**
     * 重新生成最后一条回复:删掉它,用同一段上下文重跑一轮。
     *
     * 与「删掉再手动重发」的区别:用户消息、更早的历史、以及那条消息携带的仓库/模型关联都原样保留
     * (重发的做法会多出一条重复的用户消息)。只允许作用在最后一条回复上,理由见 MessageOps.canRegenerate。
     */
    fun regenerateReply(agentMessageId: String) {
        if (_streaming.value) return
        val sessionId = activeSession.value?.id ?: return
        viewModelScope.launch {
            val s = sessionRepo.get(sessionId) ?: return@launch
            val plan = MessageOps.planRegenerate(s.messages, agentMessageId)
            if (plan == null) {
                navigator.showSnack(context.getString(R.string.chat_snack_regen_last_only))
                return@launch
            }
            // 这次回复对应的问题:仓库上下文要从它继承,否则重跑会丢掉「本次任务关联仓库」
            val ask = s.messages.dropLast(1).lastOrNull { it.role == "user" }
            applyPlan(s.id, plan)
            sessionRepo.appendMessages(s.id, listOf(newAgentPlaceholder()))
            inputOwnerSession = s.id
            launchAgentOp(s.id, ask?.ctxRepo.orEmpty(), ask?.ctxModel.orEmpty())
        }
    }

    /**
     * 编辑用户消息后重发:改写正文、丢弃它之后的全部消息,再跑一轮。
     *
     * 截断是必须的 —— 后面的回复与工具执行都是针对旧问题产生的,留着会让模型看到
     * 「新问题 + 旧答案」并自我矛盾(规则见 MessageOps.planEditAndResend)。
     */
    fun editAndResend(userMessageId: String, newText: String) {
        if (_streaming.value) return
        val sessionId = activeSession.value?.id ?: return
        viewModelScope.launch {
            val s = sessionRepo.get(sessionId) ?: return@launch
            val plan = MessageOps.planEditAndResend(s.messages, userMessageId, newText)
            if (plan == null) {
                navigator.showSnack(context.getString(R.string.chat_snack_resend_invalid))
                return@launch
            }
            val ask = s.messages.firstOrNull { it.id == userMessageId }
            applyPlan(s.id, plan)
            sessionRepo.appendMessages(s.id, listOf(newAgentPlaceholder()))
            inputOwnerSession = s.id
            launchAgentOp(s.id, ask?.ctxRepo.orEmpty(), ask?.ctxModel.orEmpty())
        }
    }

    /**
     * 删除单条消息:精确删这一条,不连带它的回复。
     * 生成中不开放(会被流式写入覆盖回去,白删一次)。
     */
    fun deleteMessage(messageId: String) {
        if (_streaming.value) return
        val sessionId = activeSession.value?.id ?: return
        viewModelScope.launch {
            val s = sessionRepo.get(sessionId) ?: return@launch
            val plan = MessageOps.planDelete(s.messages, messageId) ?: return@launch
            applyPlan(s.id, plan)
            navigator.showSnack(context.getString(R.string.chat_snack_deleted))
        }
    }

    /**
     * 把消息改写计划落到仓库:先改正文,再删消息,最后重算预览。
     *
     * 历史上有了改动,基于旧历史派生的早期上下文摘要必须作废 —— 否则下一轮会把一份
     * 描述着已被删消息的摘要注入上下文(缓存判定只看覆盖条数,它仍会被判为有效)。
     */
    private suspend fun applyPlan(sessionId: String, plan: MessageOps.Plan) {
        plan.rewrite?.let { rw ->
            val cur = sessionRepo.get(sessionId)?.messages?.firstOrNull { it.id == rw.id }
            if (cur != null) sessionRepo.replaceMessage(sessionId, rw.id, cur.copy(text = rw.text))
        }
        sessionRepo.deleteMessages(sessionId, plan.dropIds)
        summaryCache.remove(sessionId)
        sessionRepo.refreshPreview(sessionId)
    }

    /**
     * 跑一轮 agent 生成:占位消息已由调用方落库,这里只负责循环本身。
     *
     * 发送、重新生成、编辑重发三条入口共用它 —— 三份循环实现迟早会走偏,而这里的策略
     * (轮次上限/提醒/熔断)是踩过坑才定下来的(见 docs/agent-architecture.md)。
     *
     * 整个对话操作包进**一个**可取消子协程,句柄存进 streamJob:
     * stopStreaming 取消它时,LLM 摘要压缩/上下文构建/重试退避/工具执行等所有阶段一并中断 ——
     * 只取消单个流式 Job 时,用户停止后操作可能仍在后台继续写库(僵尸协程),
     * 把后续答案写进下一次发送的占位消息(即「轮次停止后 agent 消息缺失」的根因)。
     *
     * 循环内部**不再另起 job**(那会让句柄在工具执行阶段指着一个已完成的 job,停止成为空操作),
     * 并在每个轮次边界查 [stopRequested] —— 即使取消被工具层吞掉,停止也一定生效。
     */
    private suspend fun CoroutineScope.launchAgentOp(sessionId: String, ctxRepo: String, ctxModel: String) {
        val target = resolveTarget()
        // 重新取一次会话:循环体里的 session.id 语义与原实现一致(此处不可能为空,取不到就直接放弃)
        val session = sessionRepo.get(sessionId) ?: return
        val agentMsg = session.messages.lastOrNull { it.role == "agent" }
        val opJob = launch {
            if (LOG_DB_TRACE) Log.i(TAG, "op begin sid=${session.id.takeLast(6)} msg=${agentMsg?.id?.takeLast(6)}")
            // 本次操作的身份:收尾时只有它还是「当前操作」才允许清共享状态
            val myOp = ++opSeq
            // 本次流式操作归属的会话:停止/确认回写都靠它定位(此处必须赋值)
            streamSessionId = session.id
            _streaming.value = true
            // 新操作开始:清掉上一次可能残留的停止标记(上一条消息点过停止,不该影响这一条)
            stopRequested = false
            // 上一条消息可能还留着不到一个节流周期的正文:停止路径已经同步落过库,这里直接丢弃,
            // 否则它会被写进这一条新消息(占位消息此刻已经是「最后一条助手消息」)
            streamText.drain(System.currentTimeMillis())
            streamTextOwner = null
            // 长任务保活与完成通知(见 core:ui.platform.AgentTaskNotifier)。
            // 通知用到的文案在这里就读好:finally 里协程可能已被取消,那里做 suspend 调用会立刻抛
            var keepAlive = false
            var finishOk = false
            var finishSummary = ""
            var finishNotify = true
            val sessionTitle = sessionRepo.get(session.id)?.title.orEmpty()
            try {
                if (!target.isConfigured) {
                    updateAgentText(session.id, noModelMessage())
                } else {
                    // 前台保活:让这条多轮任务在用户切走/锁屏后继续跑完。必须在 App 可见时启动
                    // (Android 12+ 不允许后台启动前台服务),此处正是用户刚点发送的上下文。
                    keepAlive = true
                    notifier.onTaskStarted(sessionTitle, target.name.ifBlank { target.model })
                    // agent 循环交给可单测的 AgentLoopRunner(见 AgentLoopRunnerTest):
                    // 轮次上限、提醒、熔断、重复调用复用、轮次边界的停止判定都在它里面,
                    // 建上下文 / 跑流 / 执行工具 / 写卡片这些副作用由下面的 ChatLoopHost 回调进本类。
                    val host = ChatLoopHost(session, ctxRepo, target)
                    val result = agentLoopRunner.run(session.id, host)
                    val userStopped = result.stoppedByUser // 本轮是被用户停止中断的(收尾要给停止标注)
                    val toolRounds = result.rounds
                    finishAgent(session.id, stoppedByUser = userStopped)
                    // 完成通知的正文取回复正文(首行会再截断一次);用户自己按了停止就不再打扰他
                    finishOk = !userStopped
                    finishNotify = !userStopped
                    finishSummary = sessionRepo.get(session.id)?.messages
                        ?.lastOrNull { it.role == "agent" }?.text.orEmpty()
                    if (LOG_DB_TRACE) Log.i(TAG, "op finish sid=${session.id.takeLast(6)} rounds=$toolRounds")
                }
            } catch (t: Throwable) {
                // 用户主动停止已由 stopStreaming 收尾;只有真实失败才写错误文案
                if (t is CancellationException) {
                    // 用户按下停止 / ViewModel 被销毁:只撤常驻通知,不再发完成通知
                    finishNotify = false
                    if (LOG_DB_TRACE) Log.i(TAG, "op cancelled sid=${session.id.takeLast(6)} (user stop or VM clear)")
                    throw t
                }
                Log.w(TAG, "op failed sid=${session.id.takeLast(6)}: ${t.message}")
                finishOk = false
                finishSummary = t.toApiError().userMessage
                failAgent(session.id, finishSummary)
            } finally {
                // 撤下常驻通知:成功/失败/停止三条路径都必须配对,否则通知栏会挂着一条
                // 永不消失的「正在生成」。这里只发 Intent,不做 suspend 调用。
                if (keepAlive) {
                    notifier.onTaskFinished(session.id, sessionTitle, finishOk, finishSummary, finishNotify)
                }
                // 只有「当前这一次操作」才能清共享状态:停止后立刻再发一条时,旧操作不能在收尾时
                // 把新操作刚置上的「正在生成」抹掉(否则界面变回可发送,又会放行并发写同一条消息)
                if (myOp == opSeq) {
                    if (streamSessionId == session.id) streamSessionId = null
                    _streaming.value = false
                    // 流结束(含被取消)时丢弃未决的确认弹窗与会话记忆,避免残留遮罩/记忆泄到下一轮会话语境
                    _pendingConfirmation.value = null
                    sessionPermissionMemory.clear()
                }
            }
            // 轮次结束后的记忆提取:独立协程执行 —— 它是额外一次 LLM 调用,
            // 不能让「正在生成」状态与输入框被它拖住(提取失败静默)。
            // 同时只允许一次在跑:慢端点下连发多轮时,后台提取会堆积并把前台请求拖慢
            launch {
                if (!extractionLock.tryLock()) return@launch
                try {
                    extractTurnMemories(session.id, target)
                } finally {
                    extractionLock.unlock()
                }
            }
        }
        streamJob = opJob
        opJob.join()
        // 操作结束后释放句柄(身份比对:期间若已开新操作,不动它的句柄)。留着会让人以为
        // 「还有东西在跑」,也让后续对一条已完成 job 的停止把「已停止」标注盖到正常完成的消息上
        if (streamJob === opJob) streamJob = null
    }

    /**
     * [AgentLoopRunner] 的宿主实现:循环只管控制流与循环状态,这里把它的每个动作落到本类的
     * 上下文构建 / 流式收包 / 工具分派 / 卡片与正文写入上。
     *
     * 表单测覆盖看 `AgentLoopRunnerTest` —— 循环的规则都在 runner 里,本类只剩接线,
     * 所以 Android 依赖(仓库、通知、Compose 状态)不再藏在循环骨架里。
     */
    private inner class ChatLoopHost(
        private val session: Session,
        private val ctxRepo: String,
        private val target: ChatTarget,
    ) : AgentLoopHost {

        override fun stopRequested(): Boolean = this@ChatViewModel.stopRequested

        override fun trace(line: String) {
            if (LOG_DB_TRACE) Log.i(TAG, line)
        }

        override suspend fun prepareRound(): RoundSetup {
            // 工具可用性:用户开关 + 本会话是否已被端点拒过工具(拒绝后本轮继续纯对话,不再反复碰壁)。
            // 先算出来再建上下文:系统提示里的「工具使用约定」要与本轮是否真的附带 tools 一致
            val useTools = toolsEnabled && toolsRejectedSession != session.id
            val available = activeTools()
            return RoundSetup(
                messages = buildHistory(session.id, ctxRepo, target, useTools),
                toolsJson = if (useTools) DefaultTools.toJsonArrayString(available) else "",
                // 未知工具名的「可用工具」建议按真实工具集给出
                toolNames = DefaultTools.names(available),
            )
        }

        override suspend fun streamRound(
            history: List<ChatMessage>,
            toolsJson: String,
            textSink: StringBuilder,
        ): List<ToolCallReq> = runStreamRound(session.id, target, history, toolsJson, textSink)

        override suspend fun dispatch(call: ToolCallReq, attempt: Int): ToolOutcome =
            dispatchTool(session.id, call, target, attempt)

        override suspend fun onRoundStart(round: Int) {
            // 状态牌:让用户看到在第几轮、在做什么(实测多轮工具调用时界面会长时间无反馈)
            setProgressStep(session.id, MsgSteps.round(round), MsgSteps.calling(emptyList()))
            notifier.onTaskProgress(AgentNotify.progressDetail(round, emptyList()))
        }

        override suspend fun onToolsDone(round: Int, names: List<String>) {
            setProgressStep(session.id, MsgSteps.round(round), MsgSteps.calling(names))
            notifier.onTaskProgress(AgentNotify.progressDetail(round, names))
        }

        override suspend fun onToolCard(callId: String, outcome: ToolOutcome, reused: Boolean) {
            completeToolCard(session.id, callId, outcome.content, outcome.isError, reused = reused)
        }

        override suspend fun onAppendText(text: String) {
            // 边界写入:先把流式缓冲落库,停因说明才能接在模型已生成的正文之后
            flushStreamText()
            appendAgentText(session.id, text)
        }

        override suspend fun onDropTrailingText(text: String) {
            removeTrailingText(session.id, text)
        }
    }

    /**
     * 停止当前对话操作:取消整个操作协程(含流式收包/工具执行/压缩重试等所有阶段)并把
     * 「正在生成」落定;按发起流的那条会话收尾,而非当前激活会话。
     *
     * 两条腿缺一不可:
     * 1. 先置 [stopRequested] —— 协程取消是协作式的,工具层若把 CancellationException 当普通
     *    失败吞掉,只有这个标记能保证「不再开新的一轮」;
     * 2. 再取消操作协程 —— 立刻中断正在收的流、退避等待、以及**等用户确认**的挂起等待
     *    (旧实现把句柄指向内层已完成的 job,这一步在工具执行阶段是空操作)。
     */
    fun stopStreaming() {
        val sid = streamSessionId
        val myOp = opSeq
        Log.i(TAG, "stop requested sid=${sid?.takeLast(6)} job=${streamJob != null}")
        stopRequested = true
        streamJob?.cancel()
        streamJob = null
        // 缓冲里还没落库的正文要在这里同步取走:操作协程取消后它的 suspend 写入不再可靠
        // (取消点之后调 appendAgentText 会直接抛),而用户要看的正是这一段。
        val pendingText = streamText.drain(System.currentTimeMillis())
        val pendingSession = streamTextOwner
        viewModelScope.launch {
            if (pendingText.isNotEmpty() && pendingSession != null) appendAgentText(pendingSession, pendingText)
            sid?.let { finishAgent(it, stoppedByUser = true) }
            // 期间又开了新操作(用户停止后立刻重发)时不再动它的「正在生成」
            if (myOp == opSeq) _streaming.value = false
        }
    }

    /* ---------------- 内部实现 ---------------- */

    /**
     * 流式增量:先进缓冲,到点才落库(首块立即落,气泡不会晚出现)。
     * 会话换了就先把上一段的尾巴落库,避免跨消息串写。
     */
    private suspend fun appendAgentTextThrottled(sessionId: String, chunk: String) {
        if (streamTextOwner != null && streamTextOwner != sessionId) flushStreamText()
        streamTextOwner = sessionId
        if (streamText.append(chunk, System.currentTimeMillis())) flushStreamText()
    }

    /**
     * 把缓冲里的流式正文落库(结构写入、轮次收尾等边界调用)。
     *
     * 边界必须**先 flush 再写卡片/状态牌/收尾**:那些写入是整行替换,
     * 带着旧正文快照落库会把刚写进去的正文盖回旧值。
     */
    private suspend fun flushStreamText() {
        val sid = streamTextOwner ?: return
        val pending = streamText.drain(System.currentTimeMillis())
        if (pending.isNotEmpty()) appendAgentText(sid, pending)
    }

    /**
     * 事件分派(agent-architecture.md §4):文本追加、工具卡片 upsert、错误归因。
     * 事件按到达顺序处理;工具串行执行保证 callId 成对有序,无需排序。
     * @return 本轮结束时的结构化工具调用(供循环执行);无调用返回空表
     */
    private suspend fun handleAgentEvent(sessionId: String, ev: AgentEvent): List<com.freebuff.core.model.ToolCallReq> =
        when (ev) {
            is AgentEvent.Text -> {
                // 边收边洗:端点会把 chat 模板控制词(<|eos|> 等)当普通文本漏进正文,
                // 写入/显示前就去掉(收尾还会再洗一次,兼顾被拆到两个 chunk 的半个词)
                val clean = com.freebuff.core.model.OutputSanitizer.clean(ev.chunk)
                if (clean.isNotEmpty()) appendAgentTextThrottled(sessionId, clean)
                emptyList()
            }
            is AgentEvent.Reasoning -> {
                // 思考(思维链)增量:单独存一列并折叠展示,不进正文、不进下一轮上下文
                appendAgentReasoning(sessionId, ev.chunk); emptyList()
            }
            is AgentEvent.ToolCall -> {
                upsertToolCard(sessionId, ev.callId, ev.tool, ev.input); emptyList()
            }
            is AgentEvent.ToolResult -> {
                completeToolCard(sessionId, ev.callId, ev.output, ev.isError); emptyList()
            }
            is AgentEvent.Failure -> {
                // 先把缓冲里的正文落库:失败提示要接在已生成的正文之后,顺序才对
                flushStreamText()
                appendAgentText(sessionId, "\n⚠ " + ev.message); emptyList()
            }
            is AgentEvent.SubagentChunk -> {
                upsertToolCard(
                    sessionId, "subagent-" + ev.agent,
                    ToolCard.SUBAGENT_PREFIX + ev.agent,
                    ev.chunk,
                ); emptyList()
            }
            is AgentEvent.Notice -> {
                // 适配层提示(端点不吃工具定义/思考字段):原样告知用户,并记下本会话的降级状态
                when (ev.kind) {
                    AgentEvent.Notice.Kind.TOOLS_DROPPED -> toolsRejectedSession = sessionId
                    AgentEvent.Notice.Kind.REASONING_DROPPED -> Unit
                    AgentEvent.Notice.Kind.INFO -> Unit
                }
                navigator.showSnack(ev.message)
                emptyList()
            }
            is AgentEvent.Calls -> ev.calls
            AgentEvent.EndTurn -> emptyList()
        }

    /** 按呼叫 upsert 工具卡片:同名 callId 更新(OpenAI 参数增量多次到达),否则追加。 */
    private suspend fun upsertToolCard(sessionId: String, callId: String, tool: String, input: String) {
        flushStreamText()
        val last = sessionRepo.lastMessage(sessionId, "agent") ?: return
        val cards = last.tools.toMutableList()
        val idx = cards.indexOfFirst { it.callId == callId }
        if (idx >= 0) cards[idx] = cards[idx].copy(tool = tool, input = input, state = "running")
        else cards += ToolCard(callId = callId, tool = tool, input = input, state = "running")
        sessionRepo.replaceMessage(sessionId, last.id, last.copy(tools = cards))
    }

    /** 工具收尾:写入输出与终态;无对应卡片时补一张(容错乱序)。reused = 结果复用(未真正执行)。 */
    private suspend fun completeToolCard(
        sessionId: String,
        callId: String,
        output: String,
        isError: Boolean,
        reused: Boolean = false,
    ) {
        flushStreamText()
        val last = sessionRepo.lastMessage(sessionId, "agent") ?: return
        val cards = last.tools.toMutableList()
        val idx = cards.indexOfFirst { it.callId == callId }
        val updated = (cards.getOrNull(idx) ?: ToolCard(callId = callId, tool = "tool"))
            .copy(output = output, state = when { reused -> "reused"; isError -> "error"; else -> "done" })
        if (idx >= 0) cards[idx] = updated else cards += updated
        sessionRepo.replaceMessage(sessionId, last.id, last.copy(tools = cards))
    }

    /** 解析当前模型对应的请求目标(自定义模型读其端点,官方模型走网关)。 */
    /**
     * 「没有可用模型」的操作指引:按缺的是「选择」还是「配置」给不同出路,
     * 避免用户按提示添加了自定义模型却发现真正原因是从没选过模型。
     */
    private fun noModelMessage(): String {
        val picked = modelId.value.isNotBlank()
        val hasCustom = customModels.value.isNotEmpty()
        return when {
            !picked && hasCustom ->
                context.getString(R.string.chat_no_model_unpicked)
            hasCustom ->
                context.getString(R.string.chat_no_model_unavailable)
            else ->
                context.getString(R.string.chat_no_model_none)
        }
    }

    private fun resolveTarget(): ChatTarget {
        val id = modelId.value
        val custom = customModels.value.firstOrNull { it.id == id }
        return if (custom != null) {
            customModelRepo.chatTarget(custom)
        } else {
            ChatTarget(endpoint = gatewayBaseUrl, model = id, name = id)
        }
    }

    /**
     * 构建请求上下文:记忆块 + 会话历史,经 ContextBuilder 预算裁剪与压缩。
     * 每轮循环重建一次 —— 工具结果落库后,下一轮自动进入上下文。
     */
    private suspend fun buildHistory(
        sessionId: String,
        ctxRepo: String,
        target: ChatTarget,
        toolsEnabled: Boolean = true,
    ): List<ChatMessage> {
        val s = sessionRepo.get(sessionId)
        val msgs = s?.messages.orEmpty().dropLast(1) // 最后一条是占位 agent 消息,不进上下文
        val budget = ContextBudget(contextWindow = ContextPolicy.parseCtxWindow(target.ctxWindow))
        // 工作记忆:按最新用户输入检索 Top K 历史条目,注入 system prompt(检索失败不影响对话)
        val workingMemory = if (memoryEnabled) {
            val query = msgs.lastOrNull { it.role == "user" }?.text.orEmpty()
            if (query.isBlank()) ""
            // 不用 runCatching:它把 CancellationException 一起吞掉(用户停止后这一轮还会跑完),
            // 这里只吞「检索失败」—— 工作记忆是尽力而为,但取消必须继续往上走
            else try {
                MemoryRecallCodec.formatForPrompt(
                    memoryEntryRepo.search(query, topK = MemoryStore.DEFAULT_TOP_K, bumpHits = false),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ""
            }
        } else ""
        // 摘要缓存:同一会话内被裁段没明显变多就复用上次的摘要,不再重复调 LLM 摘要
        val built = contextBuilder.build(
            memoryBlocks = if (memoryEnabled) memoryRepo.load() else emptyList(),
            session = msgs,
            budget = budget,
            summarize = llmSummarizer(target),
            workingMemory = workingMemory,
            userId = MemoryStore.LOCAL_USER_ID,
            cachedSummary = summaryCache[sessionId],
            toolsEnabled = toolsEnabled,
        )
        built.summary?.let {
            summaryCache[sessionId] = com.freebuff.core.data.context.CachedSummary(built.summaryCoverage, it)
        }
        _contextStats.value = built.stats
        val out = built.messages.toMutableList()
        if (ctxRepo.isNotBlank()) {
            // 任务向导携带的仓库上下文:追加进首条 system 提示,而不是插一条新的 system 消息
            // (OpenAI 兼容网关惯例把 system 当首条,中途再插一条行为不一)
            out[0] = ChatMessage.text(
                "system",
                out[0].content + context.getString(R.string.chat_ctx_repo_prefix) + ctxRepo,
            )
        }
        return out
    }

    /** 会话 → 最近一次生成的早期上下文摘要(长会话里避免每轮都重新摘要)。 */
    private val summaryCache = HashMap<String, com.freebuff.core.data.context.CachedSummary>()

    /** 记忆提取串行锁:同一时刻只跑一次,已在跑则跳过本轮(记忆是尽力而为)。 */
    private val extractionLock = kotlinx.coroutines.sync.Mutex()

    /**
     * LLM 摘要压缩执行器(LibreChat 式):把被裁掉的早期消息交给模型生成摘要。
     * 复用流式管道收集文本;失败由调用方回退提取式摘要。
     */
    private fun llmSummarizer(target: ChatTarget): (suspend (List<com.freebuff.core.data.context.ContextTurn>, String?) -> String)? {
        if (!target.isConfigured) return null
        return { turns, previous ->
            val convo = turns.joinToString("\n") { t ->
                (if (t.role == "agent") "助手" else "用户") + ":" +
                    t.text.take(800) +
                    t.toolOutputs.joinToString("") { (tool, pair) -> "\n[" + tool + " 结果] " + pair.second.take(300) }
            }
            val sb = StringBuilder()
            chatRepo.chatStream(
                endpoint = target.endpoint,
                model = target.model,
                apiKey = target.apiKey,
                headers = target.headers,
                skipTLS = target.skipTLS,
                history = listOf(
                    ChatMessage.text(
                        "system",
                        "把以下对话压缩成简洁摘要,保留:用户的最终目标、已确定的关键决策、重要数据与结论、尚未完成的事项。" +
                            "若已给出【已有摘要】,它是更早部分的压缩,请把它与新内容合并成一份连贯摘要。直接输出摘要正文,不要客套。",
                    ),
                    ChatMessage.text(
                        "user",
                        (if (previous.isNullOrBlank()) "" else "【已有摘要】\n" + previous.take(4000) + "\n\n") +
                            "【需要压缩的对话】\n" + convo.take(24000),
                    ),
                ),
            ).collect { ev ->
                if (ev is com.freebuff.core.model.AgentEvent.Text) sb.append(ev.chunk)
            }
            sb.toString().trim()
        }
    }

    /**
     * 端侧工具分派(全应用唯一入口):先过权限层(allow 静默执行 / confirm 挂起等用户决定 / deny 直接拒绝),
     * 再把调用交给 [com.freebuff.core.data.tools.ToolExecutors.execute] —— 它按注册表的 route 分流:
     * DATA 工具自己执行,HOST 工具回调 [hostFor](记忆 / 子代理需要仓库、会话与 UI 回调)。
     */
    private suspend fun dispatchTool(
        sessionId: String,
        c: ToolCallReq,
        target: ChatTarget,
        /** 该工具在本轮对话里连续第几次失败(错误信封据此升级为「换路」)。 */
        attempt: Int,
    ): ToolOutcome {
        // 当前会话真正可用的工具集:关掉记忆能力时不含记忆工具,未知名字的建议据此生成
        val available = activeTools()
        // 熔断在循环层(AgentLoopRunner):这里只负责执行与信封分级
        // 未知工具名直接回错误结果(带近邻建议):它无法执行,弹确认只会卡住循环、白点一次「允许」(实测浏览工具幻觉)
        if (!com.freebuff.core.model.DefaultTools.isKnown(c.name, available)) {
            return com.freebuff.core.model.ToolOutcome(
                com.freebuff.core.model.ToolErrors.unknownTool(
                    c.name, com.freebuff.core.model.DefaultTools.names(available),
                ),
                isError = true,
            )
        }
        when (ToolPermissions.effective(c.name, permissionOverridesState.value)) {
            ToolPermission.DENY ->
                return com.freebuff.core.model.ToolOutcome(
                    com.freebuff.core.model.ToolErrors.disabledByUser(c.name), isError = true,
                )
            ToolPermission.CONFIRM -> {
                // 会话内已记住本工具的决定(「本次会话记住选择」):免弹窗直接按记忆执行/拒绝
                sessionPermissionMemory.get(c.name)?.let { remembered ->
                    if (remembered == ToolPermission.DENY) {
                        return com.freebuff.core.model.ToolOutcome(
                            com.freebuff.core.model.ToolErrors.deniedByUser(c.name), isError = true,
                        )
                    }
                }
                // 卡片置 waiting;await 真挂起等用户决定,停止对话(取消协程)会直接中断。
                // 会话 id 由调用方传入:曾依赖可变的 streamSessionId,一旦漏赋值,
                // 所有「需确认」工具都会以「会话已关闭」失败、确认弹窗永不出现。
                upsertToolCardState(sessionId, c.callId, "waiting")
                val pending = PendingConfirm(c, sessionId, kotlinx.coroutines.CompletableDeferred())
                _pendingConfirmation.value = pending
                val granted = pending.deferred.await()
                if (!granted) {
                    return com.freebuff.core.model.ToolOutcome(
                        com.freebuff.core.model.ToolErrors.deniedByUser(c.name), isError = true,
                    )
                }
            }
            ToolPermission.ALLOW -> Unit
        }
        return tools.execute(c, available, attempt, host = hostFor(sessionId, target))
    }

    /**
     * 会话宿主侧的 HOST 工具实现(单一分派入口的宿主回调):
     * 记忆与子代理需要仓库、会话与 UI 回调,执行器只认「一次调用 + 第几次失败」,
     * 所以把这层上下文在这里闭包捕获。返回 null = 宿主不认识 → 执行器回未知工具信封。
     */
    private fun hostFor(sessionId: String, target: ChatTarget) = object : com.freebuff.core.model.ToolHost {
        override suspend fun executeHostTool(
            call: com.freebuff.core.model.ToolCallReq,
            attempt: Int,
        ): com.freebuff.core.model.ToolOutcome? = when (call.name) {
            "save_memory" -> executeSaveMemory(call, attempt)
            "memory_recall" -> executeMemoryRecall(call, attempt)
            TodoList.TOOL -> executeWriteTodos(sessionId, call, attempt)
            // 子代理:端侧真执行(独立上下文 + 只读白名单),结论压缩后当普通工具结果回填
            Subagent.TOOL_NAME -> runSubagent(sessionId, call, target, attempt)
            else -> null
        }
    }

    /**
     * spawn_subagent 端侧执行(P0:串行单子代理,见 docs/subagent-design.md):
     * 子代理用独立上下文 + 只读白名单跑自己的循环,结论压缩后作为本轮工具结果回填主循环。
     * 卡片就地改写成子代理形态(`subagent:<type>`,UI 已有渲染分支),运行中把流式输出
     * 节流写进卡片做成实时尾部 —— 用户能看见后台那一路在动。
     */
    private suspend fun runSubagent(
        sessionId: String,
        c: com.freebuff.core.model.ToolCallReq,
        target: ChatTarget,
        attempt: Int,
    ): com.freebuff.core.model.ToolOutcome {
        val example = com.freebuff.core.model.DefaultTools.find(Subagent.TOOL_NAME)?.example.orEmpty()
        // 参数不合法:不执行,回可修正信封(与其它工具同一条错误路径,主模型可自行改对)
        val req = Subagent.parseRequest(c.argsJson)
            ?: return com.freebuff.core.model.ToolOutcome(
                com.freebuff.core.model.ToolErrors.report(
                    com.freebuff.core.model.ToolErrors.Kind.BAD_VALUE, Subagent.TOOL_NAME,
                    Subagent.invalidArgsMessage(c.argsJson),
                    "agent_type 只能取 researcher / code_reader / analyst,task 必须是非空的、自包含的一句话",
                    attempt, example,
                ),
                isError = true,
            )
        markSubagentCard(sessionId, c.callId, req.type, req.task)
        val tail = StringBuilder()
        var flushed = 0
        val result = subagentRunner.run(
            target = target,
            req = req,
            permissions = { name -> ToolPermissions.effective(name, permissionOverridesState.value) },
            reasoning = reasoningPlan(target),
            onProgress = { chunk ->
                tail.append(chunk)
                // 节流:每积累 80 字符写一次库(卡片只是给人看的进度尾巴,不必逐 token 落盘)
                if (tail.length - flushed >= 80) {
                    flushed = tail.length
                    appendSubagentTail(sessionId, c.callId, req.task, tail.toString())
                }
            },
        )
        return when (result) {
            is SubagentRunner.Result.Ok -> com.freebuff.core.model.ToolOutcome(
                // 结果压缩后回填:子代理输出上限 1000 token,压缩闸门保证主上下文不被撑爆
                ContextPolicy.compressToolResult(
                    Subagent.formatResult(req.type, result.rounds, result.toolsUsed, result.text),
                ),
            )
            is SubagentRunner.Result.Fail -> com.freebuff.core.model.ToolOutcome(
                Subagent.formatFailure(req.type, result.reason), isError = true,
            )
        }
    }

    /** 把该调用的卡片改写成子代理形态(✷ 子代理 · 类型),任务描述作为摘要写进 input。 */
    private suspend fun markSubagentCard(sessionId: String, callId: String, type: String, task: String) {
        upsertSubagentCard(sessionId, callId, ToolCard.SUBAGENT_PREFIX + type, task)
    }

    /** 运行中把子代理正文写进卡片 input(UI 取其最后一行做实时尾部)。 */
    private suspend fun appendSubagentTail(sessionId: String, callId: String, task: String, tail: String) {
        upsertSubagentCard(sessionId, callId, null, task + "\n" + tail)
    }

    /**
     * 子代理卡片的 upsert:tool 为 null 时保留现有名字(只有首次改成子代理形态时才传类型)。
     * 卡片不存在时补一张 —— 流式工具事件缺失(只拿到最后的结构化 Calls)也能有卡可看。
     */
    private suspend fun upsertSubagentCard(sessionId: String, callId: String, tool: String?, input: String) {
        flushStreamText()
        val last = sessionRepo.lastMessage(sessionId, "agent") ?: return
        val cards = last.tools.toMutableList()
        val idx = cards.indexOfFirst { it.callId == callId }
        val cur = cards.getOrNull(idx)
        val updated = (cur ?: ToolCard(callId = callId, tool = tool ?: Subagent.TOOL_NAME)).copy(
            tool = tool ?: cur?.tool ?: Subagent.TOOL_NAME,
            input = input,
            state = "running",
        )
        if (idx >= 0) cards[idx] = updated else cards += updated
        sessionRepo.replaceMessage(sessionId, last.id, last.copy(tools = cards))
    }

    /** 把指定工具卡片的状态置为 waiting/running(权限确认流用)。 */
    private suspend fun upsertToolCardState(sessionId: String, callId: String, state: String) {
        flushStreamText()
        val last = sessionRepo.lastMessage(sessionId, "agent") ?: return
        val cards = last.tools.map { if (it.callId == callId) it.copy(state = state) else it }
        sessionRepo.replaceMessage(sessionId, last.id, last.copy(tools = cards))
    }

    /**
     * write_todos 端侧执行:清单写进卡片(随消息持久化),同时把整份清单一字不差地回给模型。
     *
     * 为什么走 HOST 而不是 DATA 路由:它要改的是**当前会话最后一条 assistant 消息里的卡片** ——
     * 那需要 sessionId 与消息写入,正是 [hostFor] 闭包捕获的东西(与记忆/子代理同一条路)。
     *
     * 解析不出来时不静默成功:回带处方的信封,让模型补齐字段后再发一次。空清单会让界面
     * 出现一张永远空着的卡片,比报错更难查。
     */
    private suspend fun executeWriteTodos(
        sessionId: String,
        c: ToolCallReq,
        attempt: Int = 1,
    ): ToolOutcome {
        val example = com.freebuff.core.model.DefaultTools.find(TodoList.TOOL)?.example.orEmpty()
        val items = TodoList.parse(c.argsJson)
            ?: return ToolOutcome(
                com.freebuff.core.model.ToolErrors.report(
                    com.freebuff.core.model.ToolErrors.Kind.BAD_VALUE, TodoList.TOOL,
                    "todos 缺失、为空,或每一项都没有 content",
                    "传完整清单:每项至少要有 content,status 取 pending / in_progress / completed",
                    attempt, example,
                ),
                isError = true,
            )
        setTodoCard(sessionId, c.callId, items)
        return ToolOutcome(TodoList.summary(items))
    }

    /**
     * 把清单写进该调用的卡片(结构化 todos + 摘要行)。
     * 卡片不存在时补一张 —— 流式工具事件缺失(只拿到最后的结构化 Calls)也要有卡可看。
     */
    private suspend fun setTodoCard(sessionId: String, callId: String, items: List<TodoList.Item>) {
        flushStreamText()
        val last = sessionRepo.lastMessage(sessionId, "agent") ?: return
        val cards = last.tools.toMutableList()
        val idx = cards.indexOfFirst { it.callId == callId }
        val cur = cards.getOrNull(idx) ?: ToolCard(callId = callId, tool = TodoList.TOOL)
        val updated = cur.copy(
            tool = TodoList.TOOL,
            input = TodoList.brief(items),
            todos = items,
            state = "running",
        )
        if (idx >= 0) cards[idx] = updated else cards += updated
        sessionRepo.replaceMessage(sessionId, last.id, last.copy(tools = cards))
    }

    /**
     * memory_recall 工具端侧执行:按 user_id/query/top_k/memory_type 检索记忆库。
     * 参数缺失或非法时回传明确错误(模型可修正后重试);非本机 user_id 命中为空时回退本机记忆。
     */
    private suspend fun executeMemoryRecall(
        c: com.freebuff.core.model.ToolCallReq,
        attempt: Int = 1,
    ): com.freebuff.core.model.ToolOutcome {
        val a = MemoryRecallCodec.parse(c.argsJson)
        val recallTool = com.freebuff.core.model.DefaultTools.find("memory_recall")
        val example = recallTool?.example.orEmpty()
        if (a.userId.isBlank()) {
            return com.freebuff.core.model.ToolOutcome(
                com.freebuff.core.model.ToolErrors.report(
                    com.freebuff.core.model.ToolErrors.Kind.MISSING_PARAM, "memory_recall",
                    "缺少 user_id", "user_id 填系统提示里给出的当前用户 id", attempt, example,
                ),
                isError = true,
            )
        }
        if (a.query.isBlank()) {
            return com.freebuff.core.model.ToolOutcome(
                com.freebuff.core.model.ToolErrors.report(
                    com.freebuff.core.model.ToolErrors.Kind.MISSING_PARAM, "memory_recall",
                    "缺少 query", "query 写你要查的事(如「回答长度偏好」)", attempt, example,
                ),
                isError = true,
            )
        }
        return try {
            var hits = memoryEntryRepo.search(a.query, a.topK, a.memoryType, userId = a.userId)
            var note = ""
            if (hits.isEmpty() && a.userId != MemoryStore.LOCAL_USER_ID) {
                // 访客模式单用户:模型传了别的 user_id 时回退本机记忆,避免检索永远为空
                hits = memoryEntryRepo.search(a.query, a.topK, a.memoryType, userId = MemoryStore.LOCAL_USER_ID)
                if (hits.isNotEmpty()) note = "\n(user_id=${a.userId} 无记录,已回退本机记忆)"
            }
            if (hits.isEmpty()) {
                // 词法检索也可能因说法/语言差异(如中文查询 ↔ 英文记忆)零命中:
                // 此时给「最近记忆」兜底并明确标注不是直接匹配,避免模型误以为记忆库为空
                hits = memoryEntryRepo.search("", a.topK, a.memoryType, userId = MemoryStore.LOCAL_USER_ID, bumpHits = false)
                if (hits.isNotEmpty()) note = "\n(无直接匹配,以下为最近记忆,引用时请说明这是推测)"
            }
            com.freebuff.core.model.ToolOutcome(MemoryRecallCodec.formatResult(a.userId, a.query, hits) + note)
        } catch (e: CancellationException) {
            throw e // 用户停止:取消不得被改写成「检索失败」的成功工具结果,让循环继续跑
        } catch (e: Exception) {
            com.freebuff.core.model.ToolOutcome("检索记忆失败:" + (e.message ?: e::class.java.simpleName), isError = true)
        }
    }

    /**
     * 轮次结束后的记忆提取(工作流第 3 步):让模型抽取本轮值得长期保留的信息,
     * 按 long_term/short_term 分类入库,供后续检索注入。失败静默,不影响对话。
     */
    private suspend fun extractTurnMemories(sessionId: String, target: ChatTarget) {
        if (!memoryEnabled || !target.isConfigured) return
        try {
            val s = sessionRepo.get(sessionId) ?: return
            val user = s.messages.lastOrNull { it.role == "user" } ?: return
            val agent = s.messages.lastOrNull { it.role == "agent" } ?: return
            if (user.text.isBlank() || agent.text.isBlank()) return
            if (agent.text.trim().startsWith("⚠")) return // 失败的一轮没有可提信息(错误文案不作记忆源;trim 兼容追加式错误文案)
            val sb = StringBuilder()
            chatRepo.chatStream(
                endpoint = target.endpoint,
                model = target.model,
                apiKey = target.apiKey,
                headers = target.headers,
                skipTLS = target.skipTLS,
                history = listOf(
                    ChatMessage.text("system", EXTRACT_PROMPT),
                    ChatMessage.text("user", "用户:" + user.text.take(2000) + "\n助手:" + agent.text.take(3000)),
                ),
            ).collect { ev -> if (ev is AgentEvent.Text) sb.append(ev.chunk) }
            val items = MemoryExtraction.parse(sb.toString())
            if (items.isNotEmpty()) {
                memoryEntryRepo.addExtracted(items, sessionId = sessionId, userId = MemoryStore.LOCAL_USER_ID)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // 提取失败不影响本轮对话
        }
    }

    /** save_memory 工具端侧执行:写入记忆块,返回给模型的确认文案。 */
    private suspend fun executeSaveMemory(
        c: com.freebuff.core.model.ToolCallReq,
        attempt: Int = 1,
    ): com.freebuff.core.model.ToolOutcome {
        val a = MemoryCodec.parseSaveArgs(c.argsJson)
        return try {
            com.freebuff.core.model.ToolOutcome(memoryRepo.applySave(a.block, a.content, a.replace))
        } catch (e: CancellationException) {
            throw e // 用户停止:同 memory_recall,取消不得被当作工具失败吞掉
        } catch (e: Exception) {
            com.freebuff.core.model.ToolOutcome(
                com.freebuff.core.model.ToolErrors.internalError(
                    "save_memory",
                    "保存记忆失败:" + (e.message ?: e::class.java.simpleName),
                    attempt,
                ),
                isError = true,
            )
        }
    }

    /**
     * 跑一轮流式对话:收集事件、更新 UI,流结束后返回模型请求的结构化工具调用。
     *
     * 直接在**调用方的协程里**跑 —— 不另起 job、不碰 streamJob 句柄:取消外层操作协程
     * 就等于取消这一轮的收包与退避等待。历史上这里用 `viewModelScope.launch` 起的是与操作
     * 协程**平级**的 job,并把句柄覆盖进 streamJob,于是运行到工具阶段时句柄指向一个已完成
     * 的 job,点停止什么也取消不掉(工具照跑、下一轮照开)。
     *
     * 用户停止不再靠返回值表达:取消会直接抛 CancellationException 给操作协程统一收尾;
     * 取消若被工具层吞掉,由调用方的 [stopRequested] 边界检查兜住。
     *
     * @return 本轮的调用列表(可为空)
     */
    private suspend fun runStreamRound(
        sessionId: String,
        target: ChatTarget,
        history: List<ChatMessage>,
        toolsJson: String,
        textSink: StringBuilder? = null,
    ): List<com.freebuff.core.model.ToolCallReq> {
        var calls: List<com.freebuff.core.model.ToolCallReq> = emptyList()
        var retry = 0
        while (true) {
            try {
                chatRepo.chatStream(
                    endpoint = target.endpoint,
                    model = target.model,
                    apiKey = target.apiKey,
                    headers = target.headers,
                    skipTLS = target.skipTLS,
                    history = history,
                    toolsJson = toolsJson,
                    reasoning = reasoningPlan(target),
                ).collect { ev ->
                    if (ev is com.freebuff.core.model.AgentEvent.Text) textSink?.append(ev.chunk)
                    handleAgentEvent(sessionId, ev)?.let { calls = it }
                }
                break // 本轮流完整结束
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // 本轮已有流式输出:半截内容 + 重新生成 = 重复文本,不重试,走收尾提示
                if (textSink?.isNotEmpty() == true) {
                    if (t is ApiError.StreamIdle) {
                        appendAgentText(sessionId, context.getString(R.string.chat_note_idle_stop, t.idleMs / 1000))
                    } else {
                        failAgent(sessionId, t.toApiError().userMessage)
                    }
                    break
                }
                // 瞬时失败(网络抖动/限流/5xx)自动重试;确定性失败(鉴权/参数/TLS)重试必然再败,直接报错
                if (!RetryPolicy.isRetryable(t) || retry >= RetryPolicy.MAX_RETRIES) {
                    failAgent(sessionId, t.toApiError().userMessage)
                    break
                }
                retry++
                recordRetryAttempt(sessionId, retry, t.toApiError().userMessage)
                // 退避等待(指数):期间用户停止则放弃重试(标记是唯一判据,与取消句柄无关)
                if (!RetryPolicy.waitBackoff(retry) { stopRequested }) break
            }
        }
        return calls
    }

    /** 把一次自动重试写进消息:steps 追加「重试 n」步骤,time 位显示尝试进度(流式期间的状态牌,成功后由 finishAgent 落定真实时间)。 */
    private suspend fun recordRetryAttempt(sessionId: String, retry: Int, lastError: String) {
        flushStreamText()
        val last = sessionRepo.lastMessage(sessionId, "agent") ?: return
        sessionRepo.replaceMessage(
            sessionId, last.id,
            last.copy(
                time = context.getString(R.string.chat_step_retry, retry + 1, RetryPolicy.MAX_RETRIES + 1),
                steps = last.steps + MsgStep(
                    MsgSteps.retry(retry),
                    context.getString(R.string.chat_step_retry_reason, lastError),
                ),
            ),
        )
    }

    /** 首条用户消息后自动命名会话(取首行前 12 字);仅当仍是默认标题时生效。 */
    private suspend fun autoTitle(sessionId: String, firstUserText: String) {
        val s = sessionRepo.get(sessionId) ?: return
        if (s.title.isNotBlank() && s.title != context.getString(R.string.chat_title_new)) return
        val line = firstUserText.trim().lines().firstOrNull().orEmpty()
        if (line.isBlank()) return
        sessionRepo.updateTitle(sessionId, line.take(12) + if (line.length > 12) "…" else "")
    }

    /**
     * 直接把一段文本追加进最后一条助手消息 —— 节流器落库的终点,以及非流式的一次性写入。
     * 只读回这一行:[sessionRepo.lastMessage],不再为一次写入加载整个会话的消息并解析其 JSON。
     */
    private suspend fun appendAgentText(sessionId: String, chunk: String) {
        val last = sessionRepo.lastMessage(sessionId, "agent") ?: return
        sessionRepo.replaceMessage(sessionId, last.id, last.copy(text = last.text + chunk))
    }

    /** 追加思考(思维链)内容:单独存储,不混进正文(复制正文/上下文重建都不带它)。 */
    private suspend fun appendAgentReasoning(sessionId: String, chunk: String) {
        flushStreamText()
        val last = sessionRepo.lastMessage(sessionId, "agent") ?: return
        sessionRepo.replaceMessage(sessionId, last.id, last.copy(reasoning = last.reasoning + chunk))
    }

    private suspend fun updateAgentText(sessionId: String, text: String) {
        flushStreamText()
        val last = sessionRepo.lastMessage(sessionId, "agent") ?: return
        sessionRepo.replaceMessage(sessionId, last.id, last.copy(text = text, time = fmtT(System.currentTimeMillis())))
    }

    private suspend fun finishAgent(sessionId: String, stoppedByUser: Boolean = false) {
        flushStreamText()
        val last = sessionRepo.lastMessage(sessionId, "agent") ?: return
        if (LOG_DB_TRACE) {
            Log.i(
                TAG,
                "finish sid=${sessionId.takeLast(6)} stopped=$stoppedByUser textLen=${last.text.length} " +
                    "cards=${last.tools.size} running=${last.tools.count { it.isRunning }}",
            )
        }
        // 流结束时仍在 running 的卡片 = 未等到 tool_result(流提前结束/用户停止),标记未完成
        val cards = if (last.tools.any { it.isRunning }) {
            last.tools.map {
                if (it.isRunning) it.copy(state = "error", output = context.getString(R.string.chat_tool_incomplete)) else it
            }
        } else last.tools
        // 收尾统一洗一次正文:去掉端点漏出的 chat 模板控制词(如 <|eos|>),
        // 并补上两种「空回复」的说明 —— 用户停止、模型什么都没说(实测都会留下空白气泡)
        val cleaned = com.freebuff.core.model.OutputSanitizer.clean(last.text)
        val finalText = when {
            stoppedByUser -> MsgSteps.stoppedText(cleaned)
            cleaned.isBlank() && cards.isEmpty() -> MsgSteps.EMPTY_REPLY_NOTE
            else -> cleaned
        }
        sessionRepo.replaceMessage(
            sessionId, last.id,
            last.copy(
                text = finalText,
                tools = cards,
                // 过程性步骤(连接/第 N 轮)是临时状态牌:收尾必须清掉,
                // 否则对话结束后会一直挂着「连接模型并开始生成…」(实测残留)
                steps = MsgSteps.withoutTransient(last.steps),
                time = fmtT(System.currentTimeMillis()),
            ),
        )
    }

    /** 更新状态牌的过程步骤:始终只留一个(不堆积),历史性步骤(重试)保持原样。 */
    private suspend fun setProgressStep(sessionId: String, name: String, sub: String) {
        flushStreamText()
        val last = sessionRepo.lastMessage(sessionId, "agent") ?: return
        val steps = MsgSteps.withoutTransient(last.steps) + MsgStep(name, sub)
        if (steps != last.steps) {
            sessionRepo.replaceMessage(sessionId, last.id, last.copy(steps = steps))
        }
    }

    /** 本轮正文与上一轮完全相同时,把刚追加进消息的这段重复删掉(模型重放场景)。 */
    private suspend fun removeTrailingText(sessionId: String, text: String) {
        flushStreamText()
        val last = sessionRepo.lastMessage(sessionId, "agent") ?: return
        val t = last.text
        if (text.isNotBlank() && t.endsWith(text)) {
            Log.i(TAG, "dedupe sid=${sessionId.takeLast(6)} drop=${text.length}")
            sessionRepo.replaceMessage(sessionId, last.id, last.copy(text = t.dropLast(text.length)))
        }
    }

    /** 失败收尾:追加错误文案而非覆盖 —— 保留前面轮次已生成的内容与重试轨迹;时间由随后的 finishAgent 落定。 */
    private suspend fun failAgent(sessionId: String, reason: String) {
        appendAgentText(sessionId, context.getString(R.string.chat_error_request_failed, reason))
    }
}
