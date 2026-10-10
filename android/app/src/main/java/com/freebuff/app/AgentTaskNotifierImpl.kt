package com.freebuff.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.freebuff.core.ui.platform.AgentTaskNotifier
import com.freebuff.mobile.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * [AgentTaskNotifier] 的实现:把「任务开始/进度/结束」翻译成发给 [AgentForegroundService] 的 intent。
 *
 * 全部经 `startForegroundService` 发送(而不区分 startService):服务侧每条路径都会先
 * `startForeground`,因此无论这一条是「首次拉起」还是「更新/收尾」都满足系统的 5 秒约束。
 *
 * 后台启动前台服务在 Android 12+ 会被拒(`ForegroundServiceStartNotAllowedException`):
 * 这里吞掉异常并降级 —— 任务本身照常跑完,只是失去后台保活。宁可不提醒,也不要让通知把对话搞崩。
 */
@Singleton
class AgentTaskNotifierImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTaskNotifier {

    override fun onTaskStarted(sessionTitle: String, modelName: String) {
        AgentForegroundService.ensureChannels(context)
        send(
            action = AgentForegroundService.ACTION_START,
            title = sessionTitle,
            detail = if (modelName.isBlank()) "" else context.getString(R.string.notify_connecting_model, modelName),
        )
    }

    override fun onTaskProgress(detail: String) {
        send(action = AgentForegroundService.ACTION_PROGRESS, detail = detail)
    }

    override fun onTaskFinished(
        sessionId: String,
        sessionTitle: String,
        ok: Boolean,
        summary: String,
        notify: Boolean,
    ) {
        val i = baseIntent(AgentForegroundService.ACTION_FINISH)
            .putExtra(AgentForegroundService.EXTRA_TITLE, sessionTitle)
            .putExtra(AgentForegroundService.EXTRA_SUMMARY, summary.take(400))
            .putExtra(AgentForegroundService.EXTRA_OK, ok)
            .putExtra(AgentForegroundService.EXTRA_NOTIFY, notify)
            .putExtra("sessionId", sessionId)
        deliver(i)
    }

    /* ---------------- 内部实现 ---------------- */

    private fun send(action: String, title: String? = null, detail: String? = null) {
        val i = baseIntent(action)
        title?.let { i.putExtra(AgentForegroundService.EXTRA_TITLE, it) }
        detail?.let { i.putExtra(AgentForegroundService.EXTRA_DETAIL, it) }
        deliver(i)
    }

    private fun baseIntent(action: String) =
        Intent(context, AgentForegroundService::class.java).setAction(action)

    private fun deliver(i: Intent) {
        runCatching { ContextCompat.startForegroundService(context, i) }
            .onFailure { e ->
                // 不改行为、不静默:记一行日志,便于「为什么没保活/没通知」的现场排查
                Timber.w(e, "startForegroundService rejected (task keeps running without keep-alive)")
            }
    }
}
