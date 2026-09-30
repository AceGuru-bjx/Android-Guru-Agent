package com.apex.agent.core.engine

/**
 * 聊天信号检测器 —— 共情引擎与澄清引导的统一数据面（Round 2）。
 *
 * ## 解决的问题
 *
 *  1. **情绪盲区**：用户带着情绪说话（难过/焦虑/愤怒/开心），旧提示词
 *     只有任务向策略，模型容易上来就办任务、忽略人的感受，或对倾诉型
 *     消息回一大段教程式说教；
 *  2. **模糊求助灾难**：用户发「怎么办」「帮帮我」「不行了」这类**无宾语
 *     短求助**，模型倾向猜一个主题然后倾倒长篇泛泛而论——聊天体验杀手。
 *
 * 本检测器在 [ApexAgentEngine.execute] 入口运行，产出 [ChatSignal]：
 *  - 情绪命中 → EnginePrompts 注入 "## Emotional Attunement (THIS TURN)"
 *    （先处理心情、再处理任务；正向情绪同频庆祝）；
 *  - 模糊命中 → 注入 "## Vague Request (THIS TURN)"（先一个聚焦追问 +
 *    2-4 个具体选项，禁止猜主题写长文）。
 *
 * ## 判定哲学（与 [SmallTalkDetector] 同源）
 *
 * 刻意保守，**宁可漏判不可误判**：
 *  - 把「写一篇安慰朋友的文案」误判成用户本人在难过，比漏判一次共情
 *     伤害大得多——所以情绪检测有一层**任务指令护栏**（帮我写/翻译成/
 *     生成一/写文案…命中即跳过情绪判定，那是内容创作不是情绪表达）；
 *  - 模糊求助用**归一化后整串精确匹配**（词表闭集），而非子串包含：
 *     「我的电脑蓝屏了怎么办」不命中（有具体宾语），「怎么办」命中；
 *  - 情绪冲突（同时命中多类）取命中数最多者，平票按
 *     低落 > 焦虑 > 愤怒 > 欢快（负向优先：共情误报无害，漏报可惜）。
 *
 * ## 可见性
 *
 * public：app 层 ChatMemoryPipeline 复用 [detect] 的情绪结果做「用户
 * 近况」情绪基调沉淀（与引擎同一套判定口径，不另造轮子）。
 *
 * 纯函数、无状态、线程安全；不产生日志与副作用。
 */

/** 可检测情绪（NONE = 无命中/被护栏拦截，不注入适配段）。 */
public enum class ChatEmotion { NONE, SAD, ANXIOUS, ANGRY, JOYFUL }

/**
 * 一轮用户消息的聊天信号。
 *
 * @param emotion 情绪四分类（NONE = 本轮无情绪信号）
 * @param emotionHits 胜出类别的命中词数（0 = 无命中；≥ 2 视为情绪较强）
 * @param vague 是否为「无宾语短求助」（归一化整串精确命中词表）
 */
public data class ChatSignal(
    val emotion: ChatEmotion,
    val emotionHits: Int,
    val vague: Boolean
)

public object ChatSignalDetector {

    /** 情绪检测的原文长度上限：超长消息几乎必然是任务描述而非情绪表达。 */
    private const val MAX_EMOTION_LENGTH = 200

    /** 模糊求助的原文长度上限（词表内的整串都很短）。 */
    private const val MAX_VAGUE_LENGTH = 24

    /** 低落类词表（含疲惫语义：倾诉场景高频）。 */
    private val SAD_WORDS = listOf(
        "难过", "伤心", "心碎", "失落", "沮丧", "低落", "想哭", "泪目", "哭死",
        "绝望", "委屈", "心累", "好累", "累死", "累坏了", "崩溃", "崩了",
        "我emo", "emo了", "emo中", "抑郁"
    )

    /** 焦虑类词表。 */
    private val ANXIOUS_WORDS = listOf(
        "焦虑", "压力大", "压力好大", "压力太大", "紧张", "心慌", "好慌", "慌死",
        "失眠", "睡不着", "睡不好", "着急", "焦头烂额", "来不及", "提心吊胆",
        "忐忑", "发愁", "愁死", "担心死"
    )

    /** 愤怒类词表。 */
    private val ANGRY_WORDS = listOf(
        "生气", "气死", "气人", "气疯", "气炸", "愤怒", "恼火", "火大",
        "烦死了", "烦死", "受够了", "受够", "暴怒", "无语死"
    )

    /** 欢快类词表。 */
    private val JOYFUL_WORDS = listOf(
        "开心", "太好了", "好开心", "高兴", "兴奋", "太棒", "超棒", "棒呆",
        "真棒", "爽", "舒坦", "笑死", "哈哈哈", "绝了", "上岸", "圆满", "太赞",
        "超爱", "爱死"
    )

    /**
     * 任务指令护栏：命中任一即跳过情绪判定——这些是「替我创作/加工内容」
     * 的祈使句式，其中的情绪词是**素材**不是用户本人的状态。
     */
    private val TASK_GUARDS = listOf(
        "帮我写", "写一篇", "写个", "写一段", "帮我翻译", "翻译成", "翻译一",
        "生成一", "写文案", "写代码", "写作文", "改写", "润色", "仿写", "续写"
    )

    /**
     * 模糊求助词表（**归一化整串精确匹配**）。
     * 收录原则：求助信号强、且不含任何具体宾语的完整口语短句。
     */
    private val VAGUE_CRIES = setOf(
        "怎么办", "咋办", "怎么办呢", "怎么办啊", "我该怎么办", "我该怎么办呢",
        "不知道怎么办", "不知道咋办", "帮帮我", "帮帮我吧", "求求了",
        "帮我看看", "帮我看下", "帮我看一下", "帮我想想", "救救我", "急急急",
        "不行了", "不行了呀", "不行了怎么办", "出问题了", "出问题了怎么办", "有问题", "有问题了",
        "坏了坏了", "坏事了", "搞不定", "搞不定了", "弄不好", "弄不好了",
        "失败了", "失败了吗", "没辙了", "没办法了", "没办法", "怎么整", "咋整",
        "这可怎么办", "这怎么办", "有什么建议", "有什么建议吗", "有什么办法",
        "有什么办法吗", "该怎么解决", "怎么解决", "怎么处理", "咋处理",
        "完蛋了", "完了完了", "麻了", "我麻了"
    )

    /**
     * 检测一轮用户消息的聊天信号。
     *
     * 示例：
     *  - 「我今天真的好难过」→ SAD / vague=false
     *  - 「帮我写一段安慰失恋朋友的文案」→ 护栏命中 → NONE
     *  -「怎么办」→ vague=true；「我电脑蓝屏了怎么办」→ vague=false
     */
    public fun detect(text: String): ChatSignal {
        val raw = text.trim()
        val emotion = detectEmotion(raw)
        return ChatSignal(
            emotion = emotion?.first ?: ChatEmotion.NONE,
            emotionHits = emotion?.second ?: 0,
            vague = isVagueCry(raw)
        )
    }

    /**
     * 情绪四分类。返回 (胜出类别, 该类别命中数)；null = 无命中或被护栏
     * 拦截。多类并存时命中数多者胜；平票按 负向优先 序（SAD > ANXIOUS
     * > ANGRY > JOYFUL）。
     */
    private fun detectEmotion(raw: String): Pair<ChatEmotion, Int>? {
        if (raw.isEmpty() || raw.length > MAX_EMOTION_LENGTH) return null
        if (raw.contains("```")) return null
        if (TASK_GUARDS.any { raw.contains(it) }) return null
        val candidates = listOf(
            ChatEmotion.SAD to SAD_WORDS.count { raw.contains(it) },
            ChatEmotion.ANXIOUS to ANXIOUS_WORDS.count { raw.contains(it) },
            ChatEmotion.ANGRY to ANGRY_WORDS.count { raw.contains(it) },
            ChatEmotion.JOYFUL to JOYFUL_WORDS.count { raw.contains(it) }
        ).filter { it.second > 0 }
        if (candidates.isEmpty()) return null
        // 负向优先：列表序即平票优先级，maxWith 稳定取先出现的
        return candidates.maxWith(compareBy { it.second })
    }

    /**
     * 模糊求助判定：归一化（剥离空白与标点，含全角）后**整串**精确匹配
     * [VAGUE_CRIES]。携带任何具体宾语的变体（长度/内容超出词表）自然
     * 落空——这正是保守性的来源。
     */
    private fun isVagueCry(raw: String): Boolean {
        if (raw.isEmpty() || raw.length > MAX_VAGUE_LENGTH) return false
        val normalized = buildString {
            for (ch in raw) {
                when {
                    ch.isWhitespace() -> Unit
                    Character.isLetterOrDigit(ch) -> append(ch)
                    else -> Unit
                }
            }
        }
        return normalized in VAGUE_CRIES
    }
}
