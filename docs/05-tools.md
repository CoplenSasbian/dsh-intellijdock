# 05 · 17 个编辑器工具：`vscode.*` → IntelliJ 平台

对应源文件：`dsh-vsdock/src/tools.ts`（813 行）+ `diff.ts` + `review-watcher.ts`。

命名：`intellij_*`（DSH 侧最终是 `mcp__intellij__intellij_read_file`；嫌重复可以把工具名前缀去掉）。

---

## 1. 通用约定

| 约定 | 值 |
|---|---|
| 位置参数 | 文本里给 1-based `line` / `column`；入参也是 1-based |
| 路径 | 绝对路径；接受 `C:\...` 与 `/`；比较时归一化（大小写不敏感 + 统一分隔符，照 `src/paths.ts`） |
| 上限 | 行数 2000、字节 256KB、符号 200、诊断 200、搜索结果 200、单次预览 200 字符 |
| 截断 | 返回 `truncated: true`，并在文本里告诉模型"用更窄的参数再问一次" |
| 错误 | 抛异常 → MCP 层包成 `error: …` 文本（`isError: true`） |
| 只读/写入 | 前 **13** 个只读；后 5 个写入，由设置项 `dshIdeadock.mcpWriteTools` 控制 |

> 总数是 **18**（13 + 5）。VS Code 版的 `editorTools()` 实际也是 18 个，本节旧版写的"17 个 / 前 12 个"
> 是笔误；`tools/list` 与指纹都按 18 计算。

## 2. 只读工具映射表

| 工具 | VS Code | IntelliJ 平台 | 稳定性 |
|---|---|---|---|
| `workspace_folders` | `workspace.workspaceFolders` | `ProjectRootManager.getInstance(p).contentRoots` + `FileEditorManager.selectedTextEditor` | 🟢 |
| `selection` | `activeTextEditor.selection` | `(FileEditorManager.selectedTextEditor as? TextEditor)?.editor` + `selectionModel` / `caretModel` | 🟢 |
| `read_file` | `openTextDocument` | `VirtualFile` + `FileDocumentManager.getDocument(vf)?.text`（含未保存）→ 否则 `VfsUtilCore.loadText` | 🟢 |
| `symbols` | `executeDocumentSymbolProvider` | PSI 递归 `PsiNamedElement`（v1）；`StructureViewFactory` 更准但绕 | 🟡 |
| `list_files` | `workspace.findFiles(glob)` | `VfsUtilCore.visitChildrenRecursively` + `ProjectFileIndex` 过滤 | 🟢 |
| `search` | 遍历 + 正则 | `PsiSearchHelper.processElementsWithWord`（索引）或 `FindInProjectUtil`（全文） | 🟡 |
| `definitions` | `executeDefinitionProvider` | `GotoDeclarationAction.findTargetElement(p, editor, offset)` / `TargetElementUtil` | 🟡 |
| `references` | `executeReferenceProvider` | `ReferencesSearch.search(psi, GlobalSearchScope.projectScope(p), false)` | 🟢 |
| `hover` | `executeHoverProvider` | `DocumentationManager.getInstance().getDocumentation(element, null)` → `getHtml()` 去标签 | 🟡 |
| `diagnostics` | `languages.getDiagnostics` | `DocumentMarkupModel.forDocument(doc, p, false).allHighlighters` 取 `HighlightInfo`；要"先分析完"用 `DaemonCodeAnalyzerImpl` | 🔴 内部 API |
| `code_actions` | `executeCodeActionProvider` | `IntentionManager.getInstance().getAvailableIntentions(editor, psiFile, offset)` | 🔴 签名跨版本变 |
| `reveal` | `showTextDocument({preview})` | `FileEditorManager.openTextEditor(OpenFileDescriptor(p, vf, line, col), true)` | 🟢 |
| `open_diff` | 自定义 provider + diff tab | `DiffManager.showDiff(p, SimpleDiffRequest(...))` + `DiffContentFactory` | 🟡 见 §4 |

🟢 稳定公开 API ｜ 🟡 公开但语义/性能有坑 ｜ 🔴 内部 API，必须收敛到 `IdeAdapter`

## 3. 逐条要点

### `workspace_folders`
```kotlin
val roots = ProjectRootManager.getInstance(project).contentRoots      // 多根项目
val editor = FileEditorManager.getInstance(project).selectedTextEditor
```
- **不要用 `project.basePath`**（多根项目为 null，且已不推荐）；
- `workspaceFile` 概念不存在（IDEA 没有 `.code-workspace`），返回 `null` 或省略。

### `selection`
```kotlin
val editor = FileEditorManager.getInstance(project).selectedTextEditor
val sm = editor?.selectionModel
val text = sm?.selectedText?.take(4000)
val caret = editor?.caretModel?.currentCaret?.offset
```
- 必须 EDT（`EdtUtil.invokeAndWait` / `invokeLater`）；
- 从 offset 反算 line/column 用 `document.getLineNumber(offset)` + `offset - lineStartOffset`；
- **Document 没有 `selectionModel`**（`deepseek-harness-idea` 实测过）——要从 `TextEditor` 拿。

### `read_file`
```kotlin
val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: error("not found: $path")
val doc = FileDocumentManager.getInstance().getDocument(vf)      // 有未保存修改时优先
val text = doc?.text ?: VfsUtilCore.loadText(vf).take(MAX_BYTES)
val dirty = doc != null && FileDocumentManager.getInstance().isDocumentUnsaved(doc)
```
- **`Document.isModified` 不存在**，用 `isDocumentUnsaved`；
- 读 `Document.text`、PSI 一律在 `ReadAction` 里；
- 大文件先看 `vf.length`，超限只返回前 N 行 + `truncated`；
- 二进制：`vf.fileType.isBinary` 直接拒绝；编码用 `vf.charset`（`LoadTextUtil`）。

### `symbols`
- v1 用 PSI：递归 `PsiFile` 子元素，收 `PsiNamedElement`，取 `name` + `textOffset` →
  `document.getLineNumber(offset)+1`，按缩进/嵌套深度限 3 层；
- 想要"和 IDE 大纲一模一样"再用
  `StructureViewFactory.getInstance(p).getStructureViewBuilder(psiFile)?.createStructureViewModel(editor)`
  → 遍历 `TreeElement.children`，把 `NodeDescriptor.element` 转成行号。**麻烦且容易空**，放 v2。

### `list_files` / `search`
- 遍历：`VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Any>() { override fun visitFile(file: VirtualFile): Boolean { … } })`
  （`visitFile` 返回 `Boolean`，false = 跳过子树；`VfsUtil.visitChildrenRecursively` **不存在**）；
- 排除：`ProjectFileIndex.isInContent` + 硬编码跳过 `out/build/target/node_modules/.git/dist`；
- 全文检索优先用索引（`PsiSearchHelper` / `FileBasedIndex`），不要自己遍历磁盘——大仓库会卡死；
  `FindInProjectUtil` 是内部 API 但很稳（IDEA 自己的 Find in Path 用它）；
- `ReadAction` + `ProgressIndicator`（`ProgressManager.getInstance().runProcessWithProgressSynchronously`
  或 `ReadAction.nonBlocking(...).executeSynchronously(timeout)`），并允许被打断。

### `definitions` / `references`
```kotlin
val target = GotoDeclarationAction.findTargetElement(project, editor, offset)  // 或 TargetElementUtil
val query = ReferencesSearch.search(psi, GlobalSearchScope.projectScope(project), false)
```
- `ReadAction` + 进度；`query.forEach { … }` 里注意 `MaxResults` 上限；
- 结果路径用 `psi.containingFile.virtualFile.path`。

### `hover`
```kotlin
val doc = DocumentationManager.getInstance().getDocumentation(element, null)  // 可能 null/阻塞
val html = doc?.getHtml()?.let(::htmlToText)
```
- 很多元素没 doc → 返回空要明说，不要让模型以为"没问题"；
- 解析要 `ReadAction`，且可能触发索引/慢查询 → 加超时（例如 2s 拿不到就返回部分）。

### `diagnostics`（最难的一个）
两条路：

```kotlin
// A. 只读现状（快、稳一点）：不触发分析
val markup = DocumentMarkupModel.forDocument(document, project, false)
val infos = markup.allHighlighters.filterIsInstance<HighlightInfo>()

// B. 等分析完再读（准，但内部 API + 可能很慢）
if (DaemonCodeAnalyzer.getInstance(project).isRunning(psiFile)) { /* 等一下或直接回"正在分析" */ }
val infos = DaemonCodeAnalyzerImpl.getHighlights(psiFile, HighlightSeverity.INFORMATION, editor)
```
- `HighlightSeverity` 映射到 `error` / `warning` / `weak_warning` / `info`，与 VS Code 的
  `DiagnosticSeverity` 对齐；**不要**把 `INFORMATION` 以下都算进来（会爆炸）；
- dumb mode（索引期）直接返回 "indexing, retry later"，别卡住；
- 上限 200 条，按 severity 降序。

### `code_actions`
- `IntentionManager.getAvailableIntentions(editor, psiFile, offset)` 的签名在不同平台版本变过；
- **v1 只列不执行**（需要 `IntentionAction.invoke` + `WriteCommandAction`，风险高）；
- 若平台版本不兼容，退化为"列出 `HighlightInfo` 上的 quick fixes 数量"，宁可少给也别崩。

### `reveal`
```kotlin
val descriptor = OpenFileDescriptor(project, vf, line - 1, column - 1)
FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
```
- 会抢焦点——这是 IDE 的语义，别硬仿 VS Code 的 preview tab；
- 需要"后台打开"就用通知，参考 §4。

## 4. 审阅 diff：**改设计**

VS Code 能开"不抢焦点的预览 diff 标签"；IntelliJ 的 diff 是编辑器区的普通文件编辑器，
弹出来会切走焦点、打断输入。**不要照搬**。建议：

| 触发 | 行为 |
|---|---|
| agent 调 `intellij_open_diff`（显式） | 直接开 diff（这是用户主动要看的） |
| DSH 在后台改了文件（自动审阅） | **发通知**（`NotificationGroup` + `NotificationAction("审阅改动")`），点开才进 diff |

开 diff 的写法：

```kotlin
val left  = DiffContentFactory.getInstance().create(project, baselineText)          // 改动前（自己缓存）
val right = DiffContentFactory.getInstance().create(project, document)              // 改动后（活文档，含未保存）
DiffManager.getInstance().showDiff(project, SimpleDiffRequest("审阅：${vf.name}", left, right, "改动前", "改动后"))
```

- 左岸用**自己缓存的基线文本**，不要依赖 git 插件（`git4idea` 是可选依赖，PyCharm/WebStorm 也未必有）；
- 基线来源：watcher 启动时缓存工作区文本 + 用户保存时更新（完全照 `review-watcher.ts` 的两张 Map）；
- 打开位置：默认编辑器区；想放右边可以按平台 API 调整（⚠️ `FileEditorOpenOptions` 一类的入口跨版本有差异，实测后再写死）。

文件监听（`EditReviewWatcher`）：

```kotlin
project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        for (e in events) {
            if (e is VFileContentChangeEvent || e is VFileCreateEvent) schedule(e.file)
        }
    }
})
```

规则（照 `review.ts` 抄）：

- debounce **350ms**（工具写文件是成批的）；
- 自己保存后 **2s** 内的事件不审阅（否则用户每次 Ctrl+S 都弹 diff）；
- 跳过 `node_modules / out / dist / build / .git / target`、锁文件、二进制；
- 单文件 **>512KB** 不缓存、不 diff；
- 只在**已注册给 DSH 的目录**里生效（未配对的编辑器不要乱弹）。

## 5. 写工具（默认关闭）

| 工具 | API | 注意 |
|---|---|---|
| `intellij_apply_edit` | `WriteCommandAction.runWriteCommandAction(p) { document.replaceString(…) }` | 必须写命令（撤销栈）；改完立刻开审阅 diff |
| `intellij_save_file` | `FileDocumentManager.getInstance().saveDocument(doc)` | 只保存目标文件，别 `saveAllDocuments` |
| `intellij_run_task` | `RunManagerImpl.getInstanceImpl(p).getConfigurationByName(name)` + `ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())` | IDEA 没有"task"概念，按 run configuration 语义 |
| `intellij_terminal_run` | `TerminalToolWindowManager.getInstance(p).createNewSession(...)` | 🔴 捆绑插件 API，跨版本不稳定；可先只做"在 Run 工具窗口里跑一个 Process" |
| `intellij_start_debug` | `XDebuggerManager` 或 `ProgramRunnerUtil` + `DefaultDebugExecutor.EXECUTOR_ID` | 与上面同源 |

写工具打开后，README 必须明确写："agent 可以编辑、保存、运行任务、开终端、启动调试"。

## 6. 建议分期

| 期 | 工具 |
|---|---|
| M2 | `workspace_folders`、`selection`、`read_file` |
| M3a | `list_files`、`symbols`、`search` |
| M3b | `definitions`、`references`、`hover`、`reveal` |
| M3c | `diagnostics`、`code_actions`（这两个要给足降级路径） |
| M4 | `open_diff` + 自动审阅（通知式） |
| M5 | 5 个写工具 |

## 7. M3 验收清单

- [ ] 每个工具的返回在"大文件 / 多根项目 / 未保存缓冲区 / dumb mode"四种情况下都有明确结果
- [ ] `diagnostics` 在索引期不卡住（≤3s 返回或明确说"稍后再试"）
- [ ] 所有结果都有上限且 `truncated` 正确
- [ ] 工具名、描述、schema 与 DSH 侧 `tools/list` 一致（指纹稳定）

---

## 8. 实测更正（2025.3 / build 253.28294.334）

实现时用 `javap` 对着 2025.3 的发行版逐条核对过，本页正文里这几条已经过时：

| 现象 | 真因 | 修法（已在 `IdeAdapter` 落地） |
|---|---|---|
| `DocumentMarkupModel` 找不到 | 类搬了包 | 用 `com.intellij.openapi.editor.impl.DocumentMarkupModel` |
| `DaemonCodeAnalyzer.isRunning(psiFile)` 编译失败 | 逐文件重载已删 | 只剩 `isRunning()`（整体是否在分析）；dumb mode 用 `DumbService.isDumb` 判断 |
| `IntentionManager.getAvailableIntentions(editor, file, offset)` 编译失败 | 该重载被删，只剩无参与 `Collection<String>` 两个 | 改用 `ShowIntentionsPass.getAvailableFixes(editor, file, start, end)`（IDE 自己弹意图菜单用的就是它），拿到 `HighlightInfo.IntentionActionDescriptor` |
| `DocumentationManager.getDocumentation(...)` 编译失败 | 方法被删 | 用 `generateDocumentation(element, null, false)`，返回 HTML，再 `stripHtml` |
| `RunManager.allConfigurations` 编译失败 | Kotlin 化后改名 | `allSettings`（`List<RunnerAndConfigurationSettings>`）/ `allConfigurationsList` |
| `ProgramRunnerUtil` 找不到 | 不在 `.runners` 子包 | `com.intellij.execution.ProgramRunnerUtil.executeConfiguration(project, settings, executor)` |
| `FileEditorManager.selectedTextEditor` 取不到 `TextEditor` | 2025.3 直接返回 `Editor` | 直接用 `Editor`（`document` / `selectionModel` / `caretModel` 都在） |
| `terminal_run` 用 `TerminalToolWindowManager` 风险高 | 捆绑插件 API，跨版本不稳 | 改为在 **Run 工具窗口**里跑命令行（`RunContentExecutor` + `OSCommandLine`），行为与 VS Code 版接近且只用公开 API |

`diagnostics` 仍是"只读现状"：读 `MarkupModel` 上已有的 `HighlightInfo`，不主动触发分析；
索引期直接返回结构化结果（`{"reason":"indexing","retry":true}`），不阻塞。
