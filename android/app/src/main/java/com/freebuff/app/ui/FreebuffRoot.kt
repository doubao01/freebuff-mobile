package com.freebuff.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
// app 模块的 namespace(com.freebuff.mobile)与文件所在的 Kotlin 包不同,必须显式导入 R
import com.freebuff.mobile.R
import com.freebuff.app.CrashReporter
import com.freebuff.app.RootViewModel
import com.freebuff.core.ui.navigation.AppNavState
import com.freebuff.core.ui.navigation.AppNavigator
import com.freebuff.core.ui.navigation.LocalAppNavigator
import com.freebuff.core.ui.theme.DarkTokens
import com.freebuff.core.ui.theme.FreebuffTheme
import com.freebuff.core.ui.theme.LightTokens
import com.freebuff.core.ui.theme.LocalTokens
import com.freebuff.feature.auth.WelcomeScreen
import com.freebuff.feature.chat.ChatScreen
import com.freebuff.feature.chat.ChatViewModel
import com.freebuff.feature.chat.HomeScreen
import com.freebuff.feature.chat.ModelSheet
import com.freebuff.feature.chat.SessionMenuSheet
import com.freebuff.feature.chat.TaskWizardSheet
import com.freebuff.feature.settings.AboutSheet
import com.freebuff.feature.settings.CustomModelFormSheet
import com.freebuff.feature.settings.CustomModelsSheet
import com.freebuff.feature.settings.GitSheet
import com.freebuff.feature.settings.SearchSheet
import com.freebuff.feature.settings.SettingsScreen
import com.freebuff.feature.settings.SettingsViewModel
import com.freebuff.feature.settings.UpdateSheet

/**
 * 应用根:路由 when + BottomBar + ModalSheetHost + snackbar。
 * 所有 ViewModel 绑定 Activity 级 ViewModelStore,此处 hiltViewModel()
 * 与 feature screen 拿到的是同一实例——任务向导提交后直接驱动 ChatViewModel。
 */
@Composable
fun FreebuffRoot(navigator: AppNavigator) {
    val rootVm: RootViewModel = hiltViewModel()
    val chatVm: ChatViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val navState by navigator.state.collectAsState()
    val themeMode by settingsVm.themeMode.collectAsState()
    val repairReport by rootVm.repairReport.collectAsState()

    // 上次异常退出的黑匣子回看:欢迎页横幅 + 可复制堆栈(诊断启动崩溃用)
    val context = androidx.compose.ui.platform.LocalContext.current
    val crashBanner = remember { mutableStateOf(CrashReporter.hasCrash(context)) }
    if (crashBanner.value) {
        CrashBanner(
            onCopy = {
                CrashReporter.readLast(context)?.let { text ->
                    CrashReporter.copyToClipboard(context, text)
                    navigator.showSnack(context.getString(R.string.crash_stack_copied))
                }
                CrashReporter.clear(context)
            },
            onDismiss = {
                CrashReporter.clear(context)
                crashBanner.value = false
            },
        )
    }

    // 一次性迁移:把清洗修复报告注入设置页,供「恢复原始记录」
    LaunchedEffect(repairReport) {
        repairReport?.let {
            settingsVm.setRepairReport(it)
            rootVm.consumeRepairReport()
        }
    }

    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val tokens = if (dark) DarkTokens else LightTokens

    // 系统返回键:弹层打开时优先关弹层(含返回目标);聊天页返回回首页
    BackHandler(enabled = navState.sheet != null) { navigator.closeSheet() }
    BackHandler(enabled = navState.sheet == null && navState.route == AppNavState.ROUTE_CHAT) {
        navigator.navigate(AppNavState.ROUTE_HOME)
    }

    CompositionLocalProvider(LocalAppNavigator provides navigator) {
        FreebuffTheme(darkTheme = dark) {
            CompositionLocalProvider(LocalTokens provides tokens) {
                // 弹窗(Dialog)要自己避让导航条,而根 Box 的 navigationBarsPadding() 会消费掉
                // 这些 insets(子树里再读只能是 0),所以在根 Box 之前先把真实值取出来。
                val dlgNavBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                // Dialog 窗口是 wrap-content,fillMaxHeight 拿不到可用高,只能自己算
                val screenHeight = LocalConfiguration.current.screenHeightDp.dp
                val snackHost = remember { SnackbarHostState() }
                val snackMessage = navState.snackMessage
                LaunchedEffect(snackMessage) {
                    snackMessage?.let {
                        snackHost.showSnackbar(it)
                        navigator.consumeSnack()
                    }
                }
                // 边到边绘制:背景铺满全屏,内容用系统栏内边距避让状态栏/手势条
                Box(Modifier.fillMaxSize().background(tokens.bg).statusBarsPadding().navigationBarsPadding()) {
                    when (navState.route) {
                        AppNavState.ROUTE_WELCOME -> WelcomeScreen()
                        AppNavState.ROUTE_HOME -> HomeScreen()
                        AppNavState.ROUTE_CHAT -> ChatScreen()
                        AppNavState.ROUTE_SETTINGS -> SettingsScreen()
                    }
                    if (navState.route == AppNavState.ROUTE_HOME || navState.route == AppNavState.ROUTE_SETTINGS) {
                        BottomBar(navigator, Modifier.align(Alignment.BottomCenter))
                    }
                    navState.sheet?.let { id ->
                        if (id.startsWith("sess-menu:")) {
                            ModalSheetHost(false, { navigator.closeSheet() }) {
                                SessionMenuSheet(id.removePrefix("sess-menu:"))
                            }
                        } else if (id == AppNavState.SHEET_CUSTOM_FORM ||
                            id.startsWith(AppNavState.SHEET_CUSTOM_FORM + ":")
                        ) {
                            // 自定义模型表单:居中弹窗(Dialog),内容长的表单比底部面板更聚焦。
                            // 页脚避让导航条用 dlgNavBottom(在主窗口算好的真实内边距):Dialog 窗口里
                            // navigationBarsPadding() 拿不到值(实测页脚按钮有 66px 压在导航条下,
                            // 「保存」摸不到一半);顶部不用加——Dialog 窗口本身已从状态栏下沿开始。
                            val editId = id.removePrefix(AppNavState.SHEET_CUSTOM_FORM)
                                .removePrefix(":").ifBlank { null }
                            androidx.compose.ui.window.Dialog(
                                onDismissRequest = { navigator.closeSheet() },
                                properties = androidx.compose.ui.window.DialogProperties(
                                    usePlatformDefaultWidth = false,
                                    decorFitsSystemWindows = false,
                                ),
                            ) {
                                    Box(Modifier.fillMaxSize()
                                        .padding(bottom = dlgNavBottom + 24.dp, top = 24.dp)
                                        .padding(horizontal = 18.dp),
                                        contentAlignment = Alignment.Center) {
                                        Column(
                                            // 必须显式限高:表单内容本身比屏幕高,Dialog 窗口又是 wrap-content,
                                            // 只加 padding 时卡片会撑破避让区域、页脚重新压回导航条(实测 2306 > 2274)。
                                            // 用屏幕高 - 导航条内边距 - 留白 封顶,表单内部滚动、页脚固定在可用区内。
                                            Modifier.fillMaxWidth()
                                                .heightIn(max = screenHeight - dlgNavBottom - 72.dp)
                                                .clip(RoundedCornerShape(22.dp))
                                                .background(tokens.elev)
                                                .border(1.dp, tokens.border, RoundedCornerShape(22.dp))
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                        ) {
                                        CustomModelFormSheet(editId = editId)
                                    }
                                }
                            }
                        } else {
                            ModalSheetHost(false, { navigator.closeSheet() }) {
                                when (id) {
                                    AppNavState.SHEET_MODEL -> ModelSheet()
                                    AppNavState.SHEET_TASK -> TaskWizardSheet(
                                        onLaunch = { sessionId, desc, ctxRepo, ctxModel ->
                                            chatVm.sendMessage(desc, ctxRepo, ctxModel, sessionIdHint = sessionId)
                                        },
                                    )
                                    AppNavState.SHEET_GIT -> GitSheet()
                                    AppNavState.SHEET_SEARCH -> SearchSheet()
                                    AppNavState.SHEET_CUSTOM_MODELS -> CustomModelsSheet()
                                    AppNavState.SHEET_UPDATE -> UpdateSheet()
                                    AppNavState.SHEET_ABOUT -> AboutSheet()
                                }
                            }
                        }
                    }
                    // 提示条必须画在弹层之后(z 序靠后):否则弹层期间的用户反馈
                    // (如 Git 授权失败原因)会被弹层面板盖住 —— 表现就是「点了没反应」
                    SnackbarHost(snackHost, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
                }
            }
        }
    }
}

/** 底部弹层宿主:半透明遮罩 + 底部圆角面板。expand 时撑高到 90%。 */
@Composable
fun ModalSheetHost(expand: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current
    val noRipple = remember { MutableInteractionSource() }
    BoxWithConstraints(Modifier.fillMaxSize().background(t.backdrop)) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = noRipple, indication = null) { onDismiss() })
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(t.elev)
                .then(if (expand) Modifier.fillMaxHeight(0.9f) else Modifier.heightIn(max = maxHeight * 0.86f))
                .clickable(interactionSource = noRipple, indication = null) {},
        ) { content() }
    }
}

@Composable
private fun BottomBar(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val navState by navigator.state.collectAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(t.tabbar)
            .padding(horizontal = 26.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TabItem(stringResource(R.string.tab_sessions), navState.route == AppNavState.ROUTE_HOME,
            navState.route != AppNavState.ROUTE_HOME) {
            navigator.navigate(AppNavState.ROUTE_HOME)
        }
        // "+" 是纯字形:读屏读不出「发起任务」,必须自己给描述
        val newTaskCd = stringResource(R.string.cd_new_task)
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(t.accent)
                .clickable(onClickLabel = newTaskCd, role = Role.Button) { navigator.openSheet(AppNavState.SHEET_TASK) }
                .clearAndSetSemantics { contentDescription = newTaskCd },
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = t.accentInk, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        TabItem(stringResource(R.string.tab_settings), navState.route == AppNavState.ROUTE_SETTINGS,
            navState.route != AppNavState.ROUTE_SETTINGS) {
            navigator.navigate(AppNavState.ROUTE_SETTINGS)
        }
    }
}

/**
 * 上次异常退出横幅(顶部覆盖层,颜色自持不依赖主题——崩溃场景下主题可能未就绪)。
 * 「复制堆栈」把黑匣子全文交给剪贴板;两个动作都会清掉记录,横幅只在下次崩溃后再现。
 */
@Composable
private fun CrashBanner(onCopy: () -> Unit, onDismiss: () -> Unit) {
    val bg = Color(0xFEEE4444)
    Column(
        Modifier.fillMaxWidth().background(bg).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚠", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.crash_banner_title),
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.crash_banner_dismiss),
                color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { onDismiss() }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.crash_banner_copy),
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.18f))
                .clickable(role = Role.Button) { onCopy() }.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun TabItem(label: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button) { onClick() }
            .padding(horizontal = 22.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = if (on) t.accent else t.text3, fontSize = 12.5.sp,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
    }
}
