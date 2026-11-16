# 04 · 反向 MCP server 与配对

对应源文件：`dsh-vsdock/src/mcp.ts`（501 行，协议 + 配对客户端 + server）与
`src/extension.ts` 的 `startMcp` / `syncMcp` / `toolFingerprint`。

## 1. 为什么是"反向"的

DSH 里的 agent 要读你的编辑器，只能通过 MCP。但 DSH 不知道 IDE 在哪、也不该拿到 IDE 的凭据，
所以方向反过来：

```
IDE 启动一个 loopback MCP server  →  把自己的 URL + token 注册给 QuickDock
                                          ↓
                            QuickDock 转发给 DSH 的 workspace MCP bridge
                                          ↓
                    cwd 在该目录下的会话挂载 mcp__intellij__* 工具
```

好处（契约文档里写明的）：不需要改 DSH profile、不需要 DSH 重启、工具按目录生效。
注册在内存里，所以**心跳就是生命周期**。

## 2. HTTP 表面（实现时要一字不差）

| 请求 | 应答 |
|---|---|
| `GET /health`（无鉴权） | 200 `{ "ok": true, "version": "0.1.0", "tools": 17 }` |
| `POST /mcp`（bearer） | 200 JSON，或 202（纯通知批） |
| `GET /mcp` | 405 + `Allow: POST, DELETE` |
| `DELETE /mcp` | 204 |
| 其他路径 | 404 `{ "error": "not found" }` |
| 鉴权失败（任何路径，含 /health 以外） | 401 `{ "error": "unauthorized" }` |

## 3. Kotlin 骨架

```kotlin
class EditorMcpServer(
    private val token: String,
    private val version: String,
    private val tools: List<IdeTool>,
    private val log: (String) -> Unit
) : Disposable {

    private var server: HttpServer? = null
    var port: Int = 0; private set
    val url: String get() = "http://127.0.0.1:$port/mcp"

    /** @return 实际绑定的端口；首选端口被占时自动换空闲端口。 */
    fun start(preferredPort: Int): Int {
        val addr = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
        val s = try {
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), preferredPort), 0)
        } catch (e: BindException) {
            log("mcp: port $preferredPort taken, falling back to an ephemeral one")
            HttpServer.create(addr, 0)
        }
        s.executor = Executors.newFixedThreadPool(4) { r ->
            Thread(r, "dsh-dock-mcp").apply { isDaemon = true }
        }
        s.createContext("/") { exchange -> handle(exchange) }
        s.start()
        server = s
        port = s.address.port
        log("mcp: listening on $url with ${tools.size} tool(s)")
        return port
    }

    override fun dispose() {
        server?.stop(0)
        server = null
    }
}
```

要点：

- 用 `HttpServer` 的 `Executor` 显式给线程命名，避免和 IDE 的池混在一起；
- `HttpServer` 默认是**多线程**的，工具实现里自己负责 `ReadAction`；
- 每个请求都要有读超时；配对调用（客户端方向）用 `java.net.http.HttpClient` 并设
  `connectTimeout` / `request timeout`；
- 请求体上限 8MB，超了直接 413 而不是 OOM。

## 4. JSON 选型

| 方案 | 优点 | 风险 |
|---|---|---|
| `com.google.gson` | 平台自带、写起来最快 | JetBrains 在逐步移出平台（已有插件因此改成自研 codec） |
| 自研 `JsonCodec` | 零依赖、行为可控 | 要自己写解析器，约 200–300 行 |
| `kotlinx.serialization` | 类型安全 | 平台是否随附要看版本，可能需要 shade |

建议：**先用 Gson，但把它包在 `contract/Json.kt` 后面**（三个函数：`parseObject`、`stringify`、
`stringLiteral`），M3 之后再决定是否替换。这一点和 `deepseek-harness-idea` 的演进路径一致。

## 5. 工具结果约定（照 `src/tools.ts` 抄语义）

- 成功：`content: [{type:"text", text: <人类可读文本或 JSON pretty>}]`；
- 失败：`content: [{type:"text", text:"error: …"}], isError: true`（**不要**用 JSON-RPC error，
  否则模型看不到"为什么"）；
- 每个工具都要：上限（行数/字节/条数）、`truncated: true` 标记、稳定的字段名；
- 文本里给出 1-based 的 `line` / `column`，路径用绝对路径（agent 要在 DSH 沙箱里核对）。

## 6. 配对循环

```kotlin
class DockService(private val project: Project) : Disposable {

    data class McpState(
        val server: EditorMcpServer,
        val token: String,
        /** 已经确认注册成功的目录 → 那次注册的 url */
        val registered: MutableMap<String, String> = mutableMapOf(),
        val fingerprint: String
    )

    private var mcp: McpState? = null
    private var starting = false
    private var syncing = false

    /** 20 秒一拍；用自建单线程 scheduled executor，别占 IDE 的公共池。 */
    private val beat = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "dsh-dock-pairing").apply { isDaemon = true }
    }

    fun start() {
        beat.scheduleWithFixedDelay({
            try { sync("heartbeat") } catch (t: Throwable) { log("pairing beat failed: $t") }
        }, 0, 20, TimeUnit.SECONDS)
    }

    fun toggleTools() {
        if (mcp != null) stopTools() else startTools()
    }

    private fun startTools() {
        if (starting) return
        starting = true
        // 立刻回一条"启动中"，让插头别一直转圈
        dockPanel?.pushMcpState(false, "starting…")
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val token = newToken()                    // 32B base64url
                val server = EditorMcpServer(token, version, IdeTools.all(write = writeToolsOn), ::log)
                server.start(preferredMcpPort)            // 47920 → 0 兜底
                mcp = McpState(server, token, fingerprint = toolFingerprint())
                sync("start", announce = true)
                dockPanel?.pushMcpState(true, "connected")
            } catch (t: Throwable) {
                dockPanel?.pushMcpState(false, "failed: ${t.message}")
            } finally {
                starting = false
            }
        }
    }
}
```

`sync(reason)` 的判定顺序（照 `extension.ts` 抄，别"优化"）：

1. 重新解析端口（可能被用户改了），重新探测（4s 内缓存）；
2. 反注册"已经不在打开列表里的目录"；
3. 没有内容根 → 记一条日志并返回（不打扰）；
4. 逐目录 `mcp-list`；
   - 失败：记日志、返回（QuickDock 没起来不是错误，下一拍会成功；404 要提示"更新插件"）；
5. 指纹变了 **或** 列表里没有同名同 url → `mcp-register`（`waitMs`：首次 5000，心跳 1000）；
6. 注册成功 → 记入 `registered`、更新指纹、记日志（会话数、failed 数）。

## 7. 指纹（工具集升级的自愈）

```kotlin
fun toolFingerprint(): String =
    IdeTools.namesAndSchemas()
        .joinToString("\u0001") { "${it.name}\u0000${it.description}\u0000${it.schemaJson}" }
        .let { sha1Hex(it).take(12) }
```

存在项目级 state 里。DSH 会话记住的是"连接时的工具列表"，换工具集必须重注册一次，
否则升级后 agent 还在用旧工具描述。

## 8. 多窗口、多项目

| 场景 | 处理 |
|---|---|
| 两个 IDE 窗口（两个项目） | 每个项目一个 `DockService`、一个 token、一个 server；端口 47920 被占的那个绑 0 |
| 一个窗口多内容根 | 每个内容根各注册一次（bridge 按目录挂载） |
| 同一目录两个客户端（VS Code + IDEA） | `name` 不同（`vscode` / `intellij`），互不冲突 |
| 同一目录两个 IDEA 窗口 | 同名注册会互相顶掉，下一拍再抢回来——可接受，不加锁 |

`dockId` 建议存项目级 state，`idea-` + 12 位 hex，**首次生成后不再变**（工具窗口关闭重开不变）。

## 9. 安全边界（写进 README，别省）

- 只绑 `127.0.0.1`，没有 remote 模式；
- 每个窗口一个 bearer token，只出现在注册 body 里，不进日志；
- 写工具（编辑/保存/跑任务/终端/调试）**默认关闭**，打开入口只能是设置项（=用户授权）；
- 工具权限 = IDE 能做的所有事，包括未保存缓冲区；这点必须在 README 明说；
- 关闭 MCP 时立刻 `unregister` 所有目录，别留下"幽灵 server"。

## 10. M2 验收清单

- [ ] 打开工具窗口、点插头 → DSH 会话在下一拍拿到工具（`mcp__intellij__*`）
- [ ] 让 agent 调 `intellij_workspace_folders` / `intellij_selection` / `intellij_read_file`，
      返回内容与 IDE 现状一致（含未保存修改）
- [ ] 关闭 IDE 后 DSH 侧注册在 90s 内消失（或用 unregister 立即清）
- [ ] 重启 DSH → 不需要任何操作，20s 内工具恢复
- [ ] 在 DSH 里改 QuickDock 端口 → 下一拍自动跟随
- [ ] 升级插件版本（工具集变化）→ 自动重注册一次，日志可见"new tool set"
