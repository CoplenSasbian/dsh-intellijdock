# 00 · 迁移规格书（立项文档原文 + 已定决策）

> 这份文件是开工前那份 `README.md` 的原样存档：当时仓库里只有文档，没有代码。
> 现在代码已经落地，末尾补上了三个决策的**最终选择**和当前进度。
> 插件用户向的说明见根目录 [README.md](../README.md)。

## 目标

把 `C:\Users\futur\Desktop\dsh-vsdock`（VS Code 扩展 v0.7.0）的**停靠窗**移植成
JetBrains 平台插件（IntelliJ IDEA / PyCharm / WebStorm / GoLand …）：

- 在 IDE 的**工具窗口**里显示正在运行的 DSH 会话（工具卡片、审批、模型/权限控制）；
- 点工具卡片里的文件路径 → 在 **IDE 里**打开；
- 反向给 agent 提供一组**编辑器 MCP 工具**（诊断、选中文本、符号、定义/引用、审阅 diff …）。

## 相关仓库（本机路径）

| 路径 | 作用 | 备注 |
|---|---|---|
| `C:\Users\futur\Desktop\dsh-vsdock` | **移植源**：VS Code 扩展（TypeScript，`src/` 约 3.8k 行） | 已打包 `dsh-vsdock-0.7.0.vsix` |
| `C:\Users\futur\Desktop\dsh-quickdock` | **契约真源**：DSH 的 QuickDock 插件（Host + Client） | `API.md` / `MCP-BRIDGE.md` / `DOCK-CONTEXT.md` |
| `C:\Users\futur\Desktop\dsh-intellijdock` | 本仓库 | `docs/` + `src/` |

## 阅读顺序

| 顺序 | 文档 | 读它是为了 |
|---|---|---|
| 1 | [01-architecture.md](01-architecture.md) | 三层拆分：什么零改动、什么照抄、什么必须重写 |
| 2 | [02-contracts.md](02-contracts.md) | QuickDock 契约的**准确**形状（URL、参数、三条消息、三个 API、描述符） |
| 3 | [03-jcef-dock.md](03-jcef-dock.md) | 停靠窗：JCEF、iframe 壳、JS 桥、主题、降级 |
| 4 | [04-mcp-host.md](04-mcp-host.md) | 反向 MCP server + 配对心跳 |
| 5 | [05-tools.md](05-tools.md) | 18 个工具的 `vscode.*` → IntelliJ API 逐条映射 |
| 6 | [06-platform-rules.md](06-platform-rules.md) | 线程/EDT/ReadAction/模态；工具窗口、设置、状态栏、i18n |
| 7 | [07-build-test-release.md](07-build-test-release.md) | Gradle、版本策略、测试、Verifier、签名、发布 |
| 8 | [08-risks-milestones.md](08-risks-milestones.md) | 风险排序、M0–M6 验收清单、工作量 |

## 三个决策（已定）

| 决策 | 选项 | 结论 |
|---|---|---|
| 依赖形态 | A 薄客户端 / **C 薄客户端 + 启动 DSH** / B 自带运行时 | **C**：只连 `127.0.0.1`；离线卡片带「启动 DSH」，在 Run 工具窗口拉起 `dsh --profile <name>`，起来后自动重连 |
| 最低 IDE | 241 / **253** / 两套制品 | **`since-build=253`**，`until-build` 不设；`com.intellij.modules.jcef` 用 optional 依赖（原因见 [07 §3](07-build-test-release.md)） |
| 命名空间 | `vscode` 复用 / **`intellij`** | 插件 id `dsh.ideadock`，MCP 命名空间 `intellij`（`mcp__intellij__*`），包名 `cc.futurvo.ideadock`，dock id `idea-<12 hex>` per project |

## 本次核对过的事实（2026-11-17，开工前）

写文档时逐条查过源码或官方文档，标 ✅ 的可以直接信；标 ⚠️ 的要在实现时实测。

| # | 事实 | 来源 | 状态 |
|---|---|---|---|
| 1 | `GET /quickdock/api/meta` 返回 `name/apiVersion/version/capabilities/views/mcpBridge`，`capabilities` 含 `app-proxy` 才能嵌 | `host/quickdock-host.js:1354`、`dsh-vsdock/src/quickdock.ts` | ✅ |
| 2 | `/quickdock?view=<view>` 的 `view` 是**白名单**，未知值返回 **400** | `host/quickdock-host.js:1035-1042`、`runtime/test/host.test.js` | ✅ |
| 3 | `view=vscode` 同时决定三件事：trims chrome、走 Host app-proxy、app 侧 `DOCK_HOST='vscode'` | `host/page.js:79`、`host/quickdock-host.js:1056`、`client.js:1453` | ✅ |
| 4 | 插头按钮只在 `dockMcp && !electron && window.parent !== window` 时出现 | `host/page.js:60-66`、`host/quickdock-host.js:1070` | ✅ |
| 5 | 收到 `quickdock:mcp-state` 前插头一直 `data-pending` → **宿主必须回发状态** | `host/page.js:270-277` | ✅ |
| 6 | `quickdock:open-file` 的 `path` 是 `"<绝对路径>\u0000<source>"` | `client.js:1470`、`dsh-vsdock/src/extension.ts:742` | ✅ |
| 7 | 未保存缓冲区只有编辑器自己知道 → 工具必须走 `FileDocumentManager.getDocument(vf)?.text` | `src/tools.ts` 语义 | ✅ |
| 8 | 2026.2 起 JCEF 需 `<depends>com.intellij.modules.jcef</depends>`，别名自 2025.3.1 起可用 | [官方文档](https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html) | ✅（实现时按 optional 处理） |
| 9 | 插件作者签名**非强制**，未签名只是安装时提示 | [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) | ✅ |
| 10 | `JBCefJSQuery` 必须在页面 load **之前**创建 | `deepseek-harness-idea` 实测笔记 | ✅ 已核对字节码：`inject` 产出 `window.<fn>({request: '' + <expr>, ...})` |
| 11 | 自定义 JBR 或 2026.2 未启用 "Web Browser (JCEF)" 时 `JBCefApp.isSupported()==false` | 官方文档 + 上述笔记 | ✅ 代码里有 Swing 降级卡片 |

## 文档维护约定

- 本目录的文档是**活文档**：实现过程中发现新事实，直接改对应文件，并在该文件末尾的
  「实测更正」小节记下 `现象 → 真因 → 修法`（照 `deepseek-harness-idea/docs/PROJECT_NOTES.md` 的风格）。
- 标 ⚠️ 的结论在实测后改成 ✅ 并注明版本（例如 `2025.3 / JBR 21`）。
- 与 QuickDock 契约有关的内容只写**事实**，不做推测；改契约时同步 `dsh-quickdock` 三份 md。
