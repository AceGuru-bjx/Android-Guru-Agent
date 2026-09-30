package com.apex.agent.ui.screen.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.agent.R
import com.apex.agent.platform.terminal.io.TerminalKey

/**
 * 触屏辅助键行（T87 从 TerminalRenderer.kt 拆出 —— 渲染器行数预算治理；
 * 功能与视觉逐字节不变，可见性 private → internal 供同包引用）。
 *
 * 设计对齐 Termux extra-keys（T85 重做 / T86 增强）：
 *  - **主簇**（滚动区前端，一眼可达）：拉起键盘 / 退格 / ESC / TAB / CTRL·SHIFT·ALT
 *    锁存 / 方向键 —— 高频键排在最前；
 *  - **扩展簇**（继续横向滚动）：常用 shell 符号（| ~ - / \ $ & 等）+ 控制码
 *    （^C ^D ^Z ^L ^U）+ HOME/END/PgUp/PgDn + F1-F12 + 粘贴；
 *  - 触控目标 36dp 高（Material 无障碍阈值）；锁存键高亮为 mint 实底深字，
 *    SHIFT/ALT 一次性（随下一个特殊键发出即释放）。
 */
@Composable
internal fun KeyToolbar(
    ctrlActive: Boolean,
    onCtrlToggle: () -> Unit,
    shiftActive: Boolean,
    onShiftToggle: () -> Unit,
    altActive: Boolean,
    onAltToggle: () -> Unit,
    onText: (String) -> Unit,
    onKey: (TerminalKey) -> Unit,
    onControl: (Char) -> Unit,
    onShowKeyboard: () -> Unit,
    onPaste: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(KeybarChrome.bg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 5.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── 主簇 ──
        // 显式拉起输入法：触屏上"点一下没反应"的兜底入口
        ToolbarKey("⌨", emphasized = true) { onShowKeyboard() }
        // 退格：隐藏 IME 桥的缓冲恒为空，输入法拿不到"可删除的 surrounding text"，
        // 触屏上必须给一个确定可用的删除键（否则打错字只能靠 Ctrl+U 整行重来）。
        ToolbarKey("⌫", emphasized = true) { onKey(TerminalKey.BACKSPACE) }
        ToolbarKey("ESC") { onKey(TerminalKey.ESC) }
        ToolbarKey("TAB") { onKey(TerminalKey.TAB) }
        ToolbarKey(
            label = "CTRL",
            highlighted = ctrlActive,
            onClick = onCtrlToggle
        )
        // T86：SHIFT/ALT 锁存（一次性 —— 与下一个方向/导航/F 键组合后自动释放）。
        // SHIFT+方向 = vim 可视选择 / readline 选区；ALT+B/F = 词跳（发送时 meta 化）。
        ToolbarKey(
            label = "SHIFT",
            highlighted = shiftActive,
            onClick = onShiftToggle
        )
        ToolbarKey(
            label = "ALT",
            highlighted = altActive,
            onClick = onAltToggle
        )
        ToolbarKey("↑") { onKey(TerminalKey.ARROW_UP) }
        ToolbarKey("↓") { onKey(TerminalKey.ARROW_DOWN) }
        ToolbarKey("←") { onKey(TerminalKey.ARROW_LEFT) }
        ToolbarKey("→") { onKey(TerminalKey.ARROW_RIGHT) }

        // ── 扩展簇：shell 符号（免切输入法的符号面板）──
        ToolbarKey("|") { onText("|") }
        ToolbarKey("~") { onText("~") }
        ToolbarKey("-") { onText("-") }
        ToolbarKey("/") { onText("/") }
        ToolbarKey("\\") { onText("\\") }
        ToolbarKey("$") { onText("$") }
        ToolbarKey("&") { onText("&") }
        ToolbarKey(";") { onText(";") }
        ToolbarKey("<") { onText("<") }
        ToolbarKey(">") { onText(">") }
        ToolbarKey("*") { onText("*") }
        ToolbarKey("=") { onText("=") }

        // ── 扩展簇：控制码 / 导航 / F 键（htop 帮助、vim 命令模式、mc 菜单）──
        ToolbarKey("^C") { onControl('c') }
        ToolbarKey("^D") { onControl('d') }
        ToolbarKey("^Z") { onControl('z') }
        ToolbarKey("^L") { onControl('l') }
        ToolbarKey("^U") { onControl('u') }   // 清空当前行（readline 惯例）
        ToolbarKey("HOME") { onKey(TerminalKey.HOME) }
        ToolbarKey("END") { onKey(TerminalKey.END) }
        ToolbarKey("PGUP") { onKey(TerminalKey.PAGE_UP) }
        ToolbarKey("PGDN") { onKey(TerminalKey.PAGE_DOWN) }
        ToolbarKey("F1") { onKey(TerminalKey.F1) }
        ToolbarKey("F2") { onKey(TerminalKey.F2) }
        ToolbarKey("F3") { onKey(TerminalKey.F3) }
        ToolbarKey("F4") { onKey(TerminalKey.F4) }
        ToolbarKey("F5") { onKey(TerminalKey.F5) }
        ToolbarKey("F6") { onKey(TerminalKey.F6) }
        ToolbarKey("F7") { onKey(TerminalKey.F7) }
        ToolbarKey("F8") { onKey(TerminalKey.F8) }
        ToolbarKey("F9") { onKey(TerminalKey.F9) }
        ToolbarKey("F10") { onKey(TerminalKey.F10) }
        ToolbarKey("F11") { onKey(TerminalKey.F11) }
        ToolbarKey("F12") { onKey(TerminalKey.F12) }
        ToolbarKey(stringResource(R.string.term_paste)) { onPaste() }
    }
}

@Composable
private fun ToolbarKey(
    label: String,
    highlighted: Boolean = false,
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    highlighted -> KeybarChrome.keyHi
                    emphasized -> Color(0xFF223729)
                    else -> KeybarChrome.key
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = if (highlighted) Color(0xFF06120D) else KeybarChrome.keyText
        )
    }
}

/** 键栏 chrome 调色（终端内容色由 TerminalColorScheme 提供；键栏自身恒深色）。 */
internal object KeybarChrome {
    val bg = Color(0xFF111815)
    val key = Color(0xFF1A2420)
    val keyHi = Color(0xFF4EE9B0)
    val keyText = Color(0xFFE8F2ED)
}
