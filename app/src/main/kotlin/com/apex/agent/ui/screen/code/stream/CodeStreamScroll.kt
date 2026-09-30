package com.apex.agent.ui.screen.code.stream

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow

/**
 * # Code Stream Scroll — 时间轴智能滚动锚
 *
 * 移植 Agent 聊天页验证过的锚定模式（isAtBottom 派生 + 阅读模式保护 +
 * 流式即时跟随），封装为可复用状态类。
 *
 * ## 语义（与 AgentChatScreen 对齐 + 本轮补强）
 *
 * - [isAtBottom]：距底 <150px 派生态（空列表视为在底）；
 * - [userScrolledUp]：阅读模式——snapshotFlow 监听「索引递减 **或** 同索引
 *   内 offset 递减」（长条目内部上翻也算上翻——原实现只看索引，长气泡
 *   中部翻页会被 auto-follow 拽回底部）；程序化滚动置抑制标志防误判；
 * - [maybeAutoScroll]：流式期间即时 scrollToItem（跟手、无动画叠加），
 *   收尾 animate；
 * - [followTick]：内容字符数 / [stepChars] 节流档位（key 不含文本本身
 *   → 不会每 token 重启 effect）。
 */
class StreamScrollAnchor(
    val listState: LazyListState,
    private val stepChars: Int = 200
) {
    /** 距底 <150px（空列表视为在底）。 */
    val isAtBottom by derivedStateOf {
        val layoutInfo = listState.layoutInfo
        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
        if (lastVisible == null) true
        else layoutInfo.viewportSize.height - (lastVisible.offset + lastVisible.size) < BOTTOM_EPSILON_PX
    }

    /** 用户主动上翻后置位（阅读模式），回底复位。 */
    var userScrolledUp: Boolean = false
        private set

    /** 程序化滚动抑制：snapToLast/animateToLast 期间的方向变化不算用户上翻。 */
    @Volatile
    private var programmaticScroll = false

    /** 监听滚动方向（LaunchedEffect 里 collect）：索引或条目内偏移递减 ⇔ 用户上翻。 */
    suspend fun observe() {
        var prevIndex = listState.firstVisibleItemIndex
        var prevOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            // 8px 容差：layout 期间的亚像素抖动不算方向
            if (!programmaticScroll &&
                (index < prevIndex || (index == prevIndex && offset < prevOffset - OFFSET_TOLERANCE_PX))
            ) {
                userScrolledUp = true
            }
            prevIndex = index
            prevOffset = offset
        }
    }

    /** 是否应 auto-follow：在底部附近 或 未进入阅读模式。 */
    fun shouldFollow(): Boolean = isAtBottom || !userScrolledUp

    /** 即时跟随末项（流式期间；时间轴末尾有 1dp 尾哨兵条目 → 等效贴底）。 */
    suspend fun snapToLast() {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            programmaticScroll = true
            try {
                listState.scrollToItem(total - 1)
            } finally {
                programmaticScroll = false
            }
        }
    }

    /** 动画回底（FAB / 收尾）。 */
    suspend fun animateToLast() {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            programmaticScroll = true
            try {
                listState.animateScrollToItem(total - 1)
            } finally {
                programmaticScroll = false
            }
        }
        userScrolledUp = false
    }

    /** 内容长度节流档位（effect key 用）。 */
    fun followTick(contentLength: Int): Int = contentLength / stepChars

    private companion object {
        const val BOTTOM_EPSILON_PX = 150
        const val OFFSET_TOLERANCE_PX = 8
    }
}

/**
 * 锚定装配：挂载观察协程 + 结构变化自动滚动。
 *
 * @param itemCountKey 列表结构变化 key（条目数）
 * @param contentTickKey 内容节流档位 key（字符数/200）
 * @param isStreaming 流式中（即时跟随）与收尾（动画）的分野
 */
@Composable
internal fun rememberStreamAnchor(
    listState: LazyListState,
    itemCountKey: Int,
    contentTickKey: Int,
    isStreaming: Boolean
): StreamScrollAnchor {
    val anchor = remember(listState) { StreamScrollAnchor(listState) }

    LaunchedEffect(listState) { anchor.observe() }

    // 结构变化（新条目）→ 跟随
    LaunchedEffect(itemCountKey, isStreaming) {
        if (itemCountKey > 0 && anchor.shouldFollow()) {
            if (isStreaming) anchor.snapToLast() else anchor.animateToLast()
        }
    }
    // 内容增长（同条目变长）→ 节流跟随（key 含 isStreaming：恢复会话后
    // 流式开启而字符档位未变时也要启动跟随）
    LaunchedEffect(contentTickKey, isStreaming) {
        if (isStreaming && itemCountKey > 0 && anchor.shouldFollow()) {
            anchor.snapToLast()
        }
    }
    return anchor
}
