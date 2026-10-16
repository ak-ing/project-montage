# 四平台骨架 CI

## 本地视频导入验证（本次）

2026-10-16 仓库改名并公开后，[iOS 定向复检 38877487547](https://github.com/ak-ing/project-montage/actions/runs/38877487547)（源码 `68e3006`）全部成功：16 个原生领域测试与 2 个 XCTest，无失败、无跳过。已下载并校验主报告及完整 xcresult ZIP 的 SHA-256，审阅恢复后的素材页；实际日志记录了取消、选择回调和受管理副本提交，存储断言确认原素材未改动、移除保存后副本清理完成。前三端沿用源码未变化的 `38852177157`，合计 64 次领域测试、20 次桌面测试和两条移动端 UI 流程全部成功。[完整证据清单](evidence/video-import-38877487547.json)逐项记录报告来源，未将本轮跳过的作业视为通过。

当前工作流要求每端至少 16 个领域测试、每个桌面至少 10 个存储/导入/UI 测试，以及 iOS 恰好 2 个 XCTest 用例。桌面新增原生 FileDialog 打开/关闭、分块复制/失败清理/保留引用和共享素材 UI 验证；桌面选择成功路径通过注入选择结果调用真实文件适配器，尚未自动驱动原生对话框选中文件。Android/iOS 直接操作系统文档选择器，覆盖取消、导入、保存、重启恢复和移除；Android 另删除外部源并逐字节核对内部副本及清理结果。测试素材为 [自生成一秒蓝色视频](../../kmp/test-data/README.md)。

复检运行 [38852177157](https://github.com/ak-ing/project-montage/actions/runs/38852177157)（源码 `a212a16`）的 Windows/macOS 各 16 个领域测试、10 个存储/导入/UI 测试全部成功，Android 的 16 个 host 测试与系统选择器流程成功；三份 ZIP 已校验摘要，素材页截图已检查。Android 内部副本与源测试片 SHA-256 一致，删除外部源后可恢复，移除保存后受管理目录为空；这轮没有触发 Launcher 恢复。

iOS 这轮失败点为缩略图可见但不支持 XCTest 的直接点击（命中点 `-1,-1`）。`3f73e2c` 改为依据无障碍边界计算中心坐标点击，在 [38853815667](https://github.com/ak-ing/project-montage/actions/runs/38853815667) 定向复检：16 个领域测试成功，XCTest 草稿用例成功、视频导入用例失败，选择器没有返回，界面停留在 Working。此结果不算 iOS 导入通过。

`0107eed` 将 iOS 选择器改为复制导入、从顶层控制器呈现并补充回调日志。2026-10-16 的定向运行 [38856216880](https://github.com/ak-ing/project-montage/actions/runs/38856216880) 曾被 Actions 计费限制阻止，作业无执行步骤；该修正现已在 `38877487547` 编译并验证通过。已核对 `a212a16..68e3006` 的非 iOS KMP 源码未变化，因此保留前三端实际通过的报告。状态与历史失败记录见 [video-import-status.json](evidence/video-import-status.json)。

| Windows | macOS | Android | iOS |
| --- | --- | --- | --- |
| [素材页](evidence/video-import-windows.png) | [素材页](evidence/video-import-macos.png) | [素材页](evidence/video-import-android.png) | [重启恢复后的素材页](evidence/video-import-ios-restored.png) |

首轮 [38849878968](https://github.com/ak-ing/project-montage/actions/runs/38849878968) 定位了测试环境/交互问题：macOS 的 Robot Escape 未关闭文件框，现使用应用取消时的原生关闭路径；Android 冷启动被 Pixel Launcher ANR 弹窗遮挡，脚本仅允许记录并关闭该确切系统弹窗一次，Montage 崩溃/ANR 仍失败；iOS 文件网格中心点击后未返回，现按无障碍树选择文件缩略图，系统出现 Open 确认时完成该操作。两条 XCTest 的构建与执行共用 16 分钟步骤上限，单例执行仍由 Xcode 的 120/180 秒限制保护。

## 设备工作区验证基线

运行 [38846483748](https://github.com/ak-ing/project-montage/actions/runs/38846483748)（源码 `b70eb47`）四个作业全部成功。四份主报告 ZIP 已下载并校验 SHA-256，实际报告确认每端 12 个领域测试（共 48 次执行）、每个桌面 4 个存储/UI 测试（共 8 次执行）、Android adb 流程及 iOS 1 个 XCTest 流程均成功，无失败、无跳过。完整清单见 [workspace-38846483748.json](evidence/workspace-38846483748.json)。完整 iOS xcresult 由 Actions 另行保存，本次审阅主报告中的汇总、日志和截图附件。

桌面流程覆盖保存→重置编辑→重载，并通过新建 repository 验证磁盘结果；另覆盖损坏文件保护与修复后重试。Android/iOS 覆盖保存→终止进程→重新启动→恢复标题及分镜→重置并保存。四端恢复截图已逐张审阅并纳入仓库：

| Windows | macOS | Android 模拟器 | iOS 模拟器 |
| --- | --- | --- | --- |
| [恢复后](evidence/workspace-windows-restored.png) | [恢复后](evidence/workspace-macos-restored.png) | [恢复后](evidence/workspace-android-restored.png) | [恢复后](evidence/workspace-ios-restored.png) |

测试仅验证本地草稿和检查点基础，没有模型 API、任务执行或媒体处理。以下保留先前骨架的验证基线。

## 已验证的原始骨架基线

运行：[38759747810](https://github.com/ak-ing/project-montage/actions/runs/38759747810)，源码 `75f1f04`，**四个作业全部成功**。已下载并逐份校验五个产物包，审阅实际报告：20 次 core 测试执行与四条 UI 流程均成功，无失败、无跳过。移动端 UI 结果来自模拟器。完整摘要见 [验证清单](evidence/ui-38759747810.json)。

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

2026-10-15 已下载指定运行 [38729383572](https://github.com/ak-ing/project-montage/actions/runs/38729383572)（`6bdfc0d`）全部四份 ZIP，在本地按 GitHub 提供的 SHA-256 逐份校验。Windows/macOS/Android 的 JUnit 报告各 5 成功、0 失败、0 跳过；iOS TeamCity 日志也为 5 开始、5 完成、0 失败、0 跳过。额外的空测试引擎没有计入执行数量。[产物校验清单](evidence/ci-38729383572.json)包含提交、产物 ID、摘要和测试名。这些旧报告只覆盖共享逻辑。

旧 Xcode 产物含 `project.pbxproj` 与共享 `app.xcscheme`，但 bundle ID 为默认 `ios-app`、没有明确最低 iOS 版本、scheme 的 Testables 为空。已将审查后的工程纳入版本管理，设置 `dev.montage.app`、iOS 15.0，保留 Toolchain 管理的 Kotlin 集成阶段，新增独立 `MontageUITests` 目标。UI 测试源码位于 `ios-app/ui-tests/`，不参与生产 app 的同步源码组。模拟器不需要开发团队或真机签名。

首次 iOS Swift 构建中的 `Scene` 重名问题已由 `SwiftUI.Scene` 限定修复，并由实际编译验证。UI 测试按实际无障碍树使用 `TextView` 查询当前多行输入框；测试不会把无匹配控件视为成功。

本次接续早期，直接 `gh run download` 遇到 Blob 403；已通过连接的 GitHub 下载工具取回原始和最终产物，再在本地核对摘要，完成了证据检查，没有重复修改环境草稿。

## 检查与产物规则

工作流是 [kmp.yml](../../.github/workflows/kmp.yml)。推送 `rewrite/kmp` 或提交 PR 时，修改 `kmp/**` 或工作流会触发完整四端构建；手动运行可用 `platform=all/desktop/android/ios` 定向验证，例如 `gh workflow run 385462765 -R ak-ing/project-montage --ref rewrite/kmp -f platform=ios`。复用其他平台报告前须证明相关源码未变化，并记录实际来源提交/作业，不将未执行作业当成成功。仅 `docs/**` 修改不触发构建。所有构建与测试由固定版本的 Kotlin Toolchain wrapper 发起，没有新增 Gradle 工程。

`scripts/verify-ci-tests.py` 检查 JVM/Android JUnit XML 与 iOS TeamCity 事件，工作流显式要求每端至少 16 个领域测试，全部完成、无失败、无跳过。桌面存储/导入/UI 要求至少 10 个成功测试；Android 脚本断言失败与 iOS 两个 XCTest/汇总失败都会使作业失败。构建成功、配置了测试或零测试均不能替代 UI 验证。

Android 从明确的 SDK 工具路径启动模拟器，不依赖 runner 的 PATH。AVD 请求 2 GB 用户数据分区；镜像仍可能要求更高初始化下限。构建后清理这个一次性 runner 中不再使用的 NDK、CMake 与 .NET，释放镜像初始化所需空间。新版默认 12 GB 超过构建后约 5.9 GB 的可用磁盘，缩小分区后仍约需 7.2 GB，故不能只靠分区设置。监控在模拟器进程提前退出时立即失败，避免等待完整启动超时。坐标从 UI XML 的控件边界计算。

iOS 优先复用已启动的 iPhone 模拟器，先用 `build-for-testing` 构建测试包，再安装应用并将测试视频放入独立安装的 Documents，随后 `test-without-building` 执行两个用例。测试有执行超时，失败时也尝试导出汇总与附件。完整 xcresult 单独上传为 `ios-ui-xcresult`，主要 `ios-simulator-evidence` 包保留日志、结果 JSON、截图和工程，便于直接审阅。

所有产物保留七天；代表截图另存于仓库证据目录。桌面 JAR 不是自包含安装包。软件渲染用于稳定的桌面功能验证，不代表 GPU 性能测试。

工作流仅申请 `contents: read`，checkout 不保留认证信息，不需要云服务密钥、不发布或签名。官方 checkout v7.0.1 与 upload-artifact v7.0.2 固定完整提交，工作流已用 actionlint 1.7.12 检查。Android 使用托管 SDK 与已有许可，不伪造许可文件。

## 复现与范围

### Actions 额度与 CNB 分流（2026-10-16 调研）

此前私有仓库 Actions 额度耗尽。2026-10-16 用户将原仓库改名为 `ak-ing/project-montage` 并设为 Public，仓库 ID `1349344394` 与历史运行保留，本地 origin 已更新。标准托管 runner 的公开仓库运行不消耗私有仓库分钟额度；大型 runner 仍收费，详见 [GitHub 官方计费文档](https://docs.github.com/en/billing/concepts/product-billing/github-actions)。本次没有更改付费设置。定向重跑只需 iOS，前三端相关源码未变，不必重复完整矩阵。

CNB [定价文档](https://docs.cnb.cool/en/pricing.md)列出 Events-CPU 免费额度为每月 160 核时，月末重置；4 核累计约 40 小时、默认 8 核约 20 小时，不能按单核分钟理解。实际以组织「用量管理」为准，Workspaces 的额度不是流水线 Events 额度。

CNB 托管构建使用 Docker，可承接 Linux/JVM 测试及配置好 Android SDK 后的 APK 构建。Android 模拟器仍须实测节点的 `/dev/kvm` 能力，不能把 APK 编译成功当作 UI 冒烟通过。[构建节点文档](https://docs.cnb.cool/en/build/build-node.md)支持根组织接入自有 Mac/Windows 构建机，组织可能需要申请开启；这不代表平台免费提供 Mac/Windows。iOS 仍须 macOS/Xcode，Windows 原生 UI 仍须 Windows。自有构建机属于开发验证设施，不改变产品无需自建后端的架构。

尚未创建 CNB 仓库、同步私有源码、接入构建机或执行 CNB 流水线；迁移需要明确目标仓库与相应账户访问。

从 `kmp/` 执行：

```sh
./kotlin test -m desktop-app --platform jvm
python3 scripts/verify-ci-tests.py --xml-dir build/reports/desktop-app/jvm --min-tests 10
```

Windows 使用 `kotlin.bat`，桌面需要图形会话。无 GPU 云端使用 `SKIKO_RENDER_API=SOFTWARE`；当前云实例还需 source `scripts/cloud-env.sh` 并提供 Xorg `DISPLAY`。CI 不加载云专用脚本。

Android：`./kotlin build -m android-app` 后，在有 KVM、已接受 SDK 许可的 Linux 主机运行 `./scripts/boot-android-smoke.sh`。已有设备可在安装 APK 后执行 `python3 scripts/android-ui-smoke.py --serial <serial>`，并确保 adb 在 PATH。

iOS：在 macOS/Xcode 上先 `./kotlin build -m ios-app --platform iosSimulatorArm64`，再 `./scripts/ios-ui-smoke.sh`。

移动端 UI 流程使用独立、初始为空的测试安装，会修改并保存草稿；不要将日常项目目录作为测试数据。桌面测试自建临时目录，不使用默认项目目录。

当前验证对象是设备端草稿工作区、任务检查点与本地视频导入。macOS Intel、Windows Arm64、Android/iOS 真机、旋转/生命周期全面回归、安装包、签名、公证、GPU 性能与设备端生成全流程（模型 API 直连及本地媒体处理/导出）仍未覆盖。完整记录见 [VALIDATION.md](VALIDATION.md)。
