package cc.futurvo.ideadock.dock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated pages.
 *
 * These are strings, and the VS Code version's most expensive bugs were all in
 * here: an unescaped path breaking the document, the iframe losing its origin
 * check, a button that does nothing because the injected call was wrong.
 */
class DockPageTest {

    private val theme = DockPage.Theme(
        bg = "#1e1f22",
        fg = "#dfe1e5",
        border = "#393b40",
        description = "#8b8f96",
        link = "#6c9ef8",
        buttonBg = "#4a88c7",
        buttonFg = "#ffffff",
        dark = true
    )

    private val translate: (String) -> String = { "t:$it" }

    @Test
    fun `the frame embeds the shell page and relays both messages`() {
        val page = DockPage.frame(
            pageUrl = "http://127.0.0.1:47910/quickdock?view=vscode&dock=idea-1&mcp=1",
            theme = theme,
            lang = "en",
            toggleCall = "window.__toggle('x')",
            openCall = "window.__open(data.path)"
        ).html
        assertTrue(page.contains("src=\"http://127.0.0.1:47910/quickdock?view=vscode&amp;dock=idea-1&amp;mcp=1\""))
        assertTrue(page.contains("window.__toggle('x')"))
        assertTrue(page.contains("window.__open(data.path)"))
        assertTrue(page.contains("quickdock:mcp-toggle"))
        assertTrue(page.contains("quickdock:open-file"))
        assertTrue(page.contains("window.dshMcpState"))
        // The origin check must compare against the iframe's own origin.
        assertTrue(page.contains("http://127.0.0.1:47910"))
        assertTrue(page.contains("clipboard-read"))
    }

    @Test
    fun `the frame escapes the URL so a query cannot break out`() {
        val page = DockPage.frame(
            pageUrl = "http://127.0.0.1:47910/quickdock?x=\"><script>alert(1)</script>",
            theme = theme, lang = "en", toggleCall = "", openCall = ""
        ).html
        assertFalse(page.contains("<script>alert(1)</script>"))
        assertTrue(page.contains("&quot;&gt;&lt;script&gt;"))
    }

    @Test
    fun `the offline card offers the actions for a refused port`() {
        val page = DockPage.offline(
            model = DockPage.OfflineModel(
                port = 47910,
                pageUrl = "http://127.0.0.1:47910/quickdock?view=browser",
                lead = "nothing answered",
                reason = "connection refused",
                detail = "connection refused",
                detectedPort = 48000,
                detectedFile = "C:\\state\\config.json",
                detectedDiffers = true,
                disabled = false,
                stale = false,
                startable = true,
                hasLog = true,
                stateDir = "C:\\state",
                steps = listOf("first step", "second step")
            ),
            theme = theme,
            lang = "en",
            actionCall = "window.__action(action)",
            t = translate
        ).html
        assertTrue(page.contains("startDsh"))
        assertTrue(page.contains("retry"))
        assertTrue(page.contains("useDetectedPort"))
        assertTrue(page.contains("openInBrowser"))
        assertTrue(page.contains("openLog"))
        assertTrue(page.contains("window.__action(action)"))
        assertTrue(page.contains("first step"))
        assertTrue(page.contains("47910"))
        assertTrue(page.contains("C:\\state\\config.json"))
    }

    @Test
    fun `the offline buttons carry a valid inline handler`() {
        val page = DockPage.offline(
            model = DockPage.OfflineModel(
                port = 47910,
                pageUrl = "http://127.0.0.1:47910/quickdock?view=browser",
                lead = "nothing answered",
                reason = "connection refused",
                detail = "connection refused",
                detectedPort = 48000,
                detectedFile = "C:\\state\\config.json",
                detectedDiffers = true,
                disabled = false,
                stale = false,
                startable = true,
                hasLog = true,
                stateDir = "C:\\state",
                steps = listOf("first step")
            ),
            theme = theme,
            lang = "en",
            actionCall = "window.__action({request: '' + action})",
            t = translate
        ).html
        // A raw `"` in `onclick="..."` would end the attribute right there; this
        // regex reads it the way a browser does, so `var action=` fails below.
        val handlers = Regex("onclick=\"([^\"]*)\"").findAll(page).map { it.groupValues[1] }.toList()
        assertEquals(6, handlers.size)
        assertEquals(
            "var action=\"startDsh\"; window.__action({request: '' + action}); return false;",
            unescapeAttribute(handlers[0])
        )
        assertEquals(
            "var action=\"openLog\"; window.__action({request: '' + action}); return false;",
            unescapeAttribute(handlers[5])
        )
    }

    /** Undo [DockPage.esc] for an attribute value, as an HTML parser would. */
    private fun unescapeAttribute(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    @Test
    fun `a stale plugin gets no port button and no start button`() {
        val page = DockPage.offline(
            model = DockPage.OfflineModel(
                port = 47910, pageUrl = "http://127.0.0.1:47910/quickdock",
                lead = "stale", reason = "no app-proxy", detail = "apiVersion 1",
                detectedPort = 48000, detectedFile = null, detectedDiffers = true,
                disabled = false, stale = true, startable = false, hasLog = false,
                stateDir = "C:\\state", steps = listOf("update QuickDock")
            ),
            theme = theme, lang = "en", actionCall = "window.__action(action)", t = translate
        ).html
        assertFalse(page.contains("useDetectedPort"))
        assertFalse(page.contains("startDsh"))
        assertTrue(page.contains("retry"))
        assertTrue(page.contains("update QuickDock"))
    }

    @Test
    fun `a disabled listener explains port zero`() {
        val page = DockPage.offline(
            model = DockPage.OfflineModel(
                port = 0, pageUrl = "http://127.0.0.1/quickdock",
                lead = "off", reason = "switched off", detail = "0",
                detectedPort = 0, detectedFile = "C:\\state\\config.json", detectedDiffers = false,
                disabled = true, stale = false, startable = false, hasLog = false,
                stateDir = "C:\\state", steps = listOf("port 0 turns the listener off")
            ),
            theme = theme, lang = "en", actionCall = "window.__action(action)", t = translate
        ).html
        assertTrue(page.contains("port 0 turns the listener off"))
        assertFalse(page.contains("useDetectedPort"))
    }

    @Test
    fun `the connecting card shows the address`() {
        val page = DockPage.connecting(47910, theme, "en", "Connecting", "wait a moment").html
        assertTrue(page.contains("127.0.0.1:47910"))
        assertTrue(page.contains("wait a moment"))
    }

    @Test
    fun `the jcef card still lets the user act`() {
        val page = DockPage.jcefUnavailable(
            theme, "en", "window.__action(action)",
            listOf("no JBR", "built-in plugin disabled"), translate
        ).html
        assertTrue(page.contains("no JBR"))
        assertTrue(page.contains("openInBrowser"))
        assertTrue(page.contains("retry"))
    }

    @Test
    fun `text and attributes are escaped`() {
        assertEquals("&lt;b&gt; &amp; &quot;x&quot; &#39;y&#39;", DockPage.esc("<b> & \"x\" 'y'"))
    }

    @Test
    fun `a js string literal cannot close the script`() {
        val literal = DockPage.jsString("</script><script>alert(1)</script>")
        assertFalse(literal.contains("</script>"))
        assertTrue(literal.startsWith("\""))
    }

    @Test
    fun `the origin of a dock url ignores the path and query`() {
        assertEquals("http://127.0.0.1:47910", DockPage.originOf("http://127.0.0.1:47910/quickdock?view=vscode"))
        assertEquals("http://127.0.0.1", DockPage.originOf("http://127.0.0.1/quickdock"))
        assertEquals("", DockPage.originOf("not a url"))
    }
}
