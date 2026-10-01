package com.apex.agent.ui.screen.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// 历史对话持久化（ChatHistoryManager）
//
// 存储：SharedPreferences「apex_chat_history」——
//  - key "index"     → List<ChatSessionSummary> JSON（会话索引，按 updatedAt 倒序读出）
//  - key "msg_{id}"  → 该会话的 List<ChatHistoryMessage> JSON
//
// 所有方法均为同步阻塞 IO，调用方（ViewModel / 控制器扩展）必须切
// Dispatchers.IO —— 与 SharedPrefsConversationMemory 的进程内缓存策略
// 不同：历史会话是低频访问路径（打开列表 / 恢复 / 删除），无需常驻缓存，
// 直接读盘最简单也最不易出现缓存/磁盘不一致。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 历史对话仓库。会话在 Agent 消息流变化时由
 * [AgentChatHistoryController] 自动归档；切新会话 / 恢复 / 删除经同一入口。
 *
 * 并发模型（P2 修复）：归档（防抖 IO 协程）/ 删除（列表 IO 协程）/ 清空
 * 会在 ViewModel 作用域内并发触发 —— 旧实现「读索引→合并→写回」无锁，
 * 交错时会互相覆盖丢更新；删除后在途归档还能把已删会话复活。
 * 现全部写路径经 [ioLock] 串行化，并以墓碑集合拦截迟到的归档。
 */
@Singleton
class ChatHistoryManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val indexSerializer = ListSerializer(ChatSessionSummary.serializer())
    private val messagesSerializer = ListSerializer(ChatHistoryMessage.serializer())

    /** 全部写路径的串行锁（方法均为同步阻塞 IO，监视器锁最贴合调用约定）。 */
    private val ioLock = Any()

    /**
     * 本进程内已删除会话的墓碑：删除后仍在途的防抖归档照常抵达
     * [saveSession] —— 命中墓碑即丢弃，防「删除后 800ms 内会话复活」。
     * 恢复路径只会恢复索引内（未删）会话，与墓碑无交集。
     */
    private val tombstones = HashSet<String>()

    /** 墓碑容量上限：超出即整体重置 —— 超过窗口的在途归档早已落盘，
    // 清空安全；防止 clearAll（一次登记最多 100 个 UUID）后长期进程内存缓慢累积。 */
    private fun rememberTombstone(id: String) {
        if (tombstones.size >= MAX_TOMBSTONES) tombstones.clear()
        tombstones.add(id)
    }

    /** 全部会话摘要，按最近更新倒序。 */
    fun loadSessions(): List<ChatSessionSummary> {
        val raw = prefs.getString(KEY_INDEX, null) ?: return emptyList()
        val parsed = runCatching { json.decodeFromString(indexSerializer, raw) }
            .getOrDefault(emptyList())
        return parsed.sortedByDescending { it.updatedAt }
    }

    /** 读取指定会话的消息（空 = 无此会话或数据损坏）。 */
    fun loadMessages(sessionId: String): List<ChatHistoryMessage> {
        val raw = prefs.getString(keyMessages(sessionId), null) ?: return emptyList()
        return runCatching { json.decodeFromString(messagesSerializer, raw) }
            .getOrDefault(emptyList())
    }

    /** 会话创建时间（恢复会话时延续原 createdAt，避免排序漂移）。 */
    fun sessionCreatedAt(sessionId: String): Long? =
        loadSessions().firstOrNull { it.id == sessionId }?.createdAt

    /** upsert：索引合并 + 消息全量覆写（写路径串行 + 墓碑拦截）。 */
    fun saveSession(summary: ChatSessionSummary, messages: List<ChatHistoryMessage>) {
        synchronized(ioLock) {
            // 已删除会话的迟到归档：直接丢弃（deleteSession 后在途的防抖快照）
            if (summary.id in tombstones) return
            val existing = loadSessions()
            val sorted = (existing.filter { it.id != summary.id } + summary)
                .sortedByDescending { it.updatedAt }
            // 会话数量封顶：最近 100 个 —— 历史无限增长会拖慢索引反序列化
            val merged = sorted.take(MAX_SESSIONS)
            // 被裁掉的旧会话：从索引消失的同时删除其 msg_ 键，防孤儿数据
            // 单向膨胀（旧实现只重写索引，被裁会话的消息键永久残留）。
            val keptIds = merged.map { it.id }.toSet()
            val editor = prefs.edit()
                .putString(KEY_INDEX, json.encodeToString(indexSerializer, merged))
                .putString(keyMessages(summary.id), json.encodeToString(messagesSerializer, messages))
            sorted.filter { it.id !in keptIds }.forEach { editor.remove(keyMessages(it.id)) }
            editor.apply()
        }
    }

    /** 删除单个会话（索引 + 消息；登记墓碑拦截在途归档）。 */
    fun deleteSession(sessionId: String) {
        synchronized(ioLock) {
            rememberTombstone(sessionId)
            prefs.edit()
                .putString(KEY_INDEX, json.encodeToString(indexSerializer, loadSessions().filter { it.id != sessionId }))
                .remove(keyMessages(sessionId))
                .apply()
        }
    }

    // ═══ v1.4.4 #5：会话操作补全 —— 重命名 / 置顶 / 搜索 / 导入 ═══

    /**
     * 重命名会话（v1.4.4 #5）：置 customTitle = true，此后自动归档不再覆盖标题。
     * 不存在/空白标题 → false（调用方转用户反馈）。
     */
    fun renameSession(sessionId: String, newTitle: String): Boolean {
        val title = newTitle.trim().take(TITLE_MAX_LENGTH)
        if (title.isEmpty()) return false
        synchronized(ioLock) {
            val sessions = loadSessions()
            val target = sessions.firstOrNull { it.id == sessionId } ?: return false
            val updated = sessions.map {
                if (it.id == sessionId) it.copy(title = title, customTitle = true) else it
            }
            prefs.edit()
                .putString(KEY_INDEX, json.encodeToString(indexSerializer, updated))
                .apply()
        }
        return true
    }

    /**
     * 置顶/取消置顶（v1.4.4 #5）：仅改索引标记，不动消息与 updatedAt。
     * 返回操作后的最新状态（false = 会话不存在）。
     */
    fun setPinned(sessionId: String, pinned: Boolean): Boolean {
        synchronized(ioLock) {
            val sessions = loadSessions()
            if (sessions.none { it.id == sessionId }) return false
            val updated = sessions.map {
                if (it.id == sessionId) it.copy(pinned = pinned) else it
            }
            prefs.edit()
                .putString(KEY_INDEX, json.encodeToString(indexSerializer, updated))
                .apply()
        }
        return true
    }

    /**
     * 搜索会话（v1.4.4 #5）：标题匹配（本地子串）+ 消息全文匹配（逐会话读盘）。
     * 大小写不敏感；空查询 → 全量返回（调用方语义：清空搜索即恢复全列表）。
     * 全文扫描最多读 [SEARCH_SCAN_LIMIT] 个会话的消息（防止 100 会话全量 JSON
     * 反序列化卡住 IO 线程太久——最近的会话优先，符合“搜最近的”直觉）。
     */
    fun searchSessions(query: String): List<ChatSessionSummary> {
        val q = query.trim()
        val sessions = loadSessions()
        if (q.isEmpty()) return sessions
        val lower = q.lowercase()
        val titleHits = sessions.filter { it.title.lowercase().contains(lower) }.toMutableList()
        // 全文扫描：跳过已命中的（标题命中不必再读消息体）
        val candidates = sessions
            .filter { it.id !in titleHits.map { s -> s.id } }
            .take(SEARCH_SCAN_LIMIT)
        val contentHits = candidates.filter { s ->
            loadMessages(s.id).any { it.text.lowercase().contains(lower) }
        }
        // 置顶优先 + 最近更新在前：与列表页展示序一致，搜索结果位置可预测
        return (titleHits + contentHits)
            .distinctBy { it.id }
            .sortedWith(compareByDescending<ChatSessionSummary> { it.pinned }.thenByDescending { it.updatedAt })
    }

    /**
     * 导入会话（v1.4.4 #5，分享/备份场景）：id 冲突时分配新 id 重写（导入永不
     * 覆盖已有会话——覆盖语义留给备份恢复路径，那是显式选择的场景）。
     * @return 实际入库的会话 id（null = 失败）。
     */
    fun importSession(summary: ChatSessionSummary, messages: List<ChatHistoryMessage>): String? {
        if (messages.isEmpty()) return null
        val existingIds = loadSessions().map { it.id }.toSet()
        val finalId = if (summary.id in existingIds) {
            UUID.randomUUID().toString()
        } else {
            summary.id
        }
        val now = System.currentTimeMillis()
        saveSession(
            summary = summary.copy(
                id = finalId,
                // 导入的旧时间戳可能来自另一台设备，统一“入康新鲜度”为当前时间，
                // 保证导入会话出现在列表顶部可立即看到；createdAt 保留原值供排序展示。
                updatedAt = now,
                messageCount = messages.size
            ),
            messages = messages
        )
        return finalId
    }

    /** 清空全部历史会话（全键清扫：索引外的孤儿 msg_ 键一并回收）。 */
    fun clearAll() {
        synchronized(ioLock) {
            loadSessions().forEach { rememberTombstone(it.id) }
            val editor = prefs.edit().remove(KEY_INDEX)
            // 只按当前索引删键回收不了历史孤儿（被截断出索引的会话）——
            // 直接按 msg_ 前缀全键清扫，一次性兜底。
            prefs.all.keys.filter { it.startsWith(KEY_MSG_PREFIX) }.forEach { editor.remove(it) }
            editor.apply()
        }
    }

    private fun keyMessages(sessionId: String) = "$KEY_MSG_PREFIX$sessionId"

    private companion object {
        const val PREFS_NAME = "apex_chat_history"
        const val KEY_INDEX = "index"
        const val KEY_MSG_PREFIX = "msg_"
        const val MAX_SESSIONS = 100
        const val MAX_TOMBSTONES = 256
        /** 重命名标题上限（与 historyTitle 的 40 字截断对齐，给自定义命名留余量）。 */
        const val TITLE_MAX_LENGTH = 40
        /** 全文搜索最多扫描的会话数（见 [searchSessions]）。 */
        const val SEARCH_SCAN_LIMIT = 60
    }
}
