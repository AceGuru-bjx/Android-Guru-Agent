package com.apex.agent.ui.screen.terminal.scheme

/**
 * T87：scheme 注册表 —— id → [TerminalColorScheme] 的唯一查找入口。
 *
 * 纯 Kotlin（零 Android 依赖），app 单测可直接断言。
 * 持久化只存 id 字符串（[TerminalColorSchemeSettings]），加载时经
 * [byId] 解析；未知 id（升级删改方案/损坏数据）→ [TerminalColorScheme.FALLBACK_ID]
 * 兜底而非崩溃。
 */
object TerminalColorSchemeRegistry {

    private val byIdMap: Map<String, TerminalColorScheme> =
        TerminalColorSchemeDefs.ALL.associateBy { it.id }

    /** 默认方案（Apex Mint —— T85 控制台视觉延续）。 */
    val DEFAULT: TerminalColorScheme =
        byIdMap[TerminalColorScheme.FALLBACK_ID] ?: TerminalColorSchemeDefs.ALL.first()

    /** id 查找；未知 id → null（调用方决定兜底）。 */
    fun byIdOrNull(id: String?): TerminalColorScheme? =
        id?.let { byIdMap[it] }

    /** id 查找；未知/空 → [DEFAULT]（永不 null，UI 安全）。 */
    fun byId(id: String?): TerminalColorScheme =
        byIdOrNull(id) ?: DEFAULT

    /** 全部方案（注册顺序即展示顺序）。 */
    fun all(): List<TerminalColorScheme> = TerminalColorSchemeDefs.ALL

    /** 方案总数（选择器分块/测试断言用）。 */
    val count: Int get() = TerminalColorSchemeDefs.ALL.size

    /** id 合法性（设置页保存前校验）。 */
    fun isValidId(id: String): Boolean = byIdMap.containsKey(id)
}
