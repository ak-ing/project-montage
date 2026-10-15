# 四平台骨架 CI

工作流位于 [kmp.yml](../../.github/workflows/kmp.yml)。推送 `rewrite/kmp` 或提交 PR 时，修改 `kmp/**` 或工作流自身会触发；也支持手动运行。仅文档修改不触发构建。

| 作业 | GitHub 托管主机 | 检查 |
| --- | --- | --- |
| Windows 桌面 | windows-2025，x64 | 官方 kotlin.bat、桌面 JVM 编译、core JVM 测试 |
| macOS 桌面 | macos-26，Arm64 | 官方 kotlin、桌面 JVM 编译、core JVM 测试 |
| Android | ubuntu-latest | Debug APK、core Android host 单元测试 |
| iOS | macos-26，Arm64 | Xcode/模拟器 SDK 检查、模拟器应用构建、core iosSimulatorArm64 测试 |

所有构建由仓库固定版本的 Kotlin Toolchain wrapper 发起。CI 不载入云实例专用的 `cloud-env.sh`。Android 使用托管 runner 预装 SDK 和已接受的许可；缺少许可时明确失败，不生成许可文件。iOS 首次构建自动生成的 Xcode 工程作为检查证据上传，后续需在 Mac 上检查并纳入版本管理。

测试后执行 `kmp/scripts/verify-ci-tests.py`：JVM/Android 检查 JUnit XML，iOS 检查 Toolchain TeamCity 事件，要求至少执行当前五个测试、全部完成、无失败且无跳过。构建或测试命令自身失败也会使作业失败。产物和报告保留七天；桌面 JAR 不是自包含安装包。

工作流仅申请 `contents: read`，checkout 不保留认证信息，不需要云服务密钥，不发布或签名。Actions 固定为官方 release 对应完整提交：checkout v7.0.1、upload-artifact v7.0.2。更新时从各官方仓库重新核实 release 和 SHA。相同分支的新运行取消旧运行。

## 本次验证与限制

2026-10-14 UTC，本地使用校验过官方发布 SHA-256 的 actionlint 1.7.12 检查通过。报告脚本已读取实际 JVM/Android 各五条成功测试，并通过重新执行 JVM 测试得到的真实 TeamCity 日志验证。缺报告、零测试、失败、跳过和未完成事件五种拒绝场景也已检查。

**尚未推送或触发 GitHub Actions；以上四个远端作业均未实际运行。** 工作流配置不能证明 Windows/macOS/iOS 已编译成功。首次运行仍需确认 Toolchain 在托管 runner 上的下载、Xcode 工程生成、Apple SDK 兼容性与模拟器启动。

此工作流不覆盖桌面 UI 启动、macOS Intel、Windows Arm64、Android 设备 UI、iOS 真机、安装包、签名、公证或完整云生成流程。现有 Linux 实测结果及完整未验证清单见 [VALIDATION.md](VALIDATION.md)。
