package cc.futurvo.ideadock.ui

import com.intellij.DynamicBundle
import java.util.Locale

/**
 * The plugin's message bundle.
 *
 * `DynamicBundle` (not a bare `ResourceBundle`) so the platform's language
 * setting and the plugin's own `<resource-bundle>` work, and so a missing key
 * fails loudly in development instead of showing `!key!` in the UI.
 */
object DshDockBundle : DynamicBundle("messages.DshDockBundle") {

    fun message(key: String, vararg params: Any?): String = getMessage(key, *params)

    /** The `<html lang>` value for the generated pages. */
    fun localeLanguage(): String = Locale.getDefault().toLanguageTag()
}
