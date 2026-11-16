# 01 · 架构与复用边界

## 1. 原插件的真实结构

`dsh-vsdock` 是**薄客户端**：真正的界面由 DSH 的 `dsh-quickdock` 插件在 `127.0.0.1` 上渲染，
VS Code 扩展只做三件事。

```
┌──────────────────────────── DSH 进程 ────────────────────────────┐
│  dsh web（DSH 自己的 Web UI）                                     │
│    └─ dsh-quickdock 插件                                          │
│         ├─ Host: 127.0.0.1:47910  /quickdock?view=…  (壳页)       │
│         │        + app-proxy（把 DSH UI 用插件自己的源站代理出来） │
│         └─ workspace MCP bridge（按目录挂载编辑器工具）            │
└──────────────────────────────────────────────────────────────────┘
        ▲ ① 探测 + 嵌页                          ▲ ③ 反向 MCP
        │  GET /quickdock/api/meta               │  POST /quickdock/api/mcp-register
        │  http://127.0.0.1:47910/quickdock?…    │  GET  /quickdock/api/mcp-list
┌───────┴──────────────────────────────────────┴───────────────────┐
│ 编辑器客户端（VS Code 扩展 / 本插件）                              │
│   ├─ Webview（iframe 壳）  ←→  三条 postMessage  ←→  QuickDock 壳页│
│   ├─ 本机 MCP server（127.0.0.1:<mcpPort>/mcp，bearer token）      │
│   └─ 编辑器工具实现（读缓冲区、诊断、符号、diff…）                  │
└──────────────────────────────────────────────────────────────────┘
```

关键：**QuickDock 负责"能不能嵌、壳长什么样、工具挂给哪个会话"**；客户端负责"把壳放哪儿、
点击怎么回到编辑器、工具怎么读编辑器"。所以任何一个 IDE 客户端都能接同一份契约——文档里原话是
"every future IDE client uses the same one URL"。

## 2. 三层，只有一层要重写

| 层 | 内容 | 移植策略 |
|---|---|---|
| **契约层** | 端口解析、`meta` 探测、壳页 URL 与参数、三条 `postMessage`、`mcp-register/list/unregister`、20s 配对心跳 | **零改动**：按 [02-contracts.md](02-contracts.md) 重写实现，语义不变 |
| **宿主适配层** | Webview → JCEF；`vscode.*` → IntelliJ 平台 API；17 个工具 | **全部重写**（唯一真正的工作量） |
| **外壳层** | 侧边栏视图、状态栏、命令、快捷键、设置、i18n | 等价重写，样板多但风险低 |

可以直接搬的两块：

| 源 | 搬到 | 说明 |
|---|---|---|
| `dsh-vsdock/src/html.ts` 的 `renderFrame` / `renderOffline` / `renderConnecting` | Kotlin 里的 HTML 构造 | 纯 HTML/CSS/JS，唯一要换的是 `acquireVsCodeApi()` → JCEF JS 桥；`command:…` 链接 → JS 桥调用 |
| `dsh-vsdock/test/*.test.mjs` 的"纯逻辑"思路 | JUnit | 端口解析、探测、壳页渲染、JSON-RPC、审阅决策都不依赖 IDE |

必须重写的（`vscode.*` 全部消失）：`extension.ts`（命令/状态栏/视图生命周期/配对循环）、
`tools.ts`（17 个工具）、`diff.ts`（diff 编辑器）、`review-watcher.ts`（文件监听）、
`dsh.ts`（如果选决策 C：启动 DSH 的命令行与 profile 发现，逻辑可照抄）。

## 3. 目标模块结构

```
src/main/kotlin/cc/futurvo/ideadock/
├── contract/                    // ← 与 dsh-vsdock/src/quickdock.ts + mcp.ts 一一对应，纯 Kotlin，无 IDE 依赖
│   ├── QuickDockPorts.kt        // stateDir/config.json/host.log 解析、端口优先级
│   ├── QuickDockProbe.kt        // GET meta、ProbeResult、失败分类
│   ├── QuickDockUrls.kt         // pageUrl/metaUrl/dockApi（view、dock、mcp、workspace 参数拼装）
│   ├── McpProtocol.kt           // JSON-RPC 方法表、错误码、工具指纹
│   ├── McpServer.kt             // com.sun.net.httpserver 实现（loopback + bearer）
│   ├── Pairing.kt               // 注册/查询/反注册 + 需要重注册的判定
│   ├── Json.kt                  // 自研 JSON codec（零依赖，见 07 §4）
│   ├── Paths.kt                 // 大小写/分隔符归一
│   └── Glob.kt                  // list_files / search 的 glob 子集
├── dock/                        // ← 对应 html.ts + extension.ts 的视图部分
│   ├── DockToolWindowFactory.kt
│   ├── DockPanel.kt             // JCEF 宿主 + 生命周期
│   ├── DockView.kt              // 一帧要渲染的状态
│   ├── DockPage.kt              // 壳页 HTML 构造（搬 html.ts）
│   └── DockTheme.kt             // IDE 主题色 → 壳页 CSS 变量
├── editor/                      // ← 对应 tools.ts + diff.ts + review-watcher.ts
│   ├── IdeTools.kt              // 18 个工具的定义与实现
│   ├── IdeAdapter.kt            // 所有"内部/不稳定 API"的收敛点（诊断、意图动作、悬停、调试）
│   ├── ReviewDiff.kt            // DiffManager + 基线文本
│   ├── ReviewDecision.kt        // 纯逻辑的审阅规则（对应 review.ts）
│   └── EditReviewWatcher.kt     // VFS 监听 + debounce + 跳过自己保存
├── ui/
│   ├── DshDockSettings.kt       // PersistentStateComponent
│   ├── DshDockConfigurable.kt   // 设置页
│   ├── DockStatusBarWidget.kt
│   ├── DockActions.kt           // 11 个动作
│   └── DshDockBundle.kt         // DynamicBundle + i18n
└── lifecycle/
    ├── DockService.kt           // per-project：dockId、端口解析、探测缓存、配对状态机
    └── DshLauncher.kt           // 决策 C：启动 dsh（照抄 dsh.ts，纯逻辑）
```

> 包名在实现阶段从 `com.deepseek.dsh.idea` 改成了 **`cc.futurvo.ideadock`**（仓库所有者的域名
> 反写）；插件 id 与工具命名空间都不受影响，§4 的命名约定仍然有效。

`contract/` 和 `dock/DockPage.kt` 应该**能在没有 IDE 的单测里跑**——这是从原项目学到的最大
工程收益：`npm test` 里 53 个测试全部不需要打开 VS Code。

## 4. 命名与标识约定

| 项 | 取值 | 理由 |
|---|---|---|
| 插件 id | `dsh.ideadock` | 与 VS Code 的 `dsh.dsh-vsdock` 对应 |
| 工具窗口 id | `DSH Dock` | 用户可以改名/移动 |
| 设置前缀 | `dshIdeadock.` | 与 VS Code 的 `dshVsdock.` 对齐但避免混淆 |
| MCP `name` | `intellij` | DSH 侧最终是 `mcp__intellij__intellij_read_file` |
| dock id | `idea-<12 hex>`，per project | 不要从路径派生（两个窗口同一项目会撞） |
| 状态文件 | `dsh-dock.xml`（项目级） | `@State(storages = [Storage("dsh-dock.xml")])` |

## 5. 为什么不做"弹出式 webview 窗口"

VS Code 版把停靠窗放在**次要侧边栏**（Copilot Chat 那一列），是"不抢编辑器区"的设计。
IntelliJ 没有次要侧边栏概念，等价物是 **anchor="right" 的工具窗口**：

- 工具窗口可以被拖到左右任一侧、可以和编辑器并排、可以浮动（即"弹出窗"）；
- 用 `DialogWrapper` 自建窗口会丢掉停靠、拖动、快捷键、焦点管理等一整套平台行为，不要做。

## 6. 与原项目的对应关系（便于逐文件对照移植）

| 原文件 | 行数 | 目标 | 移植难度 |
|---|---|---|---|
| `src/quickdock.ts` | 342 | `contract/QuickDockPorts.kt` + `QuickDockProbe.kt` + `QuickDockUrls.kt` | 低（纯逻辑） |
| `src/mcp.ts` | 501 | `contract/McpProtocol.kt` + `McpServer.kt` + `Pairing.kt` | 中（HTTP 换成 JDK HttpServer） |
| `src/html.ts` | 347 | `dock/DockPage.kt` | 低（直接搬字符串，换桥） |
| `src/extension.ts` | 1233 | `DockService.kt` + `DockPanel.kt` + `ui/*` | 高（生命周期/线程模型不同） |
| `src/tools.ts` | 813 | `editor/EditorTools.kt` + `IdeAdapter.kt` | 高（API 全换） |
| `src/diff.ts` | 151 | `editor/ReviewDiff.kt` | 中（DiffManager） |
| `src/review.ts` + `review-watcher.ts` | 316 | `editor/EditReviewWatcher.kt` | 中（VFS 监听） |
| `src/dsh.ts` | 112 | `lifecycle/DshLauncher.kt` | 低（照抄命令行） |
| `src/paths.ts` | 49 | `contract/Paths.kt` | 低（大小写/分隔符归一） |
