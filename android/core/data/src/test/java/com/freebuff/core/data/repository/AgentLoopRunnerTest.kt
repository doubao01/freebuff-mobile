package com.freebuff.core.data.repository

import com.freebuff.core.data.network.ChatMessage
import com.freebuff.core.model.AgentLoop
import com.freebuff.core.model.ToolCallReq
import com.freebuff.core.model.ToolErrors
import com.freebuff.core.model.ToolOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * agent 循环的控制流契约(见 docs/agent-architecture.md)。
 *
 * 这些规则过去长在 `ChatViewModel` 的循环体里,只能靠真机回归:轮次上限、动过工具却只回文本时的
 * 提醒、同一工具连败后的熔断、完全重复调用的结果复用、以及用户停止在三个边界上的生效。
 * 抽成 [AgentLoopRunner] + [AgentLoopHost] 后,用一个假宿主就能把每条规则钉住。
 *
 * 假宿主只记录循环要它做的事(建上下文/跑流/执行工具/写卡片/追加正文),不碰 Android、不碰网络。
 */
class AgentLoopRunnerTest {

    private val runner = AgentLoopRunner()

    // ---------------------------------------------------------------- 轮次上限

    @Test
    fun `工具轮次到达上限后停止, 并把停因与最近一轮结果写进正文`() = runTest {
        // 每轮都换参数:不是重复调用,唯一能停下来的就是轮次上限
        val host = FakeHost(script = { r ->
            FakeRound(calls = listOf(call("call-$r", "web_search", """{"query":"q$r"}""")))
        })

        val result = runner.run("session-1", host)

        assertEquals(AgentLoop.MAX_TOOL_ROUNDS, result.rounds)
        assertFalse("不是用户停止", result.stoppedByUser)
        assertEquals(AgentLoop.MAX_TOOL_ROUNDS, host.dispatched.size)
        assertEquals("不再开下一轮", AgentLoop.MAX_TOOL_ROUNDS, host.prepared)

        val note = host.appended.single()
        assertTrue("要说明是轮次上限", note.contains("上限"))
        assertTrue("并给出继续的出路", note.contains("继续"))
        assertTrue("还附上最近一轮的执行结果", note.contains("最近一轮的执行结果"))
        assertTrue("结果里要有这一轮的输出", note.contains("ok"))
    }

    // ---------------------------------------------------------------- 提醒

    @Test
    fun `动过工具却只回文本时先提醒, 提醒用满后交给用户`() = runTest {
        val host = FakeHost(script = { r ->
            when (r) {
                1 -> FakeRound(calls = listOf(call("call-1", "web_search", """{"query":"a"}""")))
                // 2..N:进度播报(只说不动),应当被提醒继续
                else -> FakeRound(text = "我已经查到了,接下来准备整理一下(第 $r 轮)")
            }
        })

        val result = runner.run("session-1", host)

        assertEquals("工具轮数只算真的调了工具的那一轮", 1, result.rounds)
        assertFalse(result.stoppedByUser)
        // 1 次工具轮 + MAX_CONTINUE_NUDGES 次提醒 + 最后一次判定停止
        assertEquals(1 + AgentLoop.MAX_CONTINUE_NUDGES + 1, host.prepared)

        // 提醒进协议、不进可见正文:用户不该在气泡里看到系统提醒
        assertTrue("提醒不写进正文", host.appended.isEmpty())
        val lastRequest = host.histories.last()
        val nudges = lastRequest.filter { it.role == AgentLoop.REMINDER_ROLE && it.content == AgentLoop.NUDGE_TEXT }
        assertEquals(AgentLoop.MAX_CONTINUE_NUDGES, nudges.size)
        assertTrue("提醒要带系统前缀", nudges.all { it.content.startsWith(AgentLoop.REMINDER_PREFIX) })

        // 提醒是 user 回合(Cline/OpenHands/Codex CLI 同款:中途插 system 各家网关处理不一)
        assertEquals("user", AgentLoop.REMINDER_ROLE)
        assertTrue(host.traces.any { it.contains("nudge #1") })
    }

    // ---------------------------------------------------------------- 熔断

    @Test
    fun `同一工具连败到熔断次数后不再执行, 只回拒绝信封`() = runTest {
        val host = FakeHost(
            script = { r ->
                if (r <= 4) FakeRound(calls = listOf(call("call-$r", "read_file", """{"path":"p$r"}""")))
                else FakeRound(calls = listOf(call("call-done", AgentLoop.COMPLETION_TOOL, """{"summary":"放弃读取"}""")))
            },
            // 每次都失败:第 1 次鼓励按建议重试,第 2 次劝换路,第 3 次起端上熔断
            onDispatch = { _, _ -> ToolOutcome("读取失败:文件不存在", isError = true) },
        )

        val result = runner.run("session-1", host)

        // 只真正执行了前两次;第 3、4 次被熔断(收工工具另算)
        assertEquals(2, host.dispatched.count { it.name == "read_file" })
        assertEquals(listOf(1, 2), host.attempts.take(2))

        val breaker = host.cards[2]
        assertTrue("熔断是错误结果", breaker.outcome.isError)
        assertTrue("要说清没有执行", breaker.outcome.content.contains("暂停执行"))
        assertTrue("并给出换路方向", breaker.outcome.content.contains("不要再调用 read_file"))
        assertFalse("熔断不是结果复用", breaker.reused)

        // 熔断后循环仍在跑:模型还能收工,不会锁死
        assertFalse(result.stoppedByUser)
        assertEquals("最后一轮调用完成工具", AgentLoop.COMPLETION_TOOL, host.dispatched.last().name)
    }

    @Test
    fun `工具失败后成功一次会清零连败计数, 不会误触发熔断`() = runTest {
        val host = FakeHost(
            script = { r ->
                if (r <= 4) FakeRound(calls = listOf(call("call-$r", "read_file", """{"path":"p$r"}""")))
                else FakeRound(calls = listOf(call("call-done", AgentLoop.COMPLETION_TOOL, """{"summary":"ok"}""")))
            },
            onDispatch = { _, attempt ->
                // 每次失败两次就成功一次:连败永远到不了 BREAK_AT
                if (attempt == ToolErrors.BREAK_AT - 1) ToolOutcome("读到了") else ToolOutcome("读取失败", isError = true)
            },
        )

        runner.run("session-1", host)

        assertEquals("四次调用全部真的执行了", 4, host.dispatched.count { it.name == "read_file" })
        assertTrue(host.cards.none { it.outcome.content.contains("暂停执行") })
    }

    // ---------------------------------------------------------------- 去重

    @Test
    fun `完全相同的调用复用上次结果, 不再重新执行`() = runTest {
        val host = FakeHost(
            script = { r ->
                when (r) {
                    1 -> FakeRound(calls = listOf(call("call-1", "web_search", """{"query":"kotlin"}""")))
                    // 只差空白:归一化后是同一个签名
                    2 -> FakeRound(calls = listOf(call("call-2", "web_search", """{ "query" : "kotlin" }""")))
                    else -> FakeRound(calls = listOf(call("call-done", AgentLoop.COMPLETION_TOOL, """{"summary":"ok"}""")))
                }
            },
            onDispatch = { _, _ -> ToolOutcome("搜索结果:kotlin 2.0 已发布") },
        )

        runner.run("session-1", host)

        assertEquals("重复调用只执行一次", 1, host.dispatched.count { it.name == "web_search" })
        val reused = host.cards[1]
        assertTrue("卡片要标成复用", reused.reused)
        assertTrue("并告诉模型结果来自复用", reused.outcome.content.contains("复用上次结果"))
        assertTrue("复用结果里带上上次的内容", reused.outcome.content.contains("kotlin 2.0 已发布"))

        // 复用也要回填协议:每个 tool_call 都必须有对应的 tool 结果(严格端点否则 400)
        val lastRequest = host.histories.last()
        val toolResults = lastRequest.filter { it.role == "tool" }
        assertTrue("call-2 也有结果", toolResults.any { it.toolCallId == "call-2" })
    }

    @Test
    fun `字符串内部的空白不算差异, 字符串外美化换行算同一调用`() = runTest {
        val a = ToolCallReq("c1", "calculator", """{"expression":"1 + 1"}""")
        val b = ToolCallReq("c2", "calculator", """ { "expression" : "1 + 1" } """)
        val c = ToolCallReq("c3", "calculator", """{"expression":"1+1"}""")

        val host = FakeHost(
            script = { r ->
                when (r) {
                    1 -> FakeRound(calls = listOf(a))
                    2 -> FakeRound(calls = listOf(b))
                    3 -> FakeRound(calls = listOf(c))
                    else -> FakeRound(calls = listOf(call("d", AgentLoop.COMPLETION_TOOL, """{"summary":"ok"}""")))
                }
            },
            onDispatch = { _, _ -> ToolOutcome("= 2") },
        )

        runner.run("session-1", host)

        // a 与 b 同签名(只差字符串外的空白)→ 复用;c 的字符串内容不同(1+1 ≠ 1 + 1 作为表达式其实等价,
        // 但去重必须保守:字符串里的空白可能有意义)→ 真的执行
        assertEquals("a 执行、b 复用、c 执行", 2, host.dispatched.count { it.name == "calculator" })
        assertTrue(host.cards[1].reused)
        assertFalse(host.cards[2].reused)
    }

    @Test
    fun `整轮重复调用先提醒一次, 再重复就停, 不烧到轮次上限`() = runTest {
        val host = FakeHost(script = {
            // 模型/中转把同一组工具整轮重放
            FakeRound(calls = listOf(call("call-x", "web_search", """{"query":"a"}""")))
        })

        val result = runner.run("session-1", host)

        assertEquals("同一签名只执行一次", 1, host.dispatched.size)
        // 首轮一次 + MAX_REPEAT_ROUNDS 轮重复:重复两轮就停,而不是烧到 30 轮
        assertEquals(AgentLoop.MAX_REPEAT_ROUNDS + 1, result.rounds)
        val note = host.appended.single()
        assertTrue("要说明是重复调用", note.contains("重复调用"))
        assertTrue(host.histories.last().any { it.content == AgentLoop.REPEAT_NUDGE_TEXT })
    }

    @Test
    fun `模型重放同一段正文时把它从消息里删掉`() = runTest {
        val host = FakeHost(script = { r ->
            when (r) {
                1 -> FakeRound(text = "正在分析…", calls = listOf(call("call-1", "web_search", """{"query":"a"}""")))
                // 换了参数(不算重复调用),却把同一段话又吐了一遍
                2 -> FakeRound(text = " 正在分析… ", calls = listOf(call("call-2", "web_search", """{"query":"b"}""")))
                else -> FakeRound(calls = listOf(call("call-done", AgentLoop.COMPLETION_TOOL, """{"summary":"ok"}""")))
            }
        })

        runner.run("session-1", host)

        assertEquals(listOf(" 正在分析… "), host.dropped)
    }

    // ---------------------------------------------------------------- 停止

    @Test
    fun `用户停止后不再开新的一轮`() = runTest {
        val host = FakeHost(script = { FakeRound(calls = listOf(call("call-1", "web_search", """{"query":"a"}"""))) })
        host.stopped = true

        val result = runner.run("session-1", host)

        assertTrue(result.stoppedByUser)
        assertEquals(0, result.rounds)
        assertEquals("一个请求都没发", 0, host.prepared)
        assertTrue(host.dispatched.isEmpty())
        assertTrue("停止时不写停因正文", host.appended.isEmpty())
        assertTrue(host.traces.any { it.contains("stop at boundary") })
    }

    @Test
    fun `工具批次执行中途停止, 剩余调用不再执行`() = runTest {
        val host = FakeHost(
            script = {
                FakeRound(
                    calls = listOf(
                        call("call-1", "read_file", """{"path":"a"}"""),
                        call("call-2", "read_file", """{"path":"b"}"""),
                    ),
                )
            },
            onDispatch = { _, _ -> ToolOutcome("内容") },
        )
        // 第一个工具执行期间用户按下停止
        host.stopAfterDispatch = 1

        val result = runner.run("session-1", host)

        assertTrue(result.stoppedByUser)
        assertEquals("第二个调用没执行(工具可能有副作用)", 1, host.dispatched.size)
        assertTrue("不再写状态牌/判定延续", host.toolsDone.isEmpty())
        assertTrue(host.traces.any { it.contains("tool batch aborted") })
    }

    @Test
    fun `流式回合结束后才观察到停止也不开新轮`() = runTest {
        val host = FakeHost(script = {
            // 模型这一轮流式输出期间用户停止:返回空调用,循环在「本轮结束」边界看到标记
            FakeRound(text = "被打断的半句话")
        })
        host.stopDuringStream = true

        val result = runner.run("session-1", host)

        assertTrue(result.stoppedByUser)
        assertEquals(1, host.prepared)
        assertTrue("没有调用就没有轮次", result.rounds == 0)
        assertTrue(host.toolsDone.isEmpty())
    }

    // ---------------------------------------------------------------- 收工与不带工具

    @Test
    fun `模型声明完成后立即收工`() = runTest {
        val host = FakeHost(script = {
            FakeRound(
                text = "已经完成",
                calls = listOf(call("call-1", AgentLoop.COMPLETION_TOOL, """{"summary":"ok"}""")),
            )
        })

        val result = runner.run("session-1", host)

        assertFalse(result.stoppedByUser)
        assertEquals(1, result.rounds)
        assertEquals(1, host.prepared)
        assertEquals("收工工具本身仍然执行", AgentLoop.COMPLETION_TOOL, host.dispatched.single().name)
    }

    @Test
    fun `本轮没带工具定义时不继续(端点拒绝工具后会空转到上限)`() = runTest {
        // 模型仍幻觉出工具调用,但请求里没有 tools:继续只会一直重复
        val host = FakeHost(
            script = { FakeRound(calls = listOf(call("call-1", "web_search", """{"query":"a"}"""))) },
            toolsJson = "",
        )

        val result = runner.run("session-1", host)

        assertEquals(1, host.prepared)
        assertFalse(result.stoppedByUser)
    }

    // ---------------------------------------------------------------- 假宿主

    /** 一轮的模型输出:文本增量 + 结构化调用。 */
    private data class FakeRound(val text: String = "", val calls: List<ToolCallReq> = emptyList())

    private data class Card(val callId: String, val outcome: ToolOutcome, val reused: Boolean)

    /**
     * 只记录、不实现的宿主:把循环的每个动作落进可断言的表里。
     *
     * @param script 第 N 轮(从 1 开始)的模型输出
     * @param onDispatch 第 N 次工具执行的结果
     * @param toolsJson 本轮携带的工具定义;空串表示「端点拒了工具」
     */
    private class FakeHost(
        private val script: (round: Int) -> FakeRound,
        private val onDispatch: (ToolCallReq, Int) -> ToolOutcome = { _, _ -> ToolOutcome("ok") },
        private val toolsJson: String = "[]",
    ) : AgentLoopHost {

        /** 用户是否已按停止(测试可随时置 true)。 */
        var stopped = false

        /** 第 N 次工具执行完后按下停止。 */
        var stopAfterDispatch: Int? = null

        /** 本轮流式输出期间按下停止。 */
        var stopDuringStream = false

        var prepared = 0
        val histories = mutableListOf<List<ChatMessage>>()
        val dispatched = mutableListOf<ToolCallReq>()
        val attempts = mutableListOf<Int>()
        val cards = mutableListOf<Card>()
        val appended = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        val roundStarts = mutableListOf<Int>()
        val toolsDone = mutableListOf<Pair<Int, List<String>>>()
        val traces = mutableListOf<String>()

        override fun stopRequested(): Boolean = stopped

        override fun trace(line: String) {
            traces += line
        }

        override suspend fun prepareRound(): RoundSetup {
            prepared++
            return RoundSetup(
                messages = emptyList(),
                toolsJson = toolsJson,
                toolNames = listOf("web_search", "read_file", "calculator"),
            )
        }

        override suspend fun streamRound(
            history: List<ChatMessage>,
            toolsJson: String,
            textSink: StringBuilder,
        ): List<ToolCallReq> {
            histories += history
            val round = script(prepared)
            textSink.append(round.text)
            if (stopDuringStream) stopped = true
            return round.calls
        }

        override suspend fun dispatch(call: ToolCallReq, attempt: Int): ToolOutcome {
            dispatched += call
            attempts += attempt
            val outcome = onDispatch(call, attempt)
            if (stopAfterDispatch != null && dispatched.size == stopAfterDispatch) stopped = true
            return outcome
        }

        override suspend fun onRoundStart(round: Int) {
            roundStarts += round
        }

        override suspend fun onToolsDone(round: Int, names: List<String>) {
            toolsDone += round to names
        }

        override suspend fun onToolCard(callId: String, outcome: ToolOutcome, reused: Boolean) {
            cards += Card(callId, outcome, reused)
        }

        override suspend fun onAppendText(text: String) {
            appended += text
        }

        override suspend fun onDropTrailingText(text: String) {
            dropped += text
        }
    }

    private fun call(callId: String, name: String, argsJson: String) = ToolCallReq(callId, name, argsJson)
}
