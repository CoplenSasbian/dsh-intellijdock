package cc.futurvo.ideadock.dock

import cc.futurvo.ideadock.contract.Pairing
import cc.futurvo.ideadock.ui.DshDockBundle
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import javax.swing.JComponent

/**
 * The editor-tools panel: what this dock published to DSH, and whether DSH has
 * it mounted.
 *
 * It is the Swing twin of QuickDock's shell page panel (`GET
 * /quickdock/api/mcp-servers?workspace=`): the shell page draws it for a window
 * and for the VS Code dock, this draws it for an editor whose browser cannot
 * frame the app. Same endpoint, same fields.
 *
 * Two sizing traps are handled here:
 *
 * - a popup takes the content's *preferred* width, and a row of tool names is as
 *   wide as the longest one, so the width is fixed at [WIDTH] and every long text
 *   is a wrapping `JTextArea` rather than a label;
 * - `BoxLayout` hands leftover height to components whose maximum height is
 *   unbounded, which turned one-line labels into 100px-tall rows. `GridBagLayout`
 *   with `weighty = 0` keeps every row at its preferred height.
 */
object DockToolsPanel {

    /** Content width in unscaled pixels; `JBUI.scale` turns it into DIPs. */
    private const val WIDTH = 420
    private const val MAX_HEIGHT = 420

    /** Build the panel: a folder line, then one card per published server. */
    fun build(folder: String?, servers: List<Pairing.McpServerInfo>, error: String?): JComponent {
        val column = column().apply { border = JBUI.Borders.empty(9, 11) }
        addRow(column, JBLabel(DshDockBundle.message("dock.info.title")).apply {
            font = font.deriveFont(Font.BOLD)
        })
        addRow(
            column,
            wrappedText(
                if (folder.isNullOrBlank()) DshDockBundle.message("dock.info.noFolder")
                else DshDockBundle.message("dock.info.folder", folder),
                color = UIUtil.getContextHelpForeground()
            ),
            top = 3
        )
        when {
            error != null -> addRow(column, note(DshDockBundle.message("dock.info.failed", error)), top = 8)
            servers.isEmpty() -> addRow(column, note(DshDockBundle.message("dock.info.empty")), top = 8)
            else -> for (server in servers) addRow(column, serverCard(server), top = 9)
        }
        val scroll = JBScrollPane(column).apply {
            border = null
            isOpaque = true
            background = UIUtil.getPanelBackground()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        // Height follows the content (a popup takes its preferred size), with a
        // floor so one server does not open a sliver and a ceiling that is the
        // screen, not a hard-coded number: a dock with 18 tools should be readable
        // in one look, and only a long list of servers should ever scroll.
        val contentHeight = column.preferredSize.height + JBUI.scale(4)
        val height = contentHeight.coerceIn(JBUI.scale(160), ceiling())
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = UIUtil.getPanelBackground()
            preferredSize = Dimension(JBUI.scale(WIDTH + 22), height)
            add(scroll, BorderLayout.CENTER)
        }
    }

    /* -------------------------------- layout --------------------------------- */

    /**
     * How tall this panel may grow before it scrolls.
     *
     * The panel is a popup, so it must stay inside the screen; 90% of the usable
     * area is the ceiling. The content height is usually far below it - the point
     * is that the whole tool list is visible at once instead of four lines of it.
     */
    private fun ceiling(): Int {
        val bounds = try {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        } catch (error: Throwable) {
            null
        }
        val usable = bounds?.height?.takeIf { it > 0 } ?: JBUI.scale(MAX_HEIGHT)
        return (usable * 0.9).toInt().coerceAtLeast(JBUI.scale(240))
    }

    private fun column(): JPanel = JPanel(GridBagLayout()).apply { isOpaque = false }

    /**
     * Add one row: full width, its own height.
     *
     * `weighty = 0` is the whole point - it keeps a label from being stretched to
     * fill the popup, which is what made every line of this panel float in a
     * screenful of empty space.
     */
    private fun addRow(target: JPanel, component: JComponent, top: Int = 0) {
        target.add(component, GridBagConstraints().apply {
            gridx = 0
            gridy = target.componentCount
            weightx = 1.0
            weighty = 0.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
            insets = Insets(JBUI.scale(top), 0, 0, 0)
        })
    }

    /* --------------------------------- rows ---------------------------------- */

    private fun note(text: String): JBLabel = JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground()
    }

    /**
     * A read-only text block that wraps inside [WIDTH].
     *
     * `JBLabel` never wraps and reports the whole string as its preferred width,
     * which is what stretches a popup; a `JTextArea` with `columns` has a bounded
     * preferred width and wraps the rest.
     */
    private fun wrappedText(
        text: String,
        monospace: Boolean = false,
        color: java.awt.Color? = null
    ): JTextArea = JTextArea(text).apply {
        isEditable = false
        isOpaque = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        columns = 44
        val base = font
        if (monospace) font = Font(Font.MONOSPACED, Font.PLAIN, base.size - 1)
        color?.let { foreground = it }
    }

    private fun serverCard(server: Pairing.McpServerInfo): JPanel {
        val card = column().apply {
            isOpaque = true
            background = UIUtil.getPanelBackground()
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(6, 8)
            )
        }
        val head = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(JBLabel(server.name).apply { font = font.deriveFont(Font.BOLD) })
            add(badge(
                if (server.mountedSessions > 0) {
                    DshDockBundle.message("dock.info.mounted", server.mountedSessions)
                } else {
                    DshDockBundle.message("dock.info.idle")
                },
                mounted = server.mountedSessions > 0
            ))
            if (!server.version.isNullOrBlank()) add(badge("v" + server.version, mounted = false))
        }
        addRow(card, head)
        if (!server.url.isNullOrBlank()) {
            addRow(card, wrappedText(server.url, monospace = true).apply {
                foreground = UIUtil.getContextHelpForeground()
            }, top = 3)
        }
        if (server.tools.isEmpty()) {
            addRow(card, note(DshDockBundle.message("dock.info.noTools")), top = 6)
        } else {
            // A wrapped text block, not a row of chips: chips size themselves to
            // their text, and a wrapping container reports the widest row as its
            // preferred width - which is what made the popup open full-screen.
            addRow(card, wrappedText(
                server.tools.joinToString("  ·  "),
                monospace = true,
                color = UIUtil.getLabelForeground()
            ), top = 6)
        }
        return card
    }

    private fun badge(text: String, mounted: Boolean): JBLabel = JBLabel(text).apply {
        isOpaque = true
        background = if (mounted) JBColor(0x2E5B3A, 0x2E5B3A) else JBColor(0x3A3A3E, 0x3A3A3E)
        foreground = if (mounted) JBColor(0xC8E6C9, 0xC8E6C9) else UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(1, 6)
    }
}
