package com.apex.agent.ui.screen.terminal.history

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * T87：终端命令历史（Termux ↑/history 的可视化等价物）。
 *
 * 职责：
 *  - 记录用户在交互终端提交的命令（回车时刻、行内容非空）；
 *  - 去重（最新在前）+ 上限裁剪（[MAX_ENTRIES]）；
 *  - SharedPreferences 持久化（进程重启不丢）；
 *  - [StateFlow] 广播给 UI（历史抽屉实时刷新）。
 *
 * 不做：Agent 写入的命令（AGENT owner 不进用户历史 —— 与 bash history 语义
 * 一致：history 记的是「人在提示符敲的」）。
 */
class TerminalCommandHistory(
    context: Context,
    private val maxEntries: Int = MAX_ENTRIES
) {
    private val prefs = context.getSharedPreferences("apex_terminal", Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(load())
    /** 历史（最新在前）。 */
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    /** 记录一条已提交命令（空/空白忽略；去重置顶）。 */
    fun record(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        val next = buildList(capacity = maxEntries.coerceAtMost(_entries.value.size + 1)) {
            add(cmd)
            for (e in _entries.value) {
                if (size >= maxEntries) break
                if (e != cmd) add(e)
            }
        }
        _entries.value = next
        persist(next)
    }

    /** 清空（设置抽屉入口确认后调用）。 */
    fun clear() {
        _entries.value = emptyList()
        prefs.edit().remove(KEY).apply()
    }

    /** 供 ↑ 补全（未来接线：键栏上箭头长按 → 历史步进）。 */
    fun search(prefix: String, exclude: Set<String> = emptySet()): List<String> =
        _entries.value.filter { it.startsWith(prefix) && it !in exclude }

    private fun load(): List<String> =
        prefs.getStringSet(KEY, null)?.filter { it.isNotBlank() }?.sortedByDescending {
            // StringSet 无序 —— 存储时附带序号前缀「00001|cmd」保证恢复顺序
            it.substringBefore('|').toIntOrNull() ?: 0
        }?.map { it.substringAfter('|') } ?: emptyList()

    private fun persist(entries: List<String>) {
        val asSet = entries.mapIndexedNotNull { i, e ->
            if (e.contains('\n') || e.contains('|')) {
                // 竖线/换行会破坏编码格式 —— 转义后仍冲突则跳过该条（诚实丢弃
                // 优于静默串行错乱；极罕见：命令里同时含换行+竖线）
                if (e.contains('\n')) return@mapIndexedNotNull null
                "${String.format(java.util.Locale.US, "%05d", i)}|${e.replace('|', '\uFF5C')}"
            } else {
                "${String.format(java.util.Locale.US, "%05d", i)}|$e"
            }
        }.toSet()
        prefs.edit().putStringSet(KEY, asSet).apply()
    }

    companion object {
        private const val KEY = "term_command_history"
        const val MAX_ENTRIES = 500
    }
}
