# DSH Dock for JetBrains IDEs

**DeepSeek Harness's conversation in a JetBrains tool window — with editor tools the agent can use.**

DSH Dock puts DSH's own conversation UI next to the code it is working on, and opens a door
back into the IDE: a path clicked in the conversation opens in the editor, changed files get a
review diff, and with the plug in the title bar turned on the agent gets read-only (and
optionally write) tools that run inside the IDE. It is a client of **dsh-quickdock**, the
plugin that runs inside DSH.

## Features

- **The real conversation, in a tool window.** Right-anchored, `Ctrl+Alt+D` / `Cmd+Alt+D` —
  tool cards, approvals, model and permission controls, not a re-implementation.
- **Typing that works.** The app is loaded as the browser's top-level document instead of an
  iframe, so Chinese input methods commit normally; the title bar is drawn by the plugin.
- **Files open in the IDE.** A file path in a tool card opens in the editor, not in the dock's
  own preview. When DSH creates or changes a file, a review diff opens as a notification — it
  never steals focus.
- **Editor tools for the agent.** Thirteen read-only tools run in the IDE: workspace folders,
  selection, file contents (unsaved buffers included), diagnostics, symbols, search, list
  files by pattern, definitions and references, hover docs, code actions, reveal, and review
  diff. Five write tools (edit, save, run configuration, terminal, start debug) are off by
  default.
- **A namespace per IDE.** Tools are namespaced by the host product — `mcp__idea__*` in IntelliJ
  IDEA, `mcp__clion__*` in CLion, `mcp__pycharm__*` in PyCharm, and so on — so several docks in
  different IDEs stay distinguishable in DSH.
- **Scoped to your folders.** Tools are mounted only for sessions working inside a folder this
  window has open; otherwise the title bar shows an amber warning triangle, and the list button
  shows what was published and what is mounted.
- **Never a blank panel.** When DSH is not reachable, the tool window explains the port and
  reason, and offers Start DSH, Retry, connection settings, open-in-browser and the QuickDock
  log. Remote development / Gateway degrades to the browser instead of failing.

## How the docks fit together

DSH Dock for JetBrains IDEs is one of three editor docks for the same page. **It requires DSH
with the [dsh-quickdock](https://github.com/CoplenSasbian/dsh-quickdock) plugin installed** —
QuickDock serves the conversation and the MCP bridge this plugin registers its tools with.

| Project | Runs in | What it is |
|---|---|---|
| [dsh-quickdock](https://github.com/CoplenSasbian/dsh-quickdock) | DSH | The host half: serves the conversation as a loopback page and the MCP bridge the editor docks register with. **Required by every editor dock.** |
| [DSH Dock for VS Code](https://github.com/CoplenSasbian/dsh-vsdock) | VS Code | The same page in the secondary side bar, plus VS Code editor tools. |
| [DSH Dock for JetBrains IDEs](https://github.com/CoplenSasbian/dsh-intellijdock) | IntelliJ IDEA, CLion, PyCharm, WebStorm, GoLand, … | The same page in a tool window, plus JetBrains editor tools. |
| [DSH Dock for Visual Studio](https://github.com/CoplenSasbian/dsh-visualstudio) | Visual Studio 2026 | The same page in a tool window (the editor-tools half is next). |

The editor docks can be connected to the same folder at the same time: their tool namespaces
(`vscode_*`, `mcp__<ide>__*`) do not collide.

## Requirements

- DSH (desktop or `dsh web`) with **dsh-quickdock 0.6.0+** — it needs the `app-url` route and
  the `view=intellij` client; older builds fall back to a framed page.
- A JetBrains IDE 2025.3 or newer (IntelliJ IDEA, CLion, PyCharm, WebStorm, GoLand, …) with the
  bundled JCEF runtime. Remote development / Gateway is detected and falls back to the browser.
- QuickDock's listener reachable on `http://127.0.0.1:47910`, or the port configured in
  DSH → **Settings → QuickDock → Connection**.

## Install

**Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the plugin zip from
[Releases](https://github.com/CoplenSasbian/dsh-intellijdock/releases) (or
`build/distributions` in a checkout), restart the IDE, then open **Tools → DSH Dock**
(`Ctrl+Alt+D` / `Cmd+Alt+D`).

## License

MIT — see [LICENSE](LICENSE).
