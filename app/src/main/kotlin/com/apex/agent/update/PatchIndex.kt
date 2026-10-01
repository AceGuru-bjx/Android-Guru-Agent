package com.apex.agent.update

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 发布仓库补丁全量索引（`patches.json`）—— 跨版本增量更新的数据底座。
 *
 * `version.json` 只携带「上一版 → 本版」单个补丁，跨两个以上小版本时
 * 客户端无从得知中间补丁的存在，只能回退 300~900MB 全量包。本索引由
 * 开发仓库 CI 在每次发布时**累积**维护（老条目永不清除），客户端由此
 * 把「本地版本 → 最新版本」解析成一条补丁链，逐段应用即跨越任意多版。
 *
 * 文件布局（与发布仓库 main 分支同源，raw CDN 可读）：
 * ```json
 * {
 *   "generatedAt": "2026-09-30T15:04:25Z",
 *   "latestTag": "v1.4.4.5",
 *   "patches": [
 *     {"variant":"arm64","fromTag":"v1.4.4.4","toTag":"v1.4.4.5",
 *      "url":"https://github.com/.../patch_arm64_v1.4.4.4_to_v1.4.4.5.vcdiff",
 *      "sizeBytes":11147826,"sha256":"493c…"}
 *   ]
 * }
 * ```
 *
 * 设计约束：
 * - **前向兼容**：全部字段可选 + `ignoreUnknownKeys` —— 索引 schema 演进
 *   （新增字段）不崩老客户端；缺 sha256 时跳过校验（与既有 APK 下载同策）；
 * - **零鉴权**：与 version.json 同走 raw.githubusercontent 公开 CDN；
 * - **容错折叠**：解析失败返回 null，调用方回退 version.json 单补丁或
 *   全量包，更新链路永不因索引问题而中断。
 */
object PatchIndex {

    /** 发布仓库 main 分支上的补丁索引固定地址（CI 自动维护）。 */
    const val PATCH_INDEX_URL =
        "https://raw.githubusercontent.com/AceGuru-mjh/Android-Guru-Agent-Release/main/patches.json"

    /** 链上补丁数量上限 —— 防御环形索引（CI bug / 被篡改）导致死循环。 */
    private const val MAX_CHAIN_LENGTH = 64

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 单条补丁：variant ∈ {arm64, universal}。 */
    @Serializable
    data class Entry(
        val variant: String,
        val fromTag: String,
        val toTag: String,
        val url: String,
        val sizeBytes: Long = 0L,
        val sha256: String? = null
    )

    /** 索引根模型。 */
    @Serializable
    data class Model(
        val generatedAt: String? = null,
        val latestTag: String? = null,
        val patches: List<Entry> = emptyList()
    )

    /** 解析出的补丁链（有序，可逐段应用）与总体积。 */
    data class Chain(
        val steps: List<Entry>,
        val totalBytes: Long
    )

    /** 宽松解析：任何形状异常折叠为 null（防御式 IO 纪律）。 */
    fun parse(text: String): Model? = runCatching { json.decodeFromString<Model>(text) }
        .onFailure {
            AppLogger.instance.warn(
                LogCategory.SYSTEM, "PatchIndex", "补丁索引解析失败：${it.message}"
            )
        }
        .getOrNull()

    /**
     * 解析「本地版本 → 目标 tag」的最短补丁链。
     *
     * 图结构：fromTag → toTag 有向边（按 variant 过滤）。用哈希表逐跳
     * 推进；同一 from 出现多条边时取**第一条**（CI 语义下 from 唯一），
     * visited 集合防环。任一跳断链（该版本无后继补丁）即整体失败，
     * 调用方回退全量。
     *
     * @param index 索引模型
     * @param localVersionName 本地 versionName（如 "1.4.4.3"）
     * @param targetTag 目标 tag（如 "v1.4.4.5"）
     * @param variant 设备 ABI 变体（"arm64" / "universal"）
     */
    fun resolveChain(
        index: Model,
        localVersionName: String,
        targetTag: String,
        variant: String
    ): Chain? {
        val from = "v$localVersionName"
        if (from == targetTag) return null
        val outgoing = HashMap<String, Entry>()
        for (entry in index.patches) {
            if (entry.variant != variant) continue
            if (entry.url.isBlank()) continue
            // 保留首个出现的历史条目（向后兼容早期索引的重复写入）
            outgoing.putIfAbsent(entry.fromTag, entry)
        }
        val visited = HashSet<String>()
        val steps = ArrayList<Entry>()
        var cursor = from
        while (cursor != targetTag) {
            if (!visited.add(cursor) || steps.size >= MAX_CHAIN_LENGTH) return null
            val edge = outgoing[cursor] ?: return null
            steps.add(edge)
            cursor = edge.toTag
        }
        if (steps.isEmpty()) return null
        val total = steps.fold(0L) { acc, entry -> acc + entry.sizeBytes.coerceAtLeast(0L) }
        return Chain(steps, total)
    }
}
