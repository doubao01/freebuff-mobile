package com.freebuff.core.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局导航状态。route 驱动根页面切换(welcome/home/chat),
 * sheet 驱动底部弹层(custom-form:xxx / sess-menu:xxx 等带参标识),
 * activeSessionId 表示当前打开的会话,snackMessage 为一次性提示。
 */
data class AppNavState(
    val route: String = ROUTE_WELCOME,
    val sheet: String? = null,
    /** 弹层关闭后要返回的弹层(如从任务向导跳去 Git 授权,完成后回到向导)。 */
    val returnSheet: String? = null,
    val activeSessionId: String? = null,
    val snackMessage: String? = null,
) {
    companion object {
        const val ROUTE_WELCOME = "welcome"
        const val ROUTE_HOME = "home"
        const val ROUTE_CHAT = "chat"
        const val ROUTE_SETTINGS = "settings"

        /** sheet id 常量 */
        const val SHEET_MODEL = "model"
        const val SHEET_TASK = "task-wizard"
        const val SHEET_SESSION_MENU = "sess-menu"
        const val SHEET_GIT = "git"
        const val SHEET_SEARCH = "search"
        const val SHEET_CUSTOM_MODELS = "custom-models"
        const val SHEET_CUSTOM_FORM = "custom-form"
        const val SHEET_UPDATE = "update"
        const val SHEET_ABOUT = "about"
    }
}

/**
 * 导航抽象:由 app 模块提供单例实现,feature 层通过 Hilt 注入调用。
 * 组合层通过 [LocalAppNavigator] 读取 [state]。
 */
interface AppNavigator {
    val state: StateFlow<AppNavState>

    fun navigate(route: String)
    fun openSession(sessionId: String)

    /**
     * 打开弹层。[returnTo] 指定该弹层关闭后应回到的弹层 id
     * (如从任务向导打开 Git 授权:openSheet(SHEET_GIT, SHEET_TASK));传 null 清除返回目标。
     */
    fun openSheet(sheet: String?, returnTo: String? = null)

    /** 关闭当前弹层;若存在 [AppNavState.returnSheet] 则回到该弹层(一次性)。 */
    fun closeSheet()
    fun showSnack(message: String)
    fun consumeSnack()
}

val LocalAppNavigator = staticCompositionLocalOf<AppNavigator> {
    error("LocalAppNavigator 未提供:请在 app 根节点注入 AppNavigator")
}
