# Changelog

## 0.1.0 — unreleased

第一个可运行版本：契约层 + 停靠窗 + 反向 MCP + 18 个编辑器工具。

### 停靠窗

- 工具窗口（右侧锚点，`Tools → DSH Dock`，`Ctrl+Alt+D` / `Cmd+Alt+D`）用 JCEF
  嵌入 QuickDock 壳页；`JBCefJSQuery` 先于页面创建，桥不会哑。
- 三条消息全部接通：标题栏插头（`quickdock:mcp-toggle`）、点文件回 IDE
  （`quickdock:open-file`，按 NUL 切分）、工具状态回推（`quickdock:mcp-state`）。
- 端口解析与 VS Code 版一致：设置项 → QuickDock `config.json` → `host.log` → 47910；
  `port 0` 单独识别为"监听已关闭"。
- 离线/连接中/旧版 QuickDock/JCEF 不可用四种卡片；降级路径仍可「在浏览器中打开」。
- 主题跟随 IDE（L&F 变化只改 CSS 变量，不重载页面）。

### 反向 MCP

- loopback `HttpServer`：`/health`、`POST /mcp`（bearer）、`GET /mcp` 405、`DELETE /mcp` 204，
  批量请求、通知不回包、工具异常 → `isError: true`。
- 20 秒配对心跳：端口漂移跟随、DSH 重启自动重注册、工具集指纹变化强制重注册、
  关闭的目录反注册、404 明确提示"更新 dsh-quickdock"。
- 每个项目窗口一个 token 与 dock id（`idea-<12 hex>`，持久化，不随窗口重开改变）。

### 编辑器工具（13 只读 + 5 写入）

- 只读：`workspace_folders`、`selection`、`read_file`、`symbols`、`list_files`、`search`、
  `diagnostics`、`definitions`、`references`、`hover`、`code_actions`、`reveal`、`open_diff`。
- 写入（默认关闭）：`apply_edit`、`save_file`、`run_task`、`terminal_run`、`start_debug`。
- 全部有上限并带 `truncated`；dumb mode 返回 `{"reason":"indexing","retry":true}`。

### 审阅

- VFS 监听 + 350ms 防抖；IDE 自己保存的 2 秒内不审阅；跳过构建目录、二进制、>512KB。
- 只在已配对目录里生效；默认**发通知**，点开才进 diff（不抢焦点）。

### 外壳

- 10 个项目级设置项、状态栏条目、11 个动作、中英两套文案。
- 「启动 DSH」在 Run 工具窗口执行 `dsh --profile <name>`，起来后自动重连。

### 已知限制

- 只支持 `view=vscode`：QuickDock 目前没有 `intellij` 视图，Dock 的 `type` 会是 `vscode`。
- 不支持远程开发/Gateway 的内嵌渲染（只做检测与浏览器降级）。
- `intellij_hover` / `intellij_code_actions` 依赖内部 API，已收敛在 `IdeAdapter`，
  平台升级时坏在那里、且工具会退化为"不可用"而不是让整个 server 挂掉。
