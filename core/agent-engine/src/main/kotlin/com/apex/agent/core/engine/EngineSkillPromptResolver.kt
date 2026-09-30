package com.apex.agent.core.engine

import com.apex.agent.core.llm.runtime.LlmRequestContext
import com.apex.agent.core.tools.skill.SkillDigest
import com.apex.agent.core.tools.skill.SkillRegistry

/**
 * 请求上下文组装件（自 [ApexAgentEngine] 迁出，God-file 预算腾挪）：
 * - T76 executionTags → LlmRequestContext（tagged）；
 * - #197 双工位 × 渐进披露 —— 系统提示词的技能注入解析（自
 * [ApexAgentEngine.buildSystemPrompt] 迁出，SRP 预算腾挪；模式与
 * [EngineCompressionGate] / [EnginePromptDelegates] 相同：同包顶层扩展 +
 * internal 成员直调，引擎调用点零改动）。
 *
 * 两条注入通道按优先级合成：
 * 1. **渐进披露**（[skillActivation] 非空，主路径）：双层结构 ——
 *    scope 过滤后的技能目录（一行摘要）+ 仅激活技能的全文注入；
 *    激活集外技能只经目录暴露，由模型调 skill_activate 按需装备。
 * 2. **legacy 全量**（[skillActivation] 为空，单测/子代理）：按
 *    [AgentConfig.skillScope] 作用域全量注入。
 *
 * [AgentConfig.skillScope]（"agent" | "coding" | "all"）在两条通道上
 * 都生效：市场分级、斜杠菜单与提示词注入三处同源过滤。
 */
internal val ApexAgentEngine.skillScopeOrNull: String?
    get() = config.skillScope.takeIf { it.isNotBlank() }

/** 激活技能的全文注入（渐进披露主路径）或 scope 过滤后的全量注入（legacy）。 */
internal val ApexAgentEngine.skillPromptInjections: List<String>
    get() {
        val registry: SkillRegistry = skillRegistry ?: return emptyList()
        return if (skillActivation != null) {
            registry.getActivePromptInjections(skillActivation.activeIds.value, skillScopeOrNull)
        } else {
            registry.getPromptInjections(skillScopeOrNull)
        }
    }

/** 技能目录摘要（仅渐进披露路径；legacy 通道返回空，保持旧行为）。 */
internal val ApexAgentEngine.skillCatalogDigests: List<SkillDigest>
    get() {
        val registry: SkillRegistry = skillRegistry ?: return emptyList()
        return if (skillActivation != null) registry.getSkillDigests(skillScopeOrNull) else emptyList()
    }

/** T76 — executionTags（taskId/stepId）填入 LlmRequestContext；未接线时原样返回。 */
internal fun ApexAgentEngine.tagged(ctx: LlmRequestContext): LlmRequestContext {
    val tags = executionTags ?: return ctx
    return ctx.copy(taskId = tags.first, stepId = tags.second)
}
