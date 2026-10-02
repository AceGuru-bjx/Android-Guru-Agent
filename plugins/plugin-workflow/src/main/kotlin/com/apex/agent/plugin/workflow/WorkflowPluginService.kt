package com.apex.agent.plugin.workflow

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.apex.agent.plugin.api.IApexPlugin
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 工作流插件（applicationId: com.apex.agent.plugin.workflow）。
 *
 * 三工具的真实实现（运行在本插件自己的 APK 进程里）：
 * - `workflow/save`：校验 args（name + steps 数组）后原子落盘到本插件私有的
 *   `filesDir/workflows/<id>.json`，返回带路径的成功消息；
 * - `workflow/list`：列目录读全部 JSON，返回 `[{name, steps, savedAt}]` 摘要数组；
 * - `workflow/execute`：读取保存的工作流并解析步骤；实际执行需要宿主桥
 *   （步骤语义是宿主侧工具调用，本插件进程没有执行通道），当前版本返回
 *   **明确的不支持错误**——绝不谎报执行成功污染 agent 循环。
 *
 * AIDL 协议（getMetadataJson / getToolsJson / executeTool / attachHost）形态不变。
 */
class WorkflowPluginService : Service() {

    private val binder = WorkflowPluginBinder()

    /** json 解析器：宽松配置（插件收到的 args 形态由模型决定）。 */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun onBind(intent: Intent): IBinder = binder

    /** 工作流持久化目录（本插件私有存储，宿主与其他 App 均不可见）。 */
    private val workflowsDir: File get() = File(filesDir, WORKFLOWS_DIR_NAME)

    inner class WorkflowPluginBinder : IApexPlugin.Stub() {

        override fun getMetadataJson(): String {
            return buildJsonObject {
                put("id", "com.apex.agent.plugin.workflow")
                put("name", "工作流引擎")
                put("version", 1)
                put("versionName", "1.0.0")
                put("minHostVersion", 1)
                put("description", "工作流持久化：保存/列出可复用的动作序列（执行需宿主桥，当前版本不支持）")
            }.toString()
        }

        override fun getToolsJson(): String {
            return buildJsonArray {
                addJsonObject {
                    put("id", "workflow/save")
                    put("name", "Save Workflow")
                    put("description", "Save a sequence of actions as a reusable workflow")
                    put("parametersSchema", buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("name") { put("type", "string") }
                            putJsonObject("steps") { put("type", "array") }
                        }
                        putJsonArray("required") { add("name"); add("steps") }
                    }.toString())
                }
                addJsonObject {
                    put("id", "workflow/execute")
                    put("name", "Execute Workflow")
                    put("description", "Execute a saved workflow by name (NOT SUPPORTED in this " +
                        "plugin version: execution requires a host bridge which is not available)")
                    put("parametersSchema", buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("name") { put("type", "string") }
                            putJsonObject("params") { put("type", "object") }
                        }
                        putJsonArray("required") { add("name") }
                    }.toString())
                }
                addJsonObject {
                    put("id", "workflow/list")
                    put("name", "List Workflows")
                    put("description", "List all saved workflows")
                    put("parametersSchema", """{"type":"object","properties":{}}""")
                }
            }.toString()
        }

        override fun executeTool(toolId: String, argumentsJson: String): String {
            return when (toolId) {
                "workflow/save" -> handleSaveWorkflow(argumentsJson)
                "workflow/execute" -> handleExecuteWorkflow(argumentsJson)
                "workflow/list" -> handleListWorkflows()
                else -> "Error: Unknown tool $toolId"
            }
        }

        override fun onActivate() {}
        override fun onDeactivate() {}

        /**
         * 宿主桥注入（IApexPlugin v3 新增方法）。本插件的工具语义不依赖宿主
         * 能力（save/list 是本地文件操作，execute 如上所述不支持），空实现
         * 即可——注意 AIDL 生成的 Kotlin 签名参数为可空类型。
         */
        override fun attachHost(host: IBinder?) {}
    }

    // ── 工具实现 ─────────────────────────────────────────────────────

    /** `workflow/save`：校验 → 原子落盘 → 带路径回执。 */
    private fun handleSaveWorkflow(args: String): String {
        val obj = parseArgsObject(args)
            ?: return "Error: workflow/save arguments must be a JSON object: $MALFORMED_ARGS_HINT"

        val name = obj.stringOf("name")?.trim()
        if (name.isNullOrEmpty()) {
            return "Error: workflow/save requires a non-empty 'name' string"
        }
        val steps = (obj["steps"] as? JsonArray)
            ?: return "Error: workflow/save requires 'steps' to be an array of steps"
        if (steps.isEmpty()) {
            return "Error: workflow/save requires at least one step in 'steps'"
        }
        if (steps.size > MAX_STEPS) {
            return "Error: too many steps (${steps.size}, max $MAX_STEPS)"
        }
        val id = sanitizeWorkflowId(name)
            ?: return "Error: workflow name '$name' has no usable characters for a file id"

        val file = File(workflowsDir, "$id$JSON_SUFFIX")
        val payload = buildJsonObject {
            put("name", name)
            put("steps", steps)
            put("stepCount", steps.size)
            put("savedAt", System.currentTimeMillis())
        }.toString()
        return try {
            writeAtomically(file, payload)
            "OK: workflow '$name' saved to ${file.absolutePath} (${steps.size} steps)"
        } catch (e: IOException) {
            "Error: failed to persist workflow '$name': ${e.message}"
        }
    }

    /** `workflow/list`：列目录 → 摘要数组（损坏文件跳过，不拖垮整个清单）。 */
    private fun handleListWorkflows(): String {
        val dir = workflowsDir
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(JSON_SUFFIX) }
            ?: return "[]"
        val summaries = files.mapNotNull { file ->
            runCatching {
                val obj = json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
                buildJsonObject {
                    put("name", obj["name"]?.jsonPrimitive?.contentOrNull ?: file.nameWithoutExtension)
                    put("steps", obj["stepCount"]?.jsonPrimitive?.intOrNull
                        ?: (obj["steps"] as? JsonArray)?.size ?: 0)
                    obj["savedAt"]?.jsonPrimitive?.longOrNull?.let { put("savedAt", it) }
                }
            }.getOrNull() // 损坏/半写的 tmp 残留：跳过
        }.sortedBy { it["name"]?.jsonPrimitive?.contentOrNull ?: "" }
        return summaries.joinToString(prefix = "[", separator = ",", postfix = "]") { it.toString() }
    }

    /** `workflow/execute`：能读能解析，但执行通道不存在 → 诚实失败。 */
    private fun handleExecuteWorkflow(args: String): String {
        val obj = parseArgsObject(args)
            ?: return "Error: workflow/execute arguments must be a JSON object: $MALFORMED_ARGS_HINT"
        val name = obj.stringOf("name")?.trim()
        if (name.isNullOrEmpty()) {
            return "Error: workflow/execute requires a non-empty 'name' string"
        }
        val id = sanitizeWorkflowId(name)
            ?: return "Error: workflow name '$name' has no usable characters for a file id"

        val file = File(workflowsDir, "$id$JSON_SUFFIX")
        if (!file.isFile) {
            return "Error: workflow '$name' not found. Use workflow/list to see saved workflows."
        }
        val workflow = runCatching {
            json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        }.getOrNull()
            ?: return "Error: saved workflow '$name' is corrupted (unparseable JSON at ${file.absolutePath})"
        val steps = (workflow["steps"] as? JsonArray)?.size ?: 0

        // 步骤语义是"声明给宿主的工具调用"，而本插件进程没有执行通道
        // （attachHost 未注入可用桥）——诚实失败，绝不返回假成功。
        return "Error: workflow execution requires host bridge which is not available " +
            "in this plugin version (workflow '$name' loaded fine: $steps steps). " +
            "Execute the steps yourself with the equivalent host tools instead."
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    private fun parseArgsObject(args: String): JsonObject? = runCatching {
        json.parseToJsonElement(args) as? JsonObject
    }.getOrNull()

    /** 安全取字符串字段：缺失/非原始类型/JSON null 返回 null（不抛——binder 线程不能崩）。 */
    private fun JsonObject.stringOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    /**
     * 工作流名 → 文件 id：仅保留字母/数字/`-`/`_`/`.`（其余折叠为 `-`），
     * 首尾符号剥离、长度截断。名字来自模型输出——目录穿越（`../` 中的
     * 分隔符会被折叠）与空名在此被消除。
     */
    private fun sanitizeWorkflowId(raw: String): String? {
        val cleaned = raw.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.') c else '-'
        }.joinToString("").trim('-', '.').take(MAX_ID_CHARS)
        return cleaned.ifEmpty { null }
    }

    /**
     * 原子写（对齐宿主 AGENTS.md 纪律）：tmp → flush → fsync → rename，
     * rename 失败直写兜底。
     */
    @Throws(IOException::class)
    private fun writeAtomically(file: File, content: String) {
        val parent = file.parentFile
        parent?.let { if (!it.isDirectory) it.mkdirs() }
        val tmp = File(parent, file.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    private companion object {
        const val WORKFLOWS_DIR_NAME = "workflows"
        const val JSON_SUFFIX = ".json"
        const val TMP_SUFFIX = ".tmp"
        const val MAX_STEPS = 200
        const val MAX_ID_CHARS = 64
        const val MALFORMED_ARGS_HINT = "expected {\"name\": \"...\", \"steps\": [...]}"
    }
}
