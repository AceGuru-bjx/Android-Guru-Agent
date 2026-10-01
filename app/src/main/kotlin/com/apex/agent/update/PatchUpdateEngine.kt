package com.apex.agent.update

import android.app.DownloadManager
import android.content.Context
import android.os.StatFs
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 增量更新流水线编排器 —— 「补丁链下载 → 链式合成 → 校验 → 拉起安装器」
 * 的全自动执行单元，直接消灭旧的「复制 xdelta3 命令去终端手工跑」环节。
 *
 * 职责边界：
 * - **顺序下载**：补丁链逐段入队系统 DownloadManager（通知栏进度、断点
 *   续传、进程被杀不丢），每段完成即 SHA-256 校验，坏一段立即失败；
 * - **链式合成**：已安装 APK（applicationInfo.sourceDir，只读）+ 补丁 1 →
 *   产物 A；A + 补丁 2 → 产物 B…… 乒乓复用临时文件，峰值磁盘 ≈
 *   2×APK + 补丁体积（开始前做剩余空间预检，不足则劝导全量/清理）；
 * - **双保险校验**：解码器逐窗 Adler32 + 终局 SHA-256 对账 version.json
 *   的 download 指纹（arm64/universal 与补丁链变体严格对应）；
 * - **自动安装**：合成产物落到公共 `Download/ApexAgent/`（FileProvider
 *   授权 URI），直接拉起系统安装器；
 * - **自清理**：开工先清上次残留（含旧命令行方案遗留的 .vcdiff），成功
 *   后清全部补丁与中间产物，只留最终 APK 供安装器读取。
 *
 * 状态经 [StateListener] 单向上报（IO 线程回调，UI 层自行切主线程）；
 * 全部失败折叠为 [State.Failed]（按 [FailKind] 本地化，不向上抛）——
 * 调用方选择重试增量或回退全量。
 */
class PatchUpdateEngine(
    private val context: Context,
    private val downloader: UpdateDownloader,
    private val decoder: VcdiffDecoder = VcdiffDecoder()
) {

    /** 失败类别 —— UI 据此出本地化文案，detail 供日志/高级折叠。 */
    enum class FailKind { SPACE, DOWNLOAD, VERIFY, DECODE, INSTALLER }

    /** 流水线状态 —— UI 按类型渲染，无需解析错误码。 */
    sealed interface State {
        /** 下载补丁链第 step/steps 段。 */
        data class Downloading(
            val step: Int,
            val steps: Int,
            val percent: Int,
            val bytesSoFar: Long
        ) : State

        /** 合成阶段：第 step/steps 段补丁应用至 percent%。 */
        data class Applying(val step: Int, val steps: Int, val percent: Int) : State

        /** 终局 SHA-256 通过且安装器已拉起。 */
        data class Installed(val apk: File) : State

        /** 任一环节失败。 */
        data class Failed(val kind: FailKind, val detail: String? = null) : State
    }

    fun interface StateListener {
        fun onStateChanged(state: State)
    }

    private var job: Job? = null

    /** 仍在执行的流水线（按钮防重入）。 */
    val isRunning: Boolean get() = job?.isActive == true

    /** 公共下载工作目录（与全量包同目录，同卷做空间预检）。 */
    private val workDir: File
        get() = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            "ApexAgent"
        ).apply { mkdirs() }

    /**
     * 启动流水线（幂等：正在跑则先取消旧的）。
     *
     * @param scope 调用方组合域（离开页面即取消下载轮询与合成协程）
     * @param chain 补丁链（[UpdateChecker.resolvePatchChain] 的结果，非空有序）
     * @param manifest 更新清单（终局 SHA-256/体积对账）
     * @param mirror 已解析的下载镜像（URL 改写）
     * @param listener 状态回调（IO 线程）
     */
    fun start(
        scope: CoroutineScope,
        chain: PatchIndex.Chain,
        manifest: UpdateManifest,
        mirror: DownloadMirror,
        listener: StateListener
    ) {
        cancel()
        job = scope.launch(Dispatchers.IO) {
            runFlow(chain, manifest, mirror, listener)
        }
    }

    /** 取消流水线并清理中间产物（补丁文件保留供人工诊断）。 */
    fun cancel() {
        job?.cancel()
        job = null
        runCatching { cleanStaleIntermediates(keepPatches = true) }
    }

    // ── 流水线主体 ───────────────────────────────────────────────────────

    private suspend fun runFlow(
        chain: PatchIndex.Chain,
        manifest: UpdateManifest,
        mirror: DownloadMirror,
        listener: StateListener
    ) {
        try {
            runCatching { cleanStaleIntermediates(keepPatches = false) }
            val variant = chain.steps.first().variant
            val expectedAsset = assetForVariant(manifest, variant)
            val expectedSize = expectedAsset?.sizeBytes ?: 0L
            val expectedSha = expectedAsset?.sha256

            // ── 空间预检：链合成峰值 ≈ 2×产物 + 补丁（多段链）─────────────
            if (expectedSize > 0) {
                val required = expectedSize * (if (chain.steps.size > 1) 2L else 1L) +
                    chain.totalBytes + (16L shl 20)
                val available = StatFs(workDir.absolutePath).availableBytes
                if (available < required) {
                    listener.onStateChanged(
                        State.Failed(FailKind.SPACE, "need=$required free=$available")
                    )
                    return
                }
            }

            // ── 阶段一：逐段下载补丁链 ────────────────────────────────────
            val patchFiles = ArrayList<File>(chain.steps.size)
            for ((index, step) in chain.steps.withIndex()) {
                val number = index + 1
                val fileName = step.url.substringAfterLast('/')
                val enqueued = downloader.enqueue(
                    url = mirror.rewrite(step.url),
                    fileName = fileName,
                    title = "Apex Agent patch $number/${chain.steps.size}",
                    isPatch = true,
                    expectedSha256 = step.sha256
                ) ?: run {
                    listener.onStateChanged(
                        State.Failed(FailKind.DOWNLOAD, "DownloadManager unavailable")
                    )
                    return
                }
                awaitDownload(enqueued.id) { percent, bytes ->
                    listener.onStateChanged(
                        State.Downloading(number, chain.steps.size, percent, bytes)
                    )
                }
                val file = downloader.localFile(fileName)
                if (!file.exists() || !downloader.verifySha256(file, step.sha256)) {
                    listener.onStateChanged(
                        State.Failed(FailKind.VERIFY, "patch $number: $fileName")
                    )
                    return
                }
                patchFiles.add(file)
            }

            // ── 阶段二：链式合成（乒乓临时文件）────────────────────────────
            val sourceApk = File(
                requireNotNull(context.applicationInfo.sourceDir) {
                    "installed APK path is unavailable"
                }
            )
            var intermediate: File? = null
            var chainSucceeded = false
            try {
                var input: File = sourceApk
                for ((index, patchFile) in patchFiles.withIndex()) {
                    val number = index + 1
                    val isLast = number == patchFiles.size
                    val output = if (isLast) finalApkFile(manifest) else {
                        File(workDir, "ApexAgent-chain-step$number.apk").also {
                            runCatching { it.delete() }
                        }
                    }
                    decoder.decode(
                        source = input.takeIf { it.exists() },
                        patch = patchFile.readBytes(),
                        output = output,
                        expectedSize = if (isLast) expectedSize else 0L,
                        onProgress = { decoded ->
                            val percent = if (expectedSize > 0) {
                                ((decoded * 100) / expectedSize).toInt().coerceIn(0, 100)
                            } else 0
                            listener.onStateChanged(
                                State.Applying(number, patchFiles.size, percent)
                            )
                        }
                    )
                    // 上一段中间产物使命完成（首段输入是已安装 APK，绝不能删）
                    intermediate?.let { previous -> runCatching { previous.delete() } }
                    intermediate = if (isLast) null else output
                    input = output
                }
                chainSucceeded = true
            } finally {
                intermediate?.let { runCatching { it.delete() } }
                if (!chainSucceeded) runCatching { finalApkFile(manifest).delete() }
            }

            // ── 阶段三：终局 SHA-256 对账 + 拉起安装器 ─────────────────────
            val finalApk = finalApkFile(manifest)
            if (!downloader.verifySha256(finalApk, expectedSha)) {
                runCatching { finalApk.delete() }
                listener.onStateChanged(State.Failed(FailKind.VERIFY, "final APK"))
                return
            }
            val launched = downloader.installApk(finalApk)
            runCatching { patchFiles.forEach { it.delete() } }
            if (launched) {
                AppLogger.instance.info(
                    LogCategory.SYSTEM, "PatchUpdateEngine",
                    "增量更新合成完成：${finalApk.name}（${patchFiles.size} 段补丁）"
                )
                listener.onStateChanged(State.Installed(finalApk))
            } else {
                listener.onStateChanged(
                    State.Failed(FailKind.INSTALLER, finalApk.absolutePath)
                )
            }
        } catch (e: Exception) {
            AppLogger.instance.warn(
                LogCategory.SYSTEM, "PatchUpdateEngine",
                "增量更新失败：${e.message}"
            )
            val kind = when (e) {
                is VcdiffDecoder.VcdiffFormatException -> FailKind.DECODE
                else -> FailKind.DECODE
            }
            listener.onStateChanged(State.Failed(kind, e.message))
        }
    }

    /** 轮询指定下载直至成功/失败（协程取消即止；PAUSED 等待自动恢复）。 */
    private suspend fun awaitDownload(
        id: Long,
        onTick: (percent: Int, bytes: Long) -> Unit
    ) {
        while (currentCoroutineContext().isActive) {
            val status = downloader.statusOf(id)
            if (status == DownloadManager.STATUS_SUCCESSFUL) return
            if (status == DownloadManager.STATUS_FAILED) {
                throw VcdiffDecoder.VcdiffFormatException("download failed")
            }
            val (percent, bytes) = downloader.progress(id)
            onTick(percent, bytes)
            delay(POLL_INTERVAL_MS)
        }
    }

    /** 最终合成 APK 的落点名（FileProvider 可授权安装）。 */
    private fun finalApkFile(manifest: UpdateManifest): File =
        File(workDir, "ApexAgent-v${manifest.versionName}-patched.apk")

    /** 按补丁链变体取全量资产指纹（arm64 链对账 arm64 包）。 */
    private fun assetForVariant(
        manifest: UpdateManifest,
        variant: String
    ): UpdateAsset? {
        val download = manifest.download ?: return null
        return when (variant) {
            "arm64" -> download.arm64 ?: download.universal
            else -> download.universal ?: download.arm64
        }
    }

    /** 清理旧中间产物；keepPatches=true 保留 .vcdiff 供人工诊断。 */
    private fun cleanStaleIntermediates(keepPatches: Boolean) {
        val files = workDir.listFiles() ?: return
        for (file in files) {
            val name = file.name
            val stale = name.startsWith("ApexAgent-chain-step") ||
                (name.startsWith("ApexAgent-v") && name.endsWith("-patched.apk")) ||
                (!keepPatches && name.endsWith(".vcdiff"))
            if (stale) runCatching { file.delete() }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 600L
    }
}
