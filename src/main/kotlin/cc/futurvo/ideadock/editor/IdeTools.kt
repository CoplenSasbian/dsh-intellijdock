package cc.futurvo.ideadock.editor

import cc.futurvo.ideadock.contract.Glob
import cc.futurvo.ideadock.contract.McpTool
import cc.futurvo.ideadock.contract.Paths
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import java.io.File

/**
 * The editor tools DSH can call through MCP.
 *
 * Thirteen of them only read - the workspace, the active selection, file
 * contents, the outline, diagnostics, definitions, references, hovers, code
 * actions, the finder and the review diff - and nine change something (a buffer,
 * a save, a run configuration, a command line, a debug session, and the job
 * status/output/stop/list tools). The write tools
 * only exist when `dshIdeadock.mcpWriteTools` is on, because they reach past the
 * DSH sandbox into the IDE.
 *
 * Every result is bounded and says when it was truncated, and every read runs
 * inside a `ReadAction` with a timeout for the slow ones: an MCP request that
 * hangs is worse than one that answers "indexing, retry".
 */
object IdeTools {

    private const val MAX_SELECTION = 4_000
    private const val MAX_LINES = 2_000
    private const val MAX_BYTES = 256 * 1024
    private const val MAX_SYMBOLS = 200
    private const val MAX_DIAGNOSTICS = 200
    private const val MAX_MESSAGE = 600
    private const val MAX_SEARCH_RESULTS = 200
    private const val MAX_SEARCH_FILES = 2_000
    private const val MAX_PREVIEW = 200
    private const val MAX_LOCATIONS = 200
    private const val MAX_HOVER_CHARS = 4_000
    private const val MAX_ACTIONS = 50
    private const val MAX_FILES = 500
    private const val MAX_SEARCH_BYTES = 512L * 1024
    private const val MAX_SEARCH_TOTAL_BYTES = 64L * 1024 * 1024
    private const val SEARCH_BUDGET_MS = 20_000L
    private const val MAX_EDIT_CHARS = 512 * 1024
    private const val MAX_LOAD_BYTES = 8 * 1024 * 1024
    private const val READ_TIMEOUT_MS = 5_000L

    /** Every tool, read-only ones first. */
    fun build(
        project: Project,
        log: (String) -> Unit,
        write: Boolean,
        /** Tool-name prefix: the IDE's slug (`idea`, `clion`, …). */
        ns: String = IdeIdentity.slug
    ): List<McpTool> {
        val tools = ArrayList<McpTool>(18)
        tools += readTools(project, log, ns)
        if (write) tools += writeTools(project, log, ns)
        return tools
    }

    /* --------------------------------- read tools ------------------------------ */

    private fun readTools(project: Project, log: (String) -> Unit, ns: String): List<McpTool> = listOf(
        tool(
            name = "${ns}_workspace_folders",
            description = "List the IDE project's content roots and the active editor (path, file type). " +
                "Use it to learn which project the user is in.",
            schema = noArguments()
        ) {
            val folders = read {
                ProjectRootManager.getInstance(project).contentRoots.map { root ->
                    linkedMapOf<String, Any?>("name" to root.name, "path" to root.path)
                }
            }
            val active = edt {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@edt null
                val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return@edt null
                linkedMapOf<String, Any?>(
                    "path" to file.path,
                    "fileType" to file.fileType.name,
                    "dirty" to FileDocumentManager.getInstance().isDocumentUnsaved(editor.document)
                )
            }
            linkedMapOf(
                "folders" to folders,
                "workspaceFile" to null,
                "active" to active
            )
        },

        tool(
            name = "${ns}_selection",
            description = "The active editor: file, file type, cursor position and the selected text " +
                "(truncated at 4000 characters). An empty selection means the caret position alone.",
            schema = noArguments()
        ) {
            val data = edt {
                // 2025.3: getSelectedTextEditor() returns an Editor directly.
                val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@edt null
                val document = editor.document
                val file = FileDocumentManager.getInstance().getFile(document)
                val selection = editor.selectionModel
                val caret = editor.caretModel.offset
                val selected = selection.selectedText ?: ""
                linkedMapOf<String, Any?>(
                    "path" to (file?.path ?: ""),
                    "fileType" to (file?.fileType?.name ?: ""),
                    "dirty" to FileDocumentManager.getInstance().isDocumentUnsaved(document),
                    "caret" to position(document, caret),
                    "selection" to linkedMapOf(
                        "start" to position(document, selection.selectionStart),
                        "end" to position(document, selection.selectionEnd),
                        "empty" to (selection.selectionStart == selection.selectionEnd)
                    ),
                    "text" to (if (selected.length > MAX_SELECTION) {
                        selected.take(MAX_SELECTION) + "\n…[truncated]"
                    } else {
                        selected
                    }),
                    "truncated" to (selected.length > MAX_SELECTION)
                )
            }
            data ?: linkedMapOf<String, Any?>("active" to null)
        },

        tool(
            name = "${ns}_read_file",
            description = "Read an editor file, including unsaved changes. Optional 1-based line range. " +
                "Truncated at 2000 lines or 256 KB.",
            schema = schema(
                linkedMapOf(
                    "path" to stringProperty("Absolute file path."),
                    "startLine" to intProperty("First line (1-based).", minimum = 1),
                    "endLine" to intProperty("Last line, inclusive.", minimum = 1)
                ),
                required = listOf("path")
            )
        ) { args ->
            val path = text(args, "path") ?: error("path is required")
            val file = fileFor(path) ?: error("not found: $path")
            if (file.isDirectory) error("not a file: $path")
            if (file.fileType.isBinary) error("binary file: $path")
            val document = read { FileDocumentManager.getInstance().getDocument(file) }
            if (document == null && file.length > MAX_LOAD_BYTES) {
                error("file is ${file.length} bytes, over the $MAX_LOAD_BYTES-byte read limit")
            }
            val (text, dirty) = read {
                val content = document?.text ?: VfsUtilCore.loadText(file)
                content to (document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document))
            }
            val lines = text.split('\n')
            val start = (int(args, "startLine", 1)).coerceIn(1, lines.size.coerceAtLeast(1))
            val requestedEnd = (int(args, "endLine", lines.size)).coerceIn(start, lines.size.coerceAtLeast(1))
            val out = ArrayList<String>()
            var bytes = 0
            var end = start - 1
            for (index in start..requestedEnd) {
                if (out.size >= MAX_LINES) break
                val line = lines[index - 1]
                val size = line.toByteArray(Charsets.UTF_8).size + 1
                if (bytes + size > MAX_BYTES) break
                bytes += size
                out += line
                end = index
            }
            linkedMapOf(
                "path" to file.path,
                "fileType" to file.fileType.name,
                "dirty" to dirty,
                "lineCount" to lines.size,
                "startLine" to start,
                "endLine" to end,
                "text" to out.joinToString("\n"),
                "truncated" to (end < requestedEnd)
            )
        },

        tool(
            name = "${ns}_symbols",
            description = "The document outline from the IDE's PSI: named elements with kind and line, " +
                "at most 3 levels deep and 200 nodes.",
            schema = schema(
                linkedMapOf(
                    "path" to stringProperty("Absolute file path. Defaults to the active editor."),
                    "depth" to intProperty("Child levels to include, default 2.", minimum = 0, maximum = 3)
                )
            )
        ) { args ->
            val file = fileFor(text(args, "path") ?: activeFile(project)?.path ?: error("no file: pass path"))
                ?: error("not found")
            val depth = int(args, "depth", 2).coerceIn(0, 3)
            val symbols = read {
                val psi = PsiManager.getInstance(project).findFile(file)
                    ?: error("no PSI for ${file.path}")
                val document = FileDocumentManager.getInstance().getDocument(file)
                collectSymbols(psi, depth, document)
            }
            log("mcp: symbols ${file.path} -> ${symbols.size}")
            linkedMapOf(
                "path" to file.path,
                "symbols" to symbols,
                "truncated" to (symbols.size >= MAX_SYMBOLS)
            )
        },

        tool(
            name = "${ns}_list_files",
            description = "List project files by glob (for example **/*.kt). Use it to learn the tree " +
                "before reading specific files.",
            schema = schema(
                linkedMapOf(
                    "include" to stringProperty("Glob to include. Default **/*."),
                    "exclude" to stringProperty("Glob to exclude."),
                    "maxResults" to intProperty("Default 200.", minimum = 1, maximum = MAX_FILES)
                )
            )
        ) { args ->
            val include = text(args, "include") ?: "**/*"
            val exclude = text(args, "exclude")
            val limit = int(args, "maxResults", 200).coerceIn(1, MAX_FILES)
            val includeMatcher = Glob.matcher(include)
            val excludeMatcher = exclude?.let { Glob.matcher(it) }
            val found = ArrayList<String>(limit)
            read {
                val index = ProjectFileIndex.getInstance(project)
                index.iterateContent(object : ContentIterator {
                    override fun processFile(fileOrDir: VirtualFile): Boolean {
                        if (found.size >= limit) return false
                        if (fileOrDir.isDirectory) return true
                        val root = index.getContentRootForFile(fileOrDir) ?: return true
                        val relative = VfsUtilCore.getRelativePath(fileOrDir, root, '/') ?: return true
                        if (!includeMatcher(relative)) return true
                        if (excludeMatcher != null && excludeMatcher(relative)) return true
                        found += fileOrDir.path
                        return true
                    }
                })
            }
            log("mcp: list $include -> ${found.size}")
            linkedMapOf(
                "include" to include,
                "folders" to contentRoots(project),
                "files" to found,
                "truncated" to (found.size >= limit)
            )
        },

        tool(
            name = "${ns}_search",
            description = "Search the project text and return matching lines: path, line, column and a short " +
                "preview. Pass include/exclude globs to narrow it.",
            schema = schema(
                linkedMapOf(
                    "query" to stringProperty("Text or regular expression to look for."),
                    "regex" to boolProperty("Treat query as a regular expression. Default false."),
                    "caseSensitive" to boolProperty("Match case. Default false."),
                    "include" to stringProperty("Glob to include, for example src/**/*.kt."),
                    "exclude" to stringProperty("Glob to exclude, for example **/build/**."),
                    "maxResults" to intProperty("Default 100.", minimum = 1, maximum = MAX_SEARCH_RESULTS)
                ),
                required = listOf("query")
            )
        ) { args ->
            val query = text(args, "query") ?: error("query is required")
            val limit = int(args, "maxResults", 100).coerceIn(1, MAX_SEARCH_RESULTS)
            val include = text(args, "include") ?: "**/*"
            val exclude = text(args, "exclude")
            val includeMatcher = Glob.matcher(include)
            val excludeMatcher = exclude?.let { Glob.matcher(it) }
            val options = if (bool(args, "caseSensitive", false)) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val expression = try {
                Regex(if (bool(args, "regex", false)) query else Regex.escape(query), options)
            } catch (error: Exception) {
                error("invalid regular expression: ${error.message}")
            }
            val matches = ArrayList<Map<String, Any?>>(limit)
            var scanned = 0
            var bytes = 0L
            var exhausted = false
            val deadline = System.nanoTime() + SEARCH_BUDGET_MS * 1_000_000
            read {
                val index = ProjectFileIndex.getInstance(project)
                index.iterateContent(object : ContentIterator {
                    override fun processFile(fileOrDir: VirtualFile): Boolean {
                        if (matches.size >= limit || scanned >= MAX_SEARCH_FILES) return false
                        if (bytes >= MAX_SEARCH_TOTAL_BYTES || System.nanoTime() > deadline) {
                            exhausted = true
                            return false
                        }
                        if (fileOrDir.isDirectory) return true
                        if (fileOrDir.length > MAX_SEARCH_BYTES) return true
                        if (fileOrDir.fileType.isBinary) return true
                        bytes += fileOrDir.length
                        val root = index.getContentRootForFile(fileOrDir) ?: return true
                        val relative = VfsUtilCore.getRelativePath(fileOrDir, root, '/') ?: return true
                        if (!includeMatcher(relative)) return true
                        if (excludeMatcher != null && excludeMatcher(relative)) return true
                        val content = try {
                            VfsUtilCore.loadText(fileOrDir)
                        } catch (error: Exception) {
                            return true
                        }
                        if (content.indexOf('\u0000') >= 0) return true
                        scanned += 1
                        content.split('\n').forEachIndexed { index0, line ->
                            if (matches.size >= limit) return@forEachIndexed
                            val hit = expression.find(line) ?: return@forEachIndexed
                            matches += linkedMapOf(
                                "path" to fileOrDir.path,
                                "line" to index0 + 1,
                                "column" to hit.range.first + 1,
                                "preview" to preview(line)
                            )
                        }
                        return true
                    }
                })
            }
            log("mcp: search \"$query\" -> ${matches.size} in $scanned file(s)")
            linkedMapOf(
                "query" to query,
                "matches" to matches,
                "scanned" to scanned,
                "truncated" to (matches.size >= limit || scanned >= MAX_SEARCH_FILES || exhausted),
                "stopped" to if (exhausted) "budget" else if (scanned >= MAX_SEARCH_FILES) "file limit" else null
            )
        },

        tool(
            name = "${ns}_diagnostics",
            description = "Errors and warnings the IDE's analysis attached to files. Defaults to the active " +
                "file; pass path or scope \"workspace\" for more. Returns counts and at most 200 items.",
            schema = schema(
                linkedMapOf(
                    "path" to stringProperty("Absolute file path. Defaults to the active editor."),
                    "scope" to enumProperty("Read one file or every open document. Defaults to file.", listOf("file", "workspace")),
                    "limit" to intProperty("Maximum items, default 50.", minimum = 1, maximum = MAX_DIAGNOSTICS)
                )
            )
        ) { args ->
            if (DumbService.isDumb(project)) {
                return@tool linkedMapOf(
                    "scope" to "file",
                    "reason" to "indexing",
                    "retry" to true,
                    "counts" to linkedMapOf("error" to 0, "warning" to 0, "info" to 0),
                    "items" to emptyList<Any?>()
                )
            }
            val scope = if (text(args, "scope") == "workspace") "workspace" else "file"
            val limit = int(args, "limit", 50).coerceIn(1, MAX_DIAGNOSTICS)
            val target = text(args, "path")?.let { fileFor(it) } ?: activeFile(project)
            val files: List<VirtualFile> = if (scope == "workspace") {
                edt {
                    FileEditorManager.getInstance(project).openFiles.filter { !it.isDirectory }
                }
            } else {
                listOfNotNull(target)
            }
            val counts = linkedMapOf<String, Any?>("error" to 0, "warning" to 0, "info" to 0)
            val items = ArrayList<Map<String, Any?>>()
            var total = 0
            read {
                for (file in files) {
                    val document = FileDocumentManager.getInstance().getDocument(file) ?: continue
                    val entries = try {
                        IdeAdapter.diagnostics(project, file, document)
                    } catch (error: IdeAdapter.NotAvailable) {
                        listOf()
                    }
                    total += entries.size
                    for (entry in entries) {
                        val bucket = when (entry.severity) {
                            "error" -> "error"
                            "warning", "weak_warning" -> "warning"
                            else -> "info"
                        }
                        counts[bucket] = (counts[bucket] as Int) + 1
                        if (items.size >= limit) continue
                        items += linkedMapOf(
                            "path" to entry.path,
                            "line" to entry.line,
                            "column" to entry.column,
                            "severity" to entry.severity,
                            "message" to entry.message.take(MAX_MESSAGE),
                            "source" to entry.source
                        )
                    }
                }
            }
            linkedMapOf(
                "scope" to scope,
                "file" to if (scope == "file") target?.path else null,
                "counts" to counts,
                "items" to items,
                "truncated" to (total > items.size)
            )
        },

        tool(
            name = "${ns}_definitions",
            description = "Where is this symbol defined? Resolves the reference at a position and returns the " +
                "locations (path, line, column). Defaults to the active editor caret.",
            schema = schema(
                linkedMapOf(
                    "path" to stringProperty("Absolute file path. Defaults to the active editor."),
                    "line" to intProperty("1-based line. Default 1.", minimum = 1),
                    "column" to intProperty("1-based column. Default 1.", minimum = 1)
                )
            )
        ) { args ->
            if (DumbService.isDumb(project)) {
                return@tool linkedMapOf(
                    "reason" to "indexing",
                    "retry" to true,
                    "locations" to emptyList<Any?>()
                )
            }
            val target = resolveTarget(project, args)
            val locations = read {
                val reference = target.psi.findReferenceAt(target.offset)
                    ?: return@read emptyList<Map<String, Any?>>()
                val elements: List<PsiElement> = if (reference is PsiPolyVariantReference) {
                    reference.multiResolve(false).mapNotNull { it.element }
                } else {
                    listOfNotNull(reference.resolve())
                }
                elements.take(MAX_LOCATIONS).map { element -> location(element) }
            }
            log("mcp: definitions ${target.psi.virtualFile.path} -> ${locations.size}")
            linkedMapOf(
                "path" to target.psi.virtualFile.path,
                "locations" to locations,
                "truncated" to (locations.size >= MAX_LOCATIONS)
            )
        },

        tool(
            name = "${ns}_references",
            description = "Every place this symbol is used. Defaults to the active editor caret.",
            schema = schema(
                linkedMapOf(
                    "path" to stringProperty("Absolute file path. Defaults to the active editor."),
                    "line" to intProperty("1-based line. Default 1.", minimum = 1),
                    "column" to intProperty("1-based column. Default 1.", minimum = 1)
                )
            )
        ) { args ->
            if (DumbService.isDumb(project)) {
                return@tool linkedMapOf(
                    "reason" to "indexing",
                    "retry" to true,
                    "locations" to emptyList<Any?>()
                )
            }
            val target = resolveTarget(project, args)
            val locations = read {
                val reference = target.psi.findReferenceAt(target.offset)
                val element = reference?.resolve() ?: target.psi.findElementAt(target.offset)
                ?: return@read emptyList<Map<String, Any?>>()
                val found = ArrayList<Map<String, Any?>>()
                // Stop the search itself at the cap: a hot symbol can have
                // thousands of usages and materialising them all is wasteful.
                ReferencesSearch
                    .search(element, GlobalSearchScope.projectScope(project), false)
                    .forEach(com.intellij.util.Processor { usage ->
                        if (found.size >= MAX_LOCATIONS) return@Processor false
                        val file = usage.element.containingFile?.virtualFile ?: return@Processor true
                        found += location(file, usage.element.textOffset)
                        true
                    })
                found
            }
            log("mcp: references ${target.psi.virtualFile.path} -> ${locations.size}")
            linkedMapOf(
                "path" to target.psi.virtualFile.path,
                "locations" to locations,
                "truncated" to (locations.size >= MAX_LOCATIONS)
            )
        },

        tool(
            name = "${ns}_hover",
            description = "The documentation the IDE shows for the symbol at a position (plain text, bounded). " +
                "Defaults to the active editor caret.",
            schema = schema(
                linkedMapOf(
                    "path" to stringProperty("Absolute file path. Defaults to the active editor."),
                    "line" to intProperty("1-based line. Default 1.", minimum = 1),
                    "column" to intProperty("1-based column. Default 1.", minimum = 1)
                )
            )
        ) { args ->
            if (DumbService.isDumb(project)) {
                return@tool linkedMapOf("reason" to "indexing", "retry" to true, "text" to "")
            }
            val target = resolveTarget(project, args)
            val text = try {
                read {
                    val element = target.psi.findElementAt(target.offset)
                        ?: return@read ""
                    IdeAdapter.hoverText(project, element)
                }
            } catch (error: IdeAdapter.NotAvailable) {
                ""
            }
            val bounded = if (text.length > MAX_HOVER_CHARS) text.take(MAX_HOVER_CHARS) + "…" else text
            log("mcp: hover ${target.psi.virtualFile.path} -> ${bounded.length} char(s)")
            linkedMapOf(
                "path" to target.psi.virtualFile.path,
                "text" to bounded,
                "truncated" to (bounded.length < text.length),
                "note" to if (bounded.isBlank()) DshDockBundleText.hoverEmpty() else null
            )
        },

        tool(
            name = "${ns}_code_actions",
            description = "Quick fixes and intentions available at a position or selection. Lists titles only; " +
                "applying one is a separate, deliberate step.",
            schema = schema(
                linkedMapOf(
                    "path" to stringProperty("Absolute file path. Defaults to the active editor."),
                    "startLine" to intProperty("1-based first line. Defaults to the caret line.", minimum = 1),
                    "startColumn" to intProperty("1-based first column. Default 1.", minimum = 1)
                )
            )
        ) { args ->
            if (DumbService.isDumb(project)) {
                return@tool linkedMapOf("reason" to "indexing", "retry" to true, "actions" to emptyList<Any?>())
            }
            val target = resolveTarget(project, args)
            val actions = edt {
                val document = FileDocumentManager.getInstance().getDocument(target.psi.virtualFile)
                    ?: return@edt emptyList<IdeAdapter.CodeAction>()
                val selected = FileEditorManager.getInstance(project).selectedTextEditor
                val reuse = selected != null && selected.document === document
                val editor: Editor = if (reuse) selected!!
                else IdeAdapter.headlessEditor(project, target.psi.virtualFile, document)
                try {
                    read { IdeAdapter.codeActions(project, editor, target.psi, target.offset).take(MAX_ACTIONS) }
                } catch (error: IdeAdapter.NotAvailable) {
                    emptyList()
                } finally {
                    if (!reuse) EditorFactory.getInstance().releaseEditor(editor)
                }
            }
            log("mcp: code actions ${target.psi.virtualFile.path} -> ${actions.size}")
            linkedMapOf(
                "path" to target.psi.virtualFile.path,
                "actions" to actions.map { action ->
                    linkedMapOf<String, Any?>(
                        "title" to action.title,
                        "family" to action.family,
                        "preferred" to action.preferred
                    )
                },
                "truncated" to (actions.size >= MAX_ACTIONS)
            )
        },

        tool(
            name = "${ns}_reveal",
            description = "Open a file in the editor and put the caret on a line (no diff). Use it when the user " +
                "should look at code rather than at a change.",
            schema = schema(
                linkedMapOf(
                    "path" to stringProperty("Absolute file path."),
                    "line" to intProperty("1-based line to reveal. Default 1.", minimum = 1)
                ),
                required = listOf("path")
            )
        ) { args ->
            val path = text(args, "path") ?: error("path is required")
            val file = fileFor(path) ?: error("not found: $path")
            val line = int(args, "line", 1).coerceAtLeast(1)
            val opened = edt { IdeAdapter.reveal(project, file, line, 1) }
            log("mcp: reveal $path:$line -> $opened")
            linkedMapOf("opened" to opened, "path" to file.path, "line" to line)
        },

        tool(
            name = "${ns}_open_diff",
            description = "Open a change in the IDE's diff editor for review. Pass original with the text the file " +
                "had before your change; without it the cached review baseline is used. Nothing is written.",
            schema = schema(
                linkedMapOf(
                    "path" to stringProperty("Absolute path of the file on disk (the \"after\" side)."),
                    "original" to stringProperty("The text the file had before the change."),
                    "title" to stringProperty("Diff tab title. Defaults to the file name.")
                ),
                required = listOf("path")
            )
        ) { args ->
            val path = text(args, "path") ?: error("path is required")
            val original = args["original"] as? String
            val title = text(args, "title")
            val result = edt { ReviewDiff.open(project, path, original, title, focus = true) }
            log("mcp: diff $path -> ${if (result.opened) result.title else "not opened: ${result.reason}"}")
            linkedMapOf(
                "opened" to result.opened,
                "path" to result.path,
                "title" to result.title,
                "before" to result.before,
                "reason" to result.reason
            )
        }
    )

    /* -------------------------------- write tools ------------------------------ */

    private fun writeTools(project: Project, log: (String) -> Unit, ns: String): List<McpTool> = listOf(
        tool(
            name = "${ns}_apply_edit",
            description = "Replace a range (or the whole file) with new text through a write command: the buffer " +
                "becomes unsaved (Ctrl+Z undoes it) and the change opens in a diff tab. Nothing is saved unless " +
                "save is true.",
            schema = schema(
                linkedMapOf(
                    "path" to stringProperty("Absolute file path."),
                    "text" to stringProperty("The replacement text."),
                    "startLine" to intProperty("1-based first line to replace. Omit to replace the whole file.", minimum = 1),
                    "startColumn" to intProperty("1-based first column. Default 1.", minimum = 1),
                    "endLine" to intProperty("1-based last line to replace (inclusive). Defaults to startLine.", minimum = 1),
                    "endColumn" to intProperty("1-based last column (exclusive). Defaults to the end of endLine.", minimum = 1),
                    "save" to boolProperty("Write the file to disk immediately. Default false.")
                ),
                required = listOf("path", "text")
            )
        ) { args ->
            val path = text(args, "path") ?: error("path is required")
            val replacement = args["text"] as? String ?: ""
            if (replacement.length > MAX_EDIT_CHARS) error("text is ${replacement.length} characters, over the limit")
            val file = fileFor(path) ?: error("not found: $path")
            val document = read { FileDocumentManager.getInstance().getDocument(file) }
                ?: error("no document for $path")
            val before = read { document.text }
            val wholeFile = args["startLine"] == null
            edt {
                WriteCommandAction.runWriteCommandAction(project) {
                    if (wholeFile) {
                        document.setText(replacement)
                    } else {
                        val startLine = int(args, "startLine", 1).coerceIn(1, document.lineCount)
                        val startColumn = int(args, "startColumn", 1).coerceAtLeast(1)
                        val endLine = int(args, "endLine", startLine).coerceIn(startLine, document.lineCount)
                        val startOffset = document.getLineStartOffset(startLine - 1) + (startColumn - 1)
                        val endOffset = if (args["endColumn"] == null) {
                            document.getLineEndOffset(endLine - 1)
                        } else {
                            val endColumn = int(args, "endColumn", 1).coerceAtLeast(1)
                            document.getLineStartOffset(endLine - 1) + (endColumn - 1)
                        }
                        document.replaceString(
                            startOffset.coerceIn(0, document.textLength),
                            endOffset.coerceIn(0, document.textLength),
                            replacement
                        )
                    }
                }
            }
            if (bool(args, "save", false)) {
                edt { FileDocumentManager.getInstance().saveDocument(document) }
            }
            val diff = edt {
                ReviewDiff.open(project, file.path, before, Paths.fileName(file.path) + " (DSH edit)", focus = false)
            }
            log("mcp: edit ${file.path} (${replacement.length} chars)")
            linkedMapOf(
                "path" to file.path,
                "applied" to true,
                "saved" to bool(args, "save", false),
                "diff" to diff.opened
            )
        },

        tool(
            name = "${ns}_save_file",
            description = "Save one file (by absolute path). Omit the path to save every unsaved document.",
            schema = schema(linkedMapOf("path" to stringProperty("Absolute file path. Omit to save every dirty document.")))
        ) { args ->
            val path = text(args, "path")
            if (path != null) {
                val file = fileFor(path) ?: error("not found: $path")
                val document = read { FileDocumentManager.getInstance().getDocument(file) }
                    ?: return@tool linkedMapOf<String, Any?>("saved" to emptyList<String>(), "failed" to listOf(path))
                val saved = edt {
                    if (FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
                        FileDocumentManager.getInstance().saveDocument(document)
                        !FileDocumentManager.getInstance().isDocumentUnsaved(document)
                    } else {
                        true
                    }
                }
                log("mcp: save $path -> $saved")
                return@tool linkedMapOf(
                    "saved" to if (saved) listOf(path) else emptyList<String>(),
                    "failed" to if (saved) emptyList<String>() else listOf(path)
                )
            }
            val before = edt {
                FileDocumentManager.getInstance().unsavedDocuments
                    .mapNotNull { FileDocumentManager.getInstance().getFile(it)?.path }
                    .toSet()
            }
            val after = edt {
                FileDocumentManager.getInstance().saveAllDocuments()
                FileDocumentManager.getInstance().unsavedDocuments
                    .mapNotNull { FileDocumentManager.getInstance().getFile(it)?.path }
                    .toSet()
            }
            val saved = before - after
            log("mcp: save all -> ${saved.size} saved, ${after.size} still unsaved")
            linkedMapOf(
                "saved" to saved.toList(),
                "failed" to after.toList()
            )
        },

        tool(
            name = "${ns}_run_task",
            description = "List the project's run configurations, or start one by name. Returns as soon as the " +
                "process starts, with a job id; poll ${ns}_job_status for its state, exit code and output. The " +
                "output also appears in the Run tool window.",
            schema = schema(linkedMapOf("label" to stringProperty("Run configuration name. Omit to list them.")))
        ) { args ->
            val label = text(args, "label")
            val configurations = edt { IdeAdapter.runConfigurations(project) }
            if (label == null) {
                log("mcp: run configurations -> ${configurations.size}")
                return@tool linkedMapOf(
                    "configurations" to configurations.take(MAX_ACTIONS).map {
                        linkedMapOf<String, Any?>("name" to it.name, "type" to it.type)
                    },
                    "truncated" to (configurations.size > MAX_ACTIONS)
                )
            }
            val settings = edt { IdeAdapter.findConfiguration(project, label) }
                ?: return@tool linkedMapOf<String, Any?>(
                    "started" to false,
                    "reason" to "no run configuration named $label",
                    "configurations" to configurations.take(MAX_ACTIONS).map { it.name }
                )
            val job = startConfiguredJob(project, settings, debug = false)
            log("mcp: run ${settings.name} -> job ${job.id}")
            RunJobs.started(job)
        },

        tool(
            name = "${ns}_terminal_run",
            description = "Run a command line in the IDE's Run tool window (named DSH Agent). Returns a job id as " +
                "soon as it starts; poll ${ns}_job_status or ${ns}_job_output for the output, or stop it with " +
                "${ns}_job_stop.",
            schema = schema(
                linkedMapOf(
                    "command" to stringProperty("The command line to run."),
                    "cwd" to stringProperty("Working directory. Defaults to the first content root.")
                ),
                required = listOf("command")
            )
        ) { args ->
            val command = text(args, "command") ?: error("command is required")
            val cwd = text(args, "cwd") ?: contentRoots(project).firstOrNull()
            val job = edt { runInRunWindow(project, command, cwd, "DSH Agent") }
            log("mcp: command \"$command\" -> job ${job.id}")
            RunJobs.started(job)
        },

        tool(
            name = "${ns}_start_debug",
            description = "List the run configurations, or start debugging one by name. Returns a job id as soon as " +
                "the debug session starts; poll ${ns}_job_status for its state.",
            schema = schema(linkedMapOf("name" to stringProperty("Run configuration name. Omit to list them.")))
        ) { args ->
            val name = text(args, "name")
            val configurations = edt { IdeAdapter.runConfigurations(project) }
            if (name == null) {
                log("mcp: debug configurations -> ${configurations.size}")
                return@tool linkedMapOf(
                    "configurations" to configurations.take(MAX_ACTIONS).map {
                        linkedMapOf<String, Any?>("name" to it.name, "type" to it.type)
                    },
                    "truncated" to (configurations.size > MAX_ACTIONS)
                )
            }
            val settings = edt { IdeAdapter.findConfiguration(project, name) }
                ?: return@tool linkedMapOf<String, Any?>(
                    "started" to false,
                    "reason" to "no run configuration named $name",
                    "configurations" to configurations.take(MAX_ACTIONS).map { it.name }
                )
            val job = startConfiguredJob(project, settings, debug = true)
            log("mcp: debug ${settings.name} -> job ${job.id}")
            RunJobs.started(job)
        },

        tool(
            name = "${ns}_job_status",
            description = "Status of a job started by ${ns}_run_task, ${ns}_terminal_run or ${ns}_start_debug: " +
                "starting/running/finished/failed, exit code, duration, the latest output and the full output file. " +
                "Pass waitMs to wait for it to finish instead of polling.",
            schema = schema(
                linkedMapOf(
                    "id" to stringProperty("Job id returned when it started."),
                    "maxChars" to intProperty("Maximum output characters to return, default 4000.", minimum = 0, maximum = 100_000),
                    "waitMs" to intProperty("Wait up to this many milliseconds for the job to finish, default 0.", minimum = 0, maximum = 60_000)
                ),
                required = listOf("id")
            )
        ) { args ->
            val id = text(args, "id") ?: error("id is required")
            val job = RunJobs.find(id) ?: error("unknown job id: $id")
            RunJobs.waitFor(job, int(args, "waitMs", 0), int(args, "maxChars", 4000).coerceIn(0, 100_000))
        },

        tool(
            name = "${ns}_job_output",
            description = "Read a slice of a job's output by character offset, for tailing a long-running process.",
            schema = schema(
                linkedMapOf(
                    "id" to stringProperty("Job id."),
                    "offset" to intProperty("Character offset to start at, default 0.", minimum = 0),
                    "maxChars" to intProperty("Maximum characters, default 8000.", minimum = 0, maximum = 100_000)
                ),
                required = listOf("id")
            )
        ) { args ->
            val id = text(args, "id") ?: error("id is required")
            val job = RunJobs.find(id) ?: error("unknown job id: $id")
            RunJobs.output(job, int(args, "offset", 0).coerceAtLeast(0), int(args, "maxChars", 8000).coerceIn(0, 100_000))
        },

        tool(
            name = "${ns}_job_stop",
            description = "Ask a running job to stop (destroy its process).",
            schema = schema(linkedMapOf("id" to stringProperty("Job id.")), required = listOf("id"))
        ) { args ->
            val id = text(args, "id") ?: error("id is required")
            val job = RunJobs.find(id) ?: error("unknown job id: $id")
            linkedMapOf<String, Any?>("id" to job.id, "stopped" to RunJobs.stop(job))
        },

        tool(
            name = "${ns}_job_list",
            description = "List the jobs this IDE session started, newest last.",
            schema = schema(emptyMap())
        ) { _ ->
            linkedMapOf(
                "jobs" to RunJobs.all().map {
                    linkedMapOf<String, Any?>(
                        "id" to it.id, "name" to it.name, "kind" to it.kind,
                        "status" to it.state, "exitCode" to it.exitCode
                    )
                }
            )
        }
    )

    /* ---------------------------------- helpers -------------------------------- */

    /** A resolved position: the file's PSI plus an offset inside it. */
    private class Target(val psi: PsiFile, val offset: Int)

    private fun resolveTarget(project: Project, args: Map<String, Any?>): Target {
        val path = text(args, "path")
        val file = if (path != null) {
            fileFor(path) ?: error("not found: $path")
        } else {
            activeFile(project) ?: error("no file: pass path")
        }
        val line = int(args, "line", 1).coerceAtLeast(1)
        val column = int(args, "column", 1).coerceAtLeast(1)
        return read {
            val psi = PsiManager.getInstance(project).findFile(file)
                ?: error("no PSI for ${file.path}")
            val document = FileDocumentManager.getInstance().getDocument(file)
                ?: error("no document for ${file.path}")
            val lineIndex = (line - 1).coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
            val offset = (document.getLineStartOffset(lineIndex) + column - 1)
                .coerceIn(0, document.textLength)
            Target(psi, offset)
        }
    }

    private fun collectSymbols(
        element: PsiElement,
        depthLimit: Int,
        document: Document?,
        depth: Int = 0,
        out: MutableList<Map<String, Any?>> = ArrayList()
    ): List<Map<String, Any?>> {
        if (out.size >= MAX_SYMBOLS || depth > depthLimit) return out
        if (element is PsiNamedElement) {
            val name = element.name
            if (!name.isNullOrBlank()) {
                out += linkedMapOf(
                    "name" to name,
                    "kind" to element.node?.elementType?.toString().orEmpty().lowercase(),
                    "line" to (document?.let { doc ->
                        if (element.textOffset in 0..doc.textLength) doc.getLineNumber(element.textOffset) + 1 else 1
                    } ?: 1)
                )
            }
        }
        if (depth == depthLimit) return out
        for (child in element.children) {
            if (out.size >= MAX_SYMBOLS) break
            collectSymbols(child, depthLimit, document, depth + 1, out)
        }
        return out
    }

    private fun location(element: PsiElement): Map<String, Any?> {
        val file = element.containingFile?.virtualFile ?: error("detached element")
        return location(file, element.textOffset)
    }

    private fun location(file: VirtualFile, offset: Int): Map<String, Any?> {
        val document = FileDocumentManager.getInstance().getDocument(file)
        val line = document?.let { doc ->
            if (offset in 0..doc.textLength) doc.getLineNumber(offset) + 1 else 1
        } ?: 1
        val column = document?.let { doc ->
            if (offset in 0..doc.textLength) offset - doc.getLineStartOffset(line - 1) + 1 else 1
        } ?: 1
        return linkedMapOf("path" to file.path, "line" to line, "column" to column)
    }

    private fun position(document: Document, offset: Int): Map<String, Any?> {
        val safe = offset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(safe) + 1
        val column = safe - document.getLineStartOffset(line - 1) + 1
        return linkedMapOf("line" to line, "column" to column)
    }

    private fun preview(line: String): String {
        val text = line.trim()
        return if (text.length > MAX_PREVIEW) text.take(MAX_PREVIEW) + "…" else text
    }

    private fun fileFor(path: String): VirtualFile? {
        val local = LocalFileSystem.getInstance()
        return local.findFileByPath(path) ?: local.refreshAndFindFileByIoFile(File(path))
    }

    private fun activeFile(project: Project): VirtualFile? = edt {
        FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
            FileDocumentManager.getInstance().getFile(editor.document)
        }
    }

    private fun contentRoots(project: Project): List<String> = ReadAction.compute<List<String>, RuntimeException> {
        ProjectRootManager.getInstance(project).contentRoots.map { root -> root.path }
    }

    private fun startConfiguredJob(
        project: Project,
        settings: com.intellij.execution.RunnerAndConfigurationSettings,
        debug: Boolean
    ): RunJobs.Job {
        val job = RunJobs.create(settings.name, if (debug) "debug" else "run")
        val connection = project.messageBus.connect()
        val listener = object : ExecutionListener {
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                if (env.runProfile?.name == job.name) {
                    RunJobs.attach(job, handler)
                    connection.disconnect()
                }
            }

            override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                if (env.runProfile?.name == job.name) {
                    RunJobs.fail(job, "the IDE did not start ${job.name}")
                    connection.disconnect()
                }
            }

            override fun processTerminated(
                executorId: String,
                env: ExecutionEnvironment,
                handler: ProcessHandler,
                exitCode: Int
            ) {
                if (env.runProfile?.name == job.name) {
                    RunJobs.attach(job, handler)
                }
            }
        }
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, listener)
        try {
            val started = edt { IdeAdapter.execute(project, settings, debug) }
            if (!started) {
                RunJobs.fail(job, "cannot start ${job.name}")
                connection.disconnect()
            }
        } catch (error: Throwable) {
            RunJobs.fail(job, error.message ?: "cannot start ${job.name}")
            connection.disconnect()
        }
        return job
    }

    private fun runInRunWindow(project: Project, command: String, cwd: String?, title: String): RunJobs.Job {
        val job = RunJobs.create("$title: $command", "terminal")
        try {
            val line = if (cwd.isNullOrBlank()) {
                command
            } else if (isWindows()) {
                "cd /d \"$cwd\" && $command"
            } else {
                "cd \"$cwd\" && $command"
            }
            val commandLine = GeneralCommandLine(*shellCommand(line).toTypedArray())
            val handler = OSProcessHandler(commandLine)
            RunJobs.attach(job, handler)
            val executor = RunContentExecutor(project, handler).withTitle(title).withActivateToolWindow(true)
            Disposer.register(project, executor)
            executor.run()
            return job
        } catch (error: Throwable) {
            RunJobs.fail(job, "cannot run the command: ${error.message}")
            throw IllegalStateException("cannot run the command: ${error.message}", error)
        }
    }

    private fun shellCommand(line: String): List<String> =
        if (isWindows()) {
            listOf("cmd.exe", "/c", line)
        } else {
            listOf("/bin/sh", "-lc", line)
        }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun <T> read(block: () -> T): T = ReadAction.compute<T, RuntimeException> { block() }

    private fun <T> edt(block: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return block()
        var result: Result<T>? = null
        application.invokeAndWait({ result = runCatching(block) }, ModalityState.any())
        return result!!.getOrThrow()
    }

    /* --------------------------------- schemas -------------------------------- */

    private fun tool(
        name: String,
        description: String,
        schema: Map<String, Any?>,
        block: (Map<String, Any?>) -> Any?
    ): McpTool = object : McpTool {
        override val name: String = name
        override val description: String = description
        override val inputSchema: Map<String, Any?> = schema
        override fun call(arguments: Map<String, Any?>): Any? = block(arguments)
    }

    private fun noArguments(): Map<String, Any?> =
        linkedMapOf("type" to "object", "properties" to linkedMapOf<String, Any?>(), "additionalProperties" to false)

    private fun schema(properties: Map<String, Any?>, required: List<String> = emptyList()): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>("type" to "object", "properties" to properties)
        if (required.isNotEmpty()) out["required"] = required
        out["additionalProperties"] = false
        return out
    }

    private fun stringProperty(description: String): Map<String, Any?> =
        linkedMapOf("type" to "string", "description" to description)

    private fun intProperty(description: String, minimum: Int? = null, maximum: Int? = null): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>("type" to "integer", "description" to description)
        if (minimum != null) out["minimum"] = minimum
        if (maximum != null) out["maximum"] = maximum
        return out
    }

    private fun boolProperty(description: String): Map<String, Any?> =
        linkedMapOf("type" to "boolean", "description" to description)

    private fun enumProperty(description: String, values: List<String>): Map<String, Any?> =
        linkedMapOf("type" to "string", "enum" to values, "description" to description)

    private fun text(args: Map<String, Any?>, key: String): String? =
        (args[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun int(args: Map<String, Any?>, key: String, fallback: Int): Int {
        val value = args[key] ?: return fallback
        return when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: fallback
            else -> fallback
        }
    }

    private fun bool(args: Map<String, Any?>, key: String, fallback: Boolean): Boolean = when (val value = args[key]) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        is Number -> value.toInt() != 0
        else -> fallback
    }

    /** The odd hover note that belongs to the tool, kept out of the adapter. */
    private object DshDockBundleText {
        fun hoverEmpty(): String =
            "the IDE has no documentation for this element; this is not an error"
    }
}
