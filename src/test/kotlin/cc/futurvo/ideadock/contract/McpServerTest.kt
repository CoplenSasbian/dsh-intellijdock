package cc.futurvo.ideadock.contract

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The MCP server, exercised over real HTTP on loopback.
 *
 * The surface here is the contract DSH's bridge depends on, so the test asserts
 * the status codes and headers exactly (`docs/04-mcp-host.md` §2), not just the
 * happy path: 401, 405 with `Allow`, 204, 404, batches, notifications, and a tool
 * that throws becoming `isError: true` instead of a protocol error.
 */
class McpServerTest {

    private lateinit var server: EditorMcpServer
    private lateinit var client: HttpClient

    private val tools = listOf(
        object : McpTool {
            override val name = "intellij_hello"
            override val description = "Says hello."
            override val inputSchema: Map<String, Any?> = linkedMapOf(
                "type" to "object",
                "properties" to linkedMapOf<String, Any?>("name" to linkedMapOf("type" to "string")),
                "additionalProperties" to false
            )

            override fun call(arguments: Map<String, Any?>): Any? {
                val name = arguments["name"] as? String ?: "world"
                return linkedMapOf("greeting" to "hello $name")
            }
        },
        object : McpTool {
            override val name = "intellij_boom"
            override val description = "Always fails."
            override val inputSchema: Map<String, Any?> = linkedMapOf("type" to "object", "properties" to linkedMapOf<String, Any?>())

            override fun call(arguments: Map<String, Any?>): Any? = throw IllegalStateException("no such file: x.kt")
        }
    )

    @Before
    fun setUp() {
        server = EditorMcpServer(
            token = "test-token",
            version = "0.1.0-test",
            tools = tools,
            log = {}
        )
        server.start(0)
        client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(LoopbackProxySelector)
            .build()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun post(body: String, token: String? = "test-token"): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(server.url))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (token != null) builder.header("Authorization", "Bearer $token")
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(url: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer test-token")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

    @Test
    fun `health answers without a token`() {
        val response = get(server.healthUrl)
        assertEquals(200, response.statusCode())
        val body = Json.parseObject(response.body())!!
        assertEquals(true, body["ok"])
        assertEquals(2L, body["tools"])
        assertEquals("0.1.0-test", body["version"])
    }

    @Test
    fun `a missing or wrong token is 401`() {
        assertEquals(401, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", token = null).statusCode())
        assertEquals(401, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", token = "nope").statusCode())
    }

    @Test
    fun `initialize negotiates a protocol version and opens a session`() {
        val response = post(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}"""
        )
        assertEquals(200, response.statusCode())
        assertNotNull(response.headers().firstValue("Mcp-Session-Id").orElse(null))
        val result = Json.obj(Json.parseObject(response.body()), "result")!!
        assertEquals("2025-03-26", result["protocolVersion"])
        assertEquals("dsh-ideadock", Json.obj(result, "serverInfo")!!["name"])
    }

    @Test
    fun `an unknown protocol version falls back to the newest one`() {
        val response = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
        val result = Json.obj(Json.parseObject(response.body()), "result")!!
        assertEquals(McpProtocol.DEFAULT_PROTOCOL, result["protocolVersion"])
    }

    @Test
    fun `tools list exposes name, description and schema`() {
        val response = post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        val result = Json.obj(Json.parseObject(response.body()), "result")!!
        val listed = Json.arr(result, "tools")!!.map { it as Map<*, *> }
        assertEquals(2, listed.size)
        assertEquals("intellij_hello", listed[0]["name"])
        assertNotNull(listed[0]["inputSchema"])
    }

    @Test
    fun `tools call returns text content`() {
        val response = post(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"intellij_hello","arguments":{"name":"DSH"}}}"""
        )
        val result = Json.obj(Json.parseObject(response.body()), "result")!!
        val content = Json.arr(result, "content")!!.first() as Map<*, *>
        assertEquals("text", content["type"])
        assertTrue(content["text"].toString().contains("hello DSH"))
    }

    @Test
    fun `a failing tool is data, not a protocol error`() {
        val response = post(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"intellij_boom","arguments":{}}}"""
        )
        assertEquals(200, response.statusCode())
        val result = Json.obj(Json.parseObject(response.body()), "result")!!
        assertEquals(true, result["isError"])
        val content = Json.arr(result, "content")!!.first() as Map<*, *>
        assertTrue(content["text"].toString().contains("no such file"))
    }

    @Test
    fun `an unknown tool is an invalid params error`() {
        val response = post("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"nope"}}""")
        val error = Json.obj(Json.parseObject(response.body()), "error")!!
        assertEquals(-32602L, error["code"])
    }

    @Test
    fun `an unknown method is method not found`() {
        val response = post("""{"jsonrpc":"2.0","id":6,"method":"resources/list"}""")
        val error = Json.obj(Json.parseObject(response.body()), "error")!!
        assertEquals(-32601L, error["code"])
    }

    @Test
    fun `a notification gets 202 and no body`() {
        val response = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, response.statusCode())
        assertTrue(response.body().isBlank())
    }

    @Test
    fun `a batch answers each message`() {
        val response = post(
            """[{"jsonrpc":"2.0","id":7,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/initialized"}]"""
        )
        val body = Json.parseArray(response.body())!!
        assertEquals(1, body.size)
    }

    @Test
    fun `GET on mcp is 405 with Allow, DELETE is 204`() {
        val getResponse = get(server.url)
        assertEquals(405, getResponse.statusCode())
        assertEquals("POST, DELETE", getResponse.headers().firstValue("Allow").orElse(""))

        val deleteResponse = client.send(
            HttpRequest.newBuilder(URI.create(server.url))
                .header("Authorization", "Bearer test-token")
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(204, deleteResponse.statusCode())
    }

    @Test
    fun `an unknown path is 404 and bad json is 400`() {
        assertEquals(404, get("http://127.0.0.1:${server.port}/other").statusCode())
        val bad = post("{ not json")
        assertEquals(400, bad.statusCode())
        val error = Json.obj(Json.parseObject(bad.body()), "error")!!
        assertEquals(-32700L, error["code"])
    }

    @Test
    fun `the url follows the bound port and falls back when the preferred one is taken`() {
        val first = EditorMcpServer("t", "v", tools)
        first.start(0)
        try {
            val second = EditorMcpServer("t2", "v", tools)
            val port = second.start(first.port)
            try {
                assertTrue(port > 0)
                assertFalse(port == first.port)
                assertTrue(second.url.endsWith("/mcp"))
            } finally {
                second.close()
            }
        } finally {
            first.close()
        }
    }
}

/** JSON-RPC message handling on its own: no sockets, no HTTP. */
class McpProtocolTest {

    private val tools = mapOf<String, McpTool>(
        "intellij_ping" to object : McpTool {
            override val name = "intellij_ping"
            override val description = "Ping."
            override val inputSchema: Map<String, Any?> = linkedMapOf("type" to "object", "properties" to linkedMapOf<String, Any?>())
            override fun call(arguments: Map<String, Any?>): Any? = "pong"
        }
    )

    private fun message(text: String): Map<String, Any?> = Json.parseObject(text)!!

    @Test
    fun `a notification never produces a response`() {
        val answer = McpProtocol.answer(
            message("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""),
            tools, "1.0", "instructions"
        )
        assertEquals(null, answer.response)
    }

    @Test
    fun `ping answers an empty object`() {
        val answer = McpProtocol.answer(message("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""), tools, "1.0", "i")
        assertEquals(emptyMap<String, Any?>(), Json.obj(answer.response, "result"))
    }

    @Test
    fun `a string result becomes text content`() {
        val answer = McpProtocol.answer(
            message("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"intellij_ping"}}"""),
            tools, "1.0", "i"
        )
        val content = Json.arr(Json.obj(answer.response, "result"), "content")!!.first() as Map<*, *>
        assertEquals("pong", content["text"])
    }

    @Test
    fun `the fingerprint changes when a description changes`() {
        val before = McpProtocol.fingerprint(tools.values)
        val renamed = mapOf<String, McpTool>(
            "intellij_ping" to object : McpTool {
                override val name = "intellij_ping"
                override val description = "Ping, but different."
                override val inputSchema: Map<String, Any?> = linkedMapOf("type" to "object", "properties" to linkedMapOf<String, Any?>())
                override fun call(arguments: Map<String, Any?>): Any? = "pong"
            }
        )
        val after = McpProtocol.fingerprint(renamed.values)
        assertFalse(before == after)
        assertEquals(12, before.length)
    }
}
