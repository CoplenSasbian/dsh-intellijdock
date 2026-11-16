package cc.futurvo.ideadock.contract

/**
 * A small glob matcher for the file tools.
 *
 * The MCP schemas speak globs, and the tools have to apply them without pulling a
 * dependency or spinning up the IDE's index machinery. Supported: `*` (inside one
 * segment), `**` (any number of segments), `?`, and `{a,b}` alternation. Matching
 * is case-insensitive on Windows, like every other path comparison in this plugin.
 */
object Glob {

    /** Compile a glob into a predicate over `/`-separated relative paths. */
    fun matcher(pattern: String, windows: Boolean = Paths.isWindows()): (String) -> Boolean {
        val normalised = pattern.replace('\\', '/').trim()
        if (normalised.isEmpty() || normalised == "**/*" || normalised == "**" || normalised == "*") {
            return { true }
        }
        val regex = Regex(
            "^" + toRegexSource(normalised) + "$",
            if (windows) setOf(RegexOption.IGNORE_CASE) else emptySet()
        )
        return { path -> regex.matches(path.replace('\\', '/').trimStart('/')) }
    }

    /** Whether `path` matches `pattern`. */
    fun matches(pattern: String, path: String, windows: Boolean = Paths.isWindows()): Boolean =
        matcher(pattern, windows)(path)

    /** Translate the supported glob syntax into a regular expression. */
    internal fun toRegexSource(pattern: String): String {
        val out = StringBuilder(pattern.length * 2)
        var index = 0
        while (index < pattern.length) {
            val character = pattern[index]
            when {
                // `**/` must also match "no directory at all".
                character == '*' && index + 1 < pattern.length && pattern[index + 1] == '*' -> {
                    val followedBySeparator = index + 2 < pattern.length && pattern[index + 2] == '/'
                    if (followedBySeparator) {
                        out.append("(?:.*/)?")
                        index += 3
                    } else {
                        out.append(".*")
                        index += 2
                    }
                }
                character == '*' -> {
                    out.append("[^/]*")
                    index += 1
                }
                character == '?' -> {
                    out.append("[^/]")
                    index += 1
                }
                character == '{' -> {
                    val close = pattern.indexOf('}', index)
                    if (close > index) {
                        val options = pattern.substring(index + 1, close).split(',')
                        out.append("(?:")
                        out.append(options.joinToString("|") { Regex.escape(it.trim()) })
                        out.append(')')
                        index = close + 1
                    } else {
                        out.append("\\{")
                        index += 1
                    }
                }
                character == '/' -> {
                    out.append('/')
                    index += 1
                }
                character.isLetterOrDigit() && character.code < 128 -> {
                    out.append(character)
                    index += 1
                }
                else -> {
                    out.append(Regex.escape(character.toString()))
                    index += 1
                }
            }
        }
        return out.toString()
    }
}
