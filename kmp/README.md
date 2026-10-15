# Project Montage KMP skeleton

本目录是 Kotlin Toolchain / Compose Multiplatform 迁移骨架。原版服务仍在仓库根的 `mcp_client/`、`mcp_montage/`、`sign_server/`；完整范围与边界见 [迁移计划](../docs/migration/PLAN.md)，验证状态见 [验证记录](../docs/migration/VALIDATION.md)。

## 结构

- `core`：共享草稿模型、时长计算、平台/会话接口及单元测试。
- `shared-ui`：所有端共享的 CMP 本地草稿界面。
- `desktop-app`：Windows/macOS JVM 宿主（Linux 也可用于编译验证）。
- `android-app`：Android Activity 宿主。
- `ios-app`：SwiftUI 宿主和 ComposeUIViewController，面向真机 Arm64 与 Apple Silicon 模拟器。

界面支持编辑标题、追加分镜、重置样例。数据仅在内存中，云生成、登录、文件选择、播放、保存和后端连接都还未实现；这些仍在最终迁移范围内。

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

Toolchain 可在首次 iOS 构建时生成 `ios-app/module.xcodeproj`（官方支持行为）；生成后检查 bundle ID 为自己的标识并将项目纳入版本管理。真机签名、Team、最低部署版本与商店发布需在 Xcode 设置，本次 Linux 环境不做已验证声明。Swift 桥接依照官方 `KotlinModules` framework 格式。

## 云环境

在 `/workspace/project-montage-private/kmp` 执行 ` . ./scripts/cloud-env.sh` 后再执行上述命令。脚本配置可写缓存/SDK 路径，并让 Java HTTP 客户端使用环境代理与系统 CA。使用现有 checkout，不创建额外 worktree。

骨架无需数据库、Google API key 或 GCP 服务。不要为了本地预览启动付费生成流水线。后端集成另按原版环境文档配置，凭据仅通过安全环境设置提供。

版本来源、Alpha 状态与 Android 内部工具版本限制见 [TOOLCHAIN.md](../docs/migration/TOOLCHAIN.md)。

四平台 GitHub Actions 作业、测试数量检查及首次远端运行限制见 [CI.md](../docs/migration/CI.md)。

云端图形冒烟可用 `DISPLAY=:99 ./scripts/run-desktop-cloud.sh`（先启动可用的 Xorg dummy display），具体经过与限制见验证记录。
