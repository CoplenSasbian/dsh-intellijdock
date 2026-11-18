package cc.futurvo.ideadock.dock

import cc.futurvo.ideadock.ui.ReviewPanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** The single review tab: every automatic diff lives here. */
class ReviewToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ReviewPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.isCloseable = true
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}
