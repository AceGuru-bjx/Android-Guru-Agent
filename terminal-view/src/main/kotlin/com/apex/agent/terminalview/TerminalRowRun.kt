package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.RenderCell

/**
 * T88（2-a）：行 → 样式 run 折叠器（Canvas 绘制的最小单元）。
 *
 * 为什么折叠：逐 cell `drawText` 在 50 行 × 200 列时是每帧 1 万次调用（Canvas
 * 状态切换放大开销）—— 合并同风格连续段后降到「每行几段」，这是 Termux
 * TerminalRenderer 的绘制密度。合并键 = (fgArgb, bgArgb, flags, link)：
 * 与旧 Compose 渲染器 `sameStyle` **完全一致**（保证行为连续），文本内容不参与
 * （文本只拼进 StringBuilder）。
 *
 * 额外职责：
 *  - FLAG_HIDDEN → 等宽空格（字符属性保留、字形隐藏 —— xterm SGR 8 语义）；
 *  - 行尾**纯默认空白**修剪（引擎 `renderRow` 已做，这里防御性再剪 —— 宿主可能
 *    注入未修剪行；滚动进来的空白尾巴不再产生 draw 调用）；
 *  - 每个 run 记录 [colStart]/[colSpan]（VT 列语义，宽字符 2 列）—— 渲染器据此
 *    做 textScaleX 宽度校正，列对齐不漂移。
 *
 * 颜色已在折叠前由调用方解析（palette.resolveCell）—— 本类不触碰调色板。
 */
object TerminalRowRun {

    /**
     * 一个可绘制 run：文本 + 已解析颜色 + 属性位 + 链接 id + 列几何。
     *
     * @param text       该段文本（HIDDEN 已替换为空格）
     * @param fgArgb     前景（已含 inverse/dim/bold-as-bright 解析）
     * @param bgArgb     背景（已解析；== 透明语义由调用方判断是否跳过背景绘制）
     * @param flags      原始 cell flags（BOLD/ITALIC/UNDERLINE/STRIKE/BLINK/LINK…）
     * @param link       OSC 8 链接 id（0 = 无）
     * @param colStart   起始 VT 列
     * @param colSpan    占用列数（宽字符 2 列/字符）
     */
    data class CellRun(
        val text: String,
        val fgArgb: Int,
        val bgArgb: Int,
        val flags: Int,
        val link: Int,
        val colStart: Int,
        val colSpan: Int
    ) {
        /** 是否携带已解析背景（0 = 调用方未解析（文本路径）；渲染器另行与
         *  palette.background 比较来决定是否跳过底色绘制 —— 省一次 draw）。 */
        val hasBackground: Boolean get() = bgArgb != 0

        /** 是否携带非默认属性（决定 Paint 派生成本）。 */
        val hasTextStyle: Boolean
            get() = flags and (RenderCell.FLAG_BOLD or RenderCell.FLAG_ITALIC or
                RenderCell.FLAG_UNDERLINE or RenderCell.FLAG_STRIKE) != 0
    }

    /** 折叠时忽略的属性位（BLINK 只影响可见性开关，不参与合并键差异判断见下 ——
     * 实际上 BLINK 参与合并键（flags 整体比较，与旧 sameStyle 一致），此掩码仅
     * 供调用方做「无样式快速路径」判断）。 */
    val STYLE_MASK: Int = RenderCell.FLAG_BOLD or RenderCell.FLAG_ITALIC or
        RenderCell.FLAG_UNDERLINE or RenderCell.FLAG_STRIKE or RenderCell.FLAG_BLINK

    /**
     * 折叠一行。调用方传入**已解析**的每-cell 颜色对（fgArgb/bgArgb，与 cells
     * 等长；典型来自 [TerminalPalette.resolveCell] 批量循环）。
     *
     * @param trimTrailingBlanks 行尾默认空白修剪（渲染路径 true；测试/文本提取 false）
     */
    fun collapse(
        cells: List<RenderCell>,
        fgColors: IntArray,
        bgColors: IntArray,
        trimTrailingBlanks: Boolean = true
    ): List<CellRun> {
        if (cells.isEmpty()) return emptyList()
        var last = cells.size - 1
        if (trimTrailingBlanks) {
            while (last >= 0 && isDefaultBlank(cells[last], fgColors.getOrNull(last), bgColors.getOrNull(last))) {
                last--
            }
            if (last < 0) return emptyList()
        }
        val runs = ArrayList<CellRun>(8)
        var i = 0
        var col = 0
        while (i <= last) {
            val first = cells[i]
            val firstFg = fgColors.getOrElse(i) { 0 }
            val firstBg = bgColors.getOrElse(i) { 0 }
            var j = i + 1
            var span = cellSpan(first)
            while (j <= last) {
                val c = cells[j]
                if (c.fg != first.fg || c.bg != first.bg || c.flags != first.flags || c.link != first.link) break
                if (fgColors.getOrElse(j) { 0 } != firstFg || bgColors.getOrElse(j) { 0 } != firstBg) break
                span += cellSpan(c)
                j++
            }
            val text = StringBuilder(j - i)
            for (k in i until j) {
                val c = cells[k]
                if (c.flags and RenderCell.FLAG_HIDDEN != 0) text.append(' ') else text.append(c.text)
            }
            runs.add(
                CellRun(
                    text = text.toString(),
                    fgArgb = firstFg,
                    bgArgb = firstBg,
                    flags = first.flags,
                    link = first.link,
                    colStart = col,
                    colSpan = span
                )
            )
            col += span
            i = j
        }
        return runs
    }

    /** 一个 cell 的 VT 列跨度（宽字符 2）。 */
    fun cellSpan(cell: RenderCell): Int =
        if (cell.flags and RenderCell.FLAG_WIDE != 0) 2 else 1

    /** 行尾默认空白判定：空格文本 + 无任何属性/链接 + 颜色对为 0（未解析/默认）。 */
    private fun isDefaultBlank(cell: RenderCell, fg: Int?, bg: Int?): Boolean {
        if (cell.text != " ") return false
        if (cell.flags != 0 || cell.link != 0) return false
        return fg == 0 || fg == null || bg == 0 || bg == null
    }

    /**
     * 便捷重载：无预解析颜色（所有 cell 视为默认色 —— 仅文本/属性语义，供
     * 文本提取路径与测试使用）。
     */
    fun collapseTextOnly(cells: List<RenderCell>): List<CellRun> =
        collapse(cells, IntArray(cells.size), IntArray(cells.size), trimTrailingBlanks = false)

    /**
     * run 列表 → 行纯文本（选择/无障碍/URL 提取用；HIDDEN 已是空格）。
     */
    fun runsToText(runs: List<CellRun>): String {
        if (runs.isEmpty()) return ""
        val sb = StringBuilder(runs.sumOf { it.text.length })
        for (r in runs) sb.append(r.text)
        return sb.toString()
    }
}
