package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具注册表:T1 的验收 —— 「定义 + 标签 + 权限 + 能力 + 执行归属」都在 `Tools.kt` 一份里,
 * 其余各处从这里派生,所以不可能再出现「加了工具但没标签/没权限分级」这类静默缺陷。
 */
class ToolRegistryTest {

    @Test
    fun `展示名来自注册表而非第二份手抄清单`() {
        // 每个注册工具的 label 就是它的展示名(过去 agentEvents 里还有一张手抄表,两边会分叉)
        DefaultTools.ALL.forEach { t ->
            assertTrue("${t.name} 缺少 label", t.label.isNotBlank())
            assertEquals(t.label, toolDisplayName(t.name))
        }
        // 后端工具名(端侧还没实现)仍走兜底表,未知名字回退原名
        assertEquals("读取目录树", toolDisplayName("read_subtree"))
        assertEquals("no_such_tool", toolDisplayName("no_such_tool"))
    }

    @Test
    fun `权限分级与注册表不会分叉`() {
        DefaultTools.ALL.forEach { t ->
            assertEquals("${t.name} 的默认分级应取自注册表", t.permission, ToolPermissions.defaultFor(t.name))
        }
        // 写工具全部默认需确认(宁可多问不静默)
        assertTrue(DefaultTools.WRITE_TOOLS.isNotEmpty())
        DefaultTools.WRITE_TOOLS.forEach { name ->
            assertEquals("$name 是写工具,默认必须 CONFIRM", ToolPermission.CONFIRM, ToolPermissions.defaultFor(name))
        }
    }

    @Test
    fun `计划模式物理剔除写工具 只读工具保留`() {
        val plan = DefaultTools.forCapabilities(memory = true, planMode = true).map { it.name }
        assertTrue("计划模式下不该看到写工具", plan.none { it in DefaultTools.WRITE_TOOLS })
        assertTrue("读工具要保留", plan.contains("github_get_file"))
        assertTrue("列分支是读操作,计划模式仍可用", plan.contains("github_list_branches"))
        assertTrue("收工工具不能被裁掉,否则循环没有出口", plan.contains(AgentLoop.COMPLETION_TOOL))

        val normal = DefaultTools.forCapabilities(memory = true, planMode = false).map { it.name }
        assertTrue("非计划模式要能看到写工具", normal.containsAll(DefaultTools.WRITE_TOOLS))
    }

    @Test
    fun `计划模式下碰到写工具会走未知工具路径并得到近邻建议`() {
        val planTools = DefaultTools.forCapabilities(memory = true, planMode = true)
        val names = DefaultTools.names(planTools)
        // 写工具被剔除后,模型若还按旧记忆去调它,执行入口（见 ToolExecutors / ChatViewModel.dispatchTool）
        // 会当成未知工具 —— 回一张带近邻读工具建议的信封,而不是执行写操作
        assertFalse(DefaultTools.isKnown("github_commit_files", planTools))
        assertFalse(DefaultTools.isKnown("github_create_pull_request", planTools))
        val env = ToolErrors.unknownTool("github_commit_files", names)
        assertTrue("要指出拼写相近的读工具,实际: $env", env.contains("github_get_file"))
        assertTrue("要带完整可用清单", env.contains("github_list_branches"))
        assertTrue("未知工具不该被鼓励重试", env.contains("UNKNOWN_TOOL"))
    }

    @Test
    fun `计划模式下模型碰到写工具会走未知工具路径并得到近邻建议`() {
        val planTools = DefaultTools.forCapabilities(memory = true, planMode = true)
        // 写工具被剔除后不再是「已知工具」,执行入口会当未知工具处理(不会弹确认、不会执行)
        assertFalse(DefaultTools.isKnown("github_commit_files", planTools))
        val env = ToolErrors.unknownTool("github_commit_files", DefaultTools.names(planTools))
        assertTrue("要指出名字接近的读工具,实际: $env", env.contains("github_get_file"))
        assertTrue("要带完整可用清单", env.contains("github_list_branches"))
        // 信封里以「工具=<名字>」回显了被调用的写工具,但可用清单里绝不能再出现写工具
        assertTrue("不能把写工具列成可用", DefaultTools.names(planTools).none { it in DefaultTools.WRITE_TOOLS })
    }

    @Test
    fun `记忆工具按能力裁剪 与计划模式可以叠加`() {
        val off = DefaultTools.forCapabilities(memory = false, planMode = true).map { it.name }
        assertTrue(off.none { it in DefaultTools.MEMORY_TOOLS })
        assertTrue(off.none { it in DefaultTools.WRITE_TOOLS })
        assertTrue(off.contains("web_search"))
        // 记忆工具集合也来自注册表,不是第二份清单
        assertEquals(setOf("save_memory", "memory_recall"), DefaultTools.MEMORY_TOOLS)
    }

    @Test
    fun `每个工具都声明了执行归属`() {
        // HOST 工具在会话宿主里执行(记忆 / 子代理),其余必须落在 DATA 路由上,
        // 由 ToolExecutorsTest 断言「DATA 路由的每个工具都有真实分派」。
        assertEquals(
            setOf("save_memory", "memory_recall", Subagent.TOOL_NAME, TodoList.TOOL),
            DefaultTools.ofRoute(ToolRoute.HOST).map { it.name }.toSet(),
        )
        val data = DefaultTools.ofRoute(ToolRoute.DATA).map { it.name }
        assertTrue(data.isNotEmpty())
        assertTrue("DATA 与 HOST 不能重叠", data.none { it in DefaultTools.ofRoute(ToolRoute.HOST).map { h -> h.name } })
    }

    @Test
    fun `任务清单不受记忆开关与计划模式影响`() {
        // 它不是对外写入,而是「把计划摆出来」:计划模式下更该有;关记忆也与它无关。
        val off = DefaultTools.forCapabilities(memory = false, planMode = true).map { it.name }
        assertTrue("计划模式下仍要能维护清单", off.contains(TodoList.TOOL))
        assertTrue(DefaultTools.isKnown(TodoList.TOOL, DefaultTools.forCapabilities(memory = false)))
    }

    @Test
    fun `写工具清单与 GitHub 写入口一致`() {
        assertEquals(
            setOf("github_create_branch", "github_commit_files", "github_create_pull_request"),
            DefaultTools.WRITE_TOOLS,
        )
        DefaultTools.WRITE_TOOLS.forEach { name ->
            assertNotNull("$name 应有说明书", DefaultTools.find(name))
        }
    }
}
