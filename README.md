# DSH Dock for JetBrains IDEs

把 DeepSeek Harness（DSH）的对话界面放进 IntelliJ IDEA / PyCharm / WebStorm / GoLand 的
**工具窗口**，同时给 agent 一组**编辑器工具**（读文件、选中文本、诊断、符号、定义/引用、审阅 diff）。

- 界面是 **dsh-quickdock**（DSH 插件）自己渲染的壳页，本插件用 JCEF 把它嵌进 iframe；
- 点会话里工具卡片的文件路径 → 在 IDE 编辑器里打开；
- 反向 MCP：IDE 起一个 loopback server，注册给 QuickDock，DSH 会话按目录挂载 `mcp__intellij__*` 工具。

全程只走 `127.0.0.1`，不联网。

| 项 | 值 |
|---|---|
| 插件 id | `dsh.ideadock` |
| 包名 | `cc.futurvo.ideadock` |
| MCP 命名空间 | `intellij`（DSH 侧 `mcp__intellij__<tool>`） |
| 最低 IDE | 2025.3（`since-build=253`），`until-build` 不设上界 |
| 工具数 | 13 个只读 + 5 个写入（写入默认关闭） |

## 前置条件

1. 已安装 DSH，并启用 **dsh-quickdock** 插件（需要它声明 `app-proxy` 能力，即较新的版本）；
2. IDE 使用带 JCEF 的 JetBrains Runtime（官方安装包默认满足）。若是自定义 JDK、或 2026.2+
   未启用内置插件 *Web Browser (JCEF)*、或远程开发/Gateway，工具窗会显示降级卡片，
   此时用「在浏览器中打开」照常可用。

## 五步跑通

1. 安装插件（Settings → Plugins → ⚙ → Install Plugin from Disk，选 `build/distributions/*.zip`）；
2. 启动 DSH（或让插件帮你启动：工具窗卡片的「启动 DSH」）；
3. `Tools → DSH Dock`（`Ctrl+Alt+D` / `Cmd+Alt+D`）打开工具窗口；
4. 工具窗口里应出现 DSH 会话。标题栏的**插头**按钮控制「编辑器工具」开关——点一下，
   状态变成 `N 个工具，M 个目录`，DSH 侧下一个 20 秒心跳内挂载 `mcp__intellij__*`；
5. 让 agent 试试 `intellij_workspace_folders` / `intellij_read_file`。

没连上时卡片会给出：端口、失败原因、QuickDock 自己配置里的端口（可一键采用）、
「启动 DSH」「重试」「在浏览器中打开」「打开 QuickDock 日志」。

## 设置

Settings → Tools → DSH Dock。所有设置都是**项目级**的（一个 IDE 多窗口各连各的）。

| 设置 | 默认 | 说明 |
|---|---|---|
| QuickDock 端口 | `0` | `0` = 跟随 QuickDock 的 `config.json` / `host.log`；填 1–65535 则固定 |
| 跟随 QuickDock 配置 | 开 | 关闭后只用上面的端口或默认 47910 |
| 状态栏条目 | 开 | 关闭后状态栏不再显示 |
| 为 DSH 启用编辑器工具 | 关 | 等价于工具窗标题栏的插头 |
| 编辑器工具端口 | `47920` | 被占用时自动退到空闲端口，配对会带真实 URL |
| 允许修改 IDE 的工具 | **关** | 打开后 agent 可编辑/保存/运行/调试 |
| DSH 改动文件时通知 | 开 | 变更只在**已配对目录**里通知，且不抢焦点 |
| dsh 命令 / profile / 额外参数 | `dsh` / 空 / 空 | 「启动 DSH」用 |

## 隐私与权限边界（请先读完再用）

- MCP server **只绑 127.0.0.1**，没有远程模式；
- 每个项目窗口一个 **bearer token**（32 字节随机 base64url），只出现在注册请求里，不写日志；
- **只读工具**能读到：项目路径、当前选中文本、任意文件内容（**包括未保存的缓冲区**）、
  诊断、符号、定义/引用、悬停文档、代码动作列表；
- **写入工具默认关闭**。打开后 agent 可以：改缓冲区（可撤销，并自动开 diff）、保存文件、
  运行 Run Configuration、在 Run 工具窗口跑命令行、启动调试；
- 工具权限 = IDE 能做的所有事。对不可信仓库，请保持写入关闭。

## 与 VS Code 版共存

`dsh-vsdock`（VS Code 扩展）用命名空间 `vscode`，本插件用 `intellij`，同一目录同时装两个
客户端不会互相顶掉注册。

## 从源码构建

```bash
./gradlew buildPlugin      # build/distributions/dsh-ideadock-<ver>.zip
./gradlew test             # 纯逻辑 + 协议端到端测试，不需要 IDE
./gradlew runIde           # 沙箱 IDE（首次会下载 ~1.5GB）
./gradlew verifyPlugin     # Plugin Verifier
```

构建细节、本机离线/代理环境的注意事项见 [DEVELOPING.md](DEVELOPING.md)。

## 文档

规格与踩坑手册在 [`docs/`](docs/)：

| 文档 | 内容 |
|---|---|
| [00-migration-spec.md](docs/00-migration-spec.md) | 立项文档原文 + 三个决策的最终选择 |
| [01-architecture.md](docs/01-architecture.md) | 三层拆分与模块结构 |
| [02-contracts.md](docs/02-contracts.md) | QuickDock 契约（端口、meta、壳页、三条消息、配对 API） |
| [03-jcef-dock.md](docs/03-jcef-dock.md) | JCEF、iframe 壳、JS 桥、降级 |
| [04-mcp-host.md](docs/04-mcp-host.md) | 反向 MCP server 与配对心跳 |
| [05-tools.md](docs/05-tools.md) | 18 个工具的实现与降级路径 |
| [06-platform-rules.md](docs/06-platform-rules.md) | 线程/EDT/ReadAction/UI 外壳 |
| [07-build-test-release.md](docs/07-build-test-release.md) | 构建、测试、发布 |
| [08-risks-milestones.md](docs/08-risks-milestones.md) | 风险与里程碑 |

实现过程中核对出的事实记在各篇末尾的「实测更正」小节。构建环境与测试分层见
[DEVELOPING.md](DEVELOPING.md)，变更记录见 [CHANGELOG.md](CHANGELOG.md)。

## 许可

MIT，见 [LICENSE](LICENSE)。
