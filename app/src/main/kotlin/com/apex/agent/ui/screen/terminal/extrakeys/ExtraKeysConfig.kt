package com.apex.agent.ui.screen.terminal.extrakeys

/**
 * T87：可定制扩展键行（Termux extra-keys 的等价物）。
 *
 * Termux 的 extra-keys 允许用户把任意「宏」钉在键盘上方的工具行。本类是该
 * 概念的数据模型 + 解析/序列化（纯 Kotlin，JVM 单测直测）：
 *
 * 宏语法（一行一个，持久化为多行文本）：
 * ```
 * apt更新=cmd:apt-get update          # 文本 + 回车（立即执行）
 * py3=text:python3                    # 纯文本注入（不执行）
 * F1=key:F1                           # 特殊键（TerminalKey 名）
 * ^C=ctrl:c                           # 控制字符
 * 粘贴=paste                          # 系统粘贴
 * ```
 *
 * 设计约束：
 *  - 行数上限 [MAX_ROWS]（每行 8 键 → 最多 40 键；超过 UI 横向滚动也失控）；
 *  - 非法行解析失败 → 跳过该行（其余照常 —— 损坏一行不废整条工具栏）；
 *  - 默认布局见 [DEFAULT_LAYOUT]（贴近本项目高频命令）。
 */
object ExtraKeysConfig {

    /** 宏类型。 */
    enum class MacroKind { TEXT, CMD, KEY, CTRL, PASTE }

    /** 单个扩展键。 */
    data class ExtraKey(
        val label: String,
        val kind: MacroKind,
        /** TEXT/CMD 的文本、KEY 的 TerminalKey 名、CTRL 的单字母。 */
        val payload: String
    ) {
        init {
            require(label.isNotBlank()) { "label must not be blank" }
        }
    }

    /** 单行键数上限（横向滚动行，过多则 UI 失控）。 */
    const val MAX_KEYS_PER_ROW = 10

    /** 行数上限。 */
    const val MAX_ROWS = 4

    /** 默认布局（高频：Ubuntu 引导修复 + 常用解释器 + 导航）—— 「恢复默认」按钮
     * 的目标；新用户初始为空（T89：默认 8 宏键与 KeyToolbar 主行 TAB/^L/粘贴
     * 重复，双行键区泛滥成「一坨按钮」，改为用户显式添加）。 */
    val DEFAULT_LAYOUT: List<List<ExtraKey>> = listOf(
        listOf(
            ExtraKey("apt-fix", MacroKind.CMD, "apt-fix"),
            ExtraKey("py3", MacroKind.TEXT, "python3"),
            ExtraKey("ls", MacroKind.CMD, "ls -alF"),
            ExtraKey("h", MacroKind.CMD, "history 25"),
            ExtraKey("git st", MacroKind.CMD, "git status")
        )
    )

    /** 新用户默认：不预置宏键（空行 → ExtraKeysBar 零占位）。 */
    val EMPTY_LAYOUT: List<List<ExtraKey>> = emptyList()

    /**
     * 解析持久化文本 → 布局（行以空行分隔；行内以 `;;` 分隔键）。
     * 全部非法/空 → null（调用方回落 [DEFAULT_LAYOUT]）。
     */
    fun parse(stored: String?): List<List<ExtraKey>>? {
        if (stored.isNullOrBlank()) return null
        val rows = mutableListOf<List<ExtraKey>>()
        for (rowText in stored.split("\n\n")) {
            if (rows.size >= MAX_ROWS) break
            val keys = rowText.split(";;").mapNotNull { parseKey(it.trim()) }
            if (keys.isNotEmpty() && keys.size <= MAX_KEYS_PER_ROW) {
                rows.add(keys)
            }
        }
        return rows.ifEmpty { null }
    }

    /** 单键解析：`label=kind:payload` 或 `label=paste`（载荷含换行/分隔符 → 拒绝）。 */
    internal fun parseKey(spec: String): ExtraKey? {
        if (spec.isBlank()) return null
        // 载荷不得含换行（行分隔符）—— 拒绝而非静默接受畸形输入
        if (spec.count { it == '\n' } > 0) return null
        val eq = spec.indexOf('=')
        if (eq <= 0) return null
        val label = spec.substring(0, eq).trim()
        if (label.isBlank() || label.length > 8) return null
        val rest = spec.substring(eq + 1).trim()
        return when {
            rest.equals("paste", true) -> ExtraKey(label, MacroKind.PASTE, "")
            rest.startsWith("cmd:", true) ->
                rest.substring(4).trim().takeIf { it.isNotEmpty() }?.let { ExtraKey(label, MacroKind.CMD, it) }
            rest.startsWith("text:", true) ->
                rest.substring(5).trim().takeIf { it.isNotEmpty() }?.let { ExtraKey(label, MacroKind.TEXT, it) }
            rest.startsWith("key:", true) ->
                rest.substring(4).trim().takeIf { it.isNotEmpty() }?.let { ExtraKey(label, MacroKind.KEY, it) }
            rest.startsWith("ctrl:", true) ->
                rest.substring(5).trim().take(1).takeIf { it.isNotEmpty() }?.let { ExtraKey(label, MacroKind.CTRL, it) }
            else -> null
        }
    }

    /** 布局 → 持久化文本（与 [parse] 互逆；不可逆的载荷字符被拒绝返回 null）。 */
    fun serialize(layout: List<List<ExtraKey>>): String? {
        if (layout.isEmpty()) return null
        // 载荷不得破坏编码（;; 与换行是分隔符）—— 预检（joinToString 的
        // transform 是 crossinline，禁止非局部 return，故先过滤后拼接）。
        for (row in layout) {
            for (key in row) {
                if (key.kind != MacroKind.PASTE &&
                    (key.payload.contains(";;") || key.payload.contains('\n'))
                ) return null
            }
        }
        return layout.take(MAX_ROWS).joinToString("\n\n") { row ->
            row.take(MAX_KEYS_PER_ROW).joinToString(";;") { key ->
                when (key.kind) {
                    MacroKind.PASTE -> "${key.label}=paste"
                    else -> "${key.label}=${prefixOf(key.kind)}:${key.payload}"
                }
            }
        }
    }

    private fun prefixOf(kind: MacroKind): String = when (kind) {
        MacroKind.TEXT -> "text"
        MacroKind.CMD -> "cmd"
        MacroKind.KEY -> "key"
        MacroKind.CTRL -> "ctrl"
        MacroKind.PASTE -> "paste"
    }

    /** 新键追加到末行（超限自动换行；整表满 → 返回原布局）。 */
    fun appendKey(layout: List<List<ExtraKey>>, key: ExtraKey): List<List<ExtraKey>> {
        if (layout.isEmpty()) return listOf(listOf(key))
        val rows = layout.map { it.toMutableList() }.toMutableList()
        val last = rows.last()
        if (last.size < MAX_KEYS_PER_ROW) {
            last.add(key)
        } else if (rows.size < MAX_ROWS) {
            rows.add(mutableListOf(key))
        } else {
            return layout
        }
        return rows
    }

    /** 移除指定标签的全部实例。 */
    fun removeKey(layout: List<List<ExtraKey>>, label: String): List<List<ExtraKey>> =
        layout.map { row -> row.filterNot { it.label == label } }.filter { it.isNotEmpty() }
}
