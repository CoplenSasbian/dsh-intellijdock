package cc.futurvo.ideadock.ui

import cc.futurvo.ideadock.lifecycle.DockService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * The plugin's actions.
 *
 * The VS Code version registered thirteen commands; two pairs collapsed into one
 * switch each ("review on/off", "pair/unpair"), because an IntelliJ action can
 * carry its own state instead of needing two menu entries with `when` clauses.
 *
 * Everything delegates to [DockService]; the actions themselves hold no state, so
 * they are safe for the platform to instantiate from `plugin.xml`.
 */
private fun <T> withService(event: AnActionEvent, block: (DockService) -> T): T? {
    val project = event.project ?: return null
    return block(DockService.getInstance(project))
}

/** Tools → DSH Dock, and the tool window's icon. */
class DshDockOpenAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        withService(event) { it.openDock() }
    }
}

/** Re-probe and re-render. */
class DshDockReloadAction : AnAction(
    "Reload DSH Dock",
    "Probe QuickDock again, reload the app page and re-render the dock",
    com.intellij.icons.AllIcons.Actions.Refresh
) {
    override fun actionPerformed(event: AnActionEvent) {
        withService(event) { it.reload(force = true, reloadDocument = true) }
    }
}

/** Open the shell page in the system browser (the remote/Gateway fallback). */
class DshDockOpenInBrowserAction : AnAction(
    "Open DSH in the Browser",
    "Open QuickDock's shell page in the system browser",
    com.intellij.icons.AllIcons.General.Web
) {
    override fun actionPerformed(event: AnActionEvent) {
        withService(event) { it.openInBrowser() }
    }
}

/** Start DSH in the Run tool window. */
class DshDockStartDshAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        withService(event) { it.startDsh() }
    }
}

/** Retry the probe (the offline card's secondary button). */
class DshDockRetryAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        withService(event) { it.reload(force = true) }
    }
}

/** Adopt the port QuickDock's own configuration reports. */
class DshDockUseDetectedPortAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        withService(event) { it.useDetectedPort() }
    }
}

/** Open this plugin's settings page. */
class DshDockOpenSettingsAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        withService(event) { it.openPortSettings() }
    }
}

/** Reveal QuickDock's host.log. */
class DshDockOpenLogAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        withService(event) { it.openLog() }
    }
}

/** Diff the active file against its review baseline. */
class DshDockOpenDiffAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        withService(event) { it.openDiffForActiveFile() }
    }
}

/** Start/stop the editor MCP server; the tool window title bar shows it too. */
class DshDockToggleToolsAction : AnAction(
    DshDockBundle.message("dock.tools.tools"),
    DshDockBundle.message("action.toggleTools.description"),
    com.intellij.icons.AllIcons.Nodes.Plugin
) {

    override fun actionPerformed(event: AnActionEvent) {
        withService(event) { it.toggleToolsFromDock() }
    }

    override fun update(event: AnActionEvent) {
        val service = event.project?.let { DockService.getInstance(it) }
        val running = service?.mcpRunning() == true
        // In the tool window header only the icon shows, so it carries the state.
        event.presentation.icon = if (running) com.intellij.icons.AllIcons.General.InspectionsOK
        else com.intellij.icons.AllIcons.Nodes.Plugin
        event.presentation.text = DshDockBundle.message(
            if (running) "action.toggleTools.on" else "action.toggleTools.off"
        )
        event.presentation.description = DshDockBundle.message("action.toggleTools.description")
        event.presentation.isEnabled = event.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/** Turn the automatic review of DSH's edits on or off. */
class DshDockToggleReviewAction : AnAction(
    DshDockBundle.message("action.toggleReview.off"),
    DshDockBundle.message("action.toggleReview.description"),
    com.intellij.icons.AllIcons.Actions.Diff
) {

    override fun actionPerformed(event: AnActionEvent) {
        withService(event) { it.toggleReview() }
    }

    override fun update(event: AnActionEvent) {
        val enabled = event.project?.let { DshDockSettings.getInstance(it).state.reviewEdits } ?: true
        event.presentation.text = DshDockBundle.message(
            if (enabled) "action.toggleReview.on" else "action.toggleReview.off"
        )
        event.presentation.icon = if (enabled) com.intellij.icons.AllIcons.General.InspectionsOK
        else com.intellij.icons.AllIcons.Actions.Diff
        event.presentation.description = DshDockBundle.message("action.toggleReview.description")
        event.presentation.isEnabled = event.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
