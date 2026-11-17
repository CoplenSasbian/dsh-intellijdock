package cc.futurvo.ideadock.contract

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * The editor-side MCP server, on loopback only.
 *
 * DSH's agent reaches the editor through this server: the IDE registers its URL
 * and bearer token with QuickDock (`Pairing`), QuickDock forwards it to DSH's
 * workspace MCP bridge, and sessions whose working directory is inside the
 * registered folder mount `mcp__intellij__*`.
 *
 * The HTTP surface:
 *
 * ```
 * GET    /health   no auth, {ok, version, tools}
 * POST   /mcp      bearer, JSON or 202 for a pure notification batch
 * GET    /mcp      405 + Allow: POST, DELETE   (no server-initiated stream)
 * DELETE /mcp      204
 * other            404
 * ```
 */
class EditorMcpServer(
    private val token: String,
    private val version: String,
    private val tools: List<McpTool>,
    private val instructions: String = DEFAULT_INSTRUCTIONS,
    private val serverName: String = McpProtocol.SERVER_NAME,
    private val log: (String) -> Unit = {}
) : AutoCloseable {

    private val toolMap: Map<String, McpTool> = tools.associateBy { it.name }
    private var server: HttpServer? = null
    private var threadPool: java.util.concurrent.ExecutorService? = null

    /** The port actually bound; 0 before [start]. */
    @Volatile
    var port: Int = 0
        private set

    /** This server's streamable HTTP endpoint, as registered with QuickDock. */
    val url: String get() = "http://127.0.0.1:$port/mcp"

    /** Unauthenticated liveness endpoint. */
    val healthUrl: String get() = "http://127.0.0.1:$port/health"

    /** The token DSH must send; kept here so callers cannot forget it. */
    val bearerToken: String get() = token

    /**
     * Bind and start.
     *
     * `preferredPort` is only a preference: a second IDE window (or any other
     * listener) may already own it, and the pairing carries the real URL, so an
     * ephemeral port costs nothing.
     *
     * @return the port that was actually bound.
     */
    fun start(preferredPort: Int): Int {
        val address = InetSocketAddress(InetAddress.getLoopbackAddress(), preferredPort.coerceAtLeast(0))
        val created = try {
            HttpServer.create(address, 0)
        } catch (error: BindException) {
            log("mcp: port $preferredPort is taken, falling back to an ephemeral one")
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        }
        val pool = Executors.newFixedThreadPool(4, daemonFactory("dsh-ideadock-mcp"))
        created.executor = pool
        created.createContext("/") { exchange -> handle(exchange) }
        created.start()
        server = created
        threadPool = pool
        port = created.address.port
        log("mcp: listening on $url with ${tools.size} tool(s)")
        return port
    }

    override fun close() {
        server?.stop(0)
        server = null
        threadPool?.shutdownNow()
        threadPool = null
        port = 0
    }

    /* -------------------------------- dispatch -------------------------------- */

    private fun handle(exchange: HttpExchange) {
        try {
            dispatch(exchange)
        } catch (error: IOException) {
            log("mcp: request failed: ${error.message}")
        } catch (error: Throwable) {
            log("mcp: request failed: ${error.javaClass.simpleName}: ${error.message}")
            try {
                sendJson(exchange, 500, linkedMapOf("error" to "internal error"))
            } catch (ignored: IOException) {
                // The client is gone; nothing left to report to.
            }
        } finally {
            exchange.close()
        }
    }

    private fun dispatch(exchange: HttpExchange) {
        val path = exchange.requestURI.path ?: "/"
        val method = exchange.requestMethod.uppercase()

        if (path == "/health") {
            sendJson(
                exchange, 200,
                linkedMapOf("ok" to true, "version" to version, "tools" to tools.size)
            )
            return
        }

        if (!authorized(exchange)) {
            sendJson(exchange, 401, linkedMapOf("error" to "unauthorized"))
            return
        }

        if (path != "/mcp") {
            sendJson(exchange, 404, linkedMapOf("error" to "not found"))
            return
        }

        when (method) {
            "GET" -> {
                // This server never initiates messages, so 405 is the correct
                // streamable-HTTP answer to "open the stream".
                exchange.responseHeaders.set("Allow", "POST, DELETE")
                sendJson(exchange, 405, linkedMapOf("error" to "no server-initiated stream"))
            }
            "DELETE" -> {
                exchange.sendResponseHeaders(204, -1)
            }
            "POST" -> handlePost(exchange)
            else -> {
                exchange.responseHeaders.set("Allow", "POST, DELETE")
                sendJson(exchange, 405, linkedMapOf("error" to "method not allowed"))
            }
        }
    }

    private fun authorized(exchange: HttpExchange): Boolean {
        val header = exchange.requestHeaders.getFirst("Authorization") ?: return false
        return constantTimeEquals(header, "Bearer $token")
    }

    private fun handlePost(exchange: HttpExchange) {
        val body = try {
            readBody(exchange)
        } catch (error: TooLargeException) {
            sendJson(exchange, 413, linkedMapOf("error" to "request body too large"))
            return
        }
        val parsed: Any? = try {
            if (body.isBlank()) null else Json.parse(body)
        } catch (error: Json.JsonException) {
            sendJson(
                exchange, 400,
                linkedMapOf(
                    "jsonrpc" to "2.0", "id" to null,
                    "error" to linkedMapOf("code" to -32700, "message" to "parse error")
                )
            )
            return
        }
        val batch = parsed is List<*>
        val messages: List<Map<String, Any?>> = when (parsed) {
            is List<*> -> parsed.mapNotNull { it as? Map<String, Any?> }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                listOf(parsed as Map<String, Any?>)
            }
            else -> emptyList()
        }

        val responses = ArrayList<Map<String, Any?>>(messages.size)
        var sessionId: String? = null
        for (message in messages) {
            val answer = McpProtocol.answer(message, toolMap, version, instructions)
            if (answer.sessionId != null) sessionId = answer.sessionId
            answer.response?.let(responses::add)
        }

        if (sessionId != null) exchange.responseHeaders.set("Mcp-Session-Id", sessionId)
        if (responses.isEmpty()) {
            exchange.sendResponseHeaders(202, -1)
            return
        }
        val payload: Any = if (batch) responses else responses.first()
        sendJson(exchange, 200, payload)
    }

    /* --------------------------------- plumbing ------------------------------- */

    private class TooLargeException : Exception()

    private fun readBody(exchange: HttpExchange): String {
        val declared = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && declared > MAX_BODY_BYTES) throw TooLargeException()
        val buffer = java.io.ByteArrayOutputStream(8192)
        exchange.requestBody.use { input ->
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                buffer.write(chunk, 0, read)
                if (buffer.size() > MAX_BODY_BYTES) throw TooLargeException()
            }
        }
        return buffer.toByteArray().toString(StandardCharsets.UTF_8)
    }

    private fun sendJson(exchange: HttpExchange, status: Int, body: Any?) {
        val text = Json.stringify(body)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** Compare two secrets without leaking their common prefix through timing. */
    private fun constantTimeEquals(left: String, right: String): Boolean {
        val a = left.toByteArray(StandardCharsets.UTF_8)
        val b = right.toByteArray(StandardCharsets.UTF_8)
        if (a.size != b.size) return false
        var difference = 0
        for (index in a.indices) difference = difference or (a[index].toInt() xor b[index].toInt())
        return difference == 0
    }

    companion object {
        /** Bodies are JSON-RPC documents; a legitimate one never approaches this. */
        const val MAX_BODY_BYTES: Int = 8 * 1024 * 1024

        const val DEFAULT_INSTRUCTIONS: String =
            "Editor tools from JetBrains IDEs: the workspace, the active selection, file reads, " +
                "diagnostics, symbols, definitions, references, hovers, code actions, and a review diff."

        /** A factory for named daemon threads, so the IDE's pools stay untouched. */
        fun daemonFactory(prefix: String): ThreadFactory {
            val counter = AtomicInteger(0)
            return ThreadFactory { runnable ->
                Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }
}
