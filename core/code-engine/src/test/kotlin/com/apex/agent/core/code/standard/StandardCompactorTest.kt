package com.apex.agent.core.code.standard

import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.llm.runtime.SingleClientModelRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StandardCompactor 压缩器测试：
 * 触发阈值 / 边界对齐 / LLM 摘述 / 滑窗降级 / 不压缩路径。
 */
class StandardCompactorTest {

    /** 可编程假 LlmClient：chat 返回固定文本；chatStream 透传 chat。 */
    private class ScriptedClient(var chatResponse: String? = null) : LlmClient {
        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<com.apex.agent.core.llm.ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): LlmResponse = LlmResponse(content = chatResponse)

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<com.apex.agent.core.llm.ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): Flow<LlmStreamChunk> = flow {
            chatResponse?.let { emit(LlmStreamChunk(content = it, isFinish = true)) }
        }
    }

    private fun messagesOf(count: Int): List<LlmMessage> = buildList {
        add(LlmMessage.User("任务开始"))
        repeat(count) { i ->
            add(LlmMessage.Assistant("步骤 $i", listOf(com.apex.agent.core.llm.ToolCall("c$i", "code_read", "{}"))))
            add(LlmMessage.ToolResult("c$i", "结果 $i " + "x".repeat(200)))
        }
    }

    @Test
    fun `no compaction below threshold`() = runTest {
        val compactor = StandardCompactor(SingleClientModelRuntime(ScriptedClient()))
        val report = compactor.compactIfNeeded(
            messages = messagesOf(4),
            estimatedTokens = 100,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecent = 6
        )
        assertNull(report)
    }

    @Test
    fun `llm summary compaction replaces old segment`() = runTest {
        val summary = "## 任务：修复登录\n- 已改 A.kt\n- 验证通过".let { "x".repeat(80) + it }
        val compactor = StandardCompactor(SingleClientModelRuntime(ScriptedClient(summary)))
        val messages = messagesOf(10)
        val report = compactor.compactIfNeeded(
            messages = messages,
            estimatedTokens = 500_000, // 远超阈值
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecent = 6
        )
        assertNotNull(report)
        assertTrue(report!!.effective)
        assertEquals("LLM_SUMMARY", report.strategy)
        // 替换 = [System 摘要] + 最近段
        assertTrue(report.replacement.first() is LlmMessage.System)
        assertTrue(report.replacement.size < messages.size)
        assertEquals(messages.size - 1 - report.messagesRemoved + 1, report.replacement.size)
        assertTrue(report.afterTokens < report.beforeTokens)
    }

    @Test
    fun `llm failure falls back to sliding window digest`() = runTest {
        // chat 抛异常 → 滑窗降级
        val failing = object : LlmClient {
            override suspend fun chat(
                messages: List<LlmMessage>,
                tools: List<com.apex.agent.core.llm.ToolDefinition>,
                temperature: Float,
                maxTokens: Int
            ): LlmResponse = throw IllegalStateException("network down")

            override fun chatStream(
                messages: List<LlmMessage>,
                tools: List<com.apex.agent.core.llm.ToolDefinition>,
                temperature: Float,
                maxTokens: Int
            ): Flow<LlmStreamChunk> = flow { }
        }
        val compactor = StandardCompactor(SingleClientModelRuntime(failing))
        val report = compactor.compactIfNeeded(
            messages = messagesOf(10),
            estimatedTokens = 500_000,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecent = 6
        )
        assertNotNull(report)
        assertTrue(report!!.effective)
        assertEquals("SLIDING_WINDOW", report.strategy)
        assertTrue(report.summary.contains("sliding-window"))
        // 最近 6 条保留：replacement = 摘要 + 6
        assertEquals(7, report.replacement.size)
    }

    @Test
    fun `boundary aligns to tool result edge`() {
        val compactor = StandardCompactor(SingleClientModelRuntime(ScriptedClient()))
        val messages = listOf(
            LlmMessage.User("q"),
            LlmMessage.Assistant("a", listOf(com.apex.agent.core.llm.ToolCall("c1", "code_read", "{}"))),
            LlmMessage.ToolResult("c1", "r1"),
            LlmMessage.Assistant("done")
        )
        // 边界 2：切在 assistant(toolCalls) 与 result 之间 → 对齐到 3（result 之后）
        assertEquals(3, compactor.alignBoundary(messages, 2))
        // 边界 1：段末是 User（其后 assistant 属保留段）→ 不动
        assertEquals(1, compactor.alignBoundary(messages, 1))
        // 边界 3：result 之后 → 不动
        assertEquals(3, compactor.alignBoundary(messages, 3))
        // 超尾边界 → 钳制
        assertEquals(4, compactor.alignBoundary(messages, 99))
    }

    @Test
    fun `empty summary gives up compaction`() = runTest {
        val compactor = StandardCompactor(SingleClientModelRuntime(ScriptedClient(null)))
        // 旧段只含 System 注入（滑窗骨架也不产线）→ 两条摘述链都空 → 放弃压缩
        val report = compactor.compactIfNeeded(
            messages = listOf(
                LlmMessage.System("sys1"), LlmMessage.System("sys2"),
                LlmMessage.User("q"), LlmMessage.Assistant("a")
            ),
            estimatedTokens = 500_000,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecent = 2
        )
        assertNull(report)
    }
}
