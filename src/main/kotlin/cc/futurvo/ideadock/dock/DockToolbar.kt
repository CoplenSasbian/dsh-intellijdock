package cc.futurvo.ideadock.dock

import cc.futurvo.ideadock.ui.DshDockBundle
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ToolTipManager

/**
 * The dock's own title bar, used when the app is the browser's document.
 *
 * That mode exists because a JCEF browser cannot commit input-method text into a
 * subframe: with QuickDock's shell page framing the app, typing Chinese shows the
 * candidate window and inserts nothing. Loading the app itself (see
 * `DockView.topLevel`) puts the composer in the main frame, where IME works - and
 * the shell page's title bar goes away with it.
 *
 * The layout and the icons are the shell page's (the VS Code dock's), so the same
 * dock in two editors looks the same:
 *
 * ```
 * [sidebar] [new session]  session title …            [warning] [handoff]
 * ```
 *
 * `new session` is hidden while the sidebar is expanded: the sidebar carries its
 * own "New Session" button there, and the shell does the same (its CSS hides the
 * bar button when the sidebar is open). The editor-tools switch is not here: it is
 * a tool window header action, next to Reload and Open-in-browser.
 */
class DockToolbar(
    private val onSidebar: () -> Unit,
    private val onNewSession: () -> Unit,
    private val onShowTools: (java.awt.Component) -> Unit,
    private val onHandoff: () -> Unit
) : JPanel(BorderLayout()) {

    private val title = JLabel(DshDockBundle.message("dock.title")).apply {
        font = font.deriveFont(font.size2D - 0.5f)
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.emptyLeft(4)
    }

    /** Shown when `dock-target.workspace.matches == false`. */
    private val warning = JLabel(DockIcons.warning).apply {
        isVisible = false
        border = JBUI.Borders.emptyRight(4)
        toolTipText = DshDockBundle.message("dock.tools.workspaceWarn")
    }

    private val newSessionButton = iconButton(DockIcons.newSession, "dock.tools.newSession") { onNewSession() }
    private val toolsInfoButton = iconButton(DockIcons.toolsList, "dock.info.tooltip") { onShowTools(it) }
    private val handoffButton = iconButton(DockIcons.handoff, "dock.tools.handoff") { onHandoff() }
        .apply { isEnabled = false }

    init {
        background = UIUtil.getPanelBackground()
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
        preferredSize = Dimension(0, JBUI.scale(28))
        // JLabel tooltips need an explicit register when the label starts hidden.
        ToolTipManager.sharedInstance().registerComponent(warning)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(4)
            add(iconButton(DockIcons.sidebar, "dock.tools.sidebar") { onSidebar() })
            add(newSessionButton)
            add(title)
        }, BorderLayout.WEST)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyRight(4)
            add(warning)
            add(toolsInfoButton)
            add(handoffButton)
        }, BorderLayout.EAST)
    }

    /** The session title the app reports (`quickdock:title`). */
    fun setTitle(text: String) {
        title.text = text.trim().ifBlank { DshDockBundle.message("dock.title") }
        revalidate()
    }

    /** Handoff only makes sense while the app shows a session. */
    fun setSessionOnScreen(sessionId: String) {
        val usable = sessionId.isNotBlank()
        if (handoffButton.isEnabled != usable) handoffButton.isEnabled = usable
    }

    /**
     * The sidebar is open, so the app shows its own "New Session" button: the bar
     * shortcut goes away, exactly as in the shell page.
     */
    fun setSidebarExpanded(expanded: Boolean) {
        if (newSessionButton.isVisible == !expanded) return
        newSessionButton.isVisible = !expanded
        revalidate()
        repaint()
    }

    /** `workspace.matches == false` from `dock-target`. */
    fun setWorkspaceWarning(show: Boolean) {
        if (warning.isVisible == show) return
        warning.isVisible = show
        revalidate()
        repaint()
    }

    private fun iconButton(icon: Icon, key: String, action: (JButton) -> Unit): JButton =
        JButton(icon).apply {
            toolTipText = DshDockBundle.message(key)
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isOpaque = false
            isFocusable = false
            margin = JBUI.insets(3)
            preferredSize = Dimension(JBUI.scale(26), JBUI.scale(22))
            addActionListener { action(this) }
        }
}
