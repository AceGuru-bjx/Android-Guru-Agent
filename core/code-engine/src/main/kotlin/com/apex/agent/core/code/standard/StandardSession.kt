package com.apex.agent.core.code.standard

import com.apex.agent.core.llm.LlmMessage
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * # Standard Session — 标准任务循环的会话管理器
 *
 * 会话 = 消息时间线（[StandardMessage] 列表）+ fork 谱系 + 统计派生。
 *
 * ## 核心语义
 *
 * - **append 追加**：用户消息 / 助手消息（含 toolCalls）/ 工具结果 / 系统
 *   注入全部经 [append]，每条获得单调 id；
 * - **fork / 回退**（[forkFrom]）：从某条消息回退生成**子会话**——
 *   父会话保留完整历史（可再 fork），子会话截取 `[0, anchor]` 后继续。
 *   会话 id 链 [lineage] 记录谱系（`root → child → grandchild`），
 *   上限 [MAX_LINEAGE_DEPTH] 防无限分叉内存膨胀；
 * - **不可变快照**（[snapshot]）：读取侧拿 `List<StandardMessage>` 副本，
 *   读写锁保护并发（引擎 IO 线程写 / UI 线程读统计）；
 * - **token 估算**：chars/4（拉丁）+ CJK 字符 ×1.5 加权 + 工具结果长度
 *   折半（工具输出多为结构化/重复内容，经验系数）。仅作仪表盘与压缩
 *   触发的**启发式**——真实用量以服务端 usage 帧为准（[StandardRunReport]）。
 *
 * ## 持久化边界
 *
 * 本类**纯内存**。落盘由 `StandardModeEngine` 经
 * `CodeConversationMemory`（LlmMessage 级 append/load）完成，
 * 恢复时 [restore] 接管。
 */
class StandardSession(
    /** 会话 id（workspace 内唯一；fork 生成新 id）。 */
    val sessionId: String,
    /** 谱系（含自身；根会话 = 长度 1）。 */
    private val lineage: List<String> = listOf(sessionId)
) {

    private val lock = ReentrantReadWriteLock()
    private val messages = mutableListOf<StandardMessage>()
    private var idSeq = 0L

    /** 上次活动时刻（懒更新，供列表排序）。 */
    @Volatile
    var lastActiveAt: Long = System.currentTimeMillis()
        private set

    /** 会话标题（首个用户消息截断；空会话 = null）。 */
    @Volatile
    var title: String? = null
        private set

    // ═══════════════════════ 写入 API ═══════════════════════

    /**
     * 追加一条消息，返回带 id 的会话消息。
     *
     * 首条用户消息自动成为标题（截断 [TITLE_MAX_CHARS]，单行化）。
     */
    fun append(message: LlmMessage): StandardMessage = lock.write {
        val wrapped = StandardMessage(
            id = ++idSeq,
            message = message,
            createdAt = System.currentTimeMillis()
        )
        messages.add(wrapped)
        lastActiveAt = wrapped.createdAt
        if (title == null && message is LlmMessage.User) {
            title = message.content
                .lineSequence()
                .firstOrNull()
                ?.take(TITLE_MAX_CHARS)
                ?.takeIf { it.isNotBlank() }
        }
        wrapped
    }

    /**
     * 从某条消息回退 fork 出子会话：截取 `[0, anchorId]`（含锚点）。
     *
     * @param anchorId 锚消息 id（不含 = 空子会话）
     * @param childId 子会话 id（调用方生成）
     * @return 子会话（已复制锚点前的消息；标题继承父会话）
     */
    fun forkFrom(anchorId: Long, childId: String): StandardSession {
        val child = StandardSession(childId, lineage + childId)
        val prefix = lock.read { messages.takeWhile { it.id <= anchorId } }
        prefix.forEach { child.append(it.message) }
        if (child.title == null) {
            child.title = title
        }
        child.lastActiveAt = System.currentTimeMillis()
        return child
    }

    /** 整体替换（恢复历史 / 压缩替换旧段时用）。 */
    fun replaceAll(newMessages: List<LlmMessage>) = lock.write {
        messages.clear()
        idSeq = 0
        title = null
        newMessages.forEach { append(it) }
    }

    // ═══════════════════════ 读取 API ═══════════════════════

    /** 不可变快照（副本；调用方可自由持引用）。 */
    fun snapshot(): List<StandardMessage> = lock.read { messages.toList() }

    /** 消息条数。 */
    fun size(): Int = lock.read { messages.size }

    /** 按 id 查消息（fork 锚点定位用；未命中 = null）。 */
    fun findById(id: Long): StandardMessage? = lock.read { messages.firstOrNull { it.id == id } }

    /**
     * 恢复历史（持久化装载 / 引擎切换）：整体替换 + id 重排。
     *
     * 工具结果消息的 toolCallId 与前一条 assistant 的 toolCalls 对齐
     * 由调用方（装载源）保证——本方法不做悬挂修复（悬挂修复属引擎职责，
     * 见 StandardModeEngine.repairDanglingResults）。
     */
    fun restore(history: List<LlmMessage>) {
        replaceAll(history)
        lastActiveAt = System.currentTimeMillis()
    }

    /** 会话统计（UI 徽标 / 报告）。 */
    fun stats(tokenEstimator: (String) -> Int): StandardSessionStats = lock.read {
        var userTurns = 0
        var toolResults = 0
        var assistant = 0
        var tokens = 0
        for (m in messages) {
            when (val msg = m.message) {
                is LlmMessage.User -> {
                    userTurns++
                    tokens += tokenEstimator(msg.content)
                }
                is LlmMessage.Assistant -> {
                    assistant++
                    tokens += tokenEstimator(msg.content) +
                        msg.toolCalls.sumOf { tokenEstimator(it.arguments) }
                }
                is LlmMessage.ToolResult -> {
                    toolResults++
                    tokens += (tokenEstimator(msg.content) + 1) / 2
                }
                is LlmMessage.System -> tokens += tokenEstimator(msg.content)
            }
        }
        StandardSessionStats(
            messageCount = messages.size,
            userTurns = userTurns,
            toolResults = toolResults,
            assistantMessages = assistant,
            estimatedTokens = tokens
        )
    }

    /** 谱系深度（根 = 1）。 */
    fun lineageDepth(): Int = lineage.size

    /** 谱系副本（展示 / 日志）。 */
    fun lineageChain(): List<String> = lineage.toList()

    companion object {
        /** 标题截断长度。 */
        const val TITLE_MAX_CHARS = 48

        /** 谱系深度上限（超深 fork 由调用方拒绝——防内存膨胀）。 */
        const val MAX_LINEAGE_DEPTH = 8

        /**
         * 默认 token 估算器（会话统计口径；引擎另有 usage 真值通道）。
         *
         * 启发式：ASCII 类 ~4 chars/token；CJK（含全角标点）按 1.5 字/token
         * 折算（中文 token 密度更高，经验系数 1.5）。
         */
        fun defaultTokenEstimator(text: String): Int {
            if (text.isEmpty()) return 0
            var cjk = 0
            var other = 0
            for (ch in text) {
                if (ch.code in 0x2E80..0x9FFF ||
                    ch.code in 0xFF00..0xFFEF ||
                    ch.code in 0x3000..0x303F
                ) cjk++ else other++
            }
            return (other / 4) + ((cjk * 3) / 2)
        }
    }
}
