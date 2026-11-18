# DSH Dock for JetBrains IDEs

**把 DeepSeek Harness 的对话放进 JetBrains 工具窗口，并让 agent 用上你的编辑器。**

DSH Dock 把 DSH 自己的对话界面放在正在处理的代码旁边，同时打开一条回到 IDE 的通道：点对话
里的文件路径会在编辑器打开，DSH 改动文件时会给出审阅 diff，打开标题栏的**插头**后 agent 还能
使用一组在 IDE 里运行的只读（可选写入）工具。它是 **dsh-quickdock**（运行在 DSH 里的插件）的
客户端。

## 功能

- **真正的对话，就在工具窗口里。** 右侧锚点，`Ctrl+Alt+D` / `Cmd+Alt+D`——工具卡片、审批、
  模型与权限控制都是 DSH 自己的界面。
- **输入法可用。** 应用作为浏览器的**顶层文档**加载而不是 iframe，中文输入法可以正常上屏；
  标题栏由插件用 Swing 绘制。
- **文件在 IDE 里打开。** 工具卡片里的文件路径在编辑器打开，不进 dock 自己的预览；DSH 新建
  或修改文件时自动打开审阅 diff——diff 标签出现在旁边，焦点回到你原来的编辑器。
- **给 agent 的编辑器工具。** 13 个只读工具在 IDE 里运行：工作区目录、选中内容、文件内容
  （含未保存缓冲区）、诊断、符号、搜索、按模式列文件、定义/引用、悬停文档、代码动作、定位、
  审阅 diff。另有 9 个写入工具（编辑、保存、运行配置、终端、启动调试，以及 job 状态/输出/停止/列表），
  默认关闭。运行、终端和调试都是**启动后立刻返回 job id**，agent 用 `job_status`（可带 `waitMs`）
  或 `job_output` 查状态和输出，用 `job_stop` 停止。
- **按 IDE 取命名空间。** 工具名带上宿主产品：IDEA 是 `mcp__idea__*`、CLion 是
  `mcp__clion__*`、PyCharm 是 `mcp__pycharm__*`……多个 IDE 的 dock 在 DSH 里不会混淆。
- **按目录生效。** 只有工作目录落在本窗口打开的文件夹里的会话才会挂载工具；否则标题栏出现
  琥珀色警告三角，「列表」按钮显示发布了什么、挂载了什么。
- **不会只剩空白。** 连不上 DSH 时工具窗会说明端口和原因，并提供启动 DSH、重试、连接设置、
  在浏览器打开、查看 QuickDock 日志；远程开发 / Gateway 会自动降级到浏览器。

## 几个 dock 怎么配合

DSH Dock for JetBrains IDEs 是同一个页面的三个编辑器 dock 之一。**它要求 DSH 安装
[dsh-quickdock](https://github.com/CoplenSasbian/dsh-quickdock) 插件**——QuickDock 负责提供
对话页面，以及本插件注册编辑器工具的 MCP 桥。

| 项目 | 运行在 | 是什么 |
|---|---|---|
| [dsh-quickdock](https://github.com/CoplenSasbian/dsh-quickdock) | DSH | 宿主：把对话作为回环页面提供出来，并提供编辑器 dock 注册工具的 MCP 桥。**每个编辑器 dock 都依赖它。** |
| [DSH Dock for VS Code](https://github.com/CoplenSasbian/dsh-vsdock) | VS Code | 同一页面放进辅助侧边栏，外加 VS Code 编辑器工具。 |
| [DSH Dock for JetBrains IDEs](https://github.com/CoplenSasbian/dsh-intellijdock) | IntelliJ IDEA、CLion、PyCharm、WebStorm、GoLand 等 | 同一页面放进工具窗口，外加 JetBrains 编辑器工具。 |
| [DSH Dock for Visual Studio](https://github.com/CoplenSasbian/dsh-visualstudio) | Visual Studio 2026 | 同一页面放进工具窗口，外加 Visual Studio 编辑器工具（目前只读）。 |

这些编辑器 dock 可以同时连同一个目录：工具命名空间（`vscode_*`、`mcp__<ide>__*`）不会冲突。

## 环境要求

- DSH（桌面版或 `dsh web`）安装 **dsh-quickdock 0.6.0+**——需要 `app-url` 路由与
  `view=intellij`；更早的版本会退回 iframe 壳页。
- JetBrains IDE 2025.3 或更新（IntelliJ IDEA、CLion、PyCharm、WebStorm、GoLand 等），使用
  自带的 JCEF 运行时。远程开发 / Gateway 会被检测到并降级到浏览器。
- QuickDock 监听地址可达：`http://127.0.0.1:47910`，或在 DSH → **设置 → QuickDock → 连接**
  里配置。

## 安装

**设置 → 插件 → ⚙ → Install Plugin from Disk…**，选择
[Releases](https://github.com/CoplenSasbian/dsh-intellijdock/releases) 里的插件 zip（或
checkout 里的 `build/distributions`），重启 IDE，然后打开 **Tools → DSH Dock**
（`Ctrl+Alt+D` / `Cmd+Alt+D`）。

## 许可

MIT，见 [LICENSE](LICENSE)。
