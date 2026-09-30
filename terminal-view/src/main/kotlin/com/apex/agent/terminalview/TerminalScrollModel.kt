package com.apex.agent.terminalview

/**
 * T88（2-a）：Termux `mTopRow` 语义的滚动模型（纯 JVM —— 单测锁定行为）。
 *
 * ## 模型语义
 *
 * - **topRow ∈ [-maxScrollUp, 0]**：0 = 贴底（显示最新 `viewRows` 行）；
 *   负值 = 向上滚 |topRow| 行（显示历史）。
 * - **合并网格**：`scrollback（旧→新）+ 可见屏`，高 `gridRows`；视口显示
 *   `[firstVisibleRow, firstVisibleRow + viewRows)`。
 * - **follow-bottom**（「命令间大段空白」的根治）：内容在底部增长时，**只有原本
 *   贴底才继续贴底**；已上滚阅读历史时不偷位置 —— 增量从底部推入，老行下标
 *   平移，`topRow` 随之递减，用户看到的行纹丝不动。
 * - **scrollForNewInput**：键入/回车时无条件跳底（Termux 同款 —— 用户敲命令
 *   意味着他要看最新输出）。
 * - **容量淘汰**：宿主可能只送 `scrollback.size < scrollbackTotal` 的最近 N 行；
 *   本模型只保证「滚得动的都是已送达的行」（clamp 用合并高度，而非 scrollbackTotal
 *   —— 滚到未送达的行只会显示空白）。
 *
 * 事件驱动（无 Handler/线程）：View 把「内容变了/滚了/fling 结束」喂进来，本类
 * 纯函数式回答 clamp/范围/是否贴底。
 */
class TerminalScrollModel(
    /** 视口可容纳行数。 */
    private var viewRows: Int
) {
    /** 当前 topRow（0=贴底；负=上滚）。 */
    var topRow: Int = 0
        private set

    /** 合并网格高（scrollback.size + snapshot.rows）。 */
    var gridRows: Int = viewRows
        private set

    /** 可上滚的最大行数（gridRows - viewRows，≥0）。 */
    val maxScrollUp: Int get() = (gridRows - viewRows).coerceAtLeast(0)

    /** 是否贴底（topRow == 0 —— Termux `mTopRow == 0`）。 */
    val isAtBottom: Boolean get() = topRow == 0

    /** 视口首行（合并网格下标；clamp 到合法范围）。 */
    val firstVisibleRow: Int
        get() = (gridRows - viewRows + topRow).coerceIn(0, (gridRows - 1).coerceAtLeast(0))

    /** 可视行范围（合并网格下标，含头含尾）。 */
    fun visibleRange(): IntRange {
        val first = firstVisibleRow
        val last = (first + viewRows - 1).coerceAtMost(gridRows - 1)
        return if (last < first) first..first else first..last
    }

    /** clamp 当前 topRow 到 [-maxScrollUp, 0]（网格尺寸变化后调用）。返回是否变化。 */
    fun clamp(): Boolean {
        val clamped = topRow.coerceIn(-maxScrollUp, 0)
        if (clamped != topRow) {
            topRow = clamped
            return true
        }
        return false
    }

    /**
     * 滚动 dy 行：**正 = 内容向下滚（朝最新/底）**，负 = 朝历史。clamp 边界；
     * 返回是否实际滚动（边界外的惯性滚动会被吃掉 —— View 据此停 fling）。
     */
    fun scrollBy(dyRows: Int): Boolean {
        if (dyRows == 0) return false
        val target = (topRow + dyRows).coerceIn(-maxScrollUp, 0)
        if (target == topRow) return false
        topRow = target
        return true
    }

    /** 跳底。返回是否发生了滚动（已贴底则 false —— 宿主免抖动）。 */
    fun snapToBottom(): Boolean {
        if (topRow == 0) return false
        topRow = 0
        return true
    }

    /**
     * 内容增长通知（快照 submit 时调用）。
     *
     * @param deltaRows 合并网格高度增量（= 新 scrollback.size+rows − 旧值；可为负
     *        —— 清屏/alt-screen 切换）
     * @param wasAtBottom 增长前是否贴底（**调用方必须先取本值再更新网格**）
     * @return 是否需要跳底（wasAtBottom && topRow≠0 的场景不存在 —— 简化语义：
     *         贴底 → topRow=0 恒成立；返回值仅供回调去重）
     */
    fun onContentGrew(deltaRows: Int, wasAtBottom: Boolean): Boolean {
        // 老行下标平移 deltaRows → topRow 同步平移（负向 = 上滚补偿），保持
        // 「用户看到的行」不变；贴底分支不动（0 恒为新内容的底）。
        if (wasAtBottom) {
            topRow = 0
            return false
        }
        if (deltaRows != 0) {
            topRow = (topRow - deltaRows).coerceIn(-maxScrollUp, 0)
        }
        return clamp()
    }

    /** 键入新输入 → 跳底（Termux `scrollForNewInput`）。返回是否滚动。 */
    fun scrollForNewInput(): Boolean = snapToBottom()

    /**
     * 网格/视口尺寸变化（resize 握手完成、字号变化重排后）。贴底保持贴底；
     * 否则只 clamp（Termux resize 不偷阅读位置）。
     */
    fun onGridResized(newGridRows: Int, newViewRows: Int): Boolean {
        gridRows = newGridRows.coerceAtLeast(1)
        viewRows = newViewRows.coerceAtLeast(1)
        return clamp()
    }

    /** 用当前快照同步网格高度（内容更新但尺寸语义未变的常规 submit 路径）。 */
    fun updateGridRows(newGridRows: Int) {
        gridRows = newGridRows.coerceAtLeast(1)
        clamp()
    }

    /**
     * 滚动位置 → 滚动条刻度（0..1；无滚动量返回 null）。供渲染器画 thumb。
     */
    fun scrollbarFraction(): Float? {
        if (maxScrollUp == 0) return null
        return (-topRow.toFloat() / maxScrollUp).coerceIn(0f, 1f)
    }

    /** 惯性滚动余量（fling 启动前判断是否值得 fling）。 */
    fun canScroll(): Boolean = maxScrollUp > 0

    /** 是否在顶（滚到最老一行 —— 上边缘渐隐判定用）。 */
    val isAtTop: Boolean get() = topRow == -maxScrollUp && maxScrollUp > 0

    companion object {
        /**
         * 像素 dy → 行数（手势滚动换算；向下取整保留亚行余量给 fling）。
         */
        fun rowsForDelta(deltaYPx: Float, cellHeightPx: Float): Int {
            if (cellHeightPx <= 0f || !deltaYPx.isFinite() || deltaYPx == 0f) return 0
            return (deltaYPx / cellHeightPx).toInt()
        }
    }
}
