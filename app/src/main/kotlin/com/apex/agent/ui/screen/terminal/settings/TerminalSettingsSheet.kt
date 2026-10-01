package com.apex.agent.ui.screen.terminal.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.screen.terminal.SettingsCard
import com.apex.agent.ui.screen.terminal.SurfaceBadge
import com.apex.agent.ui.screen.terminal.TerminalConsoleTheme
import com.apex.agent.ui.screen.terminal.TerminalViewModel
import com.apex.agent.ui.screen.terminal.ToggleRow

/**
 * 终端设置弹层（T89 从侧抽屉改为 bottom sheet）。
 *
 * 变更（T89 终端大修）：
 *  - **范式统一**：终端域的配置类弹层此前三种范式并存（设置=侧抽屉、环境中心/
 *    配色/历史=bottom sheet、新建=AlertDialog），drawer 套在 App 全局 drawer 里
 *    形成双层侧滑。统一收敛为 bottom sheet（新建会话保留系统 AlertDialog ——
 *    快速二选一的标准容器）；
 *  - **字号区间修正**：输入侧允许 8..32 而 VM 钳制 8..24 —— 输 25~32 显示大
 *    数字实际落盘 24 的静默分叉修复（对齐 TerminalSettings.MIN/MAX）；
 *  - **深色控制台主题**：经 [TerminalConsoleTheme] 包裹 —— 浅色 App 主题下
 *    弹层不再「白纸糊在黑终端上」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalSettingsSheet(
    settings: TerminalViewModel.TerminalSettings,
    onSettings: (TerminalViewModel.TerminalSettings.() -> TerminalViewModel.TerminalSettings) -> Unit,
    schemeName: String,
    onOpenSchemePicker: () -> Unit,
    boldAsBright: Boolean,
    onBoldAsBright: (Boolean) -> Unit,
    onOpenHistory: () -> Unit,
    extraKeys: List<com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig.ExtraKey>,
    onAddExtraKey: (String) -> Unit,
    onRemoveExtraKey: (String) -> Unit,
    onResetExtraKeys: () -> Unit,
    blacklist: Set<String>,
    whitelist: Set<String>,
    onAddBlack: (String) -> Unit,
    onRemoveBlack: (String) -> Unit,
    onAddWhite: (String) -> Unit,
    onRemoveWhite: (String) -> Unit,
    onClose: () -> Unit
) {
    TerminalConsoleTheme {
        ModalBottomSheet(
            onDismissRequest = onClose,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 标题
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SurfaceBadge(Icons.Default.Settings, MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.term_settings_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }

                // ═══ 1. 终端外观与交互 ═══
                // v1.4.4 UX 审查 #233：字号上下限统一为 TerminalSettings 常量（T89 上游同款修复）
                SettingsCard(Icons.Default.Settings, stringResource(R.string.term_appearance)) {
                    LabeledNumber(
                        label = stringResource(R.string.term_font_size),
                        value = settings.fontSize,
                        min = TerminalViewModel.TerminalSettings.MIN_FONT_SIZE,
                        max = TerminalViewModel.TerminalSettings.MAX_FONT_SIZE
                    ) { onSettings { copy(fontSize = it) } }
                    ToggleRow(stringResource(R.string.term_monochrome), settings.monochrome) { onSettings { copy(monochrome = it) } }
                    ToggleRow(stringResource(R.string.term_keybar), settings.showKeybar) { onSettings { copy(showKeybar = it) } }
                }

                // ═══ 1a. 配色方案 ═══
                SettingsCard(Icons.Default.Palette, stringResource(R.string.term_scheme_section)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(stringResource(R.string.term_scheme_current), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                schemeName,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        TextButton(onClick = onOpenSchemePicker) {
                            Text(stringResource(R.string.term_scheme_change))
                        }
                    }
                    ToggleRow(stringResource(R.string.term_bold_as_bright), boldAsBright, onBoldAsBright)
                    Text(
                        stringResource(R.string.term_bold_as_bright_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // ═══ 1b. 命令历史 ═══
                SettingsCard(Icons.Default.History, stringResource(R.string.term_history_section)) {
                    Text(
                        stringResource(R.string.term_history_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onOpenHistory) {
                        Text(stringResource(R.string.term_history_open))
                    }
                }

                // ═══ 1c. 扩展键行（Termux extra-keys 等价物）═══
                SettingsCard(Icons.Default.Apps, stringResource(R.string.term_extra_keys_section)) {
                    Text(
                        stringResource(R.string.term_extra_keys_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (extraKeys.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(modifier = Modifier.height((extraKeys.size.coerceAtMost(4) * 34).dp)) {
                            items(extraKeys, key = { it.label + it.kind + it.payload }) { k ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "• ${k.label}  (${kindLabel(k.kind)})",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    TextButton(onClick = { onRemoveExtraKey(k.label) }) {
                                        Text(stringResource(R.string.term_remove), color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                    ExtraKeyAdder(onAdd = onAddExtraKey)
                    TextButton(onClick = onResetExtraKeys) {
                        Text(stringResource(R.string.term_extra_keys_reset), color = MaterialTheme.colorScheme.primary)
                    }
                }

                // ═══ 2. 反馈（对齐 Termux / ConnectBot 的终端反馈习惯）═══
                SettingsCard(Icons.Default.Settings, stringResource(R.string.term_feedback)) {
                    ToggleRow(stringResource(R.string.term_vibrate_on_bell), settings.vibrateOnBell) {
                        onSettings { copy(vibrateOnBell = it) }
                    }
                    Text(
                        stringResource(R.string.term_vibrate_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ToggleRow(stringResource(R.string.term_keep_screen_on), settings.keepScreenOn) {
                        onSettings { copy(keepScreenOn = it) }
                    }
                    Text(
                        stringResource(R.string.term_keep_screen_on_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // ═══ 3. 黑名单 / 白名单 ═══
                SettingsCard(Icons.Default.Block, stringResource(R.string.term_blacklist_title)) {
                    Text(
                        stringResource(R.string.term_blacklist_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    CommandListEditor(
                        title = stringResource(R.string.term_blacklist),
                        items = blacklist.toList().sorted(),
                        onAdd = onAddBlack,
                        onRemove = onRemoveBlack,
                        danger = true
                    )
                    Spacer(Modifier.height(8.dp))
                    CommandListEditor(
                        title = stringResource(R.string.term_whitelist),
                        items = whitelist.toList().sorted(),
                        onAdd = onAddWhite,
                        onRemove = onRemoveWhite,
                        danger = false
                    )
                }

                // ═══ 4. 入口提示（环境解包在环境中心）═══
                Text(
                    stringResource(R.string.term_env_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── 其余小组件直接复用 EnvironmentCenterSheet 的 internal 共享件（同模块跨包可见）──

@Composable
private fun LabeledNumber(label: String, value: Int, min: Int, max: Int, onSet: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                text = t
                // 越界输入只标红不落盘（coerce 后写入但框内仍显示越界值的静默分叉修复）
                val n = t.toIntOrNull()
                if (n != null && n in min..max) onSet(n)
            },
            isError = text.toIntOrNull()?.let { it !in min..max } ?: true,
            supportingText = if (text.toIntOrNull()?.let { it !in min..max } ?: true) {
                { Text("$min–$max") }
            } else null,
            modifier = Modifier.width(88.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun CommandListEditor(title: String, items: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit, danger: Boolean) {
    var input by remember { mutableStateOf("") }
    Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.term_cmd_hint), style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall
        )
        TextButton(onClick = {
            if (input.isNotBlank()) { onAdd(input.trim()); input = "" }
        }) { Text(stringResource(R.string.term_add)) }
    }
    if (items.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        LazyColumn(modifier = Modifier.height((items.size.coerceAtMost(4) * 36).dp)) {
            items(items) { cmd ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("• $cmd", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    TextButton(onClick = { onRemove(cmd) }) { Text(stringResource(R.string.term_remove), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

/** 宏类型 → 短标签（设置弹层列表展示）。 */
private fun kindLabel(kind: com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig.MacroKind): String =
    when (kind) {
        com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig.MacroKind.TEXT -> "文本"
        com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig.MacroKind.CMD -> "命令"
        com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig.MacroKind.KEY -> "按键"
        com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig.MacroKind.CTRL -> "控制"
        com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig.MacroKind.PASTE -> "粘贴"
    }

/** 扩展键添加器（spec 输入框 + 添加；格式见 ExtraKeysConfig KDoc）。 */
@Composable
private fun ExtraKeyAdder(onAdd: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = {
                Text(
                    stringResource(R.string.term_extra_keys_hint),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            textStyle = MaterialTheme.typography.bodySmall
        )
        TextButton(onClick = {
            if (input.isNotBlank()) { onAdd(input.trim()); input = "" }
        }) { Text(stringResource(R.string.term_add)) }
    }
}
