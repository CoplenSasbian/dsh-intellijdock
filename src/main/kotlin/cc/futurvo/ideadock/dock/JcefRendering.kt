package cc.futurvo.ideadock.dock

/**
 * Which rendering mode the dock's JCEF browser is created with.
 *
 * The platform defaults to off-screen rendering (`ide.browser.jcef.osr.enabled`,
 * true since 2025.1). Both modes have a Chinese input method problem, but not the
 * same one:
 *
 * - in **OSR** an IME cannot commit into a page at all on some platforms (the
 *   macOS report below), so the dock asks for windowed rendering;
 * - in **either** mode a commit into a *subframe* is dropped - the caret blinks,
 *   the candidate window opens, the character never arrives, while English typing
 *   keeps working. That is the bug that mattered here, and it is why the dock
 *   stopped framing the app: `DockView.topLevel` loads the DSH app as the
 *   document itself, so its composer is in the main frame.
 *
 * Reported against 2025.1 and still open for 2026.x
 * ([JetBrains community](https://intellij-support.jetbrains.com/hc/en-us/community/posts/23893207279506-JBCefBrowser-load-iframe-HTML-unable-to-input-Chinese-Input),
 * [platform forum](https://platform.jetbrains.com/t/the-jcef-embedded-iframe-webpage-in-the-idea-2025-1-version-does-not-support-chinese-input/2383)).
 *
 * Nothing here touches the platform: the decision is a pure function of the
 * global registry value and the user's setting, so it is unit-tested without an
 * IDE.
 */
object JcefRendering {

    /**
     * Whether to ask for an off-screen browser.
     *
     * @param globalEnabled `JBCefApp.isOffScreenRenderingModeEnabled()`: when the
     *   platform itself forbids OSR (the registry key is false), creating an OSR
     *   browser throws, so windowed is the only option.
     * @param windowed the user's setting: ask for windowed rendering instead.
     */
    fun offscreen(globalEnabled: Boolean, windowed: Boolean): Boolean = globalEnabled && !windowed
}
