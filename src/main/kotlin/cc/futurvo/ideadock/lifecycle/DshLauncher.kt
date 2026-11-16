package cc.futurvo.ideadock.lifecycle

import java.io.File

/**
 * Starting DSH from the IDE, the pure half.
 *
 * A verbatim port of `dsh-vsdock/src/dsh.ts`: `dsh --profile <name>` is the only
 * command line that boots a profile, the profile list is "directories under
 * `$DSH_HOME/profiles` that carry a `package.json`", and a path with spaces has
 * to survive the shell. Everything here is testable without an IDE; the Run
 * tool window, the picker and the waiting loop live in [DockService].
 */
object DshLauncher {

    /** Profile names `dsh --profile <name>` accepts. */
    private val PROFILE_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    /** Characters that need no quoting in either shell. */
    private val SAFE = Regex("^[\\w@%+=:,./\\\\-]+$")

    /** `$DSH_HOME`, else `~/.dsh`, where `profiles/` lives. */
    fun dshHome(env: Map<String, String> = System.getenv()): File {
        val custom = env["DSH_HOME"]
        if (!custom.isNullOrBlank()) return File(custom.trim())
        val home = env["USERPROFILE"]?.takeIf { it.isNotBlank() }
            ?: env["HOME"]?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home", ".")
        return File(home, ".dsh")
    }

    /**
     * Quote one executable for the shell.
     *
     * Only a path with spaces needs quoting; Windows gets double quotes (the
     * `cmd` convention) and POSIX single quotes, so a path such as
     * `C:\Users\me\my tools\dsh.cmd` survives.
     */
    fun quoteExecutable(command: String, windows: Boolean = isWindows()): String {
        val text = command.trim()
        if (text.isEmpty()) return ""
        if (SAFE.matches(text)) return text
        return if (windows) {
            "\"" + text.replace("\"", "\"\"") + "\""
        } else {
            "'" + text.replace("'", "'\\''") + "'"
        }
    }

    /** Whether a configured command is a path rather than a bare name. */
    fun looksLikePath(command: String): Boolean {
        val text = command.trim()
        return Regex("^[A-Za-z]:[\\\\/].*").matches(text) || text.startsWith("\\\\") || text.startsWith("/")
    }

    /**
     * The line the terminal (or `cmd /c`) receives.
     * @throws IllegalArgumentException when the profile name would not survive.
     */
    fun commandLine(
        command: String,
        profile: String,
        args: String,
        windows: Boolean = isWindows()
    ): String {
        val executable = quoteExecutable(command.ifBlank { "dsh" }, windows)
        val parts = ArrayList<String>(3)
        parts += executable
        val trimmedProfile = profile.trim()
        if (trimmedProfile.isNotEmpty()) {
            require(PROFILE_PATTERN.matches(trimmedProfile)) { "invalid DSH profile name: $trimmedProfile" }
            parts += "--profile"
            parts += trimmedProfile
        }
        val trimmedArgs = args.trim()
        if (trimmedArgs.isNotEmpty()) parts += trimmedArgs
        return parts.joinToString(" ")
    }

    /**
     * Profile names under a DSH home directory, sorted.
     *
     * `dsh --profile <name>` boots `$DSH_HOME/profiles/<name>`, so the picker
     * offers exactly the directories that carry a `package.json` (the shared
     * `node_modules` and dot directories are not profiles).
     */
    fun listProfiles(home: File): List<String> {
        val profiles = File(home, "profiles")
        val entries = profiles.listFiles() ?: return emptyList()
        return entries
            .filter { it.isDirectory }
            .filter { !it.name.startsWith('.') && it.name != "node_modules" }
            .filter { File(it, "package.json").isFile }
            .map { it.name }
            .sorted()
    }

    /** The shell that runs one command line, per platform. */
    fun shellCommand(line: String, windows: Boolean = isWindows()): List<String> =
        if (windows) listOf("cmd.exe", "/c", line) else listOf("/bin/sh", "-lc", line)

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")
}
