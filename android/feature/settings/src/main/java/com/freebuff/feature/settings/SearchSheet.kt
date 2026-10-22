package com.freebuff.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.ui.R14
import com.freebuff.core.ui.RFull
import com.freebuff.core.ui.SheetScaffold
import com.freebuff.core.ui.openExternal
import com.freebuff.core.ui.theme.LocalTokens

/** Exa 控制台:用户在这里取 API key(与工具实现里的端点同源)。 */
private const val EXA_KEYS_URL = "https://dashboard.exa.ai/api-keys"

/**
 * 联网搜索(Exa)密钥录入。
 *
 * 三条刻意的取舍:
 * 1. **不回显**:密钥存的是 Keystore 密文,页面只显示「已配置/未配置」,
 *    输入框永远从空白开始 —— 打开设置页就能看到别人的密钥是更糟的失败。
 * 2. **粘贴即用,不做「测试连接」按钮**:真实验证发生在第一次 web_search 调用,
 *    那里已经有降级与原因回填(见 ToolExecutors.webSearch),再加一个假探针只会让用户多等一次。
 * 3. **空白保存被忽略**:误触保存不该把可用配置抹成空,清除是单独的破坏性动作。
 */
@Composable
fun SearchSheet(viewModel: SettingsViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    val context = LocalContext.current
    val configured by viewModel.searchKeyConfigured.collectAsState()
    var input by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    SheetScaffold(
        stringResource(R.string.set_search_title),
        stringResource(R.string.set_search_sub),
    ) {
        // 状态行:已配置时给出明确的「已生效」,未配置时说明会降级到免钥通道
        Row(
            Modifier.fillMaxWidth().clip(R14).background(t.surface2).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (configured) "●" else "○",
                color = if (configured) t.accent else t.text3,
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (configured) R.string.set_search_configured else R.string.set_search_not_configured),
                    color = t.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.set_search_status_hint),
                    color = t.text3, fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 密钥输入:默认遮起来;右侧「显示/隐藏」与自定义模型表单一致
        Row(
            Modifier.fillMaxWidth().clip(R14).background(t.surface2).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.set_search_key_label),
                    color = t.text3, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp),
                )
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = TextStyle(color = t.text, fontSize = 13.sp),
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
            Text(
                stringResource(if (visible) R.string.set_hide else R.string.set_show),
                color = t.text2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RFull)
                    .clickable(role = Role.Button) { visible = !visible }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.set_search_save),
                color = t.accentInk, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RFull).background(t.accent)
                    .clickable(role = Role.Button) { viewModel.saveSearchKey(input) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
            if (configured) {
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.set_search_clear),
                    color = t.danger, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RFull)
                        .clickable(role = Role.Button) { viewModel.clearSearchKey() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.set_search_help),
            color = t.text3, fontSize = 10.5.sp, lineHeight = 15.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.set_search_get_key),
            color = t.accent, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RFull)
                .clickable(role = Role.Button) { openExternal(context, EXA_KEYS_URL) }
                .padding(horizontal = 4.dp, vertical = 6.dp),
        )
    }
}
