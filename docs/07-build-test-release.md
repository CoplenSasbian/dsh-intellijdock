# 07 · 构建、测试、发布

## 1. 工程骨架

```
dsh-intellijdock/
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradle.properties
├─ src/main/kotlin/com/deepseek/dsh/idea/…
├─ src/main/resources/
│   ├─ META-INF/plugin.xml
│   ├─ icons/dock.svg                 // 13×13
│   └─ messages/DshDockBundle.properties, DshDockBundle_zh_CN.properties
├─ src/test/kotlin/…
└─ docs/                              // 本目录
```

`settings.gradle.kts`：

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
rootProject.name = "dsh-intellijdock"
```

`gradle.properties`：

```properties
kotlin.stdlib.default.dependency=false
org.gradle.jvmargs=-Xmx2g
org.gradle.caching=true
# 版本号唯一来源
pluginVersion=0.1.0
```

## 2. `build.gradle.kts`

```kotlin
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dsh"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdea("2025.3")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin { jvmToolchain(21) }        // 必须 ≥ 21（平台自身跑 JBR 21）

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }     // 不设上界，避免每次大版本都要改
        }
        changeNotes = provider { file("CHANGELOG.md").readText() }
    }
    buildSearchableOptions = false
}
```

> `settings.gradle.kts` 的 `pluginManagement` 里额外加了
> `maven("https://cache-redirector.jetbrains.com/plugins.gradle.org/m2")`：JetBrains 的这个
> 镜像同时代理 Plugin Portal 与 intellij-repository，在网络受限的机器上是唯一能解析依赖的源。

常用任务：

| 任务 | 用途 |
|---|---|
| `./gradlew runIde` | 起一个带插件的沙箱 IDE（第一次会下载 IDE，约 1.5GB） |
| `./gradlew buildPlugin` | 产出 `build/distributions/dsh-ideadock-<ver>.zip` |
| `./gradlew test` | 单元测试 |
| `./gradlew verifyPlugin` | Plugin Verifier，检查跨版本兼容 |
| `./gradlew signPlugin` / `publishPlugin` | 签名 / 发布到 Marketplace |
| `./gradlew buildSearchableOptions` | 生成动作索引；名字含空格的 IDE 版本会**卡死**，CI 里可禁 |

## 3. 版本策略（这条最容易翻车）

```xml
<depends>com.intellij.modules.platform</depends>
<depends optional="true">com.intellij.modules.jcef</depends>
<idea-version since-build="253"/>
```

- `com.intellij.modules.jcef` 别名 **2025.3.1 起可用**；2026.2 起 JCEF 实现也搬进这个插件；
- 结果：**一个制品、`since-build=253`、`until-build` 不设**，同时覆盖 2025.3.1–2026.2+；
- 若产品上必须支持 2024.1：需要两套制品（老的那套不能带该 `<depends>`），或者放弃 2026.2+；
- 构建依赖同步：**不要**写 `bundledPlugin("com.intellij.modules.jcef")`，2025.3 的发行版里解析不到
  这个 id（见本节末尾「实测更正」）；
- **已知问题**：Gradle 配置里 `useInstaller = false` 的 IDE 分发不带 JCEF 原生组件——
  本地 `runIde` 若报 JCEF 不可用，先查这一项。

## 4. 本地开发循环

```bash
# 1. 起沙箱 IDE（第一次慢）
./gradlew runIde

# 2. 确认 QuickDock 在跑
curl http://127.0.0.1:47910/quickdock/api/meta

# 3. 沙箱里：Tools → DSH Dock；或 Ctrl+Alt+D
```

调试顺序（照 VS Code 版的日志思路）：

1. **IDE 自带日志**：Help → Show Log in Explorer（`idea.log`），项目日志前缀 `DSH Dock`；
2. **QuickDock 日志**：`%LOCALAPPDATA%\dsh-quickdock\host.log`、`dock.log`；
3. **JCEF 页内 console**：`CefDisplayHandler.onConsoleMessage` → `Logger`（排查"消息没到"的最快手段）；
4. **JCEF DevTools**：9222 端口，或 `browser.openDevtools()`。

## 5. 测试分层（照原项目 53 个纯 Node 测试的思路）

| 层 | 测什么 | 怎么测 |
|---|---|---|
| **纯逻辑**（主力） | 端口解析、meta 探测、URL 拼装、JSON-RPC、工具指纹、审阅决策、路径归一 | JUnit，`contract/` 包，**不需要 IDE 运行时** |
| **协议端到端** | `McpServer`：401 / 405 / initialize / tools/list / tools/call / 批量 / 8MB 限制 | 起一个 `HttpServer` 实例，用 `java.net.http.HttpClient` 打 |
| **配对逻辑** | "缺注册就注册、工具集变了重注册、文件夹关了反注册" | 起一个假 QuickDock（`HttpServer` 返回固定 JSON），断言调用序列 |
| **平台相关** | PSI 读文件、符号、诊断 | `BasePlatformTestCase`（`testFramework(TestFrameworkType.Platform)`），fixture 目录放样例工程 |
| **手工** | JCEF 渲染、点击文件、主题切换、DSH 断线重连 | 只能人测，写成 M1/M4 的验收清单 |

建议至少把"纯逻辑 + 协议端到端"做到和原项目同等覆盖——这两层不需要 IDE，跑得快。

## 6. 发布

### 6.1 检查清单

- [ ] `plugin.xml` 无 `<icon>` 根元素
- [ ] `since-build` / `until-build` 与依赖一致（§3）
- [ ] `<change-notes>` 最新版本条目在**最前面**（IDE 与 Marketplace 只显示第一条）
- [ ] 图标 13×13 SVG；描述里注明"需要 DSH + dsh-quickdock 插件"
- [ ] README 里写清隐私边界（loopback、token、工具权限）与"写工具默认关闭"
- [ ] `./gradlew verifyPlugin` 通过（JetBrains 要求对最新 IDE 兼容）
- [ ] 把它们打进 zip 的不是 fat jar：**不要重打包依赖库**（Verifier 会误报 unresolved class）

### 6.2 签名（非必需）

官方文档明确：作者不签名时，安装只弹一次警告；Marketplace 会用 JetBrains 的证书**二次签名**。
所以：

- 内部/自用/开源首发：可以**先不签名**，直接上传 zip；
- 想要"无警告"体验：`openssl genpkey` 生成 RSA 私钥 + `chain.crt`，
  配 `signPlugin { privateKey / certificateChain / password }`，密码走环境变量。
- 自建仓库（非 Marketplace）必须自己签名，否则 IDE 不信任。

### 6.3 发布流程

1. 版本号只改 `gradle.properties` 的 `pluginVersion`；
2. `CHANGELOG.md` 加条目（放最上面）；
3. `./gradlew buildPlugin verifyPlugin`；
4. **首次**上传必须在 Marketplace 网页手动做；之后可用 `publishPlugin`（`PUBLISH_TOKEN` 环境变量，
   token 走 `providers.environmentVariable`，**不要**写进仓库）；
5. Marketplace 审核通过后，IDE 内的插件更新才可见；测试通道用 `channels = listOf("beta")`。

## 7. 常见构建坑

| 现象 | 真因 / 修法 |
|---|---|
| `instrumentCode` 报 `…\Packages does not exist` | 用了普通 JDK 17 布局；必须用 **JBR**（带 `jmods`/`Packages`）做构建 JDK |
| `Failed to create Jar file … caches\jars-9\…` | 残留 Gradle daemon 锁缓存；`--stop` 后删该 hash 目录，或统一用 `--no-daemon` |
| `runIde` 起来后 JCEF 不可用 | 分发是 `useInstaller=false` 的（无原生组件），或 JBR 不带 JCEF |
| `verifyPlugin` 报 unresolved class | 依赖被重打包，或漏了 `bundledPlugin(...)` |
| 插件装上但"invalid plugin descriptor" | `<idea-plugin>` 下写了不认识的子元素（典型是 `<icon>`） |
| `buildSearchableOptions` 卡住 | 该版本 IDE 名含空格；CI 里 `-x buildSearchableOptions` |
| 中文 properties 乱码 | 老工具链按 ISO-8859-1 读；用 `\uXXXX` 或 IDE 的资源束编辑器保存 |

## 8. CI 建议

```yaml
# 关键点：IDE 下载量大（~1.5GB），必须缓存
- uses: gradle/actions/setup-gradle@v4
- run: ./gradlew verifyPlugin buildPlugin --no-daemon
  env:
    PUBLISH_TOKEN: ${{ secrets.PUBLISH_TOKEN }}
```

矩阵：`2025.3`（最低）+ 当前最新（`verifyPlugin` 目标）。JCEF 相关的行为差异**不要**放 CI 断言，
那些只能在真机上手测。

---

## 9. 实测更正（2025.3 / build 253.28294.334 / Gradle 9.2 / 插件 2.20.1）

本页正文写于动手之前，实际构建时踩到这些：

| 现象 | 真因 | 修法 |
|---|---|---|
| 插件应用失败：`requires Gradle Gradle 9.0.0 and higher` | IntelliJ Platform Gradle Plugin 2.20.1 提高了最低 Gradle | wrapper 用 **Gradle 9.2**，Kotlin 用 **2.2.21**（2.1.0 不认 Gradle 9） |
| `intellijIdeaCommunity("2025.3")` 解析失败 | 2025.3 起只有统一发行版，IC 不再单独发布 | 依赖写 `intellijIdea("2025.3")`（插件会打印这条提示） |
| `bundledPlugin("com.intellij.modules.jcef")` 解析失败 | 2025.3 的发行版里没有这个模块 id | 构建里删掉；`plugin.xml` 改成 `<depends optional="true">`，见 §3 |
| 装到 IDE 里 `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics` | 平台**不**把 kotlin-stdlib 放在核心 classpath（只在捆绑 Kotlin 插件里），而 IntelliJ Platform Gradle Plugin 又会把它从 `lib/` 里剔掉 | 显式 `bundledKotlinStdlib` 配置 + `jar { from(zipTree(...)) }`，把 stdlib 并进插件 jar（约 +1.7MB） |
| `kotlin.stdlib.default.dependency=false` 是否还需要 | 不需要；上面这条是更深的问题 | 该行已删 |
| `instrumentCode` 报 `Packages does not exist` | 构建 JDK 用了普通 JDK | 用 JBR/带 jmods 的 JDK 21；本机用 Temurin 21 正常 |
| 沙箱里 Gradle 起不来（`CreatePipe error=5`） | DSH 沙箱禁止 Java 进程建管道 | 批准后以更高权限跑一次，或复用已有 daemon（`kotlin.compiler.execution.strategy=in-process`） |

### 测试现状

`./gradlew test` 是 **96 个用例全绿**，全部不需要 IDE 运行时：
JSON codec、端口解析/优先级、URL 与百分号编码、glob、JSON-RPC、HTTP 端点（真起 loopback）、
配对报文与"要不要重注册"、审阅规则、DSH 命令行、生成的 HTML。

`./gradlew buildPlugin` 产出 `build/distributions/dsh-intellijdock-0.1.0.zip`（约 1.9MB，
含 Kotlin 运行时）。`verifyPlugin` 尚未在本机跑过（需要额外下载目标 IDE），列进 M6。
