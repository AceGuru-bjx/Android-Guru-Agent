package com.apex.agent.ui.screen.code

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.standard.StandardLogicMode

/**
 * # Code Logic Mode Selector — Coding 屏右上角「思考逻辑」切换器
 *
 * Coding 模式的完成任务逻辑双引擎切换（v1.5）：
 *
 * - **深潜**（[StandardLogicMode.DEEP_DIVE]）：项目自研思考逻辑——七档
 *   思考阶梯 + AUTO 预检选档 + 深水区升级（现状默认）；
 * - **标准**（[StandardLogicMode.STANDARD]）：业界标准 Agent 任务循环
 *   ——Agent 画像（构建者/规划师）、权限三态门、隔离上下文子代理、
 *   会话压缩、task 工具委派。
 *
 * ## UI 规格（对齐 CodeThinkingSelector v2 的设计语言）
 *
 * - 触发胶囊：28dp 无边框（`RoundedCornerShape(50)`），13dp 图标 +
 *   本地化短名（「逻辑 · 深潜/标准」）；标准态用 tertiary 配色示差异；
 * - 下拉（宽 300dp，三段）：双逻辑项（选中 Check + 主色加粗 + 一行
 *   画像说明，免文字墙）+ 分隔线 + 当前引擎能力徽标行；
 * - 运行中切换被 VM 拒绝（返回 false）→ 胶囊后置一个短暂提示态
 *   （switchBlocked，2 秒自清）。
 */
@Composable
internal fun CodeLogicModeSelector(
    current: StandardLogicMode,
    onSwitchBlocked: Boolean,
    onSelect: (StandardLogicMode) -> Boolean
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        // ── 触发胶囊（28dp 无边框，对齐 CodeThinkingSelector 规格）──
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(50),
            color = if (current == StandardLogicMode.STANDARD) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = if (current == StandardLogicMode.STANDARD) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            modifier = Modifier.heightIn(min = 28.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = if (current == StandardLogicMode.STANDARD) {
                        Icons.Default.Route
                    } else {
                        Icons.Default.Psychology
                    },
                    contentDescription = null,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = stringResource(
                        R.string.code_logic_chip,
                        logicModeShortName(current)
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        // ── 结构化下拉（宽 300dp：双逻辑项 / 引擎能力徽标）──
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(300.dp)
        ) {
            Text(
                text = stringResource(R.string.code_logic_selector_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            StandardLogicMode.entries.forEach { mode ->
                val selected = mode == current
                DropdownMenuItem(
                    text = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = logicModeShortName(mode),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        selected && mode == StandardLogicMode.STANDARD ->
                                            MaterialTheme.colorScheme.tertiary
                                        selected -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = stringResource(
                                        if (mode == StandardLogicMode.STANDARD) {
                                            R.string.code_logic_badge_standard
                                        } else {
                                            R.string.code_logic_badge_deep_dive
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = stringResource(
                                    if (mode == StandardLogicMode.STANDARD) {
                                        R.string.code_logic_standard_desc
                                    } else {
                                        R.string.code_logic_deep_dive_desc
                                    }
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 切换被拒（运行中）提示——选中项正下方的行内告警
                            if (selected && onSwitchBlocked) {
                                Text(
                                    text = stringResource(R.string.code_logic_switch_blocked),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    },
                    leadingIcon = {
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = if (mode == StandardLogicMode.STANDARD) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Spacer(modifier = Modifier.size(16.dp))
                        }
                    },
                    onClick = {
                        val accepted = onSelect(mode)
                        if (accepted) expanded = false
                        // 拒绝时保持下拉展开（onSwitchBlocked 提示行内展示）
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            // 引擎能力徽标行（当前引擎的三个关键能力点，静态展示）
            Text(
                text = stringResource(
                    if (current == StandardLogicMode.STANDARD) {
                        R.string.code_logic_caps_standard
                    } else {
                        R.string.code_logic_caps_deep_dive
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
}

/** 逻辑 → 本地化短名（触发胶囊与下拉共用；zh：深潜/标准）。 */
@Composable
internal fun logicModeShortName(mode: StandardLogicMode): String = stringResource(
    when (mode) {
        StandardLogicMode.DEEP_DIVE -> R.string.code_logic_mode_deep_dive
        StandardLogicMode.STANDARD -> R.string.code_logic_mode_standard
    }
)
