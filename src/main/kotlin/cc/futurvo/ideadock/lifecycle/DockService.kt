package cc.futurvo.ideadock.lifecycle

import cc.futurvo.ideadock.contract.McpProtocol
import cc.futurvo.ideadock.contract.Pairing
import cc.futurvo.ideadock.contract.PairingClient
import cc.futurvo.ideadock.contract.Paths
import cc.futurvo.ideadock.contract.ProbeFailure
import cc.futurvo.ideadock.contract.ProbeResult
import cc.futurvo.ideadock.contract.QuickDockPorts
import cc.futurvo.ideadock.contract.QuickDockProbe
import cc.futurvo.ideadock.contract.QuickDockUrls
import cc.futurvo.ideadock.dock.DockPage
import cc.futurvo.ideadock.dock.DockPanel
import cc.futurvo.ideadock.dock.DockToolsPanel
import cc.futurvo.ideadock.dock.DockView
import cc.futurvo.ideadock.editor.IdeIdentity
import cc.futurvo.ideadock.editor.IdeTools
import cc.futurvo.ideadock.editor.ReviewDiff
import cc.futurvo.ideadock.ui.DshDockBundle
import cc.futurvo.ideadock.ui.DshDockConfigurable
import cc.futurvo.ideadock.ui.DshDockSettings
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Everything that belongs to one project window: the port in effect, the probe,
 * the editor MCP server, its pairing with QuickDock, the review watcher, and the
 * actions the dock page and the menus call.
 *
 * Threading is the part that matters here:
 * every network call, file read and process launch happens on this service's own
 * small thread pool - never on the EDT and never on the platform's shared pool,
 * which is an unbounded queue that silently stops draining during indexing.
 * Anything that touches Swing is posted back with [ModalityState.any], so a modal
 * settings dialog cannot freeze the dock's state updates.
 */
@Service(Service.Level.PROJECT)
class DockService(private val project: Project) : Disposable {

    /** The running editor MCP server and what DSH mounts from it. */
    private class McpState(
        val server: cc.futurvo.ideadock.contract.EditorMcpServer,
        val token: String,
        /** Digest of the tool set this build publishes. */
        val fingerprint: String,
        val toolNames: List<String>,
        /** Workspace folder -> endpoint that is registered for it. */
        val registered: MutableMap<String, String> = mutableMapOf()
    )

    /** What the status bar and the tool window show right now. */
    data class Status(
        val reachable: Boolean,
        val port: Int,
        val text: String,
        val tooltip: String,
        val version: String? = null
    )

    private val log = Logger.getInstance(DockService::class.java)

    /** A logger call as a lambda, for the classes that take a log sink. */
    private fun logLine(line: String) {
        log.info(line)
    }
    private val settings: DshDockSettings get() = DshDockSettings.getInstance(project)

    private val probe = QuickDockProbe()
    private val pairing = PairingClient()
    /** The follow beat that keeps a top-level dock on the session it is bound to. */
    private var followBeat: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile
    private var followSession: String = ""
    private var followReloadSeq: Long = -1
    private var followWarning: Boolean? = null
    /** The listener stopped answering (a DSH restart); rebuild when it is back. */
    private var hostDown: Boolean = false
    /** The session the app says it shows (`quickdock:session`), for bind/warning. */
    @Volatile
    private var reportedSession: String = ""
    /** The session the Host is bound to, so a report does not re-bind every beat. */
    @Volatile
    private var boundSession: String = ""
    private var warnedAboutAppUrl: Boolean = false
    /** Debounce for "the app page died, probe again" retries. */
    private var lastLoadErrorAt: Long = 0

    /** One thread does the work, one thread owns the 20-second beat. */
    private val worker: ScheduledExecutorService =
        Executors.newScheduledThreadPool(2, cc.futurvo.ideadock.contract.EditorMcpServer.daemonFactory("dsh-ideadock"))

    @Volatile
    private var panel: DockPanel? = null

    @Volatile
    private var mcp: McpState? = null

    @Volatile
    private var mcpStarting = false

    @Volatile
    private var mcpSyncing = false

    @Volatile
    private var mcpFailure: String = ""

    @Volatile
    private var status: Status = Status(false, 0, "DSH Dock", "")

    @Volatile
    private var lastProbe: ProbeResult? = null

    @Volatile
    private var lastProbeAt: Long = 0

    @Volatile
    private var renderedPort: Int = -1

    @Volatile
    private var lastView: DockView? = null

    private var beat: java.util.concurrent.ScheduledFuture<*>? = null
    private var statusBeat: java.util.concurrent.ScheduledFuture<*>? = null
    private var review: cc.futurvo.ideadock.editor.EditReviewWatcher? = null
    private var dshStarting = false
    private var disposed = false

    private val statusListeners = CopyOnWriteArrayList<(Status) -> Unit>()

    init {
        // The status bar is the only always-on surface, so it starts the beat.
        startStatusBeat()
        if (settings.state.mcp) worker.execute { startTools(announce = false) }
        syncReview()
        log.info("dsh-dock: service created for ${project.name}")
    }

    /* -------------------------------- status ---------------------------------- */

    fun status(): Status = status

    fun addStatusListener(listener: (Status) -> Unit) {
        statusListeners.add(listener)
        onUi { listener(status) }
    }

    fun removeStatusListener(listener: (Status) -> Unit) {
        statusListeners.remove(listener)
    }

    private fun publish(next: Status) {
        status = next
        for (listener in statusListeners) {
            onUi { listener(next) }
        }
    }

    private fun startStatusBeat() {
        if (statusBeat != null) return
        statusBeat = worker.scheduleWithFixedDelay(
            { safely("status beat") { refreshStatus() } },
            0, STATUS_REFRESH_SECONDS, TimeUnit.SECONDS
        )
    }

    /** Probe in the background and repaint the status bar. */
    private fun refreshStatus() {
        val info = resolvePort()
        val result = probeCached(info.port)
        applyStatus(result, info)
        // The port can move under an open dock, and a listener that came back
        // should recover without a click.
        val view = lastView
        if (view != null) {
            val portMoved = renderedPort != info.port && info.port > 0
            val recovered = view.mode == DockView.Mode.OFFLINE && result.embeddable
            if (portMoved || recovered) {
                log.info("dock: reloading (portMoved=$portMoved recovered=$recovered)")
                reload(force = true)
            }
        }
    }

    private fun applyStatus(result: ProbeResult, info: QuickDockPorts.PortInfo) {
        // The tooltip shows the address the dock actually opens, not a guess: an
        // older QuickDock only knows `vscode`.
        val url = if (info.port > 0) QuickDockUrls.pageUrl(info.port, QuickDockUrls.viewFor(result.meta)) else ""
        val next = if (result.ok) {
            Status(
                reachable = true,
                port = info.port,
                text = "DSH Dock",
                tooltip = buildString {
                    val version = result.meta?.version
                    appendLine(DshDockBundle.message("status.connected", version ?: "?"))
                    appendLine()
                    if (url.isNotBlank()) appendLine(url)
                    appendLine(sourceNote(info))
                    append(DshDockBundle.message("status.clickToOpen"))
                },
                version = result.meta?.version
            )
        } else {
            Status(
                reachable = false,
                port = info.port,
                text = DshDockBundle.message("status.disconnected"),
                tooltip = buildString {
                    appendLine(DshDockBundle.message("status.notAnswering", info.port))
                    appendLine(probeReason(result))
                    appendLine()
                    appendLine(sourceNote(info))
                    append(DshDockBundle.message("status.clickForFix"))
                }
            )
        }
        publish(next)
    }

    /* ------------------------------- resolution ------------------------------- */

    /** The port in effect, resolved the same way the VS Code client resolves it. */
    fun resolvePort(): QuickDockPorts.PortInfo {
        val detected = QuickDockPorts.detect()
        val explicit = settings.state.port.takeIf { it > 0 }
        return QuickDockPorts.resolvePort(explicit, settings.state.followQuickDockConfig, detected)
    }

    /** The port found in QuickDock's own configuration, for the offline card. */
    fun detected(): QuickDockPorts.Detection? = QuickDockPorts.detect()

    private fun probeCached(port: Int, force: Boolean = false): ProbeResult {
        val previous = lastProbe
        if (!force && previous != null && previous.port == port && System.currentTimeMillis() - lastProbeAt < PROBE_CACHE_MS) {
            return previous
        }
        val result = probe.probe(port)
        lastProbe = result
        lastProbeAt = System.currentTimeMillis()
        return result
    }

    /** The absolute content roots of this project, never `basePath`. */
    fun contentRoots(): List<String> = ReadAction.compute<List<String>, RuntimeException> {
        ProjectRootManager.getInstance(project).contentRoots.mapNotNull { it.path }.filter { it.isNotBlank() }
    }

    /** This window's stable dock id, generated once and kept in the project state. */
    fun dockId(): String {
        val current = settings.state.dockId
        if (current.isNotBlank()) return current
        val generated = "idea-" + java.lang.Long.toHexString(java.security.SecureRandom().nextLong()).padStart(12, '0').take(12)
        settings.state.dockId = generated
        return generated
    }

    /* --------------------------------- the view ------------------------------- */

    fun attachPanel(panel: DockPanel) {
        this.panel = panel
        reload(force = true)
    }

    fun detachPanel(panel: DockPanel) {
        if (this.panel === panel) this.panel = null
    }

    /**
     * Probe again and repaint the dock (the Retry button, a port move, a reload).
     *
     * @param force skip the probe cache.
     * @param reloadDocument also fetch the app document again. In top-level mode
     *   [DockPanel.render] keeps the document on purpose (that is where the
     *   conversation lives), so the title bar's Reload needs this to mean what
     *   it says: a rebuilt client bundle or a restarted app is otherwise never
     *   re-fetched while the tool window stays open.
     */
    fun reload(force: Boolean = false, reloadDocument: Boolean = false) {
        worker.execute {
            safely("reload") {
                val info = resolvePort()
                val result = probeCached(info.port, force)
                val view = buildView(result, info)
                renderedPort = info.port
                lastView = view
                applyStatus(result, info)
                onUi {
                    val loadedFresh = panel?.render(view) ?: false
                    // Reload the document only when render kept the one on screen.
                    // Right after a DSH restart render fetches the *new* app-proxy
                    // URL; a second reload there would race that navigation and
                    // leave the browser on the old, dead port's error page.
                    if (reloadDocument && !loadedFresh && view.mode == DockView.Mode.FRAME && view.topLevel) {
                        panel?.reloadApp()
                    }
                }
            }
        }
    }

    /**
     * A main-frame load failed - normally the app proxy died with the Host.
     *
     * Chromium's error page must not be the end state: probe again and, when the
     * Host is up, fetch its new app URL. Debounced, because an error can fire per
     * frame and a Host that is still starting must not be hammered.
     */
    fun loadFailed() {
        val now = System.currentTimeMillis()
        if (now - lastLoadErrorAt < 5_000) return
        lastLoadErrorAt = now
        log.info("dock: app page failed to load; probing again")
        worker.schedule({ reload(force = true) }, 1, TimeUnit.SECONDS)
    }

    /** Reveal the tool window, and make sure it is showing current state. */
    fun openDock() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (toolWindow == null) {
            log.warn("dock: no tool window '${TOOL_WINDOW_ID}' in this IDE")
            return
        }
        onUi {
            toolWindow.show()
            toolWindow.activate(null, true, true)
        }
        reload(force = true)
    }

    private fun buildView(result: ProbeResult, info: QuickDockPorts.PortInfo): DockView {
        if (!jcefSupported()) {
            return DockView(
                mode = DockView.Mode.NO_JCEF,
                port = info.port,
                reasons = jcefUnavailableReasons(),
                pageUrl = if (info.port > 0) QuickDockUrls.pageUrl(info.port, QuickDockUrls.BROWSER_VIEW) else ""
            )
        }
        if (result.embeddable && info.port > 0) {
            // `intellij` when this QuickDock lists it; an older one keeps working
            // through `vscode` (same chrome, same app proxy, same open-file relay).
            val view = QuickDockUrls.viewFor(result.meta)
            if (view != QuickDockUrls.IDE_VIEW) {
                log.info("dock: view=${QuickDockUrls.LEGACY_IDE_VIEW} (this QuickDock predates view=${QuickDockUrls.IDE_VIEW})")
            }
            // A JCEF browser cannot commit input-method text into a subframe, so
            // the app is loaded as the document itself when the Host can serve it
            // that way; the shell page stays the fallback for an older QuickDock.
            val appUrl = appUrl(info.port)
            if (appUrl != null) {
                // The shell page used to register the dock context on load; with
                // the app as the document this is the plugin's job. It is an
                // idempotent upsert, so every build of the view may repeat it.
                pairing.registerDockContext(info.port, dockId(), workspaceFolders = contentRoots())
                startFollow()
                return DockView(
                    mode = DockView.Mode.FRAME,
                    port = info.port,
                    pageUrl = appUrl,
                    topLevel = true,
                    mcpOn = mcp != null,
                    mcpDetail = mcpDetail()
                )
            }
            return DockView(
                mode = DockView.Mode.FRAME,
                port = info.port,
                pageUrl = QuickDockUrls.shellUrl(info.port, dockId(), mcp != null, contentRoots(), view = view),
                mcpOn = mcp != null,
                mcpDetail = mcpDetail()
            )
        }
        val detected = detected()
        val stale = result.ok
        val disabled = result.failure == ProbeFailure.DISABLED
        val startable = !stale && (
            result.failure == ProbeFailure.REFUSED ||
                result.failure == ProbeFailure.TIMEOUT ||
                result.failure == ProbeFailure.UNREACHABLE ||
                disabled
            )
        val pageUrl = when {
            info.port > 0 -> QuickDockUrls.pageUrl(info.port, QuickDockUrls.BROWSER_VIEW)
            else -> "http://127.0.0.1/quickdock"
        }
        val model = DockPage.OfflineModel(
            port = info.port,
            pageUrl = pageUrl,
            lead = when {
                stale -> DshDockBundle.message("dock.offline.stale.lead")
                disabled -> DshDockBundle.message("dock.offline.disabled.lead")
                else -> DshDockBundle.message("dock.offline.refused.lead", "http://127.0.0.1:${info.port}/quickdock")
            },
            reason = when {
                stale -> DshDockBundle.message("dock.offline.stale.reason")
                else -> probeReason(result)
            },
            detail = if (stale) "apiVersion ${result.meta?.apiVersion ?: "?"}" else result.detail,
            detectedPort = detected?.port,
            detectedFile = detected?.file?.absolutePath,
            detectedDiffers = detected != null && detected.port != info.port,
            disabled = disabled,
            stale = stale,
            startable = startable,
            hasLog = QuickDockPorts.hostLogFile().isFile,
            stateDir = QuickDockPorts.stateDir().absolutePath,
            steps = if (disabled) {
                listOf(
                    DshDockBundle.message("dock.offline.steps.disabled.1"),
                    DshDockBundle.message("dock.offline.steps.disabled.2"),
                    DshDockBundle.message("dock.offline.steps.disabled.3")
                )
            } else if (stale) {
                listOf(
                    DshDockBundle.message("dock.offline.steps.stale.1"),
                    DshDockBundle.message("dock.offline.steps.stale.2")
                )
            } else {
                listOf(
                    DshDockBundle.message("dock.offline.steps.refused.1"),
                    DshDockBundle.message("dock.offline.steps.refused.2", info.port),
                    DshDockBundle.message("dock.offline.steps.refused.3", detected?.port ?: info.port)
                )
            }
        )
        return DockView(mode = DockView.Mode.OFFLINE, port = info.port, offline = model, pageUrl = pageUrl)
    }

    /** One sentence explaining a failed probe, for the card and the tooltip. */
    private fun probeReason(result: ProbeResult): String = when (result.failure) {
        ProbeFailure.DISABLED -> DshDockBundle.message("probe.disabled")
        ProbeFailure.REFUSED -> DshDockBundle.message("probe.refused")
        ProbeFailure.TIMEOUT -> DshDockBundle.message("probe.timeout")
        ProbeFailure.HTTP -> DshDockBundle.message("probe.http", result.detail)
        ProbeFailure.JSON -> DshDockBundle.message("probe.json")
        ProbeFailure.FOREIGN -> DshDockBundle.message("probe.foreign", result.detail)
        else -> DshDockBundle.message("probe.other", result.detail)
    }

    private fun sourceNote(info: QuickDockPorts.PortInfo): String = when (info.source) {
        QuickDockPorts.Source.SETTING -> DshDockBundle.message("port.source.setting")
        QuickDockPorts.Source.CONFIG_FILE -> DshDockBundle.message("port.source.config")
        QuickDockPorts.Source.LOG -> DshDockBundle.message("port.source.log")
        QuickDockPorts.Source.DEFAULT -> DshDockBundle.message("port.source.default")
    }

    /* ------------------------------- the actions ------------------------------ */

    /** A button inside the dock page (offline card, "open in browser", …). */
    fun runDockAction(action: String) {
        when (action) {
            "startDsh" -> startDsh()
            "retry" -> reload(force = true)
            "useDetectedPort" -> useDetectedPort()
            "openPortSettings" -> openPortSettings()
            "openLog" -> openLog()
            "openInBrowser" -> openInBrowser()
            else -> log.info("dock: unknown page action '$action'")
        }
    }

    /** Open the shell page in the system browser (the remote/Gateway fallback). */
    fun openInBrowser() {
        val info = resolvePort()
        if (info.port <= 0) {
            notify(NotificationType.WARNING, DshDockBundle.message("notify.listenerOff.title"), DshDockBundle.message("notify.listenerOff.text"))
            return
        }
        val url = QuickDockUrls.pageUrl(info.port, QuickDockUrls.BROWSER_VIEW)
        log.info("dock: opening $url in the browser")
        BrowserUtil.browse(url)
    }

    /** Apply the port QuickDock's own configuration reports. */
    fun useDetectedPort() {
        val detected = detected()
        if (detected == null) {
            notify(
                NotificationType.INFORMATION,
                DshDockBundle.message("notify.noConfig.title"),
                DshDockBundle.message("notify.noConfig.text", QuickDockPorts.configFile().absolutePath)
            )
            return
        }
        if (detected.port <= 0) {
            notify(
                NotificationType.WARNING,
                DshDockBundle.message("notify.portZero.title"),
                DshDockBundle.message("notify.portZero.text", detected.file.absolutePath)
            )
            return
        }
        settings.state.port = detected.port
        settings.state.followQuickDockConfig = true
        log.info("dock: using the port ${detected.port} from ${detected.file}")
        notify(
            NotificationType.INFORMATION,
            DshDockBundle.message("notify.portApplied.title"),
            DshDockBundle.message("notify.portApplied.text", detected.port, detected.file.absolutePath)
        )
        onSettingsApplied()
    }

    fun openPortSettings() {
        onUi {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, DshDockConfigurable::class.java)
        }
    }

    /** Reveal QuickDock's `host.log`, next to its `config.json`. */
    fun openLog() {
        val file = QuickDockPorts.hostLogFile()
        if (!file.isFile) {
            notify(
                NotificationType.INFORMATION,
                DshDockBundle.message("notify.noLog.title"),
                DshDockBundle.message("notify.noLog.text", file.absolutePath)
            )
            return
        }
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        if (vf == null) {
            BrowserUtil.browse(file.parentFile?.absolutePath.orEmpty())
            return
        }
        onUi {
            FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vf), true)
        }
    }

    /** Review the active file against its review baseline (or git HEAD-less: cached text). */
    fun openDiffForActiveFile() {
        worker.execute {
            val path = activeEditorPath() ?: run {
                notify(
                    NotificationType.INFORMATION,
                    DshDockBundle.message("notify.noActiveFile.title"),
                    DshDockBundle.message("notify.noActiveFile.text")
                )
                return@execute
            }
            val result = ReviewDiff.open(project, path, original = null, title = Paths.fileName(path))
            if (!result.opened) {
                notify(
                    NotificationType.INFORMATION,
                    DshDockBundle.message("notify.noDiff.title"),
                    DshDockBundle.message("notify.noDiff.text", Paths.fileName(path), result.reason ?: "")
                )
            }
        }
    }

    /** The file in the selected editor, resolved for the tools and the diff action. */
    fun activeEditorPath(): String? = ReadAction.compute<String?, RuntimeException> {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@compute null
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getFile(editor.document)?.path
    }

    /* --------------------------------- review --------------------------------- */

    /** Whether a change made outside the IDE opens a review diff. */
    fun reviewEnabled(): Boolean = settings.state.reviewEdits

    fun toggleReview() {
        settings.state.reviewEdits = !settings.state.reviewEdits
        syncReview()
        notify(
            NotificationType.INFORMATION,
            DshDockBundle.message("notify.review.title"),
            if (settings.state.reviewEdits) {
                DshDockBundle.message("notify.review.on")
            } else {
                DshDockBundle.message("notify.review.off")
            }
        )
    }

    /** Start or stop the watcher to match the setting. */
    private fun syncReview() {
        val wanted = settings.state.reviewEdits
        if (!wanted) {
            review?.let {
                Disposer.dispose(it)
                review = null
            }
            return
        }
        if (review != null) return
        val watcher = cc.futurvo.ideadock.editor.EditReviewWatcher(
            project = project,
            host = cc.futurvo.ideadock.editor.EditReviewWatcher.Host(
                enabled = { settings.state.reviewEdits },
                pairedFolders = { mcp?.registered?.keys?.toList().orEmpty() },
                log = ::logLine
            )
        )
        Disposer.register(this, watcher)
        review = watcher
        watcher.start()
    }

    /* ----------------------------------- mcp ---------------------------------- */

    fun mcpRunning(): Boolean = mcp != null

    fun mcpUrl(): String? = mcp?.server?.url

    /** The dock's title bar switch. */
    fun toggleToolsFromDock() {
        worker.execute { toggleTools() }
    }

    fun toggleTools() {
        if (mcp != null) stopTools() else startTools(announce = true)
    }

    /** Start the server, then pair it; both off the EDT. */
    private fun startTools(announce: Boolean) {
        if (mcp != null || mcpStarting) return
        mcpStarting = true
        pushMcpState(false, DshDockBundle.message("mcp.starting"))
        try {
            val token = ensureToken()
            val write = settings.state.mcpWriteTools
            val tools = IdeTools.build(project, ::logLine, write)
            val server = cc.futurvo.ideadock.contract.EditorMcpServer(
                token = token,
                version = pluginVersion(),
                tools = tools,
                log = ::logLine
            )
            server.start(settings.state.mcpPort)
            mcp = McpState(
                server = server,
                token = token,
                fingerprint = McpProtocol.fingerprint(tools),
                toolNames = tools.map { it.name }
            )
            settings.state.mcp = true
            startBeat()
            syncReview()
            syncMcp("start", announce)
            // Every IDE used to register under the one name `intellij`; drop that
            // registration so DSH does not mount both `mcp__intellij__*` and
            // `mcp__<ide>__*` for the same workspace.
            dropLegacyNamespace()
            pushMcpState(true, mcpDetail())
            log.info("mcp: started on ${server.url} with ${tools.size} tool(s)")
        } catch (error: Throwable) {
            log.warn("mcp: cannot start", error)
            mcp = null
            mcpFailure = error.message ?: error.javaClass.simpleName
            pushMcpState(false, DshDockBundle.message("mcp.failed", mcpFailure))
            notify(
                NotificationType.ERROR,
                DshDockBundle.message("mcp.failed.title"),
                DshDockBundle.message("mcp.failed.text", mcpFailure)
            )
        } finally {
            mcpStarting = false
        }
    }

    /** Stop the server and withdraw its tools from DSH. */
    fun stopTools() {
        val state = mcp ?: return
        mcp = null
        stopBeat()
        state.server.close()
        val port = resolvePort().port
        for (folder in state.registered.keys.toList()) {
            val answer = pairing.unregister(port, folder, IdeIdentity.slug)
            if (!answer.ok) log.info("mcp: cannot unregister $folder: ${answer.error}")
        }
        state.registered.clear()
        settings.state.mcp = false
        mcpFailure = ""
        syncReview()
        pushMcpState(false, "")
        log.info("mcp: stopped")
    }

    /** Remove the pre-slug registration (`name=intellij`) for every root folder. */
    private fun dropLegacyNamespace() {
        if (IdeIdentity.slug == Pairing.TOOL_NAMESPACE) return
        val port = resolvePort().port
        if (port <= 0) return
        for (folder in contentRoots()) {
            val answer = pairing.unregister(port, folder, Pairing.TOOL_NAMESPACE)
            if (answer.ok) log.info("mcp: dropped the legacy intellij registration for $folder")
        }
    }

    private fun startBeat() {
        if (beat != null) return
        beat = worker.scheduleWithFixedDelay(
            { safely("pairing beat") { syncMcp("heartbeat", announce = false) } },
            MCP_BEAT_SECONDS, MCP_BEAT_SECONDS, TimeUnit.SECONDS
        )
    }

    private fun stopBeat() {
        beat?.cancel(false)
        beat = null
    }

    /**
     * Reconcile DSH with this editor: register the folders that are missing,
     * unregister the folders the user closed, and force one re-registration when
     * the tool set changed.
     */
    private fun syncMcp(reason: String, announce: Boolean) {
        val state = mcp ?: return
        if (mcpSyncing) return
        mcpSyncing = true
        try {
            val info = resolvePort()
            val folders = contentRoots()
            val fingerprint = state.fingerprint
            val toolsChanged = settings.state.toolFingerprint != fingerprint

            for (folder in state.registered.keys.toList()) {
                if (folders.any { Paths.samePath(it, folder) }) continue
                val dropped = pairing.unregister(info.port, folder, IdeIdentity.slug)
                state.registered.remove(folder)
                log.info(
                    if (dropped.ok) "mcp: unregistered $folder (folder closed)"
                    else "mcp: cannot unregister $folder: ${dropped.error}"
                )
            }
            if (folders.isEmpty()) {
                noteMcp(DshDockBundle.message("mcp.noFolder", reason), announce)
                return
            }
            for (folder in folders) {
                val registration = Pairing.Registration(
                    workspace = folder,
                    url = state.server.url,
                    name = IdeIdentity.slug,
                    headers = mapOf("Authorization" to "Bearer ${state.token}"),
                    version = pluginVersion(),
                    tools = state.toolNames,
                    // Only the first beat waits for live sessions; a heartbeat that
                    // re-registered after a DSH restart reports `connecting` and moves on.
                    waitMs = if (reason == "start") 5_000 else 1_000
                )
                val listed = pairing.list(info.port, folder)
                if (!listed.ok) {
                    noteMcp(
                        DshDockBundle.message(
                            "mcp.cannotAsk",
                            info.port, folder,
                            if (listed.status == 404) dockUpgradeHint(listed.error) else (listed.error ?: ""),
                            reason
                        ),
                        announce
                    )
                    return
                }
                if (!toolsChanged && !Pairing.needsRegistration(listed.body, registration)) {
                    state.registered[folder] = registration.url
                    mcpFailure = ""
                    continue
                }
                val answer = pairing.register(info.port, registration)
                if (!answer.ok) {
                    noteMcp(
                        if (answer.status == 404) dockUpgradeHint(answer.error)
                        else DshDockBundle.message("mcp.rejected", folder, answer.error ?: "", reason),
                        announce
                    )
                    continue
                }
                state.registered[folder] = registration.url
                mcpFailure = ""
                if (toolsChanged) settings.state.toolFingerprint = fingerprint
                val sessions = answer.body?.sessions.orEmpty()
                val failed = sessions.count { it.state == "failed" }
                log.info(
                    "mcp: paired $folder -> ${registration.url} (${sessions.size} session(s)" +
                        (if (failed > 0) ", $failed failed" else "") +
                        (if (toolsChanged) ", new tool set" else "") + ")"
                )
                for (session in sessions.filter { it.state == "failed" }) {
                    log.info("mcp: session ${session.sessionId} could not connect: ${session.error ?: "unknown error"}")
                }
            }
            pushMcpState(true, mcpDetail())
        } finally {
            mcpSyncing = false
        }
    }

    /** A 404 from the dock means the plugin predates the pairing routes. */
    private fun dockUpgradeHint(detail: String?): String =
        DshDockBundle.message("mcp.dockUpgrade", detail.orEmpty())

    /** Log a pairing problem once per distinct message; a 20 s beat must not spam. */
    private fun noteMcp(message: String, announce: Boolean) {
        if (message == mcpFailure) return
        mcpFailure = message
        log.info("mcp: $message")
        if (!announce) return
        notify(NotificationType.WARNING, DshDockBundle.message("mcp.notAccepted.title"), message)
    }

    /** Push the switch state into the dock's title bar (must run on the EDT). */
    fun pushMcpState(on: Boolean, detail: String) {
        onUi { panel?.pushMcpState(on, detail) }
    }

    private fun mcpDetail(): String {
        val state = mcp ?: return ""
        val count = state.toolNames.size
        val folders = state.registered.size
        return if (mcpFailure.isNotBlank()) {
            DshDockBundle.message("mcp.detail.error", count, folders, mcpFailure)
        } else {
            DshDockBundle.message("mcp.detail", count, folders)
        }
    }

    /** Rewrite the dock page's switch when the tool state changes. */
    fun refreshPanelState() {
        val view = lastView ?: return
        if (view.mode == DockView.Mode.FRAME) {
            onUi { panel?.pushMcpState(mcp != null, mcpDetail()) }
        }
    }

    /**
     * The dock's conversation asked for a file: open it here.
     *
     * QuickDock appends the clicked element's tag/class after a NUL byte, so only
     * the part before the first NUL is a path - using the whole string is the
     * classic "cannot open file" bug.
     */
    fun openFileFromDock(raw: String) {
        val target = raw.substringBefore('\u0000').trim()
        val source = raw.substringAfter('\u0000', "")
        val detail = if (source.isNotBlank()) " [$source]" else ""
        if (target.isEmpty()) {
            log.info("dock: click probe$detail")
            return
        }
        val folders = contentRoots()
        val candidates = if (Paths.isAbsolute(target)) {
            listOf(target)
        } else {
            folders.map { Paths.resolve(it, target) }
        }
        worker.execute {
            val vfs = LocalFileSystem.getInstance()
            for (candidate in candidates) {
                // VFS paths are `/`-separated; the dock may send either separator,
                // and the file may be new (the agent just wrote it): refresh first.
                val file = vfs.refreshAndFindFileByPath(FileUtil.toSystemIndependentName(candidate))
                if (file != null && !file.isDirectory) {
                    openInEditor(file)
                    log.info("dock: opened $candidate$detail")
                    return@execute
                }
            }
            log.info(
                "dock: no file matches $target$detail" +
                    if (folders.isEmpty()) " (no content root is open)" else ""
            )
        }
    }


    /** Open one file at a line in the editor (EDT). */
    fun openInEditor(file: VirtualFile, line: Int = 1, column: Int = 1) {
        onUi {
            FileEditorManager.getInstance(project)
                .openTextEditor(OpenFileDescriptor(project, file, line - 1, column - 1), true)
        }
    }

    /** Open an arbitrary URL in the system browser. */
    fun browse(url: String) {
        BrowserUtil.browse(url)
    }

    /* ---------------------------------- dsh ----------------------------------- */

    /**
     * Boot DSH in the Run tool window when nothing answers.
     *
     * Never behind the user's back: this is what the offline card's primary
     * button does, the command is visible in the Run window, and the dock reloads
     * by itself once the listener answers.
     */
    fun startDsh() {
        if (dshStarting) return
        worker.execute {
            safely("start dsh") {
                val info = resolvePort()
                if (info.port <= 0) {
                    notify(
                        NotificationType.INFORMATION,
                        DshDockBundle.message("notify.listenerOff.title"),
                        DshDockBundle.message("notify.listenerOff.text")
                    )
                    return@safely
                }
                val current = probeCached(info.port, force = true)
                if (current.ok) {
                    notify(
                        NotificationType.INFORMATION,
                        DshDockBundle.message("notify.alreadyRunning.title"),
                        DshDockBundle.message("notify.alreadyRunning.text", info.port)
                    )
                    openDock()
                    return@safely
                }
                val command = settings.state.dshCommand.ifBlank { "dsh" }
                if (DshLauncher.looksLikePath(command) && !File(command).exists()) {
                    notify(
                        NotificationType.ERROR,
                        DshDockBundle.message("notify.dshMissing.title"),
                        DshDockBundle.message("notify.dshMissing.text", command),
                        actions = listOf(openSettingsAction(DshDockBundle.message("action.openSettings")))
                    )
                    return@safely
                }
                val profile = chooseProfile() ?: return@safely
                val line = try {
                    DshLauncher.commandLine(command, profile, settings.state.dshArgs)
                } catch (error: IllegalArgumentException) {
                    notify(
                        NotificationType.ERROR,
                        DshDockBundle.message("notify.badProfile.title"),
                        error.message ?: ""
                    )
                    return@safely
                }
                dshStarting = true
                try {
                    launchInRunWindow(line)
                    notify(
                        NotificationType.INFORMATION,
                        DshDockBundle.message("notify.starting.title"),
                        DshDockBundle.message("notify.starting.text", line)
                    )
                    waitForDsh(info.port)
                } finally {
                    dshStarting = false
                }
            }
        }
    }

    /** Which profile to boot: the configured one, or a picker when it is unset. */
    private fun chooseProfile(): String? {
        val configured = settings.state.dshProfile.trim()
        if (configured.isNotEmpty()) return configured
        val home = DshLauncher.dshHome()
        val profiles = DshLauncher.listProfiles(home)
        if (profiles.isEmpty()) {
            notify(
                NotificationType.ERROR,
                DshDockBundle.message("notify.noProfile.title"),
                DshDockBundle.message("notify.noProfile.text", File(home, "profiles").absolutePath),
                actions = listOf(openSettingsAction(DshDockBundle.message("action.openSettings")))
            )
            return null
        }
        if (profiles.size == 1) return profiles.first()
        var picked: String? = null
        onUiAndWait {
            picked = Messages.showEditableChooseDialog(
                DshDockBundle.message("dsh.chooseProfile.text"),
                DshDockBundle.message("dsh.chooseProfile.title"),
                Messages.getQuestionIcon(),
                profiles.toTypedArray(),
                profiles.first(),
                null
            )
        }
        return picked
    }

    /** Send the launch line to the Run tool window, visibly. */
    private fun launchInRunWindow(line: String) {
        onUi {
            try {
                val commandLine = GeneralCommandLine(*DshLauncher.shellCommand(line).toTypedArray())
                val handler = OSProcessHandler(commandLine)
                val executor = RunContentExecutor(project, handler)
                    .withTitle("DSH")
                    .withActivateToolWindow(true)
                Disposer.register(this, executor)
                executor.run()
                log.info("dsh: started \"$line\" in the Run tool window")
            } catch (error: Throwable) {
                log.warn("dsh: cannot start \"$line\"", error)
                notify(
                    NotificationType.ERROR,
                    DshDockBundle.message("notify.startFailed.title"),
                    error.message ?: error.javaClass.simpleName
                )
            }
        }
    }

    /** Poll the listener until DSH answers, or give up with a hint. */
    private fun waitForDsh(port: Int) {
        val deadline = System.currentTimeMillis() + DSH_START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(DSH_START_POLL_MS)
            val result = probeCached(port, force = true)
            if (!result.ok) continue
            log.info("dsh: answering on $port after the launch")
            notify(
                NotificationType.INFORMATION,
                DshDockBundle.message("notify.dshUp.title"),
                DshDockBundle.message("notify.dshUp.text")
            )
            reload(force = true)
            return
        }
        log.info("dsh: no answer on $port within ${DSH_START_TIMEOUT_MS / 1000} s")
        notify(
            NotificationType.WARNING,
            DshDockBundle.message("notify.dshTimeout.title"),
            DshDockBundle.message("notify.dshTimeout.text", DSH_START_TIMEOUT_MS / 1000)
        )
    }

    /** Show what this dock published to DSH (the bar's list button). */
    fun showToolsInfo(anchor: java.awt.Component) {
        worker.execute {
            val port = resolvePort().port
            val folder = contentRoots().firstOrNull()
            val answer = if (port > 0) pairing.mcpServers(port, folder.orEmpty()) else null
            onUi {
                val panel = DockToolsPanel.build(
                    folder,
                    answer?.body?.servers.orEmpty(),
                    when {
                        answer == null -> DshDockBundle.message("dock.info.disabled")
                        answer.ok -> null
                        else -> answer.error ?: DshDockBundle.message("dock.info.unknown")
                    }
                )
                JBPopupFactory.getInstance()
                    .createComponentPopupBuilder(panel, null)
                    .setRequestFocus(false)
                    .setResizable(false)
                    .setMovable(false)
                    .setCancelOnClickOutside(true)
                    // The content carries its own fixed preferred size
                    // (DockToolsPanel), so the popup cannot grow to the widest row.
                    .createPopup()
                    .showUnderneathOf(anchor)
            }
        }
    }

    /* ----------------------------- top-level dock ----------------------------- */

    /**
     * The app URL the Host serves, or null when this QuickDock predates the route.
     *
     * Asked once per view build (loopback, ~1 ms): the app proxy's origin can
     * change when DSH restarts, and the URL carries that origin.
     */
    private fun appUrl(port: Int): String? {
        val answer = pairing.appUrl(port)
        if (answer.ok && !answer.body.isNullOrBlank()) return answer.body
        if (answer.status == 404) {
            log.info("dock: this QuickDock has no app-url route; using the shell page")
            // Without the route the app stays in an iframe, where JCEF cannot
            // commit a Chinese input method. Say so once: the fix is on the DSH
            // side (update the dsh-quickdock plugin, then restart DSH).
            if (!warnedAboutAppUrl) {
                warnedAboutAppUrl = true
                notify(
                    NotificationType.INFORMATION,
                    DshDockBundle.message("notify.appUrl.title"),
                    DshDockBundle.message("notify.appUrl.text")
                )
            }
        } else if (!answer.ok) {
            log.info("dock: app-url failed (${answer.error}); using the shell page")
        }
        return null
    }

    /**
     * The app reported the session it shows (`quickdock:session`).
     *
     * Keep the Host's binding on it: the Host computes
     * `dock-target.workspace.matches` - the warning that this session works
     * outside the folders this window edits - from the binding, and the binding
     * is host memory. The framed shell page does this from the same message; with
     * the app top-level there is no shell page, so this plugin is the shell.
     */
    fun dockSessionReported(sessionId: String) {
        val clean = sessionId.trim()
        if (clean == reportedSession) return
        reportedSession = clean
        if (clean.isEmpty()) return
        // The app is already showing it: a follow beat must not navigate again.
        followSession = clean
        bindDockSession(clean)
    }

    /** Bind the Host to [sessionId], quietly; a failed bind is retried later. */
    private fun bindDockSession(sessionId: String) {
        if (sessionId.isBlank() || sessionId == boundSession) return
        boundSession = sessionId
        worker.execute {
            safely("dock bind") {
                val port = resolvePort().port
                if (port <= 0) return@safely
                val answer = pairing.bind(port, dockId(), sessionId)
                if (!answer.ok) {
                    boundSession = ""
                    log.info("dock: could not bind $sessionId: ${answer.error}")
                }
            }
        }
    }

    /** Ask the app to show the session the Dock button bound, and follow reloads. */
    fun followNow() {
        worker.execute { safely("dock follow") { followTarget() } }
    }

    private fun startFollow() {
        if (followBeat != null || disposed) return
        followBeat = worker.scheduleWithFixedDelay(
            { safely("dock follow") { followTarget() } },
            0, FOLLOW_MS, TimeUnit.MILLISECONDS
        )
    }

    /**
     * One beat of "which session should this dock show".
     *
     * The shell page used to do this from inside the browser; with the app
     * top-level there is no shell page, so the plugin asks the Host directly and
     * forwards the answer to the app (see `DockPanel.openSession`).
     */
    private fun followTarget() {
        val port = resolvePort().port
        if (port <= 0) return
        val answer = pairing.dockTarget(port, dockId())
        val target = answer.body
        if (!answer.ok || target == null) {
            hostDown = true
            return
        }
        // The Host restarted: its app proxy has a new origin, so the page on
        // screen points at a dead port. Rebuild the view to pick the new URL up.
        if (hostDown) {
            hostDown = false
            log.info("dock: host is back; rebuilding the view")
            reload(force = true)
            return
        }
        // The Host forgets bindings when it restarts; the app does not re-report
        // the session it kept showing, so restore it from the last report. Only
        // when the Host has no target at all: a fresh, deliberate bind from the
        // main window must win over a stale report.
        if (target.sessionId.isBlank() && reportedSession.isNotBlank()) {
            bindDockSession(reportedSession)
        }
        // The user asked for a reload in the main window: reload the app once.
        if (followReloadSeq >= 0 && target.reloadSeq != followReloadSeq) {
            followReloadSeq = target.reloadSeq
            onUi { panel?.reloadApp() }
            return
        }
        followReloadSeq = target.reloadSeq
        val warning = target.workspaceMatches == false
        if (warning != followWarning) {
            followWarning = warning
            onUi { panel?.setWorkspaceWarning(warning) }
        }
        if (target.sessionId.isNotBlank() && target.sessionId != followSession) {
            followSession = target.sessionId
            onUi { panel?.openSession(target.sessionId) }
        }
    }

    /** Show this session in the main DSH window (the dock's handoff button). */
    fun handoffToMain(sessionId: String) {
        if (sessionId.isBlank()) return
        worker.execute {
            safely("dock handoff") {
                val port = resolvePort().port
                if (port <= 0) return@safely
                val answer = pairing.handoff(port, sessionId)
                if (!answer.ok) log.info("dock: handoff failed: ${answer.error}")
            }
        }
    }

    /* -------------------------------- settings -------------------------------- */

    /** Called by the settings page after Apply. */
    fun onSettingsApplied() {
        worker.execute {
            val writeChanged = settings.state.mcpWriteTools != writeToolsAtStart()
            if (settings.state.mcp && mcp == null) startTools(announce = false)
            if (!settings.state.mcp && mcp != null) stopTools()
            if (mcp != null && writeChanged) {
                // The tool list is part of the server: bounce it so DSH remounts.
                log.info("mcp: write tools changed; restarting the server")
                stopTools()
                startTools(announce = false)
            } else if (mcp != null) {
                syncMcp("settings", announce = false)
            }
            syncReview()
        }
        reload(force = true)
        pushMcpState(mcp != null, mcpDetail())
    }

    @Volatile
    private var writeToolsSnapshot: Boolean? = null

    private fun writeToolsAtStart(): Boolean {
        val snapshot = writeToolsSnapshot
        if (snapshot == null) {
            writeToolsSnapshot = settings.state.mcpWriteTools
            return settings.state.mcpWriteTools
        }
        return snapshot
    }

    /* --------------------------------- helpers -------------------------------- */

    private fun pluginVersion(): String = try {
        com.intellij.ide.plugins.PluginManagerCore
            .getPlugin(com.intellij.openapi.extensions.PluginId.getId(PLUGIN_ID))
            ?.version ?: "0.1.0"
    } catch (error: Throwable) {
        "0.1.0"
    }

    private fun ensureToken(): String {
        val existing = settings.state.mcpToken
        if (existing.isNotEmpty() && existing.length <= 200) return existing
        val token = Pairing.newToken()
        settings.state.mcpToken = token
        return token
    }

    private fun jcefSupported(): Boolean = try {
        com.intellij.ui.jcef.JBCefApp.isSupported()
    } catch (error: Throwable) {
        false
    }

    private fun jcefUnavailableReasons(): List<String> = buildList {
        add(DshDockBundle.message("dock.jcef.reason.jbr"))
        add(DshDockBundle.message("dock.jcef.reason.plugin"))
        add(DshDockBundle.message("dock.jcef.reason.remote"))
    }

    /** Post a UI update that is not blocked by a modal dialog. */
    private fun onUi(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())
    }

    private fun onUiAndWait(block: () -> Unit) {
        if (ApplicationManager.getApplication().isDispatchThread) {
            block()
        } else {
            ApplicationManager.getApplication().invokeAndWait(block, ModalityState.any())
        }
    }

    /** Send one notification; never throws into a background task. */
    fun notify(
        type: NotificationType,
        title: String,
        text: String,
        actions: List<NotificationAction> = emptyList()
    ) {
        onUi {
            try {
                val notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(title, text, type)
                for (action in actions) notification.addAction(action)
                notification.notify(project)
            } catch (error: Throwable) {
                log.warn("dock: cannot show a notification: ${error.message}")
            }
        }
    }

    private fun openSettingsAction(label: String): NotificationAction =
        NotificationAction.createSimple(label) { openPortSettings() }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            log.warn("dsh-dock: $what failed", error)
        }
    }

    override fun dispose() {
        disposed = true
        statusBeat?.cancel(false)
        followBeat?.cancel(false)
        stopBeat()
        val state = mcp
        mcp = null
        if (state != null) {
            val port = try {
                resolvePort().port
            } catch (error: Throwable) {
                0
            }
            for (folder in state.registered.keys.toList()) {
                try {
                    pairing.unregister(port, folder, IdeIdentity.slug)
                } catch (error: Throwable) {
                    log.info("mcp: cannot unregister $folder on close: ${error.message}")
                }
            }
            state.server.close()
        }
        review = null
        statusListeners.clear()
        worker.shutdownNow()
        log.info("dsh-dock: service disposed for ${project.name}")
    }

    companion object {
        const val TOOL_WINDOW_ID: String = "DSH Dock"
        const val NOTIFICATION_GROUP: String = "DSH Dock"
        const val PLUGIN_ID: String = "dsh.ideadock"
        private const val PROBE_CACHE_MS: Long = 4_000
        private const val STATUS_REFRESH_SECONDS: Long = 20
        /** How often a top-level dock asks the Host which session it should show. */
        private const val FOLLOW_MS: Long = 1_200
        private const val MCP_BEAT_SECONDS: Long = 20
        private const val DSH_START_TIMEOUT_MS: Long = 90_000
        private const val DSH_START_POLL_MS: Long = 1_500

        fun getInstance(project: Project): DockService = project.service<DockService>()
    }
}
