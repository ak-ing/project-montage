# Project Montage KMP skeleton

本目录是 Kotlin Toolchain / Compose Multiplatform 迁移骨架。原版服务仍在仓库根的 `mcp_client/`、`mcp_montage/`、`sign_server/`；完整范围与边界见 [迁移计划](../docs/migration/PLAN.md)，验证状态见 [验证记录](../docs/migration/VALIDATION.md)。

## 结构

- `core`：共享草稿、版本化项目文档、任务检查点与中断恢复、存储接口及单元测试。
- `shared-ui`：所有端共享的 CMP 本地草稿界面。
- `desktop-app`：Windows/macOS JVM 宿主（Linux 也可用于编译验证）。
- `android-app`：Android Activity 宿主。
- `ios-app`：SwiftUI 宿主和 ComposeUIViewController，面向真机 Arm64 与 Apple Silicon 模拟器。

界面支持编辑标题、追加分镜、重置样例、**Save draft** 保存和 **Reload saved** 重载。编辑后必须点击保存，等待 **No unsaved changes** 再关闭；未保存编辑可通过 **Discard edits & reload** 丢弃。读取损坏/未知版本文档时禁用编辑并保留原文件，可修复文件后重试读取；保存失败时保留界面编辑以供重试。

**Import video** 打开系统文件选择器并复制一个视频到应用自己的 `media/` 目录。**Videos** 显示文件名、大小与可用状态；取消不改动项目，复制失败可重试，缺失的副本可移除后重新导入。导入和移除后也需 **Save draft**；移除后成功保存，或明确丢弃编辑并重载时，才清理不再引用的应用内副本，不删除原素材。进程在保存前退出所留下的副本，会在下一次成功保存或明确重载时清理。当前仅导入文件并检查存在/大小，尚未分析编码、时长或播放兼容性。

目标是设备端编排并直接调用外部模型 API，本地完成媒体处理与导出，无需部署自建后端。当前实现单个本地工作区、视频素材导入与任务检查点的存储基础；恢复时将 Running 标记为 Interrupted，保留提供商 operation ID，不自动运行任务。模型 API、任务执行器、凭据接入、播放、拼接和导出均尚未实现。

项目文档为 UTF-8 `workspace.json`（schemaVersion 2），保存草稿、检查点和素材元数据，不存媒体字节或凭据；schema 1 自动原子迁移，未来版本或损坏文档保留并拒绝覆盖。Windows 存于 `%LOCALAPPDATA%/ProjectMontage`，macOS 存于 `~/Library/Application Support/ProjectMontage`，Linux 存于 `$XDG_DATA_HOME/project-montage`（默认 `~/.local/share/project-montage`）；桌面测试可用 JVM 属性 `montage.dataDir` 指定独立目录。Android 使用应用内部 files 目录的 AtomicFile，iOS 使用沙盒 Application Support/ProjectMontage 的原子文件写入；iOS Documents 另开放给系统“文件”App，可放入待导入的视频，工作区和已管理副本仍保留在 Application Support。桌面在同目录写临时文件、同步并原子替换；文件系统不支持原子替换时报告保存失败。当前不支持多个应用实例同时编辑同一文档。

## 构建与运行

在本目录执行。官方 wrapper 自动安装指定 Toolchain、运行时和所需工具，首次需要网络；无需全局 Gradle/Kotlin 安装。Windows 将 `./kotlin` 换成 `.\kotlin.bat`。

```sh
./kotlin --version
./kotlin show modules
./kotlin build -m desktop-app
./kotlin test -m core --platform jvm
./kotlin run -m desktop-app --no-compose-hot-reload
```

桌面运行需要图形会话；Windows/macOS 应在对应系统验证。此阶段未配置 MSI/DMG 签名与安装包。

```sh
./kotlin build -m android-app
./kotlin run -m android-app
```

Android 工具由 Toolchain 自动提供，首次需按工具提示接受 Android SDK 许可。运行需要模拟器或真机。Toolchain 官方内部 Android 后端使用 AGP/Gradle，但本工程仅使用 Kotlin CLI 和 YAML。

在 macOS 上安装并完成 Xcode 首次启动：

```sh
./kotlin build -m ios-app --platform iosSimulatorArm64
./kotlin run -m ios-app --platform iosSimulatorArm64
./kotlin test -m core --platform iosSimulatorArm64
```

`ios-app/module.xcodeproj` 已从成功的 CI 产物取回并审查后纳入版本管理。bundle ID 为 `dev.montage.app`，最低 iOS 15.0；保留官方 Toolchain 管理的 Kotlin 集成阶段。`MontageUITests` 与共享 scheme 用于模拟器 UI 冒烟；Swift 桥接使用官方 `KotlinModules` framework。真机 Team、签名和商店发行仍需另行配置与验证。

四端 UI 冒烟命令、截图和报告路径见 [CI.md](../docs/migration/CI.md)。

## 云环境

在 `/workspace/project-montage-private/kmp` 执行 ` . ./scripts/cloud-env.sh` 后再执行上述命令。脚本配置可写缓存/SDK 路径，并让 Java HTTP 客户端使用环境代理与系统 CA。使用现有 checkout，不创建额外 worktree。

骨架无需数据库、Google API key 或 GCP 服务。这里的云环境仅用于开发/构建，不是产品运行后端。不要为了本地预览启动付费生成流水线。后续模型 API 直连使用提供商支持的原生认证或用户自行配置的凭据，并通过平台安全存储保管，不在应用中内置共享密钥或服务账号。

版本来源、Alpha 状态与 Android 内部工具版本限制见 [TOOLCHAIN.md](../docs/migration/TOOLCHAIN.md)。

四平台 GitHub Actions 作业、测试数量检查及首次远端运行限制见 [CI.md](../docs/migration/CI.md)。

云端图形冒烟可用 `DISPLAY=:99 ./scripts/run-desktop-cloud.sh`（先启动可用的 Xorg dummy display），具体经过与限制见验证记录。
