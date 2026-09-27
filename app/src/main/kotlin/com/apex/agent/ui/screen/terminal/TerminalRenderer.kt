package com.apex.agent.ui.screen.terminal

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.platform.terminal.io.KeyEventMapping
import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.terminalemulator.RenderCell
import com.apex.agent.terminalemulator.TerminalRenderSnapshot
import com.apex.agent.ui.screen.terminal.scheme.LocalTerminalBoldAsBright
import com.apex.agent.ui.screen.terminal.scheme.LocalTerminalColorScheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 终端 grid 渲染器（P83 — Terminal 产品化核心件）。
 *
 * 数据：[TerminalViewModel.renderState]（TerminalRenderSnapshot —— 逐 cell 颜色/属性、
 * 光标、DEC 模式、scrollback）。渲染链（Spec §41 事件驱动）：
 *
 *   PTY → Pump → VT(TerminalCore) → ObservationEngine.styledState →(33ms sample)→ Compose
 *
 * 组件职责（纯渲染，不 fork PTY / 不持会话状态）：
 *  - **Grid**：scrollback + 可见屏逐行 styled 文本（LazyColumn —— 只组合可视行）
 *  - **光标**：DECTCEM 可见时绘制闪烁 beam；x 按行内 cell 宽度步进（CJK 2 列对齐）
 *  - **滚动**：跟随输出自动吸底；用户上滚即脱离，出现“跳到最新”浮标
 *  - **选择/复制**：长按起选，拖动扩选（cell 级），复制入系统剪贴板
 *  - **输入**：隐藏 BasicTextField 捕获 IME（增量 diff → RAW，组合期间等待提交）；
 *    硬件键盘经 onPreviewKeyEvent 映射（Ctrl+字母 / 箭头 / Home…）
 *  - **Resize**：视图尺寸 → PTY rows/cols（SIGWINCH）
 *  - **特殊键工具栏**：ESC/TAB/CTRL 锁存/方向/Home/End/PgUp/PgDn/CTRL+C/D/Z/粘贴
 */
@Composable
fun TerminalRenderer(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier
) {
    val render by viewModel.renderState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val schemeId by viewModel.colorSchemeId.collectAsStateWithLifecycle()
    val boldAsBright by viewModel.boldAsBright.collectAsStateWithLifecycle()
    val extraKeys by viewModel.extraKeys.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    // T87：方案注入 —— 渲染树内的所有内容色（背景/前景/光标/选区/ANSI 16 色）
    // 全部从 CompositionLocal 解析，切换方案即时生效（零引擎改动）。
    androidx.compose.runtime.CompositionLocalProvider(
        com.apex.agent.ui.screen.terminal.scheme.LocalTerminalColorScheme provides
            com.apex.agent.ui.screen.terminal.scheme.TerminalColorSchemeRegistry.byId(schemeId),
        com.apex.agent.ui.screen.terminal.scheme.LocalTerminalBoldAsBright provides boldAsBright
    ) {

    // T86：窗口焦点变化 → ESC[I / ESC[O（DECSET 1004；vim FocusGained/Lost、
    // tmux focus-events）。用生命周期近似（ON_RESUME=聚焦，ON_PAUSE=失焦）。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> viewModel.notifyTerminalFocus(true)
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> viewModel.notifyTerminalFocus(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    TerminalGrid(
        render = render,
        fontSize = settings.fontSize,
        monochrome = settings.monochrome,
        showKeybar = settings.showKeybar,
        extraKeys = extraKeys,
        onText = viewModel::sendInput,
        onKey = viewModel::sendKey,
        onControl = viewModel::sendControlChar,
        onPaste = viewModel::pasteText,
        onResize = viewModel::resizeTerminal,
        // 响铃（BEL）反馈：对齐 Termux/ConnectBot —— 补全失败、命令报错时给一下振动
        onBell = {
            if (settings.vibrateOnBell) runCatching { vibrateOnce(context) }
        },
        // T86：硬件键完整映射（KeyEventMapping：修饰键/F1-12/小键盘）
        onHardwareKey = { keyCode, mods, unicode ->
            viewModel.sendHardwareKey(keyCode, mods, unicode)
            true  // 有映射才会走到这里（VM 内 encode null 时静默返回仍算消费，防双发）
        },
        // T86：双指捏合调字号
        onFontSizeStep = viewModel::adjustFontSize,
        // T86：OSC 8 链接 → 系统浏览器
        onLinkOpen = { uri ->
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                )
            }
        },
        // T86：滚轮/鼠标报告路由
        onWheel = viewModel::sendWheel,
        onMouse = { type, col, row ->
            when (type) {
                0 -> viewModel.sendMouseEvent(
                    com.apex.agent.terminalemulator.TerminalMouseEventType.PRESS, 0, 0, col, row
                )
                1 -> viewModel.sendMouseEvent(
                    com.apex.agent.terminalemulator.TerminalMouseEventType.RELEASE, 0, 0, col, row
                )
            }
        },
        modifier = modifier
    )
    }  // CompositionLocalProvider end
}

/** 单次轻振动（30ms）。失败静默 —— 没有振动硬件/权限不该崩 UI。 */
private fun vibrateOnce(context: android.content.Context) {
    val vibrator = androidx.core.content.ContextCompat.getSystemService(
        context, android.os.Vibrator::class.java
    ) ?: return
    if (!vibrator.hasVibrator()) return
    vibrator.vibrate(
        android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
    )
}

/** cell 级选择区间（行/列；列区间左闭右开，含 from 至 to 前一列）。 */
private data class SelRange(val startRow: Int, val startCol: Int, val endRow: Int, val endCol: Int)

@Composable
fun TerminalGrid(
    render: TerminalRenderSnapshot?,
    fontSize: Int,
    monochrome: Boolean,
    showKeybar: Boolean = true,
    extraKeys: List<com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig.ExtraKey> = emptyList(),
    onText: (String) -> Unit,
    onKey: (TerminalKey) -> Unit,
    onControl: (Char) -> Unit,
    onPaste: (String) -> Unit,
    onResize: (rows: Int, cols: Int) -> Unit,
    /** 响铃（BEL 0x07）回调 —— 序号变化即触发，宿主决定振动/提示/忽略。 */
    onBell: () -> Unit = {},
    /** T86：硬件键盘完整映射（KeyEventMapping —— 修饰键/F1-12/小键盘）。
     * 返回 true = 已编码写入（消费事件）；false = 无映射（走旧 onKey 表/IME）。 */
    onHardwareKey: (keyCode: Int, mods: Int, unicodeChar: Int) -> Boolean = { _, _, _ -> false },
    /** T86：双指捏合调字号（Termux 手势）：delta = ±1 步进。 */
    onFontSizeStep: (Int) -> Unit = {},
    /** T86：OSC 8 链接点击（uri 已由 render.linkTable 解析）。 */
    onLinkOpen: (String) -> Unit = {},
    /** T86：滚轮路由 —— 返回 true = 已编码进 PTY（vim 滚动），UI 不滚视口。 */
    onWheel: (up: Boolean) -> Boolean = { false },
    /** T86：鼠标事件（触摸点击 → guest 鼠标报告；模式关闭时 VM 层静默忽略）。 */
    onMouse: (type: Int, col: Int, row: Int) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // ── 字体度量（monospace）：探测字符宽 + 1.25×行高 ──
    // P0（黑字根因）：baseStyle 此前不携带 color —— 无 ANSI 着色的普通 cell
    //（plain shell 输出 / 提示符正文）落不到任何 SpanStyle，BasicText 兜底色是
    // 纯黑，在深色终端底（0xFF0E1411）上「黑字黑底啥也看不清」（用户实测反馈）。
    // 修复：基础样式显式继承终端主题前景色（scheme 解析后的白），cell 级 ANSI
    // 着色仍在其上覆盖。
    val activeScheme = LocalTerminalColorScheme.current
    val baseStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize.sp,
        color = activeScheme.foregroundC
    )
    val textMeasurer = rememberTextMeasurer()
    val charWidthPx = remember(fontSize) {
        val probe = textMeasurer.measure("0".repeat(10), baseStyle)
        (probe.size.width / 10f).coerceAtLeast(1f)
    }
    val lineHeightSp = remember(fontSize) { (fontSize * 1.25f).sp }
    val lineHeightPx = with(density) { lineHeightSp.toPx() }
    val lineHeightDp = with(density) { lineHeightSp.toDp() }

    // 全量行 = scrollback（旧→新）+ 可见屏
    val allRows: List<List<RenderCell>> = remember(render) {
        if (render == null) emptyList() else render.scrollback + render.lines
    }
    val totalRows = allRows.size
    // rememberUpdatedState 让手势闭包读到最新行内容（避免 pointerInput 陈旧捕获）
    val rowsState = rememberUpdatedState(allRows)

    // ── 滚动：跟随输出；用户上滚即脱离 ──
    // P0（大段空白根因）：旧实现 follow 时 scrollToItem(totalRows - 1) —— 列表
    // 末项是屏幕最后一行，而提示符之后整屏都是空行 → 每次输出/输入后视口滚到
    // 「最后一行置顶」，用户看到的是提示符上方一大段空白 + 键盘（打开终端即
    // 空屏；跑完命令想敲第二条，中间又是整屏空白 —— 用户实测反馈原话）。
    // 修复（Termux 语义）：跟随目标 = 光标行（提示符所在行）贴视口底部，
    // 提示符永远紧贴键盘上沿，其下的空屏行自然沉到视口外。
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    // 视口尺寸（resize 转发也复用；提前声明供 follow 目标计算）
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) ->
                if (!canForward) follow = true
                else if (scrolling) follow = false
            }
    }
    /** 光标行（含 scrollback 偏移）的列表索引。 */
    val cursorItemIndex = render?.let { it.scrollback.size + it.cursorRow } ?: 0
    /** follow 目标索引：光标行贴底（视口能容纳 viewportRows 行时首行索引）。 */
    suspend fun followTarget(): Int {
        if (totalRows <= 0) return 0
        val viewportRows = (viewSize.height / lineHeightPx).toInt().coerceAtLeast(1)
        return (cursorItemIndex - viewportRows + 1).coerceIn(0, (totalRows - 1).coerceAtLeast(0))
    }
    LaunchedEffect(follow, totalRows, cursorItemIndex, lineHeightPx, viewSize.height) {
        if (follow && totalRows > 0) listState.scrollToItem(followTarget())
    }

    // ── 选择状态（cell 级；anchor=起点，head=终点）──
    var selectionAnchor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var selectionHead by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val selectionActive = selectionAnchor != null && selectionHead != null

    // ── CTRL/SHIFT/ALT 锁存（下一次特殊键/字母按修饰组合发）──
    var ctrlLatched by remember { mutableStateOf(false) }
    var shiftLatched by remember { mutableStateOf(false) }
    var altLatched by remember { mutableStateOf(false) }

    /** T86：锁存修饰位 → xterm 组合（SHIFT/ALT+方向键 = 选择/词跳，Termux 同款）。 */
    fun latchedMods(): Int {
        var m = 0
        if (ctrlLatched) m = m or KeyEventMapping.MOD_CTRL
        if (shiftLatched) m = m or KeyEventMapping.MOD_SHIFT
        if (altLatched) m = m or KeyEventMapping.MOD_ALT
        return m
    }

    /** 特殊键按当前锁存修饰发送（无锁存走旧 onKey 路径，保持兼容）。 */
    fun sendKeyWithLatches(key: TerminalKey) {
        val mods = latchedMods()
        if (mods == 0) {
            onKey(key)
            return
        }
        val keyCode = when (key) {
            TerminalKey.ARROW_UP -> KeyEventMapping.KEYCODE_DPAD_UP
            TerminalKey.ARROW_DOWN -> KeyEventMapping.KEYCODE_DPAD_DOWN
            TerminalKey.ARROW_LEFT -> KeyEventMapping.KEYCODE_DPAD_LEFT
            TerminalKey.ARROW_RIGHT -> KeyEventMapping.KEYCODE_DPAD_RIGHT
            TerminalKey.HOME -> KeyEventMapping.KEYCODE_MOVE_HOME
            TerminalKey.END -> KeyEventMapping.KEYCODE_MOVE_END
            TerminalKey.PAGE_UP -> KeyEventMapping.KEYCODE_PAGE_UP
            TerminalKey.PAGE_DOWN -> KeyEventMapping.KEYCODE_PAGE_DOWN
            TerminalKey.ENTER -> KeyEventMapping.KEYCODE_ENTER
            TerminalKey.TAB -> KeyEventMapping.KEYCODE_TAB
            TerminalKey.BACKSPACE -> KeyEventMapping.KEYCODE_DEL
            TerminalKey.F1 -> KeyEventMapping.KEYCODE_F1
            TerminalKey.F2 -> KeyEventMapping.KEYCODE_F1 + 1
            TerminalKey.F3 -> KeyEventMapping.KEYCODE_F1 + 2
            TerminalKey.F4 -> KeyEventMapping.KEYCODE_F1 + 3
            TerminalKey.F5 -> KeyEventMapping.KEYCODE_F1 + 4
            TerminalKey.F6 -> KeyEventMapping.KEYCODE_F1 + 5
            TerminalKey.F7 -> KeyEventMapping.KEYCODE_F1 + 6
            TerminalKey.F8 -> KeyEventMapping.KEYCODE_F1 + 7
            TerminalKey.F9 -> KeyEventMapping.KEYCODE_F1 + 8
            TerminalKey.F10 -> KeyEventMapping.KEYCODE_F1 + 9
            TerminalKey.F11 -> KeyEventMapping.KEYCODE_F1 + 10
            TerminalKey.F12 -> KeyEventMapping.KEYCODE_F1 + 11
            else -> 0
        }
        if (keyCode != 0) {
            onHardwareKey(keyCode, mods, 0)
            shiftLatched = false; altLatched = false  // 一次性锁存（发出即释放）
        } else {
            onKey(key)
        }
    }

    // ── IME 隐藏桥 + 焦点 ──
    val focusRequester = remember { FocusRequester() }
    var imeBuffer by remember { mutableStateOf(TextFieldValue("")) }
    val scope = rememberCoroutineScope()

    /**
     * 拉起输入法：聚焦隐藏 IME 桥 + 显式 `show()`。
     *
     * 只调 `requestFocus()` 在部分设备/输入法上不会弹键盘（焦点到了但 IME 没被请求显示），
     * 因此这里显式补一次 show()。失败不能炸 UI —— `runCatching` 兜住未挂载等时序异常。
     */
    fun showKeyboard() {
        runCatching { focusRequester.requestFocus() }
        keyboardController?.show()
    }

    // 会话就绪（首次拿到渲染快照）后自动聚焦 IME 桥：进入终端即可直接敲命令，
    // 不必"先点一下碰运气"。仅聚焦一次，避免与用户主动隐藏键盘反复打架。
    // P0 补齐：requestFocus() 在部分设备/输入法上不会拉起 IME（本文件 showKeyboard
    // 的 KDoc 已记载此事实）—— 进入终端时同样显式补一次 show()，否则用户看到的是
    // "$ 提示符 + 一大片空白、键盘不弹"的死屏误象。
    LaunchedEffect(render != null) {
        if (render != null) {
            runCatching { focusRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    // 指针 → 行列（滚动偏移 + 行内 cell 宽度步进 —— CJK 对齐）
    fun cellAt(offset: Offset): Pair<Int, Int>? {
        val rows = rowsState.value
        if (rows.isEmpty()) return null
        val contentY = offset.y + listState.firstVisibleItemIndex * lineHeightPx +
            listState.firstVisibleItemScrollOffset
        val row = (contentY / lineHeightPx).toInt().coerceIn(0, rows.size - 1)
        val cells = rows[row]
        var px = 0f
        var col = 0
        while (col < cells.size && px < offset.x) {
            px += if (cells[col].flags and RenderCell.FLAG_WIDE != 0) charWidthPx * 2 else charWidthPx
            col++
        }
        return row to col
    }

    /**
     * 双击选词：以命中列为中心向两侧扩到分隔符为止。
     *
     * 词内字符 = 字母数字 + 路径/标识符常见符号（`-_. /:`），因此双击能一次选中
     * `/sdcard/Download/a b.txt` 里的一段路径，而不是单个字符。
     * 返回 (anchor, head)，列区间左闭右开。
     */
    fun wordRangeAt(row: Int, col: Int): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val rows = rowsState.value
        val cells = rows.getOrNull(row) ?: return null
        if (col < 0 || col >= cells.size) return null

        fun isWordChar(text: String): Boolean {
            val ch = text.firstOrNull() ?: return false
            return ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.' ||
                ch == '/' || ch == ':' || ch == '~'
        }

        var start = col
        while (start > 0 && isWordChar(cells[start - 1].text)) start--
        var end = col
        while (end < cells.size && isWordChar(cells[end].text)) end++
        if (start >= end) return null
        return (row to start) to (row to end)
    }

    fun selRange(): SelRange? {
        val a = selectionAnchor ?: return null
        val h = selectionHead ?: return null
        return if (a.first < h.first || (a.first == h.first && a.second <= h.second)) {
            SelRange(a.first, a.second, h.first, h.second)
        } else {
            SelRange(h.first, h.second, a.first, a.second)
        }
    }

    fun selectedText(): String {
        val range = selRange() ?: return ""
        val rows = rowsState.value
        val builder = StringBuilder()
        for (r in range.startRow..range.endRow) {
            val cells = rows.getOrNull(r) ?: continue
            val from = if (r == range.startRow) range.startCol else 0
            val to = if (r == range.endRow) range.endCol else cells.size
            for (c in from until minOf(to, cells.size)) builder.append(cells[c].text)
            if (r != range.endRow) builder.append('\n')
        }
        return builder.toString()
    }

    // ── 硬件键盘（preview 优先消费；T86 升级：KeyEventMapping 完整修饰键协议）──
    fun handleHardwareKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val nk = event.nativeKeyEvent
        // 修饰位（xterm 协议输入侧）
        var mods = 0
        if (event.isCtrlPressed) mods = mods or KeyEventMapping.MOD_CTRL
        if (event.isShiftPressed) mods = mods or KeyEventMapping.MOD_SHIFT
        if (event.isAltPressed) mods = mods or KeyEventMapping.MOD_ALT
        // 修饰组合路径（含 F1-12/小键盘/修饰方向键）交给完整映射；
        // 无修饰的普通字符仍走 IME/onText（unicodeChar 只在修饰时需要）。
        if (mods != 0) {
            if (onHardwareKey(nk.keyCode, mods, nk.unicodeChar)) return true
        }
        // 无修饰：F1-F12 / 小键盘 / 方向键等非字符键仍需终端拦截
        when (nk.keyCode) {
            in KeyEventMapping.KEYCODE_F1..KeyEventMapping.KEYCODE_F12,
            in KeyEventMapping.KEYCODE_NUMPAD_0..KeyEventMapping.KEYCODE_NUMPAD_9,
            KeyEventMapping.KEYCODE_NUMPAD_ENTER, KeyEventMapping.KEYCODE_NUMPAD_ADD,
            KeyEventMapping.KEYCODE_NUMPAD_SUBTRACT, KeyEventMapping.KEYCODE_NUMPAD_MULTIPLY,
            KeyEventMapping.KEYCODE_NUMPAD_DIVIDE, KeyEventMapping.KEYCODE_NUMPAD_DOT,
            KeyEventMapping.KEYCODE_INSERT -> if (onHardwareKey(nk.keyCode, 0, nk.unicodeChar)) return true
        }
        // 旧路径：基础键（方向/Home/End/PgUp/PgDn/Del/Enter/Tab/Backspace/Esc）
        return when (event.key) {
            Key.Enter -> { onKey(TerminalKey.ENTER); true }
            Key.Backspace -> { onKey(TerminalKey.BACKSPACE); true }
            Key.Tab -> { onKey(TerminalKey.TAB); true }
            Key.Escape -> { onKey(TerminalKey.ESC); true }
            Key.DirectionUp -> { onKey(TerminalKey.ARROW_UP); true }
            Key.DirectionDown -> { onKey(TerminalKey.ARROW_DOWN); true }
            Key.DirectionLeft -> { onKey(TerminalKey.ARROW_LEFT); true }
            Key.DirectionRight -> { onKey(TerminalKey.ARROW_RIGHT); true }
            Key.MoveHome -> { onKey(TerminalKey.HOME); true }
            Key.MoveEnd -> { onKey(TerminalKey.END); true }
            Key.PageUp -> { onKey(TerminalKey.PAGE_UP); true }
            Key.PageDown -> { onKey(TerminalKey.PAGE_DOWN); true }
            Key.Delete -> { onKey(TerminalKey.DELETE); true }
            else -> false
        }
    }

    // ── Resize：视图尺寸 → rows/cols（与当前 PTY 尺寸不同才发）──
    //（viewSize 已提前声明于滚动段 —— 此处仅消费）
    val currentRows = render?.rows ?: 0
    val currentCols = render?.cols ?: 0
    LaunchedEffect(viewSize, charWidthPx, lineHeightPx, currentRows, currentCols) {
        if (render == null || viewSize == IntSize.Zero) return@LaunchedEffect
        val rows = (viewSize.height / lineHeightPx).toInt().coerceIn(2, 512)
        val cols = (viewSize.width / charWidthPx).toInt().coerceIn(4, 500)
        if (rows != currentRows || cols != currentCols) onResize(rows, cols)
    }

    // ── 响铃（BEL）：序号单调增，变化即"又响了一声"，交给宿主反馈 ──
    val bellSeq = render?.bellSeq ?: 0L
    LaunchedEffect(bellSeq) {
        if (bellSeq > 0L) onBell()
    }

    Column(modifier = modifier.fillMaxSize().background(activeScheme.backgroundC)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewSize = it }
                .onPreviewKeyEvent { handleHardwareKey(it) }
                // T86：双指捏合调字号（Termux 手势）—— 与 tap 手势独立挂载；
                // 阈值触发制（一次捏合跨阈值只步进一档，重置基准防连跳）。
                .pointerInput(Unit) {
                    var pinchBase = 1f
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom == 1f) return@detectTransformGestures
                        val accumulated = pinchBase * zoom
                        when {
                            accumulated >= 1.25f -> { onFontSizeStep(+1); pinchBase = 1f }
                            accumulated <= 0.8f -> { onFontSizeStep(-1); pinchBase = 1f }
                            else -> pinchBase = accumulated
                        }
                    }
                }
                // 点击整个终端区域（含"终端未启动"占位）都拉起输入法 —— 旧实现只挂在
                // LazyColumn 上，会话未启动 / 无输出时点哪都没反应。
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            // T86：OSC 8 链接点击优先（点击即打开，不拉键盘）
                            val at = cellAt(offset)
                            if (at != null) {
                                val snap = render
                                if (snap != null) {
                                    val rowCells = allRows.getOrNull(at.first)
                                    val cell = rowCells?.getOrNull(at.second)
                                    if (cell != null && cell.link != 0) {
                                        snap.linkTable[cell.link]?.let { uri ->
                                            onLinkOpen(uri)
                                            return@detectTapGestures
                                        }
                                    }
                                    // T86：鼠标报告开启（vim/tmux 触摸模式）→ 点击即鼠标事件
                                    if (snap.mouseMode.enabled) {
                                        // 屏内坐标（不含 scrollback 偏移）：visible 行号 = 全局行号 - scrollback.size
                                        val visibleRow = at.first - snap.scrollback.size + 1
                                        if (visibleRow >= 1) {
                                            onMouse(0, at.second + 1, visibleRow)  // 0 = PRESS
                                            onMouse(1, at.second + 1, visibleRow)  // 1 = RELEASE
                                            return@detectTapGestures
                                        }
                                    }
                                }
                            }
                            if (selectionActive) {
                                selectionAnchor = null; selectionHead = null
                            }
                            showKeyboard()
                        },
                        // 双击选词 —— 主流 Android 终端（Termux/JuiceSSH/ConnectBot）的
                        // 标准交互：选一个路径/标识符去复制，比拖动框选快得多。
                        onDoubleTap = { offset ->
                            val at = cellAt(offset) ?: return@detectTapGestures
                            val word = wordRangeAt(at.first, at.second) ?: return@detectTapGestures
                            selectionAnchor = word.first
                            selectionHead = word.second
                        }
                    )
                }
        ) {
            if (render == null || totalRows == 0) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.term_not_started),
                        color = Color(0xFF5A6270),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                // ── 输出 grid（只组合可视行；tap=聚焦/清除选择；长按起选+拖动扩选）──
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        // 点击聚焦已上移到外层 Box（覆盖"终端未启动"等无输出场景）
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    cellAt(offset)?.let { selectionAnchor = it; selectionHead = it }
                                },
                                onDrag = { change, _ ->
                                    cellAt(change.position)?.let { selectionHead = it }
                                },
                                onDragEnd = { /* 保留选择直至点击/取消 */ }
                            )
                        }
                ) {
                    items(totalRows) { index ->
                        TerminalRow(
                            cells = allRows[index],
                            baseStyle = baseStyle,
                            lineHeightDp = lineHeightDp,
                            monochrome = monochrome
                        )
                    }
                }

                // ── 选择高亮（可视行 → 视口坐标）──
                if (selectionActive) {
                    SelectionOverlay(
                        listState = listState,
                        rowsState = rowsState,
                        selRange = selRange(),
                        charWidthPx = charWidthPx,
                        lineHeightPx = lineHeightPx
                    )
                }

                // ── 光标（闪烁 beam；x 按行内 cell 宽度步进）──
                if (render.cursorVisible && !selectionActive) {
                    CursorOverlay(
                        listState = listState,
                        render = render,
                        charWidthPx = charWidthPx,
                        lineHeightPx = lineHeightPx
                    )
                }

                // ── 复制 / 取消浮标 ──
                if (selectionActive) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .background(Color(0xFF263041), RoundedCornerShape(10.dp)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(selectedText()))
                            selectionAnchor = null; selectionHead = null
                        }) { Text(stringResource(R.string.term_copy), fontSize = 12.sp) }
                        TextButton(onClick = {
                            selectionAnchor = null; selectionHead = null
                        }) { Text(stringResource(R.string.term_cancel), fontSize = 12.sp, color = Color(0xFF8A93A3)) }
                    }
                }

                // ── 跳到最新浮标（脱离吸底时）──
                if (!follow && totalRows > 0) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp)
                            .background(Color(0xE6263041), RoundedCornerShape(14.dp))
                            .clickable {
                                follow = true
                                scope.launch { listState.scrollToItem(followTarget()) }
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.term_jump_latest),
                            fontSize = 12.sp,
                            color = activeScheme.cursorC,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            // ── 隐藏 IME 桥（组合态整段下发；非组合按公共前缀增量 diff）──
            BasicTextField(
                value = imeBuffer,
                onValueChange = { new ->
                    /** 下发一段文本到 PTY（CTRL 锁存单字母转控制码；ALT 锁存 meta 化）。 */
                    fun deliver(chunk: String) {
                        // 换行归一：终端的"提交"是 \r（Enter 键语义），不是 \n
                        val text = chunk.replace('\n', '\r')
                        if (text.isEmpty()) return
                        when {
                            ctrlLatched && text.length == 1 && text[0].isLetter() -> {
                                onControl(text[0])
                                ctrlLatched = false
                            }
                            // T86：ALT 锁存 → meta 化（ESC + 字符；bash Alt+B/F 词跳、Alt+. 上参）
                            altLatched && text.length == 1 -> {
                                onText("\u001B$text")
                                altLatched = false
                            }
                            else -> onText(text)
                        }
                    }

                    // 统一的增量 diff：以「上一帧保留文本」为基准，只下发新增尾部、
                    // 用退格收回被删/被改的前缀。
                    //
                    // 组合态（composition）也走同一路径 —— 不单独缓存。原因：Gboard/百度/讯飞等
                    // 常把整词当成一个 composing span 一直不提交，直到按空格/回车；若组合态
                    // 完全不下发，字符就一直不出现，表现正是"打不进去"。现在每次
                    // setComposingText 的增量都实时进 PTY，候选上屏（commit）时旧组合文本由
                    // 退格收回 —— 这与真实终端的行为一致（输入法组合文本显示时被后续提交替换）。
                    //
                    // 🔑 关键修复：基准 `old` 必须是**上一帧保留下来的真实文本**，绝不能像旧
                    //    实现那样每敲一字就把 `imeBuffer` 重置成空 —— 那会让受控的 BasicTextField
                    //    告诉输入法"文本已清空"，而输入法内部还认为框里有字，状态脱节后输入法
                    //    会在**第二字起停止投递**（或重复投递），表现就是"字符根本打不进去"。
                    //    因此这里保持 `imeBuffer = new`（与编辑器/输入法一致），只在回车提交后
                    //    才清空，使下一行从干净状态开始。
                    val old = imeBuffer.text
                    val common = old.commonPrefixWith(new.text).length
                    repeat((old.length - common).coerceAtLeast(0)) { onKey(TerminalKey.BACKSPACE) }
                    if (new.text.length > common) deliver(new.text.substring(common))
                    imeBuffer = if (new.text.contains('\n') || new.text.contains('\r')) {
                        // 回车提交：本行已发，清空缓冲，下一行从干净状态开始
                        TextFieldValue("", TextRange(0))
                    } else {
                        // 保持缓冲与编辑器一致 —— 输入法不会因"被清空"而中止投递
                        new
                    }
                },
                textStyle = baseStyle.copy(color = Color.Transparent),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(1.dp)
                    .height(1.dp)
                    .focusRequester(focusRequester)
            )
        }

        // ── T87：扩展键行（用户自定义宏；空布局零占位）──
        if (extraKeys.isNotEmpty()) {
            com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysBar(
                layout = listOf(extraKeys),
                onText = onText,
                onKey = ::sendKeyWithLatches,
                onControl = onControl,
                onPaste = {
                    clipboard.getText()?.text?.let { onPaste(it) }
                }
            )
        }

        // ── 特殊键工具栏（触屏必备；横向滚动；可在终端设置中隐藏换显示区）──
        if (showKeybar) {
            KeyToolbar(
                ctrlActive = ctrlLatched,
                onCtrlToggle = { ctrlLatched = !ctrlLatched },
                shiftActive = shiftLatched,
                onShiftToggle = { shiftLatched = !shiftLatched },
                altActive = altLatched,
                onAltToggle = { altLatched = !altLatched },
                onText = onText,
                onKey = ::sendKeyWithLatches,
                onControl = onControl,
                onShowKeyboard = ::showKeyboard,
                onPaste = {
                    clipboard.getText()?.text?.let { onPaste(it) }
                }
            )
        }
    }
}

// ═══════════════════════ 行渲染 ═══════════════════════

@Composable
private fun TerminalRow(
    cells: List<RenderCell>,
    baseStyle: TextStyle,
    lineHeightDp: Dp,
    monochrome: Boolean
) {
    val scheme = LocalTerminalColorScheme.current
    val boldAsBright = LocalTerminalBoldAsBright.current
    val annotated = remember(cells, monochrome, baseStyle.fontSize, scheme, boldAsBright) {
        buildRowAnnotated(cells, monochrome, scheme, boldAsBright)
    }
    BasicText(
        text = annotated,
        style = baseStyle,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        softWrap = false,
        modifier = Modifier
            .fillMaxWidth()
            .height(lineHeightDp)
    )
}

/** 逐 cell 构建 AnnotatedString：同 style 连续段合并；hidden → 等宽空格。
 *  T87：颜色经 [TerminalAnsiRemapper] 从引擎标准板重映射到当前 scheme（含
 *  bold-as-bright 与 inverse 解析）—— 换肤零引擎改动。 */
private fun buildRowAnnotated(
    cells: List<RenderCell>,
    monochrome: Boolean,
    scheme: com.apex.agent.ui.screen.terminal.scheme.TerminalColorScheme,
    boldAsBright: Boolean
): AnnotatedString {
    if (cells.isEmpty()) return AnnotatedString("")
    return buildAnnotatedString {
        var i = 0
        while (i < cells.size) {
            var j = i
            while (j < cells.size && sameStyle(cells[i], cells[j])) j++
            val builder = StringBuilder()
            for (k in i until j) {
                val cell = cells[k]
                if (cell.flags and RenderCell.FLAG_HIDDEN != 0) builder.append(' ')
                else builder.append(cell.text)
            }
            append(builder.toString())
            spanStyleFor(cells[i], monochrome, scheme, boldAsBright)?.let { addStyle(it, i, i + (j - i)) }
            i = j
        }
    }
}

/** 两 cell 的可合并渲染样式是否一致（文本内容不参与）。 */
private fun sameStyle(a: RenderCell, b: RenderCell): Boolean =
    a.fg == b.fg && a.bg == b.bg && a.flags == b.flags

/** RenderCell → SpanStyle；monochrome 忽略颜色（保留字形/下划线语义）。
 *  T87：fg/bg 经 [TerminalAnsiRemapper] 映射到当前 scheme（bold-as-bright /
 *  inverse / dim 全部遵守 scheme 语义色）。 */
private fun spanStyleFor(
    cell: RenderCell,
    monochrome: Boolean,
    scheme: com.apex.agent.ui.screen.terminal.scheme.TerminalColorScheme,
    boldAsBright: Boolean
): SpanStyle? {
    val inverse = cell.flags and RenderCell.FLAG_INVERSE != 0
    val bold = cell.flags and RenderCell.FLAG_BOLD != 0
    val dim = cell.flags and RenderCell.FLAG_DIM != 0
    val italic = cell.flags and RenderCell.FLAG_ITALIC != 0
    val underline = cell.flags and RenderCell.FLAG_UNDERLINE != 0
    val strike = cell.flags and RenderCell.FLAG_STRIKE != 0

    var fg: Color? = null
    var bg: Color? = null
    if (!monochrome) {
        if (inverse) {
            // 反显：fg↔bg 交换（含双默认 = 亮底深字 / 浅色 scheme 深底亮字）
            val (ifg, ibg) = com.apex.agent.ui.screen.terminal.scheme.TerminalAnsiRemapper
                .mapInverse(cell.fg, cell.bg, scheme)
            fg = Color(ifg.toInt())
            bg = Color(ibg.toInt())
        } else {
            if (cell.fg != 0L) {
                val mapped = com.apex.agent.ui.screen.terminal.scheme.TerminalAnsiRemapper
                    .mapForeground(cell.fg, bold, boldAsBright, scheme)
                fg = if (dim) Color(
                    com.apex.agent.ui.screen.terminal.scheme.TerminalAnsiRemapper
                        .dimColor(mapped).toInt()
                ) else Color(mapped.toInt())
            }
            if (cell.bg != 0L) {
                val mapped = com.apex.agent.ui.screen.terminal.scheme.TerminalAnsiRemapper
                    .mapBackground(cell.bg, scheme)
                bg = Color(mapped.toInt())
            }
        }
    }
    if (fg == null && bg == null && !bold && !italic && !underline && !strike) return null
    return SpanStyle(
        color = fg ?: Color.Unspecified,
        background = bg ?: Color.Unspecified,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = when {
            underline && strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            underline -> TextDecoration.Underline
            strike -> TextDecoration.LineThrough
            else -> null
        }
    )
}

// ═══════════════════════ 光标 / 选择 overlay ═══════════════════════

@Composable
private fun CursorOverlay(
    listState: LazyListState,
    render: TerminalRenderSnapshot,
    charWidthPx: Float,
    lineHeightPx: Float
) {
    val itemIndex = render.scrollback.size + render.cursorRow
    val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }
        ?: return
    // x：行内按 cell 宽度步进（宽字符 2 列），越界尾部按 1 列步进。
    // P1（CJK 光标漂移）：渲染行已剔除宽字符 trail cell（列表比 VT 列号短），
    // 旧循环用「VT 列号」直接索引渲染列表 → 每个宽字符后索引错位 1，光标
    // 向右漂移。改为以「已消费的 VT 列数」终止循环，索引与列数解耦。
    val rowCells = render.lines.getOrNull(render.cursorRow) ?: emptyList()
    var x = 0f
    var i = 0
    var vtCol = 0
    while (vtCol < render.cursorCol && i < rowCells.size) {
        val wide = rowCells[i].flags and RenderCell.FLAG_WIDE != 0
        x += if (wide) charWidthPx * 2 else charWidthPx
        vtCol += if (wide) 2 else 1
        i++
    }
    if (render.cursorCol > vtCol) x += (render.cursorCol - vtCol) * charWidthPx

    val transition = rememberInfiniteTransition(label = "cursor-blink")
    val alpha by transition.animateFloat(
        initialValue = 1f, targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "cursor-alpha"
    )
    Box(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), visible.offset) }
            .width(2.dp)
            .height(with(LocalDensity.current) { (lineHeightPx * 0.86f).toDp() })
            .background(LocalTerminalColorScheme.current.cursorC.copy(alpha = 0.9f * alpha))
    )
}

@Composable
private fun SelectionOverlay(
    listState: LazyListState,
    rowsState: State<List<List<RenderCell>>>,
    selRange: SelRange?,
    charWidthPx: Float,
    lineHeightPx: Float
) {
    val range = selRange ?: return
    // T87：CompositionLocal 只能在 @Composable 上下文读 —— Canvas 绘制 lambda
    // 非 Composable，先取值再进绘制闭包。
    val selectionColor = LocalTerminalColorScheme.current.selectionBackgroundC
    Canvas(modifier = Modifier.fillMaxSize()) {
        for (info in listState.layoutInfo.visibleItemsInfo) {
            val r = info.index
            if (r < range.startRow || r > range.endRow) continue
            val cells = rowsState.value.getOrNull(r) ?: continue
            val from = if (r == range.startRow) range.startCol else 0
            val to = if (r == range.endRow) range.endCol else cells.size
            if (to <= from) continue
            val x0 = columnX(cells, from, charWidthPx)
            val x1 = columnX(cells, to, charWidthPx)
            drawRect(
                color = selectionColor,
                topLeft = Offset(x0, info.offset.toFloat()),
                size = Size(x1 - x0, lineHeightPx * 0.96f)
            )
        }
    }
}

/** 行内列号 → 像素 x（宽字符 2 列步进；越界按 1 列）。 */
private fun columnX(cells: List<RenderCell>, col: Int, charWidthPx: Float): Float {
    var x = 0f
    var i = 0
    while (i < col && i < cells.size) {
        x += if (cells[i].flags and RenderCell.FLAG_WIDE != 0) charWidthPx * 2 else charWidthPx
        i++
    }
    if (col > cells.size) x += (col - cells.size) * charWidthPx
    return x
}

