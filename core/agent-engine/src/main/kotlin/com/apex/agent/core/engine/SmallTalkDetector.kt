package com.apex.agent.core.engine

/**
 * 闲聊问候检测器 —— 首轮问候极简回复的判定面。
 *
 * 用户反馈根因：会话第一条消息发「你好」，Agent 回一大段编程/能力介绍。
 * 修复分两层（本类是动态层的数据面）：
 *  - 静态层：[EnginePrompts] 的 Conversational Openness 段——所有轮次生效，
 *    约束「问候 / 闲聊 / 无任务寒暄」的回复形状（短、口语、不倒能力清单）；
 *  - 动态层：本检测器在 [ApexAgentEngine.execute] 入口判定「本轮是会话首条
 *    用户消息且为纯问候」时置位 firstTurnGreeting，系统提示词追加
 *    FIRST_TURN_GREETING 硬约束段（一句话回应，见其 KDoc）。
 *
 * 判定刻意保守（宁可漏判不可误判）：把「你好，帮我写个脚本」误判成纯问候
 * 会吞掉用户的真实任务——后果比漏掉一次极简回复严重得多。因此：
 *  1. 原文长度必须 ≤ 20 字符（问候语不会长）；
 *  2. 归一化（剥离标点 / 空白 / emoji / 数字，拉丁小写化）后的剩余字符，
 *     必须能被「问候词表 + 语气词表」完全分解（动态规划切段）；
 *  3. 至少含一个问候词（纯语气词「呀呀呀」不算问候）。
 *
 * 纯函数、无状态、线程安全；不产生日志与副作用。
 */
internal object SmallTalkDetector {

    /** 原文长度上限：超过即认为携带了真实内容，交由静态层约束即可。 */
    private const val MAX_RAW_LENGTH = 20

    /** 问候词表（中文 + 英文小写）。 */
    private val GREETINGS = setOf(
        "你好", "您好", "嗨", "哈喽", "哈罗", "嘿", "早", "早安", "早呀",
        "中午好", "下午好", "晚上好", "晚安", "在吗", "在么", "你好呀", "哈哈", "哈哈哈",
        "hello", "hi", "hey", "yo", "hiya", "sup", "greetings", "hola", "hallo"
    )

    /** 语气词表（可重复、可独立出现，但不单独构成问候）。 */
    private val PARTICLES = setOf(
        "呀", "啊", "哦", "哈", "呢", "嘛", "咯", "哒", "哟", "呦", "诶", "唔", "嗯"
    )

    /** 可分解词典：问候 + 语气词（DP 切段用；问候词优先按长度降序尝试）。 */
    private val DICT = (GREETINGS + PARTICLES).sortedByDescending { it.length }

    /**
     * 判定文本是否为「纯问候」。
     *
     * 示例：
     *  - 「你好」「你好呀!」「hi~」「在吗」「晚上好」「嗨嗨」→ true
     *  - 「你好，帮我写个脚本」「你会什么」「帮我查下天气」→ false
     */
    fun isGreetingOnly(text: String): Boolean {
        val raw = text.trim()
        if (raw.isEmpty() || raw.length > MAX_RAW_LENGTH) return false
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return false
        val tokens = segment(normalized) ?: return false
        // 规则 3：至少一个问候词（纯语气词不算）
        return tokens.any { it in GREETINGS }
    }

    /** 归一化：只保留 CJK 与拉丁字母（小写化），其余（标点/emoji/数字/空白）剥离。 */
    private fun normalize(text: String): String = buildString {
        for (ch in text) {
            val lower = ch.lowercaseChar()
            when {
                lower in 'a'..'z' -> append(lower)
                Character.UnicodeScript.of(lower.code) == Character.UnicodeScript.HAN -> append(lower)
                // 其余全部丢弃
            }
        }
    }

    /**
     * 动态规划切段：能否把 normalized 完整切成词典词。
     * 能则返回切分结果（含问候词标记所需），不能返回 null。
     * 词表极小（<40 词），贪心 + 回溯代价可忽略；不用正则避免回溯灾难。
     */
    private fun segment(normalized: String): List<String>? {
        val n = normalized.length
        if (n == 0) return emptyList()
        // reachable[i] = 前缀 [0, i) 可被完整切分；choice[i] = 达成该前缀的最后一个词
        val reachable = BooleanArray(n + 1)
        val choice = arrayOfNulls<String>(n + 1)
        reachable[0] = true
        for (i in 0 until n) {
            if (!reachable[i]) continue
            for (word in DICT) {
                val end = i + word.length
                if (end <= n && !reachable[end] && normalized.startsWith(word, i)) {
                    reachable[end] = true
                    choice[end] = word
                }
            }
        }
        if (!reachable[n]) return null
        // 回溯还原词序列
        val tokens = mutableListOf<String>()
        var idx = n
        while (idx > 0) {
            val word = choice[idx] ?: return null
            tokens.add(word)
            idx -= word.length
        }
        return tokens.asReversed()
    }
}
