package com.apex.agent.ui.screen.terminal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import com.apex.agent.ui.screen.terminal.history.TerminalHistorySheet
import com.apex.agent.ui.screen.terminal.scheme.TerminalSchemePickerSheet
import com.apex.agent.ui.screen.terminal.settings.TerminalSettingsSheet
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 终端主屏（T89 大修 —— 用户反馈「页面一坨 / Ubuntu 用不了 / 没法输入命令」
 * 的系统性重构）。
 *
 * ## T89 结构决策（旧 → 新）
 *
 *  - **砍层叠条带**：旧版竖屏顶部堆 4~7 层（状态 inset + OfflineBanner +
 *    ContextMeterBar + ConsoleTopBar + SessionStrip + StatusStrip 21dp 调试行），
 *    键区再叠 80dp（45 键长滚 + 8 宏键）。新版：单一紧凑顶栏（副标题承载
 *    后端/环境状态）+ 会话条（多会话才出现）+ 终端 + 键区（见 KeyToolbar 重做）。
 *    状态栏调试噪音（#id STATE rows×cols）删除；
 *  - **notice 全局可见**：旧版 notice 只渲染在 StatusStrip 里且仅在有会话时
 *    出现 —— 而会话创建失败/Ubuntu 不可用恰恰发生在**无会话**时，用户全程
 *    盲飞。新版 notice 恒渲染为顶栏下横幅（错误红/信息 mint，可点击关闭）；
 *  - **单弹层范式**：旧版「设置=侧抽屉（套在 App 全局 drawer 里的双层侧滑）、
 *    环境/配色/历史=bottom sheet、新建=AlertDialog」三种范式混学。新版配置
 *    类统一 bottom sheet（[TerminalConsoleTheme] 深色控制台主题），新建会话
 *    保留 AlertDialog（快速二选一的标准容器）；
 *  - **环境进度不遮内容**：旧版 UbuntuBusyStrip 浮在终端 BottomCenter 盖住
 *    最后几行输出/提示符。新版并入顶栏下方 2dp 细进度线 + 副标题百分比；
 *  - **终端永可用**：配合 VM 的 LOCAL 降级兜底（Ubuntu 失败自动拉 Android
 *    shell），主区不再有「无会话 + 键盘无响应」死区。
 */
@Composable
fun TerminalScreen(
    onOpenNavDrawer: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val semantic by viewModel.semanticState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ubuntu by viewModel.ubuntuLifecycleState.collectAsStateWithLifecycle()
    val ubuntuProgress by viewModel.ubuntuProgress.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val blacklist by viewModel.blacklist.collectAsStateWithLifecycle()
    val whitelist by viewModel.whitelist.collectAsStateWithLifecycle()

    var showNewSessionDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showEnvironmentCenter by remember { mutableStateOf(false) }
    var showSchemePicker by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    // 保持屏幕常亮：看长任务输出（编译 / apt / 训练日志）时不被息屏打断 ——
    // Termux 默认持有 wakelock，这里用等价的 window flag，交给用户开关。
    val keepScreenOn = settings.keepScreenOn
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            if (keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            // 离屏必须还原：否则终端页退出后整 App 一直亮屏耗电
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 环境中心打开时刷新一次占用（安装/删除后状态驱动刷新，这里兑底）
    LaunchedEffect(showEnvironmentCenter) {
        if (showEnvironmentCenter) viewModel.refreshRootfsSize()
    }

    // 反馈横幅自动消隐（错误类稍长——降级提示含操作指引）
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(8000)
            viewModel.consumeNotice()
        }
    }

    val activeTab = sessions.firstOrNull { it.id == activeId }
    val hasSession = activeId != null && activeTab != null
    val envBusy = ubuntu.phase in setOf(
        UbuntuLifecycleCoordinator.Phase.INSTALLING,
        UbuntuLifecycleCoordinator.Phase.BOOTSTRAPPING
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ConsoleTheme.bg)
    ) {
        // ═══ 顶栏（单一紧凑层：导航 + 状态 + 动作）═══
        ConsoleTopBar(
            activeTab = activeTab,
            ubuntuPhase = ubuntu.phase,
            ubuntuPercent = ubuntuProgress?.percent ?: 0,
            semantic = semantic,
            onOpenNavDrawer = onOpenNavDrawer,
            onNewSession = { showNewSessionDialog = true },
            onOpenCenter = { showEnvironmentCenter = true },
            onOpenSettings = { showSettings = true }
        )

        // ═══ 环境收敛细进度线（不遮内容 —— 替代旧浮条）═══
        if (envBusy) {
            val percent = ubuntuProgress?.percent ?: 0
            val determinate = percent > 0
            if (determinate) {
                LinearProgressIndicator(
                    progress = { percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = ConsoleTheme.accent,
                    trackColor = ConsoleTheme.accentSoft
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = ConsoleTheme.accent,
                    trackColor = ConsoleTheme.accentSoft
                )
            }
        }

        // ═══ notice 横幅（恒可见 —— 无会话时也反馈；点击关闭）═══
        if (notice != null) {
            NoticeBanner(text = notice!!, onDismiss = viewModel::consumeNotice)
        }

        // ═══ 会话条（≥2 个会话才显示 —— 单会话零干扰）═══
        if (sessions.size >= 2) {
            SessionStrip(
                sessions = sessions,
                activeId = activeId,
                onSelect = viewModel::selectSession,
                onClose = viewModel::closeSession
            )
        }

        // ═══ 主区：终端 / 环境面板 ═══
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (hasSession) {
                // T88（3）：:terminal-view Canvas 直绘（滚动/选区/IME/鼠标上报
                // 全部下放 View 层）。
                TerminalViewHost(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
                // T87：死会话覆盖层 —— 会话进程已退出时不再让用户对着
                // 死 PTY 敲字（旧体验：每次输入弹「输入失败」却无路可走）。
                if (activeTab != null && !activeTab.isAlive) {
                    DeadSessionOverlay(
                        isUbuntu = activeTab.isUbuntu,
                        onRestart = viewModel::restartActiveSession,
                        onClose = { viewModel.closeSession(activeTab.id) }
                    )
                }
            } else {
                EnvironmentPanel(
                    phase = ubuntu.phase,
                    progress = ubuntuProgress,
                    lastError = ubuntu.lastError,
                    onRetry = viewModel::installUbuntu,
                    onNewUbuntu = { viewModel.createSession(TerminalViewModel.BACKEND_UBUNTU) },
                    onNewLocal = { viewModel.createSession(TerminalViewModel.BACKEND_LOCAL) },
                    onOpenCenter = { showEnvironmentCenter = true }
                )
            }
        }
    }

    if (showNewSessionDialog) {
        TerminalConsoleTheme {
            NewSessionDialog(
                onDismiss = { showNewSessionDialog = false },
                onUbuntu = {
                    showNewSessionDialog = false
                    viewModel.createSession(TerminalViewModel.BACKEND_UBUNTU)
                },
                onLocal = {
                    showNewSessionDialog = false
                    viewModel.createSession(TerminalViewModel.BACKEND_LOCAL)
                }
            )
        }
    }

    // ═══ 终端设置（T89：侧抽屉 → bottom sheet，单弹层范式）═══
    if (showSettings) {
        TerminalSettingsSheet(
            settings = settings,
            onSettings = viewModel::updateSettings,
            schemeName = viewModel.currentSchemeDisplayName(),
            onOpenSchemePicker = { showSchemePicker = true },
            boldAsBright = viewModel.boldAsBright.collectAsStateWithLifecycle().value,
            onBoldAsBright = viewModel::setBoldAsBright,
            onOpenHistory = { showHistory = true },
            extraKeys = viewModel.extraKeys.collectAsStateWithLifecycle().value,
            onAddExtraKey = viewModel::addExtraKey,
            onRemoveExtraKey = viewModel::removeExtraKey,
            onResetExtraKeys = viewModel::resetExtraKeys,
            blacklist = blacklist,
            whitelist = whitelist,
            onAddBlack = viewModel::addBlacklist,
            onRemoveBlack = viewModel::removeBlacklist,
            onAddWhite = viewModel::addWhitelist,
            onRemoveWhite = viewModel::removeWhitelist,
            onClose = { showSettings = false }
        )
    }

    // ═══ 环境中心（顶栏图层入口）═══
    if (showEnvironmentCenter) {
        TerminalConsoleTheme {
            EnvironmentCenterSheet(
                onDismiss = { showEnvironmentCenter = false },
                ubuntu = ubuntu,
                progress = ubuntuProgress,
                rootfsSize = viewModel.rootfsSize.collectAsStateWithLifecycle().value,
                onInstallUbuntu = viewModel::installUbuntu,
                onCancelUbuntuInstall = viewModel::cancelUbuntuInstall,
                onRepairUbuntu = viewModel::repairUbuntu,
                onRemoveUbuntu = viewModel::removeUbuntu,
                onCreateUbuntuSession = {
                    showEnvironmentCenter = false
                    viewModel.createSession(TerminalViewModel.BACKEND_UBUNTU)
                },
                useMirror = viewModel.useMirror.collectAsStateWithLifecycle().value,
                onToggleMirror = viewModel::setUseMirror,
                depItems = viewModel.depItems,
                install = viewModel.install.collectAsStateWithLifecycle().value,
                onInstallDep = viewModel::installDep,
                onInstallAll = viewModel::installAll,
                onInstallAndroid = viewModel::installAndroidOnly
            )
        }
    }

    // ═══ T87：配色方案选择器 ═══
    if (showSchemePicker) {
        TerminalConsoleTheme {
            TerminalSchemePickerSheet(
                currentSchemeId = viewModel.colorSchemeId.collectAsStateWithLifecycle().value,
                onPick = { viewModel.setColorScheme(it) },
                onDismiss = { showSchemePicker = false }
            )
        }
    }

    // ═══ T87：命令历史 ═══
    if (showHistory) {
        TerminalConsoleTheme {
            TerminalHistorySheet(
                entries = viewModel.commandHistoryEntries.collectAsStateWithLifecycle().value,
                onPick = { cmd ->
                    viewModel.sendInput(cmd)
                    showHistory = false
                },
                onClear = { viewModel.clearCommandHistory() },
                onDismiss = { showHistory = false }
            )
        }
    }
}

// ═══════════════════════ notice 横幅 ═══════════════════════

/**
 * 终端操作反馈横幅（T89：恒可见 —— 替代旧版「埋在状态条里且无会话时消失」的
 * 反馈通道）。错误语义（含「不可用/失败/拦截/已退出」字样）→ 红系；其余 →
 * mint 信息色。点击整体关闭。
 */
@Composable
private fun NoticeBanner(text: String, onDismiss: () -> Unit) {
    val isError = text.contains("失败") || text.contains("不可用") || text.contains("拦截") ||
        text.contains("已退出") || text.contains("error", true) ||
        text.contains("failed", true) || text.contains("failure", true) ||
        text.contains("unavailable", true) || text.contains("denied", true)
    val bg = if (isError) ConsoleTheme.dangerSoft else ConsoleTheme.accentSoft
    val fg = if (isError) ConsoleTheme.danger else ConsoleTheme.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            if (isError) Icons.Default.Warning else Icons.Default.CheckCircle,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = fg,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(R.string.term_close),
            tint = ConsoleTheme.dim,
            modifier = Modifier.size(14.dp)
        )
    }
}

// ═══════════════════════ 死会话覆盖层 ═══════════════════════

/**
 * 会话已退出覆盖层（半透明盖在终端 grid 上）。
 *
 * - 「重启会话」→ 同 backend 重建（[TerminalViewModel.restartActiveSession]）；
 * - 「关闭」→ 移除 tab；
 * - 下方如实展示发生了什么（进程结束 = 退出/被杀；不再伪装成输入故障）。
 */
@Composable
private fun DeadSessionOverlay(
    isUbuntu: Boolean,
    onRestart: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ConsoleTheme.bg.copy(alpha = 0.70f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = ConsoleTheme.amber,
                modifier = Modifier.size(40.dp)
            )
            Text(
                stringResource(R.string.term_session_dead_title),
                color = ConsoleTheme.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(
                    if (isUbuntu) R.string.term_session_dead_ubuntu else R.string.term_session_dead_local
                ),
                color = ConsoleTheme.dim,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    onClick = onRestart,
                    modifier = Modifier
                        .background(ConsoleTheme.accentSoft, RoundedCornerShape(10.dp))
                ) {
                    Text(stringResource(R.string.term_session_restart), color = ConsoleTheme.accent, fontSize = 13.sp)
                }
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.term_close), color = ConsoleTheme.dim, fontSize = 13.sp)
                }
            }
        }
    }
}

// ═══════════════════════ 顶栏 ═══════════════════════

@Composable
private fun ConsoleTopBar(
    activeTab: TerminalViewModel.SessionTab?,
    ubuntuPhase: UbuntuLifecycleCoordinator.Phase,
    ubuntuPercent: Int,
    semantic: com.apex.agent.platform.terminal.state.TerminalSemanticState?,
    onOpenNavDrawer: () -> Unit,
    onNewSession: () -> Unit,
    onOpenCenter: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ConsoleTheme.bar)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onOpenNavDrawer) {
            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.term_cd_nav), tint = ConsoleTheme.text)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                stringResource(R.string.term_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = ConsoleTheme.text
            )
            // 副标题（一行）：会话后端 / 前台 job / 环境进度 —— 旧 StatusStrip
            // 的调试噪音（#id STATE rows×cols）删除，保留用户可读信号。
            val subtitle = when {
                activeTab != null && !activeTab.isAlive -> stringResource(R.string.term_session_dead_title)
                activeTab != null && semantic?.foregroundJob != null ->
                    "▶ ${semantic?.foregroundJob?.command?.take(24)}"
                activeTab != null && activeTab.isUbuntu -> "ubuntu 24.04 · bash"
                activeTab != null -> "android shell"
                ubuntuPhase in setOf(
                    UbuntuLifecycleCoordinator.Phase.INSTALLING,
                    UbuntuLifecycleCoordinator.Phase.BOOTSTRAPPING
                ) -> stringResource(R.string.term_env_preparing, ubuntuPercent)
                ubuntuPhase == UbuntuLifecycleCoordinator.Phase.FAILED -> stringResource(R.string.term_env_error_short)
                else -> stringResource(R.string.term_waiting_session)
            }
            Text(
                subtitle,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = ConsoleTheme.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onNewSession) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.term_cd_new_session), tint = ConsoleTheme.accent)
        }
        IconButton(onClick = onOpenCenter) {
            Icon(Icons.Default.Layers, contentDescription = stringResource(R.string.term_cd_env_center), tint = ConsoleTheme.accent)
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.term_cd_settings), tint = ConsoleTheme.accent)
        }
    }
}

// ═══════════════════════ 会话条 ═══════════════════════

/** 会话 tab 标题的最大字符数 —— shell 标题（tmux/ssh）可能很长，UI 只取前若干字符 + 省略号。 */
private const val MAX_TAB_TITLE = 24

@Composable
private fun SessionStrip(
    sessions: List<TerminalViewModel.SessionTab>,
    activeId: Long?,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(ConsoleTheme.bg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(sessions, key = { it.id }) { tab ->
            SessionChip(
                tab = tab,
                active = tab.id == activeId,
                onSelect = { onSelect(tab.id) },
                onClose = { onClose(tab.id) }
            )
        }
    }
}

@Composable
private fun SessionChip(
    tab: TerminalViewModel.SessionTab,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    // 标题优先显示 shell 自己设的窗口名（OSC 0/1/2 —— vim/tmux/ssh 都会设），
    // 没有才退回 "#id 后端"。对齐 Termux / JuiceSSH：多会话靠标题分辨在跑什么。
    // #170：Agent 创建的会话（backendId=="agent"，见 TerminalViewModel 的推断）
    // 加「Agent·」徽标 —— 会话列表本就不按 owner 过滤，Agent 的 PTY 会话与
    // 用户会话同列展示，徽标让来源一目了然（用户接管输入仍走 USER owner）。
    val agentBadge = if (tab.backendId == "agent") "Agent·" else ""
    val title = tab.title?.take(MAX_TAB_TITLE)
        ?: "#${tab.id} $agentBadge${if (tab.isUbuntu) "Ubuntu" else "Android"}"
    val chipBg by animateColorAsState(
        targetValue = if (active) ConsoleTheme.accentSoft else ConsoleTheme.chip,
        label = "chip-bg"
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(chipBg)
            .clickable(onClick = onSelect)
            .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // 存活状态点
        Box(
            Modifier
                .size(6.dp)
                .background(
                    if (tab.isAlive) ConsoleTheme.accent else ConsoleTheme.danger,
                    CircleShape
                )
        )
        Column {
            Text(
                title,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = ConsoleTheme.text
            )
            Text(
                if (tab.title != null) "#${tab.id} $agentBadge${if (tab.isUbuntu) "Ubuntu" else "Android"}" else sessionStateLabel(tab.state),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = ConsoleTheme.dim,
                maxLines = 1
            )
        }
        // 关闭钮：24dp 视觉 + padding 撑到 ≥32dp 触控（旧 18dp 触控区低于阈值）
        Icon(
            Icons.Default.Close, stringResource(R.string.term_cd_close_session),
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .padding(4.dp),
            tint = ConsoleTheme.dim
        )
    }
}

/** 会话状态 → 本地化短标签（旧版直接显示英文枚举名）。 */
private fun sessionStateLabel(state: String): String = when (state.uppercase(Locale.US)) {
    "CREATED", "STARTING" -> "…"
    "READY", "RUNNING" -> "RUN"
    "WAITING_INPUT" -> "INPUT"
    "INTERRUPTED" -> "INT"
    "EXITED", "CLOSED" -> "EXIT"
    "BROKEN" -> "BROKEN"
    else -> state.take(6)
}

// ═══════════════════════ 环境面板（无会话时的主区）═══════════════════════

/**
 * 无会话时的主区面板 —— 三个形态：
 *  - **准备中**（NOT_INSTALLED/INSTALLING/ROOTFS_READY/BOOTSTRAPPING/RECOVERING）：
 *    三步进度（解压内置环境 → 配置系统 → 初始化工具链）+ 百分比/字节 + 当前阶段消息；
 *    T85 自动预备语义：App 启动即后台进行，用户无需任何操作，完成后自动进终端；
 *  - **失败**（FAILED）：错误详情 + 重试 + 环境中心；
 *  - **就绪空态**（READY，会话被用户全部关闭）：快捷新建入口。
 */
@Composable
private fun EnvironmentPanel(
    phase: UbuntuLifecycleCoordinator.Phase,
    progress: UbuntuLifecycleCoordinator.LifecycleProgress?,
    lastError: String?,
    onRetry: () -> Unit,
    onNewUbuntu: () -> Unit,
    onNewLocal: () -> Unit,
    onOpenCenter: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (phase) {
            UbuntuLifecycleCoordinator.Phase.FAILED -> EnvironmentFailureContent(
                lastError = lastError,
                onRetry = onRetry,
                onOpenCenter = onOpenCenter
            )
            UbuntuLifecycleCoordinator.Phase.READY -> EnvironmentReadyEmptyContent(
                onNewUbuntu = onNewUbuntu,
                onNewLocal = onNewLocal
            )
            else -> EnvironmentPreparingContent(
                phase = phase,
                progress = progress,
                onNewLocal = onNewLocal,
                onOpenCenter = onOpenCenter
            )
        }
    }
}

/** 环境准备步骤序号：0 解压内置档案 → 1 配置系统 → 2 初始化工具链。 */
private fun setupStepIndex(stage: String?): Int = when {
    stage == null -> 0
    stage.startsWith("install:CONFIGURING") ||
        stage.startsWith("install:ACTIVATING") ||
        stage.startsWith("install:VALIDATING") -> 1
    stage.startsWith("bootstrap:") -> 2
    else -> 0 // install:RESOLVING/DOWNLOADING/VERIFYING/EXTRACTING
}

@Composable
private fun EnvironmentPreparingContent(
    phase: UbuntuLifecycleCoordinator.Phase,
    progress: UbuntuLifecycleCoordinator.LifecycleProgress?,
    onNewLocal: () -> Unit,
    onOpenCenter: () -> Unit
) {
    // ── 顶部图标：呼吸光晕的终端符号 ──
    val transition = rememberInfiniteTransition(label = "env-glow")
    val glow by transition.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "env-glow-alpha"
    )
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(84.dp)
                .background(ConsoleTheme.accent.copy(alpha = 0.14f * glow), CircleShape)
        )
        Surface(
            shape = CircleShape,
            color = ConsoleTheme.accentSoft,
            border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleTheme.accent.copy(alpha = 0.35f))
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
                Icon(
                    Icons.Default.Terminal, contentDescription = null,
                    tint = ConsoleTheme.accent,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }
    Spacer(Modifier.height(22.dp))
    Text(
        stringResource(R.string.term_env_prep_title),
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        color = ConsoleTheme.text
    )
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(R.string.term_env_prep_desc),
        fontSize = 12.sp,
        color = ConsoleTheme.dim,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(28.dp))

    // ── 三步进度 ──
    val currentStep = setupStepIndex(progress?.stage)
    val steps = listOf(
        stringResource(R.string.term_step_unpack),
        stringResource(R.string.term_step_configure),
        stringResource(R.string.term_step_toolchain)
    )
    steps.forEachIndexed { index, label ->
        SetupStepRow(
            label = label,
            state = when {
                index < currentStep -> SetupStepState.DONE
                index == currentStep -> SetupStepState.ACTIVE
                else -> SetupStepState.PENDING
            }
        )
    }
    Spacer(Modifier.height(26.dp))

    // ── 进度条 + 百分比 / 字节 ──
    val percent = progress?.percent ?: 0
    val bytes = progress?.bytesTransferred ?: 0L
    val bytesTotal = progress?.bytesTotal
    val determinate = percent > 0 || (bytesTotal != null && bytesTotal > 0L)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ConsoleTheme.bar)
            .border(androidx.compose.foundation.BorderStroke(1.dp, ConsoleTheme.stroke), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    progress == null -> stringResource(R.string.term_starting)
                    else -> progress.message.ifBlank { progress.stage }
                },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = ConsoleTheme.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (determinate) {
                Text(
                    "$percent%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = ConsoleTheme.accent
                )
            }
        }
        if (determinate) {
            LinearProgressIndicator(
                progress = { (percent.coerceIn(0, 100)) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = ConsoleTheme.accent,
                trackColor = ConsoleTheme.accentSoft
            )
            if (bytesTotal != null && bytesTotal > 0L) {
                Text(
                    "${formatMb(bytes)} / ${formatMb(bytesTotal)}",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ConsoleTheme.dim
                )
            }
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = ConsoleTheme.accent,
                trackColor = ConsoleTheme.accentSoft
            )
        }
        Text(
            stringResource(R.string.term_prep_footnote),
            fontSize = 10.5.sp,
            color = ConsoleTheme.dim
        )
    }
    Spacer(Modifier.height(18.dp))

    // ── 次级入口：不等环境，先用 Android Shell / 环境中心（纵向堆叠，
    //    旧版两个横排 TextButton 在窄屏溢出）──
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onNewLocal) {
            Icon(Icons.Default.Android, null, Modifier.size(15.dp), tint = ConsoleTheme.dim)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.term_use_android_first), color = ConsoleTheme.dim, fontSize = 12.sp)
        }
        TextButton(onClick = onOpenCenter) {
            Icon(Icons.Default.Layers, null, Modifier.size(15.dp), tint = ConsoleTheme.dim)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.term_env_center), color = ConsoleTheme.dim, fontSize = 12.sp)
        }
    }
}

private enum class SetupStepState { DONE, ACTIVE, PENDING }

@Composable
private fun SetupStepRow(label: String, state: SetupStepState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (state) {
            SetupStepState.DONE -> Icon(
                Icons.Default.CheckCircle, contentDescription = null,
                tint = ConsoleTheme.accent, modifier = Modifier.size(18.dp)
            )
            SetupStepState.ACTIVE -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 1.8.dp,
                color = ConsoleTheme.accent
            )
            SetupStepState.PENDING -> Box(
                Modifier
                    .size(16.dp)
                    .background(ConsoleTheme.stroke, CircleShape)
            )
        }
        Text(
            label,
            fontSize = 13.sp,
            color = when (state) {
                SetupStepState.DONE -> ConsoleTheme.text
                SetupStepState.ACTIVE -> ConsoleTheme.accent
                SetupStepState.PENDING -> ConsoleTheme.dim
            },
            fontWeight = if (state == SetupStepState.ACTIVE) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun EnvironmentFailureContent(
    lastError: String?,
    onRetry: () -> Unit,
    onOpenCenter: () -> Unit
) {
    Icon(
        Icons.Default.Warning, contentDescription = null,
        tint = ConsoleTheme.danger, modifier = Modifier.size(56.dp)
    )
    Spacer(Modifier.height(18.dp))
    Text(stringResource(R.string.term_env_failed_title), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = ConsoleTheme.text)
    Spacer(Modifier.height(8.dp))
    if (!lastError.isNullOrBlank()) {
        Text(
            lastError.take(300),
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = ConsoleTheme.dim,
            textAlign = TextAlign.Center,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(10.dp))
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ConsoleTheme.dangerSoft,
        modifier = Modifier.clip(RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier
                .clickable(onClick = onRetry)
                .padding(horizontal = 26.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = ConsoleTheme.danger, modifier = Modifier.size(17.dp))
            Text(stringResource(R.string.term_retry), color = ConsoleTheme.danger, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
    Spacer(Modifier.height(14.dp))
    TextButton(onClick = onOpenCenter) {
        Text(stringResource(R.string.term_open_env_center_diag), color = ConsoleTheme.dim, fontSize = 12.sp)
    }
}

@Composable
private fun EnvironmentReadyEmptyContent(
    onNewUbuntu: () -> Unit,
    onNewLocal: () -> Unit
) {
    Icon(
        Icons.Default.Terminal, contentDescription = null,
        tint = ConsoleTheme.accent, modifier = Modifier.size(52.dp)
    )
    Spacer(Modifier.height(18.dp))
    Text(stringResource(R.string.term_no_terminal_session), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ConsoleTheme.text)
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(R.string.term_env_ready_hint),
        fontSize = 12.sp,
        color = ConsoleTheme.dim,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(24.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ConsoleTheme.accentSoft,
        border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleTheme.accent.copy(alpha = 0.4f)),
        modifier = Modifier.clip(RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier
                .clickable(onClick = onNewUbuntu)
                .padding(horizontal = 30.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Icon(Icons.Default.Terminal, contentDescription = null, tint = ConsoleTheme.accent, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.term_new_ubuntu_session), color = ConsoleTheme.accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onNewLocal) {
        Icon(Icons.Default.Android, null, Modifier.size(15.dp), tint = ConsoleTheme.dim)
        Spacer(Modifier.width(6.dp))
        Text("Android Shell", color = ConsoleTheme.dim, fontSize = 12.sp)
    }
}

// ═══════════════════════ 新建会话对话框 ═══════════════════════

@Composable
private fun NewSessionDialog(
    onDismiss: () -> Unit,
    onUbuntu: () -> Unit,
    onLocal: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.term_new_session_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SessionTypeCard(
                    icon = { Icon(Icons.Default.Terminal, null, tint = MaterialTheme3Colors.accent, modifier = Modifier.size(22.dp)) },
                    title = stringResource(R.string.term_ubuntu_session),
                    desc = stringResource(R.string.term_session_ubuntu_desc),
                    onClick = onUbuntu
                )
                SessionTypeCard(
                    icon = { Icon(Icons.Default.Android, null, tint = MaterialTheme3Colors.amber, modifier = Modifier.size(22.dp)) },
                    title = "Android Shell",
                    desc = stringResource(R.string.term_session_android_desc),
                    onClick = onLocal
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.term_cancel)) }
        }
    )
}

@Composable
private fun SessionTypeCard(
    icon: @Composable () -> Unit,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme3Colors.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        icon()
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ConsoleTheme.text)
            Text(
                desc,
                fontSize = 11.sp,
                color = ConsoleTheme.dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** AlertDialog 在 TerminalConsoleTheme 内的 Material 色锚（语义同名 console 色）。 */
private object MaterialTheme3Colors {
    val accent = ConsoleTheme.accent
    val amber = ConsoleTheme.amber
    val chip = ConsoleTheme.chip
}

/** 字节 → MB 文本（1 位小数）。 */
private fun formatMb(bytes: Long): String =
    String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
