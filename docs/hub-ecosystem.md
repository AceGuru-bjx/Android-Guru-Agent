# Hub 生态：官方技能 / MCP 仓库 × 斜杠门控 × 市场配置启动闭环

> 状态：已落地（v1）
> 主题：内置收敛 + 仓库分发 + 使用门控——技能与 MCP 从「全量内置」演进为
> 「少量必要内置 + 官方仓库按需安装」，市场完成 安装 → 配置 → 启动 全闭环。
> 前置：[agent-life-skills-and-memory.md](agent-life-skills-and-memory.md)（技能矩阵与渐进披露）
> · [mcp-sandbox.md](mcp-sandbox.md)（PRoot 沙箱 MCP）
> 参考：[opencode](https://opencode.ai/docs/skills/) 的远程技能注册表模式
> （`index.json` 元数据索引 + 按需下载 + 缓存原子更新）。

---

## 0. 一句话

内置瘦身到「必要最小集」，其余技能 / MCP 服务器全部放进两个 GitHub 仓库
（`apex-skill-hub` / `apex-mcp-hub`），市场直连安装；聊天斜杠菜单只放行
「已安装且已启用」的技能与「已安装且正在运行」的 MCP；MCP 的配置 / 启动 /
停止全部收敛进市场页。

## 1. 用户反馈与根因

| 反馈 | 根因 | 对策 |
| --- | --- | --- |
| 「Coding 的 skill 与 Agent 的 skill 要分开，市场分两级」 | 59/75 内置技能 scope 缺省 `all`，双工位市场都出现 | 生活技能全部 scope=agent 且迁出 APK；市场按 Agent/Coding 分级过滤（scope 口径贯通斜杠与注入） |
| 「生活相关技能全部删除，只内置几个功能强大的」 | 75 个全内置 = APK 膨胀 + 档案污染 | 保留 13 核心（8 coding + 3 agent + 2 all），62 个迁官方仓库 |
| 「新建一个仓库，市场直接下载里面的技能」 | 无自有分发渠道（ClawHub/魔搭是第三方） | 新建 `AceGuru-mjh/apex-skill-hub`（62 技能）+ `apex-mcp-hub`（8 台 MCP），市场「官方仓库」源直装 |
| 「MCP 同样处理，但要内置几个必要的（如 GitHub MCP）」 | 沙箱预置随启动自动写入 | 保留 5 台进程内 BUILTIN（github/search/fs/memory/thinking）；3 台 npx 沙箱预置迁仓库目录，不再自动写入 |
| 「软件中的 MCP 所有的都没有配置按钮」 | BUILTIN 行无 Edit（旧设计认为"无用户可配字段"） | 所有 MCP 行（含 BUILTIN）都有「配置」：BUILTIN 配置对话框（作用域/启停/连接/GitHub Token），自建复用 EditMcpDialog |
| 「MCP 本地运行需要启动；没安装/没启动的不能在斜杠里选」 | 斜杠菜单列 enabled 配置（离线也列出）；未连接的 `/mcp:x` 仍发空转提示词 | 菜单只列 connected；路由器对未运行的 MCP 拦截（空 agentPrompt + 引导语）；市场内完成安装→配置→启动 |
| 「斜杠选技能之后占的页面太大、与对话框重叠」 | 迷你胶囊的 48dp 关闭钮把胶囊撑到 54dp + Code 屏双重 imePadding | 胶囊收敛 28dp 档（视觉 12dp 关闭钮、触区 28dp）；去掉 CodeScreen 根部重复 `.imePadding()` |

## 2. 两个官方仓库

### apex-skill-hub（技能）

```
index.json            # apex-skill-hub-v1 注册表：全部技能的元数据
skills/<id>.json      # apex-skill-v1 完整 manifest（promptInjection 正文）
```

- `index.json` 只放元数据（id/name/version/description/category/tags/scope/
  author/file）——小体积一次拉全，市场按工位分级过滤；
- 技能正文按需单文件下载
  （`raw.githubusercontent.com/AceGuru-mjh/apex-skill-hub/main/<file>`）；
- 全部条目 `scope=agent`（生活/通用技能归 Agent 工位）。

### apex-mcp-hub（MCP 服务器）

单文件设计：每条配置极小（几百字节），完整内联在 `index.json` 的
`servers[]` 里（name/transport/command/args/env/runInSandbox/scope/
requiresRootfs/vendor/tags），一次请求即完整目录。收录 8 台：

| 名称 | 形态 | 作用域 | 说明 |
| --- | --- | --- | --- |
| fs-sandbox | npx 沙箱 | coding | 官方 filesystem 服务器（/workspace） |
| memory-sandbox | npx 沙箱 | agent | 官方知识图谱记忆 |
| everything-sandbox | npx 沙箱 | coding | 官方测试服务器（全能力面） |
| context7 | npx 沙箱 | coding | 库文档实时检索（Upstash） |
| sequential-thinking | npx 沙箱 | all | 官方顺序思考链 |
| fetch | npx 沙箱 | agent | 官方网页抓取 |
| time | npx 沙箱 | all | 官方时间服务 |
| deepwiki | HTTP 远端 | all | DeepWiki 仓库问答（免沙箱） |

## 3. App 侧改动地图

| 层 | 文件 | 改动 |
| --- | --- | --- |
| 源 | `core/tool-registry/.../marketplace/HubSource.kt`（新） | 双目录客户端：`listSkills`/`downloadSkillManifest`/`listMcpServers`；纯解析函数 `parseSkillIndex`/`parseMcpIndex` 可单测 |
| 迁移 | `SkillRegistry.pruneStaleBundled(bundledIds)`（新） | assets 白名单反向清理旧内置残留（四清：内存 + manifest + `.disabled` + 资源目录）；只动 bundled 条目，社区/仓库安装的永不触碰 |
| 接线 | `SkillModule.releaseBundledSkills` | 释放管线先清后装（集合不相交，顺序无耦合） |
| 接线 | `McpModule` | 移除 SANDBOX_PRESET_SERVERS 自动预置（迁仓库） |
| 状态 | `ui/screen/market/MarketHubController.kt`（新） | Hub 双目录状态域（从 MarketViewModel 拆出，God-file 预算）：load/install + `toServerConfig` 纯函数（**enabled 恒 false —— 安装 ≠ 启动**） |
| UI | `MarketHubSections.kt`（新） | HubSkillSection（Skills · 官方仓库源）/ HubMcpCatalogCard（MCP 目录行卡）/ ConfiguredMcpCard（工位服务器行卡）/ BuiltinMcpConfigDialog（内置配置对话框） |
| UI | `MarketBrowseTabs` / `MarketInstalledTabs` | Skills 源切换三档（本地/官方仓库/ClawHub）；MCP 页 = 官方仓库目录 + 工位服务器（配置/启动/停止）+ 逆向 Host + 手动入口；已安装页 BUILTIN 行新增「配置」 |
| VM | `MarketViewModel` | 注入 hub 控制器 + `updateMcpScope`；`SkillRepoSource` 增 HUB 档 |
| 门控 | `ui/component/SlashMenuProvider` | skills = installed+enabled（移除 5 个 legacy 模板并列）；mcp = connected only（空类目保留引导 hint） |
| 门控 | `slash/SlashCommandRouter` | 未运行 MCP → 空 agentPrompt + 引导语（两 VM 均有 isNotBlank 门，不发空转提示词） |
| UI | `code/CodeThinkingSelector`（重写） | 28dp 胶囊 + 300dp 结构化下拉（深度七档段 / AUTO 段 / 指南段；Check 当前档、预算徽标、仅选中档展开说明、本地化短档名） |
| UI | `code/CodeScreen` | 「新会话」文字按钮 → Add 图标按钮（28/18dp，对齐 Agent 屏）；去掉根部重复 `.imePadding()`（双重 IME 抬升根因） |
| UI | `agent/PipelineCapsuleRow`（重写胶囊） | 28dp 紧凑档（视觉 12dp 关闭钮、触区 28dp 圆形；48dp 红线保留给时间轴大胶囊） |

## 4. 门控语义总表

| 入口 | 可见条件 | 拦截行为 |
| --- | --- | --- |
| 斜杠 Skills 分组 | `installed && enabled` | 未装/未启的不出现（引导去市场） |
| 斜杠 MCP 分组 | `connected`（= installed + enabled + running） | 空类目显示「暂无运行中的 MCP」hint |
| `/mcp:<id>`（手输） | — | 未运行 → systemMessage 引导 + 空 prompt 不执行 |
| `/mcp:github`（手输） | Token 或已连接 | 未连接 → GithubTokenDialog 信号（不回归） |
| Hub MCP 安装 | 目录可见 | 落盘 enabled=false；已有同名不覆盖 |
| MCP 启动 | `enabled` | 真实连接管线（进度弹窗 + tools/list 发现）；沙箱未装 rootfs → 引导性报错 |

## 5. 设计决策

1. **为什么不内置全部 75 个技能**：改仓库即生效（无需发版）；APK 体积；
   市场分级后 Agent/Coding 各自的目录不再互相污染。
2. **为什么 MCP 目录单文件内联而技能按需单文件**：MCP 配置几百字节/条，
   索引内联一次拉全；技能正文 1.5-2.3KB/个 × 62 ≈ 130KB，索引只放元数据，
   安装时才拉正文（省流量 + 冷启动快）。
3. **为什么安装 ≠ 启动**（学习 opencode 的 `enabled:false` 预置语义）：
   沙箱 MCP 启动有真实成本（npx 冷启动 + rootfs 门禁）；显式启动让
   「正在运行」状态对用户可预期，斜杠门控才站得住。
4. **为什么保留 5 台 BUILTIN**：能力在二进制里（github REST / 搜索 / 工作区
   文件 / 记忆图谱 / 思考链），属于「必要内置」；其配置对话框暴露
   作用域 / 启停 / 连接 / GitHub Token 四个真实可配项。
5. **prune 与 uninstall 防线的关系**：`uninstall` 对 bundled 恒 false 防的是
   「用户手动卸载后下次启动被 assets 复活」；`pruneStaleBundled` 是同一
   释放管线的另一半——assets 清单说了算，离场者彻底四清。两者语义互补。

## 6. 测试

| 测试 | 覆盖 |
| --- | --- |
| `HubSourceTest`（tool-registry） | 双目录 index 解析（字段/缺省/坏条目/损坏体/工位可见性） |
| `PruneStaleBundledTest`（tool-registry） | 白名单反向清理四清 / 幸存者与社区不动 / 幂等 / 重装后可自由卸载 |
| `MarketHubMcpConfigTest`（app） | 目录条目 → 配置映射（enabled 恒 false / transport 三态 / 字段透传） |
| `SlashCommandRouterTest`（更新） | 未运行 MCP 拦截 + 已连接通用路由不回归 |
