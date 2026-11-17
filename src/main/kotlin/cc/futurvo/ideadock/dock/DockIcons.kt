package cc.futurvo.ideadock.dock

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon

/**
 * The dock bar's icons, drawn as 16x16 line art.
 *
 * They are copies of the ones QuickDock's shell page draws (the VS Code dock's
 * bar), not the platform's filled `AllIcons`: the two docks are the same UI in two
 * editors, and a user moving between them should not see a different sidebar
 * glyph. Painting the paths instead of shipping SVGs keeps them theme-aware
 * (`UIUtil.getLabelForeground`), crisp on HiDPI (drawn at the scaled size) and
 * free of the platform's SVG colour-pair conventions.
 */
object DockIcons {

    /** A 16x16 line icon painted with `draw`. */
    class Line(private val color: () -> Color, private val draw: (Graphics2D) -> Unit) : Icon {
        override fun getIconWidth(): Int = JBUI.scale(16)
        override fun getIconHeight(): Int = JBUI.scale(16)

        override fun paintIcon(component: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.translate(x, y)
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                val scale = getIconWidth().toFloat() / 16f
                g2.scale(scale.toDouble(), scale.toDouble())
                g2.color = color()
                g2.stroke = BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                draw(g2)
            } finally {
                g2.dispose()
            }
        }
    }

    private fun neutral(): Color = UIUtil.getLabelForeground()

    /** Shell bar: panel with a divider. Toggles the sidebar. */
    val sidebar = Line(::neutral) { g ->
        g.draw(RoundRectangle2D.Float(1.5f, 1.5f, 13f, 13f, 3f, 3f))
        g.draw(Line2D.Float(5.5f, 1.5f, 5.5f, 14.5f))
    }

    /** Shell bar: plus. Starts a conversation while the sidebar is out of the way. */
    val newSession = Line(::neutral) { g ->
        g.draw(Line2D.Float(8f, 3.2f, 8f, 12.8f))
        g.draw(Line2D.Float(3.2f, 8f, 12.8f, 8f))
    }

    /** Shell bar: an arrow leaving a box. Shows the session in the main window. */
    val handoff = Line(::neutral) { g ->
        g.draw(Line2D.Float(7.5f, 8.5f, 13.5f, 2.5f))
        g.draw(Line2D.Float(9.5f, 2.5f, 13.5f, 2.5f))
        g.draw(Line2D.Float(13.5f, 2.5f, 13.5f, 6.5f))
        val box = Path2D.Float()
        box.moveTo(11.5f, 9.5f)
        box.lineTo(11.5f, 12f)
        box.quadTo(11.5f, 13.5f, 10f, 13.5f)
        box.lineTo(4f, 13.5f)
        box.quadTo(2.5f, 13.5f, 2.5f, 12f)
        box.lineTo(2.5f, 6f)
        box.quadTo(2.5f, 4.5f, 4f, 4.5f)
        box.lineTo(6.5f, 4.5f)
        g.draw(box)
    }

    /** Shell bar: three lines with a dot each. Lists the editor tools. */
    val toolsList = Line(::neutral) { g ->
        for (index in 0..2) {
            val y = 4f + index * 4f
            g.fill(Ellipse2D.Float(1.7f, y - 0.55f, 1.1f, 1.1f))
            g.draw(Line2D.Float(4.7f, y, 13.5f, y))
        }
    }

    /** Shell bar: warning triangle, in the shell's amber. */
    val warning = Line({ com.intellij.ui.JBColor(0xB8860B, 0xE0B341) }) { g ->
        val triangle = Path2D.Float()
        triangle.moveTo(8f, 1.8f)
        triangle.lineTo(15f, 14f)
        triangle.lineTo(1f, 14f)
        triangle.closePath()
        g.draw(triangle)
        g.draw(Line2D.Float(8f, 6.2f, 8f, 9.6f))
        g.fill(Ellipse2D.Float(7.5f, 11.3f, 1f, 1f))
    }
}
