# Changelog

## 0.1.0 — unreleased

第一个可运行版本：契约层 + 停靠窗 + 反向 MCP + 18 个编辑器工具。

### 停靠窗

- 工具窗口（右侧锚点，`Tools → DSH Dock`，`Ctrl+Alt+D` / `Cmd+Alt+D`）用 JCEF
  嵌入 QuickDock 壳页；`JBCefJSQuery` 先于页面创建，桥不会哑。
- 壳页用 `view=intellij`（QuickDock 0.6 起支持，DSH 里 dock 的 `type` 就是 `intellij`）；
  旧版 QuickDock 在探测时发现 `meta.views` 没有它，自动退回 `view=vscode` —— chrome、
  app-proxy、点文件回 IDE 完全相同，只是 `type` 显示为 `vscode`。
- 三条消息全部接通：标题栏插头（`quickdock:mcp-toggle`）、点文件回 IDE
  （`quickdock:open-file`，按 NUL 切分）、工具状态回推（`quickdock:mcp-state`）。
- 端口解析与 VS Code 版一致：设置项 → QuickDock `config.json` → `host.log` → 47910；
  `port 0` 单独识别为"监听已关闭"。
- 离线/连接中/旧版 QuickDock/JCEF 不可用四种卡片；降级路径仍可「在浏览器中打开」。
- **离线卡片的按钮真的能点了**：`onclick` 里 `jsString` 生成的 `"startDsh"` 直接落进
  双引号属性，HTML 在第一个引号处就收尾，浏览器拿到的是非法 handler `var action=`，
  于是整排按钮（开始/重试/端口/浏览器/日志）看起来正常、点了没反应。现在整段 handler
  先按属性转义（`&quot;`）再写入，并加了按浏览器规则解析 `onclick` 的回归测试。
- **顶层模式的 Reload 现在真的重载页面**：以前它只重新探测、重新 `render`，而顶层模式
  为了保住会话状态会刻意不重载文档，所以 QuickDock 客户端更新后、或页面被 DSH
  重连带到不带 `?dock=1` 的地址后，点 Reload 没有任何效果。现在标题栏的 Reload 会
  取一次新文档；普通打开/切换工具窗仍然不重载（会话不丢）。
- **DSH 重启后不再停在 `ERR_CONNECTION_REFUSED`**：以前 Reload 在 `render` 刚开始
  加载新的 app-proxy 地址时又 `reloadIgnoreCache()` 了一次，第二次 reload 回到旧端口，
  错误页就留在那里。现在只有 `render` 没有发起新导航时才 reload 文档，而且重载永远走
  插件记录的 app URL，不是“浏览器当前地址”。主框架连接错误会触发一次去抖的重探测，
  跟随循环发现 Host 从不可达恢复后也会重建视图——和 VS Code dock 的自愈行为一致。
- **工作区警告恢复显示**：警告由 `dock-target.workspace.matches` 决定，而它挂在 Host 的
  dock 绑定上；绑定是内存态，DSH 一重启就没了，而且在 dock 自己的侧边栏里切会话也不会
  重新绑定。顶层模式没有壳页来做这件事，所以插件收到 `quickdock:session` 时静默
  `POST /bind`；跟随循环发现 Host 把绑定弄丢时也会按 app 最后报告的会话补一次。
- **会话里点相对路径的文件链接能打开了**：DSH 的 file chip 把
  `title="test/event_test.cpp"` 这种相对路径放在属性里，而客户端只从叶子的相对文本
  取路径（还把中间的 `/` 误判成 Unix 根，发出去的是 `/event_test.cpp`），于是编辑器
  dock 里点了没反应。现在属性里的相对路径也会转发，编辑器按内容根解析；插件侧同时改成
  `refreshAndFindFileByPath` + 分隔符归一，agent 刚写出来的文件也能立刻打开。
- **中文输入可用**：JCEF 无论离屏还是窗口化，都无法把输入法提交送进 **子 frame**（光标闪、候选框出、
  英文能打、中文不进）。所以停靠窗不再套壳页 iframe，而是用 `GET /quickdock/api/app-url` 把
  DSH 应用作为**顶层文档**加载，composer 落在主框架；侧边栏/新会话/插头/交接/标题/工作区警告
  由 Swing 的 `DockToolbar` 提供，会话跟随由插件自己按 1.2 秒轮询 `dock-target`（壳页原来做的事）。
  旧版 QuickDock 没有 `app-url` 时自动回退到壳页 iframe，功能不变。
- 顶层模式下的标题栏按壳页（VS Code 版）的顺序**和图标**排：左侧 `侧边栏` + `新建会话` + 会话标题，
  右侧 `工作区警告` + `在 DSH 里显示`。图标不是平台 `AllIcons`，而是照壳页那套 16×16 线稿用
  Java2D 画出来的（`dock/DockIcons.kt`），所以两个编辑器里的停靠窗长得一样，且跟随主题色与 HiDPI。
  侧边栏展开时 `新建会话` 自动隐藏（侧边栏里本来就有这个按钮，壳页的 CSS 也是这么藏的）；
  停靠模式下应用自带的侧边栏按钮被 dock 样式藏掉了，所以这个开关必须由插件提供。
- **编辑器工具面板**：工具栏新增「列表」按钮（壳页同款三行带点图标），弹出本停靠窗发布给 DSH 的
  MCP 服务器——名字、版本、url、工具名，以及「已挂载到 N 个会话 / 已发布未挂载」，
  数据来自 `GET /quickdock/api/mcp-servers?workspace=`（与壳页面板同一个端点、同一批字段）。
  面板宽度固定（约 420 DIP），高度跟内容走、上限取屏幕可用高度的 90%（不是硬编码的数值），
  所以一个 server 的完整工具列表一眼能看全，只有 server 很多时才滚动：弹层尺寸跟随内容首选尺寸，
  而工具名那一行能长到屏幕宽、`BoxLayout` 又会把余下的高度摊给每个一行高的 label，所以 url /
  工具列表 / 目录用可换行的只读文本块（`JTextArea`），行布局改用 `GridBagLayout` + `weighty = 0`。
- 工具窗标题栏的三个 action 全部补上真正图标：编辑器工具（关=拼图、开=绿勾，tooltip 带工具数）、
  重载、在浏览器打开。以前它们没有 `Icon`，平台把三个都画成同一个圆点占位符，看起来像坏了；
  其余动作仍在 `Tools → DSH Dock` 菜单里。
- **工具命名空间按 IDE 取名**：同一套插件跑在 IDEA/CLion/PyCharm/GoLand/WebStorm/Rider 等上，
  以前所有 IDE 都叫 `intellij`，多个 dock 在工具列表里分不清。现在按产品取：
  `idea_read_file`、`clion_read_file`、`pycharm_read_file`……（DSH 侧 `mcp__<ide>__<tool>`），
  映射见 `editor/IdeIdentity.kt`（产品码 `IC/IU/CL/PY/GO/WS/PS/RM/RD/DG/RS/QA/AC/DS/MPS`，
  取不到时退回版本名，再退回 `intellij`）。
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

- 不支持远程开发/Gateway 的内嵌渲染（只做检测与浏览器降级）。
- `intellij_hover` / `intellij_code_actions` 依赖内部 API，已收敛在 `IdeAdapter`，
  平台升级时坏在那里、且工具会退化为"不可用"而不是让整个 server 挂掉。
