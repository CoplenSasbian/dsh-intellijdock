package cc.futurvo.ideadock.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Path comparison is what decides which folder a registration belongs to. */
class PathsTest {

    @Test
    fun `windows comparison is case-insensitive and separator-agnostic`() {
        assertTrue(Paths.samePath("C:\\work\\proj", "c:/WORK/proj", windows = true))
        assertTrue(Paths.isInsideDirectory("C:\\work\\proj\\src\\A.kt", "c:/work/proj", windows = true))
    }

    @Test
    fun `posix comparison is case-sensitive`() {
        assertFalse(Paths.samePath("/work/proj", "/Work/proj", windows = false))
        assertTrue(Paths.samePath("/work/proj/", "/work/proj", windows = false))
    }

    @Test
    fun `containment does not match a sibling with a shared prefix`() {
        assertFalse(Paths.isInsideDirectory("/work/proj-other", "/work/proj", windows = false))
        assertTrue(Paths.isInsideDirectory("/work/proj", "/work/proj", windows = false))
    }

    @Test
    fun `a bare windows root still compares`() {
        assertEquals("c:", Paths.comparisonPath("C:\\", windows = true))
        assertEquals("/", Paths.comparisonPath("", windows = false))
    }

    @Test
    fun `absolute detection accepts both separators`() {
        assertTrue(Paths.isAbsolute("C:\\work\\a.kt", windows = true))
        assertTrue(Paths.isAbsolute("/home/me/a.kt", windows = true))
        assertTrue(Paths.isAbsolute("\\\\server\\share\\a.kt", windows = true))
        assertFalse(Paths.isAbsolute("src/main.kt", windows = true))
        assertFalse(Paths.isAbsolute("C:\\work", windows = false))
    }

    @Test
    fun `relative paths resolve against the folder`() {
        val resolved = Paths.resolve("/work/proj", "src/A.kt", windows = false)
        assertEquals("/work/proj/src/A.kt", resolved.replace('\\', '/'))
    }

    @Test
    fun `file name and parent`() {
        assertEquals("A.kt", Paths.fileName("C:\\work\\src\\A.kt"))
        assertEquals("A.kt", Paths.fileName("/work/src/A.kt"))
        assertEquals("/work/src", Paths.parent("/work/src/A.kt"))
        assertNull(Paths.parent("A.kt"))
    }
}

/** The port rules the VS Code client uses, which have to stay identical. */
class QuickDockPortsTest {

    private class FakeFs(
        private val files: MutableMap<String, String> = mutableMapOf()
    ) : QuickDockPorts.FsLike {
        override fun exists(file: java.io.File): Boolean = files.containsKey(file.path)
        override fun readText(file: java.io.File): String? = files[file.path]
        fun put(file: java.io.File, text: String) {
            files[file.path] = text
        }
    }

    private fun env(stateDir: String) = mapOf("DSH_QUICKDOCK_STATE_DIR" to stateDir)

    @Test
    fun `state dir prefers the environment variable`() {
        val dir = QuickDockPorts.stateDir(env("D:/state"))
        assertEquals("D:/state", dir.path.replace('\\', '/'))
    }

    @Test
    fun `state dir falls back to LOCALAPPDATA then TEMP`() {
        val local = QuickDockPorts.stateDir(mapOf("LOCALAPPDATA" to "C:/Users/me/AppData/Local"))
        assertEquals("dsh-quickdock", local.name)
        assertEquals("Local", local.parentFile.name)
        val temp = QuickDockPorts.stateDir(mapOf("TEMP" to "C:/Temp"))
        assertEquals("Temp", temp.parentFile.name)
    }

    @Test
    fun `config json wins over the log`() {
        val fs = FakeFs()
        val dir = env("D:/state")
        fs.put(QuickDockPorts.configFile(dir), """{"port":48001}""")
        fs.put(QuickDockPorts.hostLogFile(dir), "server listening on http://127.0.0.1:48002")
        val detected = QuickDockPorts.detect(dir, fs)!!
        assertEquals(48001, detected.port)
        assertEquals(QuickDockPorts.Source.CONFIG_FILE, detected.source)
    }

    @Test
    fun `log is the fallback and the last line wins`() {
        val fs = FakeFs()
        val dir = env("D:/state")
        fs.put(
            QuickDockPorts.hostLogFile(dir),
            "server listening on http://127.0.0.1:47910\nsomething\nserver listening on http://127.0.0.1:48010"
        )
        val detected = QuickDockPorts.detect(dir, fs)!!
        assertEquals(48010, detected.port)
        assertEquals(QuickDockPorts.Source.LOG, detected.source)
    }

    @Test
    fun `port 0 is a detection, not an error`() {
        val fs = FakeFs()
        val dir = env("D:/state")
        fs.put(QuickDockPorts.configFile(dir), """{"port":0}""")
        assertEquals(0, QuickDockPorts.detect(dir, fs)!!.port)
        assertTrue(QuickDockPorts.isValidPort(0))
        assertFalse(QuickDockPorts.isUsablePort(0))
    }

    @Test
    fun `a malformed config falls through to the log`() {
        val fs = FakeFs()
        val dir = env("D:/state")
        fs.put(QuickDockPorts.configFile(dir), "{ not json")
        fs.put(QuickDockPorts.hostLogFile(dir), "server listening on http://127.0.0.1:47911")
        assertEquals(47911, QuickDockPorts.detect(dir, fs)!!.port)
    }

    @Test
    fun `nothing found means no detection`() {
        assertNull(QuickDockPorts.detect(env("D:/nothing"), FakeFs()))
    }

    @Test
    fun `resolve precedence is setting, detection, default`() {
        val detected = QuickDockPorts.Detection(
            48000, QuickDockPorts.Source.CONFIG_FILE, java.io.File("config.json")
        )
        assertEquals(QuickDockPorts.Source.SETTING, QuickDockPorts.resolvePort(49100, true, detected).source)
        assertEquals(48000, QuickDockPorts.resolvePort(null, true, detected).port)
        assertEquals(QuickDockPorts.Source.DEFAULT, QuickDockPorts.resolvePort(null, false, detected).source)
        assertEquals(QuickDockPorts.DEFAULT_PORT, QuickDockPorts.resolvePort(0, false, null).port)
        assertEquals(QuickDockPorts.DEFAULT_PORT, QuickDockPorts.resolvePort(70000, false, null).port)
    }

    @Test
    fun `config readers reject out of range values`() {
        assertNull(QuickDockPorts.readPortFromConfig("""{"port":70000}"""))
        assertNull(QuickDockPorts.readPortFromConfig("[]"))
        assertEquals(47910, QuickDockPorts.readPortFromConfig("""{"port":"47910"}"""))
        assertNull(QuickDockPorts.readPortFromLog("nothing here"))
    }
}

/** URL construction, including the encoding a Windows path needs. */
class QuickDockUrlsTest {

    @Test
    fun `page and api urls`() {
        assertEquals("http://127.0.0.1:47910/quickdock?view=vscode", QuickDockUrls.pageUrl(47910))
        assertEquals("http://127.0.0.1:47910/quickdock?view=browser", QuickDockUrls.pageUrl(47910, QuickDockUrls.BROWSER_VIEW))
        assertEquals("http://127.0.0.1:47910/quickdock/api/meta", QuickDockUrls.metaUrl(47910))
        assertEquals("http://127.0.0.1:47910/quickdock/api/mcp-register", QuickDockUrls.dockApiUrl(47910, "mcp-register"))
    }

    @Test
    fun `shell url carries dock, mcp and every workspace`() {
        val url = QuickDockUrls.shellUrl(
            port = 47910,
            dockId = "idea-0123456789ab",
            mcp = true,
            workspaceFolders = listOf("C:\\work\\one", "C:\\work\\two")
        )
        assertTrue(url.startsWith("http://127.0.0.1:47910/quickdock?view=vscode"))
        assertTrue(url.contains("dock=idea-0123456789ab"))
        assertTrue(url.contains("mcp=1"))
        assertTrue(url.contains("workspace=C%3A%5Cwork%5Cone"))
        assertTrue(url.contains("workspace=C%3A%5Cwork%5Ctwo"))
    }

    @Test
    fun `mcp off still sends the parameter, with 0`() {
        val url = QuickDockUrls.shellUrl(47910, "idea-1", mcp = false, workspaceFolders = emptyList())
        assertTrue(url.contains("mcp=0"))
    }

    @Test
    fun `encoding keeps unreserved characters and escapes a space as percent-20`() {
        assertEquals("a-b_c.d~e", QuickDockUrls.encode("a-b_c.d~e"))
        assertEquals("a%20b", QuickDockUrls.encode("a b"))
        assertEquals("%E9%92%BB", QuickDockUrls.encode("钻"))
    }
}

/** The glob subset the file tools understand. */
class GlobTest {

    @Test
    fun `double star crosses directories`() {
        val match = Glob.matcher("**/*.kt", windows = false)
        assertTrue(match("src/main/A.kt"))
        assertTrue(match("A.kt"))
        assertFalse(match("src/A.java"))
    }

    @Test
    fun `single star stays inside one segment`() {
        val match = Glob.matcher("src/*.kt", windows = false)
        assertTrue(match("src/A.kt"))
        assertFalse(match("src/main/A.kt"))
    }

    @Test
    fun `question mark matches one character`() {
        val match = Glob.matcher("A?.kt", windows = false)
        assertTrue(match("Ab.kt"))
        assertFalse(match("Abc.kt"))
    }

    @Test
    fun `braces are an alternation`() {
        val match = Glob.matcher("**/*.{kt,java}", windows = false)
        assertTrue(match("src/A.kt"))
        assertTrue(match("src/A.java"))
        assertFalse(match("src/A.py"))
    }

    @Test
    fun `the default pattern matches everything`() {
        assertTrue(Glob.matcher("**/*")( "any/path.txt"))
        assertTrue(Glob.matcher("*")("any/path.txt"))
    }

    @Test
    fun `windows matching is case-insensitive`() {
        assertTrue(Glob.matches("**/*.KT", "src/A.kt", windows = true))
    }
}
