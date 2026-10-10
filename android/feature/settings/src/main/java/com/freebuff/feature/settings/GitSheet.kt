package com.freebuff.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.data.repository.GitConnectStep
import com.freebuff.core.ui.R14
import com.freebuff.core.ui.RFull
import com.freebuff.core.ui.SheetScaffold
import com.freebuff.core.ui.openExternal
import com.freebuff.core.ui.navigation.LocalAppNavigator
import com.freebuff.core.ui.theme.LocalTokens
import androidx.compose.ui.res.stringResource

private data class Provider(val key: String, val name: String, val host: String)

/** 正式版只支持已接通的服务商;未接通的不在 UI 里出现,避免点了没反应。 */
private val PROVIDERS = listOf(
    Provider("github", "GitHub", "github.com"),
)

/**
 * Git 账号:关联 / 断开。
 * 走真实 GitHub 设备流:展示用户码并轮询直到授权完成。
 */
@Composable
fun GitSheet(viewModel: SettingsViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val g by viewModel.git.collectAsState()
    val step by viewModel.gitConnect.collectAsState()
    val error by viewModel.gitError.collectAsState()

    // 拿到用户码后自动拉起浏览器授权页(仅一次)
    val awaiting = step as? GitConnectStep.AwaitingUser
    LaunchedEffect(awaiting?.userCode) {
        awaiting?.let { openExternal(context, it.verificationUri) }
    }

    SheetScaffold(stringResource(R.string.set_git_account), stringResource(R.string.set_git_link_sub)) {
        when {
            g.connected -> ConnectedCard(
                provider = g.provider,
                login = g.login,
                name = g.name,
                onRevoke = {
                    viewModel.revokeGit()
                    navigator.closeSheet()
                },
            )

            step is GitConnectStep.AwaitingUser -> AwaitingCard(
                userCode = (step as GitConnectStep.AwaitingUser).userCode,
                verificationUri = (step as GitConnectStep.AwaitingUser).verificationUri,
                onOpen = { uri -> openExternal(context, uri) },
                onCopy = { code -> clipboard.setText(AnnotatedString(code)) },
                onCancel = { viewModel.cancelGitConnect() },
            )

            else -> ProviderList(
                error = error,
                onPick = { viewModel.connectGit() },
            )
        }
    }
}

@Composable
private fun ConnectedCard(provider: String, login: String, name: String, onRevoke: () -> Unit) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().clip(R14).background(t.surface2).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(provider.uppercase().take(1), color = t.accentInk, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(t.accent).padding(horizontal = 9.dp, vertical = 5.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(name.ifEmpty { login }, color = t.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(login + stringResource(R.string.set_git_connected_suffix), color = t.text3, fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.set_git_disconnect), color = t.danger, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RFull).clickable { onRevoke() }.padding(horizontal = 18.dp, vertical = 9.dp))
    }
}

/** 设备流等待页:展示用户码 + 打开授权页 + 轮询状态,可取消。 */
@Composable
private fun AwaitingCard(
    userCode: String,
    verificationUri: String,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().clip(R14).background(t.surface2).padding(16.dp)) {
        Text(stringResource(R.string.set_git_device_title), color = t.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.set_git_device_hint), color = t.text3, fontSize = 11.5.sp,
            modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(userCode, color = t.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(t.surface).padding(horizontal = 14.dp, vertical = 8.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.set_copy), color = t.text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RFull).clickable { onCopy(userCode) }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(verificationUri, color = t.text3, fontSize = 11.sp)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.set_git_reopen), color = t.accentInk, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RFull).background(t.accent).clickable { onOpen(verificationUri) }
                    .padding(horizontal = 16.dp, vertical = 9.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.set_cancel), color = t.text2, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RFull).clickable { onCancel() }.padding(horizontal = 14.dp, vertical = 9.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.set_git_waiting), color = t.text3, fontSize = 11.sp)
    }
}

@Composable
private fun ProviderList(
    error: String?,
    onPick: (Provider) -> Unit,
) {
    val t = LocalTokens.current
    // 失败原因就地展示(如「GitHub OAuth client_id 未配置」):
    // 未配置时点一下就能看到为什么没反应,而不是毫无反馈
    error?.let {
        Text(it, color = t.danger, fontSize = 11.5.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(bottom = 10.dp))
    }
    PROVIDERS.forEach { p ->
        val dot = when (p.key) {
            "github" -> t.accent
            "gitlab" -> t.tier
            else -> t.warn
        }
        Row(Modifier.fillMaxWidth().clip(R14).background(t.surface2).clickable { onPick(p) }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(RFull).background(dot))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, color = t.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(p.host, color = t.text3, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    Text(stringResource(R.string.set_git_flow_note),
        color = t.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
}
