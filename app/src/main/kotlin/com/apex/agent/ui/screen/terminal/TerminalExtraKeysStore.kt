package com.apex.agent.ui.screen.terminal

import android.content.SharedPreferences
import com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig

/**
 * 扩展键布局持久化 —— 从 `TerminalViewModel` 缝拆（守 1200 行文件预算）。
 *
 * 存储键 `term_extra_keys`：单行布局序列（[ExtraKeysConfig.serialize]）。
 */

/**
 * 读取已存布局。T89：新用户默认空布局（KeyToolbar 主行已覆盖 TAB/^L/粘贴
 * —— 双行键区重复泛滥）；已存储布局的存量用户不受影响（parse 非空即用）。
 */
internal fun loadExtraKeys(prefs: SharedPreferences): List<ExtraKeysConfig.ExtraKey> {
    val stored = prefs.getString("term_extra_keys", null)
    return ExtraKeysConfig.parse(stored)?.flatten() ?: ExtraKeysConfig.EMPTY_LAYOUT.flatten()
}

/** 持久化单行布局（apply() 异步落盘 —— 主线程不等磁盘）。 */
internal fun persistExtraKeys(prefs: SharedPreferences, keys: List<ExtraKeysConfig.ExtraKey>) {
    val ser = ExtraKeysConfig.serialize(listOf(keys))
    prefs.edit().putString("term_extra_keys", ser).apply()
}
