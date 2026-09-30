package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.RenderCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T88（2-a）：行折叠器单测 —— 同风格合并键（fg/bg/flags/link 全等）、行尾默认
 * 空白修剪、HIDDEN 空格替换、宽字符 VT 列语义（colStart/colSpan）。
 *
 * 颜色数组由调用方预解析（渲染器路径）—— 折叠器只消费；测试里用 0xFFFFFFFF /
 * 0xFF000000 两个值模拟「已解析」的前景/背景对。
 */
class TerminalRowRunTest {

    private fun cell(
        text: String,
        fg: Long = 0L,
        bg: Long = 0L,
        flags: Int = 0,
        link: Int = 0
    ) = RenderCell(text, fg, bg, flags, link)

    private fun resolved(cells: List<RenderCell>, fg: Int, bg: Int): Pair<IntArray, IntArray> =
        IntArray(cells.size) { fg } to IntArray(cells.size) { bg }

    private fun collapseDefault(cells: List<RenderCell>, trim: Boolean = true): List<TerminalRowRun.CellRun> {
        val (fg, bg) = resolved(cells, 0xFFFFFFFF.toInt(), 0xFF0E1411.toInt())
        return TerminalRowRun.collapse(cells, fg, bg, trim)
    }

    /** 未解析颜色路径（fg/bg 全 0 —— 行尾空白修剪契约按「默认色 0」判定）。 */
    private fun collapseUnresolved(cells: List<RenderCell>, trim: Boolean = true): List<TerminalRowRun.CellRun> =
        TerminalRowRun.collapse(cells, IntArray(cells.size), IntArray(cells.size), trim)

    // ─── 合并键 ───

    @Test
    fun `equal style neighbors collapse into single run`() {
        val cells = listOf(cell("a"), cell("b"), cell("c"))
        val runs = collapseDefault(cells)
        assertEquals(1, runs.size)
        assertEquals("abc", runs[0].text)
        assertEquals(0, runs[0].colStart)
        assertEquals(3, runs[0].colSpan)
    }

    @Test
    fun `fg change breaks run`() {
        val cells = listOf(cell("a"), cell("b", fg = 0xFF000000L or 0x00FF00))
        val (fg, bg) = resolved(cells, 0xFFFFFFFF.toInt(), 0xFF0E1411.toInt())
        val runs = TerminalRowRun.collapse(cells, fg, bg)
        assertEquals(2, runs.size)
    }

    @Test
    fun `bg change breaks run`() {
        val cells = listOf(cell("a"), cell("b", bg = 0xFF000000L or 0x0000FF))
        val (fg, bg) = resolved(cells, 0xFFFFFFFF.toInt(), 0xFF0E1411.toInt())
        val runs = TerminalRowRun.collapse(cells, fg, bg)
        assertEquals(2, runs.size)
    }

    @Test
    fun `flags change breaks run`() {
        val cells = listOf(cell("a"), cell("b", flags = RenderCell.FLAG_BOLD))
        val runs = collapseDefault(cells)
        assertEquals(2, runs.size)
    }

    @Test
    fun `link change breaks run`() {
        val cells = listOf(cell("a", link = 1), cell("b", link = 2))
        val runs = collapseDefault(cells)
        assertEquals(2, runs.size)
        assertEquals(1, runs[0].link)
        assertEquals(2, runs[1].link)
    }

    @Test
    fun `resolved color difference breaks run even when packed longs equal`() {
        // 防御路径：宿主预解析数组与 cell Long 解耦（如 monochrome 与否切换的
        // 中间态）—— 折叠器必须尊重解析结果差异
        val cells = listOf(cell("a"), cell("b"))
        val fg = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        val bg = intArrayOf(0xFF0E1411.toInt(), 0xFF0E1411.toInt())
        val runs = TerminalRowRun.collapse(cells, fg, bg)
        assertEquals(2, runs.size)
        assertEquals(0xFFFFFFFF.toInt(), runs[0].fgArgb)
        assertEquals(0xFF000000.toInt(), runs[1].fgArgb)
    }

    // ─── 行尾修剪 ───

    @Test
    fun `trailing default blanks are trimmed`() {
        // 契约：颜色对为 0（未解析/默认）的行尾空格 = 纯背景 → 剪
        val cells = listOf(cell("h", fg = 0xFF000000L or 0xFFFFFF), cell(" "), cell(" "), cell(" "))
        val runs = collapseUnresolved(cells)
        assertEquals(1, runs.size)
        assertEquals("h", runs[0].text)
        assertEquals(1, runs[0].colSpan)
    }

    @Test
    fun `styled trailing space is not trimmed`() {
        // 属性非零的行尾空格是内容（如反显空格 = 色块）—— 不剪
        val cells = listOf(cell("a"), cell(" ", flags = RenderCell.FLAG_INVERSE))
        val runs = collapseUnresolved(cells)
        assertEquals(2, runs.size) // 风格断点：a 与反显空格分属两段
        assertEquals("a", runs[0].text)
        assertEquals(" ", runs[1].text)
        assertEquals(RenderCell.FLAG_INVERSE, runs[1].flags)
    }

    @Test
    fun `trim disabled keeps trailing blanks`() {
        val cells = listOf(cell("a"), cell(" "), cell(" "))
        val runs = collapseUnresolved(cells, trim = false)
        assertEquals("a  ", runs[0].text)
    }

    @Test
    fun `all blank row collapses to empty list`() {
        val cells = listOf(cell(" "), cell(" "), cell(" "))
        assertTrue(collapseUnresolved(cells).isEmpty())
    }

    @Test
    fun `resolved colors never satisfy blank trim contract`() {
        // 渲染器路径（颜色已解析恒非 0）：修剪契约不触发 —— 行尾空白依赖引擎
        // renderRow 已剪 + 解析色空格可能是语义底色（反显块），保守保留
        val cells = listOf(cell("h", fg = 0xFF000000L or 0xFFFFFF), cell(" "), cell(" "))
        val runs = collapseDefault(cells)
        assertEquals(2, runs.size)
        assertEquals("h", runs[0].text)
        assertEquals("  ", runs[1].text)
    }

    @Test
    fun `empty row collapses to empty list`() {
        assertTrue(collapseDefault(emptyList()).isEmpty())
    }

    // ─── HIDDEN / 宽字符 ───

    @Test
    fun `hidden cells render as spaces keeping style run`() {
        val cells = listOf(
            cell("a", flags = RenderCell.FLAG_HIDDEN),
            cell("b", flags = RenderCell.FLAG_HIDDEN)
        )
        val runs = collapseDefault(cells)
        assertEquals(1, runs.size)
        assertEquals("  ", runs[0].text) // 字形隐藏、属性保留（合并键不变）
    }

    @Test
    fun `wide char spans two columns and shifts following run`() {
        val cells = listOf(
            cell("中", flags = RenderCell.FLAG_WIDE),
            cell("a")
        )
        val runs = collapseDefault(cells)
        // 宽字符与窄字符 flags 不同 → 两个 run；几何：0..2 / 2..3
        assertEquals(2, runs.size)
        assertEquals(0, runs[0].colStart)
        assertEquals(2, runs[0].colSpan)
        assertEquals("中", runs[0].text)
        assertEquals(2, runs[1].colStart)
        assertEquals(1, runs[1].colSpan)
        assertEquals("a", runs[1].text)
    }

    @Test
    fun `multiple wide chars accumulate colSpan`() {
        val cells = List(3) { cell("漢", flags = RenderCell.FLAG_WIDE) }
        val runs = collapseDefault(cells)
        assertEquals(1, runs.size)
        assertEquals(6, runs[0].colSpan)
        assertEquals("漢漢漢", runs[0].text)
    }

    @Test
    fun `cellSpan reports wide as two`() {
        assertEquals(2, TerminalRowRun.cellSpan(cell("漢", flags = RenderCell.FLAG_WIDE)))
        assertEquals(1, TerminalRowRun.cellSpan(cell("x")))
    }

    // ─── 便捷路径 ───

    @Test
    fun `collapseTextOnly keeps all cells with default colors`() {
        val cells = listOf(cell("a"), cell("b"), cell(" "))
        val runs = TerminalRowRun.collapseTextOnly(cells)
        assertEquals(1, runs.size) // 文本提取路径不剪尾、全默认风格
        assertEquals("ab ", runs[0].text)
        assertEquals(0, runs[0].fgArgb) // 未解析 = 0 语义
        assertFalse(runs[0].hasBackground)
    }

    @Test
    fun `runsToText concatenates run text`() {
        val cells = listOf(cell("a", flags = RenderCell.FLAG_BOLD), cell("b", flags = RenderCell.FLAG_BOLD), cell("c"))
        val runs = TerminalRowRun.collapseTextOnly(cells)
        assertEquals(2, runs.size) // BOLD 段 + 普通段
        assertEquals("abc", TerminalRowRun.runsToText(runs))
        assertEquals("", TerminalRowRun.runsToText(emptyList()))
    }

    @Test
    fun `run style helpers expose paint derivation flags`() {
        val plain = TerminalRowRun.collapseTextOnly(listOf(cell("x")))[0]
        assertFalse(plain.hasTextStyle)
        val bold = TerminalRowRun.collapseTextOnly(listOf(cell("x", flags = RenderCell.FLAG_BOLD)))[0]
        assertTrue(bold.hasTextStyle)
        val underlined = TerminalRowRun.collapseTextOnly(listOf(cell("x", flags = RenderCell.FLAG_UNDERLINE)))[0]
        assertTrue(underlined.hasTextStyle)
    }
}
