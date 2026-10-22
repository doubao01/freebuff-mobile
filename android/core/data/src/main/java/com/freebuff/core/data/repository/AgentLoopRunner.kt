package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ChatMessage
import com.freebuff.core.model.AgentLoop
import com.freebuff.core.model.ContextPolicy
import com.freebuff.core.model.MsgSteps
import com.freebuff.core.model.ToolCallReq
import com.freebuff.core.model.ToolErrors
import com.freebuff.core.model.ToolOutcome
import com.freebuff.core.model.ToolRepeatTracker
import com.freebuff.core.model.toolDisplayName
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一轮的准备工作:请求消息 + 本轮携带的工具定义 + 可用工具名。
 *
 * 消息与 toolsJson 必须来自**同一次判断**:系统提示里的「工具使用约定」要与这一轮真的带了
 * tools 一致(教它用没有的东西是最贵的错误),所以两者一起返回。
 */
data class RoundSetup(
    val messages: List<ChatMessage>,
    val toolsJson: String,
    val toolNames: List<String>,
) {
    /**
     * 本轮是否真的附带了工具定义。
     * 空串 = 不带(用户关了工具开关,或本会话被端点拒过工具定义);`"[]"` 也算带。
     */
    val toolsEnabled: Boolean get() = toolsJson.isNotBlank()
}

/**
 * agent 循环的宿主:循环只负责**控制流与循环状态**,一切副作用(建上下文、跑流、执行工具、
 * 写卡片、发通知)都从这里回调出去。
 *
 * 为什么要这层:循环的骨架里藏着一堆踩过坑才定下来的规则 —— 停止要在三个边界生效、工具批次
 * 中途停下就不再执行剩余调用、连败到熔断次数不再执行、完全重复的调用复用结果、模型重放时
 * 抹掉重复正文。这些过去都长在 `ChatViewModel` 的循环体里(1291 行、依赖 Android Context),
 * 没法被任何单测覆盖;抽成宿主接口后,循环本身可以在 JVM 单测里用假宿主驱动
 * (见 `AgentLoopRunnerTest`)。
 */
interface AgentLoopHost {

    /** 用户是否按下了停止(循环在每个轮次边界查它)。 */
    fun stopRequested(): Boolean

    /**
     * 诊断轨迹(「第 N 轮开始」「停止在边界」「nudge #n」「重复调用」以及**每次工具调用的原始参数**)。
     *
     * 格式由循环拼好后原样交出来:端上的离线回归脚本按 logcat 里
     * `ChatViewModel: tool req sid=… round=… name=… args=…` 还原模型的首调参数与幻觉工具名,
     * 实现侧只负责按原样打日志,不要在中间改写。
     */
    fun trace(line: String)

    /** 组装本轮的上下文与工具定义(记忆注入、预算裁剪、摘要复用都在实现侧)。 */
    suspend fun prepareRound(): RoundSetup

    /** 跑一轮流式请求:文本增量写进 [textSink](循环据此做「模型重放同一段话」的去重)。 */
    suspend fun streamRound(
        history: List<ChatMessage>,
        toolsJson: String,
        textSink: StringBuilder,
    ): List<ToolCallReq>

    /**
     * 执行一次工具调用(权限分级、确认挂起、未知工具、子代理/记忆都在实现侧)。
     * @param attempt 该工具在本轮对话里连续第几次失败(错误信封据此升级为「换路」)
     */
    suspend fun dispatch(call: ToolCallReq, attempt: Int): ToolOutcome

    /** 第 [round] 轮开始(状态牌与通知):让用户看到在第几轮、在做什么。 */
    suspend fun onRoundStart(round: Int)

    /** 第 [round] 轮的工具批次跑完([names] 是本批调用过的工具名)。 */
    suspend fun onToolsDone(round: Int, names: List<String>)

    /** 一次工具调用的卡片收尾([reused] = 结果来自复用,没有真的执行)。 */
    suspend fun onToolCard(callId: String, outcome: ToolOutcome, reused: Boolean)

    /** 往可见正文追加内容(轮次上限/重复打转的说明,附带最近一轮结果摘要)。 */
    suspend fun onAppendText(text: String)

    /** 抹掉刚追加进去的重复正文(模型把同一段话又吐了一遍)。 */
    suspend fun onDropTrailingText(text: String)
}

/**
 * agent 循环本体(单入口 [run])。
 *
 * 每轮:`停? → 建上下文 → 跑一轮流 → (停?) → 串行执行工具 → (停?) → 延续判定`。
 * 延续策略本身是纯函数 [AgentLoop.decide](轮次上限/提醒/重复打转),这里只负责把它**落实**:
 * 该提醒时往协议里插提醒消息、该停时把说明追加到正文,并维护循环自己的状态
 * (连续失败计数、重复调用追踪、连续重复轮数、上一轮正文)。
 *
 * 有界性由三处保证:轮次上限([AgentLoop.MAX_TOOL_ROUNDS])、重复打转上限
 * ([AgentLoop.MAX_REPEAT_ROUNDS])、以及用户停止(每个边界都查,不依赖取消被及时观察到)。
 */
@Singleton
class AgentLoopRunner @Inject constructor() {

    /**
     * 一次循环的结果。
     * @param rounds 实际执行的工具轮数(诊断日志用)
     * @param stoppedByUser 是否被用户停止中断(调用方据此给消息补「已停止」标注、不再发完成通知)
     */
    data class Result(val rounds: Int, val stoppedByUser: Boolean)

    /**
     * 跑一轮完整的 agent 循环(一次用户输入 = 一次调用)。
     *
     * @param sessionId 只用于诊断轨迹:轨迹沿用既有格式,把会话短 id 带在行里便于和
     *   `op begin/op finish` 对齐(见 [AgentLoopHost.trace])
     */
    suspend fun run(sessionId: String, host: AgentLoopHost): Result {
        val sid = sessionId.takeLast(6)
        // 循环状态:每次调用都是新的 —— 连败熔断与重复追踪都不跨消息(下一条消息自动恢复)
        val repeats = ToolRepeatTracker()
        val failStreak = HashMap<String, Int>()
        val protocol = mutableListOf<ChatMessage>() // 跨轮累积:assistant(tool_calls)+tool 结果(严格协议)
        var toolRounds = 0
        var nudges = 0
        var repeatRounds = 0 // 连续「整轮都在重复调用」的轮数
        var lastRoundText = "" // 上一轮已写进正文的文本(用于去掉重复追加)
        var stoppedByUser = false

        while (true) {
            // 轮次边界:用户已停止就不再开新的一轮(取消是协作式的,可能被工具层的 catch 吞掉,这里是兜底)
            if (host.stopRequested()) {
                stoppedByUser = true
                host.trace("round stop at boundary sid=$sid round=${toolRounds + 1}")
                break
            }
            host.trace("round ${toolRounds + 1} start sid=$sid")
            host.onRoundStart(toolRounds + 1)

            val setup = host.prepareRound()
            val roundText = StringBuilder() // 本轮流式输出的文本(回传时作为 assistant content)
            val calls = host.streamRound(setup.messages + protocol, setup.toolsJson, roundText)

            // 本轮被停止(取消已生效,或退避等待被中止后的常规出口)。
            // 两条收尾路径(这里与宿主侧的 stop)执行顺序不定,所以两边都要带「用户停止」,
            // 否则后跑的那条会把已写好的停止标注覆盖成空正文。
            if (host.stopRequested()) {
                stoppedByUser = true
                host.trace("round stopped-by-user sid=$sid round=${toolRounds + 1}")
                break
            }

            val completed = calls.any { it.name in AgentLoop.END_TOOLS }
            var repeatedThisRound = 0
            val roundDigest = mutableListOf<Pair<String, String>>() // 本轮的「工具 → 结果首行」
            if (calls.isNotEmpty()) {
                toolRounds++
                protocol += ChatMessage.assistantWithCalls(roundText.toString(), calls)
                val calledNames = mutableListOf<String>()
                for (c in calls) {
                    // 用户在上一批工具执行期间按了停止:剩余调用不再执行(工具可能有副作用)
                    if (host.stopRequested()) {
                        stoppedByUser = true
                        host.trace("tool batch aborted sid=$sid round=$toolRounds")
                        break
                    }
                    // 调用轨迹:记工具名 + 原始参数 JSON。卡片里存的是给人看的参数摘要
                    // (query/url/path 这类只剩值、丢了键名),回归脚本读卡片分不清
                    // 「参数名写错」和「摘要只留了值」,所以这里留一行原样可解析的记录。
                    // 未知工具名、被熔断的调用、重复调用也都会记下来,统计才可信。
                    host.trace(
                        "tool req sid=$sid round=$toolRounds " +
                            "name=${c.name} args=${c.argsJson.take(200)}",
                    )
                    val attempt = (failStreak[c.name] ?: 0) + 1
                    val sig = repeats.signature(c)
                    val cached = repeats.cachedOutput(sig)
                    val outcome = when {
                        cached != null -> {
                            // 完全相同的调用:复用上次结果,不重新执行(某些模型会把同一组工具整轮重放)。
                            // 结果虽已复用,仍要告诉模型「别重发」——模型重放是本项目实测过的主要循环之一。
                            repeatedThisRound++
                            ToolOutcome(ToolErrors.duplicateNote(c.name, cached))
                        }
                        // 熔断:劝过换路(第 2 次失败)之后模型还重发,从第 3 次起端上直接拒绝执行 ——
                        // 把「换路」从建议变成事实,别让循环把轮次耗在同一个工具上。
                        // 收工类工具(end_turn/task_completed)豁免:它们是循环唯一的出口,
                        // 熔断它们反而会锁死整个循环。
                        attempt >= ToolErrors.BREAK_AT && c.name !in AgentLoop.END_TOOLS ->
                            ToolOutcome(
                                ToolErrors.breakerOpen(c.name, setup.toolNames, attempt),
                                isError = true,
                            )
                        else -> host.dispatch(c, attempt).also { r ->
                            if (r.isError) failStreak[c.name] = attempt else failStreak.remove(c.name)
                            repeats.remember(sig, r.content)
                        }
                    }
                    host.onToolCard(c.callId, outcome, reused = cached != null)
                    protocol += ChatMessage.toolResult(
                        c.callId,
                        ContextPolicy.compressToolResult(outcome.content),
                    )
                    calledNames += c.name
                    roundDigest += toolDisplayName(c.name) to outcome.content
                }
                // 已停止:不再写状态牌、不再判定延续、不再开新轮,直接走「用户停止」收尾
                if (stoppedByUser) break
                host.onToolsDone(toolRounds, calledNames)
                if (repeatRounds > 0 || repeatedThisRound > 0) {
                    host.trace("repeat sid=$sid round=$toolRounds dup=$repeatedThisRound/${calls.size} consecutive=$repeatRounds")
                }
            }
            // 整轮都是重复调用才算「原地打转」;换参数/换工具都算新进展
            repeatRounds = if (calls.isNotEmpty() && repeatedThisRound == calls.size) repeatRounds + 1 else 0
            // 模型重放时会把同一段话再吐一遍:去掉刚追加进去的重复段落
            val roundStr = roundText.toString()
            if (roundStr.isNotBlank()) {
                if (normText(roundStr) == normText(lastRoundText)) {
                    host.onDropTrailingText(roundStr)
                } else {
                    lastRoundText = roundStr
                }
            }
            when (val decision = AgentLoop.decide(
                toolRounds = toolRounds,
                toolCalls = calls.size,
                toolsEnabled = setup.toolsEnabled,
                nudgesUsed = nudges,
                completed = completed,
                repeatRounds = repeatRounds,
            )) {
                AgentLoop.Decision.Continue -> Unit
                AgentLoop.Decision.Stop -> break
                is AgentLoop.Decision.StopWithNote -> {
                    // 模型重放时常常一个字也不说就停了:把本轮的执行结果附在提示里,
                    // 否则用户看到的「回复」就是空卡片 + 一句停因(实测存在)
                    host.onAppendText("\n\n" + decision.note + MsgSteps.digest(roundDigest))
                    break
                }
                is AgentLoop.Decision.Nudge -> {
                    // 只进协议消息,不进正文:下一轮模型会看到「继续推进」的要求
                    nudges++
                    host.trace("nudge #$nudges sid=$sid")
                    protocol += ChatMessage.text(AgentLoop.REMINDER_ROLE, decision.message)
                }
            }
        }
        return Result(rounds = toolRounds, stoppedByUser = stoppedByUser)
    }

    /** 比对正文是否「同一段话」:忽略空白差异。 */
    private fun normText(s: String): String = s.trim().replace(WHITESPACE_RE, " ")

    private companion object {
        val WHITESPACE_RE = Regex("\\s+")
    }
}
