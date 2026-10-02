package com.apex.agent.update

import android.content.Context
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  UpdateCenter —— 应用级更新中枢（v1.4.5）
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 为什么需要它：v1.4.4 及之前 [PatchUpdateEngine] 挂在「关于页」的组合域协程上
 * —— 用户一离开页面，下载轮询与链式合成随即取消，只留系统 DownloadManager
 * 里的半截补丁；下次进来还得从头重下重合成。本单例把更新流水线提升到
 * **应用生命周期**：
 *
 * - **后台持续**：自有 [scope]（SupervisorJob + IO），页面导航/重建不断流；
 *   「关于页」只是它的一块仪表盘（订阅 [patchState] 渲染）。
 * - **断点续传**：已完成的补丁段（SHA-256 对账通过）落盘保留，重启流水线
 *   时逐段跳过 —— 中断后从断点继续，不重拉已下载的分段（见
 *   [PatchUpdateEngine.start]）。
 * - **全局检测**：App 启动即静默检查一次（[checkForUpdate]，6 小时节流），
 *   发现新版本经 [bannerVisible] 驱动 ApexRoot 顶部浮窗 —— 非强制、可关、
 *   可「忽略此版本」。
 * - **状态共享**：[checkState] / [patchIndex] / [patchState] 三个 StateFlow
 *   即全部真相源；关于页、浮窗、未来的通知栏入口都读同一份。
 *
 * 初始化：`ApexApp.onCreate → UpdateCenter.ensure(this)`（幂等）。全部
 * 耗时操作在 IO 线程；UI 只做 `collectAsStateWithLifecycle`。
 */
object UpdateCenter {

    /** 检查节流窗：同一会话/短时间内不重复拉清单（毫秒）。 */
    private const val CHECK_THROTTLE_MS = 6L * 60 * 60 * 1000

    /** 更新偏好（与 [MirrorPrefs] 同一 SharedPreferences 文件）。 */
    private const val PREFS = "apex_update_prefs"
    private const val KEY_LAST_CHECK = "last_check_epoch_ms"
    private const val KEY_DISMISSED_TAG = "banner_dismissed_tag"

    /** 进程级单次初始化守卫。 */
    private val initialized = AtomicBoolean(false)

    /** 应用上下文（[ensure] 注入；全部用 applicationContext，绝无 Activity 泄漏）。 */
    private lateinit var appContext: Context

    /** 更新链路共享作用域 —— 应用生命周期存活（页面导航不取消流水线）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val checker = UpdateChecker()

    val downloader: UpdateDownloader by lazy { UpdateDownloader(appContext) }

    val patchEngine: PatchUpdateEngine by lazy { PatchUpdateEngine(appContext, downloader) }

    // ═══ 状态流（唯一真相源）════════════════════════════════════════════

    /** 更新检查状态：Idle → Checking → Done(result)。 */
    sealed interface CheckState {
        data object Idle : CheckState
        data object Checking : CheckState
        data class Done(val result: UpdateCheckResult) : CheckState
    }

    private val _checkState = MutableStateFlow<CheckState>(CheckState.Idle)
    val checkState: StateFlow<CheckState> = _checkState.asStateFlow()

    /** 补丁全量索引（跨版本/多基底单跳数据源；有新版时才拉取）。 */
    private val _patchIndex = MutableStateFlow<PatchIndex.Model?>(null)
    val patchIndex: StateFlow<PatchIndex.Model?> = _patchIndex.asStateFlow()

    /** 增量流水线状态（null = 未在流水线中；Ready/Installed/Failed 终态保留）。 */
    private val _patchState = MutableStateFlow<PatchUpdateEngine.State?>(null)
    val patchState: StateFlow<PatchUpdateEngine.State?> = _patchState.asStateFlow()

    /** 浮窗可见性 —— App 启动检查发现新版且未被「忽略此版本」时置真。 */
    private val _bannerVisible = MutableStateFlow(false)
    val bannerVisible: StateFlow<Boolean> = _bannerVisible.asStateFlow()

    // ═══ 生命周期 ═══════════════════════════════════════════════════════

    /** 幂等初始化（ApexApp.onCreate 调用）。重复调用无副作用。 */
    fun ensure(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        AppLogger.instance.debug(
            LogCategory.SYSTEM, "UpdateCenter", "更新中枢就绪（应用级流水线 + 断点续传 + 启动检测）"
        )
    }

    /** 测试/进程重启防御：未初始化时惰性补齐（持有的是 applicationContext）。 */
    private fun requireContext(context: Context? = null): Context {
        if (initialized.get()) return appContext
        val fallback = context ?: error("UpdateCenter 未初始化（应在 ApexApp.onCreate ensure）")
        ensure(fallback)
        return appContext
    }

    // ═══ 更新检查 ═══════════════════════════════════════════════════════

    /**
     * 静默检查更新（App 启动 / 关于页手动刷新共用入口）。
     *
     * @param force true = 跳过节流（用户手动点「检查更新」/关于页进入）
     * @param fromTrigger 调用来源（日志定位用）
     */
    fun checkForUpdate(
        currentVersionCode: Int,
        force: Boolean = false,
        fromTrigger: String = "manual"
    ) {
        val context = requireContext()
        if (!force && isThrottled(context)) {
            AppLogger.instance.debug(
                LogCategory.SYSTEM, "UpdateCenter", "检查节流命中（$fromTrigger）—— 6h 内已查过"
            )
            // 节流命中但已有结果：浮窗判定照跑（进程重建后 bannerVisible 归零的场景）
            refreshBannerVisibility()
            return
        }
        if (_checkState.value is CheckState.Checking) return
        _checkState.value = CheckState.Checking
        scope.launch {
            val result = checker.check(currentVersionCode)
            if (result is UpdateCheckResult.Available) {
                // 有新版才拉补丁全量索引（多基底单跳/跨版链数据源）
                _patchIndex.value = checker.fetchPatchIndex()
            }
            _checkState.value = CheckState.Done(result)
            markChecked(context)
            refreshBannerVisibility()
            AppLogger.instance.info(
                LogCategory.SYSTEM, "UpdateCenter",
                "更新检查完成（$fromTrigger）：${result.javaClass.simpleName}"
            )
        }
    }

    private fun isThrottled(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK, 0L)
        return last > 0 && System.currentTimeMillis() - last < CHECK_THROTTLE_MS
    }

    private fun markChecked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    // ═══ 增量流水线（应用级 —— 后台持续 + 断点续传）════════════════════

    /**
     * 启动增量流水线（关于页按钮触发；此后可随意离开页面，下载与合成继续）。
     * 已在跑则忽略（防重入）；引擎回调直接落 StateFlow，任何页面回来都能续看。
     */
    fun startPatchFlow(chain: PatchIndex.Chain, manifest: UpdateManifest, mirror: DownloadMirror) {
        if (patchEngine.isRunning) return
        // 流水线启动即接管浮窗注意力（正在更新时无需再提醒）
        _bannerVisible.value = false
        patchEngine.start(scope, chain, manifest, mirror) { state ->
            _patchState.value = state
        }
    }

    /** 就绪产物 → 系统安装器（不依赖流水线协程，可对 Ready 产物反复重发）。 */
    fun launchInstaller(apk: java.io.File) {
        patchEngine.launchInstaller(apk) { state -> _patchState.value = state }
    }

    /** 清流水线终态（对话框关闭/重置 UI 用 —— 不影响引擎执行）。 */
    fun resetPatchState() {
        if (!patchEngine.isRunning) _patchState.value = null
    }

    /**
     * 就绪产物重入口：磁盘复核通过的合成包重新挂回状态流（关于页
     * 「稍后安装」→ 离开 → 回来 → 一键回弹安装确认框）。
     */
    fun restoreReadyState(apk: java.io.File, sizeBytes: Long) {
        if (patchEngine.isRunning) return
        _patchState.value = PatchUpdateEngine.State.Ready(apk, sizeBytes)
    }

    /** 当前检查命中的清单（浮窗/关于页共用；未检查或非 Available 时 null）。 */
    fun availableManifest(): UpdateManifest? =
        (_checkState.value as? CheckState.Done)
            ?.result?.let { it as? UpdateCheckResult.Available }?.latest

    // ═══ 浮窗（非强制更新提醒）═════════════════════════════════════════

    /** 检查结果变化后重算浮窗可见性（Available + 未忽略本版 + 无就绪/进行态）。 */
    private fun refreshBannerVisibility() {
        val manifest = availableManifest() ?: run {
            _bannerVisible.value = false
            return
        }
        val tag = manifest.tag.orEmpty()
        val dismissed = dismissedTag()
        val patchIdle = _patchState.value.let {
            it == null || it is PatchUpdateEngine.State.Failed
        }
        _bannerVisible.value = tag.isNotEmpty() && tag != dismissed && patchIdle
    }

    /** 「忽略此版本」—— 持久化 dismissed tag；该版本不再弹浮窗（下个新版重新弹）。 */
    fun dismissBannerForCurrentVersion() {
        val tag = availableManifest()?.tag.orEmpty()
        if (tag.isNotEmpty()) {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_DISMISSED_TAG, tag).apply()
            AppLogger.instance.debug(
                LogCategory.SYSTEM, "UpdateCenter", "用户忽略新版本浮窗：$tag"
            )
        }
        _bannerVisible.value = false
    }

    /** 浮窗点击「查看」：本次会话隐藏（不持久化忽略 —— About 页仍完整展示）。 */
    fun consumeBannerForNavigation() {
        _bannerVisible.value = false
    }

    private fun dismissedTag(): String =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DISMISSED_TAG, "").orEmpty()
}
