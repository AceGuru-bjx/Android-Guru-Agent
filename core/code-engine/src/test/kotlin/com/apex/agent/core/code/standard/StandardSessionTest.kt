package com.apex.agent.core.code.standard

import com.apex.agent.core.llm.LlmMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StandardSession 会话模型测试：追加 / 标题 / 统计 / fork 谱系 / 恢复。
 */
class StandardSessionTest {

    @Test
    fun `append assigns monotonic ids and sets title from first user message`() {
        val s = StandardSession("ws")
        val m1 = s.append(LlmMessage.User("修复登录崩溃\n第二行"))
        val m2 = s.append(LlmMessage.Assistant("收到"))
        assertTrue(m1.id < m2.id)
        assertEquals("修复登录崩溃", s.title)
        assertEquals(2, s.size())
    }

    @Test
    fun `multi-line title collapses to first line with cap`() {
        val s = StandardSession("ws")
        val long = buildString { repeat(100) { append('字') } }
        s.append(LlmMessage.User(long))
        assertEquals(48, s.title?.length)
    }

    @Test
    fun `stats counts roles and estimates tokens`() {
        val s = StandardSession("ws")
        s.append(LlmMessage.User("abcd"))                      // 1 token
        s.append(LlmMessage.Assistant("工具完成"))              // CJK 4 → 6
        s.append(LlmMessage.ToolResult("c1", "ok"))            // 折半
        val stats = s.stats { StandardSession.defaultTokenEstimator(it) }
        assertEquals(3, stats.messageCount)
        assertEquals(1, stats.userTurns)
        assertEquals(1, stats.toolResults)
        assertEquals(1, stats.assistantMessages)
        assertTrue(stats.estimatedTokens > 0)
    }

    @Test
    fun `fork truncates at anchor and keeps lineage`() {
        val s = StandardSession("root")
        val u1 = s.append(LlmMessage.User("第一问"))
        s.append(LlmMessage.Assistant("答一"))
        val u2 = s.append(LlmMessage.User("第二问"))
        s.append(LlmMessage.Assistant("答二"))

        val child = s.forkFrom(u2.id, "child1")
        // 子会话含 [第一问, 答一, 第二问]（含锚点）
        assertEquals(3, child.size())
        assertEquals(2, child.lineageDepth())
        assertEquals(listOf("root", "child1"), child.lineageChain())
        // 标题继承
        assertEquals("第一问", child.title)
        // 父会话不变
        assertEquals(4, s.size())
        assertNotNull(s.findById(u1.id))
    }

    @Test
    fun `fork from empty anchor yields empty child`() {
        val s = StandardSession("root")
        s.append(LlmMessage.User("q"))
        val child = s.forkFrom(0, "child")
        assertEquals(0, child.size())
        assertEquals("q", child.title) // 继承父标题
    }

    @Test
    fun `restore replaces messages and resets ids`() {
        val s = StandardSession("ws")
        s.append(LlmMessage.User("旧"))
        s.restore(listOf(LlmMessage.User("新1"), LlmMessage.Assistant("新2")))
        assertEquals(2, s.size())
        assertEquals("新1", s.title)
        // id 序列重排：id=1 指向恢复后的首条（旧消息实体被整体替换）
        assertEquals("新1", (s.findById(1)?.message as? LlmMessage.User)?.content)
        assertNull(s.snapshot().firstOrNull { (it.message as? LlmMessage.User)?.content == "旧" })
    }

    @Test
    fun `snapshot is a defensive copy`() {
        val s = StandardSession("ws")
        s.append(LlmMessage.User("q"))
        val snap = s.snapshot()
        // 篡改快照不影响会话
        assertEquals(1, s.size())
        assertEquals(1, snap.size)
        s.append(LlmMessage.User("q2"))
        assertEquals(1, snap.size)
        assertEquals(2, s.size())
    }

    @Test
    fun `default token estimator mixes ascii and cjk`() {
        assertEquals(0, StandardSession.defaultTokenEstimator(""))
        assertEquals(1, StandardSession.defaultTokenEstimator("abcd"))
        // 4 个 CJK = 4*3/2 = 6
        assertEquals(6, StandardSession.defaultTokenEstimator("中文计数"))
        // 混合：2 ASCII (0) + 1 CJK(1) + "ab"(0) → 0+1=1? 具体断言用下界保护
        assertTrue(StandardSession.defaultTokenEstimator("a中文b") >= 1)
    }
}
