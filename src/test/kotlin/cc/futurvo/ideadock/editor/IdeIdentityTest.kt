package cc.futurvo.ideadock.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/** The per-IDE tool namespace (see `IdeIdentity.kt`). */
class IdeIdentityTest {

    @Test
    fun `product codes map to the product, not the vendor`() {
        assertEquals("idea", IdeIdentity.slugOf("IC", null))
        assertEquals("idea", IdeIdentity.slugOf("IU", null))
        assertEquals("clion", IdeIdentity.slugOf("CL", null))
        assertEquals("pycharm", IdeIdentity.slugOf("PY", null))
        assertEquals("goland", IdeIdentity.slugOf("GO", null))
        assertEquals("rustrover", IdeIdentity.slugOf("RS", null))
        assertEquals("aqua", IdeIdentity.slugOf("QA", null))
    }

    @Test
    fun `an unknown code falls back to the version name`() {
        assertEquals("idea", IdeIdentity.slugOf(null, "IntelliJ IDEA"))
        assertEquals("clion", IdeIdentity.slugOf("", "CLion"))
        assertEquals("fleet", IdeIdentity.slugOf("ZZ", "Fleet"))
    }

    @Test
    fun `nothing usable means intellij, so a name is always valid`() {
        assertEquals("intellij", IdeIdentity.slugOf(null, null))
        assertEquals("intellij", IdeIdentity.slugOf("", "   "))
    }

    @Test
    fun `a slug is safe inside a tool name`() {
        for (code in listOf("IC", "CL", "PY", "GO", "WS", "PS", "RM", "RD", "DG", "RS", "QA", "AC")) {
            val slug = IdeIdentity.slugOf(code, null)
            assertEquals(slug, slug.lowercase())
            assertEquals(slug, slug.filter { it.isLetterOrDigit() })
        }
    }
}
