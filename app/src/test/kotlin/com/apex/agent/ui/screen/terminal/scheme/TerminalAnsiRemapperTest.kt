package com.apex.agent.ui.screen.terminal.scheme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T87：ANSI 重映射器单测 —— 「引擎标准板 → scheme」翻译的逐槽位正确性。
 *
 * 引擎事实（TerminalColor.toRgb / TerminalCore.colorArgb / NativeVtCore 同板）：
 *   - Default（fg）→ 0L
 *   - Indexed(0..15) → 0xFF000000 | BASIC_16[i]
 *   - RGB → 0xFF000000 | rgb
 */
class TerminalAnsiRemapperTest {

    private val scheme = TerminalColorSchemeDefs.TERMUX   // black bg / white fg / green cursor

    // ── 引擎标准板（与 TerminalColor.BASIC_16 一致；本地重声明避免模块依赖）──
    private val engineBasic = longArrayOf(
        0x000000L, 0x800000L, 0x008000L, 0x808000L,
        0x000080L, 0x800080L, 0x008080L, 0xC0C0C0L,
        0x808080L, 0xFF0000L, 0x00FF00L, 0xFFFF00L,
        0x0000FFL, 0xFF00FFL, 0x00FFFFL, 0xFFFFFFL
    )

    private fun engineFg(index: Int): Long = 0xFF000000L or engineBasic[index]

    @Test
    fun `default foreground maps to scheme foreground`() {
        assertEquals(scheme.foreground, TerminalAnsiRemapper.mapForeground(0L, bold = false, boldAsBright = true, scheme = scheme))
    }

    @Test
    fun `all 16 indexed colors map to their scheme slots`() {
        for (i in 0 until 16) {
            val mapped = TerminalAnsiRemapper.mapForeground(engineFg(i), bold = false, boldAsBright = false, scheme = scheme)
            assertEquals("slot $i", scheme.ansi[i], mapped)
        }
    }

    @Test
    fun `bold plus base color maps to bright slot when boldAsBright enabled`() {
        // engine blue (4) + bold → scheme brightBlue (12)
        assertEquals(scheme.ansi[12], TerminalAnsiRemapper.mapForeground(engineFg(4), bold = true, boldAsBright = true, scheme = scheme))
        // 索引 0（black）+ bold → brightBlack (8)
        assertEquals(scheme.ansi[8], TerminalAnsiRemapper.mapForeground(engineFg(0), bold = true, boldAsBright = true, scheme = scheme))
    }

    @Test
    fun `bold plus base color stays base when boldAsBright disabled`() {
        assertEquals(scheme.ansi[4], TerminalAnsiRemapper.mapForeground(engineFg(4), bold = true, boldAsBright = false, scheme = scheme))
    }

    @Test
    fun `bold plus bright color never remaps further`() {
        // bright 索引 8..15 + bold → 原槽位（不越界加 8）
        for (i in 8 until 16) {
            assertEquals("slot $i", scheme.ansi[i], TerminalAnsiRemapper.mapForeground(engineFg(i), bold = true, boldAsBright = true, scheme = scheme))
        }
    }

    @Test
    fun `truecolor rgb passes through untouched`() {
        val truecolor = 0xFF123456L
        assertEquals(truecolor, TerminalAnsiRemapper.mapForeground(truecolor, bold = true, boldAsBright = true, scheme = scheme))
    }

    @Test
    fun `256-color cube passes through untouched`() {
        // 引擎对 16-231 立方色产出 0xFF|computed-rgb —— 不在标准板内 → 透传
        val cubeColor = 0xFF003300L   // (0,51,0) 类深绿
        assertEquals(cubeColor, TerminalAnsiRemapper.mapForeground(cubeColor, bold = false, boldAsBright = true, scheme = scheme))
    }

    @Test
    fun `background default stays zero (transparent semantics)`() {
        assertEquals(0L, TerminalAnsiRemapper.mapBackground(0L, scheme = scheme))
    }

    @Test
    fun `background indexed maps without bold promotion`() {
        assertEquals(scheme.ansi[4], TerminalAnsiRemapper.mapBackground(engineFg(4), scheme = scheme))
    }

    @Test
    fun `inverse with double defaults swaps scheme fg and bg`() {
        val (fg, bg) = TerminalAnsiRemapper.mapInverse(0L, 0L, scheme)
        assertEquals(scheme.background, fg)
        assertEquals(scheme.foreground, bg)
    }

    @Test
    fun `inverse with explicit colors maps both sides`() {
        val (fg, bg) = TerminalAnsiRemapper.mapInverse(engineFg(1), engineFg(2), scheme)
        assertEquals(scheme.ansi[2], fg)   // 反显前景 = 原 bg（绿）
        assertEquals(scheme.ansi[1], bg)   // 反显背景 = 原 fg（红）
    }

    @Test
    fun `dimColor reduces alpha but keeps rgb`() {
        val color = 0xFF102030L
        val dimmed = TerminalAnsiRemapper.dimColor(color)
        assertEquals(color and 0x00FFFFFFL, dimmed and 0x00FFFFFFL)
        assertTrue("alpha 应被降低", (dimmed ushr 24) < (color ushr 24))
        assertTrue("alpha 不应低于 10% 下限", (dimmed ushr 24) >= 0x18L)
    }

    @Test
    fun `schemes differ from each other on at least one slot`() {
        val a = TerminalColorSchemeDefs.DRACULA
        val b = TerminalColorSchemeDefs.NORD
        assertNotEquals(a.ansi, b.ansi)
    }
}

/**
 * T87：scheme 注册表与 20 套方案的结构不变式。
 */
class TerminalColorSchemeRegistryTest {

    @Test
    fun `all schemes have exactly 16 ansi colors`() {
        for (s in TerminalColorSchemeRegistry.all()) {
            assertEquals("scheme ${s.id}", 16, s.ansi.size)
        }
    }

    @Test
    fun `registry has at least 20 schemes`() {
        assertTrue(TerminalColorSchemeRegistry.count >= 20)
    }

    @Test
    fun `ids are unique`() {
        val ids = TerminalColorSchemeRegistry.all().map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `fallback id resolves`() {
        assertEquals(
            TerminalColorScheme.FALLBACK_ID,
            TerminalColorSchemeRegistry.byId(TerminalColorScheme.FALLBACK_ID).id
        )
    }

    @Test
    fun `unknown id falls back to default`() {
        assertEquals(
            TerminalColorSchemeRegistry.DEFAULT.id,
            TerminalColorSchemeRegistry.byId("no-such-scheme").id
        )
    }

    @Test
    fun `byIdOrNull returns null for unknown`() {
        assertNull(TerminalColorSchemeRegistry.byIdOrNull("no-such-scheme"))
    }

    @Test
    fun `isValidId accepts known and rejects unknown`() {
        assertTrue(TerminalColorSchemeRegistry.isValidId("dracula"))
        assertTrue(!TerminalColorSchemeRegistry.isValidId("dracula2"))
    }

    @Test
    fun `light scheme exists and is marked`() {
        val light = TerminalColorSchemeRegistry.byId("solarized-light")
        assertTrue(!light.dark)
    }

    @Test
    fun `previewColors returns first 8`() {
        val s = TerminalColorSchemeDefs.MONOKAI
        assertEquals(s.ansi.subList(0, 8), s.previewColors)
    }

    @Test
    fun `displayName switches by locale`() {
        val s = TerminalColorSchemeDefs.TERMUX
        assertEquals(s.name, s.displayName(zh = false))
        assertEquals(s.nameZh, s.displayName(zh = true))
    }
}
