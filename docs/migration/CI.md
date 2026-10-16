# 四平台骨架 CI

## 当前设备工作区验证

运行 [38846483748](https://github.com/ak-ing/project-montage-private/actions/runs/38846483748)（源码 `b70eb47`）四个作业全部成功。四份主报告 ZIP 已下载并校验 SHA-256，实际报告确认每端 12 个领域测试（共 48 次执行）、每个桌面 4 个存储/UI 测试（共 8 次执行）、Android adb 流程及 iOS 1 个 XCTest 流程均成功，无失败、无跳过。完整清单见 [workspace-38846483748.json](evidence/workspace-38846483748.json)。完整 iOS xcresult 由 Actions 另行保存，本次审阅主报告中的汇总、日志和截图附件。

桌面流程覆盖保存→重置编辑→重载，并通过新建 repository 验证磁盘结果；另覆盖损坏文件保护与修复后重试。Android/iOS 覆盖保存→终止进程→重新启动→恢复标题及分镜→重置并保存。四端恢复截图已逐张审阅并纳入仓库：

| Windows | macOS | Android 模拟器 | iOS 模拟器 |
| --- | --- | --- | --- |
| [恢复后](evidence/workspace-windows-restored.png) | [恢复后](evidence/workspace-macos-restored.png) | [恢复后](evidence/workspace-android-restored.png) | [恢复后](evidence/workspace-ios-restored.png) |

测试仅验证本地草稿和检查点基础，没有模型 API、任务执行或媒体处理。以下保留先前骨架的验证基线。

## 已验证的原始骨架基线

运行：[38759747810](https://github.com/ak-ing/project-montage-private/actions/runs/38759747810)，源码 `75f1f04`，**四个作业全部成功**。已下载并逐份校验五个产物包，审阅实际报告：20 次 core 测试执行与四条 UI 流程均成功，无失败、无跳过。移动端 UI 结果来自模拟器。完整摘要见 [验证清单](evidence/ui-38759747810.json)。

| 平台 | 托管主机 | 构建与共享测试 | UI 流程 |
| --- | --- | --- | --- |
| Windows | windows-2025，x64 | 桌面 JVM 构建、5 个 core 测试 | JUnit/Compose UI，真实 ComposeWindow 与本机 Skiko |
| macOS | macos-26，Arm64 | 桌面 JVM 构建、5 个 core 测试 | 同上，在 macOS 主机执行 |
| Android | ubuntu-latest | Debug APK、5 个 core host 测试 | API 35 x86_64 模拟器，安装 APK，adb/UIAutomator |
| iOS | macos-26，Arm64 | Swift/Kotlin 应用构建、5 个 core 模拟器测试 | XCTest 启动实际应用，经系统无障碍界面交互 |

每端 UI 流程检查：初始 `2 scenes / 10s / 1s`、空描述时新增按钮禁用、编辑标题、输入并新增分镜、`3 scenes / 14s / 2s`、输入清空后按钮再次禁用，以及重置回样例。保存初始、新增、重置截图。Android 另保存 UI 树、emulator.log、logcat；iOS 保存结果 JSON、截图附件及完整 xcresult。

最终截图已审阅并纳入版本管理，避免只依赖七天后过期的 Actions 产物：

| Windows | macOS | Android | iOS |
| --- | --- | --- | --- |
| [新增分镜](evidence/ui-windows-added.png) | [新增分镜](evidence/ui-macos-added.png) | [新增分镜](evidence/ui-android-added.png) | [新增分镜](evidence/ui-ios-added.png) |

Android 截图检查发现默认系统 ActionBar 与 CMP 重复显示标题；现使用 NoActionBar 与 edge-to-edge，并通过 UI 树确认标题仅有一个。修改后的完整交互、统计、重置及崩溃检查再次通过。桌面各有 1 个 JUnit UI 测试、Android 有 1 条 adb 流程，iOS 有 1 个 XCTest UI 测试；各端另有 5 个领域测试。

## 指定 CI 产物与 Xcode 审查

2026-10-15 已下载指定运行 [38729383572](https://github.com/ak-ing/project-montage-private/actions/runs/38729383572)（`6bdfc0d`）全部四份 ZIP，在本地按 GitHub 提供的 SHA-256 逐份校验。Windows/macOS/Android 的 JUnit 报告各 5 成功、0 失败、0 跳过；iOS TeamCity 日志也为 5 开始、5 完成、0 失败、0 跳过。额外的空测试引擎没有计入执行数量。[产物校验清单](evidence/ci-38729383572.json)包含提交、产物 ID、摘要和测试名。这些旧报告只覆盖共享逻辑。

旧 Xcode 产物含 `project.pbxproj` 与共享 `app.xcscheme`，但 bundle ID 为默认 `ios-app`、没有明确最低 iOS 版本、scheme 的 Testables 为空。已将审查后的工程纳入版本管理，设置 `dev.montage.app`、iOS 15.0，保留 Toolchain 管理的 Kotlin 集成阶段，新增独立 `MontageUITests` 目标。UI 测试源码位于 `ios-app/ui-tests/`，不参与生产 app 的同步源码组。模拟器不需要开发团队或真机签名。

首次 iOS Swift 构建中的 `Scene` 重名问题已由 `SwiftUI.Scene` 限定修复，并由实际编译验证。UI 测试按实际无障碍树使用 `TextView` 查询当前多行输入框；测试不会把无匹配控件视为成功。

本次接续早期，直接 `gh run download` 遇到 Blob 403；已通过连接的 GitHub 下载工具取回原始和最终产物，再在本地核对摘要，完成了证据检查，没有重复修改环境草稿。

## 检查与产物规则

工作流是 [kmp.yml](../../.github/workflows/kmp.yml)。推送 `rewrite/kmp` 或提交 PR 时，修改 `kmp/**` 或工作流会触发，也支持手动运行。仅 `docs/**` 修改不触发构建。所有构建与测试由固定版本的 Kotlin Toolchain wrapper 发起，没有新增 Gradle 工程。

`scripts/verify-ci-tests.py` 检查 JVM/Android JUnit XML 与 iOS TeamCity 事件，工作流显式要求每端至少 12 个领域测试，全部完成、无失败、无跳过。桌面存储/UI 要求至少 4 个成功测试；Android 脚本断言失败与 iOS XCTest/汇总失败都会使作业失败。构建成功、配置了测试或零测试均不能替代 UI 验证。

Android 从明确的 SDK 工具路径启动模拟器，不依赖 runner 的 PATH。AVD 请求 2 GB 用户数据分区；镜像仍可能要求更高初始化下限。构建后清理这个一次性 runner 中不再使用的 NDK、CMake 与 .NET，释放镜像初始化所需空间。新版默认 12 GB 超过构建后约 5.9 GB 的可用磁盘，缩小分区后仍约需 7.2 GB，故不能只靠分区设置。监控在模拟器进程提前退出时立即失败，避免等待完整启动超时。坐标从 UI XML 的控件边界计算。

iOS 优先复用已启动的 iPhone 模拟器，测试有执行超时。失败时也尝试导出汇总与附件。完整 xcresult 单独上传为 `ios-ui-xcresult`，主要 `ios-simulator-evidence` 包保留日志、结果 JSON、截图和工程，便于直接审阅。

所有产物保留七天；代表截图另存于仓库证据目录。桌面 JAR 不是自包含安装包。软件渲染用于稳定的桌面功能验证，不代表 GPU 性能测试。

工作流仅申请 `contents: read`，checkout 不保留认证信息，不需要云服务密钥、不发布或签名。官方 checkout v7.0.1 与 upload-artifact v7.0.2 固定完整提交，工作流已用 actionlint 1.7.12 检查。Android 使用托管 SDK 与已有许可，不伪造许可文件。

## 复现与范围

从 `kmp/` 执行：

```sh
./kotlin test -m desktop-app --platform jvm
python3 scripts/verify-ci-tests.py --xml-dir build/reports/desktop-app/jvm --min-tests 4
```

Windows 使用 `kotlin.bat`，桌面需要图形会话。无 GPU 云端使用 `SKIKO_RENDER_API=SOFTWARE`；当前云实例还需 source `scripts/cloud-env.sh` 并提供 Xorg `DISPLAY`。CI 不加载云专用脚本。

Android：`./kotlin build -m android-app` 后，在有 KVM、已接受 SDK 许可的 Linux 主机运行 `./scripts/boot-android-smoke.sh`。已有设备可在安装 APK 后执行 `python3 scripts/android-ui-smoke.py --serial <serial>`，并确保 adb 在 PATH。

iOS：在 macOS/Xcode 上先 `./kotlin build -m ios-app --platform iosSimulatorArm64`，再 `./scripts/ios-ui-smoke.sh`。

移动端 UI 流程使用独立、初始为空的测试安装，会修改并保存草稿；不要将日常项目目录作为测试数据。桌面测试自建临时目录，不使用默认项目目录。

当前验证对象是设备端草稿工作区与任务检查点基础。macOS Intel、Windows Arm64、Android/iOS 真机、旋转/生命周期全面回归、安装包、签名、公证、GPU 性能与设备端生成全流程（模型 API 直连及本地媒体处理/导出）仍未覆盖。完整记录见 [VALIDATION.md](VALIDATION.md)。
