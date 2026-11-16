package cc.futurvo.ideadock.dock

/**
 * What the tool window should show right now.
 *
 * The service resolves this (port, probe, pairing state) on background threads
 * and hands it to [DockPanel.render] on the EDT; the panel owns no policy of its
 * own beyond "render the last state again after a theme change".
 */
data class DockView(
    val mode: Mode,
    /** The port in effect; 0 means QuickDock deliberately has its listener off. */
    val port: Int,
    /** The shell page URL, already carrying view/dock/mcp/workspace. */
    val pageUrl: String = "",
    /** Whether the editor tools are on, which the title bar's switch mirrors. */
    val mcpOn: Boolean = false,
    /** The tooltip text the switch shows. */
    val mcpDetail: String = "",
    /** Populated in [Mode.OFFLINE]. */
    val offline: DockPage.OfflineModel? = null,
    /** Populated in [Mode.NO_JCEF]. */
    val reasons: List<String> = emptyList()
) {
    enum class Mode {
        /** QuickDock answers and can serve an embedded view. */
        FRAME,

        /** The probe is still running. */
        CONNECTING,

        /** Nothing usable is listening: connect, start DSH, retry. */
        OFFLINE,

        /** JCEF is not available in this IDE at all. */
        NO_JCEF
    }
}
