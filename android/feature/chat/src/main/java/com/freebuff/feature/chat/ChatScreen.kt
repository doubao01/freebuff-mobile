package com.freebuff.feature.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.model.ChatMsg
import com.freebuff.core.model.ConfirmPreview
import com.freebuff.core.model.MessageOps
import com.freebuff.core.model.TodoList
import com.freebuff.core.model.ToolCard
import com.freebuff.core.model.toolDisplayName
import com.freebuff.core.model.toolGlyph
import com.freebuff.core.ui.MiniMarkdown
import com.freebuff.core.ui.ModelChip
import com.freebuff.core.ui.R14
import com.freebuff.core.ui.RFull
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.LocalAppNavigator
import com.freebuff.core.ui.theme.LocalTokens
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription

@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    val navigator = LocalAppNavigator.current
    val session by viewModel.activeSession.collectAsState()
    val streaming by viewModel.streaming.collectAsState()
    val modelId by viewModel.modelId.collectAsState()
    val modelList by viewModel.modelList.collectAsState()
    val listState = rememberLazyListState()
    // 自动跟随滚动。原来的实现只在「消息条数变化」时 animateScrollToItem(末条) ——
    // 两个问题:(1) 流式输出让末条越来越高时不会跟随,用户得手动往下拽;
    // (2) 跳到条目顶部,末条一旦比屏幕高,正在写的内容全在屏幕下方看不到。
    // 现在的规则:只有在用户本来就贴着底部时才跟随(上滑看历史不被打断),
    // 且滚到列表末尾而非末条顶部。
    val atBottom by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastIndex = info.totalItemsCount - 1
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || (last.index >= lastIndex && last.offset + last.size <= info.viewportEndOffset + 24)
        }
    }
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            // 用户主动滚动且没停在底部 = 想回头看历史,暂停跟随
            if (scrolling && !atBottom) follow = false
        }
    }
    LaunchedEffect(atBottom) { if (atBottom) follow = true }

    val msgs = session?.messages.orEmpty()
    val lastMessageSignature = msgs.lastOrNull()?.let {
        it.text.length * 31 + it.tools.size * 7 + it.steps.size
    } ?: 0
    // 新消息:平滑滚到底
    LaunchedEffect(msgs.size) {
        if (follow && msgs.isNotEmpty()) listState.animateScrollToItem(msgs.lastIndex, Int.MAX_VALUE)
    }
    // 流式增长:直接滚到底(逐块做动画会卡在后面,看不到正在写的字)
    LaunchedEffect(lastMessageSignature) {
        if (follow && msgs.isNotEmpty()) listState.scrollToItem(msgs.lastIndex, Int.MAX_VALUE)
    }
    LaunchedEffect(streaming) {
        // 新的一轮生成开始:重新贴回底部(用户刚点了发送)
        if (streaming && msgs.isNotEmpty()) listState.scrollToItem(msgs.lastIndex, Int.MAX_VALUE)
    }
    val modelName = modelList.firstOrNull { it.id == modelId }?.name.orEmpty()
    val pendingConfirm by viewModel.pendingConfirmation.collectAsState()
    // 计划模式:写工具已对模型隐藏 —— 状态与退出入口都放在对话页,用户看到计划能当场切回来
    val planMode by viewModel.planMode.collectAsState()
    // 待确认删除的消息:单条删除不可撤销,删前明确问一次(与工具确认同一套遮罩样式)
    var pendingDelete by remember { mutableStateOf<ChatMsg?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
        ChatHeader(
            title = session?.title ?: stringResource(R.string.chat_title_new),
            modelName = modelName,
            onBack = { navigator.navigate(AppNavState.ROUTE_HOME) },
            onModel = { navigator.openSheet(AppNavState.SHEET_MODEL) },
        )
        if (planMode) PlanModeBanner(onExit = { viewModel.exitPlanMode() })
        if (msgs.isEmpty()) {
            ChatEmpty(
                canPick = session != null,
                onPick = { viewModel.sendMessage(it) },
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().weight(1f),
                contentPadding = PaddingValues(16.dp)) {
                itemsIndexed(msgs, key = { _, m -> m.id }) { i, m ->
                    MessageItem(
                        m = m,
                        isStreaming = streaming && i == msgs.lastIndex,
                        // 编辑重发会丢弃这条之后的消息:提前把条数告诉用户,别让他事后才发现
                        dropCount = msgs.lastIndex - i,
                        canRegenerate = !streaming && MessageOps.canRegenerate(msgs, m.id),
                        actionsEnabled = !streaming,
                        onCopy = viewModel::copyText,
                        onRegenerate = { viewModel.regenerateReply(m.id) },
                        onEditResend = { text -> viewModel.editAndResend(m.id, text) },
                        onDelete = { pendingDelete = m },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        // 上下文压缩提示:早期消息已被摘要替代,模型看到的仍是连续上下文
        val stats by viewModel.contextStats.collectAsState()
        stats?.takeIf { it.didCompact }?.let { st ->
            ContextCompactedHint(compacted = st.compactedTurns, summaryTokens = st.summaryTokens)
        }
        Composer(
            input = viewModel.chatInput.collectAsState().value,
            streaming = streaming,
            onInput = { viewModel.updateInput(it) },
            onSend = { viewModel.sendInput() },
            onStop = { viewModel.stopStreaming() },
            onTool = { tok -> viewModel.updateInput(viewModel.chatInput.value + tok + " ") },
        )
        // 切换会话时清空输入框,避免把 A 会话草稿发进 B 会话
        LaunchedEffect(session?.id) { viewModel.clearInputIfStale() }
        }
        // CONFIRM 级工具的确认弹窗(权限层,见 docs/backend-integration.md「工具权限」)
        pendingConfirm?.let { req ->
            ToolConfirmOverlay(
                tool = req.name,
                input = req.argsJson,
                onAllow = { remember -> viewModel.approvePendingTool(remember) },
                onDeny = { remember -> viewModel.denyPendingTool(remember) },
            )
        }
        pendingDelete?.let { msg ->
            DeleteMessageConfirm(
                msg = msg,
                onConfirm = {
                    viewModel.deleteMessage(msg.id)
                    pendingDelete = null
                },
                onCancel = { pendingDelete = null },
            )
        }
    }
}

/**
 * 计划模式状态条(对话页顶部)。
 *
 * 为什么放在对话页而不是只留在设置里:计划模式改变的是「模型此刻能做什么」,
 * 用户在对话里最需要知道这件事,也最需要能在看完计划后当场退出。
 */
@Composable
private fun PlanModeBanner(onExit: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().background(t.surface)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("◇", color = t.accent, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.chat_plan_mode_title),
                color = t.text, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.chat_plan_mode_hint),
                color = t.text3, fontSize = 10.5.sp, lineHeight = 14.sp,
            )
        }
        // 退出入口:有可见文字,不需要额外 contentDescription(图标按钮才需要)
        Text(
            stringResource(R.string.chat_plan_mode_exit),
            color = t.accent, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(R14).clickable { onExit() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * 单条删除的确认框。
 *
 * 为什么不留「撤销」:会话列表的删除有撤销条(UI 里有位置),而单条删除没有落地的地方;
 * 与其做一个看不见的撤销,不如删前问一次。文案里点明「只删这一条、后面的回复不受影响」——
 * 用户点「删除」时真正想知道的就这件事。
 */
@Composable
private fun DeleteMessageConfirm(msg: ChatMsg, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val t = LocalTokens.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(enabled = false) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 28.dp).clip(R14).background(t.surface).padding(20.dp),
        ) {
            Text(stringResource(R.string.chat_delete_msg_title), color = t.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                msg.text.trim().lines().firstOrNull().orEmpty().take(60)
                    .ifBlank { "(" + (if (msg.role == "user") stringResource(R.string.chat_label_user_message) else stringResource(R.string.chat_label_reply)) + ")" },
                color = t.text2, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (msg.role == "user") stringResource(R.string.chat_delete_msg_user_desc)
                else stringResource(R.string.chat_delete_msg_agent_desc),
                color = t.text3, fontSize = 11.sp, lineHeight = 15.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row {
                Text(
                    stringResource(R.string.action_cancel), color = t.text2, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clip(RFull).background(t.surface2)
                        .clickable { onCancel() }.padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.action_delete), color = t.danger, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clip(RFull).background(t.danger.copy(alpha = 0.14f))
                        .clickable { onConfirm() }.padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** 工具执行确认弹窗:CONFIRM 级工具执行前必须经用户批准;可勾选「本次会话记住选择」免后续弹窗。 */
@Composable
private fun ToolConfirmOverlay(
    tool: String,
    input: String,
    onAllow: (remember: Boolean) -> Unit,
    onDeny: (remember: Boolean) -> Unit,
) {
    val t = LocalTokens.current
    var rememberChoice by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(enabled = false) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 28.dp).clip(R14).background(t.surface).padding(20.dp),
        ) {
            Text(stringResource(R.string.chat_tool_confirm_title), color = t.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(toolGlyph(tool) + " ", color = t.accent, fontSize = 13.sp)
                Text(toolDisplayName(tool), color = t.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            // 写工具的「批什么」摘要:仓库 / 分支 → base / 文件清单 / 提交信息。
            // 参数 JSON 里其实都有,但要用户自己从一段 JSON 里读出来 —— 写操作值得先说清目标。
            ConfirmPreview.of(tool, input)?.let { p ->
                Spacer(Modifier.height(8.dp))
                Text(
                    buildString {
                        append(p.repo)
                        if (p.branch.isNotBlank()) append(" @ ").append(p.branch)
                        if (p.base.isNotBlank()) append(" → ").append(p.base)
                    },
                    color = t.text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
                if (p.files.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.chat_confirm_files) + " · " + p.files.joinToString(", "),
                        color = t.text2, fontSize = 11.sp, lineHeight = 15.sp,
                    )
                }
                if (p.message.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(p.message.take(100), color = t.text3, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
            if (input.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(input.take(300), color = t.text3, fontSize = 11.sp, lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.chat_tool_confirm_note), color = t.text3, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            // 「本次会话记住选择」:仅记忆到本次会话结束,不改设置页的持久化分级
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { rememberChoice = !rememberChoice }) {
                Box(
                    Modifier.size(15.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (rememberChoice) t.accent else t.surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    if (rememberChoice) Text("✓", color = t.accentInk, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.chat_tool_confirm_remember), color = t.text2, fontSize = 11.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row {
                Text(
                    stringResource(R.string.chat_tool_confirm_deny), color = t.text2, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clip(RFull).background(t.surface2)
                        .clickable { onDeny(rememberChoice) }.padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.chat_tool_confirm_allow), color = t.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).clip(RFull).background(t.accent.copy(alpha = 0.14f))
                        .clickable { onAllow(rememberChoice) }.padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 上下文压缩提示条:告诉用户「早期消息已被摘要替代」,避免以为模型失忆。
 * 统计来自最近一轮 ContextBuilder 构建(docs/context-engineering.md §2/§3)。
 */
@Composable
private fun ContextCompactedHint(compacted: Int, summaryTokens: Int) {
    val t = LocalTokens.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⛶ ", color = t.accent, fontSize = 11.sp)
        Text(
            stringResource(R.string.chat_context_compacted, compacted, summaryTokens),
            color = t.text3,
            fontSize = 11.sp,
        )
    }
}

/** 空会话:原型中的 Hero + 示例任务卡片,点卡片直接发起。 */
@Composable
private fun ChatEmpty(canPick: Boolean, onPick: (String) -> Unit) {
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(46.dp))
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(t.accentSoft),
            contentAlignment = Alignment.Center,
        ) { Text("›_", color = t.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.chat_empty_title), color = t.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (canPick) stringResource(R.string.chat_empty_hint_can) else stringResource(R.string.chat_empty_hint_no_session),
            color = t.text3, fontSize = 12.5.sp,
        )
        if (!canPick) return@Column
        Spacer(Modifier.height(22.dp))
        Text(stringResource(R.string.chat_empty_examples), color = t.text3, fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        CHAT_SUGGESTS.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                pair.forEachIndexed { i, (icon, textRes) ->
                    val text = stringResource(textRes)
                    Column(
                        Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(16.dp))
                            .background(t.surface).clickable { onPick(text) }.padding(13.dp),
                    ) {
                        Box(
                            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(t.accentSoft),
                            contentAlignment = Alignment.Center,
                        ) { Text(icon, color = t.accent, fontSize = 14.sp) }
                        Spacer(Modifier.height(10.dp))
                        Text(text, color = t.text2, fontSize = 12.5.sp, lineHeight = 17.sp, maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    if (i == 0 && pair.size > 1) Spacer(Modifier.width(10.dp))
                }
            }
        }
    }
}

/** 对话页顶栏:返回 + 标题 + 模型切换条(点开模型表,模型选择属于任务内)。 */
@Composable
private fun ChatHeader(title: String, modelName: String, onBack: () -> Unit, onModel: () -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().background(t.elev).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // "‹" 是字形,读屏读不出「返回」:clearAndSetSemantics 换成真实描述
        val backCd = stringResource(R.string.chat_cd_back)
        Text("‹", color = t.text2, fontSize = 22.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                .clickable(onClickLabel = backCd, role = Role.Button) { onBack() }
                .clearAndSetSemantics { contentDescription = backCd }
                .padding(horizontal = 8.dp, vertical = 2.dp))
        Text(title, color = t.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
        Spacer(Modifier.width(6.dp))
        ModelChip(modelName = modelName, onClick = onModel)
    }
}

@Composable
private fun MessageItem(
    m: ChatMsg,
    isStreaming: Boolean,
    dropCount: Int,
    canRegenerate: Boolean,
    actionsEnabled: Boolean,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onEditResend: (String) -> Unit,
    onDelete: () -> Unit,
) {
    if (m.role == "user") {
        UserBubble(
            m = m,
            dropCount = dropCount,
            actionsEnabled = actionsEnabled,
            onCopy = onCopy,
            onEditResend = onEditResend,
            onDelete = onDelete,
        )
    } else {
        AgentBody(
            m = m,
            isStreaming = isStreaming,
            canRegenerate = canRegenerate,
            actionsEnabled = actionsEnabled,
            onCopy = onCopy,
            onRegenerate = onRegenerate,
            onDelete = onDelete,
        )
    }
}

/**
 * 用户消息气泡。点「编辑」就地换成输入框:改写后重发会丢弃这条之后的消息 ——
 * 必须先把丢多少条告诉用户,这属于不可逆操作(见 MessageOps.planEditAndResend)。
 */
@Composable
private fun UserBubble(
    m: ChatMsg,
    dropCount: Int,
    actionsEnabled: Boolean,
    onCopy: (String) -> Unit,
    onEditResend: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val t = LocalTokens.current
    var editing by remember(m.id) { mutableStateOf(false) }
    var draft by remember(m.id) { mutableStateOf(m.text) }
    Column(horizontalAlignment = Alignment.End) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(t.userBubble).padding(horizontal = 14.dp, vertical = 10.dp)) {
            m.ctxRepo.takeIf { it.isNotBlank() }?.let { CtxChip(it, t.accent) }
            m.ctxModel.takeIf { it.isNotBlank() }?.let { CtxChip(it, t.ok) }
            if (editing) {
                androidx.compose.foundation.text.BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = t.text, fontSize = 14.sp, lineHeight = 21.sp),
                )
            } else {
                Text(m.text, color = t.text, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
        // 编辑态:说明将丢弃多少条 + 取消/重发
        if (editing) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (dropCount > 0) stringResource(R.string.chat_edit_drop_warning, dropCount)
                else stringResource(R.string.chat_edit_regenerate_hint),
                color = t.text3, fontSize = 10.5.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                MsgAction(stringResource(R.string.action_cancel), t.text2) { draft = m.text; editing = false }
                // 生成中不可重发:ViewModel 会直接忽略,按钮必须同步变灰,否则点了没反应像坏了
                MsgAction(stringResource(R.string.chat_edit_resend), t.accent, enabled = actionsEnabled && draft.isNotBlank()) {
                    onEditResend(draft)
                    editing = false
                }
            }
        } else if (actionsEnabled) {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MsgAction(stringResource(R.string.chat_action_copy), t.text3) { onCopy(m.text) }
                MsgAction(stringResource(R.string.chat_action_edit), t.text3) { draft = m.text; editing = true }
                MsgAction(stringResource(R.string.chat_action_delete_msg), t.text3) { onDelete() }
            }
        }
    }
}

/** 消息级操作按钮:统一样式(小字号 + 点击区),深浅色主题都用 text3/text2 保证可读。 */
@Composable
private fun MsgAction(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    val t = LocalTokens.current
    Text(
        label,
        color = if (enabled) color else t.text3.copy(alpha = 0.5f),
        fontSize = 11.5.sp,
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun AgentCodeBlock(lang: String, code: String, onCopy: () -> Unit) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.codeBg)) {
        Row(Modifier.fillMaxWidth().background(t.codeHeader).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(lang, color = t.text3, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.chat_code_copy), color = t.accent, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onCopy() }.padding(horizontal = 6.dp))
        }
        Text(code, color = t.codeText, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp,
            modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun AgentBody(
    m: ChatMsg,
    isStreaming: Boolean,
    canRegenerate: Boolean,
    actionsEnabled: Boolean,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().clip(R14).background(t.surface).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Freebuff", color = t.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            m.time.takeIf { it.isNotBlank() }?.let { Text(it, color = t.text3, fontSize = 10.5.sp) }
        }
        if (m.steps.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            m.steps.forEach { st ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("✓", color = t.ok, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(" " + st.name + " · ", color = t.text2, fontSize = 11.5.sp)
                    Text(st.sub, color = t.text3, fontSize = 11.5.sp)
                }
            }
        }
        // 工具卡片时间线(agent-architecture.md §4:按到达顺序渲染,无需排序)
        if (m.tools.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ToolCards(m.tools)
        }
        // 思考过程(思维链):流式期间展开、生成完成后自动收起 —— 与正文分开展示,复制正文不带它
        if (m.reasoning.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            ReasoningBlock(m.reasoning, live = isStreaming)
        }
        if (m.text.isNotBlank()) {
            // 真实流式回复写入 text;历史演示消息走 md/code/md2 结构化渲染
            Spacer(Modifier.height(8.dp))
            MiniMarkdown(m.text)
        }
        m.md.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            MiniMarkdown(it)
        }
        m.code.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            AgentCodeBlock(m.codeLang, it) { onCopy(it) }
        }
        m.md2.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            MiniMarkdown(it)
        }
        if (isStreaming) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.chat_generating), color = t.accent, fontSize = 11.sp)
        } else {
            // 原型 .msg-actions:一条消息一个复制动作,复制完整正文(含代码块)
            Spacer(Modifier.height(6.dp))
            val payload = buildString {
                append(m.text)
                if (m.md.isNotBlank()) append(if (isEmpty()) "" else "\n").append(m.md)
                if (m.code.isNotBlank()) {
                    append(if (isEmpty()) "" else "\n").append("```").append(m.codeLang).append("\n")
                        .append(m.code).append("\n```")
                }
                if (m.md2.isNotBlank()) append(if (isEmpty()) "" else "\n").append(m.md2)
            }
            // 消息级操作:复制正文 / 重新生成这一条 / 删除。
            // 重新生成只对最后一条开放(中间的重跑会与后续追问对不上,见 MessageOps.canRegenerate)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MsgAction(stringResource(R.string.chat_action_copy), t.text3) { onCopy(payload) }
                if (actionsEnabled) {
                    if (canRegenerate) MsgAction(stringResource(R.string.chat_action_regenerate), t.accent) { onRegenerate() }
                    MsgAction(stringResource(R.string.chat_action_delete_msg), t.text3) { onDelete() }
                }
            }
        }
    }
}

/**
 * 思考过程块:模型输出的思维链单独折叠展示。
 * 流式期间展开(能看着它想),生成结束后自动收起,想细看再点开。
 */
@Composable
private fun ReasoningBlock(text: String, live: Boolean) {
    val t = LocalTokens.current
    // 以 live 为 key:流结束(或换消息)时重建为收起态
    var expanded by remember(live) { mutableStateOf(live) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.surface2)
            .clickable(
                onClickLabel = stringResource(if (expanded) R.string.chat_cd_collapse else R.string.chat_cd_expand),
                role = Role.Button,
            ) { expanded = !expanded }.padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✦", color = t.text3, fontSize = 11.sp)
            Text(
                " " + if (live) stringResource(R.string.chat_reasoning_live)
                else stringResource(R.string.chat_reasoning_done),
                color = t.text3, fontSize = 11.5.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(if (expanded) "⌃" else "⌄", color = t.text3, fontSize = 12.sp)
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(text, color = t.text2, fontSize = 11.5.sp, lineHeight = 17.sp)
        }
    }
}

/** 工具卡片列表:垂直时间线,左侧连线。 */
@Composable
private fun ToolCards(cards: List<ToolCard>) {
    Column {
        cards.forEachIndexed { i, card ->
            ToolCardItem(card, last = i == cards.lastIndex)
            if (i != cards.lastIndex) Spacer(Modifier.height(6.dp))
        }
    }
}

/** 单张工具卡片:状态点 + 展示名 + 输入摘要;点击展开输入/输出详情;运行中带呼吸动画。 */
@Composable
private fun ToolCardItem(card: ToolCard, last: Boolean) {
    val t = LocalTokens.current
    val expanded = remember(card.callId) { mutableStateOf(false) }
    val todos = card.todos
    val stateColor = when {
        card.isWaiting -> t.accent
        card.isRunning -> t.accent
        card.isError -> t.danger
        card.isReused -> t.text3
        else -> t.ok
    }
    val subagent = card.subagentName
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.surface2)
            .clickable(
                onClickLabel = stringResource(
                    if (expanded.value) R.string.chat_cd_collapse else R.string.chat_cd_expand,
                ),
                role = Role.Button,
            ) { expanded.value = !expanded.value }.padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (card.isRunning || card.isWaiting) {
                val alpha = rememberInfiniteAlpha()
                Box(
                    Modifier.size(7.dp).clip(RFull).background(stateColor.copy(alpha = 0.35f + 0.65f * alpha)),
                )
            } else {
                val glyph = when {
                    card.isError -> "⚠"
                    card.isReused -> "↺"
                    else -> "✓"
                }
                Text(glyph, color = stateColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(7.dp))
            if (subagent != null) {
                Text("✷ ", color = t.accent, fontSize = 11.5.sp)
                Text(stringResource(R.string.chat_tool_subagent_label), color = t.text2, fontSize = 11.5.sp)
                Text(subagent, color = t.text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Text(toolGlyph(card.tool) + " ", color = t.accent, fontSize = 11.sp)
                Text(toolDisplayName(card.tool), color = t.text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            if (todos.isNotEmpty()) {
                Text(
                    TodoList.progress(todos), color = t.text3, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                when {
                    card.isWaiting -> stringResource(R.string.chat_tool_state_waiting)
                    card.isRunning -> stringResource(R.string.chat_tool_state_running)
                    card.isError -> stringResource(R.string.chat_tool_state_error)
                    card.isReused -> stringResource(R.string.chat_tool_state_reused)
                    else -> stringResource(R.string.chat_tool_state_done)
                },
                color = stateColor, fontSize = 10.sp, fontWeight = FontWeight.Medium,
            )
            Text(if (expanded.value) " ⌃" else " ⌄", color = t.text3, fontSize = 10.sp)
        }
        // 清单卡片:逐项渲染状态(不是一段参数摘要);收起时只显示前几项
        if (todos.isNotEmpty()) {
            TodoChecklist(todos, expanded.value)
        } else {
            // 摘要行:输入摘要(路径/命令/搜索词);子代理显示实时输出尾部
            val summary = if (subagent != null && card.isRunning) {
                card.input.lines().lastOrNull { it.isNotBlank() }?.take(80).orEmpty()
                    .ifBlank { stringResource(R.string.chat_tool_starting) }
            } else card.input.take(80)
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(summary, color = t.text3, fontSize = 10.5.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        // 展开区:完整输入/输出
        if (expanded.value) {
            Spacer(Modifier.height(8.dp))
            DetailLine(stringResource(R.string.chat_tool_detail_input), card.input.ifBlank { stringResource(R.string.chat_tool_detail_no_args) })
            if (card.output.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                DetailLine(stringResource(R.string.chat_tool_detail_output), card.output)
            }
        }
    }
}

/** 清单卡片正文:逐项状态 + 内容;收起时只显示前 [TOOL_COLLAPSED_TODOS] 项,展开看全。 */
@Composable
private fun TodoChecklist(todos: List<TodoList.Item>, expanded: Boolean) {
    val t = LocalTokens.current
    val shown = if (expanded) todos else todos.take(TOOL_COLLAPSED_TODOS)
    Spacer(Modifier.height(6.dp))
    shown.forEachIndexed { i, item ->
        if (i > 0) Spacer(Modifier.height(4.dp))
        val (glyph, color, label) = when (item.status) {
            TodoList.COMPLETED -> Triple("☑", t.ok, stringResource(R.string.chat_todo_completed))
            TodoList.IN_PROGRESS -> Triple("◐", t.accent, stringResource(R.string.chat_todo_in_progress))
            else -> Triple("☐", t.text3, stringResource(R.string.chat_todo_pending))
        }
        Row(verticalAlignment = Alignment.Top) {
            Text(glyph, color = color, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                item.content,
                color = if (item.isDone) t.text3 else t.text2,
                fontSize = 11.sp, lineHeight = 16.sp,
                textDecoration = if (item.isDone) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(label, color = color, fontSize = 9.5.sp)
        }
    }
    if (!expanded && todos.size > shown.size) {
        Spacer(Modifier.height(4.dp))
        Text("+" + (todos.size - shown.size), color = t.text3, fontSize = 10.sp)
    }
}

/** 收起时显示的清单条数:再长就把一张卡片撑成一屏。 */
private const val TOOL_COLLAPSED_TODOS = 5

@Composable
private fun DetailLine(label: String, value: String) {
    val t = LocalTokens.current
    Row {
        Text(label, color = t.text3, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text(value, color = t.text2, fontSize = 10.5.sp, lineHeight = 15.sp)
    }
}

/** 0..1 往返的呼吸透明度(卡片运行态指示)。 */
@Composable
private fun rememberInfiniteAlpha(): Float {
    return rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    ).value
}

@Composable
private fun CtxChip(label: String, color: Color) {
    Text("⚑ " + label, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RFull).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun Composer(
    input: String,
    streaming: Boolean,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onTool: (String) -> Unit,
) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().background(t.elev).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.text.BasicTextField(
            value = input,
            onValueChange = onInput,
            modifier = Modifier.weight(1f).clip(RFull).background(t.surface2).padding(horizontal = 14.dp, vertical = 10.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = t.text, fontSize = 13.5.sp),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (input.isEmpty()) Text(stringResource(R.string.chat_composer_hint), color = t.text3, fontSize = 13.5.sp)
                    inner()
                }
            },
        )
        // 原型 .composer-tools:@ 与「引用文件」插入 token
        if (!streaming) {
            // 两个都是纯字形按钮("@"/"▤"):不给描述,读屏只说得到「at」和一堆乱码
            val mentionCd = stringResource(R.string.chat_cd_composer_mention)
            val fileCd = stringResource(R.string.chat_cd_composer_file)
            Text("@", color = t.text2, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .clickable(onClickLabel = mentionCd, role = Role.Button) { onTool("@") }
                    .clearAndSetSemantics { contentDescription = mentionCd }
                    .padding(horizontal = 8.dp, vertical = 6.dp))
            // 📎 是彩色 emoji,浅色主题下实测 2.3:1;改用能跟着主题色的文档字形
            Text("▤", color = t.text2, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .clickable(onClickLabel = fileCd, role = Role.Button) { onTool("@文件") }
                    .clearAndSetSemantics { contentDescription = fileCd }
                    .padding(horizontal = 6.dp, vertical = 6.dp))
        }
        Spacer(Modifier.width(4.dp))
        if (streaming) {
            Text(stringResource(R.string.chat_composer_stop), color = t.danger, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RFull).clickable { onStop() }.padding(horizontal = 12.dp, vertical = 9.dp))
        } else {
            val canSend = input.isNotBlank()
            // 禁用态压在同色系浅底上:text3 在深色主题只有 4.4:1,统一用 text2 保证 ≥5:1
            Text(stringResource(R.string.chat_composer_send), color = if (canSend) t.accentInk else t.text2,
                fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RFull)
                    .background(if (canSend) t.accent else t.surface3)
                    .clickable(enabled = canSend) { onSend() }
                    .padding(horizontal = 14.dp, vertical = 9.dp))
        }
    }
}

/** 原型空会话中的示例任务(图标 + 文案资源 id)。
 * 存的是资源 id 而不是字符串:这里是顶层 val,拿不到 @Composable 的 stringResource,
 * 且文案必须在**当前语言**下解析 —— 在组合期取才是对的。 */
private val CHAT_SUGGESTS: List<Pair<String, Int>> = listOf(
    "⌨" to R.string.chat_suggest_1,
    "⚡" to R.string.chat_suggest_2,
    "▤" to R.string.chat_suggest_3,
    "🐞" to R.string.chat_suggest_4,
)
