package cc.futurvo.ideadock.contract

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Pairing against a fake QuickDock.
 *
 * The fake records what the client sent, which is what the interesting rules are
 * about: `workspace` reaches the dock unchanged, the token travels in the
 * registration headers, `mcp-list` decides whether a registration is needed, and a
 * 404 is reported as "this build has no pairing route" rather than a port problem.
 */
class PairingTest {

    private lateinit var dock: HttpServer
    private var port: Int = 0

    /** Every request the client made, as `action` + body. */
    private val calls = mutableListOf<Pair<String, String>>()
    /** Every request the client made, as `action` + raw query string. */
    private val queries = mutableListOf<Pair<String, String?>>()

    private var listBody = """{"ok":true,"workspaces":[]}"""
    private var registerStatus = 200
    private var registerBody = """{"ok":true,"registered":{"name":"intellij"},"sessions":[]} """
    private var appUrlBody = """{"ok":true,"url":"http://127.0.0.1:57000/?dock=1"}"""
    private var targetBody =
        """{"sessionId":"s-1","seq":2,"reloadSeq":1,"workspace":{"dock":"C:\\work","session":"C:\\other","matches":false}}"""
    private var serversBody = """{"ok":true,"workspace":"C:\\work","servers":[
        {"name":"clion","url":"http://127.0.0.1:47920/mcp","version":"0.1.0",
         "tools":["clion_read_file","clion_selection"],"mounted":{"sessions":3}}]}"""

    @Before
    fun setUp() {
        dock = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        dock.createContext("/quickdock/api/") { exchange ->
            val action = exchange.requestURI.path.substringAfterLast('/')
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            calls += action to body
            queries += action to exchange.requestURI.query
            val (status, payload) = when (action) {
                "mcp-list" -> 200 to listBody
                "mcp-register" -> registerStatus to registerBody
                "mcp-unregister" -> 200 to """{"ok":true,"removed":["intellij"]}"""
                "app-url" -> 200 to appUrlBody
                "dock-target" -> 200 to targetBody
                "handoff" -> 200 to """{"ok":true,"seq":1}"""
                "bind" -> 200 to """{"ok":true,"dock":"idea-1"}"""
                "dock-context" -> 200 to """{"ok":true,"dock":{"id":"idea-1","type":"intellij"}}"""
                "mcp-servers" -> 200 to serversBody
                else -> 404 to """{"error":"not found"}"""
            }
            val bytes = payload.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("content-type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        dock.start()
        port = dock.address.port
    }

    @After
    fun tearDown() {
        dock.stop(0)
    }

    private fun client() = PairingClient()

    private fun registration(workspace: String = "C:\\work\\proj", url: String = "http://127.0.0.1:47920/mcp") =
        Pairing.Registration(
            workspace = workspace,
            url = url,
            headers = mapOf("Authorization" to "Bearer secret"),
            version = "0.1.0",
            tools = listOf("intellij_read_file"),
            waitMs = 1_000
        )

    @Test
    fun `register posts the bridge body with the token`() {
        val answer = client().register(port, registration())
        assertTrue(answer.ok)
        val call = calls.last()
        assertEquals("mcp-register", call.first)
        val body = Json.parseObject(call.second)!!
        assertEquals("C:\\work\\proj", body["workspace"])
        assertEquals("intellij", body["name"])
        assertEquals("Bearer secret", Json.obj(body, "headers")!!["Authorization"])
        assertEquals(1000L, body["waitMs"])
        assertEquals(listOf("intellij_read_file"), body["tools"])
    }

    @Test
    fun `unregister names the workspace and the tool namespace`() {
        client().unregister(port, "C:\\work\\proj")
        val body = Json.parseObject(calls.last().second)!!
        assertEquals("C:\\work\\proj", body["workspace"])
        assertEquals("intellij", body["name"])
    }

    @Test
    fun `a 404 is reported with its status so the caller can hint an upgrade`() {
        registerStatus = 404
        registerBody = """{"error":"unknown action"}"""
        val answer = client().register(port, registration())
        assertFalse(answer.ok)
        assertEquals(404, answer.status)
        assertEquals("unknown action", answer.error)
    }

    @Test
    fun `list parses the bridge answer`() {
        listBody = """
            {"ok":true,"workspaces":[
              {"workspace":"C:\\work\\proj","servers":[
                {"name":"intellij","url":"http://127.0.0.1:47920/mcp","transport":"streamable-http",
                 "sessions":[{"sessionId":"s1","state":"connected"}]}]}]}
        """.trimIndent()
        val answer = client().list(port, "C:\\work\\proj")
        assertTrue(answer.ok)
        val server = Pairing.findRegistration(answer.body, "C:\\work\\proj", "intellij", windows = true)
        assertNotNull(server)
        assertEquals("http://127.0.0.1:47920/mcp", server!!.url)
        assertEquals("connected", server.sessions.single().state)
    }

    @Test
    fun `a registration is needed when nothing is mounted`() {
        assertTrue(Pairing.needsRegistration(null, registration(), windows = true))
        assertTrue(Pairing.needsRegistration(Pairing.BridgeList(true, emptyList()), registration(), windows = true))
    }

    @Test
    fun `a registration is not needed when the same endpoint is mounted`() {
        val list = Pairing.bridgeList(
            Json.parseObject(
                """{"ok":true,"workspaces":[{"workspace":"C:\\work\\proj","servers":[
                   {"name":"intellij","url":"http://127.0.0.1:47920/mcp"}]}]}"""
            )
        )
        assertFalse(Pairing.needsRegistration(list, registration(), windows = true))
    }

    @Test
    fun `a moved endpoint is re-registered`() {
        val list = Pairing.bridgeList(
            Json.parseObject(
                """{"ok":true,"workspaces":[{"workspace":"C:\\work\\proj","servers":[
                   {"name":"intellij","url":"http://127.0.0.1:48000/mcp"}]}]}"""
            )
        )
        assertTrue(Pairing.needsRegistration(list, registration(), windows = true))
    }

    @Test
    fun `workspace matching is case-insensitive on windows and exact elsewhere`() {
        val list = Pairing.bridgeList(
            Json.parseObject(
                """{"ok":true,"workspaces":[{"workspace":"c:/Work/PROJ","servers":[
                   {"name":"intellij","url":"http://127.0.0.1:47920/mcp"}]}]}"""
            )
        )
        assertNotNull(Pairing.findRegistration(list, "C:\\work\\proj", "intellij", windows = true))
        assertNull(Pairing.findRegistration(list, "C:\\work\\proj", "intellij", windows = false))
    }

    @Test
    fun `a different tool namespace is a different server`() {
        val list = Pairing.bridgeList(
            Json.parseObject(
                """{"ok":true,"workspaces":[{"workspace":"/work","servers":[
                   {"name":"vscode","url":"http://127.0.0.1:47920/mcp"}]}]}"""
            )
        )
        assertNull(Pairing.findRegistration(list, "/work", "intellij", windows = false))
        assertTrue(Pairing.needsRegistration(list, registration("/work"), windows = false))
    }

    @Test
    fun `tokens are url-safe and long enough`() {
        val token = Pairing.newToken()
        assertTrue(token.length >= 43)
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertFalse(token == Pairing.newToken())
    }

    @Test
    fun `a refused connection is an answer, not an exception`() {
        val closed = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        closed.start()
        val freePort = closed.address.port
        closed.stop(0)
        val answer = client().list(freePort, "/work", timeoutMs = 2_000)
        assertFalse(answer.ok)
        assertEquals(0, answer.status)
        assertNotNull(answer.error)
    }

    /* ---- the top-level app mode: app-url, dock-target, handoff ---- */

    @Test
    fun `app-url returns the proxy-served app document`() {
        val answer = client().appUrl(port)
        assertTrue(answer.ok)
        assertEquals("http://127.0.0.1:57000/?dock=1", answer.body)
    }

    @Test
    fun `a build without the app-url route answers null, so the shell page stays`() {
        appUrlBody = """{"error":"not found"}"""
        val answer = client().appUrl(port)
        assertFalse(answer.ok)
        assertNull(answer.body)
    }

    @Test
    fun `dock-target carries the session, both sequences and the workspace verdict`() {
        val answer = client().dockTarget(port, "idea-1")
        assertTrue(answer.ok)
        val target = answer.body!!
        assertEquals("s-1", target.sessionId)
        assertEquals(2L, target.seq)
        assertEquals(1L, target.reloadSeq)
        assertEquals(false, target.workspaceMatches)
        assertEquals("dock-target", calls.last().first)
    }

    @Test
    fun `binding a session targets this dock and stays quiet`() {
        assertTrue(client().bind(port, "idea-1", "s-1").ok)
        assertEquals("bind", calls.last().first)
        assertEquals("dock=idea-1", queries.last().second)
        val body = Json.parseObject(calls.last().second)!!
        assertEquals("s-1", body["sessionId"])
        // A report from the dock must never summon a window (the shell's rule).
        assertEquals(true, body["quiet"])
    }

    @Test
    fun `handoff posts the session the main window should show`() {
        assertTrue(client().handoff(port, "s-1").ok)
        val body = Json.parseObject(calls.last().second)!!
        assertEquals("s-1", body["sessionId"])
    }

    @Test
    fun `the dock registers its own context, naming the JetBrains view and its roots`() {
        assertTrue(client().registerDockContext(port, "idea-1", workspaceFolders = listOf("C:\\work", "C:\\other")).ok)
        val body = Json.parseObject(calls.last().second)!!
        assertEquals("idea-1", body["id"])
        assertEquals("intellij", body["type"])
        val workspace = Json.obj(body, "workspace")!!
        assertEquals("C:\\work", workspace["path"])
        assertEquals(listOf("C:\\work", "C:\\other"), workspace["paths"])
    }

    @Test
    fun `mcp-servers names the published tools and their mount state`() {
        val answer = client().mcpServers(port, "C:\\work")
        assertTrue(answer.ok)
        val servers = answer.body!!.servers
        assertEquals(1, servers.size)
        val server = servers[0]
        assertEquals("clion", server.name)
        assertEquals("0.1.0", server.version)
        assertEquals(3, server.mountedSessions)
        assertEquals(listOf("clion_read_file", "clion_selection"), server.tools)
        assertEquals("mcp-servers", calls.last().first)
    }
}
