package com.apex.agent.core.code.standard

import java.util.concurrent.ConcurrentHashMap

/**
 * # Standard Permission Engine — 标准任务循环的工具权限门
 *
 * 每一次工具调用在执行前必须过本门：**规则 → 会话记忆 → 模式兜底** 三级
 * 裁决，输出三态效应 [StandardPermissionDecision]（ALLOW / ASK / DENY）。
 *
 * ## 裁决顺序（先命中先出，与既有设置层 PermissionDecider 语义对齐）
 *
 * 1. **DENY 规则**优先短路（显式拒绝永远最硬——BYPASS 模式也尊重 DENY）；
 * 2. **会话记忆**（用户在 ASK 弹窗选过"本会话总允许"的工具/命令模式）；
 * 3. **ALLOW/ASK 规则**（前缀通配匹配工具 id，[matches]）；
 * 4. **模式兜底**：
 *    - BYPASS → ALLOW（除 DENY 规则）；
 *    - ACCEPT_EDITS → 编辑/写/git 提交类 ALLOW，其余非只读 ASK；
 *    - DEFAULT → 只读类 ALLOW，写/命令类 ASK；
 *    - PLAN → 只读类 ALLOW，**任何写副作用直接 DENY**（规则不可越级
 *      ——规划阶段零副作用是硬承诺）。
 *
 * ## 只读面判定（[isReadOnlyTool]）
 *
 * 注册表元数据可用时按 id 前缀静态表判定（code_read / code_grep /
 * code_glob / code_git_status / code_git_diff / code_git_log / web 检索 /
 * code_todo 只写自己的状态文件，计为只读）——不依赖注册表实例，
 * 纯函数可单测。
 *
 * ## 命令级通配（shell_execute 专属）
 *
 * bash 类工具参数是命令字符串——规则可以对**命令首词**做尾缀通配
 * （如 `ls*`、`git status*`）。[commandPatternMatches] 只解析参数 JSON
 * 失败时退化为整串匹配。命令级规则优先于工具级规则。
 *
 * ## 会话记忆
 *
 * [rememberSessionAllow] 写入（进程内 ConcurrentHashSet，会话结束
 * [resetSessionMemory] 清空——不落盘，重启后重新询问，安全默认）。
 *
 * 线程契约：纯内存 + 并发容器，任意线程调用安全。
 */
class StandardPermissionEngine(
    /** 初始模式（运行期经 [updateMode] 热切换）。 */
    initialMode: StandardPermissionMode = StandardPermissionMode.DEFAULT
) {

    @Volatile
    private var mode: StandardPermissionMode = initialMode

    /** 工具级规则（按序首匹配；DI 从设置层拍平注入）。 */
    @Volatile
    private var rules: List<StandardPermissionRule> = emptyList()

    /** 命令级规则（仅作用于 shell 类工具；pattern = 命令首词尾缀通配）。 */
    @Volatile
    private var commandRules: List<StandardPermissionRule> = emptyList()

    /** 会话记忆：已"本会话总允许"的工具 id。 */
    private val sessionAllowedTools = ConcurrentHashMap.newKeySet<String>()

    /** 会话记忆：已"本会话总允许"的命令模式。 */
    private val sessionAllowedCommands = ConcurrentHashMap.newKeySet<String>()

    // ═══════════════════════ 配置 API ═══════════════════════

    /** 切换权限模式（立即生效，下一次裁决用新模式）。 */
    fun updateMode(newMode: StandardPermissionMode) {
        mode = newMode
    }

    /** 当前模式。 */
    fun currentMode(): StandardPermissionMode = mode

    /** 更新规则集（整体替换，快照语义；空列表 = 无规则）。 */
    fun updateRules(newRules: List<StandardPermissionRule>) {
        rules = newRules.filter { it.pattern.isNotBlank() }
    }

    /** 更新命令级规则集（整体替换）。 */
    fun updateCommandRules(newRules: List<StandardPermissionRule>) {
        commandRules = newRules.filter { it.pattern.isNotBlank() }
    }

    /** 清空会话记忆（新会话 / 用户显式重置）。 */
    fun resetSessionMemory() {
        sessionAllowedTools.clear()
        sessionAllowedCommands.clear()
    }

    /** 记住一条"本会话总允许"（工具 id 或命令模式）。 */
    fun rememberSessionAllow(target: String, isCommand: Boolean = false) {
        if (isCommand) sessionAllowedCommands.add(target) else sessionAllowedTools.add(target)
    }

    // ═══════════════════════ 裁决 API ═══════════════════════

    /**
     * 裁决一次工具调用。
     *
     * @param toolId 注册表工具 id（裁决用；合成工具 task 也走本门）
     * @param rawArguments 原始参数 JSON（命令级通配解析用）
     * @param subAgentContext 是否子代理上下文（子代理无交互通道：
     *        ASK 折叠为 DENY 并在归因里说明——子代理应避免高风险工具）
     */
    fun decide(
        toolId: String,
        rawArguments: String,
        subAgentContext: Boolean = false
    ): StandardPermissionDecision {
        // ── 1. DENY 规则短路（最高优先，BYPASS 也不越过显式拒绝）──
        rules.firstOrNull { it.effect == StandardPermissionEffect.DENY && matches(it.pattern, toolId) }
            ?.let {
                return StandardPermissionDecision(
                    StandardPermissionEffect.DENY,
                    "规则 ${it.pattern} → DENY"
                )
            }

        val readOnly = isReadOnlyTool(toolId)

        // ── 2. PLAN 模式硬门：任何写副作用直接拒（规则不可越级）──
        if (mode == StandardPermissionMode.PLAN && !readOnly) {
            return StandardPermissionDecision(
                StandardPermissionEffect.DENY,
                "PLAN 只读模式：$toolId 有写副作用，规划阶段被硬性拒绝"
            )
        }

        // ── 3. 会话记忆（"本会话总允许"）──
        if (sessionAllowedTools.contains(toolId)) {
            return StandardPermissionDecision(
                StandardPermissionEffect.ALLOW,
                "会话记忆：$toolId 本会话已总允许"
            )
        }
        if (isShellTool(toolId)) {
            val command = extractCommand(rawArguments)
            if (command != null) {
                val deniedCmd = commandRules.firstOrNull {
                    it.effect == StandardPermissionEffect.DENY &&
                        commandPatternMatches(it.pattern, command)
                }
                if (deniedCmd != null) {
                    return StandardPermissionDecision(
                        StandardPermissionEffect.DENY,
                        "命令规则 ${deniedCmd.pattern} → DENY"
                    )
                }
                if (sessionAllowedCommands.any { commandPatternMatches(it, command) }) {
                    return StandardPermissionDecision(
                        StandardPermissionEffect.ALLOW,
                        "会话记忆：命令模式命中（$command）"
                    )
                }
                val askCmd = commandRules.firstOrNull {
                    it.effect == StandardPermissionEffect.ASK &&
                        commandPatternMatches(it.pattern, command)
                }
                if (askCmd != null) {
                    return askDecision(subAgentContext, "命令规则 ${askCmd.pattern} → ASK")
                }
                val allowCmd = commandRules.firstOrNull {
                    it.effect == StandardPermissionEffect.ALLOW &&
                        commandPatternMatches(it.pattern, command)
                }
                if (allowCmd != null) {
                    return StandardPermissionDecision(
                        StandardPermissionEffect.ALLOW,
                        "命令规则 ${allowCmd.pattern} → ALLOW"
                    )
                }
            }
        }

        // ── 4. 工具级 ALLOW / ASK 规则（首匹配）──
        val hit = rules.firstOrNull { matches(it.pattern, toolId) }
        if (hit != null) {
            return when (hit.effect) {
                StandardPermissionEffect.ALLOW -> StandardPermissionDecision(
                    StandardPermissionEffect.ALLOW,
                    "规则 ${hit.pattern} → ALLOW"
                )
                StandardPermissionEffect.DENY -> StandardPermissionDecision(
                    StandardPermissionEffect.DENY,
                    "规则 ${hit.pattern} → DENY"
                )
                StandardPermissionEffect.ASK -> askDecision(subAgentContext, "规则 ${hit.pattern} → ASK")
            }
        }

        // ── 5. 模式兜底 ──
        return when (mode) {
            StandardPermissionMode.BYPASS ->
                StandardPermissionDecision(StandardPermissionEffect.ALLOW, "BYPASS 模式兜底 → ALLOW")

            StandardPermissionMode.ACCEPT_EDITS ->
                if (readOnly || isEditTool(toolId)) {
                    StandardPermissionDecision(
                        StandardPermissionEffect.ALLOW,
                        "ACCEPT_EDITS 模式：${if (readOnly) "只读" else "编辑类"} → ALLOW"
                    )
                } else {
                    askDecision(subAgentContext, "ACCEPT_EDITS 模式：非编辑写类 → ASK")
                }

            StandardPermissionMode.DEFAULT ->
                if (readOnly) {
                    StandardPermissionDecision(
                        StandardPermissionEffect.ALLOW,
                        "DEFAULT 模式：只读 → ALLOW"
                    )
                } else {
                    askDecision(subAgentContext, "DEFAULT 模式：写/命令类 → ASK")
                }

            StandardPermissionMode.PLAN ->
                StandardPermissionDecision(
                    StandardPermissionEffect.ALLOW,
                    "PLAN 模式：只读 → ALLOW"
                )
        }
    }

    /** 子代理上下文：ASK 折叠 DENY（无交互通道，宁可保守）。 */
    private fun askDecision(subAgentContext: Boolean, reason: String): StandardPermissionDecision =
        if (subAgentContext) {
            StandardPermissionDecision(
                StandardPermissionEffect.DENY,
                "子代理无询问通道：$reason 折叠为 DENY（子代理应改用低风险工具）"
            )
        } else {
            StandardPermissionDecision(StandardPermissionEffect.ASK, reason)
        }

    // ═══════════════════════ 静态判定（纯函数，可单测）═══════════════════════

    companion object {

        /** 只读工具 id 前缀表（静态——不依赖注册表实例）。 */
        private val READONLY_EXACT = setOf(
            "code_read", "code_grep", "code_glob", "code_check",
            "code_git_status", "code_git_diff", "code_git_log",
            "web_search", "web_fetch",
            "task", "code_todo"
        )

        private val READONLY_PREFIXES = listOf("mcp__", "skill_")

        /** shell 执行类工具 id 集（命令级规则的作用域）。 */
        private val SHELL_TOOLS = setOf("shell_execute", "terminal.exec", "bash")

        /** 编辑/写类工具 id 集（ACCEPT_EDITS 放行面）。 */
        private val EDIT_TOOLS = setOf(
            "code_edit", "code_write", "code_git_commit", "code_git_branch"
        )

        /** 是否只读工具（无环境副作用；todo 只写自有状态文件）。 */
        fun isReadOnlyTool(toolId: String): Boolean =
            toolId in READONLY_EXACT || READONLY_PREFIXES.any { toolId.startsWith(it) }

        /** 是否 shell 执行类工具。 */
        fun isShellTool(toolId: String): Boolean = toolId in SHELL_TOOLS

        /** 是否编辑/写类工具。 */
        fun isEditTool(toolId: String): Boolean = toolId in EDIT_TOOLS

        /**
         * 工具 id 规则匹配（对齐设置层 PermissionRuleMatcher 语义）：
         * - 单独 `*` 匹配一切；
         * - 尾缀 `*` → 前缀匹配（`mcp__` 尾星命中 mcp__github__*）；
         * - 其余精确相等（大小写敏感）。
         */
        fun matches(pattern: String, toolId: String): Boolean {
            if (pattern.isBlank()) return false
            if (pattern == "*") return true
            return if (pattern.endsWith("*")) {
                val prefix = pattern.dropLast(1)
                prefix.isNotEmpty() && toolId.startsWith(prefix)
            } else {
                pattern == toolId
            }
        }

        /**
         * 命令模式匹配：pattern 尾缀 `*` = **命令字符串前缀**匹配
         * （`git status*` 命中 "git status --short"）；无星 = 首词精确。
         *
         * 命令取 [extractCommand] 的首词口径（`git push origin main`
         * 的首词 = `git`，但 `git push*` 能命中——前缀含空格合法）。
         */
        fun commandPatternMatches(pattern: String, command: String): Boolean {
            if (pattern.isBlank() || command.isBlank()) return false
            val normalized = command.trim()
            return if (pattern.endsWith("*")) {
                val prefix = pattern.dropLast(1).trim()
                prefix.isNotEmpty() && normalized.startsWith(prefix)
            } else {
                normalized == pattern || normalized.split(WHITESPACE).firstOrNull() == pattern
            }
        }

        private val WHITESPACE = Regex("\\s+")

        /**
         * 从工具参数 JSON 提取命令字符串（shell 类工具的 command/cmd 字段；
         * 解析失败/字段缺失返回 null——调用方跳过命令级裁决）。
         */
        fun extractCommand(rawArguments: String): String? {
            val trimmed = rawArguments.trim()
            if (!trimmed.startsWith("{")) return null
            // 轻量提取：不引完整 JSON 解析器（code-engine 已有 kotlinx-json，
            // 但此处保持纯字符串——permission 引擎不依赖序列化库）。
            for (key in listOf("\"command\"", "\"cmd\"")) {
                val idx = trimmed.indexOf(key)
                if (idx < 0) continue
                val colon = trimmed.indexOf(':', idx + key.length)
                if (colon < 0) continue
                var i = colon + 1
                while (i < trimmed.length && trimmed[i].isWhitespace()) i++
                if (i >= trimmed.length || trimmed[i] != '"') continue
                val sb = StringBuilder()
                i++
                while (i < trimmed.length && trimmed[i] != '"') {
                    if (trimmed[i] == '\\' && i + 1 < trimmed.length) {
                        sb.append(trimmed[i + 1])
                        i += 2
                    } else {
                        sb.append(trimmed[i])
                        i++
                    }
                }
                if (sb.isNotEmpty()) return sb.toString()
            }
            return null
        }
    }
}
