package com.freebuff.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.freebuff.core.model.AgentNotify
import com.freebuff.mobile.R

/**
 * agent 长任务的前台服务:让多轮对话在用户切走/锁屏后继续跑,结束时发一条可点回的完成通知。
 *
 * 为什么需要它:没有前台服务的进程会被系统当成缓存进程冻结(Android 12+ 的 app freezer)或回收,
 * 流式读取与工具循环随之断掉 —— 表现就是「切走一会儿回来,回复停在半截」。
 *
 * 三个动作都由 [AgentTaskNotifierImpl] 用 `startForegroundService` 发过来:
 * - [ACTION_START]:挂上「正在生成」常驻通知并进入前台;
 * - [ACTION_PROGRESS]:原地更新常驻通知的正文(轮次/工具);
 * - [ACTION_FINISH]:撤下常驻通知;`notify=true` 时再发一条完成通知,然后停止自己。
 *
 * 关键约束:**每条路径都先调用一次 `startForeground`**。用 `startForegroundService` 拉起的服务
 * 必须在 5 秒内进入前台,否则系统会抛 `RemoteServiceException` —— 即便这次只是来「收尾」的,
 * 也要先就位再退出(否则收尾那条 intent 反而成了崩溃源)。
 */
class AgentForegroundService : Service() {

    /**
     * 常驻通知的当前状态:存**原始值**(会话名、进度),文案在发通知时才拼。
     *
     * 为什么存原始值而不是拼好的句子:通知文案要跟当前语言走,而语言在拼文案时才需要读;
     * 字段初始化阶段 Service 还没接到 base Context,拿不到 `getString`。
     */
    private var sessionTitle: String = ""
    private var lastDetail: String = ""

    /** 当前语言下的固定说法(取用时组拼,避免字段初始化期访问资源)。 */
    private fun runningTitleNow(): String =
        AgentNotify.runningTitle(sessionTitle, getString(R.string.notify_running))

    private fun runningTextNow(detail: String = lastDetail): String =
        AgentNotify.runningText(detail, getString(R.string.notify_connecting))

    companion object {
        const val ACTION_START = "com.freebuff.app.agent.START"
        const val ACTION_PROGRESS = "com.freebuff.app.agent.PROGRESS"
        const val ACTION_FINISH = "com.freebuff.app.agent.FINISH"

        const val EXTRA_TITLE = "title"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_SUMMARY = "summary"
        const val EXTRA_OK = "ok"
        const val EXTRA_NOTIFY = "notify"

        /** 通知渠道只需要建一次;进程内记住即可(渠道本身由系统持久化)。 */
        @Volatile
        private var channelsReady = false

        private fun channelRunning(context: Context) = NotificationChannel(
            AgentNotify.CHANNEL_RUNNING,
            context.getString(R.string.notify_channel_running),
            // LOW + 静音:它只是状态牌,不该在每条消息都响一下
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.notify_channel_running_desc) }

        private fun channelDone(context: Context) = NotificationChannel(
            AgentNotify.CHANNEL_DONE,
            context.getString(R.string.notify_channel_done),
            // DEFAULT:跑完提醒一次,这是本功能的重点
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notify_channel_done_desc) }

        /** 建渠道(idempotent;重复创建同 id 是安全的)。渠道名只在首次创建时生效(之后由系统保留)。 */
        fun ensureChannels(context: Context) {
            if (channelsReady) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(channelRunning(context))
            nm.createNotificationChannel(channelDone(context))
            channelsReady = true
        }

        /** 点通知回到 App(不新开一个任务栈;App 还活着时直接回到原来的界面)。 */
        private fun openApp(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun runningNotification(context: Context, title: String, text: String): Notification =
            NotificationCompat.Builder(context, AgentNotify.CHANNEL_RUNNING)
                .setSmallIcon(R.drawable.ic_stat_freebuff)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openApp(context))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

        private fun doneNotification(
            context: Context,
            title: String,
            text: String,
        ): Notification = NotificationCompat.Builder(context, AgentNotify.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_freebuff)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_TITLE)?.let { if (it.isNotBlank()) sessionTitle = it }
        when (intent?.action) {
            ACTION_PROGRESS -> {
                // 就地更新常驻通知(不重新进入前台,只是改文案)
                lastDetail = intent.getStringExtra(EXTRA_DETAIL).orEmpty()
                startForegroundCompat(runningNotification(this, runningTitleNow(), runningTextNow()))
            }
            ACTION_FINISH -> {
                // 先就位以满足「5 秒内进入前台」的硬约束,再撤下常驻通知
                startForegroundCompat(runningNotification(this, runningTitleNow(), runningTextNow()))
                if (intent.getBooleanExtra(EXTRA_NOTIFY, true)) {
                    val ok = intent.getBooleanExtra(EXTRA_OK, true)
                    val summary = intent.getStringExtra(EXTRA_SUMMARY).orEmpty()
                    NotificationManagerCompat.from(this).notify(
                        AgentNotify.ID_DONE,
                        doneNotification(
                            this,
                            AgentNotify.doneTitle(
                                ok,
                                getString(R.string.notify_done_ok),
                                getString(R.string.notify_done_fail),
                            ),
                            AgentNotify.doneText(sessionTitle, summary),
                        ),
                    )
                }
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START, null -> {
                lastDetail = intent?.getStringExtra(EXTRA_DETAIL).orEmpty()
                startForegroundCompat(runningNotification(this, runningTitleNow(), runningTextNow()))
            }
            else -> Unit
        }
        // 不粘性:任务状态在 ViewModel/数据库里,进程被杀后重启一个空服务没有意义
        return START_NOT_STICKY
    }

    /**
     * 进入前台。[ServiceCompat] 负责按版本传对 `foregroundServiceType`:
     * API 34+ 必须显式声明类型,否则 `startForeground` 直接抛异常(清单里已声明 dataSync)。
     */
    private fun startForegroundCompat(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, AgentNotify.ID_RUNNING, n, type)
    }
}
