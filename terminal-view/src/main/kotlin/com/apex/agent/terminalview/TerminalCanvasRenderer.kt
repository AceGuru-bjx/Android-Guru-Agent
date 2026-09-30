package com.apex.agent.terminalview

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.Shader
import com.apex.agent.terminalemulator.RenderCell
import com.apex.agent.terminalemulator.TerminalRenderSnapshot
import kotlin.math.abs

/**
 * T88（2-a）：Canvas 绘制通道 —— 快照 → 像素（android.graphics 专用层）。
 *
 * ## 绘制策略（Termux TerminalRenderer 对齐）
 *
 *  - **只画可视行**（`TerminalScrollModel.visibleRange()`）—— 60fps 下 50 行 ×
 *    数个 run，而非 Compose LazyColumn 的重组+布局+文本测量三级放大；
 *  - **run 折叠 + 缓存**：行 → `CellRun` 列表按（快照 id + 行号）缓存 —— 滚动
 *    重绘零折叠成本；快照换代才整表失效；
 *  - **列对齐校正**（Termux 关键技巧）：每 run 实测 `measureText` vs
 *    `colSpan × cellWidth`，偏差超 1% → `textScaleX` 缩放绘制 —— bold 假粗体/
 *    CJK 回退字体的 advance 漂移被强制锁列，绝不允许「光标越走越歪」；
 *  - **基线一次性居中**：ascent/descent 中点对齐行高中点（`onFontChanged` 算好），
 *    滚动时行与行之间无基线抖动；
 *  - 光标按 DECSCUSR 画 BLOCK/UNDERLINE/BAR，闪烁相位由 View 传入（只画
 *    `cursorVisible && focused`—— 失焦/选择时常亮淡显，不空转 Choreographer）。
 *
 * 本类**无状态语义**（可被多个 View 实例复用）；字体度量随 settings 重探测。
 */
class TerminalCanvasRenderer {

    // ─── Paint 池（每帧只改属性，不重建对象）───
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        isLinearText = false
        letterSpacing = 0f
    }
    private val bgPaint = Paint()
    private val selectionPaint = Paint()
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val decorationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val scrollbarTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrollbarThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint()
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 40f
    }

    // ─── 字体度量（onFontChanged 重探测）───
    private var boldTypeface: Typeface = Typeface.DEFAULT_BOLD
    private var italicTypeface: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
    private var boldItalicTypeface: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC)
    private var fontAscent = 0f
    private var fontDescent = 0f
    private var baselineOffsetInRow = 0f

    /** 单字符 advance（px，View 在 onSizeChanged/字号变化后读取）。 */
    var charAdvancePx: Float = 8f
        private set

    // ─── run 缓存（快照代际失效）───
    private var cachedFrameId = -1L
    private val runCache = HashMap<Int, List<TerminalRowRun.CellRun>>()

    // ─── 着色器缓存（尺寸代际失效）───
    private var shaderWidth = -1
    private var shaderHeight = -1

    /**
     * 字体/字号变化（settings 换新、字号捏合步进后调用）。
     *
     * @return 重探测后的单字符 advance（View 用它算网格）
     */
    fun onFontChanged(settings: TerminalViewSettings, density: Float): Float {
        val textSizePx = settings.fontSizeSp * density
        textPaint.textSize = textSizePx
        placeholderPaint.textSize = 13f * density
        val style = settings.typefaceStyle
        val base = Typeface.MONOSPACE
        textPaint.typeface = when (style) {
            TerminalViewSettings.TYPEFACE_BOLD -> Typeface.create(base, Typeface.BOLD)
            TerminalViewSettings.TYPEFACE_ITALIC -> Typeface.create(base, Typeface.ITALIC)
            TerminalViewSettings.TYPEFACE_BOLD_ITALIC -> Typeface.create(base, Typeface.BOLD_ITALIC)
            else -> Typeface.create(base, Typeface.NORMAL)
        }
        boldTypeface = Typeface.create(base, Typeface.BOLD)
        italicTypeface = Typeface.create(base, Typeface.ITALIC)
        boldItalicTypeface = Typeface.create(base, Typeface.BOLD_ITALIC)
        // advance 探测：64 个 '0' 取均值（单字符舍入误差 ≤0.5px 摊薄）
        val probe = PROBE_CHARS
        charAdvancePx = (textPaint.measureText(probe) / probe.length).coerceAtLeast(1f)
        // 基线居中：ascent/descent 中点对齐行高中点
        val fm = textPaint.fontMetrics
        fontAscent = fm.ascent
        fontDescent = fm.descent
        baselineOffsetInRow = -fontAscent // 行顶 → 基线（未含行高居中修正 —— 行高 > 字高时在 draw 里补）
        return charAdvancePx
    }

    /** 基线 y（行顶 + 居中修正）。 */
    private fun baselineForRow(rowTop: Float, cellHeight: Float): Float {
        val textH = fontDescent - fontAscent
        val pad = ((cellHeight - textH) / 2f).coerceAtLeast(0f)
        return rowTop + pad + baselineOffsetInRow
    }

    /** 一帧的完整输入（View 组装；全部只读）。 */
    data class RenderFrame(
        /** 合并网格行（scrollback+屏，快照代内不变）。 */
        val rows: List<List<RenderCell>>,
        val snapshot: TerminalRenderSnapshot,
        val grid: TerminalTextGrid,
        val scroll: TerminalScrollModel,
        val selection: TerminalSelectionModel,
        val settings: TerminalViewSettings,
        val palette: TerminalPalette,
        /** View 当前是否有窗口焦点。 */
        val focused: Boolean,
        /** 光标闪烁相位（true = 亮）。 */
        val blinkOn: Boolean,
        /** 选区激活（光标避让 —— 与旧渲染器一致）。 */
        val selectionActive: Boolean,
        /** 视口像素尺寸。 */
        val viewWidthPx: Int,
        val viewHeightPx: Int,
        /** 屏幕密度（dp → px）。 */
        val density: Float,
        /** 快照代际 id（run 缓存键）。 */
        val frameId: Long,
        /** 行 id → 合并下标换算基（snapshot.scrollbackBase - scrollback.size）。 */
        val rowIdBase: Long
    )

    /** 主绘制入口（View.onDraw 调用；任何输入异常都不允许炸绘制）。 */
    fun draw(canvas: Canvas, frame: RenderFrame) {
        try {
            drawInternal(canvas, frame)
        } catch (_: RuntimeException) {
            // 绘制态损坏（极端字体/巨型度量）→ 丢弃本帧而非崩 UI
        } catch (_: IllegalArgumentException) {
            // LinearGradient 越界等 —— 同上
        }
    }

    private fun drawInternal(canvas: Canvas, frame: RenderFrame) {
        val palette = frame.palette
        val grid = frame.grid
        if (frame.rows.isEmpty() || frame.viewWidthPx <= 0 || frame.viewHeightPx <= 0) {
            drawPlaceholder(canvas, frame)
            return
        }
        // 0) 默认底色（一次整屏填充 —— 覆盖 O 体积但 1 次调用）
        bgPaint.color = palette.background
        canvas.drawRect(0f, 0f, frame.viewWidthPx.toFloat(), frame.viewHeightPx.toFloat(), bgPaint)

        invalidateRunCacheIfNeeded(frame.frameId)

        val range = frame.scroll.visibleRange()
        val firstVis = range.first
        val cellH = grid.cellHeightPx
        val rowIdBase = frame.rowIdBase
        textPaint.textSize = frame.settings.fontSizeSp * frame.density
        var cursorDrawn = false

        for (row in range) {
            val cells = frame.rows.getOrNull(row) ?: continue
            val rowTop = (row - firstVis) * cellH
            val runs = runsFor(row, cells, frame)
            val rowBottom = rowTop + cellH
            val rowId = rowIdBase + row

            // 1) 行内非默认底色 run（背景 pass —— 先底后字）
            for (run in runs) {
                if (run.bgArgb != 0 && run.bgArgb != palette.background && run.colSpan > 0) {
                    bgPaint.color = run.bgArgb
                    val x = run.colStart * grid.cellWidthPx
                    canvas.drawRect(x, rowTop, x + run.colSpan * grid.cellWidthPx, rowBottom, bgPaint)
                }
            }

            // 2) 选区高亮（在文本之下 —— 文字保持原色，与旧渲染器视觉一致）
            drawSelectionForRow(canvas, frame, row, rowId, cells, rowTop, rowBottom)

            // 3) 文本（列对齐校正 + 逐 run drawText）
            drawRowText(canvas, frame, runs, rowTop, cellH)

            // 4) 下划线/删除线/链接装饰
            drawDecorations(canvas, frame, runs, rowTop, rowBottom, grid.cellWidthPx)

            // 5) 光标（命中行才画）
            if (!cursorDrawn) {
                cursorDrawn = drawCursor(canvas, frame, row, rowTop, rowBottom, grid.cellWidthPx)
            }
        }

        // 6) 滚动条 / 边缘渐隐 / 新输出指示器
        drawScrollbar(canvas, frame)
        drawFadeEdges(canvas, frame)
        if (frame.settings.showNewOutputIndicator && !frame.scroll.isAtBottom) {
            drawNewOutputIndicator(canvas, frame)
        }
    }

    // ─── 行折叠与缓存 ───

    private fun invalidateRunCacheIfNeeded(frameId: Long) {
        if (frameId != cachedFrameId) {
            runCache.clear()
            cachedFrameId = frameId
        }
    }

    private fun runsFor(row: Int, cells: List<RenderCell>, frame: RenderFrame): List<TerminalRowRun.CellRun> {
        runCache[row]?.let { return it }
        val palette = frame.palette
        val n = cells.size
        val fg = IntArray(n)
        val bg = IntArray(n)
        val mono = frame.settings.monochrome
        for (i in 0 until n) {
            val c = cells[i]
            if (mono) {
                // 单色模式：颜色全默认，仅保留字形/下划线语义（旧渲染器 monochrome 同款）
                fg[i] = palette.foreground
                bg[i] = palette.background
            } else {
                val (f, b) = palette.resolveCell(c.fg, c.bg, c.flags)
                fg[i] = f
                bg[i] = b
            }
        }
        val runs = TerminalRowRun.collapse(cells, fg, bg)
        if (runCache.size < RUN_CACHE_MAX_ROWS) runCache[row] = runs
        return runs
    }

    // ─── 文本 ───

    private fun drawRowText(
        canvas: Canvas,
        frame: RenderFrame,
        runs: List<TerminalRowRun.CellRun>,
        rowTop: Float,
        cellHeight: Float
    ) {
        val cw = frame.grid.cellWidthPx
        val baseline = baselineForRow(rowTop, cellHeight)
        for (run in runs) {
            if (run.text.isEmpty() || run.colSpan <= 0) continue
            // 属性派生（typeface/删除线）
            val bold = run.flags and RenderCell.FLAG_BOLD != 0
            val italic = run.flags and RenderCell.FLAG_ITALIC != 0
            textPaint.typeface = when {
                bold && italic -> boldItalicTypeface
                bold -> boldTypeface
                italic -> italicTypeface
                else -> Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            }
            textPaint.color = if (run.fgArgb != 0) run.fgArgb else frame.palette.foreground
            // 列对齐校正（Termux 技巧 —— 见类 KDoc）：实测宽度 ≠ 期望列宽 → textScaleX
            val expected = run.colSpan * cw
            val measured = textPaint.measureText(run.text)
            val scaleX = if (measured > 0.5f) expected / measured else 1f
            textPaint.textScaleX = if (scaleX.isFinite() && scaleX in 0.5f..2.2f) scaleX else 1f
            canvas.drawText(run.text, run.colStart * cw, baseline, textPaint)
        }
        textPaint.textScaleX = 1f
    }

    // ─── 装饰（下划线/删除线/链接）───

    private fun drawDecorations(
        canvas: Canvas,
        frame: RenderFrame,
        runs: List<TerminalRowRun.CellRun>,
        rowTop: Float,
        rowBottom: Float,
        cellWidthPx: Float
    ) {
        val strokeW = (frame.density * 1.2f).coerceAtLeast(1.5f)
        val underlineY = rowBottom - (rowBottom - rowTop) * 0.12f
        val strikeY = rowTop + (rowBottom - rowTop) * 0.5f
        for (run in runs) {
            val underline = run.flags and RenderCell.FLAG_UNDERLINE != 0
            val strike = run.flags and RenderCell.FLAG_STRIKE != 0
            val linkUnderline = run.link != 0 && frame.settings.drawLinkUnderline
            if (!underline && !strike && !linkUnderline) continue
            val x0 = run.colStart * cellWidthPx
            val x1 = x0 + run.colSpan * cellWidthPx
            decorationPaint.strokeWidth = strokeW
            if (underline) {
                decorationPaint.color = if (run.fgArgb != 0) run.fgArgb else frame.palette.foreground
                canvas.drawLine(x0, underlineY, x1, underlineY, decorationPaint)
            }
            if (strike) {
                decorationPaint.color = if (run.fgArgb != 0) run.fgArgb else frame.palette.foreground
                canvas.drawLine(x0, strikeY, x1, strikeY, decorationPaint)
            }
            if (linkUnderline) {
                decorationPaint.color = frame.palette.linkColor
                canvas.drawLine(x0, underlineY, x1, underlineY, decorationPaint)
            }
        }
    }

    // ─── 选区 ───

    private fun drawSelectionForRow(
        canvas: Canvas,
        frame: RenderFrame,
        row: Int,
        rowId: Long,
        cells: List<RenderCell>,
        rowTop: Float,
        rowBottom: Float
    ) {
        val sel = frame.selection.normalized() ?: return
        val (start, end) = sel
        if (rowId < start.rowId || rowId > end.rowId) return
        val fromCol = if (rowId == start.rowId) start.col else 0
        val toCol = if (rowId == end.rowId) end.col else Int.MAX_VALUE
        val viewW = frame.viewWidthPx.toFloat()
        val xRange = frame.grid.selectionXRange(cells, fromCol, toCol)
        val x0: Float
        val x1: Float
        if (xRange != null) {
            x0 = xRange.first.coerceIn(0f, viewW)
            x1 = xRange.second.coerceIn(0f, viewW)
        } else {
            // toCol 超出行长（整行选）→ 从 fromCol 画到行尾/视口右沿
            x0 = frame.grid.columnX(cells, fromCol).coerceIn(0f, viewW)
            x1 = viewW
        }
        if (x1 <= x0) return
        selectionPaint.color = frame.palette.selectionBackground
        canvas.drawRect(x0, rowTop, x1, rowBottom, selectionPaint)
        frame.settings.selectionBorderColor?.let {
            decorationPaint.color = it
            decorationPaint.strokeWidth = frame.density * 0.75f
            canvas.drawRect(x0, rowTop, x1, rowBottom, decorationPaint)
            decorationPaint.strokeWidth = 1f
        }
    }

    // ─── 光标 ───

    /** 画光标（仅命中行调用；返回是否命中绘制）。
     *
     * 可见性：`cursorVisible && !选区激活`；失焦时**常亮淡化**（0.5 alpha ——
     * 提示光标位置但不闪烁：未聚焦时 View 会停掉 Choreographer，blinkOn 不再
     * 翻转，这里不能依赖它）。 */
    private fun drawCursor(
        canvas: Canvas,
        frame: RenderFrame,
        row: Int,
        rowTop: Float,
        rowBottom: Float,
        cellWidthPx: Float
    ): Boolean {
        val snap = frame.snapshot
        if (!snap.cursorVisible) return false
        if (frame.selectionActive) return false
        val cursorRowMerged = snap.scrollback.size + snap.cursorRow
        if (cursorRowMerged != row) return false
        val rowCells = frame.rows.getOrNull(row) ?: return false
        if (row < 0 || row >= frame.rows.size) return false
        val x = frame.grid.cursorPixelX(rowCells, snap.cursorCol)
        val effectiveAlpha = when {
            !frame.focused -> 0.5f              // 失焦：常亮淡显（不闪烁）
            !frame.blinkOn -> 0.25f             // 闪烁灭相位
            else -> 0.9f                        // 正常亮相位
        }
        cursorPaint.color = frame.palette.cursor
        cursorPaint.alpha = (effectiveAlpha * 255f).toInt().coerceIn(0, 255)
        when (snap.cursorStyle) {
            com.apex.agent.terminalemulator.CursorStyle.BLOCK -> {
                val w = cellWidthPx.coerceAtLeast(frame.density * 2f)
                canvas.drawRect(x, rowTop, x + w, rowBottom, cursorPaint)
            }
            com.apex.agent.terminalemulator.CursorStyle.UNDERLINE -> {
                val h = (frame.density * 3f).coerceAtLeast(2f)
                canvas.drawRect(x, rowBottom - h, x + cellWidthPx * 2, rowBottom, cursorPaint)
            }
            else -> { // BAR（默认）
                val w = (frame.density * 2f).coerceAtLeast(1.5f)
                val inset = (rowBottom - rowTop) * 0.07f
                canvas.drawRect(x, rowTop + inset, x + w, rowBottom - inset, cursorPaint)
            }
        }
        cursorPaint.alpha = 255
        return true
    }

    // ─── 滚动条 / 渐隐 / 指示器 ───

    private fun drawScrollbar(canvas: Canvas, frame: RenderFrame) {
        val grid = frame.grid
        val geo = grid.scrollbarGeometry(frame.rows.size, frame.scroll.firstVisibleRow) ?: return
        val (thumbTop, thumbH, trackH) = geo
        val w = if (frame.settings.scrollbarWidthPx > 0) frame.settings.scrollbarWidthPx.toFloat()
        else frame.density * 3f
        val x = frame.viewWidthPx - w
        scrollbarTrackPaint.color = frame.settings.scrollbarTrackColor
            ?: defaultScrollbar(frame.palette, alphaF = 0.18f)
        scrollbarThumbPaint.color = frame.settings.scrollbarThumbColor
            ?: defaultScrollbar(frame.palette, alphaF = 0.55f)
        canvas.drawRect(x, 0f, x + w, trackH, scrollbarTrackPaint)
        val radius = w / 2f
        canvas.drawRoundRect(RectF(x, thumbTop, x + w, thumbTop + thumbH), radius, radius, scrollbarThumbPaint)
    }

    private fun defaultScrollbar(palette: TerminalPalette, alphaF: Float): Int =
        TerminalPalette.blend(palette.background, palette.foreground, 0.6f).let {
            (alphaF * 255).toInt().coerceIn(0, 255) shl 24 or (it and 0xFFFFFF)
        }

    private fun drawFadeEdges(canvas: Canvas, frame: RenderFrame) {
        val fadePx = if (frame.settings.fadeEdgePx >= 0) frame.settings.fadeEdgePx
        else (frame.density * 14f).toInt()
        if (fadePx <= 0 || frame.viewHeightPx <= fadePx * 2) return
        ensureFadeShaders(frame, fadePx.toFloat())
        if (!frame.scroll.isAtTop && frame.scroll.firstVisibleRow > 0) {
            canvas.drawRect(0f, 0f, frame.viewWidthPx.toFloat(), fadePx.toFloat(), fadePaintTop)
        }
        if (!frame.scroll.isAtBottom) {
            val top = frame.viewHeightPx - fadePx
            canvas.drawRect(0f, top.toFloat(), frame.viewWidthPx.toFloat(), frame.viewHeightPx.toFloat(), fadePaintBottom)
        }
    }

    private var fadePaintTop = Paint()
    private var fadePaintBottom = Paint()

    private fun ensureFadeShaders(frame: RenderFrame, fadePx: Float) {
        if (shaderWidth == frame.viewWidthPx && shaderHeight == frame.viewHeightPx) return
        shaderWidth = frame.viewWidthPx
        shaderHeight = frame.viewHeightPx
        val bg = frame.palette.background
        val fg = frame.palette.foreground
        val edge = TerminalPalette.blend(bg, fg, 0.25f)
        fadePaintTop = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, fadePx,
                edge, bg, Shader.TileMode.CLAMP
            )
        }
        fadePaintBottom = Paint().apply {
            shader = LinearGradient(
                0f, frame.viewHeightPx.toFloat(), 0f,
                frame.viewHeightPx - fadePx,
                edge, bg, Shader.TileMode.CLAMP
            )
        }
    }

    /** 非贴底时的「新输出」指示（下中部的 ↓ 药丸 —— 与宿主 onTerminalScrollChanged
     *  affordance 互补；宿主可 settings.showNewOutputIndicator=false 关闭）。 */
    private fun drawNewOutputIndicator(canvas: Canvas, frame: RenderFrame) {
        val d = frame.density
        val w = 44f * d
        val h = 26f * d
        val cx = frame.viewWidthPx / 2f
        val bottom = frame.viewHeightPx - 10f * d
        val rect = RectF(cx - w / 2f, bottom - h, cx + w / 2f, bottom)
        indicatorPaint.color = TerminalPalette.blend(frame.palette.background, frame.palette.cursor, 0.35f)
        indicatorPaint.alpha = 230
        canvas.drawRoundRect(rect, h / 2f, h / 2f, indicatorPaint)
        // ↓ 箭头
        indicatorPaint.color = frame.palette.cursor
        indicatorPaint.style = Paint.Style.STROKE
        indicatorPaint.strokeWidth = 2.5f * d
        indicatorPaint.strokeCap = Paint.Cap.ROUND
        val path = Path()
        val midY = rect.centerY()
        path.moveTo(cx, midY - 6f * d)
        path.lineTo(cx, midY + 6f * d)
        path.moveTo(cx - 4.5f * d, midY + 2.5f * d)
        path.lineTo(cx, midY + 7.5f * d)
        path.lineTo(cx + 4.5f * d, midY + 2.5f * d)
        canvas.drawPath(path, indicatorPaint)
        indicatorPaint.style = Paint.Style.FILL
        indicatorPaint.alpha = 255
    }

    /** 空快照占位（「终端未启动」—— 宿主也可自行盖层；这里给最小视觉）。 */
    private fun drawPlaceholder(canvas: Canvas, frame: RenderFrame) {
        bgPaint.color = frame.palette.background
        canvas.drawRect(0f, 0f, frame.viewWidthPx.toFloat(), frame.viewHeightPx.toFloat(), bgPaint)
    }

    private companion object {
        const val PROBE_CHARS = "0000000000000000000000000000000000000000000000000000000000000000"
        const val RUN_CACHE_MAX_ROWS = 2048
    }
}
