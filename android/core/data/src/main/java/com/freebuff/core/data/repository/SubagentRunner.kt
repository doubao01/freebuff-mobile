package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ChatMessage
import com.freebuff.core.data.network.RetryPolicy
import com.freebuff.core.data.network.toApiError
import com.freebuff.core.data.tools.ToolExecutors
import com.freebuff.core.model.AgentEvent
import com.freebuff.core.model.AgentTool
import com.freebuff.core.model.ChatTarget
import com.freebuff.core.model.ContextPolicy
import com.freebuff.core.model.DefaultTools
import com.freebuff.core.model.OutputSanitizer
import com.freebuff.core.model.ReasoningPlan
import com.freebuff.core.model.Subagent
import com.freebuff.core.model.ToolCallReq
import com.freebuff.core.model.ToolErrors
import com.freebuff.core.model.ToolOutcome
import com.freebuff.core.model.ToolPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 端侧子代理执行引擎(P0:串行单子代理,见 docs/subagent-design.md)。
 *
 * 一个子代理 = **独立上下文 + 只读工具白名单 + 自己的 LLM 循环**:它看不到主会话历史,
 * 只拿到「任务 + 最小背景 + 期望产出」,最多走 [Subagent.MAX_TOOL_ROUNDS] 轮工具,
 * 最后把结论(压缩后)交回主循环当普通 tool_result。
 *
 * 设计要点(与子代理设计文档逐条对应):
 * - **隔离**:上下文只有自己的 system 剧本 + 任务;主会话历史不进入,子代理的中间过程也不落库。
 * - **白名单强制**:工具集由 [Subagent.TOOL_WHITELIST] 硬过滤,且**再按权限降级** ——
 *   CONFIRM / DENY 级工具直接不出现在子代理的工具清单里(后台任务不能阻塞在弹窗上)。
 *   模型硬调白名单外的名字时回错误信封,不执行。
 * - **有界**:总时限 [Subagent.DEADLINE_MS];工具轮数用尽后**去掉 tools 再要一次**,
 *   逼它基于已有结果收敛成结论,而不是继续索要工具。
 * - **失败不静默**:瞬时失败最多重试 [Subagent.MAX_RETRIES] 次(且仅在本轮尚无输出时);
 *   确定性失败与超时都变成可读的失败原因交给主循环,由主模型决定换路。
 * - **不落库**:子代理转录只在内存(卡片上留最终输出),进程死亡即丢弃 —— 与文档 §8 一致。
 *
 * 纯编排,不碰 UI/DB;单测见 SubagentRunnerTest(MockWebServer + analyst/calculator 零外网)。
 */
@Singleton
class SubagentRunner @Inject constructor(
    private val chatRepo: ChatRepository,
    private val tools: ToolExecutors,
) {

    /** 子代理的一次执行结果。 */
    sealed interface Result {
        /** 拿到结论。[rounds] 为实际走过的工具轮数,[toolsUsed] 为调用过的工具名(去重)。 */
        data class Ok(val text: String, val rounds: Int, val toolsUsed: List<String>) : Result

        /** 未能完成(超时/端点失败/空结论),reason 是给主模型与用户看的归因。 */
        data class Fail(val reason: String) : Result
    }

    /** 一轮请求的产物:本轮正文 + 本轮请求的调用 + 流内错误帧(如有)。 */
    private class Round(
        val text: String,
        val calls: List<ToolCallReq>,
        val failure: String = "",
    )

    /**
     * 串行跑一个子代理。
     *
     * @param target 当前对话目标(端点/模型/密钥/请求头/TLS);子代理复用同一个端点
     * @param req 解析后的 spawn_subagent 参数(类型 + 任务 + 背景 + 期望产出)
     * @param permissions 权限查询(默认全放行):非 ALLOW 的工具会被移出子代理工具清单
     * @param reasoning 思维链参数:与主循环一致地送(用户设置决定)
     * @param idleTimeoutMs 流式空闲看门狗阈值(子代理比主循环更激进);<= 0 关闭
     * @param deadlineMs 子代理总时限;<= 0 关闭(虚拟时钟单测必须关,同 [idleTimeoutMs] 的理由:
     *   `runTest` 会在等真实 I/O 时快进虚拟时间,开着时限会变成「请求还没发出去就被判超时」)
     * @param onProgress 正文增量回调(调用方用来做卡片实时尾部);可 suspend,便于顺带写库
     */
    suspend fun run(
        target: ChatTarget,
        req: Subagent.Request,
        permissions: (String) -> ToolPermission = { ToolPermission.ALLOW },
        reasoning: ReasoningPlan? = null,
        idleTimeoutMs: Long = Subagent.IDLE_TIMEOUT_MS,
        deadlineMs: Long = Subagent.DEADLINE_MS,
        onProgress: suspend (String) -> Unit = {},
    ): Result {
        if (!target.isConfigured) return Result.Fail("当前模型未配置端点,子代理无法启动")
        // 权限降级:只把 ALLOW 级工具交给子代理 —— CONFIRM 在子代理内等同 DENY(不弹窗、不执行),
        // 而且干脆不让模型看到它,省掉一轮注定失败的调用。
        val allowed = Subagent.toolsFor(req.type).filter { permissions(it.name) == ToolPermission.ALLOW }
        val body: suspend () -> Result = { loop(target, req, allowed, reasoning, idleTimeoutMs, onProgress) }
        return try {
            if (deadlineMs <= 0) body() else withTimeout(deadlineMs) { body() }
        } catch (e: TimeoutCancellationException) {
            Result.Fail("子代理超时(" + (deadlineMs / 1000) + " 秒内未完成)")
        } catch (e: CancellationException) {
            throw e // 用户停止/父协程取消:向上传播,由主循环统一收尾
        } catch (t: Throwable) {
            Result.Fail(t.toApiError().userMessage)
        }
    }

    /* ---------------- 内部实现 ---------------- */

    private suspend fun loop(
        target: ChatTarget,
        req: Subagent.Request,
        allowed: List<AgentTool>,
        reasoning: ReasoningPlan?,
        idleTimeoutMs: Long,
        onProgress: suspend (String) -> Unit,
    ): Result {
        val toolsJson = if (allowed.isEmpty()) "" else DefaultTools.toJsonArrayString(allowed)
        // 子代理上下文:自己的剧本 + 任务;不含主会话任何历史(隔离)
        val protocol = mutableListOf(
            ChatMessage.text("system", Subagent.systemPrompt(req.type, allowed.map { it.name })),
            ChatMessage.text("user", Subagent.userPrompt(req)),
        )
        var rounds = 0
        var lastText = ""
        var lastFailure = ""
        val used = mutableListOf<String>()
        var requests = 0
        // 请求数上限只是兜底(轮数上限 + 收敛请求 + 余量),正常路径靠 calls 为空退出
        while (requests < Subagent.MAX_REQUESTS) {
            // 工具轮数用尽后去掉 tools:逼子代理基于已有结果收敛成结论
            val useTools = rounds < Subagent.MAX_TOOL_ROUNDS && toolsJson.isNotBlank()
            val round = requestRound(target, protocol, if (useTools) toolsJson else "", reasoning, idleTimeoutMs, onProgress)
            requests++
            if (round.failure.isNotBlank()) lastFailure = round.failure
            if (round.calls.isEmpty()) {
                if (round.text.isNotBlank()) lastText = round.text
                break
            }
            rounds++
            if (round.text.isNotBlank()) lastText = round.text
            protocol += ChatMessage.assistantWithCalls(round.text, round.calls)
            for (c in round.calls) {
                // 白名单外的名字(幻觉/越权)不执行,只回可修正的错误信封(深度固定 1:spawn_subagent 也不在内)
                val outcome = if (allowed.any { it.name == c.name }) {
                    tools.execute(c, allowed)
                } else {
                    ToolOutcome(
                        ToolErrors.report(
                            ToolErrors.Kind.UNKNOWN_TOOL, c.name,
                            "子代理「" + req.type + "」无权调用该工具",
                            "子代理只能用:" + allowed.joinToString("、") { it.name } +
                                "。换用其中之一,或直接用你已有的信息作答。",
                        ),
                        isError = true,
                    )
                }
                used += c.name
                // 结果压缩后回填子代理自己的上下文(与主循环同一道闸门)
                protocol += ChatMessage.toolResult(c.callId, ContextPolicy.compressToolResult(outcome.content))
            }
        }
        if (lastText.isBlank()) {
            return Result.Fail(
                if (lastFailure.isNotBlank()) "端点返回错误帧:" + lastFailure.take(160)
                else "子代理没有给出结论(已完成 " + rounds + " 轮工具调用)",
            )
        }
        return Result.Ok(text = lastText.trim(), rounds = rounds, toolsUsed = used.distinct())
    }

    /**
     * 一次流式请求 + 自动重试(仅瞬时失败且本轮尚无输出时重试,重试不会重复文本)。
     * 流内错误帧([AgentEvent.Failure])不终止本轮,只记下来 —— 若最终一个字都没有,它是失败归因。
     */
    private suspend fun requestRound(
        target: ChatTarget,
        protocol: List<ChatMessage>,
        toolsJson: String,
        reasoning: ReasoningPlan?,
        idleTimeoutMs: Long,
        onProgress: suspend (String) -> Unit,
    ): Round {
        var attempt = 0
        while (true) {
            val sb = StringBuilder()
            var failure = ""
            val calls = mutableListOf<ToolCallReq>()
            try {
                chatRepo.chatStream(
                    endpoint = target.endpoint,
                    model = target.model,
                    apiKey = target.apiKey,
                    headers = target.headers,
                    skipTLS = target.skipTLS,
                    history = protocol.toList(),
                    toolsJson = toolsJson,
                    reasoning = reasoning,
                    idleTimeoutMs = idleTimeoutMs,
                ).collect { ev ->
                    when (ev) {
                        is AgentEvent.Text -> {
                            onProgress(ev.chunk)
                            sb.append(ev.chunk)
                        }
                        is AgentEvent.Calls -> calls += ev.calls
                        is AgentEvent.Failure -> failure = ev.message
                        // 思考增量/子代理事件/收尾事件不参与子代理结论(结论只看正文)
                        else -> Unit
                    }
                }
                return Round(OutputSanitizer.clean(sb.toString()), calls, failure)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                attempt++
                val canRetry = sb.isEmpty() && RetryPolicy.isRetryable(t) && attempt <= Subagent.MAX_RETRIES
                if (!canRetry) throw t
                RetryPolicy.waitBackoff(attempt) { false }
            }
        }
    }
}
