package cc.futurvo.ideadock.contract

/**
 * The URLs of QuickDock's loopback listener.
 *
 * `view` is QuickDock's client discriminator and it decides three things at once:
 * whether the desktop window chrome is
 * trimmed, whether the app is served through the Host's app-proxy, and what the
 * embedded app thinks its host is. `intellij` is the name this client asks for;
 * a QuickDock build that predates it (no `intellij` in `meta.views`) still knows
 * `vscode`, which trims the chrome and routes open-file clicks the same way - the
 * only loss is the `type` a dock registers under.
 */
object QuickDockUrls {

    const val HOST: String = "127.0.0.1"

    /** The view name this client asks for. */
    const val IDE_VIEW: String = "intellij"

    /** The editor view an older QuickDock build knows (same chrome, same transport). */
    const val LEGACY_IDE_VIEW: String = "vscode"

    /** The view that keeps the trimmed bar but embeds the app directly (a browser tab). */
    const val BROWSER_VIEW: String = "browser"

    fun baseUrl(port: Int): String = "http://$HOST:$port"

    /**
     * The view to request from the QuickDock that answered.
     *
     * Negotiation happens at probe time: `meta.views` is the Host's own whitelist,
     * and a name missing there is a 400, so falling back is not optional. An
     * unknown or older manifest advertises no views at all, which also means
     * `vscode`.
     */
    fun viewFor(meta: QuickDockMeta?): String {
        val views = meta?.views.orEmpty()
        return if (views.contains(IDE_VIEW)) IDE_VIEW else LEGACY_IDE_VIEW
    }

    /** The shell page for one view, without the dock-specific parameters. */
    fun pageUrl(port: Int, view: String = IDE_VIEW): String = "${baseUrl(port)}/quickdock?view=$view"

    /** The capability manifest. */
    fun metaUrl(port: Int): String = "${baseUrl(port)}/quickdock/api/meta"

    /** One pairing action on the dock's listener. */
    fun dockApiUrl(port: Int, action: String): String = "${baseUrl(port)}/quickdock/api/$action"

    /**
     * The shell page for one editor window.
     *
     * `dock` names this window's dock context, `mcp` is "present means the bar
     * shows the switch" (its value is only the initial state), and `workspace`
     * repeats once per content root. Every `workspace` already in the base URL is
     * dropped first, so a reload cannot accumulate duplicates.
     */
    fun shellUrl(
        port: Int,
        dockId: String,
        mcp: Boolean,
        workspaceFolders: List<String>,
        view: String = IDE_VIEW
    ): String {
        val out = StringBuilder(pageUrl(port, view))
        if (dockId.isNotBlank()) out.append("&dock=").append(encode(dockId))
        out.append("&mcp=").append(if (mcp) "1" else "0")
        for (folder in workspaceFolders) {
            if (folder.isBlank()) continue
            out.append("&workspace=").append(encode(folder))
        }
        return out.toString()
    }

    /**
     * Percent-encode one query component.
     *
     * `URLEncoder` is a form encoder (it writes a space as `+`), which would
     * corrupt a Windows path such as `C:\work\my project`; this keeps the
     * unreserved characters and encodes everything else as UTF-8.
     */
    fun encode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            val character = byte.toInt() and 0xFF
            val safe = character in 'a'.code..'z'.code ||
                character in 'A'.code..'Z'.code ||
                character in '0'.code..'9'.code ||
                character == '-'.code || character == '_'.code ||
                character == '.'.code || character == '~'.code
            if (safe) {
                out.append(character.toChar())
            } else {
                out.append('%')
                out.append("0123456789ABCDEF"[character shr 4])
                out.append("0123456789ABCDEF"[character and 0x0F])
            }
        }
        return out.toString()
    }
}
