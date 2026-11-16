package cc.futurvo.ideadock.editor

import cc.futurvo.ideadock.contract.Paths
import cc.futurvo.ideadock.ui.DshDockBundle
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The review step: show a change with the text from before it on the left and the
 * file as it is now on the right.
 *
 * The left pane is a plain in-memory diff content, not a git revision: `git4idea`
 * is an optional dependency (PyCharm and WebStorm may not have it at all), so the
 * "before" text comes from [Baselines], which the review watcher fills as files
 * are read and saved.
 */
object ReviewDiff {

    /** A whole-file diff bigger than this is refused instead of freezing the UI. */
    private const val MAX_ORIGINAL_BYTES = 2 * 1024 * 1024

    data class Result(
        val opened: Boolean,
        val path: String,
        val title: String,
        /** Which side the left pane shows. */
        val before: String? = null,
        /** Why nothing was opened, in words. */
        val reason: String? = null
    )

    /**
     * Open one review diff.
     *
     * @param original the text the file had before the change; when null the
     *   watcher's cached baseline is used, and a file without one is refused
     *   with an explanation instead of an empty diff.
     */
    fun open(
        project: Project,
        path: String,
        original: String? = null,
        title: String? = null,
        focus: Boolean = false
    ): Result {
        val name = title?.takeIf { it.isNotBlank() } ?: Paths.fileName(path)
        val file = LocalFileSystem.getInstance().findFileByPath(path)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(path))
            ?: return Result(false, path, name, reason = DshDockBundle.message("diff.noFile"))
        val text = original ?: Baselines.get(path)
        ?: return Result(false, path, name, reason = DshDockBundle.message("diff.noBaseline"))
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_ORIGINAL_BYTES) {
            return Result(false, path, name, reason = DshDockBundle.message("diff.tooBig", bytes))
        }
        val factory = DiffContentFactory.getInstance()
        val left = factory.create(project, text)
        val right = contentOf(project, file)
        val request = SimpleDiffRequest(
            name,
            left,
            right,
            DshDockBundle.message("diff.before"),
            DshDockBundle.message("diff.after")
        )
        DiffManager.getInstance().showDiff(project, request)
        return Result(true, path, name, before = "baseline")
    }

    /** The right-hand side: the live document when the file is open, else its text. */
    private fun contentOf(project: Project, file: VirtualFile) = run {
        val factory = DiffContentFactory.getInstance()
        val document: Document? = FileDocumentManager.getInstance().getDocument(file)
        if (document != null) {
            factory.create(project, document)
        } else {
            factory.create(project, VfsUtilCore.loadText(file))
        }
    }

    /** Remember the text a file had before a change, for the next diff. */
    fun remember(path: String, text: String) {
        Baselines.put(path, text)
    }
}

/**
 * The "before" text of files DSH may have changed.
 *
 * One map per IDE process, keyed by absolute path. It is capped, because the
 * watcher would otherwise be an unbounded copy of the workspace in memory.
 */
object Baselines {

    private const val MAX_ENTRIES = 4_000
    private val texts = ConcurrentHashMap<String, String>()

    fun put(path: String, text: String) {
        if (texts.size >= MAX_ENTRIES && !texts.containsKey(path)) {
            // Drop one arbitrary entry; the watcher re-reads on the next change.
            texts.keys.firstOrNull()?.let(texts::remove)
        }
        texts[path] = text
    }

    fun get(path: String): String? = texts[path]

    fun forget(path: String) {
        texts.remove(path)
    }

    fun clear() = texts.clear()
}
