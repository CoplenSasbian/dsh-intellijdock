# 02 · QuickDock 契约（照这份实现，不要猜）

> 真源：`C:\Users\futur\Desktop\dsh-quickdock`（`API.md` / `MCP-BRIDGE.md` / `DOCK-CONTEXT.md` + `host/*.js`）。
> 本文是给 IntelliJ 客户端的**可执行摘要**，每条都能在源码里找到出处（见 §9）。

---

## 1. 端口：从哪来

优先级（与 `dsh-vsdock/src/quickdock.ts` 完全一致，**不要自创**）：

1. 本插件设置项 `dshIdeadock.port`（显式设置才生效）；
2. `dsh-quickdock` 自己的 `config.json` 里的 `port`；
3. 它的 `host.log` 里最后一条 `server listening on http://127.0.0.1:<port>`；
4. 默认 `47910`。

状态目录：

```
DSH_QUICKDOCK_STATE_DIR   （若设置）
else LOCALAPPDATA/dsh-quickdock
else TEMP/dsh-quickdock
```

端口语义：

- `0` 是**合法值**，表示"监听已关闭"——不要当成错误端口，要显示"监听已关闭"的卡片；
- 设置项里的端口必须是 `1..65535`。

## 2. 能力探测：`GET /quickdock/api/meta`

```
GET http://127.0.0.1:<port>/quickdock/api/meta
accept: application/json
connection: close
timeout: 1500ms
```

响应（`name` 必须是 `dsh-quickdock`，否则视为"这个端口上是别的东西"）：

```json
{
  "name": "dsh-quickdock",
  "apiVersion": 2,
  "version": "0.6.0-beta.2",
  "capabilities": ["app-proxy", "dock-contexts", "..."],
  "views": ["electron", "vscode", "browser"],
  "components": { "...": true },
  "mcpBridge": { "enabled": true, "origin": "http://127.0.0.1:58935", "route": "/workspace-mcp", "tokenRequired": true }
}
```

判定规则：

| 条件 | 结论 |
|---|---|
| HTTP 200 + `name == "dsh-quickdock"` | 可用 |
| `capabilities` 含 `"app-proxy"` | 才能嵌壳页；否则显示"更新 QuickDock"卡片 |
| `mcpBridge.enabled == false` | 编辑器工具挂不上，要明确告知 |
| HTTP 非 200 / 非 JSON / `name` 不符 | 分别归类为 `http` / `json` / `foreign` |

失败分类（用于离线卡片文案，照 `ProbeFailure` 抄）：`refused`、`timeout`、`unreachable`、`http`、`json`、`foreign`、`disabled`。

## 3. 壳页 URL

```
http://127.0.0.1:<port>/quickdock?view=vscode&dock=<dockId>&mcp=1&workspace=<abs1>&workspace=<abs2>
```

| 参数 | 语义 | 坑 |
|---|---|---|
| `view` | **白名单**：`electron` / `vscode` / `browser`（`frame` 是 `electron` 的别名） | 未知值直接 **400** `unknown view; expected one of electron, vscode, browser`。所以今天**只能用 `vscode`** |
| `dock` | 每个窗口唯一的 dock id | 不要从路径派生 |
| `mcp` | **只要出现**就渲染插头按钮（`dockMcp = searchParams.has('mcp')`） | 初始状态是 `params.get('mcp') === '1'`；写成 `mcp=0` 仍会显示插头 |
| `workspace` | 可重复，第一个作为 `path`、全部作为 `paths` | 拼 URL 前先删掉已有的 `workspace`，再逐个 append |

> 为什么 `view=vscode` 而不是 `view=intellij`：这个参数同时决定**三件事**——
> ① chrome（去掉桌面窗口的 pin/collapse/close）；② 传输（走 Host 的 app-proxy，因为 IDE 内嵌页是第三方上下文，
> 带不了 DSH 的 `SameSite=Strict` cookie）；③ app 侧 `DOCK_HOST='vscode'`，`client.js` 用它决定
> "点文件要不要交给宿主"。改名要改三处，见 §8。

## 4. 三条 postMessage

壳页（QuickDock 的 `/quickdock` 页）与**宿主页**（你自己的 iframe 壳）之间：

### 4.1 子页 → 宿主：插头

```js
{ type: 'quickdock:mcp-toggle' }   // 无其它字段
```

触发条件：用户点了标题栏插头。宿主收到后启动/停止 MCP，然后**必须**回发 §4.3。

### 4.2 子页 → 宿主：打开文件

```js
{ type: 'quickdock:open-file', path: '<绝对路径>\u0000<source>' }
```

- `source` 是诊断信息，形如 `code.hljs`（标签名 + 第一个 class）；
- **按第一个 `\u0000` 切分**，只把前半段当路径（VS Code 版就是这么做的）；
- 会在 `mousedown` 和 `click` 各发一次（600ms 内同一路径去重由子页自己做）。

### 4.3 宿主 → 子页：工具状态

```js
{ type: 'quickdock:mcp-state', on: true, detail: '已连接 · 12 个工具' }
```

- **必须回发**：插头在收到这条之前一直带 `data-pending`（禁点、转圈）；这是最容易漏的一条；
- `detail` 只进 tooltip，可为空字符串；
- 子页还会顺手把 `data-pending` 清掉，所以"正在启动"也应该先回一条 `{on:false, detail:'starting…'}`。

### 4.4 子页自己会做的事（不用你管）

- 页面加载时 `POST /quickdock/api/dock-context`，body：
  `{ id, type: <view>, client: { name: <UA> }, workspace: { path, paths } }`；
- 会话切换时 `POST /quickdock/api/bind?dock=<id>` `{ sessionId, quiet: true }`；
- 若 `mcpBridge` 存在，插头旁边的列表按钮会去读 `/quickdock/api/mcp-list` 展示工具面板。

所以**你不需要写 `docks/<id>.json` 描述符**（VS Code 版也没写）；`type` 会是 `vscode`，
这是本期可接受的代价。要让它变成 `intellij`，见 §8。

## 5. 配对 API（都打在 QuickDock 的监听端口上）

```
POST /quickdock/api/mcp-register     { workspace, url, name, headers, version, tools, waitMs }
POST /quickdock/api/mcp-unregister   { workspace, name? }
GET  /quickdock/api/mcp-list?workspace=<绝对目录>
```

`mcp-register` 的 body：

```json
{
  "workspace": "C:\\work\\proj",
  "url": "http://127.0.0.1:47920/mcp",
  "name": "intellij",
  "headers": { "Authorization": "Bearer <32B base64url>" },
  "version": "0.1.0",
  "tools": ["intellij_read_file", "intellij_selection", "..."],
  "waitMs": 5000
}
```

- `version` / `tools` 只给 dock 的工具面板看，Host 转发给 bridge 前会剥掉；
- `waitMs`：第一次注册给 5000，心跳给 1000（别用默认 15000，心跳会堵）；
- `name` 决定 DSH 侧的工具命名空间：`mcp__<name>__<tool>`；
- bridge 侧规则：会话 cwd 在 `workspace` 之下（含相等）才挂载；最具体的目录优先；同名注册在同一目录会**替换**；
- 注册**在内存里**，DSH 重启即丢 → 靠心跳重建。

响应（`mcp-list`）：

```json
{
  "ok": true,
  "workspaces": [
    { "workspace": "C:\\work\\proj",
      "servers": [
        { "name": "intellij", "url": "http://127.0.0.1:47920/mcp", "transport": "streamable-http",
          "sessions": [ { "sessionId": "...", "state": "connected" } ] }
      ] }
  ]
}
```

`state` 取值：`connected` / `connecting` / `failed`（`failed` 带 `error`）。

错误处理：**404 = 这个 QuickDock 版本没有配对路由**，文案要指向"更新 dsh-quickdock 插件"，
不要赖端口（VS Code 版有专门的 `dockUpgradeHint`）。

## 6. 配对状态机（20 秒一拍，语义照抄）

```
每 20s：
  解析端口 → 探测（4s 缓存）
  folders = 当前项目内容根
  指纹 = SHA1(每个工具 name+description+schema)
  for folder in 已注册过、但这次不在 folders 里的： unregister(folder, name)
  若 folders 为空：记一条"没有打开的工作区，agent 挂不到工具"
  for folder in folders:
      listed = mcp-list(folder)
      若 listed 失败（QuickDock 没答）→ 记日志，等下一拍（不打扰用户）
      若 !toolsChanged && listed 里有同名同 url → 跳过
      register(folder, {..., waitMs: 首次 5000 / 心跳 1000})
```

要点：

1. **端口会漂移**（用户在 DSH 里改 QuickDock 端口）：每拍重新解析，别缓存端口；
2. **DSH 重启**：注册丢失 → `mcp-list` 为空 → 自动重注册，用户无感；
3. **工具集变化**（插件升级）：用指纹触发一次强制重注册，因为 DSH 会话记住的是连接时的工具列表；
4. **文件夹关闭**：必须反注册，否则 bridge 还会给那个目录挂一个已经不存在的 server；
5. **两个 IDE 窗口同一目录**：后注册的赢，直到另一个下一拍再抢回来——可接受，不要加锁。

## 7. MCP server 自身的表面

| 项 | 约定 |
|---|---|
| 地址 | `http://127.0.0.1:<port>/mcp`，`/health` 无鉴权地返回 `{ok, version, tools}` |
| 端口 | 首选 `47920`；`EADDRINUSE` 时绑 `0`（OS 选空闲），配对带真实 URL |
| 鉴权 | `Authorization: Bearer <token>`，不匹配一律 401；token 32 随机字节 base64url |
| 方法 | `initialize` / `ping` / `tools/list` / `tools/call` |
| 通知 | 无 `id` 的消息不回包（`notifications/initialized` 等） |
| 协议版本 | 接受 `2024-11-05` / `2025-03-26` / `2025-06-18`，默认最后 |
| `GET /mcp` | **405**（本 server 不主动推流），`DELETE /mcp` → 204 |
| 批量 | 支持 JSON 数组（批量请求，逐条应答） |
| 结果 | `content: [{type:'text', text}]`；工具内部抛错 → `isError: true` 且文本 `error: …` |
| 上限 | 请求体 ≤ 8MB；每个工具自己再限结果大小并带 `truncated` |

## 8. 如果坚持要 `view=intellij`

需要同时改 QuickDock 三处，否则**点文件不再在 IDE 里打开**（这个检查是静默失败的）：

1. `host/constants.js`：`VIEWS` 加 `intellij: { app: 'proxy' }`；
2. `client.js:1453`：`if (DOCK_HOST !== 'vscode' || …) return` → 放行 `intellij`；
3. `runtime/test/host.test.js`：补 `view=intellij` 的用例（现在断言未知 view 是 400）。

改完再回填本节和 `dsh-quickdock` 的三份契约文档。**M0–M4 阶段建议先不改。**

## 9. 源码指针（要核对时直接跳）

| 事实 | 位置 |
|---|---|
| view 白名单与 400 | `host/quickdock-host.js:1035-1042`、`host/views.js`、`runtime/test/host.test.js:55-80` |
| `dockMcp` 判定 | `host/quickdock-host.js:1070` |
| 插头按钮渲染条件 | `host/page.js:60-66`、`:270-277` |
| 三条消息的收发 | `host/page.js:376-400`、`:455-480`、`client.js:1470`、`client.js:1503` |
| dock-context 上报 | `host/page.js:200-220` |
| 配对路由与剥离 version/tools | `MCP-BRIDGE.md:72-94`、`host/api.js:79-92`、`host/dock-mcp.js:10-14` |
| 端口来源 | `host/constants.js`、`dsh-vsdock/src/quickdock.ts` |
| MCP 表面 | `dsh-vsdock/src/mcp.ts` |
