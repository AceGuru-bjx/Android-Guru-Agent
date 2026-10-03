package com.apex.agent.ui.screen.terminal

import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.state.TerminalSemanticState
import com.apex.agent.terminalemulator.TerminalRenderSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * 渲染编排器（P0-1 拆分）—— 活跃会话的 styled/semantic/OSC52 收集者路由，
 * 从 `TerminalViewModel` 的 renderJob/semanticJob/clipboardJob 三件套收口。
 *
 * 数据流（Spec §41 事件驱动，非轮询）：
 *   PTY → PtyOutputPump → VT(TerminalCore) → ObservationEngine.styledState →
 *   (sample 33ms) → renderState → Compose grid。
 *
 * styled 投影只在有收集者时计算（ObservationEngine 背压契约）；33ms sample
 * 把 feed 洪泛（cat 大文件 / gradle 日志）折叠到 ~30fps。
 *
 * ## P0-2：会话快照缓存
 *
 * 旧实现在会话切换瞬间把 `renderState` 置 null —— Compose 重组到新快照
 * 到达之间必然渲染一帧「终端未启动」占位（用户可感知的闪空）。本编排器
 * 为每个会话保留最近一次快照（[lastSnapshots]，LRU 有界），切换时先播种
 * 上一已知快照，新会话快照到达后覆盖 —— 切换帧即有内容（Termux 的
 * per-session TerminalView 等价效果，但不动 View 实例结构）。
 */
internal class TerminalRenderOrchestrator(private val runtime: TerminalRuntime) {

    /** 活跃会话的 styled 屏（颜色/光标/scrollback；null = 未启动）。 */
    private val _renderState = MutableStateFlow<TerminalRenderSnapshot?>(null)
    val renderState: StateFlow<TerminalRenderSnapshot?> = _renderState.asStateFlow()

    /** 活跃会话的语义状态（会话状态 / 前台 job / prompt）。 */
    private val _semanticState = MutableStateFlow<TerminalSemanticState?>(null)
    val semanticState: StateFlow<TerminalSemanticState?> = _semanticState.asStateFlow()

    private var renderJob: Job? = null
    private var semanticJob: Job? = null
    private var clipboardJob: Job? = null

    /**
     * P0-2：per-session 最近快照缓存（LRU、容量有界 —— 快照本体 rows×cols
     * 级内存，几十 KB/会话，16 个上限 + refresh 存活修剪双保险）。
     * accessOrder=true：切换即触碰 MRU。
     */
    private val lastSnapshots = object : LinkedHashMap<Long, TerminalRenderSnapshot>(
        16, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TerminalRenderSnapshot>): Boolean =
            size > MAX_CACHED_SNAPSHOTS
    }

    /**
     * 切换收集者到指定会话（事件驱动 + sample 防洪泛）。
     *
     * @param onSessionTitle 快照携带的 OSC 0/1/2 标题（VM 路由到 SessionRegistry；
     *   每帧回调，VM 侧去重后才触发 tab 刷新）
     * @param onOsc52Clipboard OSC 52 剪贴板请求（vim/tmux 远程复制 —— feed 后
     *   drain 上抛，仅订阅活跃会话，后台引擎侧排队）
     */
    fun observe(
        scope: CoroutineScope,
        sessionId: Long?,
        onSessionTitle: (Long, String) -> Unit,
        onOsc52Clipboard: (String) -> Unit
    ) {
        renderJob?.cancel()
        semanticJob?.cancel()
        clipboardJob?.cancel()
        if (sessionId == null) {
            _renderState.value = null
            _semanticState.value = null
            return
        }
        val sid = sessionId
        // ★ P0-2：先播种上一已知快照 —— 切换帧即有内容，消灭「未启动」闪空帧。
        _renderState.value = snapshotOf(sid)
        _semanticState.value = null
        renderJob = scope.launch {
            runtime.styledScreenFlow(sid)?.sample(33)?.collect { snap ->
                cachePut(sid, snap)
                _renderState.value = snap
                snap?.title?.trim()?.takeUnless { it.isNullOrEmpty() }?.let {
                    onSessionTitle(sid, it)
                }
            }
        }
        semanticJob = scope.launch {
            runtime.semanticStateFlow(sid)?.collect { state ->
                _semanticState.value = state
            }
        }
        clipboardJob = scope.launch {
            runtime.clipboardRequestsFlow(sid)
                ?.collect { text -> onOsc52Clipboard(text) }
        }
    }

    /** 无活跃会话：停表 + 清屏（渲染层回落占位）。 */
    fun clear() {
        renderJob?.cancel()
        semanticJob?.cancel()
        clipboardJob?.cancel()
        _renderState.value = null
        _semanticState.value = null
    }

    /** 会话关闭/重启：快照缓存驱逐。 */
    fun evict(sessionId: Long) {
        synchronized(lastSnapshots) { lastSnapshots.remove(sessionId) }
    }

    /** refresh 存活修剪：runtime 快照里不存在的会话缓存即刻驱逐。 */
    fun pruneTo(liveIds: Set<Long>) {
        synchronized(lastSnapshots) {
            lastSnapshots.keys.retainAll(liveIds)
        }
    }

    private fun snapshotOf(sid: Long): TerminalRenderSnapshot? =
        synchronized(lastSnapshots) { lastSnapshots[sid] }

    private fun cachePut(sid: Long, snap: TerminalRenderSnapshot?) {
        if (snap == null) return
        synchronized(lastSnapshots) { lastSnapshots[sid] = snap }
    }

    companion object {
        /** 快照缓存容量上限（用户实际多会话 <10；超额 LRU 驱逐最久未切换的）。 */
        private const val MAX_CACHED_SNAPSHOTS = 16
    }
}
