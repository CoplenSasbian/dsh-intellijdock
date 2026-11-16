package cc.futurvo.ideadock.contract

import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.security.SecureRandom
import java.time.Duration

/**
 * Registering the editor's MCP server with QuickDock, and reading back what DSH
 * mounted.
 *
 * Three calls on QuickDock's own listener, exactly as `docs/02-contracts.md` §5
 * spells them out. A registration lives in DSH's memory, so the heartbeat in
 * [com.deepseek.dsh.idea.lifecycle.DockService] is the whole lifecycle: when DSH
 * restarts, the next beat re-registers and the tools come back.
 */
object Pairing {

    /** Namespace of the tools DSH mounts from this server (`mcp__intellij__*`). */
    const val TOOL_NAMESPACE: String = "intellij"

    /** Default loopback port for the editor's MCP server. */
    const val DEFAULT_MCP_PORT: Int = 47920

    private val random = SecureRandom()

    /** A fresh capability token: 32 random bytes, base64url, no padding. */
    fun newToken(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** What the editor asks QuickDock to mount: the bridge registration body. */
    data class Registration(
        /** Absolute directory the tools belong to. */
        val workspace: String,
        /** This server's streamable HTTP endpoint. */
        val url: String,
        /** Tool namespace; QuickDock's default is derived from the URL. */
        val name: String = TOOL_NAMESPACE,
        /** Extra headers DSH sends to the server (the bearer token). */
        val headers: Map<String, String> = emptyMap(),
        /** Build that serves these tools; QuickDock keeps it for the dock's tool panel. */
        val version: String? = null,
        /** Tool names, so the dock can show what it published. */
        val tools: List<String>? = null,
        /** How long the bridge may wait for live sessions (ms). */
        val waitMs: Long? = null
    ) {
        fun toJson(): Map<String, Any?> {
            val out = linkedMapOf<String, Any?>(
                "workspace" to workspace,
                "url" to url,
                "name" to name
            )
            if (headers.isNotEmpty()) out["headers"] = headers
            version?.let { out["version"] = it }
            tools?.let { out["tools"] = it }
            waitMs?.let { out["waitMs"] = it }
            return out
        }
    }

    /** One live session mounted on a registered server. */
    data class BridgeSession(val sessionId: String, val state: String, val error: String?)

    /** One registered server, as the bridge describes it. */
    data class BridgeServer(
        val name: String,
        val workspace: String?,
        val transport: String?,
        val url: String?,
        val sessions: List<BridgeSession>
    )

    /** The `mcp-list` answer. */
    data class BridgeList(val ok: Boolean, val workspaces: List<Entry>) {
        data class Entry(val workspace: String, val servers: List<BridgeServer>)
    }

    /** The `mcp-register` answer. */
    data class BridgeRegistration(
        val ok: Boolean,
        val replaced: Boolean,
        val sessions: List<BridgeSession>
    )

    /** One call to the dock, reduced to what a caller needs to decide next. */
    data class DockAnswer<T>(
        /** Whether the dock answered 2xx with JSON. */
        val ok: Boolean,
        /** HTTP status, or 0 when the listener did not answer at all. */
        val status: Int,
        val body: T? = null,
        /** Why it failed: an error code (connection refused) or the dock's own message. */
        val error: String? = null
    )

    /** The registration the bridge holds for one directory, when it holds one. */
    fun findRegistration(
        body: BridgeList?,
        workspace: String,
        name: String = TOOL_NAMESPACE,
        windows: Boolean = Paths.isWindows()
    ): BridgeServer? {
        val entry = body?.workspaces?.firstOrNull { Paths.samePath(it.workspace, workspace, windows) } ?: return null
        return entry.servers.firstOrNull { it.name == name }
    }

    /** Whether registering is needed: nothing mounted yet, or another endpoint. */
    fun needsRegistration(
        body: BridgeList?,
        registration: Registration,
        windows: Boolean = Paths.isWindows()
    ): Boolean {
        val current = findRegistration(body, registration.workspace, registration.name, windows)
        return current == null || current.url != registration.url
    }

    /** Parse the `mcp-list` body. */
    fun bridgeList(body: Map<String, Any?>?): BridgeList {
        val entries = Json.arr(body, "workspaces").orEmpty().mapNotNull { raw ->
            val entry = raw as? Map<String, Any?> ?: return@mapNotNull null
            val workspace = Json.str(entry, "workspace") ?: return@mapNotNull null
            val servers = Json.arr(entry, "servers").orEmpty().mapNotNull { serverRaw ->
                val server = serverRaw as? Map<String, Any?> ?: return@mapNotNull null
                BridgeServer(
                    name = Json.str(server, "name").orEmpty(),
                    workspace = Json.str(server, "workspace"),
                    transport = Json.str(server, "transport"),
                    url = Json.str(server, "url"),
                    sessions = sessions(server)
                )
            }
            BridgeList.Entry(workspace, servers)
        }
        return BridgeList(Json.bool(body, "ok") ?: true, entries)
    }

    /** Parse the `mcp-register` body. */
    fun bridgeRegistration(body: Map<String, Any?>?): BridgeRegistration = BridgeRegistration(
        ok = Json.bool(body, "ok") ?: false,
        replaced = Json.bool(body, "replaced") ?: false,
        sessions = sessions(body)
    )

    private fun sessions(body: Map<String, Any?>?): List<BridgeSession> =
        Json.arr(body, "sessions").orEmpty().mapNotNull { raw ->
            val session = raw as? Map<String, Any?> ?: return@mapNotNull null
            BridgeSession(
                sessionId = Json.str(session, "sessionId").orEmpty(),
                state = Json.str(session, "state").orEmpty(),
                error = Json.str(session, "error")
            )
        }
}

/**
 * The client half of pairing: three calls against QuickDock's loopback listener.
 *
 * A refused connection is an answer, not an exception: DSH still starting up is
 * a normal state and the next heartbeat simply tries again.
 */
class PairingClient(
    private val client: HttpClient = QuickDockProbe.defaultClient()
) {

    /** Register (or replace) this server for one workspace directory. */
    fun register(
        port: Int,
        registration: Pairing.Registration,
        timeoutMs: Long = 20_000
    ): Pairing.DockAnswer<Pairing.BridgeRegistration> {
        val answer = request(port, "mcp-register", "POST", registration.toJson(), null, timeoutMs)
        return Pairing.DockAnswer(
            answer.ok, answer.status,
            body = Pairing.bridgeRegistration(answer.body),
            error = answer.error
        )
    }

    /** Remove this server (or every server) of one workspace directory. */
    fun unregister(
        port: Int,
        workspace: String,
        name: String = Pairing.TOOL_NAMESPACE,
        timeoutMs: Long = 8_000
    ): Pairing.DockAnswer<List<String>> {
        val answer = request(
            port, "mcp-unregister", "POST",
            linkedMapOf("workspace" to workspace, "name" to name), null, timeoutMs
        )
        return Pairing.DockAnswer(
            answer.ok, answer.status,
            body = Json.stringList(answer.body, "removed"),
            error = answer.error
        )
    }

    /** What DSH currently mounts for one workspace directory. */
    fun list(
        port: Int,
        workspace: String,
        timeoutMs: Long = 5_000
    ): Pairing.DockAnswer<Pairing.BridgeList> {
        val answer = request(port, "mcp-list", "GET", null, mapOf("workspace" to workspace), timeoutMs)
        return Pairing.DockAnswer(
            answer.ok, answer.status,
            body = Pairing.bridgeList(answer.body),
            error = answer.error
        )
    }

    private data class RawAnswer(
        val ok: Boolean,
        val status: Int,
        val body: Map<String, Any?>? = null,
        val error: String? = null
    )

    private fun request(
        port: Int,
        action: String,
        method: String,
        body: Map<String, Any?>?,
        query: Map<String, String>?,
        timeoutMs: Long
    ): RawAnswer {
        val url = StringBuilder(QuickDockUrls.dockApiUrl(port, action))
        query?.let { parameters ->
            var first = true
            for ((key, value) in parameters) {
                url.append(if (first) '?' else '&')
                first = false
                url.append(QuickDockUrls.encode(key)).append('=').append(QuickDockUrls.encode(value))
            }
        }
        val payload = body?.let { Json.stringify(it).toByteArray(Charsets.UTF_8) }
        val builder = HttpRequest.newBuilder(URI.create(url.toString()))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("accept", "application/json")
        if (payload != null) {
            builder.header("content-type", "application/json")
            builder.method(method, HttpRequest.BodyPublishers.ofByteArray(payload))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }

        return try {
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            val status = response.statusCode()
            val text = response.body()
            val parsed = if (text.isNullOrBlank()) null else Json.parseObject(text)
            if (status !in 200..299) {
                val message = parsed?.let { Json.str(it, "error") }
                return RawAnswer(false, status, parsed, message ?: "HTTP $status")
            }
            if (!text.isNullOrBlank() && parsed == null) {
                return RawAnswer(false, status, null, "the dock answered with something other than JSON")
            }
            RawAnswer(true, status, parsed)
        } catch (error: HttpTimeoutException) {
            RawAnswer(false, 0, null, "no answer in $timeoutMs ms")
        } catch (error: ConnectException) {
            RawAnswer(false, 0, null, "connection refused")
        } catch (error: IOException) {
            RawAnswer(false, 0, null, error.message ?: error.javaClass.simpleName)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            RawAnswer(false, 0, null, "interrupted")
        }
    }
}
