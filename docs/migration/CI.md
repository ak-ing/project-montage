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

10 月 14 日仅完成本地验证；远端结果见下面的运行记录。工作流配置本身不构成平台验证证据。

## 2026-10-15 提交与首次触发

骨架、迁移文档与工作流已随提交 `8f6c3a3` 推送至私有仓库的 `rewrite/kmp`。这次推送符合工作流的分支与路径过滤条件。本次提交前重新执行 core JVM 测试，五项均成功；actionlint 和暂存内容检查通过。`kmp/.gitattributes` 保持官方 wrapper 原始字节，避免 Windows checkout 改变已校验文件。

最初 API 被网络策略拒绝；配置生效后，已确认私有仓库 API 可用。使用 `gh` 时显式指定 `-R ak-ing/project-montage-private`，避免工具根据 fork 的上游选中 Google 仓库。

首次运行：[38728676534](https://github.com/ak-ing/project-montage-private/actions/runs/38728676534)，提交 `8f6c3a3`。Windows、macOS、Android 三个作业成功（含测试数量检查）；iOS 在 Swift 编译阶段失败，后续测试未执行。GitHub check annotations 明确报出 `Scene` 类型查找歧义及 `MontageApp` 不符合 `App` 协议。共享模型导出的 `Scene` 与 SwiftUI 同名；提交 `6bdfc0d` 将返回类型限定为 `SwiftUI.Scene`。

修复后的运行：[38729383572](https://github.com/ak-ing/project-montage-private/actions/runs/38729383572)，提交 `6bdfc0d`，**总体 success，四个作业全部 success**：

| 平台 | 已验证 |
| --- | --- |
| Windows x64 | 桌面 JVM 编译、core JVM 测试、报告检查、产物上传 |
| macOS Arm64 | 桌面 JVM 编译、core JVM 测试、报告检查、产物上传 |
| Android | Debug APK、core host 测试、报告检查、产物上传 |
| iOS Arm64 模拟器 | Swift/Kotlin 应用编译、core 模拟器测试、TeamCity 事件检查、证据上传 |

每个作业的测试检查均成功：至少五个测试完成、无失败、无跳过。当前套件含五个测试；由于原始报告下载受限，这里依据远端检查结果，不声称已在本地审阅所有 XML/日志。iOS 的 `SwiftUI.Scene` 修复已由实际编译证实。


环境发布后，网络策略已报告 `enforced`，GitHub API 可用，并再次确认上述四个作业全部成功。下载产物的新重定向主机为 `productionresultssa2.blob.core.windows.net`，说明 GitHub 会使用不同存储节点；仅允许之前的 `productionresultssa17.blob.core.windows.net` 不足。

GitHub [官方 runner 网络要求](https://github.com/github/docs/blob/main/data/reusables/actions/runner-essential-communications.md) 将 `*.blob.core.windows.net` 列为日志、产物和缓存上传/下载所需域名。已保留原有配置并将该通配规则保存到环境配置草稿，需发布这次新增规则后才能验证下载。该规则覆盖 Azure Blob 域名，范围比单个存储节点更广，是为了适配官方会变动的下载目标；不应以单个 `saN` 主机替代。配置保存不代表运行实例已应用新规则。

目前尚未将远端报告或生成的 Xcode 工程下载到本地。发布后可重新执行 `gh run download 38729383572 -R ak-ing/project-montage-private`。四平台 UI 启动冒烟仍未完成。


此工作流不覆盖桌面 UI 启动、macOS Intel、Windows Arm64、Android 设备 UI、iOS 真机、安装包、签名、公证或完整云生成流程。现有 Linux 实测结果及完整未验证清单见 [VALIDATION.md](VALIDATION.md)。
