# 03 · 停靠窗：JCEF + iframe 壳

## 1. 为什么必须自己搭一层 iframe 壳

QuickDock 的壳页（`/quickdock?view=…`）**是被设计成"被别人嵌"的**，看它的两个条件：

- 插头按钮：`if (mcpButton && dockMcpRequested && window.parent !== window)`；
- 点击文件：`window.parent.postMessage({type:'quickdock:open-file', …})`，顶层文档没有 parent。

所以直接 `browser.loadURL("…/quickdock?view=vscode")` 当顶层文档 → **没有插头、点文件没反应**，
而这两条正是这个插件存在的理由。

宿主壳的职责（就三件）：

```
┌──────────────── 你的壳页（data: 文档） ────────────────┐
│  <iframe src="http://127.0.0.1:47910/quickdock?view=vscode&…"> │
│  ① 收子页消息 → 转发给 Kotlin（JBCefJSQuery）            │
│  ② 收 Kotlin 状态 → postMessage 给子页                   │
│  ③ 铺满、配色、背景（不要别的）                          │
└──────────────────────────────────────────────────────────┘
```

## 2. 壳页 HTML（可直接用的版本）

由 Kotlin 拼字符串（照 `dsh-vsdock/src/html.ts` 的 `renderFrame` 改：去掉 `acquireVsCodeApi`、
把 `command:` 链接换成桥调用、CSS 变量改成自己注入）。

```html
<!DOCTYPE html>
<html lang="__LANG__">
<head>
<meta charset="UTF-8">
<style>
  :root {
    --bg: __IDE_BG__;          /* 从 IDE 取，别用 #1f1f1f 硬编码 */
    --fg: __IDE_FG__;
    --border: __IDE_BORDER__;
    color-scheme: __SCHEME__;  /* light | dark */
  }
  html, body { height: 100%; margin: 0; overflow: hidden; background: var(--bg); }
  iframe { display: block; width: 100%; height: 100%; border: 0; background: var(--bg); }
</style>
</head>
<body>
<iframe id="dock" src="__PAGE_URL__" allow="clipboard-read; clipboard-write"></iframe>
<script>
(function () {
  var frame = document.getElementById('dock');
  var origin = new URL(frame.src).origin;

  // Kotlin → 子页：工具状态。Kotlin 用 executeJavaScript 调这个名字。
  window.dshMcpState = function (on, detail) {
    frame.contentWindow.postMessage(
      { type: 'quickdock:mcp-state', on: !!on, detail: detail || '' }, origin);
  };

  window.addEventListener('message', function (event) {
    if (event.origin !== origin) return;          // 只认子页
    var data = event.data || {};
    if (data.type === 'quickdock:mcp-toggle') { __TOGGLE_CALL__ ; return; }
    if (data.type === 'quickdock:open-file' && typeof data.path === 'string' && data.path) {
      __OPEN_CALL__ ;                             // 参数表达式用 data.path
    }
  });
})();
</script>
</body>
</html>
```

`__TOGGLE_CALL__` / `__OPEN_CALL__` 用 `JBCefJSQuery.inject()` 生成，例如
`toggleQuery.inject("'toggle'")`、`openQuery.inject("data.path")`。

> **`inject` 的语义（2025.3 字节码核对过）**：它生成的是
> `window.<funcName>({request: '' + <你的表达式>, onSuccess: function(response) {}, onFailure: function(error_code, error_message) {}});`
> 也就是说参数是**JS 表达式，不是字符串字面量**，并且返回值自带分号、是完整语句。
> 所以 `inject("'toggle'")` 得到 `'' + 'toggle'`，`inject("data.path")` 得到 `'' + data.path`；
> 把它插进 `if (...) { <call> ; return; }` 或 `onclick="...; <call>; return false;"` 都合法
> （多一个分号无害）。反过来说，**不要**把已加引号的字符串再传进去转义一次。

> 离线卡片（`renderOffline`）和"连接中"卡片（`renderConnecting`）**完全不需要 iframe**，
> 直接把 `html.ts` 的三个页面各搬一份；`href="command:dshVsdock.…"` 换成
> `href="#" onclick="hostAction('startDsh')"`，用第三个 JSQuery 回传动作名即可。

## 3. Kotlin 骨架

```kotlin
class DockToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DockPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)                       // 关标签时释放 JCEF
        toolWindow.contentManager.addContent(content)
    }
}

class DockPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private var browser: JBCefBrowser? = null
    private var toggleQuery: JBCefJSQuery? = null
    private var openQuery: JBCefJSQuery? = null
    private var actionQuery: JBCefJSQuery? = null

    fun show(state: DockState) {
        if (!JBCefApp.isSupported()) { showUnavailable(); return }

        if (browser == null) {
            val b = JBCefBrowser()
            // ① 必须先建 query：CEF 在页面加载时注入 window.<funcName>
            toggleQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
                addHandler { _ -> service.toggleTools(); null }
            }
            openQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
                addHandler { raw -> openFileFromDock(raw); null }   // raw 里按 \u0000 切
            }
            actionQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
                addHandler { action -> runAction(action); null }
            }
            Disposer.register(this, b)                              // JBCefBrowser 是 Disposable
            add(b.component, BorderLayout.CENTER)
            browser = b
        }
        val html = DockPage.frame(
            pageUrl        = state.pageUrl,        // 见 02-contracts §3
            lang           = "zh-CN",
            dockId         = state.dockId,
            folders        = state.contentRoots,
            toggleCall     = toggleQuery!!.inject("'toggle'"),
            openCall       = openQuery!!.inject("data.path"),
            actionCall     = actionQuery!!.inject("name"),
            theme          = DockTheme.current()
        )
        browser!!.loadHTML(html)                   // ② 每次重渲染都会重建文档，query 复用
    }

    /** 子页→Kotlin 状态回推：工具开关、启动中、错误。 */
    fun pushMcpState(on: Boolean, detail: String) {
        val js = "window.dshMcpState($on, ${jsonString(detail)})"
        browser?.cefBrowser?.executeJavaScript(js, "", 0)
    }

    override fun dispose() { browser = null }       // Disposer 已经登记了 browser
}
```

注意点：

- `JBCefJSQuery.create()` 的入参类型是 `JBCefBrowserBase`，`JBCefBrowser` 需要转型；
- 三个 query 都实现 `JBCefDisposable`，**随 panel 一起 dispose**（用 `Disposer.register` 串起来）；
- `loadHTML` 可以反复调（换端口、换文件夹时重渲染），**不要**为此重建 browser——
  重建会丢 CEF 的登录态（app-proxy 的 cookie 由 Host 管，但页面状态、滚动位置会丢）；
- 端口变了：重 `loadHTML`；DSH 连不上：换离线卡片（同一 browser，直接 loadHTML 另一份 HTML）。

## 4. 主题

JCEF 里没有 `--vscode-*` 这些变量。壳页只需要三种颜色，从 IDE 取：

```kotlin
object DockTheme {
    data class Colors(val bg: String, val fg: String, val border: String, val dark: Boolean)

    fun current(): Colors {
        val bg = JBColor.PanelBackground
        return Colors(
            bg     = bg.toHex(),                                  // UIUtil / ColorUtil.toHex
            fg     = JBColor.foreground.toHex(),
            border = JBColor.border().toHex(),
            dark   = !JBColor.isBright()
        )
    }
}
```

- 监听 `LafManagerListener`（L&F/主题变化）和 `EditorColorsManager` 的变化 → 重新 `loadHTML`
  （或者只 `executeJavaScript` 改 CSS 变量，更省；但重渲染更简单，页面状态会丢，权衡一下）；
- 子页（QuickDock）自带 `data-theme` 和完整配色，**不需要**你把 IDE 主题喂给它；
- 滚动条样式可用 `JBCefScrollbarsHelper.buildScrollbarsStyle()` 注入，视觉上会接近原生。

## 5. 可用性检测与降级

```kotlin
if (!JBCefApp.isSupported()) {
    // 常见原因，直接写进卡片：
    // 1) IDE 用的不是带 JCEF 的 JBR（自定义 JDK / 精简 JBR）；
    // 2) 2026.2+ 未启用内置插件 "Web Browser (JCEF)"；
    // 3) 远程开发 / Gateway（JCEF 由客户端渲染，多数组合不可用）。
}
```

降级必须是**可用的降级**，不是白屏：显示一张卡片，写明原因 + 「在浏览器中打开」
（`BrowserUtil.browse(pageUrl(port, "browser"))`）+ 「重试」。这条路径在远程开发场景是**唯一**能用的。

其它可选检测：`JBCefApp.isJcefFromJbr()`（判断 JCEF 是否来自 JBR）——用于把"为什么不可用"写准。

## 6. 浏览器行为要接的 handler

| handler | 要做什么 |
|---|---|
| `CefLifeSpanHandler.onBeforePopup` | 返回 true 并 `BrowserUtil.browse(url)`，别在工具窗里开新窗口 |
| `CefDisplayHandler.onAddressChange` | `http(s)` 外链 → 系统浏览器；`file:` 本机路径 → 在 IDE 打开（可选） |
| `CefDownloadHandler.onBeforeDownload` | 至少要弹一次保存对话框；不接的话点附件会静默失败 |
| `CefContextMenuHandler.onBeforeContextMenu` | 可选：裁掉"查看源码/打印"等，保留复制/粘贴/全选 |
| `CefLoadHandler.onLoadError` | 记录错误码（DSH 没起来时是 `ERR_CONNECTION_REFUSED`），转成离线卡片文案 |

## 7. 调试

- DevTools 默认在 **9222** 端口；改端口用 registry `ide.browser.jcef.debug.port`；
- 浏览器右键菜单里的 "Open DevTools" 只在 internal 模式 +
  registry `ide.browser.jcef.contextMenu.devTools.enabled=true` 时出现；
- 页面 console 用 `CefDisplayHandler.onConsoleMessage` 转发到 IDE 日志（`Logger.getInstance(...)`），
  排查"消息为什么没到"的最快手段；
- 也可以 `browser.openDevtools()` 开一个独立 DevTools 窗口。

## 8. 已知坑（每条都对应一个真实失败）

| # | 坑 | 症状 | 修法 |
|---|---|---|---|
| 1 | 先 `loadHTML` 后建 `JBCefJSQuery` | `window.<fn>` is not a function，桥全哑 | 先建 query，再 load（官方示例也是这个顺序） |
| 2 | 忘了回发 `quickdock:mcp-state` | 插头一直转圈、点了没反应 | 启动/停止后立刻回推；启动中回 `{on:false, detail:'starting…'}` |
| 3 | 把 `mcp` 参数当成开关 | 传 `mcp=0` 仍然显示插头，误以为能"默认关" | 它是"存在即显示"，真实状态靠消息回推 |
| 4 | `open-file` 的 path 直接当路径用 | 打开失败（路径尾巴带 `\u0000code.hljs`） | 按第一个 `\u0000` 切 |
| 5 | 壳页用 `--vscode-*` 变量 | 浅色主题下白底黑字错位/看不见 | 注入 IDE 颜色 |
| 6 | 2026.2 上不声明 `com.intellij.modules.jcef` | `NoClassDefFoundError: com/intellij/ui/jcef/JBCefBrowser` | `plugin.xml` 加 `<depends>`；`since-build=253` |
| 7 | 用自定义 JDK 起 IDE | `JBCefApp.isSupported()==false`，工具窗空白 | 检测 + 降级卡片 |
| 8 | 直接 `loadURL` 顶层文档 | 没插头、点文件没反应，且不报错 | 必须 iframe 壳（本文 §1） |
| 9 | tool window dispose 不释放 browser | 关一次开一次内存涨，最终 CEF 崩 | `content.setDisposer(panel)` + `Disposer.register` |
| 10 | 每次换端口都重建 browser | 页面状态/滚动/登录态全丢，还漏资源 | 只 `loadHTML` |
| 11 | iframe 的 `allow` 少了 clipboard | 复制粘贴在停靠窗里失效 | 带上 `allow="clipboard-read; clipboard-write"` |
| 12 | 在 EDT 上同步等 `loadHTML` 完成 | UI 卡住 | `loadHTML` 本身异步；不要在 EDT 上轮询 |

## 9. M1 验收清单

- [ ] 工具窗口能打开，显示 DSH 会话（新会话界面与 VS Code 版一致）
- [ ] 标题栏插头可见；点击后 MCP 起来，插头变"开"，tooltip 有工具数
- [ ] 点会话里工具卡片中的文件路径 → 文件在 IDE 编辑器里打开
- [ ] 停靠窗内滚动/输入/快捷键正常；IDE 主题切换后配色不崩
- [ ] DSH 没起来时显示离线卡片，且「启动 DSH」「在浏览器打开」「重试」可用
- [ ] 关闭/重开工具窗口，JCEF 资源不泄漏（内存曲线平稳）
