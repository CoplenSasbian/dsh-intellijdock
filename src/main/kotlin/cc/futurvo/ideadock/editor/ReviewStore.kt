package cc.futurvo.ideadock.editor

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The queue behind the single "DSH Review" tab.
 *
 * Automatic reviews do not open an editor tab each: they append here, and the
 * review tool window shows the list with the selected change's diff in place.
 * The newest change is selected, so one tab holds the whole history.
 */
@Service(Service.Level.PROJECT)
class ReviewStore(private val project: Project) {

    /** One reported change, with both sides already materialised. */
    data class Entry(
        val id: Long,
        val path: String,
        val title: String,
        val before: String,
        val after: String,
        val at: Long = System.currentTimeMillis()
    )

    private val nextId = AtomicLong()
    private val entries = ArrayList<Entry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Append one change and wake the review tab. */
    fun add(path: String, title: String, before: String, after: String): Entry {
        val entry = Entry(nextId.incrementAndGet(), path, title, before, after)
        synchronized(entries) {
            entries += entry
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        }
        for (listener in listeners) {
            try {
                listener()
            } catch (error: Throwable) {
                // A disposed panel must not break the watcher.
            }
        }
        // Surface the one review tab (a show, not a focus steal) so the change
        // is not missed when the tab is closed.
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("DSH Review")?.show()
        }, com.intellij.openapi.application.ModalityState.any())
        return entry
    }

    fun all(): List<Entry> = synchronized(entries) { entries.toList() }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    companion object {
        private const val MAX_ENTRIES = 100

        fun getInstance(project: Project): ReviewStore = project.service()
    }
}
