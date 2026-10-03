package com.apex.agent.ui.screen.terminal

/**
 * 交互行缓冲（黑白名单交互拦截用）—— 镜像 shell readline 当前行文本。
 * P1-2：从 `TerminalViewModel` 的裸 `StringBuilder` 收口为独立类型。
 *
 * ## 为什么需要它
 *
 * 命令黑白名单只在「回车提交时刻」检查，而检查需要知道 readline 当前行里
 * 已经敲了什么 —— IME 文本是流式进来的（每字符一次回调），所以 VM 侧必须
 * 维护一份「行镜像」。镜像的正确性依赖一个不变式：
 *
 * > **只有「纯字符输入」和「回退删除」是确定性变更；其余一切输入路径
 * > （特殊键/控制字符/粘贴/会话切换）都会让行状态不可知。**
 *
 * 旧实现里这个不变式散落在 `sendInput` / `sendKey` / `sendControlChar` /
 * `sendHardwareKey` / `pasteText` / `selectSession` / `closeSession` 的
 * 七八处 `pendingLine.setLength(0)` —— 任何新增输入路径忘掉一处，就会复现
 * 「切到 B 会话按空回车，却拿 A 会话残留的旧命令做黑白名单检查」这类误拦。
 * 本类把「什么操作会破坏镜像」集中到一处，新增输入路径只需要选择正确的
 * [feedForSubmit] / [backspace] / [reset] 语义，单元测试也只需要测这一个类。
 *
 * ## 拦截语义（沿用旧行为）
 *
 * 拦截 = 不写入回车，命令停留在 readline 未提交状态 —— 镜像**保持不变**
 * （shell 行里的文本没变，镜像也不该变），用户改完命令再回车会重新检查。
 */
internal class LineMirror {

    private val buf = StringBuilder()

    /** 当前镜像文本（即 shell readline 行的推测内容）。 */
    override fun toString(): String = buf.toString()

    /**
     * 吸收一段用户文本输入：
     *  - **无换行**（\r / \n）：确定性追加进镜像，返回 null；
     *  - **含换行**：返回「待检查的命令候选」（换行前的镜像 + 本段前缀，
     *    trim 后），**镜像暂不修改** —— 调用方做黑白名单检查：
     *      - 命中拦截 → 直接丢弃整段写入（什么都不用调，镜像保持原状）；
     *      - 放行 → 调 [commitSubmit] 完成镜像提交。
     */
    fun feedForSubmit(text: String): String? {
        val newlineIdx = text.indexOfFirst { it == '\r' || it == '\n' }
        if (newlineIdx < 0) {
            buf.append(text)
            return null
        }
        return (buf.toString() + text.substring(0, newlineIdx)).trim()
    }

    /**
     * 放行提交（仅当 [feedForSubmit] 返回非 null 且命令通过黑白名单后调用）：
     * 行缓冲重置，换行之后的剩余字符属于下一行镜像。
     */
    fun commitSubmit(text: String) {
        reset()
        val newlineIdx = text.indexOfFirst { it == '\r' || it == '\n' }
        if (newlineIdx >= 0) {
            val rest = text.substring(newlineIdx + 1)
            if (rest.isNotEmpty()) buf.append(rest)
        }
    }

    /** 退格（BACKSPACE / DEL —— readline 行删一个字符，镜像同步退格）。 */
    fun backspace() {
        if (buf.isNotEmpty()) buf.setLength(buf.length - 1)
    }

    /**
     * 镜像失效（行状态不可知）：方向/历史召回/Ctrl 组合/粘贴/会话切换等。
     * 失效后下次回车不检查（宁可漏检不误拦）。
     */
    fun reset() {
        buf.setLength(0)
    }
}
