package com.apex.agent.ui.screen.terminal.extrakeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T87：扩展键配置解析/序列化/增删 —— Termux extra-keys 等价物的不变式。
 */
class ExtraKeysConfigTest {

    @Test
    fun `parse null or blank falls back to null`() {
        assertNull(ExtraKeysConfig.parse(null))
        assertNull(ExtraKeysConfig.parse(""))
        assertNull(ExtraKeysConfig.parse("   "))
    }

    @Test
    fun `parse single row with all kinds`() {
        val stored = "apt修复=cmd:apt-fix;;py3=text:python3;;F1=key:F1;;^C=ctrl:c;;贴=paste"
        val layout = ExtraKeysConfig.parse(stored)!!
        assertEquals(1, layout.size)
        val keys = layout[0]
        assertEquals(5, keys.size)
        assertEquals(ExtraKeysConfig.MacroKind.CMD, keys[0].kind)
        assertEquals("apt-fix", keys[0].payload)
        assertEquals(ExtraKeysConfig.MacroKind.TEXT, keys[1].kind)
        assertEquals(ExtraKeysConfig.MacroKind.KEY, keys[2].kind)
        assertEquals("F1", keys[2].payload)
        assertEquals(ExtraKeysConfig.MacroKind.CTRL, keys[3].kind)
        assertEquals("c", keys[3].payload)
        assertEquals(ExtraKeysConfig.MacroKind.PASTE, keys[4].kind)
    }

    @Test
    fun `parse multiple rows separated by blank line`() {
        val stored = "a=cmd:ls;;b=cmd:pwd\n\nc=text:vim;;d=paste"
        val layout = ExtraKeysConfig.parse(stored)!!
        assertEquals(2, layout.size)
        assertEquals(2, layout[0].size)
        assertEquals(2, layout[1].size)
    }

    @Test
    fun `illegal lines are skipped without killing the rest`() {
        val stored = "good=cmd:ls;;bad-no-equals;;also=cmd:echo ok"
        val layout = ExtraKeysConfig.parse(stored)!!
        // 两个合法键都在（bad 键被丢）
        assertTrue(layout.flatten().any { it.payload == "ls" })
        assertTrue(layout.flatten().any { it.payload == "echo ok" })
    }

    @Test
    fun `newline-corrupted payloads are rejected`() {
        // 换行是行分隔符 —— 载荷里带换行 = 畸形输入 → 整键拒绝
        assertNull(ExtraKeysConfig.parseKey("c=text:vim\nd=paste"))
    }

    @Test
    fun `serialize then parse round trips`() {
        val original = listOf(
            listOf(
                ExtraKeysConfig.ExtraKey("apt修复", ExtraKeysConfig.MacroKind.CMD, "apt-fix"),
                ExtraKeysConfig.ExtraKey("贴", ExtraKeysConfig.MacroKind.PASTE, "")
            )
        )
        val ser = ExtraKeysConfig.serialize(original)!!
        val parsed = ExtraKeysConfig.parse(ser)!!
        assertEquals(original, parsed)
    }

    @Test
    fun `serialize rejects separator-corrupting payloads`() {
        val bad = listOf(listOf(ExtraKeysConfig.ExtraKey("x", ExtraKeysConfig.MacroKind.CMD, "a;;b")))
        assertNull(ExtraKeysConfig.serialize(bad))
    }

    @Test
    fun `appendKey appends to last row then wraps`() {
        var layout = listOf(listOf(ExtraKeysConfig.ExtraKey("a", ExtraKeysConfig.MacroKind.CMD, "a")))
        repeat(10) { i ->
            layout = ExtraKeysConfig.appendKey(
                layout, ExtraKeysConfig.ExtraKey("k$i", ExtraKeysConfig.MacroKind.TEXT, "v$i")
            )
        }
        assertEquals(2, layout.size)
        assertEquals(ExtraKeysConfig.MAX_KEYS_PER_ROW, layout[0].size)
    }

    @Test
    fun `appendKey saturates at max rows`() {
        var layout = listOf((0 until 10).map { ExtraKeysConfig.ExtraKey("a$it", ExtraKeysConfig.MacroKind.CMD, "x$it") })
        repeat(ExtraKeysConfig.MAX_ROWS * ExtraKeysConfig.MAX_KEYS_PER_ROW + 5) { i ->
            layout = ExtraKeysConfig.appendKey(layout, ExtraKeysConfig.ExtraKey("z$i", ExtraKeysConfig.MacroKind.CMD, "y"))
        }
        assertTrue(layout.size <= ExtraKeysConfig.MAX_ROWS)
        assertTrue(layout.flatten().size <= ExtraKeysConfig.MAX_ROWS * ExtraKeysConfig.MAX_KEYS_PER_ROW)
    }

    @Test
    fun `removeKey drops matching label and prunes empty rows`() {
        val layout = listOf(
            listOf(ExtraKeysConfig.ExtraKey("x", ExtraKeysConfig.MacroKind.CMD, "x")),
            listOf(ExtraKeysConfig.ExtraKey("y", ExtraKeysConfig.MacroKind.CMD, "y"))
        )
        val after = ExtraKeysConfig.removeKey(layout, "x")
        assertEquals(1, after.size)
        assertEquals("y", after[0][0].label)
    }

    @Test
    fun `default layout is non-empty and within budget`() {
        val flat = ExtraKeysConfig.DEFAULT_LAYOUT.flatten()
        assertTrue(flat.isNotEmpty())
        assertTrue(flat.size <= ExtraKeysConfig.MAX_ROWS * ExtraKeysConfig.MAX_KEYS_PER_ROW)
        assertTrue(ExtraKeysConfig.DEFAULT_LAYOUT.all { it.size <= ExtraKeysConfig.MAX_KEYS_PER_ROW })
    }

    @Test
    fun `labels longer than 8 chars are rejected`() {
        assertNull(ExtraKeysConfig.parseKey("verylonglabel=cmd:ls"))
    }
}
