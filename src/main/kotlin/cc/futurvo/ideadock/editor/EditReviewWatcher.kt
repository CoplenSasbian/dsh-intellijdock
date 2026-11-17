package cc.futurvo.ideadock.editor

import cc.futurvo.ideadock.contract.Paths
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Automatic review: notice a file DSH changed and offer a diff.
 *
 * The agent writes through the filesystem, never through the IDE, so the only
 * way to see it is the VFS listener. Two rules keep the notifications quiet, both
 * from the VS Code version: a save the IDE itself just made is never reviewed
 * (the listener sees those too), and only folders currently registered with DSH
 * are watched, so an unpaired IDE with a build running stays still.
 *
 * Deliberately matching the VS Code dock: a diff tab appears next to the work and
 * focus goes back to the editor that was in front, so the review never pulls the
 * caret out of what the user is typing.
 */
class EditReviewWatcher(
    private val project: Project,
    private val host: Host
) : Disposable {

    /** What the watcher needs from the service. */
    data class Host(
        val enabled: () -> Boolean,
        /** Absolute folders registered with DSH right now. */
        val pairedFolders: () -> List<String>,
        val log: (String) -> Unit
    )

    private val debounce: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            cc.futurvo.ideadock.contract.EditorMcpServer.daemonFactory("dsh-ideadock-review")
        )
    private val pending = ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<*>>()
    private val savedAt = ConcurrentHashMap<String, Long>()
    private val cachedPaths = ConcurrentHashMap.newKeySet<String>()
    private var started = false
    private var disposed = false

    /** Begin watching: cache a baseline, then follow every write. */
    fun start() {
        if (started) return
        started = true
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                for (event in events) {
                    when (event) {
                        is VFileContentChangeEvent -> schedule(event.file)
                        is VFileCreateEvent -> event.file?.let { schedule(it) }
                        else -> {}
                    }
                }
            }
        })
        connection.subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
            override fun beforeDocumentSaving(document: Document) {
                val file = FileDocumentManager.getInstance().getFile(document) ?: return
                // Remember when the IDE saved, and make the saved text the new
                // baseline so DSH's next write diffs against the user's version.
                savedAt[file.path] = System.currentTimeMillis()
                Baselines.put(file.path, document.text)
            }
        })
        debounce.execute { cacheWorkspace() }
        host.log("review: watching for changes DSH makes")
    }

    override fun dispose() {
        disposed = true
        for (future in pending.values) future.cancel(false)
        pending.clear()
        debounce.shutdownNow()
        cachedPaths.clear()
    }

    /* -------------------------------- baseline -------------------------------- */

    /** Read the workspace once, so the first agent edit has something to compare with. */
    private fun cacheWorkspace() {
        var files = 0
        var bytes = 0
        try {
            for (folder in hostRoots()) {
                if (disposed) return
                val root = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .refreshAndFindFileByIoFile(java.io.File(folder)) ?: continue
                ReadAction.run<RuntimeException> {
                    VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Any>() {
                        override fun visitFile(file: VirtualFile): Boolean {
                            if (disposed || file.isDirectory) return true
                            if (files >= MAX_CACHED_FILES || bytes >= MAX_CACHED_BYTES) return false
                            val path = file.path
                            if (ReviewDecision.isIgnoredPath(relativeTo(hostRoots(), path))) return true
                            val text = readText(file) ?: return true
                            Baselines.put(path, text)
                            cachedPaths += path
                            files += 1
                            bytes += text.length
                            return true
                        }
                    })
                }
            }
            host.log("review: cached $files file(s), ${bytes / 1024} KB, for automatic diffs")
        } catch (error: Throwable) {
            host.log("review: cannot cache the workspace: ${error.message}")
        }
    }

    /* --------------------------------- watching -------------------------------- */

    /** Coalesce a burst of writes on one path, then review. */
    private fun schedule(file: VirtualFile) {
        if (disposed || !host.enabled()) return
        if (file.isDirectory) return
        val path = file.path
        val relative = relativeTo(hostRoots(), path)
        if (ReviewDecision.isIgnoredPath(relative)) return
        pending.remove(path)?.cancel(false)
        val future = debounce.schedule({ review(file) }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        pending[path] = future
    }

    /** Compare the write with the cached text and offer the change. */
    private fun review(file: VirtualFile) {
        if (disposed || !host.enabled()) return
        val path = file.path
        if (!file.isValid) return
        val text = readText(file) ?: return
        val before = Baselines.get(path)
        val dirty = isDirty(path)
        val verdict = ReviewDecision.decide(
            ReviewDecision.Input(
                path = path,
                folders = hostRoots(),
                paired = host.pairedFolders(),
                selfSavedAt = savedAt[path],
                dirty = dirty,
                hasBefore = before != null,
                bytes = text.length,
                now = System.currentTimeMillis(),
                selfSaveGraceMs = SELF_SAVE_GRACE_MS,
                maxBytes = MAX_FILE_BYTES.toInt()
            )
        )
        if (verdict is ReviewDecision.Verdict.Skip) {
            // A write that lands without a baseline still becomes one, so the
            // *next* change can be reviewed.
            Baselines.put(path, text)
            return
        }
        if (before == null) {
            Baselines.put(path, text)
            return
        }
        if (before == text) {
            Baselines.put(path, text)
            return
        }
        Baselines.put(path, text)
        offerReview(path, before)
    }

    /** Open the change for review, the way the VS Code dock does. */
    private fun offerReview(path: String, before: String) {
        val name = Paths.fileName(path)
        ApplicationManager.getApplication().invokeLater({
            try {
                val result = ReviewDiff.open(project, path, original = before, title = name, focus = false)
                host.log(
                    if (result.opened) {
                        "review: opened $path (${textLengthDelta(before, path)} chars)"
                    } else {
                        "review: $path not opened: ${result.reason}"
                    }
                )
            } catch (error: Throwable) {
                host.log("review: cannot open $path: ${error.message}")
            }
        }, com.intellij.openapi.application.ModalityState.any())
    }

    private fun textLengthDelta(before: String, path: String): String {
        val now = Baselines.get(path)?.length ?: before.length
        val delta = now - before.length
        return (if (delta >= 0) "+" else "") + delta
    }

    /* --------------------------------- helpers -------------------------------- */

    private fun hostRoots(): List<String> = try {
        ReadAction.compute<List<String>, RuntimeException> {
            com.intellij.openapi.roots.ProjectRootManager.getInstance(project)
                .contentRoots.mapNotNull { it.path }.filter { it.isNotBlank() }
        }
    } catch (error: Throwable) {
        emptyList()
    }

    private fun relativeTo(roots: List<String>, path: String): String {
        val root = roots.firstOrNull { Paths.isInsideDirectory(path, it) } ?: return path
        val normalisedRoot = root.replace('\\', '/').trimEnd('/')
        val normalisedPath = path.replace('\\', '/')
        return if (normalisedPath.length > normalisedRoot.length) {
            normalisedPath.substring(normalisedRoot.length).trimStart('/')
        } else {
            normalisedPath
        }
    }

    private fun isDirty(path: String): Boolean {
        return try {
            val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path) ?: return false
            val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return false
            FileDocumentManager.getInstance().isDocumentUnsaved(document)
        } catch (error: Throwable) {
            false
        }
    }

    /** Read a text file, refusing what a diff could not show anyway. */
    private fun readText(file: VirtualFile): String? {
        if (file.length > MAX_FILE_BYTES) return null
        if (file.fileType.isBinary) return null
        return try {
            VfsUtilCore.loadText(file).takeIf { it.indexOf('\u0000') < 0 }
        } catch (error: Throwable) {
            null
        }
    }

    private companion object {
        /** Writes are coalesced for this long: editors and tools write in bursts. */
        const val DEBOUNCE_MS: Long = 350

        /** A save newer than this belongs to the IDE, not to DSH. */
        const val SELF_SAVE_GRACE_MS: Long = 2_000

        /** Files above this size are never cached or diffed. */
        const val MAX_FILE_BYTES: Long = 512 * 1024
        const val MAX_CACHED_FILES: Int = 4_000
        const val MAX_CACHED_BYTES: Int = 48 * 1024 * 1024
    }
}
