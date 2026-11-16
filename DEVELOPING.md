# Developing DSH Dock

## Toolchain

| 需要 | 版本 | 备注 |
|---|---|---|
| JDK | 21 | 编译与运行目标都是 21（平台自身跑 JBR 21） |
| Gradle | 9.0+ | IntelliJ Platform Gradle Plugin 2.20.1 要求 ≥ 9.0 |
| Kotlin | 2.2.21 | 由 Gradle 插件解析 |
| IDE 依赖 | IntelliJ IDEA `2025.3` | 2025.3 起只有统一发行版，`intellijIdeaCommunity` 不再解析 |

```bash
./gradlew compileKotlin      # 只编译
./gradlew test               # 单测（不含 IDE 运行时，见下）
./gradlew buildPlugin        # 产物：build/distributions/dsh-ideadock-<ver>.zip
./gradlew runIde             # 沙箱 IDE
./gradlew verifyPlugin       # 跨版本兼容检查
```

## 测试分层

`src/test/kotlin` 里除了 `DockPageTest`（纯字符串）之外，全部只依赖 JDK：

| 测试 | 覆盖 |
|---|---|
| `JsonTest` | 自研 JSON codec（转义、数字、嵌套、坏输入） |
| `ContractTest` | 端口解析/优先级、URL 拼装与百分号编码、glob、路径归一 |
| `McpServerTest` | 真 HTTP：401 / 405+Allow / 204 / 404 / 批量 / 通知 202 / 工具抛错 → `isError` |
| `PairingTest` | 假 QuickDock：register/list/unregister 的报文与"要不要重注册"的判定 |
| `ReviewDecisionTest` | 审阅规则（IDE 自己保存、脏缓冲区、未配对目录、构建目录、缺基线） |
| `DshLauncherTest` | 命令行拼装与引号、profile 名校验、profile 列表 |
| `DockPageTest` | 生成的 HTML：iframe URL、三条消息的桥接、按钮回调、转义 |

`./gradlew test` 会起真实 loopback server，不打开 IDE，跑起来是秒级。

## 本机（Windows）环境上的三个坑

这台机器的条件比较特殊，记录一次省下一次排查：

1. **C 盘经常只剩几 GB。** Gradle 缓存与 JDK 都不要放工作区：
   ```bash
   export GRADLE_USER_HOME=D:/dsh-ideadock-build/gradle-home
   export JAVA_HOME=D:/dsh-ideadock-build/jdk21/jdk-21.0.13+9
   ```
   `D:/dsh-ideadock-build/gradle-home/gradle.properties` 里放代理与
   `org.gradle.java.installations.paths`（见下）。工作区的 `.gitignore` 已忽略
   `.gradle-home/`、`.tooling/`，但放在 D 盘更省事。

2. **HTTPS 只能走 `127.0.0.1:7890` 代理，而且 curl 的 schannel 会握手失败**
   （`SEC_E_NO_CREDENTIALS`）。用 Node 的 `fetch` 测网络、用 Gradle 的 `systemProp.*`
   走代理；`curl` 的失败不能当作"没网"。

   ```properties
   systemProp.http.proxyHost=127.0.0.1
   systemProp.http.proxyPort=7890
   systemProp.https.proxyHost=127.0.0.1
   systemProp.https.proxyPort=7890
   systemProp.http.nonProxyHosts=localhost|127.0.0.1|[::1]
   ```

3. **DSH 沙箱禁止 Java 进程创建管道**（`CreatePipe error=5`），所以 Gradle 不能自己
   fork 构建 JVM。两条出路：在批准后以更高权限运行一次 `gradle`，或者直接复用已有
   daemon。`gradle.properties` 里已把 Kotlin 编译设为 `in-process`，避免再 fork 一个
   Kotlin daemon。

## 版本策略

- `pluginVersion` 只写在 `gradle.properties`；
- `since-build=253`，`until-build` 不设；
- `com.intellij.modules.jcef` 在 `plugin.xml` 里是 **optional**（2025.3 的发行版里没有
  这个模块 id，硬依赖会让插件在 2025.3 上装不上；2026.2+ 有该模块时依赖生效、类加载才
  连得上）。详见 `docs/07-build-test-release.md` 的「实测更正」。

## 目录

```
src/main/kotlin/cc/futurvo/ideadock/
├── contract/   纯逻辑：端口、探测、URL、JSON、JSON-RPC、HTTP server、配对
├── dock/       JCEF 宿主、iframe 壳页、主题、工具窗口
├── editor/     18 个工具、内部 API 收敛点、diff、VFS 审阅
├── lifecycle/  per-project 服务（探测/配对/启动 DSH）
└── ui/         设置页、状态栏、动作、i18n
src/main/resources/
├── META-INF/plugin.xml
├── icons/dock.svg
└── messages/DshDockBundle*.properties
```
