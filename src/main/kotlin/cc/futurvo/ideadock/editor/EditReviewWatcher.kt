package cc.futurvo.ideadock.editor

import cc.futurvo.ideadock.contract.Paths
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Automatic review: follow the writes DSH says its own tools made, and open the
 * before/after diff for each.
 *
 * This deliberately does **not** watch the filesystem. A VFS listener sees every
 * build, formatter and git checkout too, and a Gradle build touching a thousand
 * files would open a thousand diffs. QuickDock's `file-events` feed only carries
 * mutations DSH's own filesystem service observed (write, edit, patch, move,
 * delete), which is exactly the set shown as Edit/Write tool cards.
 *
 * The diff opens next to the work and focus returns to the editor that was in
 * front, matching the VS Code dock.
 */
class EditReviewWatcher(
    private val project: Project,
    private val port: Int,
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
    private val baseline = ConcurrentHashMap<String, String>()
    private val disposed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    @Volatile
    private var cursor = -1L
    @Volatile
    private var noted = ""

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    /** Begin following the feed: cache a baseline, then poll. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread({ cacheWorkspace() }, "dsh-ideadock-review-cache").apply {
            isDaemon = true
            start()
        }
        Thread({ follow() }, "dsh-ideadock-review-feed").apply {
            isDaemon = true
            start()
        }
    }

    override fun dispose() {
        disposed.set(true)
        debounce.shutdownNow()
    }

    /* ------------------------------- baseline ------------------------------- */

    private fun cacheWorkspace() {
        try {
            val roots = contentRoots()
            var files = 0
            var bytes = 0L
            for (root in roots) {
                if (disposed.get() || files >= MAX_CACHED_FILES || bytes >= MAX_CACHED_BYTES) break
                val vf = LocalFileSystem.getInstance().findFileByPath(root) ?: continue
                VfsUtilCore.visitChildrenRecursively(vf, object : VirtualFileVisitor<Unit>() {
                    override fun visitFile(file: VirtualFile): Boolean {
                        if (disposed.get() || files >= MAX_CACHED_FILES || bytes >= MAX_CACHED_BYTES) {
                            return false
                        }
                        if (isIgnored(file.path, roots)) return true
                        val text = readText(file) ?: return true
                        baseline[file.path] = text
                        files += 1
                        bytes += text.length
                        return true
                    }
                })
            }
            host.log("review: cached $files file(s), ${bytes / 1024} KB, for automatic diffs")
        } catch (error: Throwable) {
            host.log("review: cannot cache the workspace: ${error.message}")
        }
    }

    /* --------------------------------- feed --------------------------------- */

    private fun follow() {
        var backoff = RETRY_MS
        while (!disposed.get()) {
            if (!host.enabled()) {
                sleep(RETRY_MS)
                continue
            }
            if (port <= 0) {
                noteOnce("review: no QuickDock port is in effect, so DSH writes are not followed")
                sleep(backoff)
                backoff = minOf(30_000L, backoff * 2)
                continue
            }
            try {
                val since = if (cursor < 0) Long.MAX_VALUE else cursor
                val request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:$port/quickdock/api/file-events?since=$since&wait=$POLL_WAIT_MS")
                )
                    .timeout(Duration.ofMillis(POLL_WAIT_MS + 10_000))
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (disposed.get()) return
                if (response.statusCode() != 200) {
                    noteOnce("review: this QuickDock build does not report DSH writes (HTTP ${response.statusCode()})")
                    sleep(backoff)
                    backoff = minOf(30_000L, backoff * 2)
                    continue
                }
                val body = JsonParser.parseString(response.body()).asJsonObject
                val seq = body.get("seq")?.asLong ?: cursor
                if (cursor < 0) {
                    cursor = seq
                    host.log("review: following DSH's reported writes from sequence $cursor")
                    continue
                }
                body.getAsJsonArray("events")?.forEach { element ->
                    val event = element.asJsonObject
                    val path = event.get("path")?.asString ?: return@forEach
                    val kind = event.get("kind")?.asString ?: "write"
                    if (kind == "delete") {
                        resolve(path)?.let { baseline.remove(it.path) }
                    } else {
                        schedule(path)
                    }
                }
                cursor = seq
                noted = ""
                backoff = RETRY_MS
            } catch (error: Throwable) {
                if (disposed.get()) return
                val message = error.message ?: error.javaClass.simpleName
                if (message != noted) {
                    noted = message
                    host.log("review: cannot read DSH writes from QuickDock: $message")
                }
                sleep(backoff)
                backoff = minOf(30_000L, backoff * 2)
            }
        }
    }

    /** Coalesce a burst of writes on one path, then review. */
    private fun schedule(reported: String) {
        if (disposed.get() || !host.enabled()) return
        val file = resolve(reported) ?: return
        val roots = contentRoots()
        if (isIgnored(file.path, roots)) return
        pending.remove(file.path)?.cancel(false)
        val future = debounce.schedule({ review(file) }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        pending[file.path] = future
    }

    /** Compare the new text with the baseline and open the diff. */
    private fun review(file: VirtualFile) {
        if (disposed.get() || !host.enabled()) return
        val text = readText(file) ?: return
        val before = baseline[file.path]
        if (before == text) {
            // The IDE may have reloaded the new text into its buffer and saved it
            // before this review ran. Do not overwrite the baseline: remember the
            // first look as the next honest comparison point.
            baseline[file.path] = text
            host.log("review: ${file.path} matches the cached baseline, so there is no diff")
            return
        }
        if (before == null) {
            // A file DSH created after this dock opened starts empty; anything
            // older has no honest baseline, and an empty diff would mislead.
            host.log("review: no baseline for ${file.path}, opening it as a new file")
        }
        val dirty = try {
            val document = FileDocumentManager.getInstance().getDocument(file)
            document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)
        } catch (error: Throwable) {
            false
        }
        if (dirty) {
            host.log("review: ${file.path} has unsaved edits; skipping")
            return
        }
        baseline[file.path] = text
        val name = Paths.fileName(file.path)
        ApplicationManager.getApplication().invokeLater({
            try {
                val result = ReviewDiff.open(
                    project,
                    file.path,
                    original = before ?: "",
                    title = name,
                    focus = false
                )
                host.log(
                    if (result.opened) {
                        "review: opened ${file.path} (${text.length - (before?.length ?: 0)} chars)"
                    } else {
                        "review: ${file.path} not opened: ${result.reason}"
                    }
                )
            } catch (error: Throwable) {
                host.log("review: cannot open ${file.path}: ${error.message}")
            }
        }, com.intellij.openapi.application.ModalityState.any())
    }

    /* ------------------------------- helpers ------------------------------- */

    private fun resolve(reported: String): VirtualFile? {
        val file = if (Paths.isAbsolute(reported)) {
            File(reported)
        } else {
            val roots = host.pairedFolders() + contentRoots()
            roots.asSequence()
                .map { File(it, reported) }
                .firstOrNull { it.exists() }
                ?: return null
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
    }

    private fun contentRoots(): List<String> = try {
        ReadAction.compute<List<String>, RuntimeException> {
            com.intellij.openapi.roots.ProjectRootManager.getInstance(project)
                .contentRoots.mapNotNull { it.path }.filter { it.isNotBlank() }
        }
    } catch (error: Throwable) {
        emptyList()
    }

    private fun isIgnored(path: String, roots: List<String>): Boolean {
        val root = roots.firstOrNull { Paths.isInsideDirectory(path, it) } ?: return false
        val relative = path.removePrefix(root).replace('\\', '/').trimStart('/')
        return ReviewDecision.isIgnoredPath(relative)
    }

    private fun readText(file: VirtualFile): String? {
        return try {
            if (file.isDirectory || file.length > MAX_FILE_BYTES) return null
            val text = ReadAction.compute<String, RuntimeException> { VfsUtilCore.loadText(file) }
            if (text.contains('\u0000')) null else text
        } catch (error: Throwable) {
            null
        }
    }

    private fun noteOnce(line: String) {
        if (noted == line) return
        noted = line
        host.log(line)
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        /** Writes are coalesced for this long: editors and tools write in bursts. */
        const val DEBOUNCE_MS: Long = 350
        /** Files above this size are never cached or diffed. */
        const val MAX_FILE_BYTES: Long = 512 * 1024
        const val MAX_CACHED_FILES: Int = 4_000
        const val MAX_CACHED_BYTES: Long = 48L * 1024 * 1024
        private const val POLL_WAIT_MS = 15_000L
        private const val RETRY_MS = 2_000L
    }
}
