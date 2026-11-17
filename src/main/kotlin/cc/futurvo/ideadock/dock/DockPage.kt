package cc.futurvo.ideadock.dock

import java.net.URI

/**
 * The pages the tool window can show.
 *
 * The dock itself is QuickDock's own shell page, loaded in an iframe: that page
 * already owns the compact layout, the session list and the DSH app, so this
 * layer only frames it - plus the few lines of relay its title bar needs to
 * reach the plugin (the tools switch, and "open this file in the IDE").
 *
 * QuickDock's shell page is *designed* to be embedded: the plug button only
 * renders when `window.parent !== window`, and a clicked file is posted to the
 * parent. Loading it as a top-level document would silently lose both, which is
 * the whole reason this iframe shell exists.
 *
 * The offline and "connecting" cards are plain HTML with no iframe; their
 * buttons call back through a third [com.intellij.ui.jcef.JBCefJSQuery].
 *
 * Everything here is a string builder: no IDE types, so the pages are covered by
 * plain JUnit tests (and the VS Code version's 53 headless tests had the same
 * shape).
 */
object DockPage {

    /** The colours a page needs from the IDE. */
    data class Theme(
        val bg: String,
        val fg: String,
        val border: String,
        val description: String,
        val link: String,
        val buttonBg: String,
        val buttonFg: String,
        val dark: Boolean
    )

    /** Everything the offline card shows, already resolved by the caller. */
    data class OfflineModel(
        val port: Int,
        val pageUrl: String,
        val lead: String,
        val reason: String,
        val detail: String,
        val detectedPort: Int?,
        val detectedFile: String?,
        val detectedDiffers: Boolean,
        val disabled: Boolean,
        val stale: Boolean,
        val startable: Boolean,
        val hasLog: Boolean,
        val stateDir: String,
        val steps: List<String>
    )

    /** A fully built page plus the CSS that belongs with it. */
    data class Page(val html: String)

    /** Escape text for an HTML text node or a quoted attribute. */
    fun esc(value: String): String = buildString(value.length + 8) {
        for (character in value) {
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(character)
            }
        }
    }

    /** A JS string literal, for values interpolated into an inline script. */
    fun jsString(value: String): String = buildString(value.length + 8) {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '<' -> append("\\u003c")
                else -> if (character < ' ') {
                    append("\\u").append(String.format("%04x", character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    /* --------------------------------- styles --------------------------------- */

    private fun css(theme: Theme, extra: String = ""): String = """
        :root {
          --bg: ${theme.bg};
          --fg: ${theme.fg};
          --border: ${theme.border};
          --description: ${theme.description};
          --link: ${theme.link};
          --button-bg: ${theme.buttonBg};
          --button-fg: ${theme.buttonFg};
          color-scheme: ${if (theme.dark) "dark" else "light"};
        }
        *, *::before, *::after { box-sizing: border-box; }
        html, body { height: 100%; margin: 0; }
        body {
          background: var(--bg);
          color: var(--fg);
          font: 13px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
          -webkit-font-smoothing: antialiased;
          padding: 0;
        }
        code, kbd {
          font-family: ui-monospace, "JetBrains Mono", Consolas, monospace;
          font-size: .92em;
          background: rgba(128, 128, 128, .17);
          border-radius: 4px;
          padding: 1px 5px;
          white-space: nowrap;
        }
        a { color: var(--link); text-decoration: none; cursor: pointer; }
        a:hover { text-decoration: underline; }
        $extra
    """.trimIndent()

    /* ------------------------------- the frame -------------------------------- */

    /**
     * The working page: QuickDock's shell page, full bleed.
     *
     * `toggleCall` and `openCall` are JS expressions produced by
     * `JBCefJSQuery.inject`, so the relay can reach Kotlin.
     */
    fun frame(
        pageUrl: String,
        theme: Theme,
        lang: String,
        toggleCall: String,
        openCall: String
    ): Page {
        val origin = originOf(pageUrl)
        val source = esc(pageUrl)
        val html = """<!DOCTYPE html>
<html lang="${esc(lang)}">
<head>
<meta charset="UTF-8">
<title>DSH Dock</title>
<style>
${css(
            theme,
            """
  iframe {
    display: block; width: 100%; height: 100%; border: 0;
    background: var(--bg); color-scheme: normal;
  }
"""
        )}
</style>
</head>
<body>
<iframe id="dock" src="$source" title="DSH Dock" allow="clipboard-read; clipboard-write"></iframe>
<script>
(function () {
  var frame = document.getElementById('dock');
  var origin = ${jsString(origin)};
  if (!frame || !origin) return;

  // Kotlin -> the shell page: what the title bar's tools switch should show.
  window.dshMcpState = function (on, detail) {
    try {
      frame.contentWindow.postMessage(
        { type: 'quickdock:mcp-state', on: !!on, detail: detail || '' }, origin);
    } catch (error) { /* the frame is gone */ }
  };

  window.addEventListener('message', function (event) {
    // The shell page is the only document allowed to drive us, and its origin is
    // the one in the iframe URL; event.source is unreliable for a cross-origin
    // child here, so the origin - which cannot be forged for postMessage - is the check.
    if (event.origin !== origin) return;
    var data = event.data || {};
    if (data.type === 'quickdock:mcp-toggle') { $toggleCall ; return; }
    if (data.type === 'quickdock:open-file' && typeof data.path === 'string' && data.path) {
      $openCall ;
    }
  });
})();
</script>
</body>
</html>"""
        return Page(html)
    }

    /* ------------------------------- the cards -------------------------------- */

    /** Shown while the probe runs, which is normally under a second. */
    fun connecting(port: Int, theme: Theme, lang: String, connectingText: String, hint: String): Page {
        val html = """<!DOCTYPE html>
<html lang="${esc(lang)}">
<head>
<meta charset="UTF-8">
<title>DSH Dock</title>
<style>
${css(
            theme,
            """
  body { display: flex; align-items: center; justify-content: center; }
  main { display: flex; flex-direction: column; align-items: center; gap: 14px; padding: 24px; text-align: center; }
  .spin {
    width: 22px; height: 22px; border-radius: 50%;
    border: 2px solid var(--description);
    border-top-color: transparent; animation: turn .8s linear infinite;
  }
  @keyframes turn { to { transform: rotate(360deg); } }
  p { margin: 0; color: var(--description); }
  .address { color: var(--fg); }
"""
        )}
</style>
</head>
<body>
<main>
  <div class="spin" role="status" aria-label="${esc(connectingText)}"></div>
  <p class="address">${esc("127.0.0.1:$port")}</p>
  <p>${esc(hint)}</p>
</main>
</body>
</html>"""
        return Page(html)
    }

    /**
     * Shown when the listener does not answer as QuickDock, or cannot serve an
     * embedded view. Every button is a callback, so the card stays script-only
     * and works inside JCEF.
     */
    fun offline(model: OfflineModel, theme: Theme, lang: String, actionCall: String, t: (String) -> String): Page {
        val actions = ArrayList<String>()
        if (model.startable) {
            actions += button("startDsh", t("dock.startDsh"), primary = true, actionCall)
            actions += button("retry", t("dock.retry"), primary = false, actionCall)
        } else {
            actions += button("retry", t("dock.retry"), primary = true, actionCall)
        }
        if (!model.stale && model.detectedDiffers && model.detectedPort != null && model.detectedPort > 0) {
            actions += button("useDetectedPort", t("dock.useDetectedPort") + " ${model.detectedPort}", primary = false, actionCall)
        }
        actions += button("openPortSettings", t("dock.connectionSettings"), primary = false, actionCall)
        actions += button("openInBrowser", t("dock.openInBrowser"), primary = false, actionCall)
        if (model.hasLog) {
            actions += button("openLog", t("dock.openLog"), primary = false, actionCall, ghost = true)
        }

        val facts = StringBuilder()
        facts.append(fact(t("dock.address"), "<code>${esc(model.pageUrl)}</code>"))
        facts.append(fact(t("dock.reason"), "${esc(model.reason)} <span class=\"detail\">${esc(model.detail)}</span>"))
        if (model.detectedPort != null) {
            val where = model.detectedFile?.let { " <span class=\"detail\">${esc(it)}</span>" }.orEmpty()
            facts.append(fact(t("dock.quickdockConfig"), "${esc(t("dock.portIs") + " " + model.detectedPort)}$where"))
        } else {
            facts.append(fact(t("dock.quickdockConfig"), esc(t("dock.noConfig") + " " + model.stateDir)))
        }

        val html = """<!DOCTYPE html>
<html lang="${esc(lang)}">
<head>
<meta charset="UTF-8">
<title>DSH Dock</title>
<style>
${css(
            theme,
            """
  body { overflow: auto; }
  .wrap { min-height: 100%; display: flex; align-items: center; justify-content: center; padding: 28px 18px; }
  .card { width: 100%; max-width: 560px; }
  .mark { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
  .mark .dot { width: 26px; height: 26px; border-radius: 8px; background: var(--link); opacity: .9; }
  .mark .name { font-size: 12px; letter-spacing: .08em; text-transform: uppercase; color: var(--description); }
  h1 { font-size: 19px; font-weight: 600; margin: 0 0 6px; }
  .lead { margin: 0 0 18px; color: var(--description); }
  .facts { display: flex; flex-direction: column; gap: 1px; margin-bottom: 18px; border-radius: 6px; overflow: hidden; }
  .fact { display: flex; gap: 12px; padding: 8px 10px; background: rgba(128, 128, 128, .09); }
  .fact-label { flex: 0 0 118px; color: var(--description); }
  .fact-value { flex: 1 1 auto; min-width: 0; overflow-wrap: anywhere; }
  .detail { color: var(--description); font-size: .92em; }
  .actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 18px; }
  a.btn {
    display: inline-flex; align-items: center; height: 26px; padding: 0 12px;
    border-radius: 4px; font-size: 12px; border: 1px solid var(--border); color: var(--fg);
    background: transparent;
  }
  a.btn:hover { background: rgba(128, 128, 128, .16); text-decoration: none; }
  a.btn.primary { background: var(--button-bg); color: var(--button-fg); border-color: transparent; }
  a.btn.ghost { border-color: transparent; color: var(--link); }
  ol { margin: 0 0 4px; padding-left: 20px; }
  ol li { margin: 6px 0; }
  .foot { margin-top: 20px; color: var(--description); font-size: 11.5px; }
"""
        )}
</style>
</head>
<body>
<div class="wrap">
  <main class="card">
    <div class="mark">
      <span class="dot" aria-hidden="true"></span>
      <span class="name">${esc(t("dock.title"))}</span>
    </div>
    <h1>${esc(t("dock.offline.title"))}</h1>
    <p class="lead">${esc(model.lead)}</p>
    <div class="facts">
      $facts
    </div>
    <ol>
      ${model.steps.joinToString("\n      ") { "<li>$it</li>" }}
    </ol>
    <div class="actions">
      ${actions.joinToString("\n      ")}
    </div>
    <p class="foot">
      ${esc(t("dock.offline.foot"))}<br>
      <code>${esc(model.pageUrl)}</code>
    </p>
  </main>
</div>
</body>
</html>"""
        return Page(html)
    }

    /**
     * Shown when JCEF is not available at all (a custom JDK, a 2026.2 build with
     * the built-in browser disabled, or a remote/Gateway session). The card must
     * still be usable, so "open in the browser" is the primary action: in remote
     * development it is the only path that works.
     */
    fun jcefUnavailable(theme: Theme, lang: String, actionCall: String, reasons: List<String>, t: (String) -> String): Page {
        val steps = reasons.joinToString("\n      ") { "<li>${esc(it)}</li>" }
        val html = """<!DOCTYPE html>
<html lang="${esc(lang)}">
<head>
<meta charset="UTF-8">
<title>DSH Dock</title>
<style>
${css(
            theme,
            """
  body { overflow: auto; }
  .wrap { min-height: 100%; display: flex; align-items: center; justify-content: center; padding: 28px 18px; }
  .card { width: 100%; max-width: 560px; }
  h1 { font-size: 19px; font-weight: 600; margin: 0 0 6px; }
  .lead { margin: 0 0 16px; color: var(--description); }
  ol { margin: 0 0 18px; padding-left: 20px; }
  ol li { margin: 6px 0; }
  .actions { display: flex; flex-wrap: wrap; gap: 8px; }
  a.btn {
    display: inline-flex; align-items: center; height: 26px; padding: 0 12px;
    border-radius: 4px; font-size: 12px; border: 1px solid var(--border); color: var(--fg);
  }
  a.btn.primary { background: var(--button-bg); color: var(--button-fg); border-color: transparent; }
"""
        )}
</style>
</head>
<body>
<div class="wrap">
  <main class="card">
    <h1>${esc(t("dock.jcef.title"))}</h1>
    <p class="lead">${esc(t("dock.jcef.lead"))}</p>
    <ol>
      $steps
    </ol>
    <div class="actions">
      ${button("openInBrowser", t("dock.openInBrowser"), primary = true, actionCall)}
      ${button("retry", t("dock.retry"), primary = false, actionCall)}
    </div>
  </main>
</div>
</body>
</html>"""
        return Page(html)
    }

    /* -------------------------------- helpers -------------------------------- */

    private fun fact(label: String, value: String): String =
        "<div class=\"fact\"><span class=\"fact-label\">${esc(label)}</span><span class=\"fact-value\">$value</span></div>"

    private fun button(action: String, label: String, primary: Boolean, actionCall: String, ghost: Boolean = false): String {
        val css = if (primary) "btn primary" else if (ghost) "btn ghost" else "btn"
        // The injected query expression reads a variable named `action`.
        //
        // The handler goes into a double-quoted attribute, so every quote in it
        // has to be an entity: `jsString` yields `"startDsh"`, and dropping that
        // raw into `onclick="..."` closes the attribute at the first quote,
        // leaving the browser with the invalid handler `var action=` - a button
        // that looks right and does nothing.
        val handler = "var action=${jsString(action)}; $actionCall; return false;"
        return "<a class=\"$css\" role=\"button\" href=\"#\" onclick=\"${esc(handler)}\">${esc(label)}</a>"
    }

    /** `http://127.0.0.1:47910` from any URL on the dock's listener. */
    fun originOf(url: String): String {
        return try {
            val uri = URI.create(url)
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}$port"
        } catch (error: IllegalArgumentException) {
            ""
        }
    }
}
