package com.apex.agent.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.update.UpdateCenter

/**
 * ═══════════════════════════════════════════════════════════════
 *  新版本浮窗提醒（v1.4.5）—— 非强制更新横幅
 * ═══════════════════════════════════════════════════════════════
 *
 * 数据源：[UpdateCenter.bannerVisible]（App 启动静默检查发现新版 且 用户
 * 未「忽略此版本」）。设计红线：
 *  - **非强制**：绝不弹模态框打断操作 —— 顶部轻横幅，点外部操作不受影响；
 *  - **可拒绝**：✕ = 忽略此版本（持久化，该版本永不再弹；下个新版重新提醒）；
 *  - **可深挖**：「查看」= 本次会话隐藏 + 跳转关于页（完整更新面板：
 *    增量补丁链 / 全量包 / 镜像选择全在那里）。
 *
 * 视觉：primaryContainer 圆角条 + 系统更新图标 + 版本号 + NEW 徽标语义
 * （与关于页的更新横幅同一视觉锚点）。收展动画与 OfflineBanner 一致。
 *
 * 接线（ApexRoot）：
 * ```
 * val banner by UpdateCenter.bannerVisible.collectAsStateWithLifecycle()
 * UpdateBanner(visible = banner, onOpenAbout = { currentDestination = About })
 * ```
 */
@Composable
fun UpdateBanner(
    visible: Boolean,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 版本号从 UpdateCenter 取（检查命中即有值；浮窗只在 Available 时可见）
    val manifest = UpdateCenter.availableManifest()
    val versionName = manifest?.versionName.orEmpty()
    val dismissDesc = stringResource(R.string.update_banner_dismiss_desc)
    val newBadge = stringResource(R.string.about_update_new_badge)

    AnimatedVisibility(
        visible = visible && versionName.isNotEmpty(),
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            tonalElevation = 1.dp,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.SystemUpdateAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.update_banner_title, versionName),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = newBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.update_banner_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
                TextButton(
                    onClick = {
                        UpdateCenter.consumeBannerForNavigation()
                        onOpenAbout()
                    }
                ) {
                    Text(stringResource(R.string.update_banner_view))
                }
                IconButton(onClick = { UpdateCenter.dismissBannerForCurrentVersion() }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = dismissDesc,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
