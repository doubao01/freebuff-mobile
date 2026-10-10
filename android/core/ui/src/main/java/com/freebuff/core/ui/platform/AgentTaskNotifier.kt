package com.freebuff.core.ui.platform

/**
 * 长任务的前台保活与完成通知(app 模块实现:前台服务 + 通知)。
 *
 * 为什么要有:多轮 agent 任务动辄几十秒到几分钟。用户切走或锁屏后,没有前台服务的进程会被
 * 系统冻结/回收,流式与工具循环随之断掉 —— 只能靠冷启动修复那条「半成品消息」
 * (见 docs/backend-integration.md §3.3.4)。前台服务让任务在后台继续跑完,并在结束时提醒一次。
 *
 * 约定:
 * - [onTaskStarted] **必须在 App 可见时调用** —— Android 12+ 不允许后台启动前台服务,
 *   实现侧对失败做了降级(任务照常跑,只是没有保活),因此调用方不必处理异常,也不要依赖它成功。
 * - [onTaskProgress] 只在「轮次/工具」这种粗粒度上更新,不要逐 token 调用(通知是给人扫一眼的)。
 * - [onTaskFinished] 一定配对调用:它负责撤下常驻通知。
 */
interface AgentTaskNotifier {

    /** 任务开始:挂上常驻通知(低优先级、静音)。 */
    fun onTaskStarted(sessionTitle: String, modelName: String)

    /** 进度更新:一句人类可读的说明(如「第 2 轮 · 调用 联网搜索」)。 */
    fun onTaskProgress(detail: String)

    /**
     * 任务结束。
     * @param ok 是否正常跑完(失败/被中断为 false)
     * @param summary 完成通知的正文来源(通常是回复正文;失败时是错误文案)
     * @param notify 是否再发一条完成通知 —— false 表示只撤下常驻通知
     *   (用户自己按了「停止」时用户就在眼前,再弹一条通知是噪声)
     */
    fun onTaskFinished(sessionId: String, sessionTitle: String, ok: Boolean, summary: String, notify: Boolean = true)
}
