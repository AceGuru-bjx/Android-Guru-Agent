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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.BuildConfig
import com.apex.agent.R
import com.apex.agent.update.DownloadMirror
import com.apex.agent.update.MirrorPrefs
import com.apex.agent.update.MirrorSpeedProbe
import com.apex.agent.update.PatchIndex
import com.apex.agent.update.PatchUpdateEngine
import com.apex.agent.update.UpdateCenter
import com.apex.agent.update.UpdateCheckResult
import com.apex.agent.update.UpdateChecker
import com.apex.agent.update.UpdateDownloader
import com.apex.agent.update.UpdateManifest
import com.apex.agent.update.UpdateTarget
import com.apex.agent.update.resolveAuto
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 *  2. **补丁更新主推**：from → to、体积、节省百分比一目了然（~15MB vs 全量 300MB+）；
 *  3. **高速节点/镜像**：GitHub 直连 + 公共加速镜像，一键测速选优；
 *  4. **下载进度**：百分比 + 已下载字节数（系统 DownloadManager 托管，
 *     断点续传 + 通知栏进度）；
 *  5. **校验闭环**：下载完成 SHA-256 校验 → APK 直接拉起安装器。
 *
 * v1.4.5 增量更新全自动化 + 跨版本链：
 *  1. **零命令行**：补丁链下载完成后由 [PatchUpdateEngine] 在应用内
 *     直接合成新 APK（[VcdiffDecoder] 纯 Kotlin VCDIFF 解码）并自动拉起
 *     安装器 —— 旧版「复制 xdelta3 命令去终端手打」的路径彻底退役；
 *  2. **跨版本链**：拉取发布仓库 patches.json 全量索引（[PatchIndex]），
 *     跨任意多个小版本自动解析补丁链逐段应用，不再回退 300~900MB 全量；
 *  3. **双保险**：逐段补丁 SHA-256 + 逐窗 Adler32 + 终局产物 SHA-256；
 *  4. **失败自愈**：增量任一环节失败 → 对话框出「重试 / 改用全量」双路，
 *     更新链路永不因增量故障而卡死。
 */

/** 更新检查 UI 状态机：Idle → Checking → Done(result)。 */
private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Done(val result: UpdateCheckResult) : UpdateUiState
}

/** 下载完成后的一次性事件（全量包路径；增量路径走 PatchUpdateEngine.State）。 */
private sealed interface DownloadFinished {

    /** 全量 APK 下载完成且校验通过 —— 弹「立即安装」确认框。 */
    data class ApkReady(val file: File) : DownloadFinished

    /** 安装器未能拉起（罕见环境）—— 展示路径让用户手动装。 */
    data class InstallFailed(val file: File) : DownloadFinished

    /** SHA-256 校验失败 —— 文件不可用。 */
    data class VerifyFailed(val fileName: String) : DownloadFinished
}

@Composable
internal fun UpdatePanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // ── v1.4.5：更新中枢接管检查与增量流水线（应用级 —— 离开本页继续跑，
    //    中断后从断点续传）。本面板只是中枢的一块仪表盘 + 全量包本地路径。──
    val center = UpdateCenter
    val checker = center.checker
    val probe = remember { MirrorSpeedProbe() }
    val downloader = remember { UpdateDownloader(context) }

    // 中枢状态 → 组合（单一真相源：浮窗与本页共享同一份检查结果）
    val checkStateRaw by center.checkState.collectAsStateWithLifecycle()
    val patchIndex by center.patchIndex.collectAsStateWithLifecycle()
    val patchFlow by center.patchState.collectAsStateWithLifecycle()
    val updateState: UpdateUiState = when (val s = checkStateRaw) {
        UpdateCenter.CheckState.Idle -> UpdateUiState.Idle
        UpdateCenter.CheckState.Checking -> UpdateUiState.Checking
        is UpdateCenter.CheckState.Done -> UpdateUiState.Done(s.result)
    }

    var selectedMirror by remember { mutableStateOf(MirrorPrefs.load(context)) }
    var speeds by remember { mutableStateOf<Map<DownloadMirror, Long>>(emptyMap()) }
    var probing by remember { mutableStateOf(false) }
    var showMirrorDialog by remember { mutableStateOf(false) }
    var activeDownload by remember { mutableStateOf<UpdateDownloader.Enqueued?>(null) }
    var downloadPercent by remember { mutableStateOf(0) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var finished by remember { mutableStateOf<DownloadFinished?>(null) }

    // 就绪产物重入口：「稍后安装」之后（含离开页面再回来）仍可一键安装，
    // 不必重新下载/合成 —— readyPatch 为引擎合成的增量包，readyFull 为已
    // 下载的全量包；均在清单就绪后于后台扫描 + 指纹复核。
    var readyPatch by remember { mutableStateOf<PatchUpdateEngine.State.Ready?>(null) }
    var readyFull by remember { mutableStateOf<File?>(null) }

    // Toast 文案上提到组合层（非 Compose lambda 中使用）
    val enqueueFailedHint = stringResource(R.string.settings_about_update_enqueue_failed)
    val startedHintFmt = stringResource(R.string.settings_about_update_download_started)

    fun triggerCheck() {
        if (updateState is UpdateUiState.Checking) return
        // 关于页手动检查 = 强制（跳过 6h 节流）：用户进更新面板即期待最新结果
        center.checkForUpdate(
            currentVersionCode = BuildConfig.VERSION_CODE,
            force = true,
            fromTrigger = "about-page"
        )
    }

    // ── 进入页面自动静默检查一次（v1.4.3：不必再手动点第一次）────────────────
    LaunchedEffect(Unit) { triggerCheck() }

    // ── 引擎就绪 → 登记重入口（「稍后安装」后动作区仍可一键回弹）──────────────
    // 中枢状态流变 Ready 时同步本地 readyPatch（磁盘复核扫描的运行时孪生）
    LaunchedEffect(patchFlow) {
        val flow = patchFlow
        if (flow is PatchUpdateEngine.State.Ready) {
            readyPatch = flow
        }
    }

    // ── 就绪产物重入口：清单就绪后扫描磁盘（上次「稍后安装」的包仍可直接装）──
    // 优先级：清掉陈旧合成产物 → 复核增量就绪包 → 复核全量就绪包。
    // 指纹复核与下载完成的终局校验同源（SHA-256 对账清单），坏文件就地清理。
    // 已最新（UpToDate）时任何合成产物都是装完的陈旧残留 —— 全清（全量包
    // 是用户可见下载物，保留不动）；Idle/Checking/Failed 不动磁盘（可能有
    // 未安装的就绪产物等着复核恢复）。
    val availableManifest = (updateState as? UpdateUiState.Done)
        ?.result?.let { it as? UpdateCheckResult.Available }?.latest
    val checkSettled = updateState is UpdateUiState.Done
    LaunchedEffect(availableManifest?.versionCode, checkSettled) {
        val manifest = availableManifest
        if (manifest == null) {
            if (checkSettled &&
                (updateState as UpdateUiState.Done).result is UpdateCheckResult.UpToDate
            ) {
                withContext(Dispatchers.IO) {
                    downloader.workDirectory().listFiles()?.forEach { stale ->
                        val name = stale.name
                        if (name.startsWith("ApexAgent-v") && name.endsWith("-patched.apk")) {
                            runCatching { stale.delete() }
                        }
                    }
                }
            }
            return@LaunchedEffect
        }
        if (!center.patchEngine.isRunning) {
            withContext(Dispatchers.IO) {
                // 陈旧合成产物（非目标版本的 -patched.apk）——引擎产物而非
                // DownloadManager 托管文件，引擎空闲时清理安全
                downloader.workDirectory().listFiles()?.forEach { stale ->
                    val name = stale.name
                    if (name.startsWith("ApexAgent-v") && name.endsWith("-patched.apk") &&
                        name != "ApexAgent-v${manifest.versionName}-patched.apk"
                    ) runCatching { stale.delete() }
                }
            }
        }
        val asset = checker.preferredAsset(manifest)
        val patchFile = center.patchEngine.readyApkFor(manifest)
        val patchOk = withContext(Dispatchers.IO) {
            patchFile.exists() && downloader.verifySha256(patchFile, asset?.sha256)
        }
        readyPatch = if (patchOk) {
            PatchUpdateEngine.State.Ready(patchFile, asset?.sizeBytes ?: patchFile.length())
        } else {
            if (patchFile.exists()) {
                withContext(Dispatchers.IO) { runCatching { patchFile.delete() } }
            }
            null
        }
        val fullFile = asset?.let { downloader.localFile(it.url.substringAfterLast('/')) }
        val fullOk = asset != null && fullFile != null && withContext(Dispatchers.IO) {
            fullFile.exists() && downloader.verifySha256(fullFile, asset.sha256)
        }
        readyFull = if (fullOk) fullFile else null
    }

    // ── 下载完成广播：校验 SHA-256 → 弹「立即安装」确认框 ──────────────
    // （增量补丁链由 PatchUpdateEngine 轮询驱动，不经此广播；activeDownload
    //  仅登记全量包下载，引擎入队的补丁 ID 在此被直接忽略。）
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
                        // 下载完成即弹「立即安装」确认框 —— 不再直接拉起安装器，
                        // 安装时机由用户主导（与增量路径的就绪弹窗同一交互范式）
                        else -> DownloadFinished.ApkReady(file)
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
            // DownloadManager.query 是主线程 binder/ContentProvider 调用
            //（300MB 下载持续数分钟 = 数千次主线程 IPC）→ 收敛到 IO
            val (percent, bytes) = withContext(Dispatchers.IO) { downloader.progress(active.id) }
            downloadPercent = percent
            downloadedBytes = bytes
            if (activeDownload?.id != active.id) break
            delay(800)
        }
    }

    // ── 发起全量下载（镜像解析 → URL 改写 → DownloadManager 入队）──────────
    fun startDownload() {
        val result = (updateState as? UpdateUiState.Done)?.result
        val manifest = (result as? UpdateCheckResult.Available)?.latest ?: return
        val full = checker.preferredAsset(manifest) ?: return
        val asset: UpdateTarget = full
        val fileName = asset.url.substringAfterLast('/')
        val title = "Apex Agent v${manifest.versionName}"
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
                downloader.enqueue(finalUrl, fileName, title, false, asset.sha256)
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

    // ── 发起增量更新（中枢内全自动：链下载 → 合成 → 校验 → 就绪弹安装按钮）──
    // 应用级流水线：离开本页下载与合成继续；中断后重启只补缺失分段（断点续传）
    fun startPatchFlow(chain: PatchIndex.Chain, manifest: UpdateManifest) {
        // 补丁体积小（11~18MB/段）：AUTO 且已有测速则复用，否则直连 GitHub
        // —— 不为小文件现场跑一轮四节点探测
        val resolved = if (selectedMirror == DownloadMirror.AUTO && speeds.isNotEmpty()) {
            resolveAuto(speeds)
        } else if (selectedMirror == DownloadMirror.AUTO) {
            DownloadMirror.DIRECT
        } else selectedMirror
        center.startPatchFlow(chain, manifest, resolved)
    }

    // ── 立即安装（就绪产物 → 系统安装器；结果经中枢状态流回弹）──────────
    fun installNow(apk: File) {
        center.launchInstaller(apk)
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

                        // 下载动作区（优先级：增量流水线 > 全量下载进度 > 动作按钮）
                        val active = activeDownload
                        val flow = patchFlow
                        when {
                            flow is PatchUpdateEngine.State.Downloading ||
                                flow is PatchUpdateEngine.State.Applying -> {
                                PatchFlowProgress(flow)
                            }

                            active != null -> {
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
                            }

                            else -> {
                                // 就绪产物优先：上次已合成/已下载但未安装的包，
                                // 一键直达安装确认框（不重复下载/合成）
                                val ready = readyPatch
                                if (ready != null) {
                                    Button(
                                        onClick = {
                                            // 就绪重入口：把磁盘复核通过的合成包重新
                                            // 挂回中枢状态 → 弹安装确认框
                                            center.restoreReadyState(ready.apk, ready.sizeBytes)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            Icons.Outlined.SystemUpdateAlt,
                                            contentDescription = null
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            stringResource(
                                                R.string.about_update_install_ready_entry,
                                                manifest.versionName,
                                                formatMb(ready.sizeBytes)
                                            )
                                        )
                                    }
                                }
                                readyFull?.let { file ->
                                    OutlinedButton(
                                        onClick = { finished = DownloadFinished.ApkReady(file) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            Icons.Outlined.Download,
                                            contentDescription = null
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            stringResource(
                                                R.string.about_update_install_full_entry,
                                                formatMb(file.length())
                                            )
                                        )
                                    }
                                }

                                // 跨版本补丁链（patches.json 索引 → 链式增量）；
                                // 增量包已就绪时不再重复提供（重跑会重下补丁）
                                val chain = if (ready == null) checker.resolvePatchChain(
                                    patchIndex, manifest, BuildConfig.VERSION_NAME
                                ) else null
                                if (chain != null && full != null) {
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
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    stringResource(
                                                        R.string.about_update_patch_recommended
                                                    ),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                // 单跳直达 / N 段链式 —— 多基底补丁的
                                                // 直达边让窗口内版本一步到位
                                                Surface(
                                                    shape = RoundedCornerShape(5.dp),
                                                    color = if (chain.steps.size == 1) {
                                                        MaterialTheme.colorScheme.primary
                                                            .copy(alpha = 0.14f)
                                                    } else {
                                                        MaterialTheme.colorScheme
                                                            .secondaryContainer.copy(alpha = 0.7f)
                                                    }
                                                ) {
                                                    Text(
                                                        text = if (chain.steps.size == 1) {
                                                            stringResource(
                                                                R.string.about_update_patch_direct
                                                            )
                                                        } else {
                                                            stringResource(
                                                                R.string.about_update_patch_chain_n,
                                                                chain.steps.size
                                                            )
                                                        },
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (chain.steps.size == 1) {
                                                            MaterialTheme.colorScheme.primary
                                                        } else {
                                                            MaterialTheme.colorScheme
                                                                .onSecondaryContainer
                                                        },
                                                        modifier = Modifier.padding(
                                                            horizontal = 6.dp, vertical = 2.dp
                                                        )
                                                    )
                                                }
                                            }
                                            val saved = if (full.sizeBytes > 0) {
                                                // 守卫：清单缺 sizeBytes（旧 schema/手写清单）时
                                                // 默认 0，Long 除零会直接抛 ArithmeticException
                                                100 - (chain.totalBytes * 100 / full.sizeBytes)
                                                    .toInt().coerceIn(0, 100)
                                            } else 0
                                            Text(
                                                stringResource(
                                                    R.string.about_update_patch_chain_hint,
                                                    BuildConfig.VERSION_NAME,
                                                    manifest.versionName,
                                                    chain.steps.size,
                                                    saved
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                            // 链路可视化：本地 →（分段）→ 目标，
                                            // 每段标注体积 —— 用户看清「怎么走」
                                            PatchChainPath(chain)
                                            Button(
                                                onClick = { startPatchFlow(chain, manifest) },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(
                                                    Icons.Outlined.SystemUpdateAlt,
                                                    contentDescription = null
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    stringResource(
                                                        R.string.about_update_patch_chain_button,
                                                        formatMb(chain.totalBytes),
                                                        chain.steps.size
                                                    )
                                                )
                                            }
                                            Text(
                                                stringResource(
                                                    R.string.about_update_patch_auto_note
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                } else if (manifest.patch?.arm64 != null ||
                                    manifest.patch?.universal != null
                                ) {
                                    // 索引缺失且单补丁 fromTag 不匹配（跨多版且无索引）
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

                                // 全量安装包（永久兜底路径；已有就绪/已下载包时不重复提供）
                                if (full != null && ready == null && readyFull == null) {
                                    OutlinedButton(
                                        onClick = { startDownload() },
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

    // ── 下载完成事件对话框（全量包路径：弹「立即安装」按钮）───────────────────
    when (val event = finished) {
        is DownloadFinished.ApkReady -> {
            // 版本号来自当前清单（下载中重查过/清单过期时退化为通用文案）
            val manifest = availableManifest
            val launchedHint = stringResource(R.string.about_update_installer_launched_toast)
            AlertDialog(
                onDismissRequest = {
                    // 点外部关闭与「稍后」同义：登记重入口（动作区一键回弹）
                    readyFull = event.file
                    finished = null
                },
                title = { Text(stringResource(R.string.settings_about_update_apk_done_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                R.string.settings_about_update_apk_done_body,
                                manifest?.versionName ?: "",
                                formatMb(event.file.length())
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            stringResource(
                                R.string.settings_about_update_patch_done_saved,
                                event.file.absolutePath
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (downloader.installApk(event.file)) {
                            finished = null
                            Toast.makeText(context, launchedHint, Toast.LENGTH_SHORT).show()
                        } else {
                            finished = DownloadFinished.InstallFailed(event.file)
                        }
                    }) {
                        Text(stringResource(R.string.about_update_install_now))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        // 稍后安装：保留 readyFull 重入口（动作区一键回弹本框）
                        readyFull = event.file
                        finished = null
                    }) {
                        Text(stringResource(R.string.about_update_install_later))
                    }
                }
            )
        }

        is DownloadFinished.InstallFailed -> AlertDialog(
            onDismissRequest = {
                readyFull = event.file
                finished = null
            },
            title = { Text(stringResource(R.string.settings_about_update_install_failed_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_about_update_install_failed,
                        event.file.absolutePath
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    readyFull = event.file
                    finished = null
                }) {
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

    // ── 增量流水线对话框：就绪弹安装按钮 / 已拉起安装器 / 失败双路 ──────
    when (val flow = patchFlow) {
        // 合成完成且指纹对账通过 —— 用户主导安装时机（下载完弹出的安装按钮）
        is PatchUpdateEngine.State.Ready -> {
            val manifest = availableManifest
            AlertDialog(
                onDismissRequest = { center.resetPatchState() },
                title = { Text(stringResource(R.string.about_update_install_ready_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                R.string.about_update_install_ready_body,
                                manifest?.versionName ?: "",
                                formatMb(flow.sizeBytes)
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            flow.apk.absolutePath,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { installNow(flow.apk) }) {
                        Text(stringResource(R.string.about_update_install_now))
                    }
                },
                dismissButton = {
                    // 稍后安装：保留 readyPatch 重入口（动作区一键回弹本框）
                    TextButton(onClick = { center.resetPatchState() }) {
                        Text(stringResource(R.string.about_update_install_later))
                    }
                }
            )
        }

        is PatchUpdateEngine.State.Installed -> AlertDialog(
            onDismissRequest = { center.resetPatchState() },
            title = { Text(stringResource(R.string.about_update_patch_done_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.about_update_patch_done_body),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        flow.apk.absolutePath,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { center.resetPatchState() }) {
                    Text(stringResource(R.string.settings_about_update_close))
                }
            }
        )

        is PatchUpdateEngine.State.Failed -> {
            // 失败原因按类别本地化；detail 只进日志/高级线索
            val body = when (flow.kind) {
                PatchUpdateEngine.FailKind.SPACE -> stringResource(
                    R.string.about_update_patch_failed_space,
                    // 预检 detail 形如 "need=… free=…"；UI 侧拿不到体积参数时退化为通用语
                    flow.detail?.substringAfter("need=")?.substringBefore(" ")
                        ?.toLongOrNull()?.let { formatMb(it) } ?: ""
                )
                PatchUpdateEngine.FailKind.DOWNLOAD -> stringResource(
                    R.string.about_update_patch_failed_download
                )
                PatchUpdateEngine.FailKind.VERIFY -> stringResource(
                    R.string.about_update_patch_failed_verify, flow.detail.orEmpty()
                )
                PatchUpdateEngine.FailKind.DECODE -> stringResource(
                    R.string.about_update_patch_failed_decode, flow.detail.orEmpty()
                )
                PatchUpdateEngine.FailKind.INSTALLER -> stringResource(
                    R.string.about_update_patch_failed_installer, flow.detail.orEmpty()
                )
            }
            AlertDialog(
                onDismissRequest = { center.resetPatchState() },
                title = { Text(stringResource(R.string.about_update_patch_failed_title)) },
                text = {
                    Text(
                        body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                },
                confirmButton = {
                    // 重试增量：沿用同一条链重启流水线
                    val result = (updateState as? UpdateUiState.Done)?.result
                    val manifest = (result as? UpdateCheckResult.Available)?.latest
                    val chain = manifest?.let {
                        checker.resolvePatchChain(patchIndex, it, BuildConfig.VERSION_NAME)
                    }
                    TextButton(
                        onClick = {
                            center.resetPatchState()
                            if (manifest != null && chain != null) {
                                startPatchFlow(chain, manifest)
                            }
                        },
                        enabled = manifest != null && chain != null
                    ) {
                        Text(stringResource(R.string.about_update_patch_retry))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        center.resetPatchState()
                        startDownload()
                    }) {
                        Text(stringResource(R.string.about_update_patch_use_full))
                    }
                }
            )
        }

        else -> Unit
    }
}

// ── 小控件 ──────────────────────────────────────────────────────────────────

/**
 * 增量流水线进度：下载段（N/M · 百分比 · 已下载字节）或合成段（补丁 N/M ·
 * 百分比）—— v1.4.5 附「后台持续 + 断点续传」提示（离开页面不中断）。
 */
@Composable
private fun PatchFlowProgress(flow: PatchUpdateEngine.State) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        when (flow) {
            is PatchUpdateEngine.State.Downloading -> {
                LinearProgressIndicator(
                    progress = { flow.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(
                            R.string.about_update_patch_downloading,
                            flow.step, flow.steps, flow.percent
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (flow.bytesSoFar > 0) {
                        Text(
                            formatMb(flow.bytesSoFar),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Text(
                    stringResource(R.string.about_update_patch_background_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                )
            }

            is PatchUpdateEngine.State.Applying -> {
                LinearProgressIndicator(
                    progress = { flow.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(
                        R.string.about_update_patch_applying,
                        flow.step, flow.steps, flow.percent
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    stringResource(R.string.about_update_patch_background_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                )
            }

            else -> Unit
        }
    }
}

/**
 * 补丁链路可视化 —— 本地版本 →（每段补丁 · 体积）→ 目标版本。
 * 等宽小字 + 箭头，实验室标签风格；单段链只画一行直达。
 */
@Composable
private fun PatchChainPath(chain: PatchIndex.Chain) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // 路径行：vLocal → vMid → … → vTarget（节点 = 补丁边界版本）
        Row(verticalAlignment = Alignment.CenterVertically) {
            val nodes = buildList {
                add(chain.steps.firstOrNull()?.fromTag?.removePrefix("v") ?: "?")
                chain.steps.forEach { add(it.toTag.removePrefix("v")) }
            }
            nodes.forEachIndexed { index, node ->
                if (index > 0) {
                    Text(
                        " → ",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.outline
                    )
                }
                Text(
                    node,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (index == nodes.lastIndex) FontWeight.SemiBold
                    else FontWeight.Normal,
                    color = if (index == nodes.lastIndex) scheme.primary else scheme.onSurfaceVariant
                )
            }
        }
        // 分段体积行（>1 段才展开；单跳已在按钮上标总体积）
        if (chain.steps.size > 1) {
            Text(
                chain.steps.joinToString(" · ") { formatMb(it.sizeBytes) },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = scheme.outline
            )
        }
    }
}

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

/** 字节数 → 「318.4 MB」式人类可读体积（一位小数：<1MB 不再显示成 0 MB）。 */
private fun formatMb(bytes: Long): String =
    String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
