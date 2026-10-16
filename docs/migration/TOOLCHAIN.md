# Kotlin Toolchain 与版本决策

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

补充：最新版本约束针对项目显式选择的编译器、UI 和直接库；传递依赖遵循这些官方发行物的依赖图，没有把整个依赖图强制提升到互不兼容的任意最新版本。JDK 实际 provision 为 Temurin 25.0.4.1+1，JUnit 平台实际为 6.1.3。
