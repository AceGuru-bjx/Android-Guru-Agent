package com.apex.agent.ui.screen.market

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.tools.ToolCircuitBreaker
import com.apex.agent.core.tools.ToolTraceRecorder
import com.apex.agent.core.tools.ToolUsageTracker
import com.apex.agent.core.tools.connector.ConnectorDef
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.core.tools.marketplace.ClawHubSource
import com.apex.agent.core.tools.marketplace.ModelScopeSource
import com.apex.agent.core.tools.mcp.McpConfigImport
import com.apex.agent.core.tools.mcp.McpConfigValidator
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerCatalog
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpStartupEvent
import com.apex.agent.core.tools.mcp.McpStartupListener
import com.apex.agent.core.tools.mcp.McpStartupStage
import com.apex.agent.core.tools.mcp.McpStartupTracker
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.skill.SkillMenuProvider
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.marketplace.MarketInstallManager
import com.apex.agent.platform.csmem.store.MemoryGraphStore
import com.apex.agent.plugin.host.PluginManager
import com.apex.agent.ui.language.LanguageManager
import com.apex.agent.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 市场五个页签（两个顶栏视图共享同一套子导航）—— label 为资源 id，composable 侧 stringResource 取词。 */
enum class MarketTab(@StringRes val labelRes: Int) {
    PLUGINS(R.string.market_tab_plugins),
    SKILLS(R.string.market_tab_skills),
    MCP(R.string.market_tab_mcp),
    CONNECTORS(R.string.market_tab_connectors),
    INTEGRATIONS(R.string.market_tab_integrations)
}

/**
 * 市场顶栏视图：
 * - [BROWSE] 市场 —— 发现与安装入口（模板 / GitHub / URL / 魔搭 / 添加表单）；
 * - [INSTALLED] 已安装管理 —— 管理已装/已加载内容（启停 / 卸载 / 连接）。
 *
 * 两个视图下均保留同一套 [MarketTab] 子导航（插件 / Skills / MCP / 连接器 / 集成），
 * 切换视图不重置子页签 —— 用户在「Skills · 市场」看完安装源，切到
 * 「已安装管理」还在 Skills 分类下，上下文不断裂。
 */
enum class MarketScope(@StringRes val labelRes: Int) {
    BROWSE(R.string.market_scope_browse),
    INSTALLED(R.string.market_scope_installed)
}

/**
 * #197 市场分级：双工位市场（与技能/MCP 的 scope 字段同源）。
 * - [AGENT] Agent 市场 —— 服务于 Agent 屏（聊天技能 / 联网搜索 / 记忆 / 思维链…）；
 * - [CODING] Coding 市场 —— 服务于 Coding 屏（开发技能 / GitHub / 文件系统 / 沙箱…）。
 */
enum class MarketTier(@StringRes val labelRes: Int) {
    AGENT(R.string.market_tier_agent),
    CODING(R.string.market_tier_coding)
}

/**
 * 市场 · Skills 页签的仓库源切换：
 * - [LOCAL] —— 本地技能（已安装 + 内置模板 + 四个本地安装入口）；
 * - [CLAWHUB] —— ClawHub 技能仓库（clawhub.ai：浏览热门 / 搜索 / 真实下载安装）。
 */
enum class SkillRepoSource(@StringRes val labelRes: Int) {
    LOCAL(R.string.market_skill_source_local),
    CLAWHUB(R.string.market_skill_source_clawhub)
}

// ═══ UI 行数据（避免界面直接依赖各注册表内部类型）═══

data class MarketSkillRow(
    val id: String,
    val name: String,
    val description: String,
    val installed: Boolean,
    val enabled: Boolean,
    val source: String = "",   // local / modelscope / github
    /**
     * true = App 内置模板：能力已在二进制里（工具原生注册），不存在"下载/安装"这一步。
     * UI 显示为「内置」标记而不是「安装」按钮 —— 旧实现给它一个安装按钮，
     * 点了只是往本地写一份 JSON，观感是装了，实际什么外部内容都没拉取。
     */
    val builtin: Boolean = false,
    // ── cs-mem 认知健康（v2 增强：让市场"活"起来）──
    /** 能量 [0.01, 10.0]；衰减到 0.05 以下会被梦境折叠。1.0 = 默认/新装。 */
    val energy: Float = 1.0f,
    /** 已结晶为可跳过 LLM 的确定性宏（高频成功技能）。 */
    val isCrystallized: Boolean = false,
    /** 最近一次执行时间戳（ms）；0 = 从未执行。 */
    val lastUsedAt: Long = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    // ── 市场元数据（来自 manifest，用于分类过滤与详情页）──
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val author: String = "",
    val version: String = "",
    val trustLevel: String = "community",
    /**
     * Issue #166：manifest.bundled——APK assets（assets/skills/）首启幂等释放的内置优质技能。
     * 市场卡 / 详情显示「内置」徽标；卸载入口降级（内置可禁用不可卸载，
     * SkillRegistry.uninstall 对 bundled 恒 false）。
     */
    val bundled: Boolean = false,
    /** #197 工位作用域（"agent"/"coding"/"all"）—— 市场分级的过滤口径。 */
    val scope: String = "all"
) {
    /** 成功率 0..1。 */
    val successRate: Float get() =
        if (successCount + failureCount == 0) 0f
        else successCount.toFloat() / (successCount + failureCount)

    /** 是否低能量（需要被使用否则会衰减）。 */
    val isLowEnergy: Boolean get() = installed && energy < 0.5f && !isCrystallized
}

data class MarketMcpRow(
    val name: String,
    /** 端点摘要：远端显示 URL，STDIO 显示完整命令行。 */
    val endpoint: String,
    val transport: McpTransport,
    val enabled: Boolean,
    val connected: Boolean,
    /** 内置服务器（BUILTIN 进程内 transport，如内置 GitHub）：不可删除。 */
    val builtin: Boolean = false,
    /** #197 工位作用域（"agent"/"coding"/"all"）。 */
    val scope: String = "all"
)

data class MarketPluginRow(
    val packageName: String,
    val label: String,
    val loaded: Boolean
)

data class MarketUiState(
    val scope: MarketScope = MarketScope.BROWSE,
    val selectedTab: MarketTab = MarketTab.PLUGINS,
    // #197 市场分级（Agent 市场 / Coding 市场）
    val tier: MarketTier = MarketTier.AGENT,
    // Skills
    val skills: List<MarketSkillRow> = emptyList(),
    val skillTemplates: List<MarketSkillRow> = emptyList(),
    // MCP
    val mcps: List<MarketMcpRow> = emptyList(),
    // 连接器
    val connectors: List<ConnectorDef> = emptyList(),
    // 插件
    val plugins: List<MarketPluginRow> = emptyList(),
    // 集成：魔搭
    val modelScopeSkills: List<ModelScopeSource.ModelScopeSkill> = emptyList(),
    val modelScopeLoading: Boolean = false,
    val modelScopeQuery: String = "",
    val modelScopeError: String? = null,
    val installedModelScopeIds: Set<String> = emptySet(),
    // 集成：GitHub
    val githubQuery: String = "",
    val githubHits: List<MarketInstallManager.GitHubRepoHit> = emptyList(),
    val githubSearching: Boolean = false,
    val githubError: String? = null,
    // Skills：ClawHub 技能仓库（源切换 + 列表/搜索/安装状态）
    val skillSource: SkillRepoSource = SkillRepoSource.LOCAL,
    val clawHubSkills: List<ClawHubSource.ClawHubSkillEntry> = emptyList(),
    /** 首次/刷新加载中（trending 或翻页）。 */
    val clawHubLoading: Boolean = false,
    val clawHubError: String? = null,
    val clawHubQuery: String = "",
    /** 关键词搜索请求中（与列表加载区分，避免双重 loading）。 */
    val clawHubQueryLoading: Boolean = false,
    /** 正在安装的技能 slug（行级 busy；null = 空闲，非 null 时禁用其它安装按钮防并发）。 */
    val clawHubInstallingSlug: String? = null,
    /** 是否还有下一页（「加载更多」按钮可见性）。 */
    val clawHubHasMore: Boolean = false,
    /** 当前列表是否为搜索结果（控制「返回热门」提示）。 */
    val clawHubSearchActive: Boolean = false,
    // ── v2 认知市场增强 ──
    /** 当前选中分类过滤（null = 全部）。镜像 ToolCategory 枚举名。 */
    val categoryFilter: String? = null,
    /** 本地搜索查询（已安装技能 + 内置模板的模糊匹配，ToolSuggester 驱动）。 */
    val skillQuery: String = "",
    /** 模糊搜索建议（"你是不是要找…"）。 */
    val skillSuggestions: List<String> = emptyList(),
    /** 当前打开的技能详情对话框对应的 skillId。null = 关闭。 */
    val detailSkillId: String? = null,
    /** 详情对话框加载的分析数据。null = 未加载/加载中。 */
    val detailState: SkillDetailUiState? = null,
    /** 详情加载中。 */
    val detailLoading: Boolean = false,
    // 全局
    val busy: Boolean = false,
    /** 正在连接的 MCP 服务器名（null = 无）：连接中禁用对应行按钮，防双击并发重连 */
    val mcpConnecting: String? = null,
    /** #197 真实启动进度（连接中服务器的实时阶段事件；null = 关闭弹窗）。 */
    val mcpStartup: McpStartupUi? = null,
    // ── #205 MCP 精选目录（assets/mcp_catalog 离线随包）──
    /** 目录全量条目（未分级过滤）。 */
    val mcpCatalog: List<McpServerCatalog.McpCatalogEntry> = emptyList(),
    /** 目录加载完成（空目录且 false = 加载中/失败）。 */
    val mcpCatalogLoaded: Boolean = false,
    /** 目录加载失败原因（本地资产损坏时非空）。 */
    val mcpCatalogError: String? = null,
    /** 目录分类过滤（null = 全部）。 */
    val mcpCatalogCategory: String? = null,
    /** 待配置环境变量的目录条目（null = 环境变量弹窗关闭）。 */
    val catalogEnvEntry: McpServerCatalog.McpCatalogEntry? = null,
    val lastMessage: String? = null
)

/**
 * #197 MCP 真实启动进度弹窗状态：事件列表由 [McpStartupListener] 的真实回调
 * 逐条追加（无模拟延时，阶段间的等待就是真实的进程启动/网络握手耗时）。
 */
data class McpStartupUi(
    val serverName: String,
    val events: List<com.apex.agent.core.tools.mcp.McpStartupEvent> = emptyList(),
    val running: Boolean = true
)

/**
 * 市场页 ViewModel（v2 全面重构）
 *
 * 相比旧 MarketViewModel：
 * - **所有注册表操作移出主线程**：旧实现在 Main 线程直接调 install/setEnabled/
 *   discoverPlugins（扫盘/写文件/跨进程 IPC），点一下开关就掉帧。现在全部
 *   `Dispatchers.IO`。
 * - **功能补全**：MCP 添加/删除/启用开关（HTTP/SSE/STDIO）、连接器增删开关
 *   （ConnectorRegistry 持久化）、插件加载/卸载、Skill JSON 导入、
 *   魔搭/GitHub 两个集成源 + URL 导入——对齐并超越 PR45 的市场设计。
 * - **变更推送**：斜杠菜单由各注册表的 changes 流自动刷新（见 SlashMenuProvider v2），
 *   市场操作完成后无需手动通知菜单。
 */
@HiltViewModel
class MarketViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val skillRegistry: SkillRegistry,
    private val skillMenuProvider: SkillMenuProvider,
    private val mcpManager: McpManager,
    private val connectorRegistry: ConnectorRegistry,
    private val pluginManager: PluginManager,
    private val installManager: MarketInstallManager,
    private val modelScopeSource: ModelScopeSource,
    private val clawHubSource: ClawHubSource,
    // 语言切换：VM 侧消息（snackbar）按当前语言取词
    private val languageManager: LanguageManager,
    // ── v2 认知市场：注入 cs-mem + 工具分析四件套（均为 @Singleton）──
    private val memoryGraphStore: MemoryGraphStore,
    private val usageTracker: ToolUsageTracker,
    private val circuitBreaker: ToolCircuitBreaker,
    private val traceRecorder: ToolTraceRecorder
) : ViewModel() {

    /** 技能分析投影器（聚合 cs-mem + 工具统计 + 熔断 + 轨迹 → 详情 UI 状态）。 */
    private val skillAnalytics = SkillAnalytics(
        memoryGraphStore, usageTracker, circuitBreaker, traceRecorder
    )

    private val _uiState = MutableStateFlow(MarketUiState())
    val uiState: StateFlow<MarketUiState> = _uiState.asStateFlow()

    /** 魔搭全量列表（过滤基于全量，避免在已过滤结果上二次过滤后无法还原）。 */
    private var allModelScopeSkills: List<ModelScopeSource.ModelScopeSkill> = emptyList()

    /** ClawHub 当前页偏移（加载更多时 offset += 一页）。 */
    private var clawHubOffset = 0

    /** ClawHub 当前是否处于搜索结果模式（重试时区分走搜索还是热门）。 */
    private var clawHubSearchMode = false

    /** 当前搜索结果集对应的查询词（翻页/重试用，避免用户改了输入框但未点搜索时错页）。 */
    private var clawHubSearchQuery = ""

    /**
     * #205 启动事件追踪器：弹窗关掉后事件仍留底 —— 「已安装管理」里的
     * 时间线按钮可回放每台服务器的真实启动史（pid/argv/serverInfo/stderr）。
     */
    private val startupTracker = McpStartupTracker()

    init {
        refresh()
        loadMcpCatalog()
    }

    /** 全量刷新（IO 线程）：技能/MCP/连接器/插件快照 + cs-mem 健康数据。保留集成源列表避免安装后列表闪失。 */
    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { snapshotState() } ?: return@launch
            _uiState.update { state -> snapshot.copy(
                // 视图与瞬时态保留：scope 不保留 →「已安装管理」里任何开关/卸载/连接
                // （全部以 refresh() 收尾）都会把顶栏弹回「市场」视图；mcpConnecting
                // 不保留 → 长连接期间重进屏幕丢失防双击并发保护。
                // #206 修复：tier 不保留 → 任何操作后 Coding 市场选择被弹回 Agent 市场。
                scope = state.scope,
                mcpConnecting = state.mcpConnecting,
                tier = state.tier,
                // #205：进度弹窗与目录状态跨 refresh 保留 —— 连接完成后
                // refresh() 不再把弹窗抹掉（完整时间线由用户手动关闭）；
                // 目录资产只加载一次，不能被快照重置回空表。
                mcpStartup = state.mcpStartup,
                mcpCatalog = state.mcpCatalog,
                mcpCatalogLoaded = state.mcpCatalogLoaded,
                mcpCatalogError = state.mcpCatalogError,
                mcpCatalogCategory = state.mcpCatalogCategory,
                catalogEnvEntry = state.catalogEnvEntry,
                selectedTab = state.selectedTab,
                modelScopeQuery = state.modelScopeQuery,
                modelScopeSkills = state.modelScopeSkills,
                modelScopeLoading = state.modelScopeLoading,
                modelScopeError = state.modelScopeError,
                githubQuery = state.githubQuery,
                githubHits = state.githubHits,
                githubSearching = state.githubSearching,
                githubError = state.githubError,
                skillSource = state.skillSource,
                clawHubSkills = state.clawHubSkills,
                clawHubLoading = state.clawHubLoading,
                clawHubError = state.clawHubError,
                clawHubQuery = state.clawHubQuery,
                clawHubQueryLoading = state.clawHubQueryLoading,
                clawHubInstallingSlug = state.clawHubInstallingSlug,
                clawHubHasMore = state.clawHubHasMore,
                clawHubSearchActive = state.clawHubSearchActive,
                categoryFilter = state.categoryFilter,
                skillQuery = state.skillQuery,
                skillSuggestions = state.skillSuggestions,
                detailSkillId = state.detailSkillId,
                detailState = state.detailState,
                detailLoading = state.detailLoading,
                busy = state.busy,
                lastMessage = state.lastMessage
            ) }
        }
    }

    private suspend fun snapshotState(): MarketUiState? {
        return runCatching {
            val installed = skillRegistry.getInstalled()
            val installedIds = installed.map { it.manifest.id }.toSet()

            // cs-mem 健康批量投影：为每个已安装技能取 energy/crystallized/lastUsedAt/success/failure
            val healthMap = skillAnalytics.batchProjectSkillHealth(installedIds.toList())

            val skills = installed.map {
                val h = healthMap[it.manifest.id]
                MarketSkillRow(
                    id = it.manifest.id,
                    name = it.manifest.name,
                    description = it.manifest.description,
                    installed = true,
                    enabled = it.enabled,
                    energy = h?.energy ?: 1.0f,
                    isCrystallized = h?.isCrystallized ?: false,
                    lastUsedAt = h?.lastUsedAt ?: 0,
                    successCount = h?.successCount ?: 0,
                    failureCount = h?.failureCount ?: 0,
                    category = it.manifest.category,
                    tags = it.manifest.tags,
                    author = it.manifest.author,
                    version = it.manifest.version,
                    trustLevel = it.manifest.trustLevel,
                    bundled = it.manifest.bundled,
                    scope = it.manifest.scope
                )
            }
            val templates = skillMenuProvider.getBuiltinTemplates().map { t ->
                val tpl = SkillMenuProvider.BUILTIN_TEMPLATES.firstOrNull { it.id == t.id }
                MarketSkillRow(
                    id = t.id, name = t.label, description = t.description,
                    installed = false, enabled = false, source = "builtin", builtin = true,
                    category = tpl?.category,
                    tags = tpl?.tags.orEmpty()
                )
            }
            val connected = mcpManager.getConnectedServers().toSet()
            val mcps = mcpManager.getConfigs().map {
                MarketMcpRow(
                    name = it.name,
                    endpoint = it.endpointSummary(),
                    transport = it.transport,
                    enabled = it.enabled,
                    connected = it.name in connected,
                    builtin = it.transport == McpTransport.BUILTIN,
                    scope = it.scope
                )
            }
            val connectors = connectorRegistry.getAll()
            val loaded = pluginManager.loadedPlugins.value.keys
            val plugins = pluginManager.discoverPlugins().map {
                MarketPluginRow(it.packageName, it.label, it.packageName in loaded)
            }
            MarketUiState(
                skills = skills,
                skillTemplates = templates,
                mcps = mcps,
                connectors = connectors,
                plugins = plugins,
                installedModelScopeIds = installedIds
            )
        }.getOrNull()
    }

    fun selectTab(tab: MarketTab) = _uiState.update { it.copy(selectedTab = tab) }

    /** 切换顶栏视图（市场 ⇄ 已安装管理），保留当前子页签。 */
    fun selectScope(scope: MarketScope) = _uiState.update { it.copy(scope = scope) }

    // #206：selectTier 迁至 MCP 目录区（切换分级时同步校正目录分类选择），见下方。

    /** #197 当前分级下可见的技能列表（scope=all 两级都保留）。 */
    fun visibleSkills(state: MarketUiState): List<MarketSkillRow> {
        val scope = state.tier.name.lowercase()
        return state.skills.filter { it.scope == "all" || it.scope == scope }
    }

    /** #197 当前分级下可见的 MCP 列表。 */
    fun visibleMcps(state: MarketUiState): List<MarketMcpRow> {
        val scope = state.tier.name.lowercase()
        return state.mcps.filter { it.scope == "all" || it.scope == scope }
    }

    fun clearMessage() = _uiState.update { it.copy(lastMessage = null) }

    private fun message(msg: String) = _uiState.update { it.copy(lastMessage = msg) }

    // ═══ Skills ═══

    fun toggleSkill(skillId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            skillRegistry.setEnabled(skillId, enabled)
            refresh()
        }
    }

    fun uninstallSkill(skillId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Issue #166：内置技能不可卸载（SkillRegistry.uninstall 对 bundled 恒 false），
            // 提前拦下并给出正确提示——否则会落进「未找到技能」的误导文案。
            if (_uiState.value.skills.any { it.id == skillId && it.bundled }) {
                message(languageManager.getString(R.string.market_skill_bundled_uninstall_blocked_hint))
                return@launch
            }
            val ok = skillRegistry.uninstall(skillId)
            message(
                if (ok) {
                    languageManager.getString(R.string.market_skill_uninstalled).format(skillId)
                } else {
                    languageManager.getString(R.string.market_skill_not_found).format(skillId)
                }
            )
            refresh()
        }
    }

    fun installSkillTemplate(templateId: String) {
        viewModelScope.launch {
            installManager.installSkillTemplate(templateId).fold(
                onSuccess = { message(it) },
                onFailure = {
                    message(languageManager.getString(R.string.market_install_failed).format(it.message ?: ""))
                }
            )
            refresh()
        }
    }

    /** 导入自定义 Skill JSON（粘贴内容）。 */
    fun importSkillJson(content: String) {
        viewModelScope.launch {
            installManager.installSkillFromJson(content).fold(
                onSuccess = { message(it) },
                onFailure = {
                    message(languageManager.getString(R.string.market_import_failed).format(it.message ?: ""))
                }
            )
            refresh()
        }
    }

    /** 从 URL 导入 Skill manifest。 */
    fun importSkillFromUrl(url: String) {
        viewModelScope.launch {
            installManager.installSkillFromUrl(url).fold(
                onSuccess = { message(it) },
                onFailure = {
                    message(languageManager.getString(R.string.market_import_failed).format(it.message ?: ""))
                }
            )
            refresh()
        }
    }

    /**
     * 从本地文件导入 Skill（.zip / .json 自动识别）。
     *
     * 用户反馈"Skills 不能自己导入"：本地 skill 包此前没有任何入口。
     * zip 走 SafeZipExtractor（路径穿越 / zip bomb 防御），json 直接装 manifest。
     */
    fun importSkillFromFile(uri: android.net.Uri) {
        viewModelScope.launch {
            installManager.installSkillFromFile(uri).fold(
                onSuccess = { message(it) },
                onFailure = {
                    message(it.message ?: languageManager.getString(R.string.market_local_import_failed))
                }
            )
            refresh()
        }
    }

    /**
     * 从本地文件导入 MCP 配置（`{"mcpServers": {...}}` 形态的 .json）。
     * 复用 [importMcpConfig] 的解析与逐条报错管道。
     */
    fun importMcpConfigFromFile(uri: android.net.Uri) {
        viewModelScope.launch {
            installManager.readTextFile(uri).fold(
                onSuccess = { text -> importMcpConfig(text) },
                onFailure = {
                    message(
                        languageManager.getString(R.string.market_read_file_failed)
                            .format(it.message ?: "")
                    )
                }
            )
        }
    }

    // ═══ MCP ═══

    /**
     * #205 共享启动监听器：真实事件双写 —— tracker 留底（事后时间线可回放）
     * + uiState.mcpStartup（弹窗实时渲染）。所有连接路径统一走它。
     *
     * #206 修复：仅当弹窗属于同一服务器且未被用户关闭时追加 —— 旧行为里
     * 用户点「隐藏」后，后续 stderr 事件（每连接最多 30 行）会把弹窗反复拉起。
     */
    private fun startupListenerFor(name: String): McpStartupListener =
        McpStartupListener { event ->
            startupTracker.record(event)
            _uiState.update { s ->
                s.copy(
                    mcpStartup = s.mcpStartup
                        ?.takeIf { it.serverName == name && it.running }
                        ?.let { it.copy(events = it.events + event, running = event.stage != McpStartupStage.FAILED) }
                )
            }
        }

    /**
     * #206 连接成功的收尾：真实 tools/list 发现 + TOOLS_DISCOVERED 事件
     * （[McpStartupTracker.recordToolsIfChanged] 去重重记）+ 弹窗停止转圈。
     * connect/add/update 三条路径共用。
     */
    private suspend fun finishStartupWithTools(name: String) {
        val tools = mcpManager.listServerTools(name)
        val toolNames = tools.map { it.name }
        // tracker 侧：同清单不重记（重连场景不刷重复行）。
        startupTracker.recordToolsIfChanged(name, toolNames)
        val detail = "发现 ${tools.size} 个工具：" + toolNames.take(6).joinToString("、") +
            if (tools.size > 6) " …" else ""
        _uiState.update { s ->
            s.copy(
                mcpStartup = s.mcpStartup
                    ?.takeIf { it.serverName == name }
                    ?.let { it.copy(events = it.events + McpStartupEvent(name, McpStartupStage.TOOLS_DISCOVERED, detail), running = false) }
            )
        }
    }

    /**
     * 添加 MCP 工具源。
     *
     * 注意 STDIO 不是"服务器 URL"：它是本地命令（command + args + env），
     * 添加成功后 connect 会真正把这个进程拉起来做 JSON-RPC 握手。
     *
     * #205：添加后的自动连接也接上真实启动事件（进度弹窗 + tracker 留底）
     * —— 旧版只有手动「连接」才弹进度，添加失败时用户只看到一句抽象报错。
     */
    fun addMcpServer(config: McpServerConfig) {
        viewModelScope.launch {
            val name = config.name.trim()
            // #197 市场分级：从当前分级带入作用域（Agent 市场添加的归 agent 工位，
            // Coding 市场添加的归 coding 工位；导入/编辑可改）。
            val tierScope = _uiState.value.tier.name.lowercase()
            val scoped = if (config.scope == "all" && config.transport != McpTransport.BUILTIN) {
                config.copy(name = name, scope = tierScope)
            } else {
                config.copy(name = name)
            }
            mcpManager.addServer(scoped).fold(
                    onSuccess = {
                        // P2：连接结果不再被吞 —— 添加后立即连接失败（URL 错/命令不存在）时
                        // 用户只看到「已添加」成功提示，错误静默丢失。fold 进同一条 snackbar。
                        // #205：连接过程有真实启动事件弹窗（环境/spawn/握手/stderr）。
                        _uiState.update { it.copy(mcpConnecting = name, mcpStartup = McpStartupUi(serverName = name)) }
                        val connectMsg = mcpManager.connect(name, startupListenerFor(name)).fold(
                            onSuccess = { languageManager.getString(R.string.market_mcp_added).format(name) },
                            onFailure = {
                                languageManager.getString(R.string.market_add_failed)
                                    .format("${it.message ?: ""}")
                            }
                        )
                        // #206 修复：连接成功也做 tools 发现收尾，弹窗不再无限转圈。
                        if (mcpManager.getConnectedServers().contains(name)) {
                            finishStartupWithTools(name)
                        } else {
                            _uiState.update { s ->
                                s.copy(mcpStartup = s.mcpStartup
                                    ?.takeIf { it.serverName == name }?.let { it.copy(running = false) })
                            }
                        }
                        message(connectMsg)
                        _uiState.update { it.copy(mcpConnecting = null) }
                        refresh()
                    },
                onFailure = {
                    message(languageManager.getString(R.string.market_add_failed).format(it.message ?: ""))
                }
            )
        }
    }

    /**
     * 导入社区通用 MCP 配置 JSON（`{"mcpServers": {...}}`）。
     *
     * 支持 `command/args/env`（STDIO 本地命令）与 `type=streamable_http|sse` + `url`
     * 两种形态；解析不通过的条目会**逐条报出原因**，不会静默丢配置。
     */
    fun importMcpConfig(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val parsed = McpConfigImport.parse(text)
            // 分隔符 / “名称：原因”模板按语言取（en 用 "; " 与 ": "）
            val sep = languageManager.getString(R.string.market_list_sep)
            val nameReason = { serverName: String, reason: String ->
                languageManager.getString(R.string.market_name_reason).format(serverName, reason)
            }
            if (parsed.configs.isEmpty()) {
                val detail = parsed.errors.joinToString(sep) { nameReason(it.serverName, it.reason) }
                    .ifBlank { languageManager.getString(R.string.market_mcp_import_empty) }
                message(languageManager.getString(R.string.market_mcp_import_none).format(detail))
                return@launch
            }
            val added = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (config in parsed.configs) {
                val exists = mcpManager.getConfigs().any { it.name == config.name }
                val unique = if (exists) config.copy(name = "${config.name}-${System.currentTimeMillis()}") else config
                mcpManager.addServer(unique).fold(
                    onSuccess = { added += unique.name },
                    onFailure = { failed += nameReason(unique.name, it.message ?: "") }
                )
            }
            val summary = buildString {
                append(languageManager.getString(R.string.market_mcp_imported_count).format(added.size))
                if (failed.isNotEmpty()) {
                    append(
                        languageManager.getString(R.string.market_mcp_import_failed_part)
                            .format(failed.size, failed.joinToString(sep))
                    )
                }
                if (parsed.errors.isNotEmpty()) {
                    append(
                        languageManager.getString(R.string.market_mcp_import_skipped_part).format(
                            parsed.errors.size,
                            parsed.errors.joinToString(sep) { nameReason(it.serverName, it.reason) }
                        )
                    )
                }
            }
            message(summary)
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun toggleMcp(name: String, enabled: Boolean) {
        viewModelScope.launch {
            mcpManager.setEnabled(name, enabled).fold(
                onSuccess = {
                    message(
                        if (enabled) {
                            languageManager.getString(R.string.market_mcp_enabled).format(name)
                        } else {
                            languageManager.getString(R.string.market_mcp_disabled_disconnected).format(name)
                        }
                    )
                    refresh()
                },
                onFailure = {
                    message(languageManager.getString(R.string.market_action_failed).format(it.message ?: ""))
                }
            )
        }
    }

    fun connectMcp(name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(mcpConnecting = name, mcpStartup = McpStartupUi(serverName = name)) }
            // #197/#205 真实启动事件流：共享监听器（tracker 留底 + 弹窗实时渲染），
            // 每个真实阶段（env/spawn/initialize/initialized/stderr）逐条可见；
            // 连接成功后再做一次真实 tools/list 发现（这也是 McpToolRegistrar
            // 的注册路径）。
            val listener = startupListenerFor(name)
            try {
                mcpManager.connect(name, listener).fold(
                    onSuccess = {
                        // 真实工具发现：与 McpToolRegistrar 同一数据源（listServerTools）。
                        // #206：收尾逻辑抽取为 finishStartupWithTools（tracker 去重重记）。
                        finishStartupWithTools(name)
                        message(languageManager.getString(R.string.market_mcp_connected).format(name))
                    },
                    onFailure = {
                        message(languageManager.getString(R.string.market_connect_failed).format(it.message ?: ""))
                    }
                )
            } finally {
                _uiState.update { it.copy(mcpConnecting = null) }
            }
            refresh()
        }
    }

    /** #197 关闭启动进度弹窗（连接已完成/失败后用户手动关闭）。 */
    fun dismissMcpStartup() {
        _uiState.update { it.copy(mcpStartup = null) }
    }

    fun disconnectMcp(name: String) {
        viewModelScope.launch {
            mcpManager.disconnect(name)
            message(languageManager.getString(R.string.market_mcp_disconnected).format(name))
            refresh()
        }
    }

    fun removeMcp(name: String) {
        viewModelScope.launch {
            mcpManager.removeServer(name)
            startupTracker.clear(name)
            message(languageManager.getString(R.string.market_mcp_removed).format(name))
            refresh()
        }
    }

    // ═══ MCP · 配置编辑（已安装管理「编辑」入口）═══

    /** 正在编辑的 MCP 配置快照（null = 编辑器关闭）。UI 据此渲染 [EditMcpDialog]。 */
    private val _editingMcp = MutableStateFlow<McpServerConfig?>(null)
    val editingMcp: StateFlow<McpServerConfig?> = _editingMcp.asStateFlow()

    /** 打开编辑器：按名取配置快照（不存在则忽略 —— 列表与配置极小概率失同步）。 */
    fun openMcpEditor(name: String) {
        val config = mcpManager.getConfigs().firstOrNull { it.name == name } ?: return
        _editingMcp.value = config
    }

    fun closeMcpEditor() {
        _editingMcp.value = null
    }

    /**
     * 保存编辑后的配置：断开旧连接（配置已变，旧连接必然失效）→ 覆盖写 →
     * enabled 时自动重连（对齐「添加并连接」的行为闭环）。
     *
     * #205：重连同样接上真实启动事件（进度弹窗 + tracker 留底）。
     */
    fun updateMcpServer(config: McpServerConfig) {
        viewModelScope.launch {
            val name = config.name.trim()
            // 先断开：addServer 只覆盖配置不触碰活跃连接，旧 client 挂着旧参数
            mcpManager.disconnect(name)
            mcpManager.addServer(config.copy(name = name)).fold(
                onSuccess = {
                    // 连接结果折叠（同 addMcpServer）：编辑保存后重连失败不再静默。
                    if (config.enabled) {
                        _uiState.update { it.copy(mcpConnecting = name, mcpStartup = McpStartupUi(serverName = name)) }
                        val connectMsg = mcpManager.connect(name, startupListenerFor(name)).fold(
                            onSuccess = {
                                languageManager.getString(R.string.market_mcp_edit_saved).format(name)
                            },
                            onFailure = {
                                languageManager.getString(R.string.market_add_failed)
                                    .format("${it.message ?: ""}")
                            }
                        )
                        // #206 修复：与添加路径同构的 tools 发现收尾，弹窗不转圈。
                        if (mcpManager.getConnectedServers().contains(name)) {
                            finishStartupWithTools(name)
                        } else {
                            _uiState.update { s ->
                                s.copy(mcpStartup = s.mcpStartup
                                    ?.takeIf { it.serverName == name }?.let { it.copy(running = false) })
                            }
                        }
                        message(connectMsg)
                        _uiState.update { it.copy(mcpConnecting = null) }
                    } else {
                        message(languageManager.getString(R.string.market_mcp_edit_saved).format(name))
                    }
                    refresh()
                },
                onFailure = {
                    message(languageManager.getString(R.string.market_add_failed).format(it.message ?: ""))
                }
            )
            _editingMcp.value = null
        }
    }

    // ═══ MCP · 精选目录（#205：assets/mcp_catalog 离线随包，一键安装）═══

    /**
     * 加载精选目录：遍历 assets/mcp_catalog 下的分类 JSON → [McpServerCatalog.parseCategoryFile]。
     *
     * 单文件损坏只降级跳过（错误计入 [MarketUiState.mcpCatalogError]），
     * 不拖垮其余分类。只加载一次（[init]），refresh 不重置。
     *
     * #206 加固：跨文件重复 id 与非法条目不进入 [MarketUiState.mcpCatalog]
     * （LazyColumn 的 item key 与安装装配都假定 id 唯一合法——有问题条目
     * 只留在错误提示里，不进渲染列表）。
     */
    private fun loadMcpCatalog() {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = mutableListOf<McpServerCatalog.McpCatalogEntry>()
            val errors = mutableListOf<String>()
            runCatching {
                val names = appContext.assets.list("mcp_catalog").orEmpty()
                    .filter { it.endsWith(".json") }.sorted()
                require(names.isNotEmpty()) { "目录资产缺失（assets/mcp_catalog）" }
                for (file in names) {
                    val text = appContext.assets.open("mcp_catalog/$file")
                        .bufferedReader().use { it.readText() }
                    McpServerCatalog.parseCategoryFile(text).fold(
                        onSuccess = { entries += it.entries },
                        onFailure = { errors += "${file}: ${it.message}" }
                    )
                }
            }.onFailure { errors += it.message.orEmpty() }
            val issues = McpServerCatalog.validateEntries(entries)
            // 跨文件去重（首个胜出，后到的重复 id 只报错不渲染）。
            val seen = HashSet<String>()
            val deduped = entries.filter { seen.add(it.id) }
            val dedupDrops = entries.size - deduped.size
            _uiState.update {
                it.copy(
                    mcpCatalog = deduped,
                    mcpCatalogLoaded = true,
                    mcpCatalogError = buildList {
                        errors.forEach { e -> add(e) }
                        issues.forEach { i -> add("${i.entryId}: ${i.reason}") }
                        if (dedupDrops > 0) add("跨文件重复 id：已丢弃 $dedupDrops 条")
                    }.takeIf { l -> l.isNotEmpty() }?.joinToString("；")
                )
            }
        }
    }

    /** 当前分级下可见的目录条目（#197 tier 过滤 + 分类过滤）。 */
    fun visibleCatalog(state: MarketUiState): List<McpServerCatalog.McpCatalogEntry> {
        val tier = state.tier.name.lowercase()
        return state.mcpCatalog
            .filter { it.visibleToTier(tier) }
            .filter { state.mcpCatalogCategory == null || it.category == state.mcpCatalogCategory }
    }

    /** 目录分类切换（null = 全部）。 */
    fun selectCatalogCategory(category: String?) {
        _uiState.update { it.copy(mcpCatalogCategory = category) }
    }

    /**
     * #197/#206 切换市场分级：同时校正目录分类选择 —— coding 专属分类在
     * Agent 分级下无条目，旧选择留着只会得到空列表。
     */
    fun selectTier(tier: MarketTier) {
        _uiState.update { state ->
            val catStillVisible = state.mcpCatalogCategory?.let { cat ->
                state.mcpCatalog.any { it.category == cat && it.visibleToTier(tier.name.lowercase()) }
            } ?: true
            state.copy(
                tier = tier,
                mcpCatalogCategory = if (catStillVisible) state.mcpCatalogCategory else null
            )
        }
    }

    /**
     * 目录条目是否已安装：精确名匹配 + 安装时生成的序号后缀形态
     * （id-2 / id-3…）；不再用宽泛前缀（`github-backup` 不再误判成 `github`）。
     */
    fun isCatalogEntryInstalled(entry: McpServerCatalog.McpCatalogEntry): Boolean {
        val suffixPattern = Regex("^${Regex.escape(entry.id)}-\\d+$")
        return mcpManager.getConfigs().any { it.name == entry.id || suffixPattern.matches(it.name) }
    }

    /** 打开目录条目的环境变量弹窗（无必填变量时 UI 直接走安装）。 */
    fun openCatalogEnvDialog(entry: McpServerCatalog.McpCatalogEntry) {
        _uiState.update { it.copy(catalogEnvEntry = entry) }
    }

    fun closeCatalogEnvDialog() {
        _uiState.update { it.copy(catalogEnvEntry = null) }
    }

    /**
     * 安装目录条目：必填环境变量预检 → 预检（[McpConfigValidator]）→
     * 重名自动加序号 → addServer + connect（真实启动事件弹窗 + tracker 留底）。
     *
     * #206 加固：busy 锁防双击并发重入（旧行为两连点会各自 addServer 互相
     * 覆盖）；序号后缀改为递增扫描（时间戳取模可能撞车）；装配包裹
     * runCatching（非法目录条目不再炸协程）。
     */
    fun installCatalogEntry(entry: McpServerCatalog.McpCatalogEntry, envValues: Map<String, String>) {
        viewModelScope.launch {
            _uiState.update { it.copy(catalogEnvEntry = null) }
            val missing = McpServerCatalog.missingRequiredEnv(entry, envValues)
            if (missing.isNotEmpty()) {
                message(languageManager.getString(R.string.market_mcp_catalog_missing_env).format(missing.joinToString("、")))
                return@launch
            }
            if (_uiState.value.mcpConnecting != null) return@launch // 已有连接在进行：防双击
            // 重名递增：filesystem 已存在 → filesystem-2 → filesystem-3…
            val existing = mcpManager.getConfigs().map { it.name }.toSet()
            val name = if (entry.id !in existing) entry.id
            else {
                var n = 2
                while ("${entry.id}-$n" in existing) n++
                "${entry.id}-$n"
            }
            val sandboxAvailable = java.io.File(appContext.filesDir, "rootfs/ubuntu/current").exists()
            val config = runCatching {
                McpServerCatalog.toServerConfig(
                    entry, envValues, name,
                    runInSandbox = sandboxAvailable && entry.transport == McpTransport.STDIO
                )
            }.getOrElse {
                message(languageManager.getString(R.string.market_mcp_catalog_preflight_failed).format(it.message ?: ""))
                return@launch
            }
            // #205 预检：静态可判定的问题当场报出（重名/沙箱未装/URL 非法/注入字符）。
            val findings = McpConfigValidator.validate(
                config,
                existingNames = emptySet(), // 重名已自行处理为唯一名
                builtinNames = emptySet(),
                sandboxReady = if (config.runInSandbox) sandboxAvailable else null
            )
            McpConfigValidator.errorsOf(findings).let { errors ->
                if (errors.isNotEmpty()) {
                    message(
                        languageManager.getString(R.string.market_mcp_catalog_preflight_failed)
                            .format(errors.joinToString("；") { it.message })
                    )
                    return@launch
                }
            }
            addMcpServer(config)
        }
    }

    /**
     * #206 添加/编辑对话框的共享预检：全部 [McpConfigValidator] 规则
     * （含重名 —— 旧行为 addServer 直接覆盖同名配置造成无声数据丢失）。
     */
    fun validateMcpConfig(config: McpServerConfig): List<McpConfigValidator.Finding> {
        val existing = mcpManager.getConfigs().map { it.name }.toSet()
        val sandboxAvailable = java.io.File(appContext.filesDir, "rootfs/ubuntu/current").exists()
        return McpConfigValidator.validate(
            config,
            existingNames = existing,
            builtinNames = mcpManager.getConfigs()
                .filter { it.transport == McpTransport.BUILTIN }.map { it.name }.toSet(),
            sandboxReady = if (config.runInSandbox) sandboxAvailable else null
        )
    }

    /** #205 回放某台服务器的启动时间线（tracker 留底，弹窗只读渲染）。 */
    fun showMcpTimeline(name: String) {
        // #206 修复：不覆盖进行中的实时弹窗（连接事件会继续追加进历史快照造成混排）。
        if (_uiState.value.mcpConnecting != null) return
        _uiState.update {
            it.copy(mcpStartup = McpStartupUi(
                serverName = name,
                events = startupTracker.snapshot(name),
                running = false
            ))
        }
    }

    /** #205 该服务器是否有时间线可看（installed 行按钮的可见性）。 */
    fun hasMcpTimeline(name: String): Boolean = startupTracker.hasEvents(name)

    // ═══ 连接器 ═══

    fun addConnector(id: String, name: String, type: String, endpoint: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmedId = id.trim()
            connectorRegistry.add(
                ConnectorDef(id = trimmedId, name = name.trim(), type = type, endpoint = endpoint.trim())
            ).fold(
                onSuccess = {
                    message(languageManager.getString(R.string.market_connector_added).format(trimmedId))
                },
                onFailure = {
                    message(languageManager.getString(R.string.market_add_failed).format(it.message ?: ""))
                }
            )
            refresh()
        }
    }

    fun toggleConnector(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            connectorRegistry.setEnabled(id, enabled)
            refresh()
        }
    }

    fun removeConnector(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            connectorRegistry.remove(id)
            message(languageManager.getString(R.string.market_connector_removed).format(id))
            refresh()
        }
    }

    // ═══ 插件 ═══

    fun loadPlugin(row: MarketPluginRow) {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                pluginManager.discoverPlugins().firstOrNull { it.packageName == row.packageName }
            } ?: return@launch
            // bindService 需在 Looper 线程调用（Context 契约），回调本身回主线程
            withContext(Dispatchers.Main) { pluginManager.loadPlugin(info) }
            message(languageManager.getString(R.string.market_plugin_load_requested).format(info.label))
            refresh()
        }
    }

    fun unloadPlugin(packageName: String) {
        viewModelScope.launch {
            // unbindService 同样需在主线程执行
            withContext(Dispatchers.Main) { pluginManager.unloadPlugin(packageName) }
            message(languageManager.getString(R.string.market_plugin_unloaded).format(packageName))
            refresh()
        }
    }

    // ═══ 集成：魔搭 ═══

    fun updateModelScopeQuery(query: String) =
        _uiState.update { it.copy(modelScopeQuery = query) }

    fun loadModelScopeSkills() {
        viewModelScope.launch {
            _uiState.update { it.copy(modelScopeLoading = true, modelScopeError = null) }
            modelScopeList()
            _uiState.update { it.copy(modelScopeLoading = false) }
        }
    }

    private suspend fun modelScopeList() {
        modelScopeSource.listSkills().fold(
            onSuccess = { skills ->
                allModelScopeSkills = skills
                val filtered = modelScopeSource.filterSkills(skills, _uiState.value.modelScopeQuery)
                _uiState.update { it.copy(modelScopeSkills = filtered) }
            },
            onFailure = { e ->
                allModelScopeSkills = emptyList()
                _uiState.update { it.copy(modelScopeSkills = emptyList(), modelScopeError = e.message) }
            }
        )
    }

    fun filterModelScope(query: String) {
        _uiState.update { it.copy(modelScopeQuery = query) }
        val filtered = modelScopeSource.filterSkills(allModelScopeSkills, query)
        _uiState.update { it.copy(modelScopeSkills = filtered) }
    }

    fun installModelScopeSkill(skill: ModelScopeSource.ModelScopeSkill) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            installManager.installModelScopeSkill(skill).fold(
                onSuccess = { message(it) },
                onFailure = {
                    message(
                        languageManager.getString(R.string.market_modelscope_install_failed)
                            .format(it.message ?: "")
                    )
                }
            )
            _uiState.update { it.copy(busy = false) }
            refresh()
        }
    }

    // ═══ 集成：GitHub ═══

    fun updateGithubQuery(query: String) = _uiState.update { it.copy(githubQuery = query) }

    fun searchGithub() {
        viewModelScope.launch {
            val query = _uiState.value.githubQuery.trim()
            if (query.isBlank()) return@launch
            _uiState.update { it.copy(githubSearching = true, githubError = null) }
            installManager.searchGitHubSkills(query).fold(
                onSuccess = { hits -> _uiState.update { it.copy(githubHits = hits) } },
                onFailure = { e -> _uiState.update { it.copy(githubHits = emptyList(), githubError = e.message) } }
            )
            _uiState.update { it.copy(githubSearching = false) }
        }
    }

    fun installGithubRepo(fullName: String) = installFromRepoInput(fullName)

    /**
     * 真实联网安装：接受 `owner/repo`、GitHub 链接、git@ 链接任一种写法。
     * 走 raw.githubusercontent.com 取 manifest / SKILL.md —— 不再"点一下就假装装好了"。
     */
    fun installFromRepoInput(input: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            installManager.installSkillFromRepoInput(input).fold(
                onSuccess = { message(it) },
                onFailure = {
                    message(languageManager.getString(R.string.market_install_failed).format(it.message ?: ""))
                }
            )
            _uiState.update { it.copy(busy = false) }
            refresh()
        }
    }

    // ═══ Skills 页签：ClawHub 技能仓库 ═══

    /** 切换 Skills 页签的仓库源（本地 ⇄ ClawHub）。 */
    fun selectSkillSource(source: SkillRepoSource) =
        _uiState.update { it.copy(skillSource = source) }

    fun updateClawHubQuery(query: String) =
        _uiState.update { it.copy(clawHubQuery = query) }

    /** 加载 ClawHub 热门列表（第一页；也用作「返回热门」）。 */
    fun loadClawHubTrending() {
        viewModelScope.launch {
            _uiState.update { it.copy(clawHubLoading = true, clawHubError = null) }
            clawHubSearchMode = false
            clawHubOffset = 0
            clawHubSource.listTrending(limit = ClawHubSource.TRENDING_PAGE_SIZE, offset = 0).fold(
                onSuccess = { page ->
                    clawHubOffset = ClawHubSource.TRENDING_PAGE_SIZE
                    _uiState.update {
                        it.copy(
                            clawHubSkills = page.entries,
                            clawHubHasMore = page.hasMore,
                            clawHubSearchActive = false
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(clawHubSkills = emptyList(), clawHubError = e.message) }
                }
            )
            _uiState.update { it.copy(clawHubLoading = false) }
        }
    }

    /** 关键词搜索（空关键词退回热门列表，与搜索端点「必须有关键词」的行为对齐）。 */
    fun searchClawHub() {
        val query = _uiState.value.clawHubQuery.trim()
        if (query.isEmpty()) {
            loadClawHubTrending()
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(clawHubQueryLoading = true, clawHubError = null) }
            clawHubSearchMode = true
            clawHubSearchQuery = query
            clawHubOffset = 0
            clawHubSource.search(query, limit = ClawHubSource.DEFAULT_PAGE_SIZE, offset = 0).fold(
                onSuccess = { page ->
                    clawHubOffset = ClawHubSource.DEFAULT_PAGE_SIZE
                    _uiState.update {
                        it.copy(
                            clawHubSkills = page.entries,
                            clawHubHasMore = page.hasMore,
                            clawHubSearchActive = true
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(clawHubSkills = emptyList(), clawHubError = e.message) }
                }
            )
            _uiState.update { it.copy(clawHubQueryLoading = false) }
        }
    }

    /**
     * 加载下一页（追加并按 owner/slug 去重；热门与搜索模式共用）。
     *
     * 服务端 offset 翻页实测暂不生效（返回重复页）：去重后 0 新条目时
     * 自动收起「加载更多」，避免无限空点；若服务端将来修复 offset，
     * 本逻辑无需改动即自动恢复真分页。
     */
    fun loadMoreClawHub() {
        val current = _uiState.value
        if (current.clawHubLoading || current.clawHubQueryLoading || !current.clawHubHasMore) return
        viewModelScope.launch {
            _uiState.update { it.copy(clawHubLoading = true) }
            val pageSize = if (clawHubSearchMode) {
                ClawHubSource.DEFAULT_PAGE_SIZE
            } else {
                ClawHubSource.TRENDING_PAGE_SIZE
            }
            val result = if (clawHubSearchMode) {
                clawHubSource.search(
                    clawHubSearchQuery,
                    limit = pageSize,
                    offset = clawHubOffset
                )
            } else {
                clawHubSource.listTrending(
                    limit = pageSize,
                    offset = clawHubOffset
                )
            }
            result.fold(
                onSuccess = { page ->
                    clawHubOffset += pageSize
                    _uiState.update { state ->
                        val seen = state.clawHubSkills.map { it.key }.toHashSet()
                        val fresh = page.entries.filter { it.key !in seen }
                        state.copy(
                            clawHubSkills = state.clawHubSkills + fresh,
                            // 全重复页（服务端 offset 未生效）→ 收起加载更多
                            clawHubHasMore = page.hasMore && fresh.isNotEmpty()
                        )
                    }
                },
                onFailure = { e ->
                    // 追加失败：保留已有列表，仅提示错误，不动页码
                    _uiState.update { it.copy(clawHubError = e.message) }
                }
            )
            _uiState.update { it.copy(clawHubLoading = false) }
        }
    }

    /** 失败重试（按当前模式走热门或搜索）。 */
    fun retryClawHub() {
        if (clawHubSearchMode) searchClawHub() else loadClawHubTrending()
    }

    /** 从 ClawHub 安装（下载 ZIP → 转 prompt 型技能 → SkillRegistry）。 */
    fun installClawHub(entry: ClawHubSource.ClawHubSkillEntry) {
        viewModelScope.launch {
            _uiState.update { it.copy(clawHubInstallingSlug = entry.slug) }
            try {
                installManager.installClawHubSkill(entry).fold(
                    onSuccess = { message(it) },
                    onFailure = {
                        message(
                            languageManager.getString(R.string.market_clawhub_install_failed)
                                .format(it.message ?: "")
                        )
                    }
                )
            } finally {
                // 无论成败都复位行级 busy 并刷新已安装列表（「已安装」Chip 依据 skills 快照）
                _uiState.update { it.copy(clawHubInstallingSlug = null) }
                refresh()
            }
        }
    }

    // ═══ 认知市场：详情 + 分类过滤 + 模糊搜索 ═══

    /**
     * 打开技能详情对话框：异步从 cs-mem + ToolUsageTracker + CircuitBreaker + TraceRecorder
     * 投影出 [SkillDetailUiState]（能源/结晶/调用统计/熔断/轨迹）。
     */
    fun loadSkillDetail(skillId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(detailSkillId = skillId, detailLoading = true, detailState = null) }
            val manifest = withContext(Dispatchers.IO) {
                skillRegistry.getInstalled().firstOrNull { it.manifest.id == skillId }?.manifest
            }
            val detail = skillAnalytics.projectSkillAnalytics(skillId, manifest)
            _uiState.update { it.copy(detailState = detail, detailLoading = false) }
        }
    }

    /** 关闭技能详情对话框。 */
    fun closeSkillDetail() {
        _uiState.update { it.copy(detailSkillId = null, detailState = null, detailLoading = false) }
    }

    /** 设置分类过滤（null = 全部分类）。 */
    fun setCategoryFilter(category: String?) {
        _uiState.update { it.copy(categoryFilter = category) }
    }

    /**
     * 本地技能搜索：对已安装 + 内置模板做 ToolSuggester 模糊匹配。
     * 输入空时清空建议并显示全部。
     */
    fun setSkillQuery(query: String) {
        val trimmed = query.trim()
        _uiState.update { it.copy(skillQuery = trimmed) }
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(skillSuggestions = emptyList()) }
            return
        }
        viewModelScope.launch {
            // 候选 id = 已安装 + 内置模板
            val candidates = withContext(Dispatchers.IO) {
                val installed = skillRegistry.getInstalled().map { it.manifest.id }
                val templates = SkillMenuProvider.BUILTIN_TEMPLATES.map { it.id }
                (installed + templates).distinct()
            }
            val suggestions = com.apex.agent.core.tools.ToolSuggester.suggest(
                trimmed, candidates, maxSuggestions = 5
            )
            _uiState.update { it.copy(skillSuggestions = suggestions) }
        }
    }

    /**
     * 对当前已安装技能 + 内置模板应用分类过滤 + 搜索查询。
     * 返回过滤后的技能列表（UI 渲染用）。
     */
    fun filteredSkills(): List<MarketSkillRow> {
        val state = _uiState.value
        val all = state.skills + state.skillTemplates
        return all.filter { row ->
            // #206 分类过滤：键为 SkillCategory.key（categoryFilter 与 chip 同域）；
            // 「未分类」哨兵（UNCATEGORIZED_FILTER）匹配旧值残留行。
            (state.categoryFilter == null ||
                (if (state.categoryFilter == UNCATEGORIZED_FILTER) {
                    com.apex.agent.core.tools.skill.SkillCategory.of(row.category) == null
                } else {
                    row.category == state.categoryFilter
                })) &&
            // 搜索查询：精确匹配 id/name/tags/description
            (state.skillQuery.isBlank() ||
                row.id.contains(state.skillQuery, ignoreCase = true) ||
                row.name.contains(state.skillQuery, ignoreCase = true) ||
                row.description.contains(state.skillQuery, ignoreCase = true) ||
                row.tags.any { it.contains(state.skillQuery, ignoreCase = true) })
        }
    }

    companion object {
        /** #206「未分类」过滤哨兵（与真实域 key 不撞车的哨兵值）。 */
        const val UNCATEGORIZED_FILTER = "__uncategorized__"
    }
}
