package com.apex.agent.ui.screen.terminal

import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 顶部 tab 的会话视图模型。P0-1：从 `TerminalViewModel` 嵌套类型提升为顶层
 * （会话状态的唯一权威现在是 [SessionRegistry]，类型跟着家走）。
 *
 * backend 由创建方记录（runtime 快照不含该信息）。
 */
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

/**
 * 会话簿（P0-1 拆分 + P1-1 合并）—— 多会话元数据的唯一权威。
 *
 * 旧实现里 `sessionBackends` 与 `sessionTitles` 是两张独立 ConcurrentHashMap
 * （IO 线程写、主线程读），`closeSession` 靠两处 `remove` 手工同步，会话被
 * Agent 关闭时无人清理 —— 长时间运行的 App 会累积僵尸条目。本类把两表
 * 合并为单条 [SessionMeta]（字段可空：backend 只在创建时记录，title 只由
 * OSC 回写 —— 两类写路径经 `compute` 原子合并互不覆盖），`refresh()` 时对
 * runtime 快照做存活修剪（快照里不存在的会话 → meta 即刻驱逐），关闭/重启
 * 路径只需一处 [evict]。
 *
 * 线程模型：ConcurrentHashMap（`ensureDepInstallSession` 在 Dispatchers.IO
 * 里写 meta，主线程 refresh 同时读 —— 跨线程可见性由 CHM 保证）。
 */
class SessionRegistry(private val runtime: TerminalRuntime) {

    /**
     * 合并后的会话元组（P1-1：替代 sessionBackends + sessionTitles 双表）。
     * backend 字段为 null = 该会话无创建方记录（Agent 创建 / 进程恢复），
     * 展示层回落到 [SessionRegistry.inferMeta] 的 shell 推断。
     */
    data class SessionMeta(
        val backendId: String? = null,
        val runtimeType: String? = null,
        val title: String? = null
    )

    /** 活跃会话消失后 registry 对 VM 的交接指令（VM 负责重接渲染收集器）。 */
    sealed interface Handoff {
        /** 活跃会话仍在（或本就无活跃）—— 渲染收集器不动。 */
        data object Keep : Handoff

        /** 活跃会话消失 → 已内部切到 [id]，VM 需要重接收集器。 */
        data class Select(val id: Long) : Handoff

        /** 活跃会话消失且无存活会话 → activeId 已置空，VM 需要清空收集器。 */
        data object Cleared : Handoff
    }

    private val _sessionTabs = MutableStateFlow<List<SessionTab>>(emptyList())
    val sessionTabs: StateFlow<List<SessionTab>> = _sessionTabs.asStateFlow()

    private val _activeSessionId = MutableStateFlow<Long?>(null)
    val activeSessionId: StateFlow<Long?> = _activeSessionId.asStateFlow()

    private val meta = ConcurrentHashMap<Long, SessionMeta>()

    /**
     * 从 runtime 拉取会话列表（状态/存活 + VM 记录的 backend 标签），并对
     * meta 做存活修剪。返回活跃会话的交接指令（见 [Handoff]）。
     */
    suspend fun refresh(): Handoff {
        val snap = runtime.snapshot(TerminalRuntime.SnapshotMode.SESSIONS)
            .getOrNull() ?: return Handoff.Keep
        val tabs = snap.sessions.map { s ->
            val m = meta[s.session.id]
            SessionTab(
                id = s.session.id,
                backendId = m?.backendId ?: "agent",
                runtimeType = m?.runtimeType ?: inferRuntimeType(s.session.shell),
                state = s.session.state.name,
                isAlive = s.session.state in ALIVE_STATES,
                title = m?.title
            )
        }
        _sessionTabs.value = tabs
        // P1-1：存活修剪 —— runtime 快照里不存在的会话（Agent close / 异常死亡）
        // 的 meta 即刻驱逐，杜绝僵尸条目累积。
        val liveIds = tabs.asSequence().map { it.id }.toSet()
        meta.keys.retainAll(liveIds)
        // 活跃会话消失（被 Agent close）→ 切到剩余首个，没有则置空（渲染占位）
        val active = _activeSessionId.value
        if (active != null && tabs.none { it.id == active }) {
            val next = tabs.firstOrNull { it.isAlive }
            return if (next != null) {
                _activeSessionId.value = next.id
                Handoff.Select(next.id)
            } else {
                _activeSessionId.value = null
                Handoff.Cleared
            }
        }
        return Handoff.Keep
    }

    /** 会话创建成功后登记 backend 元组（runtime 快照不含该信息；保留已有标题）。 */
    fun recordBackend(id: Long, backendId: String, runtimeType: String) {
        meta.compute(id) { _, m -> SessionMeta(backendId, runtimeType, m?.title) }
    }

    /**
     * OSC 0/1/2 标题回写（vim/tmux/ssh 设置的窗口名 —— Termux/JuiceSSH/
     * ConnectBot 都把标题显示在会话标签上）。
     * @return 标题是否变化（变化才需要触发 tab 刷新，避免每帧一次列表重组）。
     */
    fun recordTitle(id: Long, title: String): Boolean {
        var changed = false
        meta.compute(id) { _, m ->
            if (m?.title != title) changed = true
            SessionMeta(m?.backendId, m?.runtimeType, title)
        }
        return changed
    }

    /** 用户切换活跃会话。@return 活跃 id 是否变化（未变时调用方无需重接收集器）。 */
    fun select(id: Long): Boolean {
        if (_activeSessionId.value == id) return false
        _activeSessionId.value = id
        return true
    }

    /** 关闭/重启会话：meta 驱逐（P1-1 —— 单处清理替代旧双表两处 remove）。 */
    fun evict(id: Long) {
        meta.remove(id)
    }

    /** 当前所有已知会话 id（渲染快照缓存的修剪输入）。 */
    fun liveIds(): Set<Long> = _sessionTabs.value.asSequence().map { it.id }.toSet()

    /** 无 backend 记录的会话（Agent 创建 / 进程恢复）—— 展示为 "agent" +
     *  按 shell 推断的运行时类型。 */
    private fun inferRuntimeType(shell: String): String =
        if (shell.contains("bash", true) || shell.contains("proot", true)) "LINUX" else "ANDROID_LOCAL"

    companion object {
        val ALIVE_STATES: Set<SessionState> = setOf(
            SessionState.CREATED,
            SessionState.STARTING,
            SessionState.READY,
            SessionState.RUNNING,
            SessionState.WAITING_INPUT,
            SessionState.INTERRUPTED
        )
    }
}
