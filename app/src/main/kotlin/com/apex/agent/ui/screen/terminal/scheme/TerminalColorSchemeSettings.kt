package com.apex.agent.ui.screen.terminal.scheme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * T87：终端配色方案持久化 + 热切换（SharedPreferences 后端）。
 *
 * 职责边界：
 *  - 只管「scheme id + bold-as-bright」两个持久化项的存取与 StateFlow 广播；
 *  - 方案解析经 [TerminalColorSchemeRegistry]（未知 id 自动兜底默认方案）；
 *  - UI（设置抽屉/选择器）改 [MutableStateFlow]，渲染树订阅后整树换色。
 *
 * 抽象注入点：测试可用假 [Store]（内存 Map）驱动，不碰 Android。
 */
class TerminalColorSchemeSettings(
    private val store: Store
) {
    /** 持久化键（TerminalViewModel 的 prefs 同一文件 —— 终端设置统一入口）。 */
    object Keys {
        const val SCHEME_ID = "term_color_scheme_id"
        const val BOLD_AS_BRIGHT = "term_bold_as_bright"
    }

    private val _schemeId = MutableStateFlow(loadSchemeId())
    /** 当前方案 id（已校验；未知持久化值在加载时即收敛为默认）。 */
    val schemeId: StateFlow<String> = _schemeId.asStateFlow()

    private val _boldAsBright = MutableStateFlow(loadBoldAsBright())
    /** bold → 亮色提升（xterm 传统；默认开 —— bash/ls 彩色输出的约定）。 */
    val boldAsBright: StateFlow<Boolean> = _boldAsBright.asStateFlow()

    /** 当前解析后的完整方案（id 变化即重解析）。 */
    val scheme: TerminalColorScheme
        get() = TerminalColorSchemeRegistry.byId(_schemeId.value)

    /** 切换方案（未知 id 拒绝 —— 调用方应来自注册表）。 */
    fun setSchemeId(id: String): Boolean {
        if (!TerminalColorSchemeRegistry.isValidId(id)) return false
        store.putString(Keys.SCHEME_ID, id)
        _schemeId.value = id
        return true
    }

    /** bold-as-bright 开关。 */
    fun setBoldAsBright(enabled: Boolean) {
        store.putBoolean(Keys.BOLD_AS_BRIGHT, enabled)
        _boldAsBright.value = enabled
    }

    private fun loadSchemeId(): String {
        val raw = store.getString(Keys.SCHEME_ID, null)
        return if (raw != null && TerminalColorSchemeRegistry.isValidId(raw)) raw
        else TerminalColorScheme.FALLBACK_ID
    }

    private fun loadBoldAsBright(): Boolean = store.getBoolean(Keys.BOLD_AS_BRIGHT, true)

    /** 持久化抽象（生产 = SharedPreferences；测试 = 内存假）。 */
    interface Store {
        fun getString(key: String, default: String?): String?
        fun putString(key: String, value: String)
        fun getBoolean(key: String, default: Boolean): Boolean
        fun putBoolean(key: String, value: Boolean)
    }

    /** SharedPreferences 适配器（app 生产接线）。 */
    class PrefsStore(context: Context, name: String = "terminal_prefs") : Store {
        private val prefs: SharedPreferences =
            context.getSharedPreferences(name, Context.MODE_PRIVATE)

        override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
        override fun putString(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }
        override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
        override fun putBoolean(key: String, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }
    }
}
