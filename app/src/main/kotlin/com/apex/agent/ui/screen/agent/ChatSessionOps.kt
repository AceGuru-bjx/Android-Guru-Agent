package com.apex.agent.ui.screen.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ─────────────────────────────────────────────────────────────────────────────
// 会话操作补全（v1.4.4 #5）—— AgentChatViewModel 的 internal 扩展
//
// 覆盖此前缺失的四类用户侧操作（模式同 AgentChatHistoryController.kt：
// 依赖的成员已开放 internal，调用点无感知）：
//  - 重命名（customTitle 持久化，自动归档不再覆盖）
//  - 置顶/取消置顶（列表页置顶分组）
//  - 搜索（标题 + 消息全文，见 ChatHistoryManager.searchSessions）
//  - 导出 Markdown / 导入 JSON（跨设备迁移的轻量通道；整体备份见 backup/）
// ─────────────────────────────────────────────────────────────────────────────

/** 单会话导出/导入文件格式（v1.4.4 #5）。 */
@Serializable
data class SessionExportFile(
    val formatVersion: Int = 1,
    val exportedAt: Long,
    val summary: ChatSessionSummary,
    val messages: List<ChatHistoryMessage>
)

/** 会话操作共享 Json 实例（与 ChatHistoryManager 同配置）。 */
private val sessionOpsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * 重命名会话：IO 落盘 + 刷新列表 + 用户反馈。
 * 正在聊的会话也允许改（下一次自动归档经 customTitle 保留新标题）。
 */
internal fun AgentChatViewModel.renameChatSession(sessionId: String, newTitle: String) {
    viewModelScope.launch(Dispatchers.IO) {
        val ok = chatHistory.renameSession(sessionId, newTitle)
        _chatSessions.value = chatHistory.loadSessions()
        _uiFeedback.tryEmit(
            if (ok) strFmt(R.string.chat_session_renamed, newTitle.trim().take(40))
            else strFmt(R.string.chat_session_rename_failed)
        )
    }
}

/** 置顶/取消置顶：IO 落盘 + 刷新列表（成功静默——列表位次变化本身就是反馈）。 */
internal fun AgentChatViewModel.toggleChatSessionPin(sessionId: String) {
    viewModelScope.launch(Dispatchers.IO) {
        val current = chatHistory.loadSessions().firstOrNull { it.id == sessionId }
        val target = !(current?.pinned ?: false)
        if (chatHistory.setPinned(sessionId, target)) {
            _chatSessions.value = chatHistory.loadSessions()
        }
    }
}

/**
 * 应用会话搜索：查询非空 → 全文搜索结果替换列表；空查询 → 恢复全量。
 * 300ms 防抖由调用方（UI 侧 TextField）负责——这里只做幂等覆盖。
 */
internal fun AgentChatViewModel.applyChatHistorySearch(query: String) {
    viewModelScope.launch(Dispatchers.IO) {
        _chatSessions.value = chatHistory.searchSessions(query)
    }
}

/**
 * 导出会话为 Markdown 并发起系统分享（人类可读归档通道）。
 *
 * 导出走 cacheDir/exports/ + FileProvider（LogViewerScreen 同款范式）；
 * appContext 解包防止协程持有 Activity；导出失败经 _uiFeedback 反馈。
 */
internal fun AgentChatViewModel.exportChatSessionMarkdown(context: Context, sessionId: String) {
    val appContext = context.applicationContext
    viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val summary = chatHistory.loadSessions().firstOrNull { it.id == sessionId }
                ?: run {
                    _uiFeedback.tryEmit(strFmt(R.string.chat_session_export_not_found))
                    return@launch
                }
            val messages = chatHistory.loadMessages(sessionId)
            if (messages.isEmpty()) {
                _uiFeedback.tryEmit(strFmt(R.string.chat_session_export_not_found))
                return@launch
            }
            val markdown = buildSessionMarkdown(summary, messages)
            val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "chat-${sessionId.take(8)}-${System.currentTimeMillis()}.md")
            file.writeText(markdown)
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                appContext.startActivity(
                    Intent.createChooser(intent, strFmt(R.string.chat_session_export_chooser))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }.onFailure {
            AppLogger.instance.warn(
                category = LogCategory.UI,
                source = "ChatSessionOps",
                message = "会话导出失败: ${it.message}"
            )
            _uiFeedback.tryEmit(strFmt(R.string.chat_session_export_failed))
        }
    }
}

/**
 * 从 SAF Uri 导入会话（JSON 格式，见 [SessionExportFile]）。
 * id 冲突自动分配新 id（导入永不覆盖既有会话）；导入后出现在列表顶部。
 */
internal fun AgentChatViewModel.importChatSessionFromUri(uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: run {
                _uiFeedback.tryEmit(strFmt(R.string.chat_session_import_failed))
                return@launch
            }
            val parsed = sessionOpsJson.decodeFromString(SessionExportFile.serializer(), text)
            val importedId = chatHistory.importSession(parsed.summary, parsed.messages)
                ?: run {
                    _uiFeedback.tryEmit(strFmt(R.string.chat_session_import_failed))
                    return@launch
                }
            _chatSessions.value = chatHistory.loadSessions()
            _uiFeedback.tryEmit(strFmt(R.string.chat_session_imported))
            AppLogger.instance.info(
                category = LogCategory.UI,
                source = "ChatSessionOps",
                message = "会话导入成功: $importedId（${parsed.messages.size} 条消息）"
            )
        }.onFailure {
            AppLogger.instance.warn(
                category = LogCategory.UI,
                source = "ChatSessionOps",
                message = "会话导入失败: ${it.message}"
            )
            _uiFeedback.tryEmit(strFmt(R.string.chat_session_import_failed))
        }
    }
}

/** 会话 → Markdown（导出正文）。role 前缀 + 时间戳 + 工具名注记。 */
private fun buildSessionMarkdown(
    summary: ChatSessionSummary,
    messages: List<ChatHistoryMessage>
): String = buildString {
    val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    appendLine("# ${summary.title}")
    appendLine()
    appendLine("> ${dateFmt.format(Date(summary.createdAt))} · ${messages.size} 条消息" +
        (summary.modelId.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
    appendLine()
    messages.forEach { m ->
        val time = if (m.timestamp > 0) " `${dateFmt.format(Date(m.timestamp))}`" else ""
        when (m.role) {
            "user" -> {
                appendLine("## 🧑 User$time")
                appendLine(m.text)
                appendLine()
            }
            "agent" -> {
                appendLine("## 🤖 Agent$time")
                appendLine(m.text)
                appendLine()
            }
            "tool" -> {
                appendLine("**🔧 ${m.toolName ?: "Tool"}**")
                appendLine("```")
                appendLine(m.text)
                appendLine("```")
                appendLine()
            }
            "error" -> {
                appendLine("> ⚠️ **Error**: ${m.text}")
                appendLine()
            }
            else -> {
                appendLine("> ${m.text}")
                appendLine()
            }
        }
    }
    appendLine("---")
    appendLine("_Exported from Apex Agent · ${dateFmt.format(Date())}_")
}
