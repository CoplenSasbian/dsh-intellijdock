# 08 · 风险、里程碑、工作量

## 1. 风险清单（按"会不会让项目失败"排序）

| # | 风险 | 触发条件 | 怎么早发现 | 缓解 |
|---|---|---|---|---|
| R1 | **JCEF 不可用** | 自定义 JBR / 2026.2 未启用 "Web Browser (JCEF)" / 远程开发·Gateway | M0 第一行代码就检测 `JBCefApp.isSupported()`；在三套环境各跑一次 | 降级卡片 + 「在浏览器打开」；文档写清前置条件；不要承诺远程开发可用 |
| R2 | **内部 API 漂移**（诊断 / 意图动作 / 终端 / 调试） | 平台大版本升级 | `verifyPlugin` + 最低/最新两版编译 | 全部收敛进 `IdeAdapter`，坏也只坏一处；每个工具有降级结果 |
| R3 | **diff 抢焦点**导致用户反感 | M4 一上线就出现 | 自己用一天 | 改通知式审阅（默认不弹 diff），只在显式调用时开 diff |
| R4 | **2026.2 JCEF 依赖拆分** | 声明依赖后老版本装不上 | 装到 2025.1 上试 | `since-build=253`；必要时分两套制品（见 07 §3） |
| R5 | **命名空间冲突**（和 VS Code 扩展 / `deepseek-harness-idea` 同目录） | 同目录双客户端 | 两个客户端都开，看 `mcp__*` | `name=intellij`；工具名 `intellij_*`；README 说明 |
| R6 | **配对被模态/线程池拖死** | 设置页开着时点插头 | 设置对话框开着操作一遍 | `ModalityState.any()` + 自建 worker（见 06 §2） |
| R7 | **QuickDock 版本不匹配** | 用户用的是老插件 | `meta.capabilities` 缺 `app-proxy`；配对路由 404 | 显式卡片"更新 dsh-quickdock"，不要怪端口 |
| R8 | **端口/防火墙**（macOS/Linux 首次绑定 loopback 也可能弹窗） | 跨平台首发 | 三平台各跑一次 | 只绑 `127.0.0.1`；失败文案带端口与排查步骤 |
| R9 | **大仓库性能**（search / list_files 遍历） | 索引 10w+ 文件 | 在真实大仓库试 | 走索引不走磁盘；上限 + 超时；`truncated` |
| R10 | **跨 IDE 兼容**（PyCharm/WebStorm 没有 Java 插件） | 用了 `com.intellij.modules.java` 的 API | 在 PyCharm 里装一次 | 只依赖 platform；语言能力用 `<depends optional="true">` |

## 2. 里程碑

> 每个里程碑都以"能演示 + 能量"为完成标准，不是"代码写完了"。
> 状态截至 2026-11-17：M0–M4 与 M5 的代码部分已落地（编译通过、96 个测试全绿），
> 带 ⏳ 的是只剩真机手测的部分。

### M0 · 骨架与探测（3–5 天）

产出：Gradle 工程可 `runIde`；工具窗口出现；`/quickdock/api/meta` 探测正确；三种失败卡片。

验收：

- [x] `compileKotlin` / `buildPlugin` 通过（沙箱里 `runIde` 需手动开图形 IDE）
- [ ] DSH 在跑 → 卡片显示"已连接 0.6.0-beta.2 / app-proxy 可用" ⏳ 真机
- [x] DSH 没跑 → `connection refused` 卡片，含端口、重试、启动 DSH
- [x] 端口配错 → 卡片给出 QuickDock 配置里的真实端口 + 一键采用
- [x] `JBCefApp.isSupported()==false` → 专门卡片（Swing 降级，不是白屏）
- [x] 纯逻辑单测：端口优先级、meta 解析、URL 拼装（远超 15 个用例）

### M1 · 停靠窗（3–5 天）

产出：iframe 壳 + 三条消息；能看到 DSH 会话；点文件在编辑器打开；插头可翻。

验收：见 [03-jcef-dock.md](03-jcef-dock.md) §9。代码与页面生成均有测试，渲染效果 ⏳ 真机。

### M2 · MCP 打通（1–1.5 周）

产出：MCP server + 3 个只读工具 + 20s 配对心跳。

验收：见 [04-mcp-host.md](04-mcp-host.md) §10。HTTP 端点与配对状态机有端到端测试；
"DSH 侧真的挂上 `mcp__intellij__*`" ⏳ 真机。

### M3 · 其余只读工具（1.5–2 周）

产出：13 个只读工具 + `IdeAdapter` + dumb mode/超时/截断。

验收：见 [05-tools.md](05-tools.md) §7。API 已按 2025.3 实际签名收敛，见该页 §8。

### M4 · 审阅（3–5 天）

产出：VFS 监听、基线缓存、通知式审阅、显式 `open_diff`。

验收：

- [x] agent 改一个文件 → 发通知（**不**抢焦点），点开是 diff，左岸是改前（逻辑与通知已实现）
- [x] 用户自己 Ctrl+S 不触发通知（`beforeDocumentSaving` 记录 + 2s 宽限，有测试）
- [x] 跳过 `node_modules`/`out`/锁文件/二进制/大于 512KB（有测试）
- [x] 未配对的目录里不弹任何东西（有测试）
- [x] 关掉设置项后完全安静

### M5 · 写入与外壳（1 周）

产出：5 个写工具（默认关）、设置页、状态栏、11 个动作、i18n。

验收：见 [06-platform-rules.md](06-platform-rules.md) §8。文案 130 键中英同步，
`node scripts/check-strings.mjs` 可自检；动作与界面行为 ⏳ 真机。

### M6 · 打包与发布（3–5 天）

产出：`buildPlugin` 产物、Verifier 通过、README/CHANGELOG、GitHub Release。

验收：全新 IDE 装 zip → 按 README 五步能跑通；Verifier 无 incompatible 报告。

## 3. 工作量

| 里程碑 | 估时（单人，含自测） |
|---|---|
| M0 | 3–5 d |
| M1 | 3–5 d |
| M2 | 1–1.5 w |
| M3 | 1.5–2 w |
| M4 | 3–5 d |
| M5 | 1 w |
| M6 | 3–5 d |
| **合计** | **6–8 周** |

缓冲建议 +30%：JCEF 与平台 API 的"第一次踩"通常比预期慢。如果选决策 B（自带运行时），
直接按 3–5 个月估。

## 4. 非目标（明确写下来，避免范围蔓延）

- ❌ 远程开发 / Gateway 的完整支持（只做"检测 + 浏览器降级"）
- ❌ 自带 DSH 运行时（决策 A/C 之外）
- ❌ 复刻 VS Code 的"不抢焦点预览 diff"（平台语义不同，改通知式）
- ❌ 多语言客户端各自的 UI 定制（一个壳，一套配色）
- ❌ 自己实现 MCP 传输的 SSE（`GET /mcp` 回 405 就是正确行为）
- ❌ 修改 DSH 的 profile / 注入自家 tool（工具只走 MCP bridge）

## 5. 与已有实现的关系

| 项目 | 关系 | 可借用 |
|---|---|---|
| `deepseek-harness-idea`（GitHub `tieJiangW/deepseek-harness-idea`） | **不同架构**（自带运行时、直接嵌 DSH Web UI） | JCEF 时序、`JBCefJSQuery` 用法、2026.2 拆分、JBR 21 构建、`<icon>` 坑、`ModalityState` 坑、模态后台更新 |
| `jetbrains-cc-gui`（GitHub `zhukunpenglinyutong/jetbrains-cc-gui`） | 同为"工具窗口嵌 JCEF + JS 桥" | 工具窗口生命周期、handler 注册、字体/请求拦截 |
| `dsh-quickdock` | **契约真源** | 三份 md + `host/*.js`；要改 `view` 就在这里改 |
| `dsh-vsdock` | 行为基准 | HTML、工具语义、配对状态机、审阅规则 |

## 6. 下一个会话的第一件事

1. 读 [../README.md](../README.md) 的"三个决策"，**先问用户**（薄/启动 DSH；最低版本；命名）；
2. `git init`，按 [07-build-test-release.md](07-build-test-release.md) §1–2 建工程；
3. 先写 `contract/QuickDockPorts.kt` + `QuickDockProbe.kt` 的单测（不需要 IDE，10 分钟就能绿），
   再写 `DockToolWindowFactory`——**先有可测的契约层，再动 UI**，这是原项目最值得复制的习惯；
4. M0 的卡片文案直接从 `dsh-vsdock/src/html.ts` 的 `renderOffline` 翻译，不要自己发明措辞。
