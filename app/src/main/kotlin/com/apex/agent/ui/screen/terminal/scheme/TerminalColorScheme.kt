package com.apex.agent.ui.screen.terminal.scheme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 终端配色方案模型（T87 — Termux 风格彩色终端）。
 *
 * 设计约束：
 *  - **纯 Kotlin 值**：颜色以 ARGB `Long` 存储（0xFFRRGGBB），不依赖任何
 *    Android/Compose 类型 —— [TerminalColorSchemeDefs]/[TerminalAnsiRemapper]
 *    因此可以在纯 JVM 单测里直接断言（app 单测零 Robolectric）。
 *  - **16 色 ANSI 全集**：0-7 基本色 + 8-15 亮色。VT 引擎把 SGR 索引色转成
 *    ARGB 时使用内置标准 16 色板（[com.apex.agent.terminalemulator.TerminalColor.BASIC_16]，
 *    C++ NativeVtCore 同板 —— 奇偶校验测试锁定）—— 渲染层用
 *    [TerminalAnsiRemapper] 把「引擎标准板颜色」重映射为当前 scheme 的对应槽位，
 *    RGB truecolor（SGR 38;2）原样透传。这样 Kotlin/C++ 双引擎无需任何改动
 *    即可获得完整换肤能力。
 *  - **语义色**：background/foreground/cursor/selection 独立于 ANSI 16 色
 *    （scheme 自身决定光标与选区颜色，Termux properties 同款）。
 */
data class TerminalColorScheme(
    /** 稳定 id（持久化键）。 */
    val id: String,
    /** 英文显示名。 */
    val name: String,
    /** 中文显示名（语言切换用；UI 按 Locale 选择）。 */
    val nameZh: String,
    /** 深色底方案？（浅色 scheme 的反显/dim 语义需要区分）。 */
    val dark: Boolean = true,
    /** 终端底色（ARGB）。 */
    val background: Long,
    /** 默认前景色（ARGB）—— 无 ANSI 着色的普通文本。 */
    val foreground: Long,
    /** 光标颜色（ARGB）。 */
    val cursor: Long,
    /** 选区高亮底色（ARGB，通常带透明度）。 */
    val selectionBackground: Long,
    /** 选区内文字色（null = 保持原色）。 */
    val selectionForeground: Long? = null,
    /** ANSI 16 色（索引 0-15 = black red green yellow blue magenta cyan white + 亮色变体）。 */
    val ansi: List<Long>
) {
    init {
        require(ansi.size == 16) { "TerminalColorScheme.ansi must have exactly 16 entries (got ${ansi.size})" }
    }

    // ── 命名访问器（索引语义与 xterm 一致）──
    val black: Long get() = ansi[0]
    val red: Long get() = ansi[1]
    val green: Long get() = ansi[2]
    val yellow: Long get() = ansi[3]
    val blue: Long get() = ansi[4]
    val magenta: Long get() = ansi[5]
    val cyan: Long get() = ansi[6]
    val white: Long get() = ansi[7]
    val brightBlack: Long get() = ansi[8]
    val brightRed: Long get() = ansi[9]
    val brightGreen: Long get() = ansi[10]
    val brightYellow: Long get() = ansi[11]
    val brightBlue: Long get() = ansi[12]
    val brightMagenta: Long get() = ansi[13]
    val brightCyan: Long get() = ansi[14]
    val brightWhite: Long get() = ansi[15]

    /** 预览条用的 8 色（基础色，Termux 配色选择器同款展示）。 */
    val previewColors: List<Long> get() = ansi.subList(0, 8)

    // ── Compose 转换（仅 UI 层使用）──
    val backgroundC: Color get() = Color(background.toInt())
    val foregroundC: Color get() = Color(foreground.toInt())
    val cursorC: Color get() = Color(cursor.toInt())
    val selectionBackgroundC: Color get() = Color(selectionBackground.toInt())
    val selectionForegroundC: Color? get() = selectionForeground?.let { Color(it.toInt()) }

    /** 本地化显示名（zh 环境 → [nameZh]）。 */
    fun displayName(zh: Boolean): String = if (zh) nameZh else name

    companion object {
        /** 找不到 scheme id 时的兜底（注册表缺项 / 持久化值损坏）。 */
        const val FALLBACK_ID = "apex-mint"
    }
}

/**
 * 渲染树内传递当前 scheme（[TerminalGrid] 读取；宿主以
 * `CompositionLocalProvider(LocalTerminalColorScheme provides scheme) { … }` 注入）。
 * staticCompositionLocal —— scheme 切换整树重组，单屏终端完全可接受。
 */
val LocalTerminalColorScheme = staticCompositionLocalOf<TerminalColorScheme> {
    TerminalColorSchemeRegistry.byId(TerminalColorScheme.FALLBACK_ID)
}

/**
 * bold-as-bright（xterm 传统：FLAG_BOLD + 基础 8 色 → 亮色槽位）。
 * bash/ls 等彩色输出大量依赖该约定（目录=bold blue 等）；Termux 默认开启。
 * 与 scheme 一起由渲染树注入（同一处 CompositionLocalProvider）。
 */
val LocalTerminalBoldAsBright = staticCompositionLocalOf { true }
