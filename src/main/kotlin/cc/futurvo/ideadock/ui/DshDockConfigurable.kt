package cc.futurvo.ideadock.ui

import cc.futurvo.ideadock.contract.Pairing
import cc.futurvo.ideadock.contract.QuickDockPorts
import cc.futurvo.ideadock.dock.DockView
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The settings page.
 *
 * Ten settings, all project level (the VS Code extension's settings are
 * `scope: "window"`), and everything applies immediately: the dock service
 * re-resolves the port, restarts or re-pairs the MCP server when a switch
 * changes the tool set.
 *
 * `port = 0` means "follow QuickDock's own configuration"; a real value pins this
 * project to it. 0 is never sent to QuickDock - it is only this client's sentinel,
 * because a listener that records port 0 is deliberately switched off.
 */
class DshDockConfigurable(private val project: Project) : SearchableConfigurable {

    private var panel: JPanel? = null
    private val portField = JBTextField()
    private val followBox = JBCheckBox(DshDockBundle.message("settings.follow"))
    private val statusBarBox = JBCheckBox(DshDockBundle.message("settings.statusBar"))
    private val mcpBox = JBCheckBox(DshDockBundle.message("settings.mcp"))
    private val mcpPortField = JBTextField()
    private val writeToolsBox = JBCheckBox(DshDockBundle.message("settings.writeTools"))
    private val reviewBox = JBCheckBox(DshDockBundle.message("settings.review"))
    private val commandField = JBTextField()
    private val profileField = JBTextField()
    private val argsField = JBTextField()

    override fun getId(): String = "dsh.ideadock"

    override fun getDisplayName(): String = DshDockBundle.message("settings.displayName")

    override fun createComponent(): JComponent {
        panel?.let { return it }
        val state = DshDockSettings.getInstance(project).state
        val detected = QuickDockPorts.detect()
        val detectedNote = if (detected != null) {
            DshDockBundle.message("settings.port.detected", detected.port, detected.file.absolutePath)
        } else {
            DshDockBundle.message("settings.port.none")
        }
        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent(DshDockBundle.message("settings.port"), portField)
            .addComponentToRightColumn(
                com.intellij.ui.components.JBLabel(
                    DshDockBundle.message("settings.port.hint", QuickDockPorts.DEFAULT_PORT, detectedNote)
                ).apply { foreground = com.intellij.util.ui.UIUtil.getLabelDisabledForeground() }
            )
            .addComponent(followBox)
            .addComponent(statusBarBox)
            .addComponent(mcpBox)
            .addLabeledComponent(DshDockBundle.message("settings.mcpPort"), mcpPortField)
            .addComponent(writeToolsBox)
            .addComponent(reviewBox)
            .addLabeledComponent(DshDockBundle.message("settings.dshCommand"), commandField)
            .addLabeledComponent(DshDockBundle.message("settings.dshProfile"), profileField)
            .addLabeledComponent(DshDockBundle.message("settings.dshArgs"), argsField)
            .addComponentFillVertically(JPanel(), JBUI.scale(8))
        val created = builder.panel
        created.border = JBUI.Borders.empty(8)
        panel = created
        reset()
        return created
    }

    override fun isModified(): Boolean {
        val state = DshDockSettings.getInstance(project).state
        return parsePort(portField.text, allowZero = true) != state.port ||
            followBox.isSelected != state.followQuickDockConfig ||
            statusBarBox.isSelected != state.statusBar ||
            mcpBox.isSelected != state.mcp ||
            parsePort(mcpPortField.text, allowZero = false) != state.mcpPort ||
            writeToolsBox.isSelected != state.mcpWriteTools ||
            reviewBox.isSelected != state.reviewEdits ||
            commandField.text.trim() != state.dshCommand ||
            profileField.text.trim() != state.dshProfile ||
            argsField.text.trim() != state.dshArgs
    }

    override fun apply() {
        val port = parsePort(portField.text, allowZero = true)
        if (port == null) {
            reportInvalid(DshDockBundle.message("settings.port.invalid"))
            return
        }
        val mcpPort = parsePort(mcpPortField.text, allowZero = false) ?: Pairing.DEFAULT_MCP_PORT
        if (mcpPort !in 1..65535) {
            reportInvalid(DshDockBundle.message("settings.mcpPort.invalid"))
            return
        }
        val state = DshDockSettings.getInstance(project).state
        state.port = port
        state.followQuickDockConfig = followBox.isSelected
        state.statusBar = statusBarBox.isSelected
        state.mcp = mcpBox.isSelected
        state.mcpPort = mcpPort
        state.mcpWriteTools = writeToolsBox.isSelected
        state.reviewEdits = reviewBox.isSelected
        state.dshCommand = commandField.text.trim().ifBlank { "dsh" }
        state.dshProfile = profileField.text.trim()
        state.dshArgs = argsField.text.trim()
        project.getService(cc.futurvo.ideadock.lifecycle.DockService::class.java)?.onSettingsApplied()
    }

    override fun reset() {
        val state = DshDockSettings.getInstance(project).state
        portField.text = state.port.toString()
        followBox.isSelected = state.followQuickDockConfig
        statusBarBox.isSelected = state.statusBar
        mcpBox.isSelected = state.mcp
        mcpPortField.text = state.mcpPort.toString()
        writeToolsBox.isSelected = state.mcpWriteTools
        reviewBox.isSelected = state.reviewEdits
        commandField.text = state.dshCommand
        profileField.text = state.dshProfile
        argsField.text = state.dshArgs
    }

    override fun disposeUIResources() {
        panel = null
    }

    /** `0` is the "follow QuickDock" sentinel; anything else must be a real port. */
    private fun parsePort(raw: String?, allowZero: Boolean): Int? {
        val value = raw?.trim()?.toIntOrNull() ?: return if (allowZero) 0 else null
        if (allowZero && value == 0) return 0
        if (value !in 1..65535) return null
        return value
    }

    private fun reportInvalid(message: String) {
        Messages.showErrorDialog(project, message, DshDockBundle.message("settings.invalid.title"))
    }

    companion object {
        /** Kept so the action can open this page by class. */
        fun pageClass(): Class<out Configurable> = DshDockConfigurable::class.java

        /** Where the dock stands right now, for the settings page's status line. */
        fun describe(project: Project): String {
            val service = project.getService(cc.futurvo.ideadock.lifecycle.DockService::class.java)
            val status = service?.status()
            return when {
                status == null -> DshDockBundle.message("settings.status.unknown")
                status.reachable -> DshDockBundle.message("settings.status.reachable", status.port)
                else -> DshDockBundle.message("settings.status.unreachable", status.port)
            }
        }

        /** Only used to keep [DockView] linked from this file's docs. */
        internal fun viewModes(): List<DockView.Mode> = DockView.Mode.entries
    }
}
