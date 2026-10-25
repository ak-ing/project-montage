# 四平台 CI

## 分镜素材关联与片段编辑（2026-10-24：三端通过，iOS 受阻）

[40166230382](https://github.com/ak-ing/project-montage/actions/runs/40166230382)（源码 `655449a`）的 Windows、macOS 和 Android 作业成功，iOS 作业失败。四份主产物 ZIP 已下载并校验 SHA-256，测试报告、截图和原生日志已核对；没有将配置完成或空目录视为四端验收。[完整证据](evidence/scene-edits-40166230382.json)与[运行历史](evidence/scene-edits-status.json)保留具体来源。

| 平台 | 核心测试 | UI / 存储结果 |
| --- | --- | --- |
| Windows x64 | 26 成功 | 15 个桌面测试成功；真实 8 秒/1 秒源切换、范围校验、排序、磁盘 schema 2 迁移、保存失败重试/恢复、缺失修复、解除关联与清理 |
| macOS Arm64 | 26 成功 | 同上，Compose 离屏场景与真实 FFmpeg |
| Android API 35 模拟器 | 26 个 host 测试成功 | 实际 APK / 系统选择器；错误区间、排序、强制停止后精确 ID/区间/JSON 恢复、引用保护和解除后移除清理成功 |
| iOS 26.5 Arm64 模拟器 | 26 个原生测试成功 | 5 条 XCTest 中 1 成功、4 失败、无跳过；原生文件 URL 获取失败，新分镜恢复未验证，未验收 |

合计 104 次核心测试、30 次桌面测试均成功，但整轮并未通过。iOS 只有草稿用例成功：独立 UIKit 对照取消后重开选择器，点击原始 1 秒素材仍无法得到 URL；JSON 记录取消 1 次、读取 0 次。FileProvider 在 KMP 选择器打开前已报告 `did=3 / FP -1005 / Cannot get URL`，原产品导入随后同样失败，分镜与预览用例被前置状态阻塞。该问题可在独立原生程序复现，排查范围是模拟器、文件提供器和测试素材提供链路；不能据此声称所有 iOS 环境或 KMP 代码均已排除问题。

独立 UIKit 诊断 [40214856335](https://github.com/ak-ing/project-montage/actions/runs/40214856335)（源码 `8545e6c`）未构建 Kotlin，仍在原生选择器点击原始 1 秒素材后复现 `localstorage/did=3 / FP -1005`；取消 1 次、读取 0 次。源文件字节和容器路径未变，宿主机与模拟器 Foundation 可读取该文件的文档 ID `3`，普通文件引用 URL 也可读。这些读取不等于文件选择回调成功，诊断不计为产品验收。此前独立探针 `40213153912` 停在 XCTest 目录导航，未发生选文件；将它与 URL 失败分别记录，见[诊断证据](evidence/native-files-probe-status.json)。

进一步诊断 [40215962225](https://github.com/ak-ing/project-montage/actions/runs/40215962225) 确认数据目录无符号链接、所在卷为 APFS，宿主机与模拟器按 inode 均可解析三个源文件。本轮又在 XCTest 导航超时，未进入源目录，不能算作 URL 问题的再次复现或修复；报告保留了该差别。

最新原生对照 [40217561432](https://github.com/ak-ing/project-montage/actions/runs/40217561432)（`b707af1`）使用完整 XCTest 查询到达了实际文件点击，仍未收到 URL。正确参数下，内核和 Foundation 均读到文档 ID `3/5/4`，三个文件均已跟踪（BSD flags `64`）；实时日志显示同一 FileProvider 进程在 `17:49:58` 成功解析 `did=3`，`17:50:05` 的 `URLForItemID` 内部重试后却返回 `ENOENT / FP -1005`。文件和容器仍未变，具体内部原因尚未确定。此前查询对照的编译失败与一次缺少必需选项的无效内核测量，均在诊断报告中单独注明并已修正，未计作系统证据或 UI 通过。

源应用在 iOS 内创建小测试文件；原子发布与初次最终文件名写入均曾失败，当前 `Data.write(.withoutOverwriting)` 保持初始文件标识的对照也未解决。三个源文件写入完成并逐字节校验后才运行 XCTest，结束后原文件仍未变。本轮工作区、媒体和预览临时目录为空，是因为导入未发生，**不计为实际清理验收**。原三条产品用例、新分镜用例、纯 Swift 对照、原始 1 秒片和同进程取消/重开条件均保留。

已完成 schema 3 的素材关联、分镜顺序和毫秒级入点/独占出点；编辑时按真实时长校验，所有引用解除前禁止移除素材。生成目标时长保留，本地编辑时长单独显示。共享迁移逻辑在四端单测通过，桌面真文件迁移保留素材与任务检查点。Windows 文件重命名探针已等待实际播放器关闭；Android 菜单按可见文件名选择并保留精确 ID/区间断言；iOS 输入查询已改为实际 TextView 类型，报告持久存储和上传清单均已补齐，当前仍被文件 URL 阻塞。

| Windows | macOS | Android | iOS |
| --- | --- | --- | --- |
| [恢复后的编辑](evidence/scene-edits-windows.png) | [恢复后的编辑](evidence/scene-edits-macos.png) | [恢复后的编辑](evidence/scene-edits-android.png) | [原生选择器阻塞现场](evidence/scene-edits-ios-blocked.png)，非分镜验收截图 |

下一步优先解决 iOS 文件 URL 提供问题，再运行完整五条 XCTest、真实片段恢复与全部存储守卫；通过后再进入设备端基础裁切、拼接和系统导出。当前未调用模型 API、未实现实际裁切/编码/导出。桌面是离屏场景，移动端是模拟器；真机、物理声音、性能与完整生命周期另行验证。主 iOS 包内 Xcode 工程已核对 bundle ID、iOS 15.0 和共享 scheme；完整 xcresult 大包未下载。

## 视频信息与播放预览（2026-10-24 已通过）

运行 [40115876922](https://github.com/ak-ing/project-montage/actions/runs/40115876922)（源码 `f9a3698`）四个作业全部成功。四份主产物 ZIP 已下载并逐份校验 SHA-256，实际 XML/TeamCity、移动端 JSON、截图与原生阶段日志已核对；测试无失败、无跳过。[完整证据清单](evidence/video-preview-40115876922.json) 保留提交、作业、产物摘要、测试名与截图来源，[历史记录](evidence/video-preview-status.json) 保留先前失败。

| 平台 | 领域测试 | 存储、媒体与 UI 证据 |
| --- | --- | --- |
| Windows x64 | 19 成功 | 14 个桌面测试成功；真实 FFmpeg 解码、缩略图、播放/暂停、红色 seek、重播、恢复及损坏修复重试 |
| macOS Arm64 | 19 成功 | 同上；Compose 离屏场景与本机解码器 |
| Android API 35 模拟器 | 19 个 host 测试成功 | 实际 APK 与系统选择器；元数据、解码帧、播放/暂停/seek、恢复、损坏修复重试与清理 |
| iOS 26.5 Arm64 模拟器 | 19 个原生测试成功 | 3 个 XCTest 成功；草稿、取消后再导入及恢复/移除、预览/seek/两次重启/错误重试；最终存储断言通过 |

合计 76 次领域测试、28 次桌面存储/媒体/UI 测试、3 条 iOS XCTest 和 Android 完整 UI 流程。自生成 [八秒蓝→红测试片](../../kmp/test-data/README.md) 含合成音轨；Android 报告 8023 ms，按实际值接受 7950..8100 ms 的 AAC 起始填充差异。iOS 三种素材均保留原文件；保存移除后的受管理目录与预览临时目录为空，日志确认至少三次移除清理。

`b4e9250` 的上一轮三条 XCTest 虽通过，最终存储断言仍发现 22 字节损坏素材副本。原因是未保存的导入再移除回到相同 JSON，保存被禁用。`d03f063` 将待清理副本计入未保存状态，清理失败允许重试。桌面回归先以 13/14 失败复现，再以 14/14 验证；本轮 iOS 明确断言该路径保存可用，并通过最终清理守卫。此前选择器、容器扩展名、重播和原生视图重试等修复也均由本轮覆盖；没有降低测试数量或存储断言。应用容器内测试片出现过 FileProvider 过期 ID/nil URL；目录重进也未解决。最终测试片由独立的测试素材应用通过原生原子写入准备完整文件，再从原生 Files 导航选择，新增容器外来源及字节完整性检查，整套 UI 使用同一台新建模拟器，记录实际设备 ID，保留同进程取消后重开。26.4.1 和 26.5 上此前 UIDocument 素材准备方式的失败均保留，本轮通过范围为原生原子写入的本地测试文件，不代表所有文件提供器均已验证。应用不再把自己的容器 URL 强行设为选择器起点。Toolchain 的 GitHub 下载响应头限制通过固定 CI JDK 和校验后预取同版 xcbeautify 解决，见 [TOOLCHAIN.md](TOOLCHAIN.md)。

| Windows | macOS | Android | iOS |
| --- | --- | --- | --- |
| [红色 seek](evidence/preview-windows-seek.png) | [结束后重播](evidence/preview-macos-replay.png) | [红色 seek](evidence/preview-android-seek.png) | [红色 seek](evidence/preview-ios-seek.png) · [预览中终止后恢复](evidence/preview-ios-terminated-restored.png) |

桌面采用 JavaCV/JavaCPP 1.5.14 和 FFmpeg 8.1.2 默认 LGPL 构建，报告直接检查运行时版本、许可及无 GPL/nonfree 配置。桌面 CI 是 Compose 离屏场景与真实本机解码器；[Linux 实际窗口重播](evidence/preview-linux-window.json) 仅作补充，截图基于相同解码器、早于最终共享 UI 清理修复。移动端为实际安装的模拟器应用。完整 xcresult 大包未下载；已检查的主 iOS 包包含导出汇总、截图、日志和 Xcode 工程。

Xcode 工程再次核对 `dev.montage.app`、最低 iOS 15.0、共享 app scheme 与 `MontageUITests`。真机、物理听音、完整生命周期/旋转、编码兼容性、性能、安装签名及完整发行许可审计仍未覆盖。当前仅为本地素材预览，没有模型 API、拼接或导出。

## 本地视频导入验证（已完成基线）

2026-10-16 仓库改名并公开后，[iOS 定向复检 38877487547](https://github.com/ak-ing/project-montage/actions/runs/38877487547)（源码 `68e3006`）全部成功：16 个原生领域测试与 2 个 XCTest，无失败、无跳过。已下载并校验主报告及完整 xcresult ZIP 的 SHA-256，审阅恢复后的素材页；实际日志记录了取消、选择回调和受管理副本提交，存储断言确认原素材未改动、移除保存后副本清理完成。前三端沿用源码未变化的 `38852177157`，合计 64 次领域测试、20 次桌面测试和两条移动端 UI 流程全部成功。[完整证据清单](evidence/video-import-38877487547.json)逐项记录报告来源，未将本轮跳过的作业视为通过。

该导入基线当时要求每端至少 16 个领域测试、每个桌面至少 10 个存储/导入/UI 测试，以及 iOS 恰好 2 个 XCTest 用例。桌面新增原生 FileDialog 打开/关闭、分块复制/失败清理/保留引用和共享素材 UI 验证；桌面选择成功路径通过注入选择结果调用真实文件适配器，尚未自动驱动原生对话框选中文件。Android/iOS 直接操作系统文档选择器，覆盖取消、导入、保存、重启恢复和移除；Android 另删除外部源并逐字节核对内部副本及清理结果。测试素材为 [自生成一秒蓝色视频](../../kmp/test-data/README.md)。

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
| Windows | windows-2025，x64 | 桌面 JVM 构建、5 个 core 测试 | JUnit/Compose UI，Compose 测试场景与本机 Skiko（离屏渲染） |
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

`scripts/verify-ci-tests.py` 检查 JVM/Android JUnit XML 与 iOS TeamCity 事件，当前工作流显式要求每端至少 19 个领域测试，全部完成、无失败、无跳过。桌面存储/媒体/UI 要求至少 14 个成功测试；Android 脚本断言失败与 iOS 三个 XCTest/汇总失败都会使作业失败。构建成功、配置了测试或零测试均不能替代 UI 验证。

Android 从明确的 SDK 工具路径启动模拟器，不依赖 runner 的 PATH。AVD 请求 2 GB 用户数据分区；镜像仍可能要求更高初始化下限。构建后清理这个一次性 runner 中不再使用的 NDK、CMake 与 .NET，释放镜像初始化所需空间。新版默认 12 GB 超过构建后约 5.9 GB 的可用磁盘，缩小分区后仍约需 7.2 GB，故不能只靠分区设置。监控在模拟器进程提前退出时立即失败，避免等待完整启动超时。坐标从 UI XML 的控件边界计算。

iOS 为整套 UI 用例创建一台独立 iPhone 模拟器，先用 `build-for-testing` 构建测试包，再安装被测应用与独立的测试素材应用。素材应用从 iOS 内原子写入三个完整文件并通过字节校验，随后 `test-without-building` 执行五个用例；取消后重开之间不重置或重启应用。测试有执行超时，失败时也尝试导出汇总与附件。完整 xcresult 单独上传为 `ios-ui-xcresult`，主要 `ios-simulator-evidence` 包保留日志、结果 JSON、截图和工程，便于直接审阅。

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
python3 scripts/verify-ci-tests.py --xml-dir build/reports/desktop-app/jvm --min-tests 15
```

Windows 使用 `kotlin.bat`，桌面需要图形会话。无 GPU 云端使用 `SKIKO_RENDER_API=SOFTWARE`；当前云实例还需 source `scripts/cloud-env.sh` 并提供 Xorg `DISPLAY`。CI 不加载云专用脚本。

Android：`./kotlin build -m android-app` 后，在有 KVM、已接受 SDK 许可的 Linux 主机运行 `./scripts/boot-android-smoke.sh`。已有设备可在安装 APK 后执行 `python3 scripts/android-ui-smoke.py --serial <serial>`，并确保 adb 在 PATH。

iOS：在 macOS/Xcode 上先 `./kotlin build -m ios-app --platform iosSimulatorArm64`，再 `./scripts/ios-ui-smoke.sh`。

移动端 UI 流程使用独立、初始为空的测试安装，会修改并保存草稿；不要将日常项目目录作为测试数据。桌面测试自建临时目录，不使用默认项目目录。

当前验证对象是设备端草稿工作区、任务检查点、本地视频导入与播放预览。macOS Intel、Windows Arm64、Android/iOS 真机、旋转/生命周期全面回归、安装包、签名、公证、GPU 性能与设备端生成全流程（模型 API 直连及本地媒体处理/导出）仍未覆盖。完整记录见 [VALIDATION.md](VALIDATION.md)。
