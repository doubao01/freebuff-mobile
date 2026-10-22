package com.freebuff.feature.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.model.Session
import com.freebuff.core.model.groupSessions
import com.freebuff.core.model.sessionTimeLabel
import com.freebuff.core.model.zoneOffsetMillis
import com.freebuff.core.ui.ModelChip
import com.freebuff.core.ui.RFull
import com.freebuff.core.ui.SectionLabel
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.LocalAppNavigator
import com.freebuff.core.ui.theme.LocalTokens
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription

/** 左滑露出的删除钮宽度。 */
private val REVEAL_ACTION_WIDTH = 84.dp

/** 滑开吸附阈值(占删除钮宽度的比例)与甩出速度(px/s)。 */
private const val REVEAL_OPEN_RATIO = 0.45f
private const val REVEAL_FLING_VELOCITY = -900f

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    val navigator = LocalAppNavigator.current
    val sessions by viewModel.sessions.collectAsState()
    val query by viewModel.query.collectAsState()
    val modelName by viewModel.modelName.collectAsState()
    val undo by viewModel.undo.collectAsState()
    // 同一时刻只允许一行滑开:滑开 B 时 A 自动收回,列表不会出现两排删除钮
    var openId by remember { mutableStateOf<String?>(null) }
    // 时间分组:列表本身已按「新会话在前」排序,分组只是把同一时间桶的相邻项合并
    val sections = remember(sessions) {
        val now = System.currentTimeMillis()
        groupSessions(sessions, now, zoneOffsetMillis(now))
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 54.dp)) {
            Text(stringResource(R.string.chat_home_title), color = t.text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(10.dp))
            SearchRow(query, viewModel::updateQuery)
            ModelChip(
                modelName = modelName,
                onClick = viewModel::openModelSheet,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
            )
            Spacer(Modifier.height(6.dp))
            if (sessions.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(120.dp))
                    if (query.isNotBlank()) {
                        Text(stringResource(R.string.chat_home_no_match), color = t.text2, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.chat_home_no_match_hint), color = t.text3, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp))
                    } else {
                        Text(stringResource(R.string.chat_home_empty), color = t.text2, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.chat_home_empty_hint), color = t.text3, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
                    sections.forEach { section ->
                        item(key = "sec:" + section.bucket.name) {
                            SectionLabel(section.label, Modifier.padding(start = 20.dp, top = 16.dp))
                        }
                        items(section.sessions, key = { it.id }) { s ->
                            SwipeSessionRow(
                                session = s,
                                open = openId == s.id,
                                onOpenChange = { open -> openId = if (open) s.id else null },
                                onOpen = { viewModel.openChat(s.id) },
                                onMenu = {
                                    openId = null
                                    navigator.openSheet(AppNavState.SHEET_SESSION_MENU + ":" + s.id)
                                },
                                onDelete = {
                                    openId = null
                                    viewModel.deleteSession(s.id)
                                },
                            )
                        }
                    }
                }
            }
        }
        // 删除即时生效,底部浮条给一次原样恢复的机会(撤销取消后自动收起)
        undo?.let { snapshot ->
            UndoBar(
                text = stringResource(R.string.chat_home_deleted, snapshot.session.title),
                onUndo = viewModel::undoDelete,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp),
            )
        }
    }
}

@Composable
private fun SearchRow(query: String, onQuery: (String) -> Unit) {
    val t = LocalTokens.current
    Row(
        modifier = Modifier.padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.surface)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🔍", color = t.text3, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.chat_home_search_hint), color = t.text3, fontSize = 13.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = t.text, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            val clearCd = stringResource(R.string.chat_cd_clear_search)
            Text("✕", color = t.text3, fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = clearCd, role = Role.Button) { onQuery("") }
                    .clearAndSetSemantics { contentDescription = clearCd }
                    .padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

/**
 * 左滑露出删除钮的会话行。滑开后点正文先收回(不误入会话),点红色「删除」才真的删;
 * 拖动结束由状态回写吸附位置 —— 拖动中直接跟手,松手后走 [tween] 吸附。
 */
@Composable
private fun SwipeSessionRow(
    session: Session,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    onDelete: () -> Unit,
) {
    val t = LocalTokens.current
    val revealPx = with(LocalDensity.current) { REVEAL_ACTION_WIDTH.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // 打开/关闭由外部状态驱动:拖动结束回写状态,这里只做吸附动画
    LaunchedEffect(open, revealPx) {
        offset.animateTo(if (open) -revealPx else 0f, tween(180))
    }
    Box(Modifier.fillMaxWidth()) {
        // 背板:滑开后露出的删除钮,右对齐铺满行高
        Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            Box(
                modifier = Modifier.width(REVEAL_ACTION_WIDTH).fillMaxHeight()
                    .background(t.dangerFill)
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.chat_home_reveal_delete), color = t.onDanger, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        SessionRow(
            session = session,
            onTap = { if (open) onOpenChange(false) else onOpen() },
            onMenu = onMenu,
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value + delta).coerceIn(-revealPx, 0f)) }
                    },
                    onDragStopped = { velocity ->
                        onOpenChange(-offset.value > revealPx * REVEAL_OPEN_RATIO || velocity < REVEAL_FLING_VELOCITY)
                    },
                ),
        )
    }
}

@Composable
private fun SessionRow(
    session: Session,
    onTap: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalTokens.current
    val openCd = stringResource(R.string.chat_cd_open_chat)
    val menuCd = stringResource(R.string.chat_cd_session_menu)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = openCd, role = Role.Button) { onTap() }
            .background(t.surface)
            .padding(horizontal = 18.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(session.title, color = t.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(sessionTimeLabel(session, System.currentTimeMillis()), color = t.text3, fontSize = 11.sp,
                modifier = Modifier.padding(start = 8.dp))
            // "⋯" 是字形:给「会话操作」这个说法,否则读屏只说得到一个「省略号」
            Text("⋯", color = t.text3, fontSize = 16.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = menuCd, role = Role.Button) { onMenu() }
                    .clearAndSetSemantics { contentDescription = menuCd }
                    .padding(horizontal = 8.dp, vertical = 2.dp))
        }
        // 列表只订会话行(不带消息):预览就是唯一正文来源,写入侧已经保证它不为空
        Text(session.preview,
            color = t.text2, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp))
    }
}

/** 「已删除 + 撤销」浮条。删除后短暂浮在列表底部,点「撤销」按原位恢复。 */
@Composable
private fun UndoBar(text: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Row(
        modifier = modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(t.elev)
            .border(1.dp, t.borderStrong, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text, color = t.text2, fontSize = 12.5.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.chat_home_undo), color = t.accent, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RFull).clickable { onUndo() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
