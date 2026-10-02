package com.apex.agent.ui.screen.terminal

import com.apex.agent.terminalview.TerminalViewSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #233（契约锁）：字号合法区间 app ↔ view 库两层一致。
 *
 * 旧病灶：设置表 8..32（UI 层）vs VM 钳制 8..24（引擎层）静默分叉 —— 用户
 * 调到 25..32 后一次捏合即 8 级跳变。T89 已统一为 8..24；本测试锁死
 * 两层常量不再漂移（库默认 = app 注入值 = 同一区间）。
 */
class TerminalFontSizeContractTest {

    @Test
    fun `app 常量与 view 库默认区间一致`() {
        val min = TerminalViewModel.TerminalSettings.MIN_FONT_SIZE
        val max = TerminalViewModel.TerminalSettings.MAX_FONT_SIZE
        // 宿主（TerminalViewHost）显式注入 app 常量；库默认（未注入路径）
        // 必须与之一致 —— 否则忘注入的宿主回退到漂移区间。
        val lib = TerminalViewSettings()
        assertEquals("下限一致", min.toFloat(), lib.minFontSp, 0.01f)
        assertEquals("上限一致", max.toFloat(), lib.maxFontSp, 0.01f)
    }

    @Test
    fun `库层钳制把越界值收回 app 区间`() {
        val lib = TerminalViewSettings()
        val min = TerminalViewModel.TerminalSettings.MIN_FONT_SIZE
        val max = TerminalViewModel.TerminalSettings.MAX_FONT_SIZE
        // 25..32 旧病灶区间与极小值都必落 [min, max]
        val clampedHigh = lib.clampFontSize(32f)
        val clampedLow = lib.clampFontSize(4f)
        assertTrue("32 钳回 ≤ max（旧病灶）", clampedHigh <= max)
        assertTrue("钳回 ≥ min", clampedHigh >= min)
        assertTrue("4 钳回 ≥ min", clampedLow >= min)
        assertTrue("钳回 ≤ max", clampedLow <= max)
    }
}
