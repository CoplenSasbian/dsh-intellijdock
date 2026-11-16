package cc.futurvo.ideadock.editor

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.ShowIntentionsPass
import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * The single place where this plugin touches APIs that are internal or whose
 * signature moves between platform versions: diagnostics highlighters, intention
 * actions, hover documentation, run/debug launching.
 *
 * Everything here is called from a background thread inside a read action (or,
 * for the UI-touching parts, marshalled to the EDT by the caller). When a
 * platform upgrade breaks one of these, it breaks here and nowhere else, and the
 * tool that owns it answers "not available on this IDE build" instead of failing
 * the whole server.
 *
 * Every signature in this file was checked against the 2025.3 distribution with
 * `javap`; the notes say what changed, because several of these moved recently.
 */
object IdeAdapter {

    /** One diagnostic, flattened for the model. */
    data class Diagnostic(
        val path: String,
        val line: Int,
        val column: Int,
        val severity: String,
        val message: String,
        val source: String?
    )

    /** One quick fix / intention. */
    data class CodeAction(val title: String, val family: String, val preferred: Boolean)

    /** One run configuration, by name and type. */
    data class RunConfiguration(val name: String, val type: String)

    /** Why an adapter call could not answer, in words the model can act on. */
    class NotAvailable(message: String) : Exception(message)

    /* ------------------------------ diagnostics ------------------------------- */

    /**
     * The highlighters currently attached to one open document.
     *
     * This is the cheap path: it reads what the daemon already produced and never
     * starts an analysis. While indexing it returns an empty list, which the tool
     * reports as "indexing" rather than "no problems".
     */
    fun diagnostics(project: Project, file: VirtualFile, document: Document): List<Diagnostic> {
        val markup = try {
            // 2025.3: com.intellij.openapi.editor.impl.DocumentMarkupModel (the
            // old com.intellij.codeInsight.daemon one is gone).
            DocumentMarkupModel.forDocument(document, project, false)
        } catch (error: Throwable) {
            throw NotAvailable("diagnostics are not readable on this IDE build (${error.javaClass.simpleName})")
        }
        val path = file.path
        val out = ArrayList<Diagnostic>()
        for (highlighter in markup.allHighlighters) {
            val info = highlighter as? HighlightInfo ?: continue
            val severity = info.severity ?: continue
            if (severity.myName == HighlightSeverity.INFORMATION.myName) continue
            if (severity.myName == HighlightSeverity.TEXT_ATTRIBUTES.myName) continue
            val offset = info.startOffset.coerceIn(0, document.textLength)
            val line = document.getLineNumber(offset) + 1
            val column = offset - document.getLineStartOffset(line - 1) + 1
            out += Diagnostic(
                path = path,
                line = line,
                column = column,
                severity = severityName(severity),
                message = info.description ?: "",
                source = info.inspectionToolId
            )
        }
        return out
    }

    /** Whether the daemon is still analysing (results are partial while it runs). */
    fun isAnalyzing(project: Project): Boolean = try {
        // 2025.3: the per-file isRunning(PsiFile) overload is gone; this asks the
        // analyzer instance whether any pass is currently running.
        DaemonCodeAnalyzer.getInstance(project).isRunning()
    } catch (error: Throwable) {
        false
    }

    /** Map an IDE severity onto the four names the MCP schema uses. */
    fun severityName(severity: HighlightSeverity): String = when (severity.myName) {
        HighlightSeverity.ERROR.myName -> "error"
        HighlightSeverity.WARNING.myName -> "warning"
        HighlightSeverity.WEAK_WARNING.myName -> "weak_warning"
        else -> "info"
    }

    /* ------------------------------ code actions ------------------------------ */

    /**
     * The quick fixes and intentions available at a position.
     *
     * `IntentionManager.getAvailableIntentions` lost its editor/offset overload in
     * 2025.3, so this asks the highlighting pass instead - the same call the
     * intention popup makes.
     *
     * Listing only: applying one would need a write action and a modal choice,
     * which is a separate, deliberate step.
     */
    fun codeActions(project: Project, editor: Editor, file: PsiFile, offset: Int): List<CodeAction> {
        return try {
            ShowIntentionsPass.getAvailableFixes(editor, file, offset, offset)
                .map { descriptor ->
                    val action = descriptor.action
                    CodeAction(
                        title = action.text,
                        family = "",
                        preferred = false
                    )
                }
        } catch (error: Throwable) {
            throw NotAvailable("intention actions are not listable on this IDE build (${error.javaClass.simpleName})")
        }
    }

    /* --------------------------------- hover ---------------------------------- */

    /**
     * The documentation the IDE would show for an element.
     *
     * `DocumentationManager.getDocumentation` is gone in 2025.3; `generateDocumentation`
     * is what the quick-doc popup calls and it returns the HTML (or plain text) the
     * provider produced. Documentation is produced lazily and may need indices, so
     * the caller runs this inside a read action; "no documentation" is a normal
     * result and the tool says so instead of returning an empty string.
     */
    fun hoverText(project: Project, element: PsiElement): String {
        return try {
            val html = DocumentationManager.getInstance(project).generateDocumentation(element, null, false)
            if (html.isNullOrBlank()) "" else stripHtml(html)
        } catch (error: Throwable) {
            throw NotAvailable("hover documentation is not available on this IDE build (${error.javaClass.simpleName})")
        }
    }

    /** Turn the IDE's HTML documentation into plain text the model can read. */
    fun stripHtml(html: String): String {
        if (html.isBlank()) return ""
        return html
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n\n")
            .replace(Regex("(?is)<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /* ------------------------------- run / debug ------------------------------ */

    /** Every run configuration this project has, as name + type. */
    fun runConfigurations(project: Project): List<RunConfiguration> =
        RunManager.getInstance(project).allSettings.map { settings ->
            RunConfiguration(
                name = settings.name,
                type = settings.type.displayName
            )
        }

    /** Find one configuration by name, case-insensitively when the exact name misses. */
    fun findConfiguration(project: Project, name: String): RunnerAndConfigurationSettings? {
        val all = RunManager.getInstance(project).allSettings
        return all.firstOrNull { it.name == name }
            ?: all.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Run (or debug) one configuration.
     *
     * The executor is resolved up front: a missing debugger is reported as
     * "debugging is not available", not as a crash half-way through the launch.
     */
    fun execute(project: Project, settings: RunnerAndConfigurationSettings, debug: Boolean): Boolean {
        val executorId = if (debug) DefaultDebugExecutor.EXECUTOR_ID else DefaultRunExecutor.EXECUTOR_ID
        val executor = ExecutorRegistry.getInstance().getExecutorById(executorId)
            ?: throw NotAvailable(
                if (debug) "debugging is not available in this IDE" else "running configurations is not available"
            )
        ProgramRunnerUtil.executeConfiguration(project, settings, executor)
        return true
    }

    /* -------------------------------- editors --------------------------------- */

    /**
     * An editor for a file that is not necessarily open.
     *
     * Several IDE actions only accept an `Editor`, so a headless, read-only one is
     * created and disposed by the caller. This is the documented alternative to
     * opening a tab and stealing the user's focus.
     */
    fun headlessEditor(project: Project, file: VirtualFile, document: Document): Editor =
        EditorFactory.getInstance().createEditor(document, project, file.fileType, true)

    /** Show a file at a position, taking focus - the IDE's "go to" semantic. */
    fun reveal(project: Project, file: VirtualFile, line: Int, column: Int, focus: Boolean = true): Boolean {
        val descriptor = OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), (column - 1).coerceAtLeast(0))
        val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, focus) ?: return false
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        return true
    }

    /** Commit pending PSI/document changes so a read sees what the user sees. */
    fun commit(project: Project) {
        try {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        } catch (error: Throwable) {
            // Nothing committed is still a valid state; reads fall back to the document.
        }
    }
}
