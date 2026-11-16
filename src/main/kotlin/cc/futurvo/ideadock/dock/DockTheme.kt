package cc.futurvo.ideadock.dock

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color

/**
 * The few colours a dock page needs, taken from the IDE.
 *
 * JCEF has none of the `--vscode-*` variables the VS Code version relied on, so
 * the palette is read once per render and injected into the page's CSS
 * variables. QuickDock's own page carries its own `data-theme` colours and is
 * not touched.
 */
object DockTheme {

    fun current(): DockPage.Theme {
        val dark = !JBColor.isBright()
        val background = JBColor.PanelBackground
        val foreground = JBColor.foreground()
        return DockPage.Theme(
            bg = hex(background),
            fg = hex(foreground),
            border = hex(JBColor.border()),
            description = hex(UIUtil.getLabelDisabledForeground()),
            link = hex(JBColor(0x2470B3, 0x6C9EF8)),
            buttonBg = hex(JBColor(0x3574F0, 0x4A88C7)),
            buttonFg = hex(Color.WHITE),
            dark = dark
        )
    }

    /** `#rrggbb`, the only form the injected CSS understands. */
    fun hex(color: Color): String = String.format("#%06x", color.rgb and 0xFFFFFF)
}
