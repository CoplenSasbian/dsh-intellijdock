package cc.futurvo.ideadock.contract

import java.io.File

/**
 * Path rules shared by pairing, the dock bar and the review watcher.
 *
 * Three places compare directories - which folder a registration belongs to,
 * what the dock warns about, and what the review watcher may look at - and all
 * three have to agree with QuickDock's own rule: Windows compares
 * case-insensitively, and both separators are the same separator.
 *
 * Pure Kotlin, so it is covered by plain JUnit tests.
 */
object Paths {

    /** `true` on Windows; injectable so the tests can exercise both rules. */
    fun isWindows(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
        osName.lowercase().contains("win")

    /**
     * The comparison form of a path: forward slashes, no trailing separator,
     * lower case on Windows. A bare root collapses to `c:` or `/`, which is
     * enough for both equality and containment.
     */
    fun comparisonPath(value: String, windows: Boolean = isWindows()): String {
        var text = value.replace('\\', '/').trimEnd('/')
        if (text.isEmpty()) text = "/"
        return if (windows) text.lowercase() else text
    }

    /** Whether two paths name the same file or directory. */
    fun samePath(left: String?, right: String?, windows: Boolean = isWindows()): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return false
        return comparisonPath(left, windows) == comparisonPath(right, windows)
    }

    /** Whether `path` is `directory` itself or sits below it. */
    fun isInsideDirectory(path: String?, directory: String?, windows: Boolean = isWindows()): Boolean {
        if (path.isNullOrBlank() || directory.isNullOrBlank()) return false
        val child = comparisonPath(path, windows)
        val parent = comparisonPath(directory, windows)
        if (child == parent) return true
        return child.startsWith("$parent/")
    }

    /** Whether a path is absolute on the current platform (both separators accepted). */
    fun isAbsolute(path: String, windows: Boolean = isWindows()): Boolean {
        val text = path.trim()
        if (text.isEmpty()) return false
        if (text.startsWith("/") || text.startsWith("\\\\")) return true
        return windows && Regex("^[A-Za-z]:[\\\\/].*").matches(text)
    }

    /** Resolve `path` against `folder` when it is relative; otherwise return it unchanged. */
    fun resolve(folder: String, path: String, windows: Boolean = isWindows()): String {
        if (isAbsolute(path, windows)) return path
        val base = folder.trimEnd('\\', '/')
        val child = path.trimStart('\\', '/')
        return "$base${File.separator}$child"
    }

    /** The last segment of a path, used as a display name. */
    fun fileName(path: String): String {
        val text = path.replace('\\', '/').trimEnd('/')
        val index = text.lastIndexOf('/')
        return if (index >= 0) text.substring(index + 1) else text
    }

    /** The parent directory of a path, or null at a root. */
    fun parent(path: String): String? {
        val text = path.replace('\\', '/').trimEnd('/')
        val index = text.lastIndexOf('/')
        if (index <= 0) return null
        return text.substring(0, index)
    }
}
