package cc.futurvo.ideadock.editor

import com.intellij.openapi.application.ApplicationInfo

/**
 * Which JetBrains IDE this is, as a short name used in tool names.
 *
 * One plugin runs in IDEA, CLion, PyCharm, GoLand, WebStorm, Rider and the rest,
 * and a session may have several of them connected. The MCP namespace becomes
 * `mcp__idea_read_file__…`-style (`mcp__<name>__<tool>`), so naming every IDE
 * `intellij` made four docks indistinguishable in the tool list. The slug is the
 * product, not the vendor: `idea`, `clion`, `pycharm`, `goland`, `webstorm`,
 * `phpstorm`, `rubymine`, `rider`, `datagrip`, `rustrover`, `aqua`, `appcode`.
 *
 * The mapping is a pure function so it can be unit-tested without an IDE; the
 * lookup of the real product code happens once, lazily.
 */
object IdeIdentity {

    /** This IDE's slug, e.g. `clion`. */
    val slug: String by lazy {
        val code = runCatching { ApplicationInfo.getInstance().build.productCode }.getOrNull()
        val name = runCatching { ApplicationInfo.getInstance().versionName }.getOrNull()
        slugOf(code, name)
    }

    /**
     * Map a product code (and the version name, as a fallback) to a slug.
     *
     * @param code `ApplicationInfo.build.productCode`: `IC`, `IU`, `CL`, `PY`, …
     * @param name `ApplicationInfo.versionName`: `IntelliJ IDEA`, `CLion`, …
     * @return a lowercase slug; `intellij` when neither is usable.
     */
    fun slugOf(code: String?, name: String?): String {
        val byCode = CODE_SLUGS[code?.trim()?.uppercase().orEmpty()]
        if (byCode != null) return byCode
        val byName = name?.trim()?.lowercase().orEmpty()
        if (byName.isNotEmpty()) {
            NAME_SLUGS[byName]?.let { return it }
            val cleaned = byName.filter { it.isLetterOrDigit() }
            if (cleaned.isNotEmpty()) return cleaned
        }
        return FALLBACK
    }

    /** The slug when nothing identifies the product. */
    const val FALLBACK: String = "intellij"

    /** Product codes from `ApplicationInfo.build.productCode`. */
    private val CODE_SLUGS = mapOf(
        "IC" to "idea",
        "IU" to "idea",
        "CL" to "clion",
        "PY" to "pycharm",
        "PC" to "pycharm",
        "GO" to "goland",
        "WS" to "webstorm",
        "PS" to "phpstorm",
        "RM" to "rubymine",
        "RD" to "rider",
        "DG" to "datagrip",
        "RS" to "rustrover",
        "RR" to "rustrover",
        "QA" to "aqua",
        "AQ" to "aqua",
        "AC" to "appcode",
        "DS" to "dataspell",
        "MPS" to "mps"
    )

    /** Version names that are not just the product with spaces removed. */
    private val NAME_SLUGS = mapOf(
        "intellij idea" to "idea",
        "intellij idea community edition" to "idea",
        "intellij idea ultimate edition" to "idea",
        "pycharm community edition" to "pycharm",
        "pycharm professional edition" to "pycharm",
        "webstorm" to "webstorm",
        "phpstorm" to "phpstorm",
        "rubymine" to "rubymine",
        "goland" to "goland",
        "clion" to "clion",
        "rider" to "rider",
        "datagrip" to "datagrip",
        "rustrover" to "rustrover",
        "aqua" to "aqua",
        "appcode" to "appcode",
        "dataspell" to "dataspell"
    )
}
