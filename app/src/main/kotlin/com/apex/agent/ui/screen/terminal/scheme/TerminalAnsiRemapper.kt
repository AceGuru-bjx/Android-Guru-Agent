package com.apex.agent.ui.screen.terminal.scheme

/**
 * T87：VT 引擎标准 16 色板 → 当前 scheme 的重映射器。
 *
 * 背景：Kotlin `TerminalCore` 与 C++ `NativeVtCore` 都在**引擎内部**把
 * SGR 索引色（0-15）转成 ARGB（引擎标准板 = TerminalColor.BASIC_16，
 * 奇偶校验测试锁定两实现逐字节一致）。渲染 cell 里只有最终 ARGB ——
 * 想换肤就得在渲染层把「标准板颜色」翻译成「scheme 板颜色」。
 *
 * 映射规则：
 *  1. `cellColor == 0L`（Default）→ scheme.foreground（bg 侧 → 0 保持透明语义，
 *     由基础样式/底色负责）。
 *  2. cellColor 的 RGB 部分与标准板 BASIC_16[i] 相等 → scheme.ansi[i]。
 *     （SGR 38;2 RGB truecolor 恰好等于某标准板色时同样会被翻译 ——
 *     视觉上与索引色无差别，行为可接受且实现零歧义。）
 *  3. **bold-as-bright**（xterm 传统，Termux 默认开）：FLAG_BOLD 且命中
 *     基础 8 色（0-7）→ 用亮色槽位（i+8）。bash/ls 等在白底上大量依赖
 *     「bold=亮色」约定（`ls --color` 的目录=bold blue 等）。
 *  4. 其余（256 色立方/灰阶/RGB truecolor）→ 原样透传（这些色与 scheme
 *     无冲突 —— scheme 只重定义语义 16 色）。
 *
 * 纯 Kotlin（Long 运算），零 Android/Compose 依赖 —— app JVM 单测直测。
 */
object TerminalAnsiRemapper {

    /** 引擎标准 16 色板（与 TerminalColor.BASIC_16 逐字节一致；本地常量避免模块依赖）。 */
    private val ENGINE_BASIC_16: LongArray = longArrayOf(
        0x000000L, 0x800000L, 0x008000L, 0x808000L,   // black red green yellow
        0x000080L, 0x800080L, 0x008080L, 0xC0C0C0L,   // blue magenta cyan white
        0x808080L, 0xFF0000L, 0x00FF00L, 0xFFFF00L,   // bright
        0x0000FFL, 0xFF00FFL, 0x00FFFFL, 0xFFFFFFL
    )

    /** 标准板 RGB → 索引（-1 = 不在板内）。命中多个不可能（板内无重复）。 */
    private val engineIndexByRgb: Map<Long, Int> =
        ENGINE_BASIC_16.withIndex().associate { (i, c) -> (c and 0xFFFFFFL) to i }

    /**
     * 前景色重映射。
     *
     * @param cellColor 引擎产出的 cell ARGB（0L = Default）
     * @param bold cell 是否 FLAG_BOLD
     * @param boldAsBright 是否启用 bold→亮色提升
     */
    fun mapForeground(cellColor: Long, bold: Boolean, boldAsBright: Boolean, scheme: TerminalColorScheme): Long {
        if (cellColor == 0L) return scheme.foreground
        val idx = engineIndexByRgb[cellColor and 0xFFFFFFL]
        if (idx == null) return cellColor                     // truecolor/256 → 透传
        if (boldAsBright && bold && idx < 8) return scheme.ansi[idx + 8]
        return scheme.ansi[idx]
    }

    /**
     * 背景色重映射（bold-as-bright 不适用于 bg —— xterm 语义）。
     * 0L 保持 0L（默认背景 = 终端底色，由渲染层负责）。
     */
    fun mapBackground(cellColor: Long, scheme: TerminalColorScheme): Long {
        if (cellColor == 0L) return 0L
        val idx = engineIndexByRgb[cellColor and 0xFFFFFFL] ?: return cellColor
        return scheme.ansi[idx]
    }

    /**
     * 反显（inverse）解析：交换后的前景/背景。双默认反显 = 亮底深字
     * （浅色 scheme 则深底亮字）—— 与真实终端 SGR 7 行为一致。
     *
     * @return Pair(fg, bg)——fg 恒非 0（反显的前景必有实色），bg 恒非 0。
     */
    fun mapInverse(
        cellFg: Long, cellBg: Long,
        scheme: TerminalColorScheme
    ): Pair<Long, Long> {
        val fg: Long = if (cellBg == 0L) scheme.background else mapBackground(cellBg, scheme)
        val bg: Long = if (cellFg == 0L) scheme.foreground else mapForeground(cellFg, bold = false, boldAsBright = false, scheme = scheme)
        return fg to bg
    }

    /**
     * dim（SGR 2）效果色：前景降不透明度（0.55）。
     * 纯 Long 运算（无 Compose alpha 类）。
     */
    fun dimColor(color: Long): Long {
        val alpha = ((color ushr 24) and 0xFFL)
        val dimmed = (alpha * 55L / 100L).coerceAtLeast(0x18L)   // 下限 10% 防全透明消失
        return (dimmed shl 24) or (color and 0x00FFFFFFL)
    }
}
