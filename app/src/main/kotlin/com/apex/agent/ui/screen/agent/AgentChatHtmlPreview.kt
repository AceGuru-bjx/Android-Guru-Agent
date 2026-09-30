package com.apex.agent.ui.screen.agent

import android.content.Context
import com.apex.agent.core.codetools.CodeWorkspaceRoots
import java.io.File

/**
 * # Agent Chat — HTML 产物预览路径解析（God-file 预算拆分）
 *
 * 从 [AgentChatViewModel] 抽出的 internal 扩展（模式同
 * AgentChatQuestionHandler / AgentChatHistoryController）：调用点
 * `vm.resolveHtmlPreviewPath(...)` 解析不变，VM 行数预算回归。
 */
internal fun AgentChatViewModel.resolveHtmlPreviewPath(rawPath: String): String? {
    val root = workspaceRoots.activeRoot()
        ?: File(context.filesDir, "linux/workspaces/default")
    val candidate: File = when {
        rawPath.startsWith("/workspace/", ignoreCase = true) ->
            File(root, rawPath.removePrefix("/workspace/"))
        rawPath.startsWith("/") -> File(rawPath)
        else -> File(root, rawPath)
    }
    return if (HtmlArtifactDetector.isPreviewableHostFile(candidate.absolutePath)) {
        candidate.absolutePath
    } else null
}
