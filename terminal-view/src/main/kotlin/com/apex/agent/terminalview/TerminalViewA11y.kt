package com.apex.agent.terminalview

import android.os.SystemClock
import android.view.View
import com.apex.agent.terminalemulator.TerminalRenderSnapshot

/**
 * T92：无障碍播报（从 TerminalView 抽出 —— SRP 行预算）。
 *
 * - [View.contentDescription] 随最新输出行更新（TalkBack 朗读面板）；
 * - 输出增长播报：2s 限速（连续刷屏不轰炸 TalkBack），仅附着窗口时派发。
 */
internal class TerminalViewA11y(private val view: View) {
    private var lastAnnounceUptime = 0L

    /** 快照到达时调用（View 持有方接线）。 */
    fun updateContent(s: TerminalRenderSnapshot) {
        val lastLine = s.lines.lastOrNull()?.joinToString("") { it.text }?.takeLast(120)
        view.contentDescription = if (lastLine.isNullOrBlank()) "Terminal" else "Terminal: $lastLine"
        val now = SystemClock.uptimeMillis()
        if (view.isAttachedToWindow && now - lastAnnounceUptime > 2000L && !lastLine.isNullOrBlank()) {
            lastAnnounceUptime = now
            view.announceForAccessibility(lastLine.take(80))
        }
    }
}
