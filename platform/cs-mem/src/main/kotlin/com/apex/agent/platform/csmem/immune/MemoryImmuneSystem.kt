package com.apex.agent.platform.csmem.immune

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.WindowManager
import com.apex.agent.platform.privilege.PrivilegeManager
import com.apex.agent.platform.privilege.UiNode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆免疫系统 —— 基于多模态交叉验证的 UI 欺骗防御。
 *
 * 防御威胁：
 *   - 悬浮窗/Overlay 攻击：恶意 App 绘制假登录界面
 *   - 无障碍服务劫持：篡改 UI 树文本内容
 *   - 记忆中毒 (Memory Poisoning)：恶意 UI 数据被写入长期记忆
 *
 * 机制：
 *   1. 悬浮窗检测：全屏覆盖且可点击的非业务节点
 *   2. 敏感关键词检测：非受信 App 中出现钓鱼/支付类话术
 *   3. 结构完整性：UI 树不应完全为空或异常稀疏（无障碍劫持特征）
 *   4. 包名可信分级：受信包名豁免敏感词策略，未知包名提高警戒
 *   5. 记忆隔离 (Quarantine)：可疑节点（含子树）剔除出写入管线，
 *      指纹持久化隔离（后续帧同指纹节点同样被剔除）
 *
 * 写入语义（节点级/帧级区分）：
 *   - SAFE：照常写入；
 *   - SUSPICIOUS（如疑似悬浮窗）：仅隔离可疑节点（含子树），不阻断整帧
 *     ——合法全屏容器等单点误报不再灭掉整帧记忆；
 *   - HIGH_RISK / MALICIOUS：整帧阻断（safe=false，防钓鱼内容入库）。
 *
 * 注意：完整的 OCR 视觉比对需要集成 MLKit Text Recognition，
 *       当前阶段实现结构/文本特征 + 屏幕几何检测，已覆盖主要攻击面。
 */
@Singleton
class MemoryImmuneSystem @Inject constructor(
    private val privilegeManager: PrivilegeManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "cs_mem_quarantine"
        private const val QUARANTINE_KEY = "quarantined_fps"

        /** 受信系统/常见 App 白名单（包名） */
        private val TRUSTED_PACKAGES = setOf(
            "com.android.settings",
            "com.android.chrome",
            "com.android.calculator2"
        )

        /** 高风险敏感词（钓鱼/支付诈骗话术） */
        private val SENSITIVE_PATTERNS = listOf(
            "输入密码", "请输入支付密码", "请输入银行卡号",
            "中奖", "领取红包", "系统升级", "安全验证"
        )

        /** 多个敏感词命中即升级为 MALICIOUS */
        private const val MALICIOUS_PATTERN_THRESHOLD = 2

        /** 覆盖超过此比例的屏幕节点视为可疑悬浮窗 */
        private const val OVERLAY_AREA_RATIO = 0.8f
    }

    /**
     * 隔离名单 —— 被标记为可疑的 UI 指纹集合（内存 + 持久化）。
     *
     * 线程安全：agent 写入协程（Dispatchers.IO，validateUiTree 写）与主线程
     * （MemoryViewModel 隔离区计数，quarantinedCount 读）并发访问——普通
     * mutableSetOf 迭代中遇写会抛 ConcurrentModificationException，改用
     * ConcurrentHashMap.newKeySet（minSdk 26 ≥ API 24）。
     */
    private val quarantineSet: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
            addAll(loadQuarantine())
        }

    /**
     * 对 UI 树执行安全检查。
     *
     * @param nodes 当前 UI 树的根节点列表（嵌套树；内部展平后逐节点检查，
     * 每节点恰好一次——兼容只提供扁平列表的旧调用方/替身）
     * @param appPackage 来源 App 包名（可能为 null，此时按未知包处理）
     * @return 安全检查结果（[ImmuneResult.safe]=false 表示应整帧阻断；
     * SUSPICIOUS 级 safe=true 但应配合 [stripQuarantinedNodes] 剔除被隔离节点）
     *
     * 屏幕分辨率从 [Context] 的 WindowManager 获取，避免调用方误传 (0,0)
     * 导致悬浮窗检测失效。
     */
    fun validateUiTree(
        nodes: List<UiNode>,
        appPackage: String?
    ): ImmuneResult {
        val (screenW, screenH) = getScreenSize()
        val issues = mutableListOf<String>()
        var threatLevel = ThreatLevel.SAFE

        // 展平嵌套树：悬浮窗/敏感词检查需覆盖全部节点（嵌套输入下仅看顶层
        // 会漏掉整棵子树，检测系统性失效）。
        val flatNodes = flattenNodes(nodes)

        // 检查1：悬浮窗检测 —— 检测全屏覆盖的可点击节点
        val overlayNodes = detectOverlay(flatNodes, screenW, screenH)
        if (overlayNodes.isNotEmpty()) {
            issues.add("检测到 ${overlayNodes.size} 个可疑全屏节点（疑似悬浮窗攻击）")
            threatLevel = maxLevel(threatLevel, ThreatLevel.SUSPICIOUS)
            for (node in overlayNodes) {
                quarantineSet.add(nodeFingerprint(node))
            }
        }

        // 检查2：敏感关键词检测（仅非受信包触发）
        val isTrusted = appPackage in TRUSTED_PACKAGES
        var sensitiveHit = 0
        for (node in flatNodes) {
            for (pattern in SENSITIVE_PATTERNS) {
                if (node.text.contains(pattern) || node.contentDescription.contains(pattern)) {
                    if (!isTrusted) {
                        sensitiveHit++
                        issues.add("非受信 App[${appPackage ?: "?"}] 节点包含敏感文本: \"$pattern\"")
                        quarantineSet.add(nodeFingerprint(node))
                    }
                }
            }
        }
        if (sensitiveHit >= MALICIOUS_PATTERN_THRESHOLD) {
            // 多个高危话术集中出现 → 极可能是钓鱼界面
            threatLevel = maxLevel(threatLevel, ThreatLevel.MALICIOUS)
        } else if (sensitiveHit > 0) {
            threatLevel = maxLevel(threatLevel, ThreatLevel.HIGH_RISK)
        }

        // 检查3：结构完整性 —— UI 树不应完全为空或异常稀疏
        if (flatNodes.size < 3) {
            issues.add("UI 树异常稀疏（${flatNodes.size} 节点），可能存在无障碍劫持")
            threatLevel = maxLevel(threatLevel, ThreatLevel.SUSPICIOUS)
        }

        // 检查4：未知包名 + 全屏可点击节点组合 → 提高警戒
        // （UiNode 不含 packageName，故以 appPackage 参数为准做包名可信分级）
        if (!isTrusted && appPackage != null && overlayNodes.isNotEmpty()) {
            issues.add("未知包名[$appPackage] 出现全屏覆盖节点，触发高危隔离")
            threatLevel = maxLevel(threatLevel, ThreatLevel.HIGH_RISK)
        }

        // 节点级/帧级区分：SUSPICIOUS 仅隔离可疑节点（stripQuarantinedNodes
        // 剔除含子树），不阻断整帧；HIGH_RISK/MALICIOUS 才整帧阻断。
        val safe = threatLevel.ordinal <= ThreatLevel.SUSPICIOUS.ordinal
        if (!safe) persistQuarantine()

        return ImmuneResult(
            safe = safe,
            threatLevel = threatLevel,
            issues = issues,
            quarantinedNodes = quarantineSet.toList()
        )
    }

    /**
     * 从 UI 树中剔除被隔离的节点（含其子树）。
     *
     * SUSPICIOUS 级（如疑似悬浮窗）只影响可疑子树，其余节点照常进入记忆
     * 写入管线。隔离名单跨帧持久化——历史被隔离指纹的后续出现同样剔除。
     */
    fun stripQuarantinedNodes(nodes: List<UiNode>, result: ImmuneResult): List<UiNode> {
        if (result.quarantinedNodes.isEmpty()) return nodes
        val blocked = HashSet<String>(result.quarantinedNodes)
        return filterTree(nodes, blocked)
    }

    /**
     * 判断指定指纹是否已被隔离。
     */
    fun isQuarantined(fingerprint: String): Boolean {
        return fingerprint in quarantineSet
    }

    /**
     * 当前隔离名单规模（记忆页「隔离区」计数展示入口）。
     * 返回被隔离的 UI 指纹条数（0 = 免疫系统未触发过隔离 —— 正常态）。
     */
    fun quarantinedCount(): Int = quarantineSet.size

    /**
     * 清除隔离名单（App 更新后可重新评估）。
     */
    fun clearQuarantine() {
        quarantineSet.clear()
        persistQuarantine()
    }

    // ==================== Private ====================

    private fun getScreenSize(): Pair<Int, Int> {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        } catch (_: Exception) {
            // 取不到分辨率时退化为 0，overlay 检测自然失效（安全优先：不误伤）
            Pair(0, 0)
        }
    }

    private fun detectOverlay(nodes: List<UiNode>, screenW: Int, screenH: Int): List<UiNode> {
        if (screenW <= 0 || screenH <= 0) return emptyList()
        val result = mutableListOf<UiNode>()
        for (node in nodes) {
            val bounds = parseBounds(node.bounds)
            val areaRatio = (bounds.width().toFloat() * bounds.height().toFloat()) /
                (screenW.toFloat() * screenH.toFloat())
            if (areaRatio > OVERLAY_AREA_RATIO && node.clickable) {
                result.add(node)
            }
        }
        return result
    }

    private fun parseBounds(boundsStr: String): Rect {
        return try {
            val nums = Regex("-?\\d+").findAll(boundsStr)
                .map { it.value.toInt() }.toList()
            if (nums.size >= 4) {
                Rect(nums[0], nums[1], nums[2], nums[3])
            } else {
                Rect(0, 0, 0, 0)
            }
        } catch (_: Exception) {
            Rect(0, 0, 0, 0)
        }
    }

    private fun nodeFingerprint(node: UiNode): String {
        return "${node.className}_${node.resourceId}_${node.text}"
    }

    /** 嵌套 UiNode 树 → 扁平列表（DFS 先序，每节点恰好一次）。 */
    private fun flattenNodes(nodes: List<UiNode>): List<UiNode> {
        val out = ArrayList<UiNode>(nodes.size * 2)
        for (node in nodes) {
            collectNodes(node, out)
        }
        return out
    }

    private fun collectNodes(node: UiNode, out: MutableList<UiNode>) {
        out.add(node)
        for (child in node.children) {
            collectNodes(child, out)
        }
    }

    /** 递归剔除被隔离节点（含其子树），保留其余结构。 */
    private fun filterTree(nodes: List<UiNode>, blocked: Set<String>): List<UiNode> =
        nodes.mapNotNull { node ->
            if (nodeFingerprint(node) in blocked) null
            else node.copy(children = filterTree(node.children, blocked))
        }

    private fun maxLevel(a: ThreatLevel, b: ThreatLevel): ThreatLevel {
        return if (a.ordinal >= b.ordinal) a else b
    }

    // ---- Quarantine 持久化（SharedPreferences，避免 Room schema 迁移）----

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadQuarantine(): Set<String> =
        runCatching { prefs().getStringSet(QUARANTINE_KEY, emptySet()) ?: emptySet() }
            .getOrDefault(emptySet())

    private fun persistQuarantine() {
        runCatching {
            prefs().edit().putStringSet(QUARANTINE_KEY, quarantineSet.toSet()).apply()
        }
    }
}

/**
 * 免疫系统检查结果。
 *
 * @param safe 是否允许整帧写入（true = 不阻断；SUSPICIOUS 级为 true，
 * 但调用方应配合 [MemoryImmuneSystem.stripQuarantinedNodes] 剔除
 * [quarantinedNodes] 命中的节点及其子树）
 */
data class ImmuneResult(
    val safe: Boolean,
    val threatLevel: ThreatLevel,
    val issues: List<String>,
    val quarantinedNodes: List<String>
)

enum class ThreatLevel {
    /** 安全，无异常 */
    SAFE,
    /** 可疑：隔离相关节点（含子树），不阻断整帧写入 */
    SUSPICIOUS,
    /** 高危，阻断写入 + 建议终止任务 */
    HIGH_RISK,
    /** 恶意（如多个钓鱼话术集中），强制终止任务 + 隔离 */
    MALICIOUS
}
