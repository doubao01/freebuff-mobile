package com.freebuff.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.freebuff.app.ui.FreebuffRoot
import com.freebuff.core.ui.AppLanguage
import com.freebuff.core.ui.navigation.AppNavigator
import com.freebuff.mobile.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var navigator: AppNavigator

    /**
     * 通知权限(API 33+)。
     *
     * 为什么一进来就问:多轮 agent 任务的保活与「跑完了」提醒都靠通知。
     * 但**被拒也不影响功能**:前台服务在没有通知权限时依然合法运行(只是通知不可见),
     * 任务照常跑完;所以这里只提示一句,不做任何降级或阻断。
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                navigator.showSnack(getString(R.string.notify_permission_denied))
            }
        }

    /**
     * 应用内语言(26–32 走这里):把选定语言包进 Activity 的 base context,
     * 让 Compose 里的 `stringResource` 跟着选。33+ 由系统的应用级语言接管,
     * 此处按同一份偏好再包一层是幂等的(语言一致,不改变渲染)。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FreebuffRoot(navigator)
        }
        requestNotificationPermissionIfNeeded()
    }

    /** 只在未授权时发起;系统在用户拒绝两次后不再弹窗,因此不会反复打扰。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
