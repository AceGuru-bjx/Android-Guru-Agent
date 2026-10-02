package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.RenderCell

/**
 * T92：词选几何 —— 列 ↔ 字符索引双向换算 + 词边界（纯函数集）。
 *
 * 从 TerminalView 抽出（该文件触及 SRP 行预算上限；本组逻辑零视图状态）：
 *  - [charIndexOfCol] VT 列 → 字符索引（宽字符占 2 列 1 词元）
 *  - [colOfCharIndex] 字符索引 → VT 列（逆映射）
 *  - [textAt] 行文本拼接
 *  - [wordSpanAt] 命中格的词选边界（VT 列区间）—— 词字符分类由调用方注入
 *    （[TerminalSelectionModel.expandToWord]，保持单一分类真源）
 */
internal object TerminalWordGeometry {

    /** 行文本（cell 文本顺序拼接 —— 组合符已内联于 cell）。 */
    fun textAt(cells: List<RenderCell>): String = cells.joinToString("") { it.text }

    /** VT 列 → 字符索引（宽字符：跨 2 列共享同一词元起点）。 */
    fun charIndexOfCol(cells: List<RenderCell>, col: Int): Int {
        var colAcc = 0
        var charIdx = 0
        var i = 0
        while (i < cells.size && colAcc < col) {
            val span = if (cells[i].flags and RenderCell.FLAG_WIDE != 0) 2 else 1
            colAcc += span
            charIdx += cells[i].text.length
            i++
        }
        return charIdx
    }

    /** 字符索引 → VT 列（[charIndexOfCol] 的逆映射）。 */
    fun colOfCharIndex(cells: List<RenderCell>, charIdx: Int): Int {
        var col = 0
        var acc = 0
        var i = 0
        while (i < cells.size && acc < charIdx) {
            col += if (cells[i].flags and RenderCell.FLAG_WIDE != 0) 2 else 1
            acc += cells[i].text.length
            i++
        }
        return col
    }

    /**
     * 命中格（row 内 VT 列）的词选边界 → VT 列区间 [fromCol, toCol]。
     *
     * T92：双击与长按共用（长按起选即整词 —— Termux 长按 = 双击选词 + 拖扩）。
     *
     * @param expand 词字符分类回调（charIdx, rowText) → (词首, 词尾) 字符索引
     * @return null = 空行/越界（不可词选）
     */
    fun wordSpanAt(
        cells: List<RenderCell>,
        col: Int,
        expand: (charIdx: Int, rowText: String) -> Pair<Int, Int>
    ): Pair<Int, Int>? {
        val text = textAt(cells)
        if (text.isEmpty()) return null
        val charIdx = charIndexOfCol(cells, col)
        if (charIdx >= text.length) return null
        val (ws, we) = expand(charIdx, text)
        return colOfCharIndex(cells, ws) to colOfCharIndex(cells, we)
    }
}
