package cc.futurvo.ideadock.dock

import cc.futurvo.ideadock.ui.titleActions
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * The tool window's factory.
 *
 * `anchor="right"` in `plugin.xml` is the closest thing to VS Code's secondary
 * side bar: it does not take the editor area, can be dragged to either side, and
 * can float. A `DialogWrapper` would lose docking, shortcuts, focus management
 * and the platform's `Ctrl+Shift+F12` behaviour, so a real tool window it is.
 */
class DockToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DockPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        // Closing the tool window must dispose the JCEF browser, not leak it.
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        // The three buttons users reach for most; the dock's own title bar also
        // carries the tools switch once QuickDock reports a bridge.
        toolWindow.setTitleActions(titleActions(project))
    }
}
