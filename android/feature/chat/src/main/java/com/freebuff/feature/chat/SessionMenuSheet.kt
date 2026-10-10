package com.freebuff.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.ui.RFull
import com.freebuff.core.ui.navigation.LocalAppNavigator
import com.freebuff.core.ui.theme.LocalTokens
import androidx.compose.ui.res.stringResource

/**
 * 会话菜单:删除单个会话。
 * 「清空全部会话」已移到 设置 → 会话与数据(带二次确认),此处不再提供一键直删入口。
 */
@Composable
fun SessionMenuSheet(sessionId: String, viewModel: HomeViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    val navigator = LocalAppNavigator.current
    val sessions by viewModel.sessions.collectAsState()
    val s = sessions.firstOrNull { it.id == sessionId }
    Column(Modifier.padding(horizontal = 6.dp).padding(bottom = 10.dp)) {
        Text(if (s != null) s.title else stringResource(R.string.chat_session_menu_title), color = t.text2, fontSize = 12.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 12.dp))
        Text(stringResource(R.string.chat_session_delete), color = t.danger, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().clip(RFull).clickable {
                viewModel.deleteSession(sessionId)
                navigator.closeSheet()
            }.padding(vertical = 11.dp))
        Text(stringResource(R.string.action_cancel), color = t.text3, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().clip(RFull).clickable { navigator.closeSheet() }
                .padding(vertical = 11.dp))
    }
}
