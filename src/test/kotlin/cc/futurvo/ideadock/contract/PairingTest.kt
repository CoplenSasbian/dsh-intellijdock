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

    private var listBody = """{"ok":true,"workspaces":[]}"""
    private var registerStatus = 200
    private var registerBody = """{"ok":true,"registered":{"name":"intellij"},"sessions":[]} """

    @Before
    fun setUp() {
        dock = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        dock.createContext("/quickdock/api/") { exchange ->
            val action = exchange.requestURI.path.substringAfterLast('/')
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            calls += action to body
            val (status, payload) = when (action) {
                "mcp-list" -> 200 to listBody
                "mcp-register" -> registerStatus to registerBody
                "mcp-unregister" -> 200 to """{"ok":true,"removed":["intellij"]}"""
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
}
