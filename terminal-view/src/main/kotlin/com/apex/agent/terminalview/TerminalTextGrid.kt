package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.RenderCell
import kotlin.math.abs

/**
 * T88（2-a）：网格几何纯数学 —— cell↔pixel 换算、可视行范围、光标/选区像素矩形。
 *
 * 为什么独立成类：这些是**最容易被 CJK/emoji 搞错的部分**（宽字符 2 列步进、
 * VT 列号 vs 渲染列表下标解耦 —— P1 光标漂移教训），抽成纯函数后可以在 JVM
 * 单测里用纯数字断言（不依赖 Paint/Canvas）。View 只负责把 MotionEvent 坐标
 * 喂进来、把算出的 Rect 交给 Canvas。
 *
 * 坐标系：内容区左上角为原点；行高恒定 [cellHeightPx]（无基线抖动 —— 基线居中
 * 由渲染器用字体度量算一次，与网格几何解耦）。
 */
class TerminalTextGrid(
    /** 单列宽（px，含 [TerminalViewSettings.wideSafetyFactor]）。 */
    val cellWidthPx: Float,
    /** 单行高（px = 字号 sp × lineHeightFactor）。 */
    val cellHeightPx: Float,
    /** 内容区像素宽。 */
    val widthPx: Float,
    /** 内容区像素高。 */
    val heightPx: Int,
    /** 视口可容纳行数（clamp 2..512）。 */
    val viewRows: Int,
    /** 视口可容纳列数（clamp 4..500）。 */
    val viewCols: Int
) {
    init {
        require(viewRows >= 1 && viewCols >= 1) { "grid must have positive capacity" }
    }

    /** 行号 → 行顶 y（px）。行号是「合并网格」下标（scrollback+屏），允许为负/越界
     *  由调用方保证；本方法不做 clamp（渲染前已按可视范围过滤）。 */
    fun rowTopY(row: Int): Float = row * cellHeightPx

    /** 行号 → 行底 y（px）。 */
    fun rowBottomY(row: Int): Float = (row + 1) * cellHeightPx

    /**
     * 行内列号 → 像素 x。宽字符（FLAG_WIDE）占 2 列 —— **以渲染列表步进**，
     * 与旧渲染器 `columnX` 同式；col 超出列表长度后按 1 列步进（VT 列号 >
     * 渲染列数时的兜底，防越界负偏移）。
     */
    fun columnX(cells: List<RenderCell>, col: Int): Float {
        var x = 0f
        var i = 0
        var remaining = col
        while (i < cells.size && remaining > 0) {
            val wide = cells[i].flags and RenderCell.FLAG_WIDE != 0
            val advance = if (wide) 2 else 1
            if (remaining < advance) break // 指在宽字符中间：取其左沿
            x += cellWidthPx * advance
            remaining -= advance
            i++
        }
        if (remaining > 0) x += remaining * cellWidthPx
        return x
    }

    /**
     * 像素 x → 行内列号（VT 列语义：宽字符落点取它自己的起始列）。返回值可能 ==
     * cells 总列数（点击行尾右侧）。
     */
    fun columnAt(cells: List<RenderCell>, x: Float): Int {
        if (x <= 0f || cells.isEmpty()) return 0
        var px = 0f
        var i = 0
        while (i < cells.size) {
            val wide = cells[i].flags and RenderCell.FLAG_WIDE != 0
            px += cellWidthPx * (if (wide) 2 else 1)
            if (x < px) return columnOfIndex(cells, i)
            i++
        }
        // 行尾右侧：按空列数延伸（视口列数上限）
        val beyond = ((x - px) / cellWidthPx).toInt().coerceAtLeast(0)
        return columnOfIndex(cells, cells.size) + beyond
    }

    /** 渲染列表下标 → VT 列号（宽字符占 2 列累计）。 */
    fun columnOfIndex(cells: List<RenderCell>, index: Int): Int {
        var col = 0
        var i = 0
        while (i < index && i < cells.size) {
            col += if (cells[i].flags and RenderCell.FLAG_WIDE != 0) 2 else 1
            i++
        }
        return col
    }

    /** 像素 y → 行号（向下取整，clamp 到 [0, maxRow]）。 */
    fun rowAt(y: Float, maxRow: Int): Int =
        (y / cellHeightPx).toInt().coerceIn(0, maxRow.coerceAtLeast(0))

    /** 光标像素 x（行内 VT 列 → 宽字符步进；与旧 `CursorOverlay` 同式）。 */
    fun cursorPixelX(cursorRowCells: List<RenderCell>, cursorCol: Int): Float =
        columnX(cursorRowCells, cursorCol)

    /**
     * 一行的选区矩形（fromCol/toCol 为 VT 列语义，左闭右开）。
     *
     * @return (x0, x1) —— x1 ≥ x0；空区间返回 null。
     */
    fun selectionXRange(cells: List<RenderCell>, fromCol: Int, toCol: Int): Pair<Float, Float>? {
        if (toCol <= fromCol) return null
        val x0 = columnX(cells, fromCol)
        val x1 = columnX(cells, toCol)
        return if (x1 > x0) x0 to x1 else null
    }

    /** 行内像素 x 是否命中某列的「链接热区」（列起止矩形）。 */
    fun hitTestColumn(cells: List<RenderCell>, x: Float): Int = columnAt(cells, x)

    /** 滚动条几何：返回 (thumbTopY, thumbHeightPx, trackHeightPx)；无滚动量 → null。 */
    fun scrollbarGeometry(
        gridRows: Int,
        firstVisibleRow: Int
    ): Triple<Float, Float, Float>? {
        if (gridRows <= viewRows) return null
        val track = heightPx.toFloat()
        val thumb = (viewRows.toFloat() / gridRows.toFloat()) * track
        val maxFirst = (gridRows - viewRows).toFloat()
        val frac = if (maxFirst <= 0f) 0f else (firstVisibleRow.toFloat() / maxFirst).coerceIn(0f, 1f)
        val top = (track - thumb) * frac
        return Triple(top, thumb, track)
    }

    /** 指定行是否落在视口（[firstVisibleRow, firstVisibleRow+viewRows)）。 */
    fun isRowVisible(row: Int, firstVisibleRow: Int): Boolean =
        row >= firstVisibleRow && row < firstVisibleRow + viewRows

    companion object {
        /** 行数 clamp 下限（与旧渲染器一致 —— 单行终端没有意义）。 */
        const val MIN_ROWS = 2

        /** 行数 clamp 上限（防极端字号 + 巨屏撑爆快照）。 */
        const val MAX_ROWS = 512

        /** 列数 clamp 下限。 */
        const val MIN_COLS = 4

        /** 列数 clamp 上限。 */
        const val MAX_COLS = 500

        /**
         * 由视口像素与字体度量推导网格（View onSizeChanged / 字号变化后调用）。
         *
         * @param charAdvancePx 单字符 advance（Paint 实测，>0）
         * @param charHeightPx 单行高（字号 × 行高系数，>0）
         */
        fun compute(
            widthPx: Int,
            heightPx: Int,
            charAdvancePx: Float,
            charHeightPx: Float,
            wideSafetyFactor: Float = 1.0f
        ): TerminalTextGrid {
            val safeAdvance = if (charAdvancePx.isFinite() && charAdvancePx > 0.01f) charAdvancePx else 8f
            val safeHeight = if (charHeightPx.isFinite() && charHeightPx > 0.01f) charHeightPx else 16f
            val safeFactor = if (wideSafetyFactor.isFinite() && wideSafetyFactor > 0.5f) wideSafetyFactor else 1.0f
            val cw = safeAdvance * safeFactor
            val rows = if (heightPx > 0) (heightPx / safeHeight).toInt() else 0
            val cols = if (widthPx > 0) (widthPx / cw).toInt() else 0
            return TerminalTextGrid(
                cellWidthPx = cw,
                cellHeightPx = safeHeight,
                widthPx = if (widthPx > 0) widthPx.toFloat() else cw * MIN_COLS,
                heightPx = if (heightPx > 0) heightPx else (safeHeight * MIN_ROWS).toInt(),
                viewRows = rows.coerceIn(MIN_ROWS, MAX_ROWS),
                viewCols = cols.coerceIn(MIN_COLS, MAX_COLS)
            )
        }

        /** 像素距离是否在 tap slop 内（双击/长按位移判定的公共阈值）。 */
        fun withinSlop(dx: Float, dy: Float, slopPx: Float): Boolean =
            abs(dx) <= slopPx && abs(dy) <= slopPx
    }
}
