# Kotlin Toolchain 与版本决策

## 2026-10-24：CI JDK 下载兼容

`40054692318` 四端均在 Toolchain 自动下载 JDK 阶段遇到 `TooLongLineException: Line exceeds limit of 8192 characters`（HTTP 响应头解析），尚未执行应用测试。`e312999` 按[官方 JDK provisioning 文档](https://github.com/JetBrains/kotlin-toolchain/blob/v0.13.0/docs/src/user-guide/advanced/jdk-provisioning.md)的 `auto` 规则，先通过 [actions/setup-java v6.1.0](https://github.com/actions/setup-java/releases/tag/v6.1.0)（固定提交 `b843ebf9b913146b38da2eaa7aeddf76074ae1ad`）设置符合要求的 `JAVA_HOME`；Kotlin CLI/YAML 仍执行全部构建与测试。

CI 固定 Eclipse Temurin：Linux x64/macOS Arm64 为 `25.0.5+8`，Windows x64 为 `25.0.4.1+1`。2026-10-24 已核对 [25.0.5+8 官方资产](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.5%2B8)仅覆盖前两类所需包，Windows 使用[该平台已发布版本](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.4.1%2B1)。模块要求仍为 JDK 25，开发者保留默认自动获取能力；没有修改官方 wrapper、关闭 TLS/校验或加入第二套构建。`40055046151` 的前三端成功，但 iOS 在下载内置 `xcbeautify 3.2.1` 时再遇同一限制。已核对 Toolchain 所用 Ktor 3.4.1 的 [HTTP_LINE_LIMIT 固定为 8192](https://github.com/ktorio/ktor/blob/3.4.1/ktor-http/ktor-http-cio/common/src/io/ktor/http/cio/HttpParser.kt)，没有通过虚构 JVM 配置修改它。

`287e212` 仅为 iOS 用 curl 预取 Toolchain [IosBuildTask 指定的同版工具](https://github.com/JetBrains/kotlin-toolchain/blob/v0.13.0/sources/amper-cli/src/org/jetbrains/amper/tasks/ios/IosBuildTask.kt)，校验[官方 release 资产](https://github.com/cpisciotta/xcbeautify/releases/tag/3.2.1)的固定 SHA-256 `ed32972e41bc3aa19588f8b0f4a0209ef70c663ad60bbf4ad84a2de73355d637`，再原子放入 runner 临时 `KOTLIN_SHARED_CACHE_DIR/download.cache`。缓存名按 [0.13 官方 Downloader](https://github.com/JetBrains/kotlin-toolchain/blob/v0.13.0/sources/core/src/org/jetbrains/amper/core/downloader/downloader.kt) 的 `sha256(URL + "V1")[:10]` 计算；该内部联系随 Toolchain 升级必须复核。未补丁 HTTP 客户端或更改压缩包，下载与摘要已本地验证；远端结果见 [CI.md](CI.md)。

iOS UI 冒烟另用已安装 Xcode SDK 编译一个仅供 CI 的 Swift 测试素材应用。当前测试创建方式用 Data.write(.withoutOverwriting) 初次写入最终文件名，保持 inode 不因原子替换而变化；写完三个小素材并校验后报告就绪，供被测应用从真实系统选择器导入；不打包进产品，也不替代 Kotlin CLI/YAML 的应用构建。这样避免宿主直接写入模拟器文件提供器目录，源文件仍逐字节校验并保留取消后同进程重开与最终清理守卫。

iOS UI 运行时另有明确的兼容性选择：默认使用最新已安装运行时，手动输入 ios_ui_runtime=26.4 可做兼容性对照。26.5 与 26.4.1 在 UIDocument 源素材下均出现 FileProvider FP -1005/nil URL，不能仅归因于 26.5；失败证据保留，未视为通过。该对照不改变 Xcode、编译 SDK、Kotlin 或依赖版本；具体执行结果与覆盖范围见 CI.md。

## 2026-10-16：桌面媒体预览

最终实现采用 JavaCV/JavaCPP **1.5.14** 与 FFmpeg **8.1.2-1.5.14**，由各 [Maven Central 发布元数据](https://repo.maven.apache.org/maven2/org/bytedeco/ffmpeg/maven-metadata.xml)核实并固定。仅引入 FFmpeg/JavaCPP 的 Windows x64、macOS Arm64/x64、Linux x64 默认 native classifier，未引入 `-gpl` 变体或全预设 platform 包。JavaCV 会传递引入其他预设的 Java API JAR，发行时仍须裁剪未用依赖并生成完整清单。Intel Mac 原生资源已列入，尚未运行该架构验证；Windows Arm64 未配置。

先前的 JavaFX 27 验证在 macOS/Linux 通过，但 Windows Server 2025 即使启用 Media Foundation 仍缺少其依赖的旧 AAC DirectShow 组件，因此改为内置解码，而不将旧系统 codec 作为客户端前置条件。CPU 预览限制为最大 640×360、15 fps，视频原始像素数限制为 3840×2160；单个解码工作线程串行拥有播放器，视频/音频解码线程数分别限制为 2/1。音频以 PCM 输出到本地设备，硬件输出不可用时明确提示并继续视频预览。未修改素材，未添加应用后端或 Gradle 构建。

Android/iOS 使用系统媒体 API，无新增媒体 Maven 依赖。数据读取、平台视图重建、释放和中断均纳入验证；真实硬件听音与性能不是 UI 冒烟结论。

核实日期：2026-10-13。用户明确要求 JetBrains **Kotlin Toolchain CLI**，不是 Kotlin Gradle 插件的 JVM toolchain DSL。工程使用 `project.yaml` / `module.yaml` / module templates；不包含 Gradle wrapper、settings.gradle 或 build.gradle。

## 官方来源

- 用户指定的官方入门：[Kotlin Toolchain](https://kotlinlang.org/docs/multiplatform/kotlin-toolchain.html#toolchain-script-install)。页面标明 Alpha，2026-10-02 更新。
- [官方仓库](https://github.com/JetBrains/kotlin-toolchain)，[当前发行版 v0.13.0](https://github.com/JetBrains/kotlin-toolchain/releases/tag/v0.13.0)。以 releases/latest 重定向和官方 installer 验证发行版本。
- [CLI provisioning](https://github.com/JetBrains/kotlin-toolchain/blob/v0.13.0/docs/src/cli/provisioning.md)、[CMP 支持](https://github.com/JetBrains/kotlin-toolchain/blob/v0.13.0/docs/src/user-guide/builtin-tech/compose-multiplatform.md)、[模块配置](https://github.com/JetBrains/kotlin-toolchain/blob/v0.13.0/docs/src/reference/module.md)、[iOS host](https://github.com/JetBrains/kotlin-toolchain/blob/v0.13.0/docs/src/user-guide/product-types/ios-app.md)。
- [KMP 官网](https://kotlinlang.org/multiplatform/)、[Android KMP 指南](https://developer.android.google.cn/kotlin/multiplatform?hl=zh-cn)。掘金链接最初受限，已按用户澄清以官方 Toolchain 文档为准。

## 锁定版本与“最新”的定义

选最新正式发行的 Kotlin/CMP/直接库，不选 Beta/RC/alpha 依赖；Toolchain 本身处于 Alpha 产品阶段，采用其最新非 dev 发行版本。不能把 Toolchain 0.x 误称为生产稳定 GA。

| 组件 | 选择 | 官方证据/说明 |
| --- | --- | --- |
| Kotlin Toolchain CLI | 0.13.0 | 官方 releases/latest；wrapper 固定版本及 distribution SHA-256 |
| Kotlin 编译器/stdlib | 2.4.21 | Maven Central `org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml` 的最后正式版本；这里只读取发布元数据，不使用插件构建 |
| Compose Multiplatform | 1.12.1 | Maven Central `org/jetbrains/compose/compose-gradle-plugin/maven-metadata.xml`；实际编译由 Toolchain 启用 Compose |
| Compose Material3 | 1.9.0 | Maven Central `org/jetbrains/compose/material3/material3/maven-metadata.xml`；独立发布节奏，不能套用 CMP 版本号 |
| kotlinx.serialization | 1.11.0 | Maven Central `org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml` |
| kotlinx.coroutines | 1.11.0 | 2026-10-15 新增显式依赖；Maven Central `org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml` 的最新正式版；用于在后台 dispatcher 执行本地存储 |
| AndroidX activity-compose | 1.13.0 | Google Maven `androidx/activity/activity-compose/maven-metadata.xml` |
| Android compile/target SDK | 37 | Toolchain 0.13 官方默认与 Google SDK repository metadata；minSdk 24 是兼容下限 |
| JDK | Toolchain 管理 JDK 25 | 官方工具链使用的 LTS 运行环境；桌面宿主 bytecode 21、共享模块/Android bytecode 17 是兼容目标，不是依赖版本 |

显式依赖版本在 module templates/YAML 中集中到最小范围。CMP 自动引入的 runtime/resources 与平台变体由 Toolchain 解析；Material3 显式固定最新正式版。没有为了骨架提前引入数据库、DI、导航、网络 engine 或媒体库；以后引入时再核实当时官方版本与支持矩阵。

## Android 的工具链限制（不能隐瞒）

官方 Toolchain 0.13 的 Android 后端内部使用 AGP/Gradle。其源码 `libs.versions.toml` 固定 AGP **9.3.1**，内部 Gradle **9.5.0**；并非独立 AGP 最新 **9.4.1**；项目 YAML 没有公开的任意 AGP 版本覆盖入口。此处采用用户指定的官方 Toolchain 发行物，不修改其内部构建或另设 Gradle 工程。

因此“完全不在任何层使用 Gradle”与“使用当前 Kotlin Toolchain 构建 Android”无法同时满足。本项目将“不用 Gradle”落实为不直接配置/调用 Gradle、所有开发命令使用 Kotlin CLI；内部 Android 实现及受其管理的工具版本如实列出。若要求连内部都禁止，则 Android 只能等待 Toolchain 官方更换后端，不能宣称已满足。

## 安装与环境

官方全局安装入口是 `curl -fsSL https://kotl.in/install.sh | sh`；本仓库使用项目本地官方 wrapper，避免每个开发者必须全局安装。wrapper 自带固定 distribution SHA-256 校验，保留其完整实现及 JetBrains 版权头。不得禁用 TLS、替换校验值或使用未校验镜像。

云环境缓存放 `/workspace/.tooling/kotlin-cli` 与 `/workspace/.tooling/kotlin-cache`，由 `KOTLIN_CLI_BOOTSTRAP_CACHE_DIR` 和 `KOTLIN_SHARED_CACHE_DIR` 指定。不要修改 HOME 或把机器绝对路径写入项目 YAML。

网络需求：官方安装/文档 `kotl.in`、`kotlinlang.org`、`kotlin-toolchain.org`；分发 `packages.jetbrains.team` 及其实际重定向 CDN `db2uklzeiyqq6.cloudfront.net`；JRE/JDK `cdn.azul.com`、JetBrains download/CDN 与 JDK metadata；依赖 Maven Central、Google Maven 和 Android SDK。新增下载跳转应根据真实失败追加，保持 TLS/校验。原版 Google 服务访问域名、授权和配额属于后续集成范围。

详细执行结果见 [VALIDATION.md](VALIDATION.md)。未在对应宿主运行的构建只能标为未验证。

补充：最新版本约束针对项目显式选择的编译器、UI 和直接库；传递依赖遵循这些官方发行物的依赖图，没有把整个依赖图强制提升到互不兼容的任意最新版本。早期执行记录的 JDK provision 为 Temurin 25.0.4.1+1；当前 CI 的 Windows/Linux/macOS 固定版本见首节。JUnit 平台实际为 6.1.3。
