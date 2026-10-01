package com.apex.agent.ui.screen.terminal

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.environment.EnvironmentProvisioner
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.KeyEventMapping
import com.apex.agent.platform.terminal.io.KeySequenceEncoder
import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.state.TerminalSemanticState
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import com.apex.agent.terminalemulator.MouseEncoder
import com.apex.agent.terminalemulator.TerminalMouseEventType
import com.apex.agent.terminalemulator.TerminalRenderSnapshot
import com.apex.agent.terminalemulator.encodeFocusEvent
import com.apex.agent.ui.language.LanguageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 交互式终端 ViewModel（P83 — Terminal 产品化）。
 *
 * 在保留原有三块职责（设置 / 黑白名单 / 依赖安装中心）之上，补齐交互终端控制面：
 *  - **多会话**：列表 + 活跃会话切换；create（Android shell / Ubuntu）/ close。
 *  - **实时屏幕**：styledScreenFlow（颜色 grid 渲染数据，sample 33ms 防洪泛）+
 *    semanticStateFlow（状态/前台 job/prompt 检测）。
 *  - **输入**：文本（IME RAW 写入）、模式感知特殊键（DECCKM 箭头 / bracketed paste）。
 *  - **Resize**：渲染区尺寸 → PTY rows/cols（SIGWINCH）。
 *  - **Ubuntu 生命周期**：安装横幅状态 + ensureReady 入口。
 *
 * 数据流（Spec §41 事件驱动，非轮询）：
 *   PTY → PtyOutputPump → VT(TerminalCore) → ObservationEngine.styledState →
 *   (sample 33ms) → _renderState → Compose grid。
 *
 * Spec ref: ATR 2.0 Final Spec §41 / §43 + P83 Terminal Finalization。
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val terminalRuntime: TerminalRuntime,
    // T82: Ubuntu 产品级生命周期 —— 依赖安装中心的路由底座（apt 命令只能跑在 Ubuntu 会话）。
    private val ubuntuLifecycle: UbuntuLifecycleCoordinator,
    // i18n：通知/安装日志文案（非 Compose 场景，LanguageManager 按当前语言取词）
    private val lang: LanguageManager
) : ViewModel() {

    private val prefs = context.getSharedPreferences("apex_terminal", Context.MODE_PRIVATE)

    /** 环境依赖安装器（ATR 2.0 — 用新 Runtime API，非旧 TerminalManager）。 */
    private val provisioner = EnvironmentProvisioner(terminalRuntime, ubuntuLifecycle)

    /** T82: Ubuntu 生命周期状态（安装/引导进度，UI 可订阅）。 */
    val ubuntuLifecycleState: StateFlow<UbuntuLifecycleCoordinator.LifecycleState> =
        ubuntuLifecycle.stateFlow

    // ═══════════════════════ 交互终端：会话管理 ═══════════════════════

    /** 顶部 tab 的会话视图模型。backend 由创建方记录（runtime 快照不含该信息）。 */
    data class SessionTab(
        val id: Long,
        val backendId: String,
        val runtimeType: String,
        val state: String,
        val isAlive: Boolean,
        val title: String?
    ) {
        val isUbuntu: Boolean get() = backendId == "linux-ubuntu"
    }

    private val _sessions = MutableStateFlow<List<SessionTab>>(emptyList())
    val sessions: StateFlow<List<SessionTab>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<Long?>(null)
    val activeSessionId: StateFlow<Long?> = _activeSessionId.asStateFlow()

    /** VM 自己创建的会话的 backend 记录（agent 创建的会话以 "agent" 展示）。 */
    private val sessionBackends = LinkedHashMap<Long, Pair<String, String>>()

    /**
     * 各会话最近一次由 shell 设置的窗口标题（OSC 0/1/2 —— `PS1` 里的 `\[\e]0;…\a\]`、
     * vim/tmux 也会设）。
     *
     * Termux / JuiceSSH / ConnectBot 都把标题显示在会话标签上：跑 `ssh host` 或
     * `vim file` 时标签会跟着变，多会话下不用靠猜。此前 `SessionTab.title` 恒为
     * null —— VT 层早就解析出标题了，只是没人往 UI 上接。
     */
    private val sessionTitles = LinkedHashMap<Long, String>()

    /** 活跃会话的 styled 屏（颜色/光标/scrollback；null = 未启动）。 */
    private val _renderState = MutableStateFlow<TerminalRenderSnapshot?>(null)
    val renderState: StateFlow<TerminalRenderSnapshot?> = _renderState.asStateFlow()

    /** 活跃会话的语义状态（会话状态 / 前台 job / prompt）。 */
    private val _semanticState = MutableStateFlow<TerminalSemanticState?>(null)
    val semanticState: StateFlow<TerminalSemanticState?> = _semanticState.asStateFlow()

    /** 终端操作反馈（toast 级消息，渲染在状态条）。 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun consumeNotice() { _notice.value = null }

    private var renderJob: Job? = null
    private var semanticJob: Job? = null
    private var pollJob: Job? = null
    private var creating = false

    /**
     * 交互行缓冲（黑白名单交互拦截用）：镜像 shell readline 当前行文本。
     * 仅跟踪「纯字符输入 + 回退删除」两种确定性变更；特殊键（方向/历史召回/
     * Ctrl 组合）使行状态不可知时清空缓冲，下次回车不检查（宁可漏检不误拦）。
     * 拦截 = 不写入回车，命令停留在 readline 未提交状态。
     */
    private val pendingLine = StringBuilder()

    /**
     * T85: Ubuntu 优先默认会话策略的挂起标记 —— 等待环境 READY 期间用户未手工
     * 创建过会话时，READY 到达后自动拉起 Ubuntu 会话（见 [init] 的第二个收集器）。
     * 用户一旦自己建了会话（含环境面板的「先用 Android Shell」）即失效。
     */
    @Volatile
    private var autoUbuntuSessionPending = false

    /** 会话创建互斥（UI 手动新建 / READY 自动拉起 / 依赖安装降级共享）。 */
    private val createMutex = Mutex()

    init {
        // Crash recovery (Spec §39): restore persisted sessions on startup.
        viewModelScope.launch {
            val recovered = terminalRuntime.recover()
            if (recovered.isNotEmpty()) {
                Log.i("TerminalVM", "Recovered ${recovered.size} sessions from persistence")
            }
            refreshSessionsInternal()
            if (_sessions.value.none { it.isAlive }) {
                // T85: Ubuntu 优先 —— 有完整 Linux 环境绝不默认降级到 Android toybox。
                // - READY → 直接建 Ubuntu 会话（bash / gcc / python3 真实可用）；
                // - 未 READY → 挂起等待（ApexApp 启动时已自动预备；这里 join 单飞
                //   兜底「冷启动直接进终端页」的竞态），READY 后自动建；
                // - FAILED → 停在环境面板等用户重试，不偷偷降级。
                if (ubuntuLifecycle.stateFlow.value.phase ==
                    UbuntuLifecycleCoordinator.Phase.READY
                ) {
                    createMutex.withLock { createSessionInternal(backendId = BACKEND_UBUNTU) }
                } else {
                    autoUbuntuSessionPending = true
                    // join 自动预备（幂等单飞；已进行中则等待共享结果）。
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { ubuntuLifecycle.ensureReady() }
                    }
                }
            } else {
                _sessions.value.firstOrNull { it.isAlive }?.let { selectSession(it.id) }
            }
            startSessionPolling()
        }

        // T85: 环境 READY 到达 → 若用户仍未手工建过会话，自动拉起 Ubuntu 会话。
        // 首次安装（后台解包 2~5 分钟）期间用户停在环境面板，无需任何点击。
        viewModelScope.launch {
            ubuntuLifecycle.stateFlow.collect { st ->
                if (st.phase == UbuntuLifecycleCoordinator.Phase.READY &&
                    autoUbuntuSessionPending &&
                    _sessions.value.none { it.isAlive }
                ) {
                    autoUbuntuSessionPending = false
                    createMutex.withLock { createSessionInternal(backendId = BACKEND_UBUNTU) }
                }
            }
        }
    }

    /** 从 runtime 拉取会话列表（状态/存活 + VM 记录的 backend 标签）。 */
    fun refreshSessions() {
        viewModelScope.launch { refreshSessionsInternal() }
    }

    private suspend fun refreshSessionsInternal() {
        val snap = terminalRuntime.snapshot(TerminalRuntime.SnapshotMode.SESSIONS)
            .getOrNull() ?: return
        val alive = snap.sessions.map { s ->
            val backend = sessionBackends[s.session.id]
                ?: ("agent" to if (s.session.shell.contains("bash", true) || s.session.shell.contains("proot", true))
                    "LINUX" else "ANDROID_LOCAL")
            SessionTab(
                id = s.session.id,
                backendId = backend.first,
                runtimeType = backend.second,
                state = s.session.state.name,
                isAlive = s.session.state in ALIVE_STATES,
                title = sessionTitles[s.session.id]
            )
        }
        _sessions.value = alive
        // 活跃会话消失（被 Agent close）→ 切到剩余首个，没有则置空（渲染占位）
        val active = _activeSessionId.value
        if (active != null && alive.none { it.id == active }) {
            val next = alive.firstOrNull { it.isAlive }
            if (next != null) selectSession(next.id) else _activeSessionId.value = null
        }
    }

    private fun startSessionPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(2000)
                refreshSessions()
            }
        }
    }

    /** 轻量状态刷新（会话状态标签），tab 徽章用。 */
    fun selectSession(id: Long) {
        if (_activeSessionId.value == id) return
        _activeSessionId.value = id
        // P2（跨会话残留）：交互行缓冲随会话切换清空 —— A 会话敲到一半的命令
        // 残留在 pendingLine 里，切到 B 后按空回车会拿旧命令做黑白名单检查，
        // 命中则空回车被拦截并弹指向旧命令的「已拦截」提示。
        pendingLine.setLength(0)
        observeActiveSession()
    }

    /** 切换 styled/semantic 收集者到当前活跃会话（事件驱动 + sample 防洪泛）。 */
    private fun observeActiveSession() {
        val sid = _activeSessionId.value ?: run {
            renderJob?.cancel(); semanticJob?.cancel()
            _renderState.value = null; _semanticState.value = null
            return
        }
        renderJob?.cancel()
        semanticJob?.cancel()
        _renderState.value = null
        _semanticState.value = null
        renderJob = viewModelScope.launch {
            // styled 投影只在有收集者时计算（ObservationEngine 背压契约）；
            // 33ms sample 把 feed 洪泛（cat 大文件 / gradle 日志）折叠到 ~30fps。
            terminalRuntime.styledScreenFlow(sid)?.sample(33)?.collect { snap ->
                _renderState.value = snap
                // 标题变了才回写并刷新 tab（避免每帧触发一次列表重组）
                val t = snap?.title?.trim().takeUnless { it.isNullOrEmpty() }
                if (t != null && t != sessionTitles[sid]) {
                    sessionTitles[sid] = t
                    refreshSessionsInternal()
                }
            }
        }
        semanticJob = viewModelScope.launch {
            terminalRuntime.semanticStateFlow(sid)?.collect { state ->
                _semanticState.value = state
            }
        }
    }

    fun createSession(backendId: String) {
        if (creating) return
        creating = true
        // 用户手工新建（含环境面板「先用 Android Shell」）→ 取消 READY 自动拉起
        autoUbuntuSessionPending = false
        viewModelScope.launch {
            try {
                createMutex.withLock { createSessionInternal(backendId) }
            } finally {
                creating = false
            }
        }
    }

    // ═══ T87：本地 Shell profile（mksh rc 播种）═══

    /** 本地 shell home（$filesDir/linux/shell —— 可写，untrusted_app 域内）。 */
    private val localShellHome by lazy {
        java.io.File(context.filesDir, "linux/shell")
    }

    /**
     * 确保 mksh rc 就绪并返回注入 env（HOME/ENV/TERM/COLORTERM）。
     *
     * 失败（磁盘满等）→ 空 map：会话照常创建（回到旧行为 —— 裸提示符），
     * 绝不因 profile 失败拒绝创建 shell。
     */
    private fun ensureLocalShellProfile(): Map<String, String> = runCatching {
        val home = com.apex.agent.platform.terminal.profile.GuestShellProfile.ensureShellHome(localShellHome)
        val rc = java.io.File(home, com.apex.agent.platform.terminal.profile.GuestShellProfile.RC_FILENAME)
        if (!rc.isFile || rc.length() == 0L) {
            rc.writeText(com.apex.agent.platform.terminal.profile.GuestShellProfile.generate(android.os.Build.MODEL ?: "android"))
        }
        com.apex.agent.platform.terminal.profile.GuestShellProfile.shellEnv(home.absolutePath, rc.absolutePath)
    }.getOrDefault(emptyMap())

    private suspend fun createSessionInternal(backendId: String) {
        if (backendId == BACKEND_UBUNTU) {
            // Ubuntu 会话：先确保 rootfs + bootstrap 就绪（长时操作，进度经
            // ubuntuLifecycleState 流回横幅）。取消/失败 → 诚实中止。
            // T84：withContext(IO) —— ensureReady 链含 capability 探测（阻塞
            // proot exec），provisioner/bootstrap 已内嵌 IO，此处兜住协调器自身
            // 的 probeFn/repairFn 端口（Main.immediate 调用曾直接吃满主线程）。
            val r = withContext(kotlinx.coroutines.Dispatchers.IO) {
                ubuntuLifecycle.ensureReady()
            }
            if (r is UbuntuLifecycleCoordinator.EnsureResult.Failed) {
                _notice.value = lang.getString(R.string.term_notice_ubuntu_unavailable, r.message.take(120))
                return
            }
        }
        // P1 修复（主线程 fork/exec）：terminalRuntime.create 链路含
        // LinuxPRootBackend.availability()/prepare() —— 真实 ProcessBuilder fork
        //（proot --version 探针，create 内各做一次共 2 次）、符号链接创建、home
        // skel 拷贝、workspace mkdirs、forkpty 本身。旧实现直接跑在
        // viewModelScope(Main.immediate)，慢设备上卡顿/StrictMode 违例/ANR 风险。
        // T87：LOCAL 会话注入 mksh profile（可写 HOME + $ENV rc + TERM）——
        // Shell 模式补 user@host:cwd 提示符、历史记录、cmds/help 命令发现。
        val localEnv = if (backendId == BACKEND_LOCAL) ensureLocalShellProfile() else emptyMap()
        val created = withContext(kotlinx.coroutines.Dispatchers.IO) {
            terminalRuntime.create(backendId = backendId, env = localEnv)
        }
        val result = created.getOrElse { e ->
            _notice.value = lang.getString(R.string.term_notice_create_failed, e.message?.take(120) ?: "")
            return
        }
        sessionBackends[result.sessionId] = result.backendId to result.runtimeType
        refreshSessionsInternal()
        selectSession(result.sessionId)
    }

    fun closeSession(id: Long) {
        viewModelScope.launch {
            terminalRuntime.close(id, force = true)
            sessionBackends.remove(id)
            sessionTitles.remove(id)
            // 关闭的是当前会话时同步清交互行缓冲（语义同 selectSession 的清理）
            if (_activeSessionId.value == id) {
                pendingLine.setLength(0)
            }
            refreshSessionsInternal()
            if (_activeSessionId.value == id) {
                _sessions.value.firstOrNull { it.isAlive }?.let { selectSession(it.id) }
            }
        }
    }

    // ═══════════════════════ 交互终端：输入 / resize ═══════════════════════

    /** 写入用户文本（IME 提交 / 硬件键盘字符），RAW 直通 PTY。
     *
     * 回车（IME 以 \r 文本下发，:terminal-view 已把 \n 归一为 \r）视为行提交：
     * 命中黑白名单 → 拦截整个写入（含回车），命令不执行。
     */
    fun sendInput(text: String) {
        // 旧实现是无提示的 `?: return`：会话没了的情况下用户敲半天没反应还以为键盘坏了，
        // 状态条也不给任何线索。这里给出明确反馈。
        val sid = _activeSessionId.value
        if (sid == null) {
            _notice.value = lang.getString(R.string.term_notice_no_session_input)
            return
        }
        if (text.isEmpty()) return

        val newlineIdx = text.indexOfFirst { it == '\r' || it == '\n' }
        if (newlineIdx >= 0) {
            val before = text.substring(0, newlineIdx)
            val candidate = (pendingLine.toString() + before).trim()
            if (candidate.isNotBlank() && !isCommandAllowed(candidate)) {
                _notice.value = lang.getString(R.string.term_notice_blocked, candidate.take(40))
                return // 不写入（含回车）—— readline 行保持未提交；缓冲保留继续同步追加
            }
            // T87：提交时刻记入历史（通过门禁的命令才有资格入史）
            if (candidate.isNotBlank()) commandHistory.record(candidate)
            // 放行：行缓冲重置，回车后的剩余字符属于下一行缓冲
            pendingLine.setLength(0)
            val rest = text.substring(newlineIdx + 1)
            if (rest.isNotEmpty()) pendingLine.append(rest)
        } else {
            pendingLine.append(text)
        }

        viewModelScope.launch {
            terminalRuntime.write(sid, InputOwner.USER, TerminalRuntime.WriteKind.RAW, text = text)
                .onFailure { e ->
                    // T87（Ubuntu「输入失败」诚实化）：WriteFailed 的最常见根因是
                    // 会话进程已退出（master EIO）—— 旧文案「输入失败:WriteFailed」
                    // 让用户以为是键盘/输入链路坏了。按错误语义分流：会话死 →
                    // 「会话已退出」+ 重启指引；其余（策略拦截等）→ 原文。
                    val msg = e.message ?: ""
                    val dead = msg.contains("WriteFailed") || msg.contains("SessionNotFound") ||
                        msg.contains("SessionClosed") || msg.contains("session closed")
                    _notice.value = if (dead) {
                        lang.getString(R.string.term_notice_session_dead)
                    } else {
                        lang.getString(R.string.term_notice_input_failed, msg.take(80))
                    }
                }
        }
    }

    /**
     * T87：重启当前会话（保留 backend）—— 死会话覆盖层的「重启会话」按钮。
     *
     * 语义：close（force）→ 同 backend 重建。Agent 创建的会话（backend 记录为
     * "agent"）按 runtimeType 映射回真实 backendId（LINUX → Ubuntu，否则 LOCAL）。
     */
    fun restartActiveSession() {
        val active = _activeSessionId.value ?: return
        val tab = _sessions.value.firstOrNull { it.id == active } ?: return
        val backend = when {
            tab.backendId == BACKEND_UBUNTU || tab.backendId == BACKEND_LOCAL -> tab.backendId
            tab.runtimeType == "LINUX" -> BACKEND_UBUNTU
            else -> BACKEND_LOCAL
        }
        viewModelScope.launch {
            terminalRuntime.close(active, force = true)
            sessionBackends.remove(active)
            sessionTitles.remove(active)
            if (_activeSessionId.value == active) pendingLine.setLength(0)
            refreshSessionsInternal()
            createMutex.withLock { createSessionInternal(backend) }
        }
    }

    /**
     * 发送特殊键：箭头按 DECCKM 编码（ESC O x / ESC [ x），其余经 TerminalKey
     *（InputManager 映射，模式无关）。粘贴按 bracketed-paste 包裹。
     *
     * ENTER = 行提交（黑白名单检查，拦截则不写入）；BACKSPACE = 行缓冲退格；
     * 其余特殊键（方向/历史/TAB…）行状态不可知 → 清空行缓冲（下次回车不检查）。
     *
     * T88（3）：[mods] 为 xterm 修饰位掩码（KeyEventMapping.MOD_* / emulator
     * KeyModifiers 同值）——非零时走 [encodeKeyWithMods] 完整修饰协议
     *（Shift+方向 = ESC[1;2A 词选择、Ctrl+F 键等）；零 = 旧路径不变（默认值
     * 保证既有单参调用方/函数引用完全兼容）。
     */
    fun sendKey(key: TerminalKey, mods: Int = 0) {
        val sid = _activeSessionId.value ?: return
        viewModelScope.launch {
            when (key) {
                TerminalKey.ENTER -> {
                    val candidate = pendingLine.toString().trim()
                    if (candidate.isNotBlank() && !isCommandAllowed(candidate)) {
                        _notice.value = lang.getString(R.string.term_notice_blocked, candidate.take(40))
                        return@launch
                    }
                    // T87：提交时刻记入历史
                    if (candidate.isNotBlank()) commandHistory.record(candidate)
                    pendingLine.setLength(0)
                }
                TerminalKey.BACKSPACE -> if (pendingLine.isNotEmpty()) pendingLine.setLength(pendingLine.length - 1)
                else -> pendingLine.setLength(0)
            }
            if (mods != 0) {
                val bytes = encodeKeyWithMods(key, mods)
                if (bytes != null && bytes.isNotEmpty()) {
                    terminalRuntime.write(
                        sid, InputOwner.USER, TerminalRuntime.WriteKind.RAW,
                        text = String(bytes, Charsets.ISO_8859_1)
                    )
                    return@launch
                }
            }
            if (key == TerminalKey.ARROW_UP || key == TerminalKey.ARROW_DOWN ||
                key == TerminalKey.ARROW_LEFT || key == TerminalKey.ARROW_RIGHT
            ) {
                val bytes = KeySequenceEncoder.encodeKey(
                    key, _renderState.value?.applicationCursor ?: false
                )
                terminalRuntime.write(
                    sid, InputOwner.USER, TerminalRuntime.WriteKind.RAW,
                    text = String(bytes, Charsets.ISO_8859_1)
                )
            } else {
                terminalRuntime.write(
                    sid, InputOwner.USER, TerminalRuntime.WriteKind.KEY, key = key
                )
            }
        }
    }

    /**
     * T88（3）：键身份 + 修饰位 → xterm 参数化序列（KeyEventMapping 完整协议）。
     *
     * 平台 [TerminalKey] → Android keycode 对照，再经 [KeyEventMapping.encode]
     * 出 `ESC[1;m{final}` 形参化序列（DECCKM/DECKPAM 模式感知）。无对照
     *（CTRL_C 等信号语义键）或编码器无映射 → null（调用方回落旧路径）。
     */
    private fun encodeKeyWithMods(key: TerminalKey, mods: Int): ByteArray? {
        val keyCode = when (key) {
            TerminalKey.ARROW_UP -> KeyEventMapping.KEYCODE_DPAD_UP
            TerminalKey.ARROW_DOWN -> KeyEventMapping.KEYCODE_DPAD_DOWN
            TerminalKey.ARROW_LEFT -> KeyEventMapping.KEYCODE_DPAD_LEFT
            TerminalKey.ARROW_RIGHT -> KeyEventMapping.KEYCODE_DPAD_RIGHT
            TerminalKey.HOME -> KeyEventMapping.KEYCODE_MOVE_HOME
            TerminalKey.END -> KeyEventMapping.KEYCODE_MOVE_END
            TerminalKey.PAGE_UP -> KeyEventMapping.KEYCODE_PAGE_UP
            TerminalKey.PAGE_DOWN -> KeyEventMapping.KEYCODE_PAGE_DOWN
            TerminalKey.INSERT -> KeyEventMapping.KEYCODE_INSERT
            TerminalKey.DELETE -> KeyEventMapping.KEYCODE_FORWARD_DEL
            TerminalKey.ENTER -> KeyEventMapping.KEYCODE_ENTER
            TerminalKey.TAB -> KeyEventMapping.KEYCODE_TAB
            TerminalKey.BACKSPACE -> KeyEventMapping.KEYCODE_DEL
            TerminalKey.ESC -> KeyEventMapping.KEYCODE_ESCAPE
            in TerminalKey.F1..TerminalKey.F12 ->
                KeyEventMapping.KEYCODE_F1 + (key.ordinal - TerminalKey.F1.ordinal)
            else -> 0
        }
        if (keyCode == 0) return null
        val modes = KeyEventMapping.KeyModes(
            applicationCursor = _renderState.value?.applicationCursor ?: false,
            applicationKeypad = _renderState.value?.applicationKeypad ?: false,
            numLock = true
        )
        return KeyEventMapping.encode(keyCode, mods, modes)
    }

    /** Ctrl+字母（工具栏 CTRL 锁存 / 硬件 Ctrl 组合）。
     *
     * Ctrl+C / Ctrl+U 等会终止/清除 readline 当前行 → 行缓冲同步清空。
     */
    fun sendControlChar(ch: Char) {
        val sid = _activeSessionId.value ?: return
        val bytes = KeySequenceEncoder.controlByte(ch) ?: return
        pendingLine.setLength(0)
        viewModelScope.launch {
            terminalRuntime.write(
                sid, InputOwner.USER, TerminalRuntime.WriteKind.RAW,
                text = String(bytes, Charsets.ISO_8859_1)
            )
        }
    }

    /** 粘贴（bracketed-paste 感知）。
     *
     * 首行命令命中黑白名单 → 拦截整次粘贴（bracketed-paste OFF 时粘贴即执行，
     * 必须拦在写入前）；首行检查放行后行缓冲清空（多行粘贴行状态不可知）。
     */
    fun pasteText(text: String) {
        val sid = _activeSessionId.value ?: return
        if (text.isEmpty()) return
        val firstLine = text.lineSequence().firstOrNull()?.trim() ?: ""
        if (firstLine.isNotBlank() && !isCommandAllowed(firstLine)) {
            _notice.value = lang.getString(R.string.term_notice_paste_blocked, firstLine.take(40))
            return
        }
        pendingLine.setLength(0)
        viewModelScope.launch {
            val bytes = KeySequenceEncoder.encodePaste(
                text, _renderState.value?.bracketedPaste ?: false
            )
            // P1（CJK 乱码）：bytes 直通 —— 旧实现把 UTF-8 字节经 ISO-8859-1 转
            // String 再按 UTF-8 重编码（Runtime 侧 InputManager 按 UTF-8 写 PTY），
            // 剪贴板里的中文/emoji/重音字符全部变成 "ä½ " 类乱码。
            // Runtime 的 RAW 路径已支持 bytes 直通（T85，注释明言「消除双重编码」），
            // UI 调用方此前没有同步切换 —— 现在对齐。
            terminalRuntime.write(
                sid, InputOwner.USER, TerminalRuntime.WriteKind.RAW,
                bytes = bytes
            )
        }
    }

    /** 视图尺寸变化 → PTY resize（SIGWINCH + VT 同步）。 */
    fun resizeTerminal(rows: Int, cols: Int) {
        val sid = _activeSessionId.value ?: return
        if (rows < 2 || cols < 4) return
        viewModelScope.launch {
            terminalRuntime.resize(sid, rows, cols)
        }
    }

    // ═══════════════════ T86：Termux 对齐输入扩展（硬件键/鼠标/焦点/字号）═══════════════════

    /**
     * 硬件键盘完整映射（KeyEventMapping —— xterm 修饰键协议）。
     *
     * 覆盖旧 [sendKey] 路径之外的键位：Shift/Alt/Ctrl+方向键（`ESC[1;5A` 类）、
     * F1-F12、小键盘（DECKPAM 感知）、Shift+Tab、Alt+字符（meta 化）。
     * 无映射（返回 null）时 UI 放行给 IME/系统。
     *
     * 行缓冲语义与 [sendKey] 一致：ENTER=提交检查、BACKSPACE=退格、其余清空。
     */
    fun sendHardwareKey(keyCode: Int, mods: Int, unicodeChar: Int = 0) {
        val sid = _activeSessionId.value ?: return
        val render = _renderState.value
        val modes = KeyEventMapping.KeyModes(
            applicationCursor = render?.applicationCursor ?: false,
            applicationKeypad = render?.applicationKeypad ?: false,
            numLock = true
        )
        val bytes = KeyEventMapping.encode(keyCode, mods, modes, unicodeChar) ?: return
        when (keyCode) {
            KeyEventMapping.KEYCODE_ENTER -> {
                val candidate = pendingLine.toString().trim()
                if (candidate.isNotBlank() && !isCommandAllowed(candidate)) {
                    _notice.value = lang.getString(R.string.term_notice_blocked, candidate.take(40))
                    return
                }
                pendingLine.setLength(0)
            }
            KeyEventMapping.KEYCODE_DEL -> if (pendingLine.isNotEmpty()) pendingLine.setLength(pendingLine.length - 1)
            else -> pendingLine.setLength(0)
        }
        viewModelScope.launch {
            terminalRuntime.write(
                sid, InputOwner.USER, TerminalRuntime.WriteKind.RAW,
                text = String(bytes, Charsets.ISO_8859_1)
            )
        }
    }

    /**
     * 鼠标事件（触摸/手写笔 → MouseEncoder → PTY）。
     *
     * guest 开启 DECSET 1000/1002/1003 后，vim/tmux/htop 把触摸点击当鼠标用。
     * 坐标 1-based（xterm 习惯）；未开启跟踪时编码器返回 null → 静默忽略。
     */
    fun sendMouseEvent(type: TerminalMouseEventType, button: Int, mods: Int, col: Int, row: Int) {
        val sid = _activeSessionId.value ?: return
        val mode = _renderState.value?.mouseMode ?: return
        val bytes = MouseEncoder.encode(type, button, mods, col, row, mode) ?: return
        viewModelScope.launch {
            terminalRuntime.write(
                sid, InputOwner.USER, TerminalRuntime.WriteKind.RAW,
                text = String(bytes, Charsets.ISO_8859_1)
            )
        }
    }

    /**
     * 滚轮路由：跟踪开启 → 滚轮当鼠标事件进 PTY（vim 里滚 = 移动光标）；
     * 备用屏 + 1007 → 方向键；否则返回 false 让 UI 滚动视口（正常行为）。
     *
     * @return true = 已编码进 PTY（UI 不要再滚视口）
     */
    fun sendWheel(up: Boolean): Boolean {
        val sid = _activeSessionId.value ?: return false
        val render = _renderState.value ?: return false
        val bytes = when {
            render.mouseMode.enabled -> MouseEncoder.encode(
                if (up) TerminalMouseEventType.WHEEL_UP else TerminalMouseEventType.WHEEL_DOWN,
                0, 0, render.cursorCol + 1, render.cursorRow + 1, render.mouseMode
            )
            render.mouseMode.altScroll && render.alternateScreen -> MouseEncoder.altScrollArrow(up)
            else -> null
        } ?: return false
        viewModelScope.launch {
            terminalRuntime.write(
                sid, InputOwner.USER, TerminalRuntime.WriteKind.RAW,
                text = String(bytes, Charsets.ISO_8859_1)
            )
        }
        return true
    }

    /**
     * 窗口焦点变化 → ESC[I / ESC[O（DECSET 1004）。
     * vim FocusGained/FocusLost、tmux focus-events 依赖此序列。
     */
    fun notifyTerminalFocus(gained: Boolean) {
        val sid = _activeSessionId.value ?: return
        val mode = _renderState.value?.focusMode ?: return
        val bytes = encodeFocusEvent(gained, mode) ?: return
        viewModelScope.launch {
            terminalRuntime.write(
                sid, InputOwner.USER, TerminalRuntime.WriteKind.RAW,
                text = String(bytes, Charsets.ISO_8859_1)
            )
        }
    }

    // ═══════════════════════ Ubuntu 生命周期入口 ═══════════════════════

    /** 一键解包 Ubuntu（横幅按钮）—— ensureReady 全链：离线解包 → 配置 → bootstrap（可降级）。 */
    fun installUbuntu() {
        viewModelScope.launch {
            // T84：IO —— 完整 rootfs（~300MB+ 档，解压分钟级）绝不能压 Main。
            val r = withContext(kotlinx.coroutines.Dispatchers.IO) { ubuntuLifecycle.ensureReady() }
            if (r is UbuntuLifecycleCoordinator.EnsureResult.Failed) {
                _notice.value = lang.getString(R.string.term_notice_unpack_failed, r.message.take(160))
            }
        }
    }

    /** 环境中心：取消进行中的安装（下载字节保留，下次断点续传）。 */
    fun cancelUbuntuInstall() {
        viewModelScope.launch {
            val r = ubuntuLifecycle.cancelInstall()
            if (!r.cancelled) _notice.value = r.message
        }
    }

    /** 环境中心：产品级修复（不触发大下载；detect → repair → verify）。 */
    fun repairUbuntu() {
        viewModelScope.launch {
            // T84：IO —— repair 链是文件/子进程操作。
            val r = withContext(kotlinx.coroutines.Dispatchers.IO) { ubuntuLifecycle.repair() }
            _notice.value = if (r.verifiedHealthy) lang.getString(R.string.term_notice_repair_ok)
            else lang.getString(R.string.term_notice_repair_unresolved, r.detail ?: r.actions.joinToString().take(120))
        }
    }

    /** 环境中心：删除 Ubuntu rootfs（用户 home/workspace 保留）。 */
    fun removeUbuntu() {
        viewModelScope.launch {
            // T84：IO —— 删除 1GB+ 版本目录是重 IO。
            val r = withContext(kotlinx.coroutines.Dispatchers.IO) { ubuntuLifecycle.removeRootfs() }
            _notice.value = r.message
            if (r.removed) refreshRootfsSize()
        }
    }

    /** rootfs 磁盘占用（bytes；null = 未安装）—— 环境中心/存储管理展示。 */
    private val _rootfsSize = MutableStateFlow<Long?>(null)
    val rootfsSize: StateFlow<Long?> = _rootfsSize.asStateFlow()

    init { refreshRootfsSize() }

    fun refreshRootfsSize() {
        viewModelScope.launch {
            _rootfsSize.value = ubuntuLifecycle.rootfsSizeBytes()
        }
    }

    /**
     * Ubuntu 安装/引导聚合进度（install:percent/bytes + bootstrap:stage/message）。
     * 冷流持续收集，状态进入 READY/NOT_INSTALLED 时清空展示。
     */
    private val _ubuntuProgress = MutableStateFlow<UbuntuLifecycleCoordinator.LifecycleProgress?>(null)
    val ubuntuProgress: StateFlow<UbuntuLifecycleCoordinator.LifecycleProgress?> = _ubuntuProgress.asStateFlow()

    init {
        viewModelScope.launch {
            ubuntuLifecycle.progressFlow().collect { p ->
                _ubuntuProgress.value = p
                // 安装完成后刷新占用（下载/解压会显著改变磁盘占用）
                // P1 修复（布尔优先级）：&& 先于 || 结合 —— 旧写法
                // `a && b || c` 等价于 `(a && b) || c`，任何以 REMOVED 结尾的
                // stage（含未来 bootstrap 可能新增的移除态）都会触发刷新；
                // 显式括号表达意图：install 域内的 READY / REMOVED 才刷新。
                if (p.stage.startsWith("install:") &&
                    (p.stage.endsWith("READY") || p.stage.endsWith("REMOVED"))
                ) {
                    refreshRootfsSize()
                }
            }
        }
    }

    // ═══ 终端设置 ═══
    // T87：配色方案状态（TerminalColorSchemeSettings 持久化 + 热切换）。
    // 零方案代码进 VM（SRP）：id/boldAsBright 透传给渲染树。
    private val schemeSettings = com.apex.agent.ui.screen.terminal.scheme.TerminalColorSchemeSettings(
        com.apex.agent.ui.screen.terminal.scheme.TerminalColorSchemeSettings.PrefsStore(
            context, "apex_terminal"
        )
    )

    /** 当前配色方案 id（渲染树解析为完整方案）。 */
    val colorSchemeId: StateFlow<String> = schemeSettings.schemeId

    /** bold → 亮色提升（xterm 传统；ls/ls 彩色输出依赖）。 */
    val boldAsBright: StateFlow<Boolean> = schemeSettings.boldAsBright

    /** 切换配色方案（未知 id 拒绝；渲染树经 StateFlow 自动换色）。 */
    fun setColorScheme(id: String) {
        schemeSettings.setSchemeId(id)
    }

    /** bold-as-bright 开关。 */
    fun setBoldAsBright(enabled: Boolean) {
        schemeSettings.setBoldAsBright(enabled)
    }

    /** 当前配色方案本地化显示名（zh → nameZh；供设置抽屉展示）。 */
    fun currentSchemeDisplayName(): String =
        schemeSettings.scheme.displayName(isZhLanguage())

    /** 语言判定（zh 显式 → 中文；system → 设备 Locale；en → 英文）。 */
    private fun isZhLanguage(): Boolean = when (lang.language.value) {
        "zh" -> true
        "en" -> false
        else -> runCatching {
            val locales = context.resources.configuration.locales
            locales.size() > 0 && locales[0].language == "zh"
        }.getOrDefault(false)
    }

    data class TerminalSettings(
        val fontSize: Int = 13,
        val monochrome: Boolean = false,
        /** 键盘辅助行（ESC/TAB/CTRL/箭头…）显隐 —— 小屏手机可隐藏换取显示区。 */
        val showKeybar: Boolean = true,
        /**
         * 响铃（BEL 0x07）时振动一下 —— Termux/ConnectBot 的常规反馈，
         * tab 补全失败、Ctrl+G、命令报错都会发 BEL。默认开。
         */
        val vibrateOnBell: Boolean = true,
        /**
         * 终端页保持屏幕常亮 —— 看长任务输出（编译 / apt / 训练日志）时不会被息屏打断。
         * Termux 默认持有 wakelock，此项对齐该行为（默认关，交用户选择）。
         */
        val keepScreenOn: Boolean = false
    ) {
        /** 字号合法区间（双指捏合缩放也走这个钳制）。 */
        companion object {
            const val MIN_FONT_SIZE = 8
            const val MAX_FONT_SIZE = 24
        }
    }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<TerminalSettings> = _settings.asStateFlow()

    // ═══ T87：命令历史（提交时刻记录；设置抽屉可查看/清空）═══
    private val commandHistory =
        com.apex.agent.ui.screen.terminal.history.TerminalCommandHistory(context)

    /** 历史（最新在前；Termux history 的可视化等价物）。 */
    val commandHistoryEntries: StateFlow<List<String>> = commandHistory.entries

    /** 清空历史（设置抽屉「清空」确认后调用）。 */
    fun clearCommandHistory() = commandHistory.clear()

    // ═══ T87：扩展键（用户自定义宏行 —— Termux extra-keys 等价物）═══
    private val _extraKeys = MutableStateFlow(loadExtraKeys())

    /** 扩展键（用户宏；空 = 不渲染扩展行）。 */
    val extraKeys: StateFlow<List<com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig.ExtraKey>> =
        _extraKeys.asStateFlow()

    /** 追加一个扩展键（spec 形如 `标签=cmd:apt-get update`；非法 spec 静默拒绝）。 */
    fun addExtraKey(spec: String) {
        val key = com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig.parseKey(spec.trim())
            ?: return
        val next = com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig.appendKey(
            listOf(_extraKeys.value), key
        ).flatten()
        _extraKeys.value = next
        persistExtraKeys(next)
    }

    /** 移除指定标签的扩展键。 */
    fun removeExtraKey(label: String) {
        val next = _extraKeys.value.filterNot { it.label == label }
        _extraKeys.value = next
        persistExtraKeys(next)
    }

    /** 重置为默认布局（设置抽屉「恢复默认」）。 */
    fun resetExtraKeys() {
        val next = com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig.DEFAULT_LAYOUT.flatten()
        _extraKeys.value = next
        persistExtraKeys(next)
    }

    private fun loadExtraKeys(): List<com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig.ExtraKey> {
        val stored = prefs.getString("term_extra_keys", null)
        return com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig.parse(stored)
            ?.flatten()
            ?: com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig.DEFAULT_LAYOUT.flatten()
    }

    private fun persistExtraKeys(keys: List<com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig.ExtraKey>) {
        val ser = com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig.serialize(listOf(keys))
        prefs.edit().putString("term_extra_keys", ser).apply()
    }

    fun updateSettings(block: TerminalSettings.() -> TerminalSettings) {
        val next = _settings.value.block().let {
            // v1.4.4 UX 审查：设置抽屉旧上限 32 与捏合钳制 24 不一致 —— 用户调到 25..32 后
            // 一次缩放即 8 级跳变；抽屉上限已统一为常量，持久化前再钳制兑底
            // （防旧版本已落盘的越界值继续生效）。
            it.copy(fontSize = it.fontSize.coerceIn(TerminalSettings.MIN_FONT_SIZE, TerminalSettings.MAX_FONT_SIZE))
        }
        prefs.edit()
            .putInt("term_font_size", next.fontSize)
            .putBoolean("term_monochrome", next.monochrome)
            .putBoolean("term_show_keybar", next.showKeybar)
            .putBoolean("term_vibrate_bell", next.vibrateOnBell)
            .putBoolean("term_keep_screen_on", next.keepScreenOn)
            .apply()
        _settings.value = next
    }

    private fun loadSettings() = TerminalSettings(
        fontSize = prefs.getInt("term_font_size", 13),
        monochrome = prefs.getBoolean("term_monochrome", false),
        showKeybar = prefs.getBoolean("term_show_keybar", true),
        vibrateOnBell = prefs.getBoolean("term_vibrate_bell", true),
        keepScreenOn = prefs.getBoolean("term_keep_screen_on", false)
    )

    /** 字号调整（钳制在 [TerminalSettings.MIN_FONT_SIZE]..[TerminalSettings.MAX_FONT_SIZE]）。 */
    fun setFontSize(size: Int) {
        val clamped = size.coerceIn(TerminalSettings.MIN_FONT_SIZE, TerminalSettings.MAX_FONT_SIZE)
        if (clamped == _settings.value.fontSize) return
        updateSettings { copy(fontSize = clamped) }
    }

    // ═══ 黑名单 / 白名单命令 ═══
    private val _blacklist = MutableStateFlow(loadSet("cmd_blacklist"))
    val blacklist: StateFlow<Set<String>> = _blacklist.asStateFlow()

    private val _whitelist = MutableStateFlow(loadSet("cmd_whitelist"))
    val whitelist: StateFlow<Set<String>> = _whitelist.asStateFlow()

    fun addBlacklist(cmd: String) = editSet("cmd_blacklist", _blacklist) { add(normalize(cmd)) }
    fun removeBlacklist(cmd: String) = editSet("cmd_blacklist", _blacklist) { remove(normalize(cmd)) }
    fun addWhitelist(cmd: String) = editSet("cmd_whitelist", _whitelist) { add(normalize(cmd)) }
    fun removeWhitelist(cmd: String) = editSet("cmd_whitelist", _whitelist) { remove(normalize(cmd)) }

    /**
     * 交互输入的命令头检查（与 TerminalModule 动态策略同源的 prefs 数据，
     * 但仅消费用户名单；交互路径的内置默认危险命令拦截由用户自行把条目
     * 加入黑名单完成 —— 自己敲的命令接 Termux 哲学：不过滤）。
     *
     * 匹配 = 命令头 token 精确等值（与 CommandPolicy 的 token 语义一致，
     * 消除旧 startsWith 前缀误拦：“rm” 不再误拦 “rmdir...” 的头 token）。
     */
    fun isCommandAllowed(command: String): Boolean {
        val head = command.trim().substringBefore(' ').lowercase()
        if (head.isEmpty()) return true
        if (_blacklist.value.any { head == it }) return false
        val wl = _whitelist.value
        if (wl.isNotEmpty()) {
            return head in wl
        }
        return true
    }

    private fun normalize(cmd: String) = cmd.trim().lowercase().substringBefore(' ')

    private fun loadSet(key: String): Set<String> =
        prefs.getStringSet(key, emptySet()) ?: emptySet()

    private fun editSet(key: String, flow: MutableStateFlow<Set<String>>, mutate: MutableSet<String>.() -> Unit) {
        val next = flow.value.toMutableSet().apply(mutate)
        prefs.edit().putStringSet(key, next).apply()
        flow.value = next
    }

    // ═══ 环境依赖下载中心（保留原有职责）═══
    data class DepItem(
        val id: String,
        val name: String,
        val group: DepGroup,
        val installOfficial: String,
        val installMirror: String,
        val checkCommand: String
    )

    enum class DepGroup { GENERAL, ANDROID }

    val depItems: List<DepItem> =
        com.apex.agent.environment.DepCatalog.ALL.map {
            DepItem(it.id, it.name, DepGroup.valueOf(it.group.name), it.installOfficial, it.installMirror, it.checkCommand)
        }

    private val _useMirror = MutableStateFlow(prefs.getBoolean("dep_use_mirror", true))
    val useMirror: StateFlow<Boolean> = _useMirror.asStateFlow()

    fun setUseMirror(on: Boolean) {
        prefs.edit().putBoolean("dep_use_mirror", on).apply()
        _useMirror.value = on
        provisioner.setUseMirror(on)
    }

    data class InstallState(
        val runningId: String? = null,
        val log: String = "",
        val useMirror: Boolean = true
    )

    private val _install = MutableStateFlow(InstallState(useMirror = _useMirror.value))
    val install: StateFlow<InstallState> = _install.asStateFlow()

    private var depSessionId: Long? = null

    fun installDep(item: DepItem) {
        val useMirror = _useMirror.value
        val cmd = if (useMirror) item.installMirror else item.installOfficial
        runCommand(item.id, cmd)
    }

    fun installAll(onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _install.update { it.copy(runningId = "__all__", log = it.log + lang.getString(R.string.term_notice_install_all_start, _useMirror.value.toString())) }
            depItems.forEachIndexed { index, item ->
                onProgress(index, depItems.size)
                val cmd = if (_useMirror.value) item.installMirror else item.installOfficial
                execAndAppend(item.id, cmd)
            }
            _install.update { it.copy(runningId = null, log = it.log + lang.getString(R.string.term_notice_install_all_done)) }
        }
    }

    fun installAndroidOnly(onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val items = depItems.filter { it.group == DepGroup.ANDROID }
            _install.update { it.copy(runningId = "__android__", log = it.log + lang.getString(R.string.term_notice_install_android_start, _useMirror.value.toString())) }
            items.forEachIndexed { index, item ->
                onProgress(index, items.size)
                val cmd = if (_useMirror.value) item.installMirror else item.installOfficial
                execAndAppend(item.id, cmd)
            }
            _install.update { it.copy(runningId = null, log = it.log + lang.getString(R.string.term_notice_install_android_done)) }
        }
    }

    private fun runCommand(id: String, cmd: String) {
        viewModelScope.launch {
            _install.update { it.copy(runningId = id, log = it.log + "\n▶ [$id] $cmd\n") }
            execAndAppend(id, cmd)
            _install.update { it.copy(runningId = null) }
        }
    }

    private suspend fun execAndAppend(id: String, cmd: String) {
        val sid = ensureDepInstallSession() ?: run {
            _install.update { it.copy(log = it.log + lang.getString(R.string.term_notice_no_pty)) }
            return
        }
        val output = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val runResult = terminalRuntime.run(sid, cmd, InputOwner.SYSTEM, background = false)
            val run = runResult.getOrElse { return@withContext lang.getString(R.string.term_notice_run_failed, it.message ?: "") }
            val waitResult = terminalRuntime.wait(sid, com.apex.agent.platform.terminal.wait.WaitCondition.ProcessExited(jobId = run.jobId), 120_000)
            val wait = waitResult.getOrElse { return@withContext lang.getString(R.string.term_notice_wait_failed, it.message ?: "") }
            val exitCode = when (wait) {
                is com.apex.agent.platform.terminal.wait.WaitResult.Matched -> {
                    val ev = wait.event
                    if (ev is com.apex.agent.platform.terminal.events.TerminalEvent.ProcessExited) ev.exitCode ?: -1 else 0
                }
                is com.apex.agent.platform.terminal.wait.WaitResult.Timeout -> {
                    terminalRuntime.signal(sid, com.apex.agent.platform.terminal.io.UnixSignal.SIGKILL, InputOwner.SYSTEM, run.jobId)
                    return@withContext lang.getString(R.string.term_notice_wait_timeout)
                }
                is com.apex.agent.platform.terminal.wait.WaitResult.SessionGone -> return@withContext lang.getString(R.string.term_notice_session_gone)
            }
            val obs = terminalRuntime.observe(sid, TerminalRuntime.ObserveMode.RAW, run.startCursor, 65536)
                .getOrNull()?.raw ?: ""
            val tail = if (obs.length > 4000) lang.getString(R.string.term_notice_truncated) + obs.takeLast(4000) else obs
            tail + if (exitCode != 0) "\n[exit=$exitCode]\n" else "\n"
        }
        _install.update { it.copy(log = it.log + output) }
    }

    /**
     * T82 断点修复：依赖安装的会话路由 —— DepCatalog 的 apt 命令必须跑在
     * linux-ubuntu 会话（Android shell 里只有 command not found）。Ubuntu
     * 拉起失败时诚实降级到 local session（输出真实报错，绝不伪造成功）。
     */
    private suspend fun ensureDepInstallSession(): Long? {
        if (depSessionId != null &&
            _sessions.value.any { it.id == depSessionId && it.isAlive }
        ) return depSessionId
        provisioner.ensureUbuntuSession()?.let {
            depSessionId = it
            return it
        }
        // 降级：复用当前活跃的 local 会话（无则新建）
        val active = _activeSessionId.value
        if (active != null && _sessions.value.any { it.id == active && it.isAlive }) {
            depSessionId = active
            return active
        }
        _install.update { it.copy(log = it.log + lang.getString(R.string.term_notice_fallback_android)) }
        val r = terminalRuntime.create(backendId = BACKEND_LOCAL)
        return if (r.isSuccess) {
            val sid = r.getOrThrow().sessionId
            sessionBackends[sid] = BACKEND_LOCAL to "ANDROID_LOCAL"
            depSessionId = sid
            refreshSessions()
            sid
        } else null
    }

    override fun onCleared() {
        // Runtime owns session lifecycle; explicit close via terminal.close() by Agent/UI.
        // 这里不主动 close，因为 Runtime 是单例，session 可能被其他消费者复用。
    }

    companion object {
        const val BACKEND_LOCAL = "local"
        const val BACKEND_UBUNTU = "linux-ubuntu"
        private val ALIVE_STATES = setOf(
            com.apex.agent.platform.terminal.session.SessionState.CREATED,
            com.apex.agent.platform.terminal.session.SessionState.STARTING,
            com.apex.agent.platform.terminal.session.SessionState.READY,
            com.apex.agent.platform.terminal.session.SessionState.RUNNING,
            com.apex.agent.platform.terminal.session.SessionState.WAITING_INPUT,
            com.apex.agent.platform.terminal.session.SessionState.INTERRUPTED
        )
    }
}
