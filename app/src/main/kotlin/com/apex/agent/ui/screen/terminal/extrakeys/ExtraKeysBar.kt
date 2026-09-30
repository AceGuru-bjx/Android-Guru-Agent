package com.apex.agent.ui.screen.terminal.extrakeys

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.agent.platform.terminal.io.TerminalKey

/**
 * T87：扩展键行渲染器（Termux extra-keys 等价物 —— 用户自定义宏）。
 *
 * 数据来自 [ExtraKeysConfig]（设置抽屉可增删；默认布局贴近本项目高频命令）。
 * 与内置 [com.apex.agent.ui.screen.terminal.KeyToolbar] 互补：内置行覆盖
 * 通用编辑/导航，本行覆盖「项目特定」命令（apt-fix / python3 / git status…）。
 */
@Composable
fun ExtraKeysBar(
    layout: List<List<ExtraKeysConfig.ExtraKey>>,
    onText: (String) -> Unit,
    onKey: (TerminalKey) -> Unit,
    onControl: (Char) -> Unit,
    onPaste: () -> Unit
) {
    if (layout.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KeybarChromeColors.bg),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (row in layout) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (key in row) {
                    ExtraKeyButton(
                        key = key,
                        onText = onText,
                        onKey = onKey,
                        onControl = onControl,
                        onPaste = onPaste
                    )
                }
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun ExtraKeyButton(
    key: ExtraKeysConfig.ExtraKey,
    onText: (String) -> Unit,
    onKey: (TerminalKey) -> Unit,
    onControl: (Char) -> Unit,
    onPaste: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(KeybarChromeColors.keyAccent)
            .clickable {
                when (key.kind) {
                    ExtraKeysConfig.MacroKind.TEXT -> onText(key.payload)
                    // CMD：文本 + 回车 —— 与用户手动敲完按 Enter 完全同路
                    //（经 VM 的 sendInput → 黑白名单门禁 + 历史记录，零旁路）。
                    ExtraKeysConfig.MacroKind.CMD -> onText(key.payload + "\r")
                    ExtraKeysConfig.MacroKind.KEY -> resolveTerminalKey(key.payload)?.let(onKey)
                    ExtraKeysConfig.MacroKind.CTRL -> onControl(key.payload[0])
                    ExtraKeysConfig.MacroKind.PASTE -> onPaste()
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            key.label,
            fontSize = 12.5.sp,
            fontFamily = FontFamily.Monospace,
            color = KeybarChromeColors.keyTextAccent
        )
    }
}

/** 宏载荷 → TerminalKey（未知名静默忽略 —— 宁可无动作也不误发 ENTER）。 */
private fun resolveTerminalKey(name: String): TerminalKey? = runCatching {
    TerminalKey.valueOf(name.uppercase().replace(' ', '_'))
}.getOrNull()

/** 扩展行 chrome（强调色区分于内置键栏 —— 一眼可辨「这是我的宏」）。 */
private object KeybarChromeColors {
    val bg = Color(0xFF101613)
    val keyAccent = Color(0xFF1F3328)
    val keyTextAccent = Color(0xFFB9E8D2)
}
