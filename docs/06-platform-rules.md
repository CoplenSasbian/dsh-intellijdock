# 06 · 平台规则：线程、生命周期、UI 外壳

移植后 80% 的"诡异 bug"来自这一页。VS Code 扩展是**单线程事件循环**，IntelliJ 是
**EDT + 多线程池 + ReadAction 门禁**，模型完全不同。

---

## 1. 线程模型（背下来）

| 要做的事 | 必须在哪里 | 写法 |
|---|---|---|
| 读 PSI / Document / VFS 内容 | 任意线程，但**在 ReadAction 内** | `ReadAction.compute<T, E> { … }` |
| 改 Document / PSI / VFS | EDT，且**在 WriteCommandAction 内** | `WriteCommandAction.runWriteCommandAction(p) { … }` |
| 碰 Swing / Editor / ToolWindow | EDT | `ApplicationManager.getApplication().invokeLater { … }` |
| 网络 / 磁盘 / 起进程 | 后台线程 | 自己的 executor 或 `AppExecutorUtil` |
| 弹对话框 | EDT | 且注意模态（见 §2） |

```kotlin
// 读：MCP worker 线程上
val text = ReadAction.compute<String, RuntimeException> {
    FileDocumentManager.getInstance().getDocument(vf)?.text ?: ""
}

// 带超时/可取消的读（推荐给"可能会慢"的工具：search / diagnostics / hover）
val infos = ReadAction.nonBlocking<List<HighlightInfo>> { collect(vf) }
    .withDocumentsCommitted(project)
    .expireWith(service)
    .finishOnUiThread(ModalityState.any()) { result -> … }
    .executeSynchronously(3_000)          // 超时返回 null，别让 MCP 请求挂死
```

## 2. 三个必背的坑

### 2.1 `ModalityState` —— "后台跑完了，界面还在读秒"

`ApplicationManager.getApplication().invokeLater(block)` 默认用 `defaultModalityState()`；
**在后台线程上它等于 NON_MODAL**，于是当设置页（模态对话框）开着时，所有回调都被推迟到对话框关闭。
症状：任务早完成、日志也写了，UI 却一直停在"进行中"。

```kotlin
// 所有"后台 → UI"的更新统一走这个
private fun postUi(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block, ModalityState.any())
```

### 2.2 公共线程池是**无界队列**

`executeOnPooledThread` / `AppExecutorUtil` 在索引期会被占满，任务排队且**不报错、不执行**，
用户看到的是"点了没反应"。所以：长任务（配对心跳、等待 DSH、批量扫描）用**自建单线程 daemon executor**，
并在日志里打"submitted / started"，以便区分"没被调度"和"卡在某步"。

### 2.3 `ReadAction` 里不要做 IO 和等待

反过来也别在 `ReadAction` 里 sleep/HTTP。`ReadAction` 是全局写锁，卡住会让整个 IDE 冻结。
凡是"等一下再读"（等分析完成、等索引）都放到**外面**，用非阻塞方式轮询或 `DumbService` 等。

## 3. dumb mode（索引期）

- `DumbService.isDumb()` 为真时，PSI 索引相关 API 会抛 `IndexNotReadyException`；
- 工具里**先检查**，返回 `{ reason: "indexing", retry: true }` 这种结构化结果，比抛异常好；
- 需要"等索引完"就用 `DumbService.runReadActionInSmartMode { … }`（会阻塞，务必带超时）。

## 4. 进度与取消

- 任何可能 >300ms 的读都要能被打断：
  `ProgressManager.checkCanceled()` 放在循环里；
- MCP 请求本身没有取消通道，所以**超时就是唯一退路**：`executeSynchronously(timeoutMs)`；
- 用户关掉工具窗口 ≠ 取消 MCP 请求：服务生命周期挂在 project 上，不是 tool window 上。

## 5. 生命周期归属

| 对象 | 生命周期 | 谁释放 |
|---|---|---|
| `JBCefBrowser` / `JBCefJSQuery` | tool window content | `content.setDisposer(panel)` + `Disposer.register` |
| `EditorMcpServer`（HTTP） | project（可手动关） | `DockService.dispose()` / 用户关工具 |
| 配对 executor | project | `dispose()` 里 `shutdownNow()` |
| VFS 监听 | project | `messageBus.connect(this)`，随 service 释放 |
| 定时的探测 | project | 同上 |

`ProjectManagerListener.projectClosing` 里要：反注册所有目录、停 server、停心跳。

## 6. UI 外壳

### 6.1 工具窗口

```xml
<extensions defaultExtensionNs="com.intellij">
  <toolWindow id="DSH Dock"
              anchor="right"
              icon="/icons/dock.svg"
              factoryClass="com.deepseek.dsh.idea.dock.DockToolWindowFactory"/>
</extensions>
```

- `anchor="right"` ≈ VS Code 的次要侧边栏；用户可以拖走、浮动；
- `shouldBeAvailable(project)` 里别做重活（会在启动时对所有项目调用）；
- 图标必须是 13×13 的 SVG（`icons/dock.svg`），沿用 `dsh-vsdock/media/dock.svg` 的形状即可。

### 6.2 动作与快捷键

```xml
<actions>
  <action id="DshDock.Open" class="…OpenDockAction" text="Open DSH Dock" icon="/icons/dock.svg">
    <keyboard-shortcut keymap="$default" first-keystroke="ctrl alt D"/>
    <keyboard-shortcut keymap="Mac OS X" first-keystroke="meta alt D"/>
    <add-to-group group-id="ToolsMenu" anchor="last"/>
  </action>
  <action id="DshDock.Reload" …>
  <action id="DshDock.OpenInBrowser" …>
  <action id="DshDock.StartDsh" …>
  <action id="DshDock.Retry" …>
  <action id="DshDock.UseDetectedPort" …>
  <action id="DshDock.OpenPortSettings" …>
  <action id="DshDock.OpenQuickDockLog" …>
  <action id="DshDock.OpenDiffForActiveFile" …>
  <action id="DshDock.ToggleTools" text="Enable / Disable editor tools for DSH" …>
</actions>
```

VS Code 的 13 个命令 → 动作的映射：

| VS Code 命令 | 动作 id | 备注 |
|---|---|---|
| `dshVsdock.open` | `DshDock.Open` | Ctrl+Alt+D |
| `dshVsdock.reload` | `DshDock.Reload` | 重新探测 + 重渲染 |
| `dshVsdock.openInBrowser` | `DshDock.OpenInBrowser` | `BrowserUtil.browse(pageUrl(port, "browser"))` |
| `dshVsdock.startDsh` | `DshDock.StartDsh` | 决策 C |
| `dshVsdock.retry` | `DshDock.Retry` | |
| `dshVsdock.useDetectedPort` | `DshDock.UseDetectedPort` | 写设置 |
| `dshVsdock.openPortSettings` | `DshDock.OpenPortSettings` | `ShowSettingsUtil.getInstance().showSettingsDialog(project, DshDockConfigurable::class.java)` |
| `dshVsdock.openLog` | `DshDock.OpenQuickDockLog` | 打开 `%LOCALAPPDATA%\dsh-quickdock\host.log` |
| `dshVsdock.openDiff` | `DshDock.OpenDiffForActiveFile` | 当前文件 vs 基线 |
| `dshVsdock.reviewEnable/Disable` | `DshDock.ToggleReview` | 合成一个（插头式切换） |
| `dshVsdock.mcpPair/Unpair` | `DshDock.ToggleTools` | 合成一个 |

> 平台没有"命令面板的 when 条件"那一套；工具窗口标题栏按钮用 `ToolWindow.setTitleActions(listOf(...))`。

### 6.3 设置

```kotlin
@State(name = "DshDockSettings", storages = [Storage("dsh-dock.xml")])
@Service(Service.Level.PROJECT)
class DshDockSettings : PersistentStateComponent<DshDockSettings.State> {
    data class State(
        var port: Int = 47910,
        var followQuickDockConfig: Boolean = true,
        var statusBar: Boolean = true,
        var mcp: Boolean = false,
        var mcpPort: Int = 47920,
        var mcpWriteTools: Boolean = false,
        var reviewEdits: Boolean = true,
        var dshCommand: String = "dsh",
        var dshProfile: String = "",
        var dshArgs: String = ""
    )
    private var state = State()
    override fun getState() = state
    override fun loadState(s: State) { state = s }
    companion object {
        fun getInstance(project: Project): DshDockSettings = project.service<DshDockSettings>()
    }
}
```

- `Configurable` + `SearchableConfigurable`（`getId()` 用 `dsh.ideadock`，`parentId = "tools"`）；
- VS Code 的 `scope: "window"` = **项目级** state（IDEA 一个 IDE 可开多个项目，别用 application 级）；
- 端口输入用 `NumberFormatException` 友好的校验：`1..65535`，`0` 只作为"QuickDock 侧关闭"的提示，
  不能写进设置。

### 6.4 状态栏

```kotlin
class DockStatusBarFactory : StatusBarWidgetFactory {
    override fun getId() = "DshDockStatus"
    override fun getDisplayName() = "DSH Dock"
    override fun createWidget(project: Project): StatusBarWidget = DockStatusWidget(project)
    override fun isAvailable(project: Project) = DshDockSettings.getInstance(project).state.statusBar
}
```

- widget 实现 `CustomStatusBarWidget` + `TextPresentation`，点击 `DshDock.Open`；
- 状态文案照 VS Code 版：`DSH Dock`（可达）/ `DSH Dock: 未连接`（tooltip 带端口与失败原因）。

### 6.5 通知

```kotlin
<notificationGroup id="DSH Dock" displayType="BALLOON"/>
```
- 4 参构造：`Notification(groupId, title, content, type)`（3 参无内容版不存在——实测坑）；
- 自动审阅的通知要带两个 action：「审阅改动」「忽略」；
- 失败类通知必须带可执行的下一步（启动 DSH / 打开设置 / 重试）。

### 6.6 i18n

- `AbstractBundle` + `messages/DshDockBundle.properties`（en）+ `DshDockBundle_zh_CN.properties`；
- `<resource-bundle>messages.DshDockBundle</resource-bundle>`；
- JDK 9+ 的 `PropertyResourceBundle` 支持 UTF-8（JEP 226），中文可直接写；但为了与平台其余
  资源文件一致，建议用 IDE 的 Resource Bundle 编辑器（会自动转 `\uXXXX`）；
- 把 `dsh-vsdock/scripts/check-strings.mjs` 的"每个 key 都必须有使用者、每个用户字符串都必须有翻译"
  做成一个 Gradle 校验任务——这是原项目里性价比最高的自检。

### 6.7 「启动 DSH」（决策 C）

照 `dsh.ts` 拼命令行：`dsh --profile <name> [args]`；在 IDE 里两种落法：

1. **Run 工具窗口**：`GeneralCommandLine` + `OSProcessHandler`，输出进 console（可停可看日志）；
2. **Terminal 工具窗口**：`TerminalToolWindowManager.createNewSession(...)`，和 VS Code 版行为最像，
   但该 API 属于捆绑插件、跨版本不稳定。

建议先用 ①，把"起来后重连"做成轮询 `/quickdock/api/meta`（间隔 1.5s，超时 90s，照 `DSH_START_TIMEOUT_MS`）。

## 7. plugin.xml 全量骨架

```xml
<idea-plugin>
  <id>dsh.ideadock</id>
  <name>DSH Dock</name>
  <vendor>dsh</vendor>
  <description><![CDATA[
    把 DeepSeek Harness 的对话界面放进 IDE 侧边工具窗口，并给 agent 一组编辑器工具。
    全程只走 127.0.0.1。
  ]]></description>

  <depends>com.intellij.modules.platform</depends>
  <!-- 2026.2 起 JCEF 拆成内置插件；since-build 需 ≥ 253（2025.3.1） -->
  <depends>com.intellij.modules.jcef</depends>

  <extensions defaultExtensionNs="com.intellij">
    <toolWindow id="DSH Dock" anchor="right" icon="/icons/dock.svg"
                factoryClass="com.deepseek.dsh.idea.dock.DockToolWindowFactory"/>
    <projectConfigurable instance="com.deepseek.dsh.idea.ui.DshDockConfigurable"
                         displayName="DSH Dock" id="dsh.ideadock" parentId="tools"/>
    <statusBarWidgetFactory implementation="com.deepseek.dsh.idea.ui.DockStatusBarFactory"/>
    <notificationGroup id="DSH Dock" displayType="BALLOON"/>
  </extensions>

  <actions>
    <!-- 见 §6.2 -->
  </actions>
</idea-plugin>
```

> **不要**在 `<idea-plugin>` 根下写 `<icon>`：2024.x 的 descriptor 解析器不认这个子元素，
> 启动期 `LOG.error` 直接让插件变成 "contains invalid plugin descriptor"
> （`deepseek-harness-idea` 踩过；虽然 `since-build=253` 已避开 2024.x，仍别写）。

## 8. M5 验收清单

- [ ] 10 个设置项都能改、能持久化、改完立即生效（端口/开关）
- [ ] 状态栏开关按设置显隐；点击能打开工具窗口
- [ ] 13 个动作都能从 Find Action 搜到并工作
- [ ] Ctrl+Alt+D / Cmd+Alt+D 生效，且不与 IDE 默认键冲突
- [ ] 中文环境下所有文案都有翻译（含通知与 tooltip）
- [ ] 关项目时 server 停止、注册清理，进程列表里不留东西

---

## 9. 实测更正（2025.3 / build 253.28294.334）

实现本页内容时核对出的差异（都已按右侧落地）：

| 现象 | 真因 | 修法 |
|---|---|---|
| `getClickConsumer(): java.util.function.Consumer<MouseEvent>` 编译不过 | 平台用的是自己的 SAM 接口 | `com.intellij.util.Consumer<MouseEvent>?`（注意返回类型可空） |
| 从 `selectedTextEditor` 拿 `TextEditor` 编译不过 | 2025.3 的 `getSelectedTextEditor()` 直接返回 `Editor` | 直接用 `Editor`；`document`/`caretModel`/`selectionModel` 都在它上面 |
| 动作用带 `bundle`+`key` 的 `<action>` 担心描述符 | 动作文案走平台自己的 i18n 才有 `%key` 的形式 | 动作的 `text`/`description` 写英文常量（Find Action 里可见），**界面文案**仍然全部走 `DshDockBundle`（130 键中英同步，`scripts/check-strings.mjs` 自检） |
| 状态栏文案刷新时机 | `StatusBar.updateWidget` 必须在 EDT | 服务统一用 `invokeLater(ModalityState.any())` 推状态，widget 只画最后一次结果 |
| 设置页 `apply()` 里弹错误框 | 需要一个父组件/项目上下文 | `Messages.showErrorDialog(project, …)` 可用；校验失败时**不写回** state，让用户改完再 Apply |

另外两条与小节号对不上的：动作不是 13 个而是 **11 个**（review 与 mcp 各合并成一个开关），
`Notification` 的 4 参构造（group/title/content/type）在 2025.3 仍然有效，本页 §6.5 的提醒保留。
