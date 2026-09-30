package com.apex.agent.terminalview

import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest

/**
 * T88（2-a）：终端 IME 桥（Termux `TerminalInputConnection` 的等价物）。
 *
 * ## 关键决策
 *
 *  - **组合文本直通**：`setComposingText` 的增量实时下发（不缓存整词）——
 *    Gboard/百度/讯飞常把整词拖到空格才提交；旧渲染器实测证明「组合态不下发
 *    = 字打不进去」。提交（commit）时由输入法自然走 `deleteSurroundingText`
 *    + `commitText`，旧组合文本被退格收回 —— 与真实终端一致；
 *  - **可编辑缓冲只留 1 行**：不镜像终端滚回 —— IME 只需要光标/删除的语义，
 *    大缓冲会撑爆内存并让 IME 误触发滚动；
 *  - **`\n` 归一为 `\r`**（终端 Enter 语义）；
 *  - `sendKeyEvent` 走 View 的硬件键路径（onKeyDown 的完整映射，保证 IME 送的
 *    Enter/Delete 与硬件键盘行为一致）；
 *  - inputType 用 `TYPE_CLASS_TEXT + NO_SUGGESTIONS`（而非 Termux 早期的
 *    TYPE_NULL —— 部分中文 IME 在 TYPE_NULL 下拒绝上屏；无建议避免输入法
 *    自作主张改写终端命令），imeOptions 去全屏/抽取，Enter 走原生键。
 */
class TerminalInputConnection(private val terminalView: TerminalView) :
    BaseInputConnection(terminalView, false) {

    /** 最小可编辑缓冲（IME 删除/光标语义的锚点；只存当前未提交行）。 */
    private val tinyBuffer: SpannableStringBuilder = SpannableStringBuilder().apply {
        Selection.setSelection(this, 0)
    }

    override fun getEditable(): Editable {
        // 只返回当前行的小缓冲 —— 绝不镜像终端滚回（内存与 IME 行为双保险）
        return tinyBuffer
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        val raw = text?.toString() ?: return super.commitText(text, newCursorPosition)
        if (raw.isEmpty()) return true
        terminalView.handleImeText(raw)
        // 行缓冲同步（IME 期望 commit 后文本进入编辑框）
        tinyBuffer.clear()
        tinyBuffer.append(raw.replace('\n', ' ').replace('\r', ' '))
        Selection.setSelection(tinyBuffer, tinyBuffer.length)
        return true
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        val raw = text?.toString() ?: return super.setComposingText(text, newCursorPosition)
        // 组合增量直通（Termux 语义 —— 见类 KDoc「关键决策」）
        if (raw.isNotEmpty()) terminalView.handleImeCompose(raw)
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        // 终端不支持区域内重组合：吞掉（返回 true 防 IME 反复重试）
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        var before = beforeLength.coerceAtLeast(0)
        var after = afterLength.coerceAtLeast(0)
        while (before-- > 0) terminalView.handleImeBackspace()
        while (after-- > 0) terminalView.handleImeDeleteForward()
        // 行缓冲收缩（IME 的本地视图一致）
        val len = tinyBuffer.length
        if (len > 0) {
            tinyBuffer.clear()
            Selection.setSelection(tinyBuffer, 0)
        }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.sendKeyEvent(event)
        // IME 生成的键（Gboard 的 Enter/Delete/方向）→ View 硬件键路径统一处理
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> terminalView.dispatchHardwareKeyEvent(event) ||
                super.sendKeyEvent(event)
            else -> super.sendKeyEvent(event)
        }
    }

    override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean {
        // 厂商 IME 私有指令（语音面板等）—— no-op，返回成功防重试风暴
        return true
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        // 全屏抽取模式已被 imeOptions 禁用；这里返回空防个别 IME 强行抽取
        val et = ExtractedText()
        et.text = tinyBuffer
        et.startOffset = 0
        et.selectionStart = tinyBuffer.length
        et.selectionEnd = tinyBuffer.length
        return et
    }

    companion object {
        /**
         * View 的 `onCreateInputConnection` 调用：填 EditorInfo（inputType/ime 选项
         * 的集中决策 —— 见类 KDoc）。
         */
        fun populateEditorInfo(outAttrs: EditorInfo) {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_FORCE_ASCII or
                EditorInfo.IME_ACTION_NONE
        }
    }
}
