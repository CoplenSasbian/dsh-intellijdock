package cc.futurvo.ideadock.contract

import java.security.SecureRandom

/** One editor tool: the schema DSH lists, and the code DSH calls. */
interface McpTool {
    val name: String
    val description: String
    /** JSON Schema object of the tool's arguments. */
    val inputSchema: Map<String, Any?>

    /** Run the tool. Throwing is fine: the protocol turns it into `isError: true`. */
    fun call(arguments: Map<String, Any?>): Any?
}

/** One JSON-RPC message answered. */
data class McpAnswer(
    /** The reply document, or null for a notification. */
    val response: Map<String, Any?>?,
    /** Set when this message opened a session (`initialize`). */
    val sessionId: String? = null
)

/**
 * The JSON-RPC surface of the editor-side MCP server.
 *
 * This is the part of `dsh-vsdock/src/mcp.ts` that is protocol and nothing else,
 * so it is pure Kotlin: `initialize`, `ping`, `tools/list`, `tools/call`,
 * notifications without a reply, batches answered message by message.
 */
object McpProtocol {

    /** Protocol revisions this server can answer. */
    val PROTOCOL_VERSIONS: List<String> = listOf("2024-11-05", "2025-03-26", "2025-06-18")

    /** The revision used when the client asks for one we do not know. */
    const val DEFAULT_PROTOCOL: String = "2025-06-18"

    /** What the client sees in `serverInfo`. */
    const val SERVER_NAME: String = "dsh-ideadock"

    private val random = SecureRandom()

    /** A fresh session id: 16 random bytes as hex. */
    fun newSessionId(): String = ByteArray(16).also { random.nextBytes(it) }.toHex()

    /** The revision this server answers with. */
    fun supportedProtocol(requested: Any?): String {
        val text = requested?.toString().orEmpty()
        return if (text in PROTOCOL_VERSIONS) text else DEFAULT_PROTOCOL
    }

    /** The `tools/list` payload, in registration order. */
    fun toolListing(tools: Collection<McpTool>): List<Map<String, Any?>> = tools.map { tool ->
        linkedMapOf(
            "name" to tool.name,
            "description" to tool.description,
            "inputSchema" to tool.inputSchema
        )
    }

    /** Answer one JSON-RPC message; a notification produces no response. */
    fun answer(
        message: Map<String, Any?>,
        tools: Map<String, McpTool>,
        version: String,
        instructions: String
    ): McpAnswer {
        val hasId = message.containsKey("id")
        val id = message["id"]
        fun reply(result: Any?): McpAnswer {
            if (!hasId) return McpAnswer(null)
            return McpAnswer(linkedMapOf("jsonrpc" to "2.0", "id" to id, "result" to result))
        }

        fun fail(code: Int, text: String): McpAnswer {
            if (!hasId) return McpAnswer(null)
            return McpAnswer(
                linkedMapOf(
                    "jsonrpc" to "2.0",
                    "id" to id,
                    "error" to linkedMapOf("code" to code, "message" to text)
                )
            )
        }

        return when (val method = Json.str(message, "method")) {
            "initialize" -> {
                val params = Json.obj(message, "params")
                val result = linkedMapOf<String, Any?>(
                    "protocolVersion" to supportedProtocol(params?.get("protocolVersion")),
                    "capabilities" to linkedMapOf("tools" to linkedMapOf("listChanged" to false)),
                    "serverInfo" to linkedMapOf("name" to SERVER_NAME, "version" to version),
                    "instructions" to instructions
                )
                McpAnswer(
                    response = if (hasId) linkedMapOf("jsonrpc" to "2.0", "id" to id, "result" to result) else null,
                    sessionId = newSessionId()
                )
            }
            "ping" -> reply(linkedMapOf<String, Any?>())
            "tools/list" -> reply(linkedMapOf("tools" to toolListing(tools.values)))
            "tools/call" -> {
                val params = Json.obj(message, "params")
                val name = Json.str(params, "name").orEmpty()
                val tool = tools[name] ?: return fail(-32602, "unknown tool: $name")
                val arguments = Json.obj(params, "arguments").orEmpty()
                try {
                    val value = tool.call(arguments)
                    val text = when (value) {
                        null -> ""
                        is String -> value
                        else -> Json.stringify(value)
                    }
                    reply(linkedMapOf("content" to listOf(linkedMapOf("type" to "text", "text" to text))))
                } catch (error: Throwable) {
                    // A tool failure is data, not a protocol failure: the model has
                    // to see why, and a JSON-RPC error would hide the message.
                    reply(
                        linkedMapOf(
                            "content" to listOf(
                                linkedMapOf("type" to "text", "text" to "error: ${error.message ?: error.javaClass.simpleName}")
                            ),
                            "isError" to true
                        )
                    )
                }
            }
            else ->
                if (!hasId) McpAnswer(null) else fail(-32601, "method not found: $method")
        }
    }

    /** A stable digest of the tool set: names, descriptions and schemas. */
    fun fingerprint(tools: Collection<McpTool>): String {
        val text = tools.joinToString("\u0001") { "${it.name}\u0000${it.description}\u0000${Json.stringify(it.inputSchema)}" }
        return sha1Hex(text).take(12)
    }

    private fun sha1Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.toHex()
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            out.append("0123456789abcdef"[value shr 4]).append("0123456789abcdef"[value and 0x0F])
        }
        return out.toString()
    }
}
