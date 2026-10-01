package com.apex.agent.core.code.standard

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DefaultToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StandardToolSurface 工具编排层测试：
 * 别名归一 / 画像过滤 / 合成 task 工具 / 指南文本。
 */
class StandardToolSurfaceTest {

    private class FakeTool(
        override val id: String,
        override val description: String = "fake"
    ) : AgentTool {
        override val name: String get() = id
        override val parametersSchema: String =
            """{"type":"object","properties":{},"required":[]}"""
        override suspend fun execute(arguments: String): String = "ok"
    }

    private fun registry(vararg ids: String): DefaultToolRegistry =
        DefaultToolRegistry().apply { ids.forEach { register(FakeTool(it)) } }

    // ═══ 别名归一 ═══

    @Test
    fun `aliases resolve to registry ids`() {
        assertEquals("code_read", StandardToolSurface.resolveRegistryId("read"))
        assertEquals("code_read", StandardToolSurface.resolveRegistryId("read_file"))
        assertEquals("code_write", StandardToolSurface.resolveRegistryId("write"))
        assertEquals("code_edit", StandardToolSurface.resolveRegistryId("edit"))
        assertEquals("code_edit", StandardToolSurface.resolveRegistryId("multiedit"))
        assertEquals("shell_execute", StandardToolSurface.resolveRegistryId("bash"))
        assertEquals("code_grep", StandardToolSurface.resolveRegistryId("grep"))
        assertEquals("code_glob", StandardToolSurface.resolveRegistryId("glob"))
        assertEquals("code_todo", StandardToolSurface.resolveRegistryId("todowrite"))
        assertEquals("code_check", StandardToolSurface.resolveRegistryId("lint"))
    }

    @Test
    fun `unknown and mcp names pass through unchanged`() {
        assertEquals("mcp__github__create_issue", StandardToolSurface.resolveRegistryId("mcp__github__create_issue"))
        assertEquals("code_read", StandardToolSurface.resolveRegistryId("code_read"))
        assertEquals("web_search", StandardToolSurface.resolveRegistryId("web_search"))
    }

    @Test
    fun `task tool detection`() {
        assertTrue(StandardToolSurface.isSyntheticTaskTool("task"))
        assertTrue(StandardToolSurface.isSyntheticTaskTool("subagent"))
        assertFalse(StandardToolSurface.isSyntheticTaskTool("code_read"))
    }

    // ═══ 工具面构建 ═══

    @Test
    fun `build profile sees full registry plus task tool`() {
        val r = registry("code_read", "code_edit", "shell_execute", "web_search", "mcp__x__y")
        val plan = StandardToolSurface.buildToolPlan(StandardAgentCatalog.BUILD, r)
        val names = plan.map { it.name }
        assertTrue(names.contains("code_read"))
        assertTrue(names.contains("shell_execute"))
        assertTrue(names.contains("mcp__x__y"))
        assertTrue("合成 task 恒在场", names.contains("task"))
    }

    @Test
    fun `plan profile filters to readonly allowlist without task`() {
        val r = registry(
            "code_read", "code_grep", "code_glob", "code_check",
            "code_git_status", "code_git_diff", "code_git_log",
            "code_write", "code_edit", "shell_execute", "web_search"
        )
        val plan = StandardToolSurface.buildToolPlan(StandardAgentCatalog.PLAN, r)
        val names = plan.map { it.name }
        assertTrue(names.contains("code_read"))
        assertTrue(names.contains("code_git_status"))
        assertFalse("规划师不该看到写工具", names.contains("code_write"))
        assertFalse(names.contains("code_edit"))
        assertFalse(names.contains("shell_execute"))
        // web_search 不在 PLAN 白名单（提示词宣告其不可用）
        assertFalse(names.contains("web_search"))
    }

    @Test
    fun `explore subagent gets readonly surface and no task tool`() {
        val r = registry("code_read", "code_grep", "code_write", "shell_execute")
        val plan = StandardToolSurface.buildToolPlan(StandardAgentCatalog.EXPLORE, r)
        val names = plan.map { it.name }
        assertTrue(names.contains("code_read"))
        assertFalse(names.contains("code_write"))
        assertFalse(names.contains("shell_execute"))
        assertFalse("子代理不能再派子代理", names.contains("task"))
    }

    @Test
    fun `forced tools narrow the surface and drop task`() {
        val r = registry("code_read", "code_edit", "code_write")
        val plan = StandardToolSurface.buildToolPlan(
            StandardAgentCatalog.BUILD, r,
            forcedToolIds = setOf("code_edit")
        )
        val names = plan.map { it.name }
        assertEquals(listOf("code_edit"), names)
    }

    @Test
    fun `exposeAll overrides profile allowlist`() {
        val r = registry("code_read", "code_write", "shell_execute")
        val plan = StandardToolSurface.buildToolPlan(
            StandardAgentCatalog.PLAN, r, exposeAll = true
        )
        val names = plan.map { it.name }
        assertTrue(names.contains("code_write"))
    }

    @Test
    fun `tool plan is capped at budget`() {
        val ids = (1..80).map { "tool_%02d".format(it) }
        val r = registry(*ids.toTypedArray())
        val plan = StandardToolSurface.buildToolPlan(StandardAgentCatalog.GENERAL, r)
        // ≤ MAX_TOOLS + 1（task 合成不占预算）
        assertTrue(plan.size <= StandardToolSurface.MAX_TOOLS + 1)
    }

    // ═══ 合成工具定义 ═══

    @Test
    fun `synthetic task tool definition has description and schema`() {
        val def = StandardToolSurface.syntheticTaskTool()
        assertEquals("task", def.name)
        assertTrue(def.description.contains("sub-agent"))
        assertTrue(def.parameters.contains("description"))
        assertTrue(def.parameters.contains("prompt"))
        assertTrue(def.parameters.contains("subagent_type"))
        assertTrue(def.parameters.contains("explore"))
        assertTrue(def.parameters.contains("research"))
    }

    // ═══ 指南文本 ═══

    @Test
    fun `tool guide lists tools and aliases`() {
        val guide = StandardToolSurface.buildToolGuide(
            StandardAgentCatalog.BUILD,
            listOf("code_read", "code_edit", "task")
        )
        assertTrue(guide.contains("code_read"))
        assertTrue(guide.contains("task"))
        assertTrue(guide.contains("read→code_read"))
        assertTrue(guide.contains("subagent_type"))
    }

    @Test
    fun `tool guide empty surface answers directly`() {
        val guide = StandardToolSurface.buildToolGuide(StandardAgentCatalog.BUILD, emptyList())
        assertTrue(guide.contains("answer directly"))
    }

    @Test
    fun `readonly guide declares the boundary`() {
        val guide = StandardToolSurface.buildToolGuide(
            StandardAgentCatalog.PLAN, listOf("code_read")
        )
        assertTrue(guide.contains("READ-ONLY"))
    }

    @Test
    fun `planHasTodo detection`() {
        assertTrue(StandardToolSurface.planHasTodo(listOf("code_todo")))
        assertTrue(StandardToolSurface.planHasTodo(listOf("todo")))
        assertFalse(StandardToolSurface.planHasTodo(listOf("code_read")))
    }
}
