package cc.futurvo.ideadock.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Starting DSH from the IDE.
 *
 * `dsh --profile <name>` is the only line that boots a profile, and it goes to a
 * shell, so quoting is not cosmetic: a path with a space that loses its quotes
 * starts the wrong program.
 */
class DshLauncherTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `a bare command is left alone`() {
        assertEquals("dsh", DshLauncher.quoteExecutable("dsh"))
        assertEquals("dsh --profile desktop", DshLauncher.commandLine("dsh", "desktop", "", windows = true))
    }

    @Test
    fun `a path with spaces is quoted per platform`() {
        assertEquals(
            "\"C:\\Program Files\\DSH\\dsh.cmd\"",
            DshLauncher.quoteExecutable("C:\\Program Files\\DSH\\dsh.cmd", windows = true)
        )
        assertEquals(
            "'/opt/my tools/dsh'",
            DshLauncher.quoteExecutable("/opt/my tools/dsh", windows = false)
        )
    }

    @Test
    fun `a posix quote inside a path is escaped`() {
        assertEquals("'/opt/it'\\''s/dsh'", DshLauncher.quoteExecutable("/opt/it's/dsh", windows = false))
    }

    @Test
    fun `extra arguments are appended verbatim`() {
        assertEquals(
            "dsh --profile desktop --port 8080",
            DshLauncher.commandLine("dsh", "desktop", "--port 8080", windows = true)
        )
    }

    @Test
    fun `a profile name is validated before it reaches the shell`() {
        try {
            DshLauncher.commandLine("dsh", "desk top", "", windows = true)
            throw AssertionError("expected an exception")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("invalid DSH profile"))
        }
    }

    @Test
    fun `no profile means no profile argument`() {
        assertEquals("dsh", DshLauncher.commandLine("dsh", "  ", "  ", windows = true))
    }

    @Test
    fun `a configured path is detected`() {
        assertTrue(DshLauncher.looksLikePath("C:\\tools\\dsh.cmd"))
        assertTrue(DshLauncher.looksLikePath("/usr/local/bin/dsh"))
        assertFalse(DshLauncher.looksLikePath("dsh"))
        assertFalse(DshLauncher.looksLikePath("npx dsh"))
    }

    @Test
    fun `profiles are the directories with a package json`() {
        val home = temporary.newFolder("home")
        val profiles = File(home, "profiles")
        File(profiles, "desktop").mkdirs()
        File(File(profiles, "desktop"), "package.json").writeText("{}")
        File(profiles, "node_modules").mkdirs()
        File(File(profiles, "node_modules"), "package.json").writeText("{}")
        File(profiles, ".hidden").mkdirs()
        File(File(profiles, ".hidden"), "package.json").writeText("{}")
        File(profiles, "empty").mkdirs()

        assertEquals(listOf("desktop"), DshLauncher.listProfiles(home))
    }

    @Test
    fun `a missing profiles directory is empty, not an error`() {
        assertEquals(emptyList<String>(), DshLauncher.listProfiles(temporary.newFolder("nothing")))
    }

    @Test
    fun `the shell matches the platform`() {
        assertEquals(listOf("cmd.exe", "/c", "dsh"), DshLauncher.shellCommand("dsh", windows = true))
        assertEquals(listOf("/bin/sh", "-lc", "dsh"), DshLauncher.shellCommand("dsh", windows = false))
    }

    @Test
    fun `dsh home comes from the environment or the user home`() {
        assertEquals(
            File("D:/dsh-home").path,
            DshLauncher.dshHome(mapOf("DSH_HOME" to "D:/dsh-home")).path
        )
        val fallback = DshLauncher.dshHome(mapOf("USERPROFILE" to "C:/Users/me"))
        assertEquals(".dsh", fallback.name)
        assertEquals("me", fallback.parentFile.name)
    }
}
