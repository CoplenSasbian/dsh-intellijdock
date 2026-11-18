package cc.futurvo.ideadock.ui

import cc.futurvo.ideadock.editor.ReviewStore
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JList
import javax.swing.JPanel

/**
 * One tab for every automatic review: a list of reported changes on the left,
 * the selected change's before/after diff on the right.
 *
 * Opening a diff per file buried the user in tabs; this keeps a single review
 * surface whose selection follows the newest change.
 */
class ReviewPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val model = CollectionListModel<ReviewStore.Entry>()
    private val list = JBList(model)
    private val processor = ReviewProcessor(project) { list.selectedValue }
    private val store = ReviewStore.getInstance(project)
    private val refresh: () -> Unit = { onEntriesChanged() }

    init {
        border = JBUI.Borders.empty()
        list.cellRenderer = object : ColoredListCellRenderer<ReviewStore.Entry>() {
            override fun customizeCellRenderer(
                list: JList<out ReviewStore.Entry>,
                value: ReviewStore.Entry?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                if (value == null) return
                append(value.title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  " + value.path, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
        list.addListSelectionListener {
            if (!it.valueIsAdjusting) processor.updateRequest(true)
        }

        val splitter = JBSplitter(false, 0.32f)
        splitter.firstComponent = JBScrollPane(list)
        splitter.secondComponent = processor.component
        add(splitter, BorderLayout.CENTER)

        store.addListener(refresh)
        rebuild(selectNewest = true)
    }

    override fun dispose() {
        store.removeListener(refresh)
        processor.dispose()
    }

    private fun onEntriesChanged() {
        ApplicationManager.getApplication().invokeLater({
            rebuild(selectNewest = true)
            // Surface the tab so a change is noticed; the editor keeps focus.
            ToolWindowManager.getInstance(project).getToolWindow("DSH Review")?.show()
        }, com.intellij.openapi.application.ModalityState.any())
    }

    private fun rebuild(selectNewest: Boolean) {
        val entries = store.all()
        val previous = list.selectedValue
        model.replaceAll(entries)
        val selected = if (selectNewest) entries.lastOrNull() else previous
        if (selected != null) list.setSelectedValue(selected, true)
        processor.updateRequest(true)
    }

    private class ReviewProcessor(
        private val owner: Project,
        private val current: () -> ReviewStore.Entry?
    ) : DiffRequestProcessor(owner) {

        override fun updateRequest(force: Boolean, scrollToPolicy: DiffUserDataKeysEx.ScrollToPolicy?) {
            val entry = current() ?: return
            val factory = DiffContentFactory.getInstance()
            val request = SimpleDiffRequest(
                entry.title,
                factory.create(owner, entry.before),
                factory.create(owner, entry.after),
                "before",
                "after"
            )
            applyRequest(request, true, scrollToPolicy)
        }
    }
}
