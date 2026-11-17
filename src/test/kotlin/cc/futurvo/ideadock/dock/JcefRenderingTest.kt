package cc.futurvo.ideadock.dock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rendering mode the dock asks JCEF for (see `JcefRendering.kt`). */
class JcefRenderingTest {

    @Test
    fun `the dock asks for windowed rendering by default`() {
        assertFalse(JcefRendering.offscreen(globalEnabled = true, windowed = true))
    }

    @Test
    fun `turning the setting off returns to off-screen`() {
        assertTrue(JcefRendering.offscreen(globalEnabled = true, windowed = false))
    }

    @Test
    fun `a platform without OSR stays windowed whatever the setting says`() {
        assertFalse(JcefRendering.offscreen(globalEnabled = false, windowed = false))
        assertFalse(JcefRendering.offscreen(globalEnabled = false, windowed = true))
    }
}
