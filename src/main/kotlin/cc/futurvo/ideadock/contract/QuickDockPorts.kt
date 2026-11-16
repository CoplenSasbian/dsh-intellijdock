package cc.futurvo.ideadock.contract

import java.io.File

/**
 * Where QuickDock keeps its state, and which port is in effect.
 *
 * A verbatim reimplementation of `dsh-vsdock/src/quickdock.ts` (the first half),
 * with the same precedence, because the two clients must agree about where the
 * listener is. No IDE types here: the whole file is exercised by plain JUnit.
 */
object QuickDockPorts {

    /** Loopback port the plugin binds when nothing says otherwise (`host/constants.js`). */
    const val DEFAULT_PORT: Int = 47910

    /** The manifest name the listen endpoint must report for us to trust it. */
    const val PLUGIN_NAME: String = "dsh-quickdock"

    /** Which place a detected port came from. */
    enum class Source(val id: String) {
        SETTING("setting"),
        CONFIG_FILE("config-file"),
        LOG("log"),
        DEFAULT("default")
    }

    /** A port found in QuickDock's own state, and the file it came from. */
    data class Detection(val port: Int, val source: Source, val file: File)

    /** The port this client will talk to, and why. */
    data class PortInfo(val port: Int, val source: Source, val file: File? = null)

    /** The two filesystem calls detection needs; injected by the tests. */
    interface FsLike {
        fun exists(file: File): Boolean
        fun readText(file: File): String?
    }

    val defaultFs: FsLike = object : FsLike {
        override fun exists(file: File): Boolean = file.exists()
        override fun readText(file: File): String? = try {
            file.readText(Charsets.UTF_8)
        } catch (error: Exception) {
            null
        }
    }

    /** `DSH_QUICKDOCK_STATE_DIR`, else `LOCALAPPDATA`, else the temp directory. */
    fun stateDir(env: Map<String, String> = System.getenv()): File {
        val custom = env["DSH_QUICKDOCK_STATE_DIR"]
        if (!custom.isNullOrBlank()) return File(custom.trim())
        val base = env["LOCALAPPDATA"]?.takeIf { it.isNotBlank() }
            ?: env["TEMP"]?.takeIf { it.isNotBlank() }
            ?: System.getProperty("java.io.tmpdir", ".")
        return File(base, PLUGIN_NAME)
    }

    /** Absolute path of the plugin's `config.json`. */
    fun configFile(env: Map<String, String> = System.getenv()): File = File(stateDir(env), "config.json")

    /** Absolute path of the plugin's `host.log`. */
    fun hostLogFile(env: Map<String, String> = System.getenv()): File = File(stateDir(env), "host.log")

    /** Whether a number can be a loopback port. 0 is valid and means "off". */
    fun isValidPort(value: Int?): Boolean = value != null && value in 0..65535

    /** Whether a number can be stored in the port setting (0 is not a port). */
    fun isUsablePort(value: Int?): Boolean = isValidPort(value) && value!! > 0

    /** The port recorded in a `config.json` body, if any. */
    fun readPortFromConfig(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val body = Json.parseObject(text) ?: return null
        val raw = body["port"] ?: return null
        val value = when (raw) {
            is Number -> raw.toDouble().toInt()
            is String -> raw.trim().toDoubleOrNull()?.toInt()
            else -> null
        }
        return if (isUsablePort(value) || value == 0) value else null
    }

    /** The last port the plugin logged as listening (`server listening on http://127.0.0.1:<port>`). */
    fun readPortFromLog(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val pattern = Regex("server listening on http://127\\.0\\.0\\.1:(\\d+)")
        var last: Int? = null
        for (match in pattern.findAll(text)) {
            val port = match.groupValues[1].toIntOrNull() ?: continue
            if (isValidPort(port)) last = port
        }
        return last
    }

    /**
     * Find the port the running plugin actually uses.
     *
     * `config.json` wins: it is what the plugin writes whenever the user moves
     * the port. `host.log` is the fallback for an install whose config file has
     * not been written yet.
     */
    fun detect(env: Map<String, String> = System.getenv(), fs: FsLike = defaultFs): Detection? {
        val config = configFile(env)
        if (fs.exists(config)) {
            val port = readPortFromConfig(fs.readText(config))
            if (port != null) return Detection(port, Source.CONFIG_FILE, config)
        }
        val log = hostLogFile(env)
        if (fs.exists(log)) {
            val port = readPortFromLog(fs.readText(log))
            if (port != null) return Detection(port, Source.LOG, log)
        }
        return null
    }

    /**
     * Decide which port to use: an explicitly configured one, else QuickDock's
     * own value (while following it is on), else the compiled-in default.
     */
    fun resolvePort(setting: Int?, follow: Boolean, detected: Detection?): PortInfo {
        if (isUsablePort(setting)) return PortInfo(setting!!, Source.SETTING)
        if (follow && detected != null) return PortInfo(detected.port, detected.source, detected.file)
        return PortInfo(DEFAULT_PORT, Source.DEFAULT)
    }
}
