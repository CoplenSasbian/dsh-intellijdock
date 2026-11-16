package cc.futurvo.ideadock.editor

/**
 * Whether an on-disk change is worth showing to the user.
 *
 * A port of `dsh-vsdock/src/review.ts`, kept free of IDE types so the rules are
 * covered by plain JUnit tests: the file has to be inside a folder registered
 * with DSH, must not be a build/product path or a binary, must not be the buffer
 * the user is typing in, and an earlier version has to exist to compare against.
 */
object ReviewDecision {

    /** Directories whose contents are generated, vendored or not text. */
    private val IGNORED_SEGMENTS = setOf(
        "node_modules", ".git", ".svn", ".hg", "out", "dist", "build", "coverage",
        "tmp", "temp", ".cache", ".next", ".nuxt", ".output", ".turbo", ".venv",
        "venv", "__pycache__", "target", "obj", "bin", ".gradle", ".idea", "idea-sandbox",
        ".vscode-test", ".electron-test"
    )

    /** Extensions a diff editor cannot show usefully. */
    private val BINARY_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "ico", "bmp", "webp", "avif", "tiff",
        "pdf", "zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "tar",
        "exe", "dll", "node", "so", "dylib", "class", "jar", "wasm", "asar", "vsix", "map",
        "woff", "woff2", "ttf", "otf", "eot",
        "mp3", "mp4", "mov", "wav", "webm", "avi", "mkv",
        "icns", "db", "sqlite", "sqlite3", "lock"
    )

    /** What the watcher knows about one write when it decides. */
    data class Input(
        val path: String,
        val folders: List<String>,
        /** Absolute folders currently registered with DSH (empty means "not paired"). */
        val paired: List<String>,
        /** When the IDE last saved this path (epoch ms), if it did recently. */
        val selfSavedAt: Long?,
        /** Whether an editor buffer for this path has unsaved changes. */
        val dirty: Boolean,
        /** Whether an earlier version is known, so there is something to compare. */
        val hasBefore: Boolean,
        /** Size of the new content in bytes. */
        val bytes: Int,
        val now: Long,
        val selfSaveGraceMs: Long,
        val maxBytes: Int
    )

    /** The verdict: review it, or why not. */
    sealed interface Verdict {
        data object Review : Verdict
        data class Skip(val reason: String) : Verdict
    }

    /** Whether a workspace-relative path is generated, vendored or binary. */
    fun isIgnoredPath(relativePath: String): Boolean {
        val segments = relativePath.split(Regex("[\\\\/]+")).filter { it.isNotEmpty() }
        if (segments.isEmpty()) return true
        if (segments.any { it.lowercase() in IGNORED_SEGMENTS }) return true
        val name = segments.last()
        if (name.endsWith(".min.js") || name.endsWith(".min.css")) return true
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return false
        return name.substring(dot + 1).lowercase() in BINARY_EXTENSIONS
    }

    /** Decide whether one change opens a review. */
    fun decide(input: Input, windows: Boolean = cc.futurvo.ideadock.contract.Paths.isWindows()): Verdict {
        if (input.selfSavedAt != null && input.now - input.selfSavedAt < input.selfSaveGraceMs) {
            return Verdict.Skip("the IDE saved it")
        }
        if (input.dirty) return Verdict.Skip("the buffer has unsaved changes")
        if (input.paired.isEmpty()) return Verdict.Skip("no folder is registered with DSH")
        if (input.paired.none { cc.futurvo.ideadock.contract.Paths.isInsideDirectory(input.path, it, windows) }) {
            return Verdict.Skip("outside the folders DSH may change")
        }
        if (input.bytes > input.maxBytes) return Verdict.Skip("file is too large")
        if (!input.hasBefore) return Verdict.Skip("no earlier version to compare with")
        return Verdict.Review
    }

    /** Whether `path` is one of `directories` or sits below one. */
    fun insideAny(path: String, directories: List<String>, windows: Boolean = cc.futurvo.ideadock.contract.Paths.isWindows()): Boolean =
        directories.any { cc.futurvo.ideadock.contract.Paths.isInsideDirectory(path, it, windows) }
}
