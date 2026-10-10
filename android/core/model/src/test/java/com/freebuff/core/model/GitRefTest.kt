package com.freebuff.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Git 引用与仓库内路径的合法性:端上先判,才能给模型带处方的信封(不然只有 GitHub 的 422/404)。 */
class GitRefTest {

    @Test
    fun `合法分支名`() {
        listOf(
            "main", "master", "release/1.2", "agent/fix-typo", "feat_ui", "v1.0.0",
            "fix-issue-42", "用户/中文分支",
        ).forEach { assertTrue("$it 应当是合法分支名", GitRef.isValidBranch(it)) }
    }

    @Test
    fun `非法分支名逐条说清原因`() {
        val cases = mapOf(
            "" to "不能为空",
            "   " to "不能为空",
            "my branch" to "不能包含空格",
            "a~b" to "不能包含",
            "a^b" to "不能包含",
            "a:b" to "不能包含",
            "a?b" to "不能包含",
            "a*b" to "不能包含",
            "a[b" to "不能包含",
            "a\\b" to "不能包含",
            "a..b" to "..",
            "a@{b" to "@{",
            "/main" to "开头",
            "main/" to "结尾",
            "a//b" to "//",
            "-main" to "- 开头",
            "main." to "结尾",
            "main.lock" to "lock",
            " main" to "空白",
        )
        cases.forEach { (name, needle) ->
            val problem = GitRef.branchProblem(name)
            assertFalse("$name 应当被判非法", GitRef.isValidBranch(name))
            assertTrue("$name 的原因要说清($needle),实际=$problem", problem!!.contains(needle))
        }
        assertTrue("超长分支名要报", !GitRef.isValidBranch("a".repeat(GitRef.MAX_REF_LEN + 1)))
    }

    @Test
    fun `合法仓库内路径`() {
        listOf("README.md", "src/main.rs", "docs/zh/用法.md", "a/b/c.txt").forEach {
            assertTrue("$it 应当是合法路径", GitRef.isValidRepoPath(it))
        }
    }

    @Test
    fun `非法路径挡住逃逸与绝对路径`() {
        val cases = mapOf(
            "" to "不能为空",
            "/etc/passwd" to "相对路径",
            "../secret.txt" to "..",
            "docs/../../etc/hosts" to "..",
            "./a.txt" to "不能包含 . 段",
            "docs//a.txt" to "连续的 /",
            "docs/" to "指向文件",
            "a\\b.txt" to "/ 分隔",
        )
        cases.forEach { (path, needle) ->
            assertFalse("$path 应当被判非法", GitRef.isValidRepoPath(path))
            assertTrue("$path 的原因要说清($needle),实际=${GitRef.pathProblem(path)}",
                GitRef.pathProblem(path)!!.contains(needle))
        }
        assertTrue("超长路径要报", !GitRef.isValidRepoPath("a".repeat(GitRef.MAX_PATH_LEN + 1)))
    }

    @Test
    fun `合法时不给原因`() {
        assertNull(GitRef.branchProblem("agent/x"))
        assertNull(GitRef.pathProblem("src/a.kt"))
    }
}
