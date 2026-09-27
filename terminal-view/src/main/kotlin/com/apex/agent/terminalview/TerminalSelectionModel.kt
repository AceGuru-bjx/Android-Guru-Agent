package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.RenderCell

/**
 * T88（2-a）：跨快照稳定的选区模型（纯 JVM）。
 *
 * ## 绝对行寻址（为什么用 Long 行 id 而不是合并列表下标）
 *
 * 合并网格（scrollback+屏）的下标每次输出都会平移（内容从底部推进）；若选区
 * 存下标，拖选时每来一帧新输出选区就「漂走」。改用**单调行 id**：
 * `行id = snapshot.scrollbackBase - snapshot.scrollback.size + 合并下标`
 * （`scrollbackBase` 只增不减 —— T85 M-2 语义）。新快照反向解出下标：
 * `下标 = 行id - (scrollbackBase - scrollback.size)`；行被淘汰（id < 最低存活 id）
 * → 选区自动收缩/清除，绝不越界。
 *
 * ## 列语义
 *
 * 列是 **VT 列**（宽字符占 2 列）。拖选起点/终点由 `TerminalTextGrid.columnAt`
 * 给出；提取文本时按渲染 cell 步进（宽字符一次性带出，trail cell 已折叠进 lead）。
 * 列区间**左闭右开**（与旧渲染器 `SelRange` 一致）。
 *
 * ## 词扩展（Termux 双击选词）
 *
 * 词字符 = 字母/数字/下划线 + 路径常见符号 `-. / : ~ @ + = ? & % #`——
 * 双击可一次选中 `/sdcard/Download/file.txt` 整段路径（含 URL）。
 */
class TerminalSelectionModel {

    /** 选区端点（VT 列；col 左闭语义）。 */
    data class Anchor(val rowId: Long, val col: Int)

    private var anchor: Anchor? = null
    private var head: Anchor? = null

    /** 是否存在选区（两端都已落点）。 */
    val active: Boolean get() = anchor != null && head != null

    /** 起点仍在等待第二次落点（拖选刚开始的坍缩态）。 */
    val pending: Boolean get() = anchor != null && head == null

    /** 当前最低存活行 id（新快照滚动窗口的底；低于它的行已被淘汰）。 */
    var lowestLiveRowId: Long = 0L
        private set

    /** 开始一次选择（长按下压 / 双击词选的落点）。 */
    fun start(rowId: Long, col: Int) {
        anchor = Anchor(rowId, col.coerceAtLeast(0))
        head = null
    }

    /** 移动活动端（拖动）。 */
    fun extend(rowId: Long, col: Int) {
        if (anchor == null) return
        head = Anchor(rowId, col.coerceAtLeast(0))
    }

    /** 清空。 */
    fun clear() {
        anchor = null
        head = null
    }

    /** 新快照同步存活窗口（淘汰收缩）：任一端点被淘汰 → 整体清空（被选内容已
     *  不存在，保留只会高亮错行）。返回是否清空（需通知宿主）。 */
    fun onSnapshotScrolled(scrollbackBase: Long, scrollbackSize: Int): Boolean {
        lowestLiveRowId = scrollbackBase - scrollbackSize
        val a = anchor
        val h = head
        val evicted = (a != null && a.rowId < lowestLiveRowId) ||
            (h != null && h.rowId < lowestLiveRowId)
        if (evicted) {
            clear()
            return true
        }
        return false
    }

    /** 规范化（start ≤ end 按行/列字典序）；无选区 → null。 */
    fun normalized(): Pair<Anchor, Anchor>? {
        val a = anchor ?: return null
        val h = head ?: return null
        val start: Anchor
        val end: Anchor
        if (a.rowId < h.rowId || (a.rowId == h.rowId && a.col <= h.col)) {
            start = a; end = h
        } else {
            start = h; end = a
        }
        return start to end
    }

    /** 该 (rowId, col) 是否被选区覆盖（渲染高亮判定 —— 每可见 cell 一次，走快路径）。 */
    fun covers(rowId: Long, col: Int): Boolean {
        val n = normalized() ?: return false
        val (start, end) = n
        if (rowId < start.rowId || rowId > end.rowId) return false
        if (start.rowId == end.rowId) return col >= start.col && col < end.col
        if (rowId == start.rowId) return col >= start.col
        if (rowId == end.rowId) return col < end.col
        return true
    }

    /**
     * 词扩展（双击）：向两侧扩到非词字符为止。行文本来自调用方（渲染 run 的拼接
     * 或 cell 列表）。返回 (startCol, endCol) 左闭右开；不可扩 → col..col+1。
     */
    fun expandToWord(rowId: Long, col: Int, rowText: String): Pair<Int, Int> {
        if (rowText.isEmpty()) return col to (col + 1)
        val chars = rowText.map { it } // 按码点语义近似（cell 文本已含组合符）
        var start = col.coerceIn(0, chars.size - 1)
        if (start >= chars.size) return col to (col + 1)
        val pivotIsWord = isWordChar(chars[start])
        var s = start
        while (s > 0 && isWordChar(chars[s - 1]) == pivotIsWord) s--
        var e = start + 1
        while (e < chars.size && isWordChar(chars[e]) == pivotIsWord) e++
        // 双击空白 → 选整段连续空白（Termux 行为）
        return s to e
    }

    /** 行扩展（三击/全选行）。 */
    fun expandToLine(rowId: Long, rowLength: Int): Pair<Int, Int> = 0 to rowLength.coerceAtLeast(0)

    /**
     * 提取选中文本（多行以 `\n` 连接；行尾空白修剪 —— 复制 `ls` 彩色输出不会带
     * 一串尾随空格）。宽字符按 cell 原样带出；HIDDEN cell 以空格参与。
     *
     * @param rowProvider 行id → 该行渲染 cell 列表（越界/null = 行已淘汰，跳过）
     * @return null = 无选区/全淘汰
     */
    fun selectedText(rowProvider: (Long) -> List<RenderCell>?): String? {
        val n = normalized() ?: return null
        val (start, end) = n
        if (start.rowId > end.rowId) return null
        val sb = StringBuilder()
        var any = false
        var row = start.rowId
        while (row <= end.rowId) {
            val cells = rowProvider(row)
            if (cells != null) {
                // 分隔符只落在「两个存活行」之间 —— 头部/中段被淘汰的行不产生
                // 空行（复制 `x` 得 "x" 而非 "\n\n\nx"）
                if (any) sb.append('\n')
                any = true
                val from = if (row == start.rowId) start.col else 0
                val to = if (row == end.rowId) end.col else cells.size
                appendCells(sb, cells, from, to)
            }
            row++
            if (row - start.rowId > MAX_ROWS_EXTRACT) break // 防御：异常输入不无限循环
        }
        if (!any) return null
        return sb.toString().trimEnd(' ', '\u00A0')
    }

    /** 列区间内 cell 文本拼接（VT 列语义：宽字符 2 列，起止可落在宽字符中间 ——
     * 落中间时整字符带出）。 */
    private fun appendCells(sb: StringBuilder, cells: List<RenderCell>, fromCol: Int, toCol: Int) {
        if (toCol <= fromCol) return
        var col = 0
        var i = 0
        while (i < cells.size) {
            val cell = cells[i]
            val span = if (cell.flags and RenderCell.FLAG_WIDE != 0) 2 else 1
            val cellStart = col
            val cellEnd = col + span
            // 命中判定：区间 [fromCol, toCol) 与该 cell 列区间有交集，或该 cell
            // 横跨区间边界（从字符中间起选也带出整个字符 —— Termux 同款）
            if (cellEnd > fromCol && cellStart < toCol) {
                sb.append(if (cell.flags and RenderCell.FLAG_HIDDEN != 0) ' ' else cell.text)
            }
            col = cellEnd
            i++
        }
    }

    /** 词字符集（字母数字 + 路径/URL 常见符号）。 */
    fun isWordChar(ch: Char): Boolean =
        ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.' || ch == '/' ||
            ch == ':' || ch == '~' || ch == '@' || ch == '+' || ch == '=' ||
            ch == '?' || ch == '&' || ch == '%' || ch == '#'

    companion object {
        /** 提取上限（防御 malformed 输入 —— 选区行 id 异常时兜底）。 */
        private const val MAX_ROWS_EXTRACT = 20_000
    }
}
