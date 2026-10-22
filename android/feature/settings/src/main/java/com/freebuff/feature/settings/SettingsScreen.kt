package com.freebuff.feature.settings

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.freebuff.core.model.FEEDBACK_URL
import com.freebuff.core.model.OFFICIAL_SITE
import com.freebuff.core.model.ToolPermission
import com.freebuff.core.ui.AppLanguage
import com.freebuff.core.ui.RowCard
import com.freebuff.core.ui.SectionLabel
import com.freebuff.core.ui.SegRow
import com.freebuff.core.ui.SetRow
import com.freebuff.core.ui.openExternal
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.LocalAppNavigator
import com.freebuff.core.ui.theme.LocalTokens
import androidx.compose.ui.res.stringResource

/**
 * 工具权限配置区(工具调用开启时展示):逐工具三级分级(免确认/需确认/禁止)。
 * 行内展开;显示「已自定义」标记,改动即时生效并持久化到 settings 表。
 */
@Composable
private fun ToolPermissionSection(viewModel: SettingsViewModel) {
    val t = LocalTokens.current
    val overrides by viewModel.toolPermissionOverrides.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.set_tool_perm), color = t.text2, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
        // 尾部只有字形「⌄」说明可展开:补 onClickLabel,读屏才会说出动作
        val permCd = stringResource(if (expanded) R.string.set_cd_collapse else R.string.set_cd_expand)
        Text(if (overrides.isEmpty()) stringResource(R.string.set_tool_perm_default) else overrides.size.toString() + stringResource(R.string.set_tool_perm_custom),
            color = t.text3, fontSize = 11.sp,
            modifier = Modifier.clickable(onClickLabel = permCd, role = Role.Button) { expanded = !expanded })
    }
    if (expanded) {
        Spacer(Modifier.height(8.dp))
        com.freebuff.core.model.DefaultTools.ALL.forEach { tool ->
            val effective = overrides[tool.name] ?: com.freebuff.core.model.ToolPermissions.defaultFor(tool.name)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        com.freebuff.core.model.toolDisplayName(tool.name),
                        color = t.text, fontSize = 12.sp,
                    )
                    Text(tool.name, color = t.text3, fontSize = 9.5.sp)
                }
                SegRow(
                    listOf(stringResource(R.string.set_perm_allow), stringResource(R.string.set_perm_confirm), stringResource(R.string.set_perm_deny)),
                    when (effective) {
                        ToolPermission.ALLOW -> 0
                        ToolPermission.CONFIRM -> 1
                        ToolPermission.DENY -> 2
                    },
                    // 有界宽度:不传会让 SegRow 吃掉整行宽度,把左侧工具名挤成 0 宽(不可见)
                    Modifier.width(180.dp),
                ) { i ->
                    viewModel.setToolPermission(
                        tool.name,
                        when (i) { 0 -> ToolPermission.ALLOW; 2 -> ToolPermission.DENY; else -> ToolPermission.CONFIRM },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.set_tool_perm_help),
            color = t.text3, fontSize = 10.sp)
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val t = LocalTokens.current
    val navigator = LocalAppNavigator.current
    val context = LocalContext.current
    val themeMode by viewModel.themeMode.collectAsState()
    val modelName by viewModel.modelName.collectAsState()
    val git by viewModel.git.collectAsState()
    val searchConfigured by viewModel.searchKeyConfigured.collectAsState()
    val repoParse by viewModel.repoParse.collectAsState()
    val toolsEnabled by viewModel.toolsEnabled.collectAsState()
    val memoryEnabled by viewModel.memoryEnabled.collectAsState()
    val planMode by viewModel.planMode.collectAsState()
    val reasoningMode by viewModel.reasoningMode.collectAsState()
    val customModels by viewModel.customModels.collectAsState()
    val sessionCount by viewModel.sessionCount.collectAsState()
    val clearArmed by viewModel.clearArmed.collectAsState()

    Column(Modifier.fillMaxSize().padding(top = 54.dp)) {
        Text(stringResource(R.string.set_title), color = t.text, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 130.dp)) {

            /* ---------------- 外观 ---------------- */
            SectionLabel(stringResource(R.string.set_section_appearance), Modifier.padding(top = 16.dp, bottom = 8.dp))
            RowCard {
                val themes = listOf(stringResource(R.string.set_theme_system), stringResource(R.string.set_theme_light), stringResource(R.string.set_theme_dark))
                val idx = when (themeMode) { "light" -> 1; "dark" -> 2; else -> 0 }
                Text(stringResource(R.string.set_theme), color = t.text2, fontSize = 13.5.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
                SegRow(themes, idx) { i -> viewModel.setTheme(listOf("system", "light", "dark")[i]) }
                Spacer(Modifier.height(14.dp))
                // 语言不走 ViewModel:它必须在 attachBaseContext 里同步读到(见 AppLanguage 注释)
                val language by AppLanguage.language.collectAsState()
                Text(stringResource(R.string.set_language), color = t.text2, fontSize = 13.5.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
                SegRow(
                    listOf(
                        stringResource(R.string.set_lang_system),
                        stringResource(R.string.set_lang_zh),
                        stringResource(R.string.set_lang_en),
                    ),
                    AppLanguage.OPTIONS.indexOf(language).coerceAtLeast(0),
                ) { i ->
                    // 26–32 没有系统级 API,得自己重建界面;33+ 由系统重建,不重复触发
                    if (AppLanguage.set(context, AppLanguage.OPTIONS[i])) {
                        (context as? Activity)?.recreate()
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.set_language_help), color = t.text3, fontSize = 10.sp)
            }

            /* ---------------- 模型 ---------------- */
            SectionLabel(stringResource(R.string.set_section_model), Modifier.padding(top = 18.dp, bottom = 8.dp))
            RowCard {
                SetRow(
                    // 图标一律用等宽字形符号:emoji(⚡/🗑/💬)在浅色主题下只按自己的彩色渲染,实测对比度仅 1.5–2.6:1
                    icon = "✦",
                    title = modelName.ifBlank { stringResource(R.string.set_model_title) },
                    sub = stringResource(R.string.set_model_sub),
                    onClick = { viewModel.openModelSheet() },
                )
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.set_tools), color = t.text2, fontSize = 13.5.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
                SegRow(listOf(stringResource(R.string.set_on), stringResource(R.string.set_off)), if (toolsEnabled) 0 else 1) { i ->
                    viewModel.setToolsEnabled(i == 0)
                }
                if (toolsEnabled) {
                    Spacer(Modifier.height(14.dp))
                    ToolPermissionSection(viewModel)
                }
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.set_memory), color = t.text2, fontSize = 13.5.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
                SegRow(listOf(stringResource(R.string.set_on), stringResource(R.string.set_off)), if (memoryEnabled) 0 else 1) { i ->
                    viewModel.setMemoryEnabled(i == 0)
                }
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.set_plan_mode), color = t.text2, fontSize = 13.5.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
                SegRow(listOf(stringResource(R.string.set_on), stringResource(R.string.set_off)), if (planMode) 0 else 1) { i ->
                    viewModel.setPlanMode(i == 0)
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.set_plan_mode_help), color = t.text3, fontSize = 10.sp)
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.set_reasoning), color = t.text2, fontSize = 13.5.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
                SegRow(
                    listOf(stringResource(R.string.set_off), stringResource(R.string.set_reasoning_auto), stringResource(R.string.set_on)),
                    when (reasoningMode) {
                        com.freebuff.core.model.Reasoning.MODE_OFF -> 0
                        com.freebuff.core.model.Reasoning.MODE_ON -> 2
                        else -> 1
                    },
                ) { i ->
                    viewModel.setReasoningMode(
                        listOf(
                            com.freebuff.core.model.Reasoning.MODE_OFF,
                            com.freebuff.core.model.Reasoning.MODE_AUTO,
                            com.freebuff.core.model.Reasoning.MODE_ON,
                        )[i],
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.set_reasoning_help),
                    color = t.text3, fontSize = 10.sp,
                )
            }

            /* ---------------- 集成(本模块文案尚未迁到 strings.xml,见 docs/i18n-and-accessibility.md) ---------------- */
            SectionLabel(stringResource(R.string.set_section_integrations), Modifier.padding(top = 18.dp, bottom = 8.dp))
            RowCard {
                SetRow(
                    icon = "◈",
                    title = stringResource(R.string.set_git_account),
                    sub = stringResource(R.string.set_git_account_sub),
                    trailing = if (git.connected) git.name.ifEmpty { git.login } + stringResource(R.string.set_git_connected_suffix) else stringResource(R.string.set_git_not_connected),
                    accent = git.connected,
                    // 空状态置灰:SetRow 不再自己比字符串(文案多语言后那种比较会静默失效)
                    dim = !git.connected,
                    onClick = { navigator.openSheet(AppNavState.SHEET_GIT) },
                )
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.set_repo_parse), color = t.text2, fontSize = 13.5.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
                SegRow(listOf(stringResource(R.string.set_repo_strict), stringResource(R.string.set_repo_loose)),
                    if (repoParse == "strict") 0 else 1) { i ->
                    viewModel.setRepoParse(if (i == 0) "strict" else "loose")
                }
                Spacer(Modifier.height(14.dp))
                SetRow(
                    icon = "◎",
                    title = stringResource(R.string.set_search_title),
                    sub = stringResource(R.string.set_search_row_sub),
                    trailing = if (searchConfigured) stringResource(R.string.set_search_on) else stringResource(R.string.set_search_off),
                    accent = searchConfigured,
                    dim = !searchConfigured,
                    onClick = { navigator.openSheet(AppNavState.SHEET_SEARCH) },
                )
                Spacer(Modifier.height(14.dp))
                SetRow(
                    icon = "▣",
                    title = stringResource(R.string.set_custom_models),
                    sub = stringResource(R.string.set_custom_models_sub),
                    trailing = if (customModels.isEmpty()) stringResource(R.string.set_custom_not_added) else customModels.size.toString() + stringResource(R.string.set_count_items),
                    accent = customModels.isNotEmpty(),
                    dim = customModels.isEmpty(),
                    onClick = { navigator.openSheet(AppNavState.SHEET_CUSTOM_MODELS) },
                )
            }

            /* ---------------- 会话与数据 ---------------- */
            SectionLabel(stringResource(R.string.set_section_data), Modifier.padding(top = 18.dp, bottom = 8.dp))
            RowCard {
                SetRow(
                    icon = "▤",
                    title = stringResource(R.string.set_data_local_title),
                    sub = stringResource(R.string.set_data_local_sub),
                )
                Spacer(Modifier.height(4.dp))
                SetRow(
                    icon = "✕",
                    title = if (clearArmed) stringResource(R.string.set_clear_confirm) else stringResource(R.string.set_clear_all),
                    sub = if (clearArmed) stringResource(R.string.set_clear_confirm_hint)
                    else stringResource(R.string.set_clear_danger_prefix) + sessionCount + stringResource(R.string.set_clear_danger_suffix),
                    danger = true,
                    onClick = {
                        if (clearArmed) viewModel.clearAllSessions() else viewModel.armClear()
                    },
                )
            }

            /* ---------------- 关于 ---------------- */
            SectionLabel(stringResource(R.string.set_section_about), Modifier.padding(top = 18.dp, bottom = 8.dp))
            RowCard {
                SetRow(
                    icon = "ⓘ",
                    title = stringResource(R.string.set_about_title),
                    sub = stringResource(R.string.set_about_sub),
                    onClick = { navigator.openSheet(AppNavState.SHEET_ABOUT) },
                )
                SetRow(
                    icon = "↻",
                    title = stringResource(R.string.set_version),
                    sub = stringResource(R.string.set_version_check),
                    trailing = "v" + viewModel.appVersion,
                    onClick = { navigator.openSheet(AppNavState.SHEET_UPDATE) },
                )
                SetRow(
                    icon = "↗",
                    title = stringResource(R.string.set_official_site),
                    sub = OFFICIAL_SITE,
                    onClick = { openExternal(context, OFFICIAL_SITE) },
                )
                SetRow(
                    icon = "✎",
                    title = stringResource(R.string.set_feedback),
                    sub = stringResource(R.string.set_feedback_sub),
                    onClick = { openExternal(context, FEEDBACK_URL) },
                )
            }

            /* ---------------- 页脚 ---------------- */
            Spacer(Modifier.height(22.dp))
            Column(Modifier.align(Alignment.CenterHorizontally)) {
                Text("Freebuff Mobile", color = t.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("v" + viewModel.appVersion, color = t.text3, fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 3.dp))
                Text("Made for the Freebuff open-source project", color = t.text3, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
