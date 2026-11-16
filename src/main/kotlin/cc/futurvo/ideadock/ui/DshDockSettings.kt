package cc.futurvo.ideadock.ui

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * The plugin's per-project settings.
 *
 * Project level, on purpose: the VS Code extension's settings are `scope:
 * "window"`, and an IDE can have several projects open, each talking to its own
 * dock (and its own content roots).
 *
 * `port == 0` means "follow QuickDock's own configuration", which is how the
 * "explicitly set" case of the VS Code version is represented: the plugin never
 * writes 0 back as a port, it is only a sentinel.
 */
@State(name = "DshDockSettings", storages = [Storage("dsh-dock.xml")])
@Service(Service.Level.PROJECT)
class DshDockSettings : PersistentStateComponent<DshDockSettings.State> {

    data class State(
        /** 0 = follow QuickDock's config.json / host.log; a value pins this port. */
        var port: Int = 0,
        var followQuickDockConfig: Boolean = true,
        var statusBar: Boolean = true,
        /** Whether the editor MCP server runs. */
        var mcp: Boolean = false,
        var mcpPort: Int = 47920,
        /** Whether the tools that change state exist at all. */
        var mcpWriteTools: Boolean = false,
        var reviewEdits: Boolean = true,
        var dshCommand: String = "dsh",
        var dshProfile: String = "",
        var dshArgs: String = "",
        /** One stable id per project window: QuickDock keeps one dock context per id. */
        var dockId: String = "",
        /** Bearer token of this project's MCP server. */
        var mcpToken: String = "",
        /** Digest of the tool set DSH last mounted; a change forces one re-register. */
        var toolFingerprint: String = "",
        /** Last known good width/height, kept only so a re-open looks the same. */
        var dockSide: String = "right"
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(project: Project): DshDockSettings =
            project.getService(DshDockSettings::class.java)
                ?: error("DshDockSettings is not registered")
    }
}
