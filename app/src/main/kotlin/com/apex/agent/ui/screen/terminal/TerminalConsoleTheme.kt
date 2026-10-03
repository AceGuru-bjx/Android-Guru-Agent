package com.apex.agent.ui.screen.terminal

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * T89 终端统一控制台调色（深色 mint 控制台 —— 与终端内容区同一视觉锚点）。
 *
 * 背景：终端主屏是恒深色控制台（[ConsoleTheme]），但所有弹层（设置抽屉/
 * 环境中心/配色选择器/历史/新建对话框）此前跟随 App MaterialTheme —— 浅色
 * App 主题下「深色主屏 + 浅色弹层」视觉割裂（用户反馈「页面一坨」的成因
 * 之一）。本对象把终端域的全部 chrome 色收敛为单一来源，主屏与弹层同源。
 */
internal object ConsoleTheme {
    /** 页面底色（顶栏/条带下的 chrome 层）。 */
    val bg = Color(0xFF0C1210)

    /** 顶栏 / 状态条底色。 */
    val bar = Color(0xFF111815)

    /** 会话 chip 底色。 */
    val chip = Color(0xFF18211C)

    /** 活跃 chip 底色。 */
    val chipActive = Color(0xFF1F3429)

    /** 分隔线。 */
    val stroke = Color(0xFF233029)

    /** 主文本（提亮至近白 —— 与 TerminalViewHost 前景统一，终端「白色字体」反馈）。 */
    val text = Color(0xFFF2F7F4)

    /** 次级文本。 */
    val dim = Color(0xFF7E948A)

    /** 强调（neon mint —— App dark primary）。 */
    val accent = Color(0xFF4EE9B0)

    /** 强调弱底（选中态 / 进度底）。 */
    val accentSoft = Color(0xFF1B382D)

    /** 警示（amber —— App dark secondary）。 */
    val amber = Color(0xFFFFB454)

    /** 警示弱底（P1-4：FALLBACK 类 notice 的底色 —— 降级提示非错误但值得警示）。 */
    val warnSoft = Color(0xFF33270F)

    /** 危险（magenta —— App dark tertiary）。 */
    val danger = Color(0xFFFF6B9D)

    /** 危险弱底。 */
    val dangerSoft = Color(0xFF38182A)

    /** 浮层底（P2-1：上下文菜单/跳底浮标等 TerminalViewHost 内 Popup ——
     * 旧值散落在文件私部 PopupChrome，与 ConsoleTheme 脱钩改主题要两处同步）。 */
    val popover = Color(0xE60F1613)
}

/**
 * 终端域弹层的统一 Material 主题 —— 深色控制台配色（不随 App 浅/深主题漂移）。
 *
 * 主屏 chrome（[ConsoleTheme] 直用）与弹层 Material 组件（本包装）共享同一
 * 调色板；终端整个特性在视觉上是一块完整的深色控制台表面。仅作用于终端域
 * 的弹层内容，离开弹层作用域即恢复 App 主题。
 */
@Composable
internal fun TerminalConsoleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ConsoleTheme.accent,
            onPrimary = Color(0xFF06120D),
            primaryContainer = ConsoleTheme.accentSoft,
            onPrimaryContainer = ConsoleTheme.accent,
            secondary = ConsoleTheme.amber,
            onSecondary = Color(0xFF1A1206),
            secondaryContainer = ConsoleTheme.accentSoft,
            onSecondaryContainer = ConsoleTheme.amber,
            tertiary = ConsoleTheme.danger,
            onTertiary = Color(0xFF1F0812),
            background = ConsoleTheme.bg,
            onBackground = ConsoleTheme.text,
            surface = ConsoleTheme.bar,
            onSurface = ConsoleTheme.text,
            surfaceVariant = ConsoleTheme.chip,
            onSurfaceVariant = ConsoleTheme.dim,
            surfaceDim = ConsoleTheme.bar,
            surfaceBright = ConsoleTheme.chipActive,
            surfaceContainerLowest = ConsoleTheme.bg,
            surfaceContainerLow = ConsoleTheme.bar,
            surfaceContainer = ConsoleTheme.chip,
            surfaceContainerHigh = ConsoleTheme.chipActive,
            surfaceContainerHighest = ConsoleTheme.chipActive,
            outline = ConsoleTheme.stroke,
            outlineVariant = ConsoleTheme.stroke,
            error = ConsoleTheme.danger,
            onError = Color(0xFF1F0812),
            errorContainer = ConsoleTheme.dangerSoft,
            onErrorContainer = ConsoleTheme.danger
        ),
        content = content
    )
}
