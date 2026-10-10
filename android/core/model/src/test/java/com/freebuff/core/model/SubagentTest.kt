package com.freebuff.core.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 子代理协议层:参数解析、白名单、剧本。 */
class SubagentTest {

    @Test
    fun `合法请求解析成功`() {
        val req = Subagent.parseRequest(
            """{"agent_type":"researcher","task":"查 Kotlin 2.0 要点","context":"用户在做升级评估","expected_output":"≤200 字结论"}""",
        )!!
        assertEquals("researcher", req.type)
        assertEquals("查 Kotlin 2.0 要点", req.task)
        assertEquals("用户在做升级评估", req.context)
        assertEquals("≤200 字结论", req.expectedOutput)
    }

    @Test
    fun `未知类型或空 task 返回 null 坏 JSON 也返回 null`() {
        assertNull(Subagent.parseRequest("""{"agent_type":"hacker","task":"x"}"""))
        assertNull(Subagent.parseRequest("""{"agent_type":"researcher","task":""}"""))
        assertNull(Subagent.parseRequest("""{"agent_type":"researcher"}"""))
        assertNull(Subagent.parseRequest("not json"))
    }

    @Test
    fun `context 与 expected_output 可省略`() {
        val req = Subagent.parseRequest("""{"agent_type":"analyst","task":"算 1+2"}""")!!
        assertEquals("", req.context)
        assertEquals("", req.expectedOutput)
    }

    @Test
    fun `白名单均为只读且不含编排工具`() {
        Subagent.TOOL_WHITELIST.forEach { (type, tools) ->
            assertTrue(Subagent.isValidType(type))
            tools.forEach { t ->
                assertTrue("白名单只允许只读工具:$t", t in setOf("web_search", "web_fetch", "github_get_file", "github_get_readme", "calculator"))
            }
            assertFalse("深度固定 1:子代理不能再 spawn", Subagent.TOOL_NAME in tools)
        }
        assertEquals(setOf("web_search", "web_fetch"), Subagent.TOOL_WHITELIST["researcher"])
    }

    @Test
    fun `toolsFor 按类型过滤且未知类型为空`() {
        val tools = Subagent.toolsFor("code_reader")
        assertEquals(setOf("github_get_file", "github_get_readme"), tools.map { it.name }.toSet())
        assertTrue(Subagent.toolsFor("nope").isEmpty())
    }

    @Test
    fun `剧本包含约束与轮次上限`() {
        val p = Subagent.systemPrompt("researcher")
        assertTrue(p.contains("researcher"))
        assertTrue(p.contains("web_search"))
        assertTrue(p.contains("不得编造"))
        assertTrue(p.contains(Subagent.MAX_TOOL_ROUNDS.toString()))
    }

    @Test
    fun `userPrompt 拼接任务背景与期望产出`() {
        val req = Subagent.Request("analyst", "算 1+2", "背景 A", "只给结果")
        val p = Subagent.userPrompt(req)
        assertTrue(p.contains("算 1+2"))
        assertTrue(p.contains("背景 A"))
        assertTrue(p.contains("只给结果"))
    }

    @Test
    fun `结论回填带来源标签`() {
        val r = Subagent.formatResult("researcher", 2, listOf("web_search", "web_fetch"), "  结论 A  ")
        assertTrue(r.startsWith("[子代理 researcher 完成"))
        assertTrue(r.contains("2 轮工具"))
        assertTrue(r.contains("web_search、web_fetch"))
        assertTrue("结论要原样附上(仅去首尾空白)", r.endsWith("结论 A"))
        // 没调工具也说得通(0 轮)
        val plain = Subagent.formatResult("analyst", 0, emptyList(), "直接给出结论")
        assertFalse(plain.contains("轮工具"))
        assertTrue(plain.endsWith("直接给出结论"))
    }

    @Test
    fun `失败回填给主模型的替代路径`() {
        val f = Subagent.formatFailure("researcher", "子代理超时(120 秒内未完成)")
        assertTrue(f.contains("未能完成"))
        assertTrue(f.contains("超时"))
        assertTrue("要给出替代路径,避免原地重派", f.contains("web_search"))
    }

    @Test
    fun `剧本按传入的工具清单说话`() {
        assertTrue(Subagent.systemPrompt("researcher").contains("web_fetch"))
        val trimmed = Subagent.systemPrompt("researcher", listOf("web_search"))
        assertTrue(trimmed.contains("web_search"))
        assertFalse("被权限摘掉的工具不该写进剧本", trimmed.contains("web_fetch"))
    }

    @Test
    fun `请求数上限给收敛留余量`() {
        assertEquals(Subagent.MAX_TOOL_ROUNDS + 2, Subagent.MAX_REQUESTS)
        assertTrue(Subagent.MAX_REQUESTS > Subagent.MAX_TOOL_ROUNDS + 1)
    }

    @Test
    fun `错误文案含原始参数截断`() {
        val msg = Subagent.invalidArgsMessage("""{"agent_type":"x"}""")
        assertTrue(msg.contains("agent_type"))
        assertTrue(msg.contains("researcher"))
    }

    @Test
    fun `spawn_subagent 已注册进工具定义且参数齐全`() {
        val def = DefaultTools.ALL.firstOrNull { it.name == Subagent.TOOL_NAME }
        assertTrue("spawn_subagent 应注册进 DefaultTools", def != null)
        val names = def!!.params.map { it.name }
        assertTrue(names.containsAll(listOf("agent_type", "task", "context", "expected_output")))
        val schema = def!!.toJsonObject().getJSONObject("function")
        val required = schema.getJSONObject("parameters").getJSONArray("required")
        assertEquals(listOf("agent_type", "task"), (0 until required.length()).map { required.getString(it) })
    }
}
