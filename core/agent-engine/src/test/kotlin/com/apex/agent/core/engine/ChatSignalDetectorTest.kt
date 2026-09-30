package com.apex.agent.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 2 — [ChatSignalDetector] 单元测试。
 *
 * 锁定四组不变量（判定哲学：宁可漏判不可误判）：
 * 1. 情绪四分类命中 + 命中计数；
 * 2. 任务指令护栏：创作型句式（帮我写/翻译成…）里的情绪词是素材，
 *    不得误判为用户本人情绪；
 * 3. 模糊求助：归一化整串精确命中；携带具体宾语的变体不命中；
 * 4. 平票负向优先：多类情绪并存时低落 > 焦虑 > 愤怒 > 欢快。
 *
 * 纯 JVM JUnit4，与 SmallTalkDetector 同风格（无依赖、无副作用）。
 */
class ChatSignalDetectorTest {

    // ═══ 情绪命中 ═════════════════════════════════════════════════

    @Test
    fun `sad message detects SAD with hits`() {
        val signal = ChatSignalDetector.detect("我今天真的很难过，还有点想哭")
        assertEquals(ChatEmotion.SAD, signal.emotion)
        assertTrue("命中数 ≥ 2（难过 + 想哭）", signal.emotionHits >= 2)
        assertFalse(signal.vague)
    }

    @Test
    fun `anxious message detects ANXIOUS`() {
        assertEquals(
            ChatEmotion.ANXIOUS,
            ChatSignalDetector.detect("睡不着，明天演示我好紧张").emotion
        )
    }

    @Test
    fun `angry message detects ANGRY`() {
        assertEquals(
            ChatEmotion.ANGRY,
            ChatSignalDetector.detect("气死我了，真的受够了").emotion
        )
    }

    @Test
    fun `joyful message detects JOYFUL`() {
        val signal = ChatSignalDetector.detect("太好了！我面试过了，好开心！")
        assertEquals(ChatEmotion.JOYFUL, signal.emotion)
        assertTrue(signal.emotionHits >= 2)
    }

    @Test
    fun `neutral message detects NONE`() {
        assertEquals(ChatEmotion.NONE, ChatSignalDetector.detect("帮我看看这道题怎么解").emotion)
    }

    // ═══ 任务指令护栏 ═════════════════════════════════════════════

    @Test
    fun `writing task with emotion words is guarded to NONE`() {
        assertEquals(
            ChatEmotion.NONE,
            ChatSignalDetector.detect("帮我写一段安慰失恋朋友的文案，要温暖一点").emotion
        )
    }

    @Test
    fun `translation task is guarded`() {
        assertEquals(
            ChatEmotion.NONE,
            ChatSignalDetector.detect("把这句翻译成英文：我今天很难过").emotion
        )
    }

    @Test
    fun `code fence content is guarded`() {
        assertEquals(
            ChatEmotion.NONE,
            ChatSignalDetector.detect("```python\nprint('我很开心')\n```").emotion
        )
    }

    @Test
    fun `overlong text is guarded`() {
        val long = "我很难过".repeat(60)
        assertEquals(ChatEmotion.NONE, ChatSignalDetector.detect(long).emotion)
    }

    // ═══ 模糊求助（归一化整串精确匹配）════════════════════════════

    @Test
    fun `bare cry for help is vague`() {
        assertTrue(ChatSignalDetector.detect("怎么办").vague)
        assertTrue(ChatSignalDetector.detect("怎么办？？").vague)
        assertTrue(ChatSignalDetector.detect(" 帮帮我！ ").vague)
        assertTrue(ChatSignalDetector.detect("不行了，怎么办").vague)
    }

    @Test
    fun `request with concrete object is NOT vague`() {
        assertFalse("有宾语：电脑蓝屏", ChatSignalDetector.detect("我电脑蓝屏了怎么办").vague)
        assertFalse("有日期限定自然更长", ChatSignalDetector.detect("明天下午3点的会议怎么办").vague)
        assertFalse("普通任务句", ChatSignalDetector.detect("帮我看看这个报错是什么意思").vague)
    }

    @Test
    fun `vague cry with emotion both flags can coexist`() {
        // 「完了完了」本身在模糊词表；情绪侧 NONE —— 两信号独立判定
        val signal = ChatSignalDetector.detect("完了完了")
        assertTrue(signal.vague)
    }

    // ═══ 平票负向优先 ═════════════════════════════════════════════

    @Test
    fun `tie between negative and positive resolves to negative`() {
        // 低落(难过) + 欢快(开心) 平票 1:1 → 负向优先 SAD 胜出
        assertEquals(
            ChatEmotion.SAD,
            ChatSignalDetector.detect("有点难过，但听说你很开心").emotion
        )
    }

    @Test
    fun `more hits wins regardless of priority`() {
        // 开心×2 vs 难过×1 → 计数优先，JOYFUL 胜出
        assertEquals(
            ChatEmotion.JOYFUL,
            ChatSignalDetector.detect("好开心啊，太开心了，虽然有点难过").emotion
        )
    }

    // ═══ 混合与边界 ═══════════════════════════════════════════════

    @Test
    fun `empty and blank text produce clean NONE signal`() {
        val signal = ChatSignalDetector.detect("")
        assertEquals(ChatEmotion.NONE, signal.emotion)
        assertEquals(0, signal.emotionHits)
        assertFalse(signal.vague)
    }

    @Test
    fun `pure greeting is not vague and carries no emotion`() {
        val signal = ChatSignalDetector.detect("你好")
        assertEquals(ChatEmotion.NONE, signal.emotion)
        assertFalse(signal.vague)
    }
}
