package cc.futurvo.ideadock.dock

import cc.futurvo.ideadock.lifecycle.DockService
import cc.futurvo.ideadock.ui.DshDockBundle
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

    private var browser: JBCefBrowser? = null
    private var toggleQuery: JBCefJSQuery? = null
    private var openQuery: JBCefJSQuery? = null
    private var actionQuery: JBCefJSQuery? = null
    private var lastView: DockView? = null
    private var lafListener: Disposable? = null

    private val cards = JPanel(CardLayout())
    private var noJcefCard: JPanel? = null

    init {
        background = com.intellij.ui.JBColor.PanelBackground
        add(cards, BorderLayout.CENTER)
        service.attachPanel(this)
        subscribeToTheme()
    }

    /* --------------------------------- render --------------------------------- */

    /** Show one state; always called on the EDT. */
    fun render(view: DockView) {
        lastView = view
        if (view.mode == DockView.Mode.NO_JCEF) {
            showNoJcefCard(view)
            return
        }
        val b = ensureBrowser() ?: run {
            showNoJcefCard(view)
            return
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
            DockView.Mode.NO_JCEF -> return
        }
        (cards.layout as CardLayout).show(cards, CARD_BROWSER)
        b.loadHTML(page.html)
        if (view.mode == DockView.Mode.FRAME) pushMcpState(view.mcpOn, view.mcpDetail)
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
        val created = JBCefBrowser()
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
        listOfNotNull(toggleQuery, openQuery, actionQuery).forEach { Disposer.register(this, it) }
        Disposer.register(this, created)
        installHandlers(created)
        cards.add(created.component, CARD_BROWSER)
        browser = created
        return created
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

            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {}

            override fun onLoadError(
                browser: CefBrowser,
                frame: CefFrame,
                errorCode: CefLoadHandler.ErrorCode,
                errorText: String,
                failedUrl: String
            ) {
                log.info("jcef: $errorCode $errorText for $failedUrl")
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

    /* ------------------------------- fallback card ---------------------------- */

    /**
     * JCEF is missing (a custom JDK, a 2026.2 build with the built-in browser
     * disabled, or a remote session). The panel must still be usable, so this is
     * a real Swing card with working buttons - never a white rectangle.
     */
    private fun showNoJcefCard(view: DockView) {
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
        browser = null
        noJcefCard = null
        // The browser and the queries were registered with this panel, so the
        // platform disposes them right after this method returns.
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
