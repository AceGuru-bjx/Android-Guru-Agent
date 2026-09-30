package com.apex.agent.ui.screen.agent

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 历史对话抽屉（ModalBottomSheet）—— Agent 页顶栏「历史」按钮唤起。
 *
 * v1.4.4 #5 全面升级：
 * - **搜索**：标题 + 消息全文（300ms 防抖，IO 线程扫描）；
 * - **置顶**：置顶会话独立分组展示在最上方（pinned 标记跨归档保留）；
 * - **重命名**：MoreVert 菜单（customTitle 持久化，自动归档不再覆盖）；
 * - **导出**：单会话导出 Markdown 发起系统分享；
 * - **导入**：从 SAF 选取会话 JSON 导入（id 冲突自动新建）；
 * - 无障碍：操作按钮触达目标回归 48dp（旧 36dp 违反最小触达规范）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistorySheet(
    sessions: List<ChatSessionSummary>,
    currentSessionId: String?,
    onRestore: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
    onRename: (String, String) -> Unit,
    onTogglePin: (String) -> Unit,
    onExport: (Context, String) -> Unit,
    onImport: (Uri) -> Unit,
    onSearch: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var deleteTarget by remember { mutableStateOf<ChatSessionSummary?>(null) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ChatSessionSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current

    // SAF 导入选择器：application/json（SessionExportFile 格式）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImport) }

    // 搜索防抖：停顿 300ms 才触发全文扫描（每键一次 IO 读盘太重）
    LaunchedEffect(searchQuery) {
        delay(300)
        onSearch(searchQuery)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .padding(horizontal = 16.dp)
        ) {
            // ── 头部：标题 + 导入 + 清空全部 ──
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.chat_history_title, sessions.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (sessions.isNotEmpty()) {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                        Icon(
                            Icons.Outlined.UploadFile,
                            contentDescription = stringResource(R.string.chat_session_import),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { showClearAllConfirm = true }) {
                        Text(stringResource(R.string.chat_clear_all), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.chat_history_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(8.dp))

            // ── 搜索框（空列表也保留：导入后立即可搜）──
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.chat_session_search_hint)) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))

            if (sessions.isEmpty()) {
                // 空态：区分「搜索无结果」与「本来就没有」
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Chat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        stringResource(
                            if (searchQuery.isNotBlank()) R.string.chat_session_search_empty
                            else R.string.chat_history_empty
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                val pinned = sessions.filter { it.pinned }
                val normal = sessions.filter { !it.pinned }
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (pinned.isNotEmpty()) {
                        item(key = "pinned-header") {
                            PinnedHeader()
                        }
                        items(pinned, key = { it.id }) { session ->
                            ChatHistoryRow(
                                session = session,
                                isCurrent = session.id == currentSessionId,
                                onClick = {
                                    onRestore(session.id)
                                    onDismiss()
                                },
                                onRename = { renameTarget = session; renameText = session.title },
                                onTogglePin = { onTogglePin(session.id) },
                                onExport = { onExport(context, session.id) },
                                onDelete = { deleteTarget = session }
                            )
                        }
                        item(key = "normal-header") {
                            Text(
                                stringResource(R.string.chat_session_recent_group),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    items(normal, key = { it.id }) { session ->
                        ChatHistoryRow(
                            session = session,
                            isCurrent = session.id == currentSessionId,
                            onClick = {
                                onRestore(session.id)
                                onDismiss()
                            },
                            onRename = { renameTarget = session; renameText = session.title },
                            onTogglePin = { onTogglePin(session.id) },
                            onExport = { onExport(context, session.id) },
                            onDelete = { deleteTarget = session }
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    // 重命名对话框
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.chat_session_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(40) },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_session_rename_hint)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) onRename(target.id, renameText)
                        renameTarget = null
                    },
                    enabled = renameText.isNotBlank()
                ) { Text(stringResource(R.string.chat_session_rename_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.chat_cancel)) }
            }
        )
    }

    // 单条删除确认（破坏性操作；i18n：标题/正文组合内取词）
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.chat_delete_session_title)) },
            text = { Text(stringResource(R.string.chat_delete_session_text, target.title)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    deleteTarget = null
                }) { Text(stringResource(R.string.chat_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.chat_cancel)) } }
        )
    }

    // 清空全部确认
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text(stringResource(R.string.chat_clear_history_title)) },
            text = { Text(stringResource(R.string.chat_clear_history_text, sessions.size)) },
            confirmButton = {
                TextButton(onClick = {
                    onClearAll()
                    showClearAllConfirm = false
                }) { Text(stringResource(R.string.chat_delete_all), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) { Text(stringResource(R.string.chat_cancel)) }
            }
        )
    }
}

/** 置顶分组头：图钉图标 + 标签。 */
@Composable
private fun PinnedHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Icon(
            Icons.Filled.PushPin,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.chat_session_pinned_group),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 单条会话行：标题 + 元信息 + 置顶钮 + 更多菜单（重命名/导出/删除）；点击整行恢复。
 *
 * 触达目标：IconButton 默认 48dp 最小触达（旧实现 Modifier.size(36.dp) 把触达
 * 压到规范之下 —— v1.4.4 #8 无障碍修复，去掉显式尺寸回归系统默认）。
 */
@Composable
private fun ChatHistoryRow(
    session: ChatSessionSummary,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                // i18n：buildString 非组合上下文，元信息片段在组合内预取
                val timeLabel = relativeTime(session.updatedAt)
                val msgCountLabel = stringResource(R.string.chat_msg_count, session.messageCount)
                val currentLabel = stringResource(R.string.chat_current_session)
                Text(
                    buildString {
                        append(timeLabel)
                        append(" · $msgCountLabel")
                        if (session.modelId.isNotBlank()) append(" · ${session.modelId}")
                        if (isCurrent) append(" · $currentLabel")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 置顶切换（当前态即视觉反馈：实心图钉 = 已置顶）
            IconButton(onClick = onTogglePin) {
                Icon(
                    if (session.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = stringResource(
                        if (session.pinned) R.string.chat_session_unpin
                        else R.string.chat_session_pin
                    ),
                    tint = if (session.pinned) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 更多操作：重命名 / 导出 / 删除（低频操作收进菜单，行内只留高频）
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.chat_session_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_session_rename)) },
                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                    onClick = { menuOpen = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_session_export)) },
                    leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                    onClick = { menuOpen = false; onExport() }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.chat_delete_session_title),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = { menuOpen = false; onDelete() }
                )
            }
        }
    }
}

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / 昨天 / M月d日（跨年带年份）。 */
@Composable
internal fun relativeTime(timestamp: Long): String {
    // i18n：相对时间在组合内取词（本函数仅 ChatHistorySheet 使用，无其它非组合调用方）
    if (timestamp <= 0) return ""
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> stringResource(R.string.chat_time_just_now)
        diff < 3_600_000L -> stringResource(R.string.chat_time_minutes_ago, diff / 60_000L)
        diff < 86_400_000L -> stringResource(R.string.chat_time_hours_ago, diff / 3_600_000L)
        diff < 172_800_000L -> stringResource(R.string.chat_time_yesterday)
        else -> {
            val now = java.util.Calendar.getInstance()
            val then = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
            val pattern = if (now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)) {
                stringResource(R.string.chat_date_pattern_this_year)
            } else {
                stringResource(R.string.chat_date_pattern_full)
            }
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
        }
    }
}
