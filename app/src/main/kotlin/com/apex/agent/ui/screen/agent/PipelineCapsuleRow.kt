package com.apex.agent.ui.screen.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * ═══ 输入栏 · 流水线指令迷你胶囊行（v2 紧凑版）═══
 *
 * 用户从斜杠菜单（SlashCommandButton / 实时联想）选中 Skill / MCP / 插件 /
 * 连接器后，指令不再以 `/skill:xxx` 裸文本污染输入框，而是挂在本行的
 * 迷你胶囊上：
 *
 * ```
 * ┌──────────────────────────────┐
 * │ [</> skill: 网页搜索 ×]        │  ← 胶囊行（无胶囊时不渲染）
 * │ [ 🔍 输入框 ................ ] │
 * └──────────────────────────────┘
 * ```
 *
 * ## v2 修复（用户反馈「技能占的页面范围太大、与对话框重叠」）
 * - 旧版关闭钮走 `sizeIn(min 48×48)`，把「迷你」胶囊撑到 ~54dp——比输入栏
 *   所有元素（工具栏 40dp / 输入框 ~56dp / 工具芯片 ~26dp）都高，视觉上
 *   糊在输入框上；
 * - 现在胶囊高度收敛到 28dp 量级（`heightIn(min=28)` + 竖向 3dp 内边距），
 *   与 ToolkitChip / AgentModeSelector 同规格；关闭钮视觉 12dp、触区 28dp
 *   圆形（与输入栏既有芯片的触区规格一致——48dp 红线保留给时间轴大胶囊，
 *   输入栏一族统一紧凑档）。
 *
 * - 胶囊内容 = 类型图标 + `type: 展示名` + × 移除钮；
 * - 点击 × 移除胶囊；再选一条直接替换（单条语义）；
 * - 发送时由 ViewModel 把胶囊拼回 `/type:id` + 输入框附加文本走斜杠管线。
 */
@Composable
fun PipelineCapsuleRow(
    pending: PendingPipelineCommand?,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (pending == null) return

    val removeCd = androidx.compose.ui.res.stringResource(R.string.chat_cd_close)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        PipelineCapsule(
            pending = pending,
            removeCd = removeCd,
            onRemove = onRemove
        )
    }
}

@Composable
private fun PipelineCapsule(
    pending: PendingPipelineCommand,
    removeCd: String,
    onRemove: () -> Unit
) {
    val icon = pipelineIconOf(pending.type)
    val typeTag = pipelineTypeTagOf(pending.type)
    val cd = "$typeTag: ${pending.label}"

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        // mergeDescendants：TalkBack 把胶囊读作一个整体（否则先读胶囊
        // 描述、再逐个读子 Text/关闭钮，重复播报）
        modifier = Modifier
            .semantics(mergeDescendants = true) { contentDescription = cd }
            .heightIn(min = 28.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, top = 3.dp, bottom = 3.dp, end = 2.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "$typeTag: ${pending.label}",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 视觉 12dp、触区 28dp 圆形（输入栏芯片族统一紧凑档；48dp 红线
            // 保留给时间轴大胶囊——那里有充足留白容纳大触区）
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(2.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = removeCd,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

/** 类型 → 图标（与斜杠菜单分组图标同源，跨入口认知一致）。 */
private fun pipelineIconOf(type: String): ImageVector = when (type) {
    "mcp" -> Icons.Default.Api
    "connector" -> Icons.Default.Link
    "plugin" -> Icons.Default.Extension
    else -> Icons.Default.Code // skill —— Material "Code" 即 </> 代码符号
}

/** 类型 → 胶囊前缀标签（全小写，与斜杠命令 type 段一致）。 */
private fun pipelineTypeTagOf(type: String): String = when (type) {
    "mcp" -> "mcp"
    "connector" -> "connector"
    "plugin" -> "plugin"
    else -> "skill"
}
