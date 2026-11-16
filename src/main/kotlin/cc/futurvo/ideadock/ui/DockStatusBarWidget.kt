package cc.futurvo.ideadock.ui

import cc.futurvo.ideadock.lifecycle.DockService
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import java.awt.Component
import java.awt.event.MouseEvent
import com.intellij.util.Consumer

/**
 * The status bar entry: `DSH Dock` when QuickDock answers, `DSH Dock: not
 * connected` (with the port and the failure in the tooltip) when it does not.
 *
 * Availability follows the `statusBar` setting, so switching it off removes the
 * widget instead of hiding text.
 */
class DockStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = WIDGET_ID

    override fun getDisplayName(): String = DshDockBundle.message("status.widget.name")

    override fun isAvailable(project: Project): Boolean {
        return DshDockSettings.getInstance(project).state.statusBar
    }

    override fun createWidget(project: Project): StatusBarWidget = DockStatusWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val WIDGET_ID: String = "DshDockStatus"
    }
}

/** The widget itself; it only paints what [DockService] already decided. */
class DockStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private var current: DockService.Status? = null

    private val listener: (DockService.Status) -> Unit = { status ->
        current = status
        statusBar?.updateWidget(DockStatusBarWidgetFactory.WIDGET_ID)
    }

    private val service: DockService? =
        project.getService(DockService::class.java)?.also { it.addStatusListener(listener) }

    override fun ID(): String = DockStatusBarWidgetFactory.WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        service?.removeStatusListener(listener)
        statusBar = null
        current = null
    }

    override fun getText(): String = current?.text ?: DshDockBundle.message("status.widget.name")

    override fun getTooltipText(): String = current?.tooltip ?: ""

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer { service?.openDock() }
}
