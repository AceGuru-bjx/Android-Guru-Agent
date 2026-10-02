package com.apex.agent.plugin.api

/**
 * # 插件契约（宿主 ↔ 插件共享常量）
 *
 * AIDL（[IApexPlugin] / [IApexPluginHost]）只定义跨进程方法形状；版本与发现
 * 协议的**数值**统一锚定在这里——宿主与插件两侧引用同一常量，避免各自
 * 硬拷贝漂移（此前宿主侧从不消费 minHostVersion，插件侧散落硬编码 action
 * 字符串，本文件整体是死代码——现已激活为契约单一事实源）。
 *
 * 版本演进纪律：HOST_API_VERSION 只增不减（语义化主版本），插件用
 * [PluginMetadata.minHostVersion] 声明其所需的最低宿主 API 版本；宿主在
 * 注册插件工具前比对（PluginManager），不满足则拒绝注册并日志引导。
 */
object PluginContract {

    /**
     * 宿主插件 API 版本。绑定协议（attachHost 宿主桥）/ 工具清单 JSON 形状
     * 的当前版本——不满足插件声明 minHostVersion 的旧宿主不加载该插件。
     */
    const val HOST_API_VERSION: Int = 1

    /** 插件发现 action（宿主 PackageManager queryIntentServices 与插件 service intent-filter 双侧约定）。 */
    const val ACTION_PLUGIN: String = "com.apex.agent.plugin.PLUGIN"
}

/**
 * 插件契约：所有插件APK必须实现的接口
 * 通过AIDL暴露给主APK
 */
interface ApexPluginService {
    fun getMetadata(): PluginMetadata
    fun getTools(): List<PluginToolDescriptor>
    suspend fun executeTool(toolId: String, arguments: String): String
    fun onActivate()
    fun onDeactivate()
}

data class PluginMetadata(
    val id: String,
    val name: String,
    val version: Int,
    val versionName: String,
    val minHostVersion: Int,
    val description: String
)

data class PluginToolDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val parametersSchema: String
)
