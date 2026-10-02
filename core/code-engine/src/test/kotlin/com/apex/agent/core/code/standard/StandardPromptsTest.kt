package com.apex.agent.core.code.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StandardPrompts 提示词层测试：非空 / 关键指令在场 / 结构段完整。
 *
 * 提示词是标准循环的灵魂——这里锁住每段的核心动词，防重构时静默漂移。
 */
class StandardPromptsTest {

    @Test
    fun `all profile prompts are non-blank and carry identity`() {
        val prompts = mapOf(
            "build" to StandardPrompts.buildAgent(),
            "plan" to StandardPrompts.planAgent(),
            "general" to StandardPrompts.generalAgent(),
            "explore" to StandardPrompts.exploreSubAgent(),
            "research" to StandardPrompts.researchSubAgent()
        )
        prompts.forEach { (key, prompt) ->
            assertTrue("$key prompt not blank", prompt.isNotBlank())
            assertTrue("$key prompt 有体量", prompt.length > 200)
        }
        assertTrue(StandardPrompts.planAgent().contains("READ-ONLY"))
        assertTrue(StandardPrompts.exploreSubAgent().contains("READ-ONLY"))
        assertTrue(StandardPrompts.buildAgent().contains("BUILD agent"))
    }

    @Test
    fun `plan prompt defines the output contract`() {
        val p = StandardPrompts.planAgent()
        assertTrue(p.contains("### Goal"))
        assertTrue(p.contains("### Steps"))
        assertTrue(p.contains("### Verification"))
        assertTrue(p.contains("### Risks"))
    }

    @Test
    fun `task methodology carries the eight rules`() {
        val m = StandardPrompts.taskMethodology()
        assertTrue(m.contains("Read before you write"))
        assertTrue(m.contains("minimal diff"))
        assertTrue(m.contains("Verify after every change"))
        assertTrue(m.contains("todo list"))
        assertTrue(m.contains("Delegate exploration"))
        assertTrue(m.contains("Report honestly"))
        // 反占位铁律（任务完成逻辑的硬承诺）
        assertTrue(m.contains("TODO: implement later"))
    }

    @Test
    fun `subagent prompt demands evidence format`() {
        val e = StandardPrompts.exploreSubAgent()
        assertTrue(e.contains("## Conclusion"))
        assertTrue(e.contains("## Evidence"))
        assertTrue(e.contains("## Uncertainty"))
        val r = StandardPrompts.researchSubAgent()
        assertTrue(r.contains("## Sources"))
    }

    @Test
    fun `subagent brief embeds description and prompt`() {
        val brief = StandardPrompts.subAgentBrief("找出调用点", "扫描 module A", "/ws/root")
        assertTrue(brief.contains("找出调用点"))
        assertTrue(brief.contains("扫描 module A"))
        assertTrue(brief.contains("/ws/root"))
        assertTrue(brief.contains("no parent history"))
    }

    @Test
    fun `plan execution brief lists confirmed steps`() {
        val brief = StandardPrompts.planExecutionBrief("修登录", listOf("读代码", "改代码", "验证"))
        assertTrue(brief.contains("修登录"))
        assertTrue(brief.contains("1. 读代码"))
        assertTrue(brief.contains("3. 验证"))
        assertTrue(brief.contains("todo list"))
    }

    @Test
    fun `permission denial tool result is model-actionable`() {
        val out = StandardPrompts.permissionDeniedToolResult("shell_execute", "DEFAULT 模式兜底")
        assertTrue(out.contains("shell_execute"))
        assertTrue(out.contains("safer route"))
        assertFalse(out.contains("{"))
    }

    @Test
    fun `budget exhaustion directive forbids more tool calls`() {
        val d = StandardPrompts.turnBudgetExhausted()
        assertTrue(d.contains("turn budget"))
        assertTrue(d.contains("Do not call"))
    }

    @Test
    fun `repetitive call warning includes count and tool`() {
        val w = StandardPrompts.repetitiveCallWarning(3, "code_grep")
        assertTrue(w.contains("3 times"))
        assertTrue(w.contains("code_grep"))
    }

    @Test
    fun `subagent tool result carries stats and conclusion`() {
        val outcome = StandardSubAgentOutcome(
            kind = StandardAgentKind.EXPLORE,
            output = "结论：X 被 3 处调用",
            turns = 4,
            toolCalls = 6,
            durationMs = 12_500
        )
        val out = StandardPrompts.subAgentToolResult(outcome)
        assertTrue(out.contains("explore"))
        assertTrue(out.contains("4 turns"))
        assertTrue(out.contains("结论：X 被 3 处调用"))
    }

    @Test
    fun `compaction prompt keeps facts and drops noise`() {
        val p = StandardPrompts.compactionSummary("压缩以下转录")
        assertTrue(p.contains("files changed"))
        assertTrue(p.contains("verification results"))
        assertTrue(p.contains("open problems"))
        assertTrue(p.contains("raw tool outputs"))
    }

    @Test
    fun `tool surface section embeds the dynamic guide`() {
        val s = StandardPrompts.toolSurfaceSection("Available: code_read, code_edit")
        assertTrue(s.contains("## Tool Surface"))
        assertTrue(s.contains("code_edit"))
    }

    @Test
    fun `todo guidance locks status semantics`() {
        val g = StandardPrompts.todoGuidance()
        assertTrue(g.contains("in_progress"))
        assertTrue(g.contains("completed"))
    }
}
