# DSH Dock for JetBrains IDEs

把 DeepSeek Harness（DSH）的对话界面放进 IntelliJ IDEA / PyCharm / WebStorm / GoLand 的
**工具窗口**，同时给 agent 一组**编辑器工具**（读文件、选中文本、诊断、符号、定义/引用、审阅 diff）。

- 界面是 **dsh-quickdock**（DSH 插件）自己渲染的壳页（`view=intellij`），本插件用 JCEF 把它嵌进 iframe；
- 点会话里工具卡片的文件路径 → 在 IDE 编辑器里打开；
- 反向 MCP：IDE 起一个 loopback server，注册给 QuickDock，DSH 会话按目录挂载 `mcp__<ide>__*` 工具
  （IDE 名就是命名空间：IDEA 是 `idea_*`、CLion 是 `clion_*`、PyCharm 是 `pycharm_*`……）。

全程只走 `127.0.0.1`，不联网。

| 项 | 值 |
|---|---|
| 插件 id | `dsh.ideadock` |
| 包名 | `cc.futurvo.ideadock` |
| MCP 命名空间 | 按 IDE 取名：`idea` / `clion` / `pycharm` / `goland` / `webstorm` / `phpstorm` / `rubymine` / `rider` / `datagrip` / `rustrover` / `aqua` / `appcode`（DSH 侧 `mcp__<ide>__<tool>`） |
| 最低 IDE | 2025.3（`since-build=253`），`until-build` 不设上界 |
| 工具数 | 13 个只读 + 5 个写入（写入默认关闭） |

## 前置条件

1. 已安装 DSH，并启用 **dsh-quickdock** 插件（需要它声明 `app-proxy` 能力，即较新的版本；
   再新一点的版本才有 `view=intellij`，更早的会自动退回 `view=vscode`，界面与点文件都一样，
   只是 DSH 的 dock 面板里 `type` 显示成 `vscode`）；
2. IDE 使用带 JCEF 的 JetBrains Runtime（官方安装包默认满足）。若是自定义 JDK、或 2026.2+
   未启用内置插件 *Web Browser (JCEF)*、或远程开发/Gateway，工具窗会显示降级卡片，
   此时用「在浏览器中打开」照常可用。

## 五步跑通

1. 安装插件（Settings → Plugins → ⚙ → Install Plugin from Disk，选 `build/distributions/*.zip`）；
2. 启动 DSH（或让插件帮你启动：工具窗卡片的「启动 DSH」）；
3. `Tools → DSH Dock`（`Ctrl+Alt+D` / `Cmd+Alt+D`）打开工具窗口；
4. 工具窗口里应出现 DSH 会话。标题栏的**插头**按钮控制「编辑器工具」开关——点一下，
   状态变成 `N 个工具，M 个目录`，DSH 侧下一个 20 秒心跳内挂载 `mcp__<ide>__*`
   （例如在 CLion 里是 `mcp__clion__*`）；
5. 让 agent 试试 `clion_workspace_folders` / `clion_read_file`（IDEA 里则是 `idea_*`）。

没连上时卡片会给出：端口、失败原因、QuickDock 自己配置里的端口（可一键采用）、
「启动 DSH」「重试」「在浏览器中打开」「打开 QuickDock 日志」。

## 设置

Settings → Tools → DSH Dock。所有设置都是**项目级**的（一个 IDE 多窗口各连各的）。

| 设置 | 默认 | 说明 |
|---|---|---|
| QuickDock 端口 | `0` | `0` = 跟随 QuickDock 的 `config.json` / `host.log`；填 1–65535 则固定 |
| 跟随 QuickDock 配置 | 开 | 关闭后只用上面的端口或默认 47910 |
| 状态栏条目 | 开 | 关闭后状态栏不再显示 |
| 使用窗口化渲染 | 开 | 关掉 JCEF 的离屏渲染（OSR）——某些平台上 OSR 会让输入法连主框架都不上屏；页面显示异常时才需要关掉它 |
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

## 已知限制

- **中文输入**：JCEF 无法把输入法提交送进子 frame（光标在闪、候选框出现、英文能打、选字后不进），
  所以停靠窗把 DSH 应用作为**顶层文档**加载（`GET /quickdock/api/app-url`），不再套壳页 iframe；
  代价是标题栏那几个按钮由插件自己用 Swing 画（`DockToolbar`），外观与壳页略有不同。
  旧版 QuickDock 没有该路由时退回壳页 iframe，此时中文输入法仍不可用（平台问题，非本插件）。
- 不支持远程开发/Gateway 的内嵌渲染（只做检测与浏览器降级）。

## 与 VS Code 版共存

`dsh-vsdock`（VS Code 扩展）用命名空间 `vscode`、壳页参数 `view=vscode`；本插件用命名空间
`intellij`、壳页参数 `view=intellij`。同一目录同时装两个客户端不会互相顶掉注册，DSH 的 dock
面板里也能一眼分清哪个面板属于哪个编辑器。

## 从源码构建

```bash
./gradlew buildPlugin      # build/distributions/dsh-ideadock-<ver>.zip
./gradlew test             # 纯逻辑 + 协议端到端测试，不需要 IDE
./gradlew runIde           # 沙箱 IDE（首次会下载 ~1.5GB）
./gradlew verifyPlugin     # Plugin Verifier
```

变更记录见 [CHANGELOG.md](CHANGELOG.md)。

## 许可

MIT，见 [LICENSE](LICENSE)。
