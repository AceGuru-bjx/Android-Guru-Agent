package com.apex.agent.ui.screen.glass

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.ui.glass.GlassStyle
import com.apex.agent.ui.glass.GlassSurface

/**
 * ═══════════════════════════════════════════════════════════════
 *  顶阶动态 —— v1.4.3 玻璃实验室新增首个区块（LabHeader 之后）
 * ═══════════════════════════════════════════════════════════════
 *
 * 对标顶级产品动态语言的三件套（全部实时绘制，逐帧可验证）：
 *  1. **流光边框**：绕心旋转的锥形渐变描边 —— Linear / Vercel 发布页质感；
 *  2. **镜面扫掠**：斜向光带周期性掠过玻璃卡面 —— 通知卡 / 会员卡的 sheen；
 *  3. **呼吸光晕**：三枚相位错开的玻璃球，光环脉动如呼吸节律。
 *
 * 诚实声明（延续本实验室原则）：
 *  - 边框/扫掠/光晕均为单图层 Canvas 级绘制，无折射位移（Refraction 仍未实现）；
 *  - 动画值以 State<Float> 传入 draw 作用域读取 —— 子树零逐帧重组。
 */
@Composable
internal fun DynamicsSection() {
    SectionHeader(
        title = "顶阶动态 · Pro Dynamics",
        hint = "对标顶级产品的三件套动态语言 —— 流光边框 / 镜面扫掠 / 呼吸光晕，全部实时绘制可逐帧验证"
    )
    ConicBorderCard()
    SpecularSweepCard()
    BreathingOrbsRow()
}

// ── 1. 流光边框 ──────────────────────────────────────────────────────────────

/** 旋转锥形渐变描边卡：光斑沿轮廓周向流动（9s 一周）。 */
@Composable
private fun ConicBorderCard() {
    val scheme = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(16.dp)

    val transition = rememberInfiniteTransition(label = "conic_border")
    val angle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 9000, easing = LinearEasing)),
        label = "angle"
    )

    DynamicsFrame(label = "流光边框 · Conic Border", icon = Icons.Filled.FlashOn) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .drawWithContent {
                    drawContent()
                    val outline = cardShape.createOutline(
                        size = size,
                        layoutDirection = layoutDirection,
                        density = this
                    )
                    rotate(degrees = angle.value, pivot = center) {
                        drawOutline(
                            outline = outline,
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    scheme.primary.copy(alpha = 0.90f),
                                    Color.Transparent,
                                    scheme.tertiary.copy(alpha = 0.60f),
                                    Color.Transparent
                                ),
                                center = center
                            ),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
        ) {
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                style = GlassStyle.Card,
                shape = cardShape
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            "光沿轮廓流动",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "锥形渐变绕心旋转 —— 单图层描边，零重组",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── 2. 镜面扫掠 ──────────────────────────────────────────────────────────────

/** 周期性斜向光带：4.2s 扫过一次 + 1.6s 停顿（StartOffset 相位）。 */
@Composable
private fun SpecularSweepCard() {
    val cardShape = RoundedCornerShape(16.dp)

    val transition = rememberInfiniteTransition(label = "specular_sweep")
    val sheen = transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(1600)
        ),
        label = "sheen"
    )

    DynamicsFrame(label = "镜面扫掠 · Specular Sweep", icon = Icons.Filled.BlurOn) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .drawWithContent {
                    drawContent()
                    val outline = cardShape.createOutline(
                        size = size,
                        layoutDirection = layoutDirection,
                        density = this
                    )
                    val bandX = sheen.value * size.width
                    drawOutline(
                        outline = outline,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.22f),
                                Color.Transparent
                            ),
                            start = Offset(bandX - size.width * 0.24f, 0f),
                            end = Offset(bandX + size.width * 0.24f, size.height)
                        )
                    )
                }
        ) {
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                style = GlassStyle.Card,
                shape = cardShape
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Filled.Lens,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            "光带掠过卡面",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "4.2s 扫掠 + 1.6s 停顿 —— 通知卡 / 会员卡同款节律",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── 3. 呼吸光晕 ──────────────────────────────────────────────────────────────

/** 三枚相位错开 1/3 周期的玻璃球：光环半径与亮度同步脉动。 */
@Composable
private fun BreathingOrbsRow() {
    val scheme = MaterialTheme.colorScheme

    val transition = rememberInfiniteTransition(label = "breathing_orbs")
    // 三路相位：0 / 1/3 / 2/3 周期错开 —— 呼吸此起彼伏
    val breathA = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath_a"
    )
    val breathB = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(866)
        ),
        label = "breath_b"
    )
    val breathC = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(1733)
        ),
        label = "breath_c"
    )

    DynamicsFrame(label = "呼吸光晕 · Breathing Glow", icon = Icons.Filled.AutoAwesome) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BreathingOrb(breath = breathA, orbSize = 56.dp, tint = scheme.primary)
            BreathingOrb(breath = breathB, orbSize = 72.dp, tint = scheme.tertiary)
            BreathingOrb(breath = breathC, orbSize = 56.dp, tint = scheme.secondary)
        }
    }
}

/** 单枚呼吸玻璃球：外圈脉动光晕 + 玻璃本体（参数名避开 DrawScope.size）。 */
@Composable
private fun BreathingOrb(breath: State<Float>, orbSize: androidx.compose.ui.unit.Dp, tint: Color) {
    Box(contentAlignment = Alignment.Center) {
        // 脉动光晕（draw 作用域读 State —— 零重组）
        Box(
            Modifier
                .size(orbSize * 1.65f)
                .drawBehind {
                    val b = breath.value
                    val r = size.minDimension * (0.30f + 0.16f * b)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                tint.copy(alpha = 0.30f + 0.18f * b),
                                Color.Transparent
                            ),
                            center = center,
                            radius = r
                        ),
                        radius = r,
                        center = center
                    )
                }
        )
        GlassSurface(
            modifier = Modifier.size(orbSize),
            style = GlassStyle.Floating,
            shape = CircleShape,
            accent = tint
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(orbSize * 0.34f)
                        .drawBehind {
                            val b = breath.value
                            drawCircle(
                                color = tint.copy(alpha = 0.45f + 0.35f * b),
                                radius = size.minDimension / 2f
                            )
                        }
                )
            }
        }
    }
}

// ── 展示框 ──────────────────────────────────────────────────────────────────

/** 动态演示的标准外框：等宽小标签 + 演示主体。 */
@Composable
private fun DynamicsFrame(
    label: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
        }
        content()
    }
}
