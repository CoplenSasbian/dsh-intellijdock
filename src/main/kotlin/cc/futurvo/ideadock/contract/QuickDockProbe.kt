package cc.futurvo.ideadock.contract

import java.io.IOException
import java.net.ConnectException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/** What went wrong when the listener did not answer as QuickDock. */
enum class ProbeFailure(val id: String) {
    REFUSED("refused"),
    TIMEOUT("timeout"),
    UNREACHABLE("unreachable"),
    HTTP("http"),
    JSON("json"),
    FOREIGN("foreign"),
    DISABLED("disabled");

    companion object {
        fun fromId(id: String?): ProbeFailure? = entries.firstOrNull { it.id == id }
    }
}

/** The part of the `mcpBridge` manifest this client reads. */
data class McpBridge(
    val enabled: Boolean,
    val origin: String?,
    val route: String?,
    val tokenRequired: Boolean
)

/** Capability manifest served by `GET /quickdock/api/meta`. */
data class QuickDockMeta(
    val name: String,
    val apiVersion: Long?,
    val version: String?,
    val capabilities: List<String>,
    val views: List<String>,
    val mcpBridge: McpBridge?
) {
    /** Whether this build can serve an embedded IDE view. */
    val supportsEmbeddedView: Boolean get() = capabilities.contains("app-proxy")

    /** Whether the dock's title bar can host the editor-tools switch. */
    val supportsMcpBridge: Boolean get() = mcpBridge != null

    companion object {
        /** Parse a manifest; `null` when the body is not a QuickDock manifest. */
        fun parse(body: Map<String, Any?>?): QuickDockMeta? {
            if (body == null) return null
            val name = Json.str(body, "name") ?: return null
            val bridge = Json.obj(body, "mcpBridge")?.let {
                McpBridge(
                    enabled = Json.bool(it, "enabled") ?: false,
                    origin = Json.str(it, "origin"),
                    route = Json.str(it, "route"),
                    tokenRequired = Json.bool(it, "tokenRequired") ?: false
                )
            }
            return QuickDockMeta(
                name = name,
                apiVersion = Json.num(body, "apiVersion"),
                version = Json.str(body, "version"),
                capabilities = Json.stringList(body, "capabilities").orEmpty(),
                views = Json.stringList(body, "views").orEmpty(),
                mcpBridge = bridge
            )
        }
    }
}

/** One probe of the listener. */
data class ProbeResult(
    val ok: Boolean,
    val port: Int,
    val meta: QuickDockMeta? = null,
    val failure: ProbeFailure? = null,
    /** Short technical detail for logs and tooltips (error code, status line). */
    val detail: String = "",
    val elapsedMs: Long = 0
) {
    /** Whether the dock page can be embedded at all. */
    val embeddable: Boolean get() = ok && meta != null && meta.supportsEmbeddedView
}

/**
 * Ask the listener who it is.
 *
 * `GET /quickdock/api/meta` is the plugin's manifest, so a 200 whose `name` is
 * `dsh-quickdock` proves both that something is listening and that it is the
 * plugin - not an unrelated server that happens to own the port.
 *
 * The probe never throws: a failure is an answer, because "DSH is not up yet" is
 * a normal state that the dock renders as a card.
 */
class QuickDockProbe(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val client: HttpClient = defaultClient(timeoutMs)
) {

    /** One probe; never throws. */
    fun probe(port: Int): ProbeResult {
        val started = System.nanoTime()
        fun finish(
            ok: Boolean,
            meta: QuickDockMeta? = null,
            failure: ProbeFailure? = null,
            detail: String = ""
        ) = ProbeResult(ok, port, meta, failure, detail, (System.nanoTime() - started) / 1_000_000)

        if (port <= 0 || !QuickDockPorts.isValidPort(port)) {
            return finish(ok = false, failure = ProbeFailure.DISABLED, detail = port.toString())
        }
        return try {
            val request = HttpRequest.newBuilder(URI.create(QuickDockUrls.metaUrl(port)))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("accept", "application/json")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            if (response.statusCode() != 200) {
                return finish(false, failure = ProbeFailure.HTTP, detail = "HTTP ${response.statusCode()}")
            }
            val body = Json.parseObject(response.body())
                ?: return finish(false, failure = ProbeFailure.JSON, detail = "invalid JSON")
            val meta = QuickDockMeta.parse(body)
                ?: return finish(false, failure = ProbeFailure.JSON, detail = "no name field")
            if (meta.name != QuickDockPorts.PLUGIN_NAME) {
                return finish(false, failure = ProbeFailure.FOREIGN, detail = meta.name.ifBlank { "unknown server" })
            }
            finish(true, meta = meta, detail = meta.version ?: "ok")
        } catch (error: HttpTimeoutException) {
            finish(false, failure = ProbeFailure.TIMEOUT, detail = "no answer in $timeoutMs ms")
        } catch (error: ConnectException) {
            finish(false, failure = ProbeFailure.REFUSED, detail = "connection refused")
        } catch (error: IOException) {
            val message = error.message.orEmpty()
            val refused = message.contains("refused", ignoreCase = true) ||
                message.contains("Connection refused", ignoreCase = true)
            finish(
                ok = false,
                failure = if (refused) ProbeFailure.REFUSED else ProbeFailure.UNREACHABLE,
                detail = message.ifBlank { error.javaClass.simpleName }
            )
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            finish(false, failure = ProbeFailure.UNREACHABLE, detail = "interrupted")
        } catch (error: RuntimeException) {
            finish(false, failure = ProbeFailure.UNREACHABLE, detail = error.message ?: "probe failed")
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 1_500

        /** A client that never goes through a proxy: the dock is on loopback. */
        fun defaultClient(timeoutMs: Long = DEFAULT_TIMEOUT_MS): HttpClient =
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(LoopbackProxySelector)
                .build()
    }
}

/** A proxy selector that never proxies: everything here is 127.0.0.1. */
object LoopbackProxySelector : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> = listOf(Proxy.NO_PROXY)
    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        // Nothing to do: a loopback connection that fails is reported by the probe.
    }
}
