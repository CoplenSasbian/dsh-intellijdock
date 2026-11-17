package cc.futurvo.ideadock.dock

import cc.futurvo.ideadock.contract.Json
import cc.futurvo.ideadock.lifecycle.DockService
import cc.futurvo.ideadock.ui.DshDockBundle
import cc.futurvo.ideadock.ui.DshDockSettings
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.callback.CefCallback
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The dock's Swing host: one `JBCefBrowser` showing an HTML shell, plus the JS
 * queries that carry messages between the page and this plugin.
 *
 * Two things are easy to get wrong and both are handled here:
 *
 * 1. `JBCefJSQuery` must exist *before* the page loads; CEF injects
 *    `window.<name>` when the document is created, so a query made afterwards
 *    leaves a dead bridge (`window.<fn> is not a function`).
 * 2. The browser is a `Disposable`: registering it with this panel is what keeps
 *    closing and reopening the tool window from leaking a CEF instance each time.
 */
class DockPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(DockPanel::class.java)
    private val service: DockService = project.getService(DockService::class.java)
        ?: error("DockService is not registered for this project")
    private val settings: DshDockSettings = DshDockSettings.getInstance(project)

    private var browser: JBCefBrowser? = null
    /** The Swing component of the current browser, kept so a recreate can remove it. */
    private var browserComponent: Component? = null
    /** Per-browser disposable: disposing it frees that browser and its queries. */
    private var browserDisposable: Disposable? = null
    /** The rendering mode the current browser was created with (see JcefRendering). */
    private var browserOffscreen: Boolean? = null
    private var toggleQuery: JBCefJSQuery? = null
    private var openQuery: JBCefJSQuery? = null
    private var actionQuery: JBCefJSQuery? = null
    /** Carries app -> host messages when the app itself is the document. */
    private var relayQuery: JBCefJSQuery? = null
    private var lastView: DockView? = null
    private var lafListener: Disposable? = null
    /** The URL currently loaded as the document (top-level mode), else null. */
    private var loadedUrl: String? = null
    /** The session the app on screen shows, for the handoff button. */
    private var currentSessionId: String = ""

    private val cards = JPanel(CardLayout())
    private var noJcefCard: JPanel? = null

    /**
     * The bar this plugin draws when the app is the document.
     *
     * With the app top-level there is no shell page to draw the title bar, so the
     * four controls it owns live in Swing (see [DockToolbar]).
     */
    private val toolbar = DockToolbar(
        onSidebar = { sendToApp("""{"type":"quickdock:toggle-sidebar"}""") },
        onNewSession = { sendToApp("""{"type":"quickdock:new-session"}""") },
        onShowTools = { anchor -> service.showToolsInfo(anchor) },
        onHandoff = { service.handoffToMain(currentSessionId) }
    ).apply { isVisible = false }

    init {
        background = com.intellij.ui.JBColor.PanelBackground
        add(toolbar, BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)
        service.attachPanel(this)
        subscribeToTheme()
    }

    /* --------------------------------- render --------------------------------- */

    /**
     * Show one state; always called on the EDT.
     *
     * @return true when a fresh document was requested for the browser (a
     *   top-level navigation or a `loadHTML`), false when the same document was
     *   kept - the title bar's Reload uses this to know whether a second reload
     *   would race the navigation it has just started.
     */
    fun render(view: DockView): Boolean {
        lastView = view
        if (view.mode == DockView.Mode.NO_JCEF) {
            showNoJcefCard(view)
            return false
        }
        // The rendering mode is fixed when a browser is created, so a settings
        // flip recreates it. A plain reload keeps the same browser (and the app's
        // state) - only a mode change must throw it away.
        val wanted = desiredOffscreen()
        if (browser != null && browserOffscreen != wanted) {
            log.info("jcef: rendering mode changed, recreating the browser (offscreen=$wanted)")
            dropBrowser()
        }
        val b = ensureBrowser() ?: run {
            showNoJcefCard(view)
            return false
        }
        val theme = DockTheme.current()
        val lang = DshDockBundle.localeLanguage()
        val t: (String) -> String = { key -> DshDockBundle.message(key) }
        val page = when (view.mode) {
            DockView.Mode.FRAME -> DockPage.frame(
                pageUrl = view.pageUrl,
                theme = theme,
                lang = lang,
                toggleCall = toggleQuery?.inject("'toggle'").orEmpty(),
                openCall = openQuery?.inject("data.path").orEmpty()
            )
            DockView.Mode.CONNECTING -> DockPage.connecting(
                port = view.port,
                theme = theme,
                lang = lang,
                connectingText = t("dock.connecting"),
                hint = t("dock.connecting.hint")
            )
            DockView.Mode.OFFLINE -> DockPage.offline(
                model = view.offline ?: error("offline view without a model"),
                theme = theme,
                lang = lang,
                actionCall = actionQuery?.inject("action").orEmpty(),
                t = t
            )
            DockView.Mode.NO_JCEF -> return false
        }
        // Top-level mode: the app is the document, the browser is not reloaded on
        // every render (that would drop the conversation's state), and this plugin
        // draws the chrome.
        if (view.mode == DockView.Mode.FRAME && view.topLevel && view.pageUrl.isNotBlank()) {
            toolbar.isVisible = true
            (cards.layout as CardLayout).show(cards, CARD_BROWSER)
            var loaded = false
            if (loadedUrl != view.pageUrl) {
                loadedUrl = view.pageUrl
                log.info("dock: loading the app directly (${view.pageUrl})")
                b.loadURL(view.pageUrl)
                loaded = true
            }
            pushMcpState(view.mcpOn, view.mcpDetail)
            return loaded
        }
        toolbar.isVisible = false
        loadedUrl = null
        (cards.layout as CardLayout).show(cards, CARD_BROWSER)
        b.loadHTML(page.html)
        if (view.mode == DockView.Mode.FRAME) pushMcpState(view.mcpOn, view.mcpDetail)
        return true
    }

    /**
     * Relay the tool state into the shell page's title bar.
     *
     * The bar keeps its switch disabled until it receives this message, which is
     * the single most commonly missed piece of the contract.
     */
    fun pushMcpState(on: Boolean, detail: String) {
        val js = "if (window.dshMcpState) { window.dshMcpState($on, ${DockPage.jsString(detail)}) }"
        execute(js)
    }

    /** Ask the app to show one session (the dock the Dock button bound). */
    fun openSession(sessionId: String) {
        if (sessionId.isBlank() || loadedUrl == null) return
        sendToApp("""{"type":"quickdock:open-session","sessionId":${DockPage.jsString(sessionId)}}""")
    }

    /** `dock-target.workspace.matches == false`: the tools do not reach that session. */
    fun setWorkspaceWarning(show: Boolean) {
        toolbar.setWorkspaceWarning(show)
    }

    /**
     * Reload the document, keep the chrome.
     *
     * The app shell is the durable thing here (a rebuilt QuickDock client, a
     * restarted DSH): reloading the top-level document goes through the URL the
     * plugin intended, not "whatever the browser is on" - after a DSH restart the
     * current page is the dead app-proxy port, and reloading that keeps showing
     * `ERR_CONNECTION_REFUSED`. The proxy serves documents with `no-store`, so
     * the page is fetched again.
     */
    fun reloadApp() {
        val url = loadedUrl
        if (url != null) {
            try {
                browser?.loadURL(url)
            } catch (error: Throwable) {
                log.debug("jcef: reload failed: ${error.message}")
            }
            return
        }
        browser?.cefBrowser?.let { cef ->
            try {
                cef.reloadIgnoreCache()
            } catch (error: Throwable) {
                log.debug("jcef: reload failed: ${error.message}")
            }
        }
    }

    /** Re-render the state that is already on screen (after a retry, a port move…). */
    fun rerender() {
        lastView?.let(::render)
    }

    /** The page the user is looking at, for "open in browser". */
    fun currentPageUrl(): String? = lastView?.pageUrl?.takeIf { it.isNotBlank() }

    /* --------------------------------- browser -------------------------------- */

    private fun ensureBrowser(): JBCefBrowser? {
        browser?.let { return it }
        // The JCEF classes may be absent entirely (a 2026.2 build with the
        // bundled browser disabled): that is a normal "no JCEF here", not a crash.
        val supported = try {
            JBCefApp.isSupported()
        } catch (error: Throwable) {
            log.info("jcef: not available (${error.javaClass.simpleName})")
            false
        }
        if (!supported) return null
        val offscreen = desiredOffscreen()
        // `JcefRendering` explains the two IME problems and why the app is loaded
        // top-level (DockView.topLevel) instead of being framed.
        val created = createBrowser(offscreen)
        browserOffscreen = offscreen
        // One generation per browser: dropping it disposes the browser and every
        // query created against it, so a mode flip cannot leak a CEF instance.
        val generation = Disposer.newDisposable("dsh-dock-browser").also { Disposer.register(this, it) }
        browserDisposable = generation
        // Queries first: CEF injects their entry points while loading the page.
        toggleQuery = JBCefJSQuery.create(created).also { query ->
            query.addHandler { _ ->
                service.toggleToolsFromDock()
                null
            }
        }
        openQuery = JBCefJSQuery.create(created).also { query ->
            query.addHandler { raw ->
                service.openFileFromDock(raw)
                null
            }
        }
        actionQuery = JBCefJSQuery.create(created).also { query ->
            query.addHandler { action ->
                service.runDockAction(action)
                null
            }
        }
        // The top-level app talks to its parent, which is itself; this query is
        // how those messages reach the plugin (see bridgeScript).
        relayQuery = JBCefJSQuery.create(created).also { query ->
            query.addHandler { raw ->
                onAppMessage(raw)
                null
            }
        }
        listOfNotNull(toggleQuery, openQuery, actionQuery, relayQuery).forEach { Disposer.register(generation, it) }
        Disposer.register(generation, created)
        installHandlers(created)
        val component = created.component
        browserComponent = component
        cards.add(component, CARD_BROWSER)
        browser = created
        log.info("jcef: browser created (offscreen=$offscreen)")
        return created
    }

    /** The rendering mode to ask for right now, honouring the platform's own limit. */
    private fun desiredOffscreen(): Boolean {
        val global = try {
            JBCefApp.isOffScreenRenderingModeEnabled()
        } catch (error: Throwable) {
            // Unknown: assume the default (OSR) and let the user turn it off.
            true
        }
        return JcefRendering.offscreen(globalEnabled = global, windowed = settings.state.jcefWindowed)
    }

    /**
     * Build the browser in the requested mode.
     *
     * Windowed rendering is the only mode whose IME commit works inside the
     * dock's nested frames, but the platform may refuse it in some compositions,
     * so a failure falls back to the default browser instead of a blank panel.
     */
    private fun createBrowser(offscreen: Boolean): JBCefBrowser {
        if (offscreen) return JBCefBrowser()
        return try {
            JBCefBrowser.createBuilder().setOffScreenRendering(false).build()
        } catch (error: Throwable) {
            log.info("jcef: windowed rendering unavailable (${error.javaClass.simpleName}); using the default")
            JBCefBrowser()
        }
    }

    /** Tear the current browser down so the next render creates a fresh one. */
    private fun dropBrowser() {
        val component = browserComponent
        val generation = browserDisposable
        browser = null
        browserComponent = null
        browserDisposable = null
        browserOffscreen = null
        toggleQuery = null
        openQuery = null
        actionQuery = null
        relayQuery = null
        loadedUrl = null
        if (component != null) cards.remove(component)
        if (generation != null) Disposer.dispose(generation)
    }

    private fun installHandlers(b: JBCefBrowser) {
        val cef = b.cefBrowser
        // A popup inside the dock would open a desktop window; send it to the
        // system browser instead.
        b.jbCefClient.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(
                browser: CefBrowser,
                frame: CefFrame,
                targetUrl: String,
                targetFrameName: String
            ): Boolean {
                if (targetUrl.isNotBlank()) service.browse(targetUrl)
                return true
            }
        }, cef)
        // Console output is the fastest way to answer "why did the message not
        // arrive", so it goes to the IDE log at debug level.
        b.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(
                browser: CefBrowser,
                level: CefSettingsLogLevel,
                message: String,
                source: String,
                line: Int
            ): Boolean {
                if (LOG_CONSOLE) log.debug("jcef console: $message ($source:$line)")
                return false
            }
        }, cef)
        b.jbCefClient.addLoadHandler(object : CefLoadHandler {
            override fun onLoadingStateChange(browser: CefBrowser, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {}

            override fun onLoadStart(browser: CefBrowser, frame: CefFrame, transitionType: CefRequest.TransitionType) {}

            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                // Top-level mode: the document is the app itself, so the bridge
                // the shell page used to provide has to be installed here. It runs
                // on every load of the main frame (a reload keeps the chrome).
                if (frame.isMain && loadedUrl != null) {
                    execute(bridgeScript())
                    sendToApp(contextJson())
                }
            }

            override fun onLoadError(
                browser: CefBrowser,
                frame: CefFrame,
                errorCode: CefLoadHandler.ErrorCode,
                errorText: String,
                failedUrl: String
            ) {
                log.info("jcef: $errorCode $errorText for $failedUrl")
                // The app proxy lives and dies with the Host: after a DSH restart
                // (or a QuickDock reload) the old origin is gone and Chromium's
                // error page would be the end state. Rebuild the view instead,
                // which asks the Host for the new app URL.
                if (!frame.isMain || loadedUrl == null) return
                when (errorCode) {
                    CefLoadHandler.ErrorCode.ERR_CONNECTION_REFUSED,
                    CefLoadHandler.ErrorCode.ERR_CONNECTION_RESET,
                    CefLoadHandler.ErrorCode.ERR_CONNECTION_CLOSED,
                    CefLoadHandler.ErrorCode.ERR_CONNECTION_ABORTED,
                    CefLoadHandler.ErrorCode.ERR_CONNECTION_FAILED,
                    CefLoadHandler.ErrorCode.ERR_EMPTY_RESPONSE,
                    CefLoadHandler.ErrorCode.ERR_NAME_NOT_RESOLVED -> service.loadFailed()
                    else -> Unit
                }
            }
        }, cef)
    }

    private fun execute(js: String) {
        browser?.let { b ->
            try {
                b.cefBrowser.executeJavaScript(js, b.cefBrowser.url ?: "", 0)
            } catch (error: Throwable) {
                log.debug("jcef: cannot execute script: ${error.message}")
            }
        }
    }

    /* --------------------------- app bridge (top-level) ----------------------- */

    /**
     * Install the message bridge inside the app document.
     *
     * With the app as the document, `window.parent` is the window itself: a
     * `postMessage` the app sends "to its host" is a message it also receives, so
     * the same code path works and the shell page's relay is not needed. The
     * bridge forwards the app's messages (ready, open-file, session, title) to the
     * plugin and exposes `__dshDockSend` for the other direction, which is how the
     * shell page used to talk to the app.
     */
    private fun bridgeScript(): String {
        val relay = relayQuery?.inject("JSON.stringify({ type: String(data.type || ''), data: data })")
            ?: return ""
        return """
            (function () {
              if (window.__dshDockBridge) return;
              window.__dshDockBridge = true;
              window.addEventListener('message', function (event) {
                // Only our own messages: the app posts to `parent`, which is us.
                if (event.source !== window) return;
                var data = event.data || {};
                var type = String(data.type || '');
                if (type.indexOf('quickdock:') !== 0) return;
                // Host -> app messages would loop back through this listener.
                if (type === 'quickdock:context' || type === 'quickdock:open-session'
                    || type === 'quickdock:toggle-sidebar' || type === 'quickdock:new-session') return;
                $relay;
              });
              window.__dshDockSend = function (json) {
                try { window.postMessage(JSON.parse(json), '*'); } catch (error) { }
              };
            })();
        """.trimIndent()
    }

    /** The context the shell page sent on load: dock id, host, dock flag. */
    private fun contextJson(): String = buildString {
        append("""{"type":"quickdock:context","dock":true,"host":"intellij","id":""")
        append(DockPage.jsString(service.dockId()))
        append("}")
    }

    /** Post a host -> app message the way the shell page did. */
    private fun sendToApp(json: String) {
        if (loadedUrl == null) return
        execute("if (window.__dshDockSend) { window.__dshDockSend(${DockPage.jsString(json)}) }")
    }

    /** One app -> host message, relayed from the injected bridge. */
    private fun onAppMessage(raw: String) {
        val body = Json.parseObject(raw) ?: return
        val type = Json.str(body, "type").orEmpty()
        val data = Json.obj(body, "data")
        when (type) {
            // The app can navigate now: name the dock and ask for the target.
            "quickdock:ready" -> {
                sendToApp(contextJson())
                service.followNow()
            }
            "quickdock:open-file" -> Json.str(data, "path")?.let { service.openFileFromDock(it) }
            "quickdock:session" -> {
                currentSessionId = Json.str(data, "sessionId").orEmpty()
                toolbar.setSessionOnScreen(currentSessionId)
                // The Host warns from its binding; the app is the only thing that
                // knows which session is on screen (dock sidebar, restored last
                // session), so the report doubles as the bind request.
                service.dockSessionReported(currentSessionId)
            }
            "quickdock:title" -> Json.str(data, "title")?.let { toolbar.setTitle(it) }
            // The shell page used this to highlight its sidebar button and to hide
            // the new-session shortcut while the sidebar (which has its own) is
            // open; the Swing bar does the same.
            "quickdock:sidebar-state" -> toolbar.setSidebarExpanded(Json.bool(data, "expanded") == true)
            else -> Unit
        }
    }

    /* ------------------------------- fallback card ---------------------------- */

    /**
     * JCEF is missing (a custom JDK, a 2026.2 build with the built-in browser
     * disabled, or a remote session). The panel must still be usable, so this is
     * a real Swing card with working buttons - never a white rectangle.
     */
    private fun showNoJcefCard(view: DockView) {
        toolbar.isVisible = false
        var card = noJcefCard
        if (card == null) {
            card = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createEmptyBorder(24, 20, 24, 20)
                background = com.intellij.ui.JBColor.PanelBackground
                val title = JBLabel(DshDockBundle.message("dock.jcef.title")).apply {
                    font = font.deriveFont(font.size2D + 3f)
                    horizontalAlignment = SwingConstants.LEFT
                }
                add(title)
                add(JBLabel(DshDockBundle.message("dock.jcef.lead")))
                for (reason in view.reasons) {
                    add(JBLabel("• $reason").apply { border = JBUI.Borders.emptyTop(6) })
                }
                val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyTop(18)
                }
                actions.add(JButton(DshDockBundle.message("dock.openInBrowser")).apply {
                    addActionListener { service.openInBrowser() }
                })
                actions.add(JButton(DshDockBundle.message("dock.retry")).apply {
                    addActionListener { service.reload(force = true) }
                })
                add(actions)
            }
            noJcefCard = card
            cards.add(card, CARD_NO_JCEF)
        }
        (cards.layout as CardLayout).show(cards, CARD_NO_JCEF)
    }

    /* ---------------------------------- theme --------------------------------- */

    /** Theme flips must not reload the page (that would lose the app's state). */
    private fun subscribeToTheme() {
        lafListener = ApplicationManager.getApplication().messageBus.connect(this).also { connection ->
            connection.subscribe(LafManagerListener.TOPIC, LafManagerListener { applyTheme() })
        }
    }

    private fun applyTheme() {
        val theme = DockTheme.current()
        val js = """
            (function () {
              var root = document.documentElement;
              if (!root) return;
              root.style.setProperty('--bg', ${DockPage.jsString(theme.bg)});
              root.style.setProperty('--fg', ${DockPage.jsString(theme.fg)});
              root.style.setProperty('--border', ${DockPage.jsString(theme.border)});
              root.style.setProperty('--description', ${DockPage.jsString(theme.description)});
              root.style.setProperty('--link', ${DockPage.jsString(theme.link)});
              root.style.setProperty('--button-bg', ${DockPage.jsString(theme.buttonBg)});
              root.style.setProperty('--button-fg', ${DockPage.jsString(theme.buttonFg)});
              root.style.colorScheme = ${DockPage.jsString(if (theme.dark) "dark" else "light")};
            })();
        """.trimIndent()
        execute(js)
        noJcefCard?.background = com.intellij.ui.JBColor.PanelBackground
    }

    /* -------------------------------- lifecycle ------------------------------- */

    override fun dispose() {
        service.detachPanel(this)
        // The browser and the queries belong to `browserDisposable`, which the
        // platform disposes with this panel; dropping it here frees them before
        // the panel itself goes away.
        dropBrowser()
        noJcefCard = null
    }

    private companion object {
        const val CARD_BROWSER = "browser"
        const val CARD_NO_JCEF = "noJcef"
        /** Flip to true to see the shell page's console in idea.log. */
        const val LOG_CONSOLE = false
    }
}

/** Alias so the console handler signature stays readable. */
private typealias CefSettingsLogLevel = org.cef.CefSettings.LogSeverity
