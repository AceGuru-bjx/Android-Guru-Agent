package com.apex.agent.ui.screen.about

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.BuildConfig
import com.apex.agent.R
import com.apex.agent.update.DownloadMirror
import com.apex.agent.update.MirrorPrefs
import com.apex.agent.update.MirrorSpeedProbe
import com.apex.agent.update.UpdateCheckResult
import com.apex.agent.update.UpdateChecker
import com.apex.agent.update.UpdateDownloader
import com.apex.agent.update.UpdateTarget
import com.apex.agent.update.resolveAuto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 关于页「软件更新」面板 —— 双仓库发布架构的完整客户端侧闭环。
 *
 * v1.4.3 从设置页迁移至关于页并重构 UI：
 *  1. **自动检查**：进入页面即静默检查一次（手动可重试）；
 *  2. **补丁更新主推**：from → to、体积、节省百分比一目了然（~15MB vs 全量 300MB+），
 *     `fromTag` 与本地版本一致时主推补丁，跨多版自动回退全量；
 *  3. **高速节点/镜像**：GitHub 直连 + 公共加速镜像，一键测速选优；
 *  4. **下载进度**：百分比 + 已下载字节数（系统 DownloadManager 托管，
 *     断点续传 + 通知栏进度）；
 *  5. **校验闭环**：下载完成 SHA-256 校验 → APK 直接拉起安装器 / 补丁给出
 *     针对本机的 xdelta3 命令。
 */

/** 更新检查 UI 状态机：Idle → Checking → Done(result)。 */
private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Done(val result: UpdateCheckResult) : UpdateUiState
}

/** 下载完成后的一次性事件（对话框展示 / 触发安装）。 */
private sealed interface DownloadFinished {
    /** 补丁就绪：路径 + 可复制的应用命令。 */
    data class PatchReady(val file: File, val command: String) : DownloadFinished

    /** APK 就绪但安装器未能拉起（罕见环境）—— 展示路径让用户手动装。 */
    data class ApkReady(val file: File) : DownloadFinished

    /** SHA-256 校验失败 —— 文件不可用。 */
    data class VerifyFailed(val fileName: String) : DownloadFinished
}

@Composable
internal fun UpdatePanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checker = remember { UpdateChecker() }
    val probe = remember { MirrorSpeedProbe() }
    val downloader = remember { UpdateDownloader(context) }

    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var selectedMirror by remember { mutableStateOf(MirrorPrefs.load(context)) }
    var speeds by remember { mutableStateOf<Map<DownloadMirror, Long>>(emptyMap()) }
    var probing by remember { mutableStateOf(false) }
    var showMirrorDialog by remember { mutableStateOf(false) }
    var activeDownload by remember { mutableStateOf<UpdateDownloader.Enqueued?>(null) }
    var downloadPercent by remember { mutableStateOf(0) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var finished by remember { mutableStateOf<DownloadFinished?>(null) }

    // Toast 文案上提到组合层（非 Compose lambda 中使用）
    val enqueueFailedHint = stringResource(R.string.settings_about_update_enqueue_failed)
    val startedHintFmt = stringResource(R.string.settings_about_update_download_started)

    fun triggerCheck() {
        if (updateState == UpdateUiState.Checking) return
        updateState = UpdateUiState.Checking
        scope.launch {
            updateState = UpdateUiState.Done(checker.check(BuildConfig.VERSION_CODE))
        }
    }

    // ── 进入页面自动静默检查一次（v1.4.3：不必再手动点第一次）────────────────
    LaunchedEffect(Unit) { triggerCheck() }

    // ── 下载完成广播：校验 SHA-256 → 补丁出说明 / APK 拉起安装器 ─────────────
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                val active = activeDownload ?: return
                if (completedId != active.id) return
                scope.launch {
                    val file = withContext(Dispatchers.IO) {
                        downloader.localFile(active.fileName).takeIf { it.exists() }
                    }
                    val verified = file != null && withContext(Dispatchers.IO) {
                        downloader.verifySha256(file, active.expectedSha256)
                    }
                    finished = when {
                        file == null || !verified -> DownloadFinished.VerifyFailed(active.fileName)
                        active.isPatch -> DownloadFinished.PatchReady(
                            file = file,
                            command = buildString {
                                append("xdelta3 -d -s ")
                                append(context.applicationInfo.sourceDir)
                                append(" ")
                                append(file.absolutePath)
                                append(" ")
                                append(file.parent)
                                append("/ApexAgent-patched.apk")
                            }
                        )
                        else -> {
                            // APK 校验通过直接拉起安装器；拉不起则回退路径提示
                            if (downloader.installApk(file)) null
                            else DownloadFinished.ApkReady(file)
                        }
                    }
                    activeDownload = null
                }
            }
        }
        runCatching {
            context.registerReceiver(
                receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // ── 下载进度轮询（广播只管终点，进度条靠轮询 800ms 一拍）──────────────────
    LaunchedEffect(activeDownload?.id) {
        val active = activeDownload ?: return@LaunchedEffect
        while (true) {
            val (percent, bytes) = downloader.progress(active.id)
            downloadPercent = percent
            downloadedBytes = bytes
            if (activeDownload?.id != active.id) break
            delay(800)
        }
    }

    // ── 发起下载（镜像解析 → URL 改写 → DownloadManager 入队）──────────────
    // 注意：patch（UpdatePatchAsset）与 full（UpdateAsset）经 [UpdateTarget]
    // 统一接待 —— 否则 elvis 的公共父类型坍缩为 Any，.url/.sha256 全部失联。
    fun startDownload(usePatch: Boolean) {
        val result = (updateState as? UpdateUiState.Done)?.result
        val manifest = (result as? UpdateCheckResult.Available)?.latest ?: return
        val patch = checker.preferredPatch(manifest, BuildConfig.VERSION_NAME)
        val full = checker.preferredAsset(manifest)
        val asset: UpdateTarget = (if (usePatch) patch else null) ?: full ?: return
        val fileName = asset.url.substringAfterLast('/')
        val title = if (usePatch) {
            "Apex Agent ${manifest.versionName} patch"
        } else {
            "Apex Agent v${manifest.versionName}"
        }
        scope.launch {
            // AUTO 档：无测速数据先现场探测一轮，再取最快节点
            val resolved = if (selectedMirror == DownloadMirror.AUTO && speeds.isEmpty()) {
                speeds = probe.probeAll(asset.url)
                resolveAuto(speeds)
            } else if (selectedMirror == DownloadMirror.AUTO) {
                resolveAuto(speeds)
            } else selectedMirror
            val finalUrl = resolved.rewrite(asset.url)
            val enqueued = withContext(Dispatchers.IO) {
                downloader.enqueue(finalUrl, fileName, title, usePatch, asset.sha256)
            }
            if (enqueued != null) {
                downloadPercent = 0
                downloadedBytes = 0
                activeDownload = enqueued
                Toast.makeText(
                    context, startedHintFmt.format(fileName), Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(context, enqueueFailedHint, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ═══ 面板主体 ═══
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── 状态头：图标井 + 标题/当前版本 + 状态徽章 ──────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Box(
                        Modifier.size(38.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.SystemUpdateAlt,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.about_section_update),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(
                            R.string.about_update_current,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                when (val state = updateState) {
                    is UpdateUiState.Checking -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )

                    is UpdateUiState.Done -> when (state.result) {
                        is UpdateCheckResult.UpToDate -> Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(20.dp)
                        )

                        is UpdateCheckResult.Available -> StatusBadge(
                            stringResource(R.string.about_update_new_badge)
                        )

                        is UpdateCheckResult.Failed -> Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    UpdateUiState.Idle -> Unit
                }
            }

            // ── 状态主体 ──────────────────────────────────────────────────────
            when (val state = updateState) {
                UpdateUiState.Idle -> {
                    Text(
                        stringResource(R.string.about_update_auto_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    OutlinedButton(
                        onClick = ::triggerCheck,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_about_update_check))
                    }
                }

                UpdateUiState.Checking -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.settings_about_update_checking),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                is UpdateUiState.Done -> when (val result = state.result) {
                    is UpdateCheckResult.UpToDate -> Text(
                        stringResource(
                            R.string.settings_about_update_latest, result.latest.versionName
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    is UpdateCheckResult.Failed -> {
                        Text(
                            stringResource(
                                R.string.settings_about_update_failed, result.reason
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        OutlinedButton(onClick = ::triggerCheck) {
                            Text(stringResource(R.string.about_update_retry))
                        }
                    }

                    is UpdateCheckResult.Available -> {
                        val manifest = result.latest
                        val patch = checker.preferredPatch(manifest, BuildConfig.VERSION_NAME)
                        val full = checker.preferredAsset(manifest)

                        // 新版本横幅：大号版本号 + NEW 徽章
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.60f)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "v${manifest.versionName}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        stringResource(
                                            R.string.settings_about_update_available,
                                            manifest.versionName
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                StatusBadge(stringResource(R.string.about_update_new_badge))
                            }
                        }

                        // 下载源（高速节点/镜像）选择行
                        MirrorSelectionRow(
                            selected = selectedMirror,
                            speeds = speeds,
                            onClick = { showMirrorDialog = true }
                        )

                        // 下载动作区（下载中显示进度，隐藏按钮防重复）
                        val active = activeDownload
                        if (active != null) {
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                LinearProgressIndicator(
                                    progress = { downloadPercent / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    stringResource(
                                        R.string.about_update_progress_mb,
                                        downloadPercent,
                                        formatMb(downloadedBytes)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Text(
                                    active.fileName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        } else {
                            // 补丁可用 → 主推推荐卡；不可用 → 提示原因
                            if (patch != null && full != null) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                                        .copy(alpha = 0.55f)
                                ) {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            stringResource(R.string.about_update_patch_recommended),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        val saved =
                                            100 - (patch.sizeBytes * 100 / full.sizeBytes).toInt()
                                        Text(
                                            stringResource(
                                                R.string.settings_about_update_patch_hint,
                                                patch.fromTag.removePrefix("v"),
                                                manifest.versionName,
                                                saved
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                        Button(
                                            onClick = { startDownload(usePatch = true) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(
                                                Icons.Outlined.Download,
                                                contentDescription = null
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                stringResource(
                                                    R.string.settings_about_update_patch,
                                                    formatMb(patch.sizeBytes)
                                                )
                                            )
                                        }
                                    }
                                }
                            } else if (manifest.patch?.arm64 != null ||
                                manifest.patch?.universal != null
                            ) {
                                // 有补丁但 fromTag 不匹配本地版本（跨多版）
                                Text(
                                    stringResource(
                                        R.string.settings_about_update_patch_inapplicable,
                                        manifest.patch?.arm64?.fromTag
                                            ?: manifest.patch?.universal?.fromTag.orEmpty(),
                                        BuildConfig.VERSION_NAME
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }

                            // 全量安装包（永久兜底路径）
                            if (full != null) {
                                OutlinedButton(
                                    onClick = { startDownload(usePatch = false) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        Icons.Outlined.Download,
                                        contentDescription = null
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(
                                            R.string.settings_about_update_full,
                                            formatMb(full.sizeBytes)
                                        )
                                    )
                                }
                            } else {
                                // 清单缺资产（schema 异常）：回退发布页外链
                                val noBrowserHint =
                                    stringResource(R.string.settings_about_no_browser)
                                manifest.releasePage?.let { page ->
                                    Button(
                                        onClick = { openUrl(context, page, noBrowserHint) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.settings_about_update_download
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 镜像选择对话框（打开自动触发一轮测速）────────────────────────────────
    if (showMirrorDialog) {
        LaunchedEffect(Unit) {
            val result = (updateState as? UpdateUiState.Done)?.result
            val manifest = (result as? UpdateCheckResult.Available)?.latest
            val probeUrl = manifest?.let { checker.preferredAsset(it)?.url }
                ?: "https://github.com/AceGuru-mjh/Android-Guru-Agent-Release/releases/latest"
            probing = true
            speeds = probe.probeAll(probeUrl)
            probing = false
        }
        MirrorSelectionDialog(
            selected = selectedMirror,
            speeds = speeds,
            probing = probing,
            onSelect = { mirror ->
                selectedMirror = mirror
                MirrorPrefs.save(context, mirror)
                showMirrorDialog = false
            },
            onDismiss = { showMirrorDialog = false }
        )
    }

    // ── 下载完成事件对话框 ──────────────────────────────────────────────────
    val clipboard = LocalClipboardManager.current
    when (val event = finished) {
        is DownloadFinished.PatchReady -> {
            val copiedHint = stringResource(R.string.settings_about_update_copied)
            AlertDialog(
                onDismissRequest = { finished = null },
                title = { Text(stringResource(R.string.settings_about_update_patch_done_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                R.string.settings_about_update_patch_done_saved,
                                event.file.absolutePath
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.settings_about_update_patch_done_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Surface(
                            tonalElevation = 2.dp,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                event.command,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(event.command))
                        Toast.makeText(context, copiedHint, Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null,
                            Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.settings_about_update_copy_cmd))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { finished = null }) {
                        Text(stringResource(R.string.settings_about_update_close))
                    }
                }
            )
        }

        is DownloadFinished.ApkReady -> AlertDialog(
            onDismissRequest = { finished = null },
            title = { Text(stringResource(R.string.settings_about_update_apk_done_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_about_update_patch_done_saved, event.file.absolutePath
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = { finished = null }) {
                    Text(stringResource(R.string.settings_about_update_close))
                }
            }
        )

        is DownloadFinished.VerifyFailed -> AlertDialog(
            onDismissRequest = { finished = null },
            title = { Text(stringResource(R.string.settings_about_update_verify_failed_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_about_update_verify_failed_body, event.fileName
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            },
            confirmButton = {
                TextButton(onClick = { finished = null }) {
                    Text(stringResource(R.string.settings_about_update_close))
                }
            }
        )

        null -> Unit
    }
}

// ── 小控件 ──────────────────────────────────────────────────────────────────

/** 主色实底小徽章（NEW）。 */
@Composable
private fun StatusBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primary
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** 当前下载源展示行：镜像名 + 延迟徽章（AUTO 显示已解析的最快节点）。 */
@Composable
private fun MirrorSelectionRow(
    selected: DownloadMirror,
    speeds: Map<DownloadMirror, Long>,
    onClick: () -> Unit
) {
    val fastest = speeds.minByOrNull { it.value }?.key
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.60f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Outlined.Speed,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_about_update_source),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                val label = when (selected) {
                    DownloadMirror.AUTO -> {
                        val target = fastest?.let { mirrorLabel(it) }
                            ?: stringResource(R.string.settings_about_update_source_direct)
                        stringResource(R.string.settings_about_update_source_auto) + " · $target"
                    }
                    else -> mirrorLabel(selected)
                }
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
            // 右侧：AUTO 且有测速 → 「最快」；手选 → 延迟毫秒
            if (selected == DownloadMirror.AUTO && fastest != null) {
                Text(
                    stringResource(R.string.settings_about_update_fastest),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (selected != DownloadMirror.AUTO) {
                speeds[selected]?.let { latency ->
                    Text(
                        stringResource(R.string.settings_about_update_ms, latency),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 镜像选择对话框：单选 + 各节点延迟 + 打开即测速。 */
@Composable
private fun MirrorSelectionDialog(
    selected: DownloadMirror,
    speeds: Map<DownloadMirror, Long>,
    probing: Boolean,
    onSelect: (DownloadMirror) -> Unit,
    onDismiss: () -> Unit
) {
    val fastest = speeds.minByOrNull { it.value }?.key
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_about_update_source_title)) },
        // text 必须传 lambda：直接传 Column(...) 调用会得到 Unit，
        // 导致 AlertDialog 重载解析失败并级联报出 title/confirmButton 处的
        // 假错误（@Composable invocations can only happen…）。
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(R.string.settings_about_update_mirror_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                HorizontalDivider(Modifier.padding(vertical = 6.dp))

                val options = listOf(DownloadMirror.AUTO) + DownloadMirror.NODES
                options.forEach { mirror ->
                    val latency = speeds[mirror]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mirror) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mirror == selected, onClick = { onSelect(mirror) })
                        Column(Modifier.weight(1f)) {
                            Text(mirrorLabel(mirror), style = MaterialTheme.typography.bodyMedium)
                            if (mirror == DownloadMirror.AUTO) {
                                Text(
                                    stringResource(R.string.settings_about_update_mirror_auto_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                        // 延迟徽章：测速中 spinner 文案 / 毫秒 / 不通
                        val latencyText = when {
                            probing && mirror != DownloadMirror.AUTO ->
                                stringResource(R.string.settings_about_update_speedtesting)
                            latency != null ->
                                stringResource(R.string.settings_about_update_ms, latency)
                            mirror != DownloadMirror.AUTO ->
                                stringResource(R.string.settings_about_update_unreachable)
                            else -> ""
                        }
                        if (latencyText.isNotEmpty()) {
                            Text(
                                latencyText,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (mirror == fastest) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                }
                            )
                        }
                        if (mirror == fastest) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.settings_about_update_fastest),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_about_update_close))
            }
        }
    )
}

/** 镜像显示名（加速站以其域名命名，免翻译歧义）。 */
@Composable
private fun mirrorLabel(mirror: DownloadMirror): String = when (mirror) {
    DownloadMirror.AUTO -> stringResource(R.string.settings_about_update_source_auto)
    DownloadMirror.DIRECT -> stringResource(R.string.settings_about_update_source_direct)
    DownloadMirror.GHFAST -> "ghfast.top"
    DownloadMirror.GHPROXY_NET -> "ghproxy.net"
    DownloadMirror.GHPROXY_COM -> "gh-proxy.com"
}

/** 字节数 → 「318 MB」式人类可读体积。 */
private fun formatMb(bytes: Long): String = "${bytes / 1024 / 1024} MB"
