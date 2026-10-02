package com.apex.agent.platform.terminal.service

import com.apex.agent.platform.terminal.events.StateKind
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * T91（D2-D4）：Terminal IPC 控制器 —— AIDL 契约与 [TerminalRuntime] 之间的纯 JVM 桥。
 *
 * ## 分层（薄壳原则）
 *
 * ```
 * ITerminalService.Stub（TerminalService.kt，Android 壳，零逻辑）
 *   └─ TerminalIpcController（本类，纯 JVM —— 语义/编码/事件桥全部在此，可单测）
 *        └─ TerminalRuntime（既有 9 操作门面，PR #60 冻结契约）
 * ```
 *
 * Android 壳只做「binder 参数 → 控制器调用」的机械转发 —— 逻辑零下放，
 * 与 T88「View 只做事件清洗与动作转发，决策进纯状态机」同一纪律。
 *
 * ## 语义契约（与 ITerminalService.aidl 注释一一对应）
 *
 *  - **createSession**：成功 → 十进制 sessionId 字符串；失败 → `"ERR:<message>"`
 *    （单往返携带完整错误信息，不依赖 binder 异常传播 —— 运行时 Result 的
 *    message 原样透传，TerminalError 编码保留）；
 *  - **write/writeText**：owner 恒为 [InputOwner.USER]（IPC 桥是用户侧通道 ——
 *    AIDL 客户端**不可能**伪造 owner=AGENT，与 Spec §14「Runtime 注入 owner」同构）；
 *  - **listSessions**：JSON 数组（[SessionSummaryDto]）；
 *  - **回调流**：事件驱动（[TerminalRuntime.terminalEventFlow] —— TerminalEventBus
 *    的 crash-safe 增量订阅），**非轮询**。会话创建锚点 = create 返回的初始游标；
 *    中途 attach 的已知会话从诞生起重放（重连客户端的 transcript 重同步语义，
 *    与 Termux 客户端重附时的回读对齐）。
 *
 * ## 防御性纪律（AGENTS.md「防御式 IO」）
 *
 *  - env 赋值 `"K=V"` 形态非法（无 `=` / 空 key）→ **跳过该条**，不炸整次 create；
 *  - 回调派发 try-catch 全吞（单个客户端崩溃不得拖垮 PTY 泵 —— binder oneway
 *    本身不抛，防御的是测试 fake 与未来进程内复用）；
 *  - 事件收集协程 per-session 独立 Job（SupervisorJob 域内互不传染）。
 */
class TerminalIpcController(
    /** runtime 来源（Registry / 测试注入）。null = 未安装 → ERR:RUNTIME_NOT_INSTALLED。 */
    private val runtimeProvider: () -> TerminalRuntime?,
    /** 事件收集域（SupervisorJob —— 单会话收集失败不传染其它会话）。 */
    private val scope: CoroutineScope,
    /** create 的有界预算（冷启动 rootfs 场景的防御；无界挂起会钉死 binder 线程）。 */
    private val createTimeoutMs: Long = 15_000L,
    /** onOutput 单次回调的最大字节（binder 异步事务缓冲 ~1MB —— 防御性 256KB 分片）。 */
    private val maxOutputChunkBytes: Int = 256 * 1024
) {

    /** AIDL ITerminalCallback 的 Kotlin 镜像（壳层把 binder 代理适配到本接口）。 */
    interface Callback {
        fun onOutput(sessionId: Long, data: ByteArray)
        fun onExit(sessionId: Long, exitCode: Int, cause: String)
        fun onSessionStateChanged(sessionId: Long, state: String)
    }

    @Serializable
    data class SessionSummaryDto(
        val id: Long,
        val state: String,
        val alive: Boolean
    )

    private val json = Json { encodeDefaults = true }

    private val callbacks = CopyOnWriteArraySet<Callback>()

    /** per-session 事件收集 Job（onExit/SessionClosed 后主动收割）。 */
    private val collectors = ConcurrentHashMap<Long, Job>()

    /** onExit 只派发一次（ProcessExited 与 SessionClosed 双源合并）。 */
    private val exitAnnounced = ConcurrentHashMap.newKeySet<Long>()

    // ─── AIDL 操作实现 ───

    /** 成功 → 十进制 sessionId；失败 → "ERR:<message>"。 */
    fun createSession(
        backendId: String,
        rows: Int,
        cols: Int,
        cwd: String?,
        envAssignments: List<String>?
    ): String {
        val runtime = runtimeProvider() ?: return ERR_RUNTIME_NOT_INSTALLED
        val safeRows = rows.coerceIn(2, 512)
        val safeCols = cols.coerceIn(2, 1024)
        val env = parseEnvAssignments(envAssignments)
        val effectiveCwd = cwd?.takeIf { it.isNotBlank() }?.trim()
            ?: if (backendId == LOCAL_BACKEND_ID) "/sdcard" else "/workspace"
        return try {
            runBlocking {
                withTimeout(createTimeoutMs) {
                    runtime.create(
                        cwd = effectiveCwd,
                        rows = safeRows,
                        cols = safeCols,
                        env = env,
                        backendId = backendId
                    )
                }
            }.fold(
                onSuccess = { created ->
                    // 事件流锚点 = 会话初始游标（此后全部输出/状态事件推给回调）
                    startEventStream(created.sessionId, created.cursor)
                    created.sessionId.toString()
                },
                onFailure = { e -> "ERR:${e.message ?: e::class.simpleName}" }
            )
        } catch (e: Exception) {
            // withTimeout / runBlocking 异常（含取消）—— 有界预算兑现为 ERR
            "ERR:${e.message ?: e::class.simpleName}"
        }
    }

    /** 原始 UTF-8 字节直通 PTY（T85 纪律：不经 String↔charset 往返）。 */
    fun write(sessionId: Long, data: ByteArray) {
        val runtime = runtimeProvider() ?: return
        runCatching {
            runBlocking { runtime.write(sessionId, owner = InputOwner.USER, kind = TerminalRuntime.WriteKind.RAW, bytes = data) }
        }
    }

    /** 原始文本便捷路径（换行由调用方决定）。 */
    fun writeText(sessionId: Long, text: String) {
        val runtime = runtimeProvider() ?: return
        runCatching {
            runBlocking { runtime.write(sessionId, owner = InputOwner.USER, kind = TerminalRuntime.WriteKind.RAW, text = text) }
        }
    }

    fun resize(sessionId: Long, rows: Int, cols: Int) {
        val runtime = runtimeProvider() ?: return
        runCatching {
            runBlocking { runtime.resize(sessionId, rows.coerceIn(2, 512), cols.coerceIn(2, 1024)) }
        }
    }

    fun closeSession(sessionId: Long, force: Boolean) {
        val runtime = runtimeProvider() ?: return
        runCatching {
            runBlocking { runtime.close(sessionId, force) }
        }
        // 注意：不在此处收割事件收集器 —— SessionClosed 事件尚需经收集器派发
        // onExit（announceExit 收到事件后自会收割）。显式 close 的 onExit 语义
        // 由事件链兑现，与外部死亡（BROKEN）同一条路径。
    }

    /** JSON 数组：[{"id":1,"state":"RUNNING","alive":true}, …]。 */
    fun listSessions(): String {
        val runtime = runtimeProvider() ?: return "[]"
        val summaries = runCatching {
            runBlocking {
                runtime.snapshot(mode = TerminalRuntime.SnapshotMode.SESSIONS).getOrNull()?.sessions
                    ?: emptyList()
            }
        }.getOrDefault(emptyList())
        return json.encodeToString(
            ListSerializer(SessionSummaryDto.serializer()),
            summaries.map { s ->
                SessionSummaryDto(
                    id = s.session.id,
                    state = s.session.state.name,
                    alive = s.session.state in ALIVE_STATES
                )
            }
        )
    }

    /** "pong:<TerminalApiVersion>"（bind 探活 + 契约版本协商）。 */
    fun ping(): String = "pong:${com.apex.agent.platform.terminal.api.TerminalApiVersion.versionString}"

    fun registerCallback(callback: Callback) {
        callbacks.add(callback)
        // 已有会话从诞生起全量重放（重连客户端的 transcript 重同步 —— 与
        // TerminalEventBus 的 crash-safe 订阅语义同源，非为此新建轮询路径）
        val runtime = runtimeProvider() ?: return
        val known = runCatching {
            runBlocking {
                runtime.snapshot(mode = TerminalRuntime.SnapshotMode.SESSIONS).getOrNull()?.sessions
                    ?: emptyList()
            }
        }.getOrDefault(emptyList())
        for (s in known) {
            if (s.session.state in ALIVE_STATES) startEventStream(s.session.id, anchorCursor = 0L)
        }
    }

    fun unregisterCallback(callback: Callback) {
        callbacks.remove(callback)
    }

    /** 无回调订阅者时收割全部收集器（Service onDestroy 调用）。 */
    fun shutdown() {
        collectors.values.forEach { runCatching { it.cancel() } }
        collectors.clear()
        callbacks.clear()
        exitAnnounced.clear()
    }

    // ─── 事件流桥（event-driven，非轮询） ───

    private fun startEventStream(sessionId: Long, anchorCursor: Long) {
        val runtime = runtimeProvider() ?: return
        val flow: Flow<TerminalEvent> = runtime.terminalEventFlow(sessionId, anchorCursor) ?: return
        // 已有收集器不重复启动（首个回调注册或首个会话创建 —— 单订阅多播给全部回调）
        collectors.putIfAbsent(
            sessionId,
            scope.launch {
                flow.collect { event -> dispatchEvent(runtime, sessionId, event) }
            }
        )
    }

    private fun stopEventStream(sessionId: Long) {
        collectors.remove(sessionId)?.let { runCatching { it.cancel() } }
    }

    private fun dispatchEvent(runtime: TerminalRuntime, sessionId: Long, event: TerminalEvent) {
        when (event) {
            is TerminalEvent.OutputProduced -> forwardOutput(runtime, sessionId, event)
            is TerminalEvent.ProcessExited ->
                // jobId == null → shell 自身退出（会话终结信号）
                if (event.jobId == null) announceExit(sessionId, event.exitCode ?: -1, event.cause.name)
            is TerminalEvent.SessionClosed ->
                announceExit(sessionId, -1, event.cause.name)
            is TerminalEvent.StateChanged ->
                if (event.kind == StateKind.SESSION) {
                    dispatchToCallbacks { it.onSessionStateChanged(sessionId, event.to) }
                }
            else -> Unit
        }
    }

    private fun forwardOutput(runtime: TerminalRuntime, sessionId: Long, event: TerminalEvent.OutputProduced) {
        if (event.byteCount <= 0 || event.endCursor <= event.startCursor) return
        // 游标驱动分段拉取：事件范围可能超单次回调上限 → 按 maxOutputChunkBytes
        // 逐段 observe(RAW) 派发。进度以游标推进为准（不按字节数算术 —— UTF-8
        // 转码可能使字节计数与环缓冲计数不同步，无进展即终止防死循环）。
        var cursor = event.startCursor
        while (cursor < event.endCursor) {
            val want = (event.endCursor - cursor).toInt().coerceAtMost(maxOutputChunkBytes)
            val result = runCatching {
                runBlocking {
                    runtime.observe(
                        sessionId = sessionId,
                        mode = TerminalRuntime.ObserveMode.RAW,
                        afterCursor = cursor,
                        maxBytes = want
                    ).getOrNull()
                }
            }.getOrNull() ?: return
            val raw = result.raw ?: return
            if (raw.isEmpty()) return
            val next = result.cursor
            if (next <= cursor) return // 无进展防御（环缓冲边界/驱逐）—— 终止
            dispatchToCallbacks { it.onOutput(sessionId, raw.toByteArray(Charsets.UTF_8)) }
            cursor = next
        }
    }

    private fun announceExit(sessionId: Long, exitCode: Int, cause: String) {
        // ProcessExited(shell) 与 SessionClosed 双源合并 —— onExit 语义上只发一次
        if (!exitAnnounced.add(sessionId)) return
        dispatchToCallbacks { it.onExit(sessionId, exitCode, cause) }
        stopEventStream(sessionId)
    }

    private inline fun dispatchToCallbacks(block: (Callback) -> Unit) {
        for (cb in callbacks) {
            runCatching { block(cb) }
        }
    }

    companion object {
        const val ERR_RUNTIME_NOT_INSTALLED = "ERR:RUNTIME_NOT_INSTALLED — host app has not installed the terminal runtime yet"
        const val LOCAL_BACKEND_ID = "local"

        /** 会话「可交互」状态集（listSessions 的 alive 投影）。 */
        private val ALIVE_STATES = setOf(
            com.apex.agent.platform.terminal.session.SessionState.CREATED,
            com.apex.agent.platform.terminal.session.SessionState.STARTING,
            com.apex.agent.platform.terminal.session.SessionState.READY,
            com.apex.agent.platform.terminal.session.SessionState.RUNNING,
            com.apex.agent.platform.terminal.session.SessionState.WAITING_INPUT,
            com.apex.agent.platform.terminal.session.SessionState.INTERRUPTED
        )

        /** AIDL "ERR:" 前缀（客户端判定契约 —— 壳层与测试共享）。 */
        const val ERROR_PREFIX = "ERR:"

        /** 防注入（TM6 平移）：`K=V` 形态外的赋值整条跳过，不炸 create。 */
        fun parseEnvAssignments(assignments: List<String>?): Map<String, String> {
            if (assignments.isNullOrEmpty()) return emptyMap()
            val env = LinkedHashMap<String, String>()
            for (entry in assignments) {
                val eq = entry.indexOf('=')
                if (eq <= 0) continue // 无 '=' 或空 key —— 跳过（防御式 IO）
                val key = entry.substring(0, eq)
                if (key.isEmpty() || key.contains(' ') || key.contains('\n') || key.contains('\u0000')) continue
                env[key] = entry.substring(eq + 1)
            }
            return env
        }
    }
}
