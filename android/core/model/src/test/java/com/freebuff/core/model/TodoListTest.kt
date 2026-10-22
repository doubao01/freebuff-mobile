package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务清单:解析宽容度、上限、进度文案与工具声明。
 *
 * 这里盯的是「弱模型怎么写都不至于让整份清单丢掉」:字段别名、中文状态、认不出的状态 ——
 * 都能落到一个可用结果;真正不可用的(空清单/没有 content)才返回 null,由宿主回错误信封。
 */
class TodoListTest {

    @Test
    fun `解析标准清单`() {
        val items = TodoList.parse(
            """{"todos":[
                {"content":"读 README","status":"completed"},
                {"content":"改配置","status":"in_progress"},
                {"content":"补测试","status":"pending"}
            ]}""",
        )!!
        assertEquals(3, items.size)
        assertEquals(listOf("读 README", "改配置", "补测试"), items.map { it.content })
        assertEquals(
            listOf(TodoList.COMPLETED, TodoList.IN_PROGRESS, TodoList.PENDING),
            items.map { it.status },
        )
        assertTrue(items[0].isDone)
        assertTrue(items[1].isDoing)
    }

    @Test
    fun `字段别名与中文状态都能救回`() {
        val items = TodoList.parse(
            """{"todos":[
                {"task":"做完 A","state":"Done"},
                {"title":"  做 B  ","status":"进行中"},
                {"text":"C","status":"TODO"}
            ]}""",
        )!!
        assertEquals(listOf("做完 A", "做 B", "C"), items.map { it.content })
        assertEquals(
            listOf(TodoList.COMPLETED, TodoList.IN_PROGRESS, TodoList.PENDING),
            items.map { it.status },
        )
    }

    @Test
    fun `状态写法宽容 认不出按待办`() {
        assertEquals(TodoList.COMPLETED, TodoList.normalizeStatus("Finished"))
        assertEquals(TodoList.COMPLETED, TodoList.normalizeStatus("已完成"))
        assertEquals(TodoList.IN_PROGRESS, TodoList.normalizeStatus("in-progress"))
        assertEquals(TodoList.IN_PROGRESS, TodoList.normalizeStatus("正在做"))
        assertEquals(TodoList.PENDING, TodoList.normalizeStatus("blocked"))
        assertEquals(TodoList.PENDING, TodoList.normalizeStatus(""))
        // 没写 status 的条目不能因此丢掉
        val items = TodoList.parse("""{"todos":[{"content":"只有内容"}]}""")!!
        assertEquals(1, items.size)
        assertEquals(TodoList.PENDING, items[0].status)
    }

    @Test
    fun `空清单与坏 JSON 返回 null`() {
        assertNull(TodoList.parse(""))
        assertNull(TodoList.parse("{}"))
        assertNull(TodoList.parse("""{"todos":[]}"""))
        assertNull(TodoList.parse("""{"todos":null}"""))
        // 只有状态、没有内容的条目不算一条任务
        assertNull(TodoList.parse("""{"todos":[{"status":"pending"},{"content":"   "}]}"""))
        assertNull(TodoList.parse("这不是 JSON"))
    }

    @Test
    fun `超过上限截断 单条内容也截断`() {
        val many = (1..25).joinToString(",") { """{"content":"任务 $it"}""" }
        val items = TodoList.parse("""{"todos":[$many]}""")!!
        assertEquals(TodoList.MAX_ITEMS, items.size)
        assertEquals("任务 1", items.first().content)

        val long = "很长的任务".repeat(60)
        val one = TodoList.parse("""{"todos":[{"content":"$long"}]}""")!!
        assertEquals(TodoList.MAX_CONTENT, one[0].content.length)
    }

    @Test
    fun `进度与回给模型的摘要说清状态与下一步`() {
        val items = listOf(
            TodoList.Item("读 README", TodoList.COMPLETED),
            TodoList.Item("改配置", TodoList.IN_PROGRESS),
            TodoList.Item("补测试", TodoList.PENDING),
        )
        assertEquals("1/3", TodoList.progress(items))
        assertTrue(TodoList.brief(items).contains("共 3 项"))
        assertTrue(TodoList.brief(items).contains("已完成 1"))
        assertTrue(TodoList.brief(items).contains("进行中 1"))

        val s = TodoList.summary(items)
        assertTrue("要逐项带状态,实际: $s", s.contains("[已完成] 读 README"))
        assertTrue("要逐项带状态,实际: $s", s.contains("[进行中] 改配置"))
        assertTrue("要说清还剩几项,实际: $s", s.contains("还有 2 项未完成"))
        assertTrue("要提醒整份覆盖,实际: $s", s.contains("整份覆盖"))

        val done = items.map { it.copy(status = TodoList.COMPLETED) }
        assertTrue("全做完要指向收工,实际: ${TodoList.summary(done)}", TodoList.summary(done).contains("task_completed"))
    }

    @Test
    fun `工具声明覆盖清单结构与状态闭集`() {
        val tool = DefaultTools.find(TodoList.TOOL)!!
        assertEquals("任务清单是排计划,不该弹确认", ToolPermission.ALLOW, tool.permission)
        assertEquals("要写会话卡片,归宿主执行", ToolRoute.HOST, tool.route)
        assertEquals("多步任务工具不受计划模式影响", emptySet<ToolCap>(), tool.caps)

        val todos = tool.params.first { it.name == "todos" }
        assertEquals("array", todos.type)
        assertEquals(listOf("content", "status"), todos.itemFields.map { it.name })
        assertEquals(
            listOf(TodoList.PENDING, TodoList.IN_PROGRESS, TodoList.COMPLETED),
            todos.itemFields.first { it.name == "status" }.enum,
        )
        // 写进 schema 的是 items 对象,不是一坨待转义的字符串
        val schema = tool.toJsonObject().getJSONObject("function")
            .getJSONObject("parameters").getJSONObject("properties").getJSONObject("todos")
        assertEquals("array", schema.getString("type"))
        val status = schema.getJSONObject("items").getJSONObject("properties").getJSONObject("status")
        assertEquals(3, status.getJSONArray("enum").length())
        assertTrue("说明书要讲清整份覆盖语义", tool.description.contains("整份覆盖"))
    }
}
