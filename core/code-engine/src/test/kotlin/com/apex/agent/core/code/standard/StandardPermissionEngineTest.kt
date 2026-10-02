package com.apex.agent.core.code.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StandardPermissionEngine 裁决矩阵测试：
 * 规则短路 / 会话记忆 / 模式兜底 / 命令级通配 / 子代理折叠。
 */
class StandardPermissionEngineTest {

    private fun engine(mode: StandardPermissionMode = StandardPermissionMode.DEFAULT) =
        StandardPermissionEngine(mode)

    // ═══ 规则 ═══

    @Test
    fun `deny rule short-circuits even under bypass`() {
        val e = engine(StandardPermissionMode.BYPASS)
        e.updateRules(listOf(StandardPermissionRule("code_edit", StandardPermissionEffect.DENY)))
        val d = e.decide("code_edit", "{}")
        assertEquals(StandardPermissionEffect.DENY, d.effect)
        assertTrue(d.reason.contains("DENY"))
    }

    @Test
    fun `prefix wildcard rule matches dynamic mcp ids`() {
        val e = engine()
        e.updateRules(listOf(StandardPermissionRule("mcp__github_*", StandardPermissionEffect.ALLOW)))
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("mcp__github__create_issue", "{}").effect)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("mcp__github__list_prs", "{}").effect)
        // 非同前缀不命中（gitlab 无 mcp 前缀 → 非 only 读分类 → 走 ASK 兑底）
        assertEquals(StandardPermissionEffect.ASK, e.decide("gitlab__x", "{}").effect)
    }

    @Test
    fun `single star matches everything`() {
        val e = engine()
        e.updateRules(listOf(StandardPermissionRule("*", StandardPermissionEffect.ALLOW)))
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("anything_at_all", "{}").effect)
    }

    @Test
    fun `blank pattern never matches`() {
        val e = engine()
        e.updateRules(listOf(StandardPermissionRule("  ", StandardPermissionEffect.ALLOW)))
        // 空模式在 updateRules 已被过滤；裁决回退模式兜底
        assertEquals(StandardPermissionEffect.ASK, e.decide("code_write", "{}").effect)
    }

    // ═══ 模式兜底 ═══

    @Test
    fun `default mode allows readonly and asks for writes`() {
        val e = engine()
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("code_read", "{}").effect)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("code_grep", "{}").effect)
        assertEquals(StandardPermissionEffect.ASK, e.decide("code_write", "{}").effect)
        assertEquals(StandardPermissionEffect.ASK, e.decide("shell_execute", "{}").effect)
    }

    @Test
    fun `bypass mode allows everything except deny rules`() {
        val e = engine(StandardPermissionMode.BYPASS)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("code_write", "{}").effect)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("shell_execute", "{}").effect)
    }

    @Test
    fun `accept_edits allows edit-family but still asks for shell`() {
        val e = engine(StandardPermissionMode.ACCEPT_EDITS)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("code_edit", "{}").effect)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("code_write", "{}").effect)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("code_git_commit", "{}").effect)
        assertEquals(StandardPermissionEffect.ASK, e.decide("shell_execute", "{}").effect)
    }

    @Test
    fun `plan mode hard-denies writes regardless of allow rules`() {
        val e = engine(StandardPermissionMode.PLAN)
        // 显式 ALLOW 规则也不可越级（规划阶段零副作用硬承诺）
        e.updateRules(listOf(StandardPermissionRule("code_write", StandardPermissionEffect.ALLOW)))
        assertEquals(StandardPermissionEffect.DENY, e.decide("code_write", "{}").effect)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("code_read", "{}").effect)
    }

    // ═══ 会话记忆 ═══

    @Test
    fun `session memory allows subsequent calls without asking`() {
        val e = engine()
        assertEquals(StandardPermissionEffect.ASK, e.decide("code_write", "{}").effect)
        e.rememberSessionAllow("code_write")
        val d = e.decide("code_write", "{}")
        assertEquals(StandardPermissionEffect.ALLOW, d.effect)
        assertTrue(d.reason.contains("会话记忆"))
    }

    @Test
    fun `reset session memory restores asking`() {
        val e = engine()
        e.rememberSessionAllow("shell_execute")
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("shell_execute", "{}").effect)
        e.resetSessionMemory()
        assertEquals(StandardPermissionEffect.ASK, e.decide("shell_execute", "{}").effect)
    }

    @Test
    fun `deny rule outranks session memory`() {
        val e = engine(StandardPermissionMode.BYPASS)
        e.rememberSessionAllow("code_edit")
        e.updateRules(listOf(StandardPermissionRule("code_edit", StandardPermissionEffect.DENY)))
        assertEquals(StandardPermissionEffect.DENY, e.decide("code_edit", "{}").effect)
    }

    // ═══ 子代理上下文 ═══

    @Test
    fun `ask folds to deny in sub-agent context`() {
        val e = engine()
        val d = e.decide("code_write", "{}", subAgentContext = true)
        assertEquals(StandardPermissionEffect.DENY, d.effect)
        assertTrue(d.reason.contains("子代理"))
    }

    // ═══ 命令级通配 ═══

    @Test
    fun `shell command rules match command prefix`() {
        val e = engine(StandardPermissionMode.BYPASS)
        e.updateCommandRules(
            listOf(
                StandardPermissionRule("git status*", StandardPermissionEffect.ALLOW),
                StandardPermissionRule("rm*", StandardPermissionEffect.DENY)
            )
        )
        val ok = e.decide("shell_execute", """{"command":"git status --short"}""")
        assertEquals(StandardPermissionEffect.ALLOW, ok.effect)

        val denied = e.decide("shell_execute", """{"command":"rm -rf /data"}""")
        assertEquals(StandardPermissionEffect.DENY, denied.effect)
    }

    @Test
    fun `command session memory allows matching commands only`() {
        val e = engine()
        e.rememberSessionAllow("git status", isCommand = true)
        assertEquals(
            StandardPermissionEffect.ALLOW,
            e.decide("shell_execute", """{"command":"git status"}""").effect
        )
        // 同工具不同命令仍走 ASK
        assertEquals(
            StandardPermissionEffect.ASK,
            e.decide("shell_execute", """{"command":"git push"}""").effect
        )
    }

    @Test
    fun `command extraction from json handles escapes`() {
        val cmd = StandardPermissionEngine.extractCommand("""{"command":"echo \"hi\" && ls"}""")
        assertEquals("echo \"hi\" && ls", cmd)
        assertNull(StandardPermissionEngine.extractCommand("not json"))
        assertNull(StandardPermissionEngine.extractCommand("""{"path":"x"}"""))
    }

    @Test
    fun `command pattern without star matches first word`() {
        assertTrue(StandardPermissionEngine.commandPatternMatches("git", "git status"))
        assertTrue(StandardPermissionEngine.commandPatternMatches("git status", "git status"))
        assertTrue(!StandardPermissionEngine.commandPatternMatches("git push", "git status"))
    }

    // ═══ 静态判定 ═══

    @Test
    fun `readonly classification`() {
        assertTrue(StandardPermissionEngine.isReadOnlyTool("code_read"))
        assertTrue(StandardPermissionEngine.isReadOnlyTool("code_todo"))
        assertTrue(StandardPermissionEngine.isReadOnlyTool("mcp__github__get"))
        assertTrue(!StandardPermissionEngine.isReadOnlyTool("code_write"))
        assertTrue(!StandardPermissionEngine.isReadOnlyTool("shell_execute"))
    }

    @Test
    fun `tool id pattern matcher semantics`() {
        assertTrue(StandardPermissionEngine.matches("code_read", "code_read"))
        assertTrue(!StandardPermissionEngine.matches("code_read", "code_read2"))
        assertTrue(StandardPermissionEngine.matches("code_git_*", "code_git_log"))
        assertTrue(!StandardPermissionEngine.matches("code_git_*", "code_grep"))
        assertTrue(StandardPermissionEngine.matches("*", "whatever"))
        assertTrue(!StandardPermissionEngine.matches("", "whatever"))
    }

    @Test
    fun `update mode hot-switches`() {
        val e = engine()
        assertEquals(StandardPermissionEffect.ASK, e.decide("code_edit", "{}").effect)
        e.updateMode(StandardPermissionMode.ACCEPT_EDITS)
        assertEquals(StandardPermissionEffect.ALLOW, e.decide("code_edit", "{}").effect)
        e.updateMode(StandardPermissionMode.PLAN)
        assertEquals(StandardPermissionEffect.DENY, e.decide("code_edit", "{}").effect)
        assertNotNull(e.currentMode())
    }
}
