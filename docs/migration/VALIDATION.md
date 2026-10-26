# 验证记录

## 2026-10-26：分镜素材关联与片段编辑（三端通过，iOS 未验收）

[40329665412](https://github.com/ak-ing/project-montage/actions/runs/40329665412)（源码 `fa8f698`）的四份主产物已下载并校验 SHA-256；104 次核心测试、30 次桌面测试通过，Windows/macOS/Android 的完整分镜流程与恢复截图已核对。iOS 五项 UI 中 2 成功、3 失败、0 跳过，四端里程碑仍未验收。[本轮证据](evidence/scene-edits-40329665412.json)、[完整历史](evidence/scene-edits-status.json)和 [CI.md](CI.md)保留测试名、摘要与原因。

iOS 原生对照取消 1 次并读到三份真实素材，原始 1 秒导入/保存/重启/移除也通过。草稿用例在首次启动时遇到 XCTest 后台断言超时；分镜数字输入、越界拒绝、应用 1250–6500 和排序已执行，但紧接滚动的返回点击未离开编辑页，未进入保存/重启，预览因此阻断。最终媒体副本和预览硬链接各留一个，不能算清理成功。测试助手增加可见区域/滚动稳定检查和明确的返回完成断言后等待复检，不移除任何用例或守卫。

schema 3、旧格式迁移、三端精确素材关联/顺序/片段恢复、引用保护和解除后移除已经实现并验证；四端单测保留草稿、素材和任务检查点。Xcode 产物中的 bundle ID、最低 iOS 15.0、官方 Kotlin 集成和未过滤共享测试 target 已核对。桌面为 Compose 离屏和真实解码器，移动端为模拟器；真机、物理声音、性能和发行未覆盖。

本阶段只保存编辑决定，没有实际裁切、拼接、编码、系统导出或模型调用。完整五条 iOS 和最终存储检查通过后再推进设备端基础成片。

## 2026-10-24：视频信息与播放预览（四端通过）

[40115876922](https://github.com/ak-ing/project-montage/actions/runs/40115876922)（源码 `f9a3698`）四个作业全部成功。四份主报告 ZIP 的 SHA-256、实际测试报告、蓝/红解码帧截图、原生日志和存储结果均已核验：76 次领域测试、Windows/macOS 合计 28 次存储/媒体/UI 测试、3 条 iOS XCTest 和 Android 实际 APK 流程全部成功，测试无失败/跳过。[完整证据清单](evidence/video-preview-40115876922.json)、[截图与复现](CI.md)、[失败历史](evidence/video-preview-status.json) 保留执行来源。

已验证真实时长/尺寸/旋转/音轨、解码缩略图、播放推进/暂停、跳转后红色画面、重启后再打开和损坏文件重试；桌面还验证结束后重播，iOS 在预览打开时终止进程并恢复。返回保留未保存编辑；移动端非活动时暂停，退出/重试释放播放器。元数据/缩略图是派生数据，工作区仍为 schema 2。

本轮还修复未保存素材“导入→移除”回到相同 JSON 导致无法保存/清理的问题。新回归修改前 13/14 失败、修改后 14/14 通过，且验证清理失败保留副本并可再次保存。最终 iOS 存储检查确认原素材未变、工作区无视频引用、受管理目录和预览临时硬链接目录均为空；不是仅看 XCTest 成功。

桌面 FFmpeg 8.1.2 在 Linux/Windows x64/macOS Arm64 实际报告 LGPLv3-or-later，无 GPL/nonfree 配置。桌面 CI 为 Compose 离屏场景与真实解码器，Android/iOS 为模拟器。Linux 补充实际窗口显示结束 8000 ms→重播至 1044 ms 后暂停，项目和素材未变；其共享 UI 早于本轮清理修复。该窗口无声音输出设备，提示后继续视频，未验证物理听音。

已审查主 iOS 包内 Xcode 工程的 bundle ID、iOS 15.0 与共享 scheme/XCTest target；完整 xcresult 大包未下载。真机、物理声音、完整旋转/生命周期、编码兼容性、性能与发行仍需专门验证。模型 API、任务执行器、拼接和导出尚未实现或验证。

用户时区 America/Tijuana：2026-10-13。执行主机记录为 2026-10-14 UTC。Linux x86_64，4 CPU 配额、32 GiB 内存。以下原始本地记录针对骨架；后续的提交、远端 CI 和 UI 冒烟状态见本节及 CI.md。真机与完整业务仍未验证。

## 2026-10-15：本地视频素材导入

已接入桌面 FileDialog、Android OpenDocument 与 iOS UIDocumentPicker，选择后复制到应用自己的 media 目录，项目仅保存不透明 ID/名称/字节数。共享 UI 支持导入、取消/失败重试、素材页、可用状态、移除和保存/重载；成功保存或明确丢弃重载后清理未引用的受管理副本，不删除原素材。schema 1 原子迁移到 2，保留草稿和任务。

本地 Linux：16 个领域测试与 10 个桌面存储/导入/UI 测试全部成功，无失败/跳过；包括原生选择器取消、复制独立性、空文件/读取失败清理、旧文档迁移、损坏/未来版本保护、共享 UI 取消/失败/重试/缺失文件。Android APK 与 16 个 host 测试成功，清理规则调整后桌面复检和 Android 构建也通过。未运行模型 API、视频分析/解码、播放、拼接或导出。

2026-10-16 复核：CI `38852177157` 的 Windows/macOS 各 16 个领域测试和 10 个桌面测试成功，Android 的 16 个 host 测试与实际系统选择器导入、保存、重启恢复、移除流程成功；三份产物已校验 SHA-256 并审阅截图。Android 内部副本逐字节一致，删除外部源后仍恢复成功，移除保存后目录为空。桌面成功选择结果由测试注入，真实 FileDialog 仅验证打开/关闭，未自动操作原生对话框选中文件。

iOS `38853815667` 曾出现视频选择后未返回应用的失败，`0107eed` 修正后的首次复检 `38856216880` 又因 Actions 额度/计费限制未启动。仓库公开后的 `38877487547`（源码 `68e3006`）已完成编译，16 个原生领域测试及 2 个 XCTest 全部成功，无失败/跳过；主报告及完整 xcresult ZIP 的摘要已核对，恢复截图已审阅。存储断言确认导入/移除未改动原素材，保存移除后受管理目录为空。前三端相关源码未变化，合并此前真实执行结果，本地视频导入里程碑现已四端通过；移动端为模拟器，桌面原生文件框成功选择仍采用前述注入方式。[完整证据清单](evidence/video-import-38877487547.json)、[CI.md](CI.md) 与 [状态及历史记录](evidence/video-import-status.json)保留执行来源。

## 2026-10-15：设备工作区与任务检查点基线

已实现 schemaVersion 1 的单工作区文档、四端本地存储、共享 UI 显式保存/重载，以及任务检查点的中断标记。当前 Linux 上 12 个领域测试、4 个桌面存储/UI 测试成功，均无失败/跳过；保存/重载的实际截图已检查。领域测试覆盖多语言草稿与 operation ID 往返、运行任务中断后保存、损坏/未来版本保护、保存及恢复写入失败、重试与任务 ID 校验。

Android APK 构建成功，12 个 core host 测试全部成功、无失败/跳过。测试数量检查与 actionlint 通过，原版服务目录相对上游基线仍无改动。

远端 [38846483748](https://github.com/ak-ing/project-montage/actions/runs/38846483748)（源码 `b70eb47`）四个作业全部成功。四份主报告 ZIP 的 SHA-256、XML/TeamCity、Android result.json、XCTest 汇总与截图附件已核对：

| 平台 | 领域测试 | 存储/UI 验证 |
| --- | --- | --- |
| Windows x64 | 12 成功 | 2 个文件存储测试 + 2 个 Compose 离屏 UI 测试成功；保存/重载及损坏文件修复重试 |
| macOS Arm64 | 12 成功 | 同上，4 个存储/UI 测试成功 |
| Android API 35 模拟器 | 12 个 host 测试成功 | 实际 APK 保存→force-stop→重新启动→恢复→重置/保存成功 |
| iOS Arm64 模拟器 | 12 个原生测试成功 | 1 个 XCTest 成功，覆盖保存→terminate→launch→恢复→重置/保存 |

合计 48 次领域测试、8 次桌面存储/UI 测试和两条移动端恢复流程全部成功，无失败/跳过。[证据清单](evidence/workspace-38846483748.json)记录提交、作业、产物摘要和测试名，四端恢复截图见 [CI.md](CI.md)。原子文件适配器已验证正常保存/读取，未模拟断电或验证多实例并发写入。

没有实际任务执行器、模型 API 调用或媒体处理，也没有运行付费生成。检查点恢复仅标记中断并保留 operation ID，不自动重发任务或轮询；显式保存完成前的编辑仍只在内存中。

## 2026-10-15：先前 CI 产物与四端骨架 UI

运行 38729383572 的四份产物已全部下载，ZIP SHA-256 与 GitHub 记录逐份一致。Windows/macOS/Android 的 XML 各有 5 个领域测试成功，iOS TeamCity 日志记录 5 个测试完成；四端均为 0 失败、0 跳过。[逐项清单](evidence/ci-38729383572.json)保留提交、产物 ID、摘要和测试名。

生成的 Xcode 工程已审查并纳入版本管理，补齐 `dev.montage.app`、iOS 15.0 和 XCTest UI target。

最终运行 [38759747810](https://github.com/ak-ing/project-montage/actions/runs/38759747810)（源码 `75f1f04`）四个作业全部成功。五份 ZIP 已逐份校验 SHA-256，实际 XML、TeamCity、Android result.json 与 XCTest 汇总已核对：

| 平台 | 共享逻辑 | UI 冒烟 |
| --- | --- | --- |
| Windows x64 | 5 成功 | 1 个 Compose 场景/JUnit 测试成功（离屏渲染） |
| macOS Arm64 | 5 成功 | 1 个 Compose 场景/JUnit 测试成功（离屏渲染） |
| Android API 35 x86_64 模拟器 | 5 个 host 测试成功 | adb 驱动实际 APK 的完整流程成功，无应用崩溃 |
| iOS Arm64 模拟器 | 5 个原生测试成功 | 1 个 XCTest UI 测试成功 |

四端均覆盖初始状态、标题编辑、追加分镜、时长/缓冲统计、清空后按钮禁用与重置，均为 0 失败、0 跳过。Android 默认 ActionBar 导致重复标题，已修复并通过标题唯一性检查；最终截图确认修复。完整 [报告清单](evidence/ui-38759747810.json)、四端截图及复现命令见 [CI.md](CI.md)。

当前云实例没有 `/dev/kvm`、Android 设备或 Apple SDK，因此移动端冒烟由对应 GitHub 托管主机执行。没有把本地 Linux 结果等同于四端验证。Linux 自身新增的桌面 UI 测试为 1 执行、1 成功；Android 修改后 APK 构建成功。

## 原始 Linux 验证

| 检查 | 实际结果 |
| --- | --- |
| 仓库/分支/上游 | `rewrite/kmp` 跟踪私有 origin 同名分支；Google `upstream/main` 的 3 提交已快进至 `19de972` |
| 官方 wrapper 完整性 | POSIX 与 Windows wrapper 均按官方安装器的 SHA-256 校验；distribution 继续使用 wrapper 内置校验 |
| `./kotlin --version` | Kotlin Toolchain **0.13.0 (abc7a5f, 2026-10-01)** |
| `./kotlin show modules` / `show settings -m core` | 五模块模型解析成功；Kotlin 2.4.21、JDK 25、共享字节码 17 实际生效 |
| `./kotlin build -m desktop-app` | core/shared-ui/desktop-app 的 JVM 编译成功，生成桌面 JAR |
| `./kotlin test -m core --platform jvm` | **5 执行、5 成功、0 跳过、0 失败**；JUnit 平台 6.1.3 |
| `./kotlin build -m android-app` | Debug APK 成功；安装/许可完成后再次以非交互方式构建成功 |
| `./kotlin test -m core --platform android` | **5 执行、5 成功、0 跳过、0 失败**；这是 host-side Android 目标单元测试，不是设备 instrumentation |
| APK 检查 | `aapt dump badging` 确认 dev.montage.app、version 0.1.0、minSdk 24、target/compileSdk 37 |
| 桌面运行与交互 | Xorg dummy display 上启动 CMP；初始 2 场景/10s/1s 缓冲；输入 smoke scene 并新增后为 3 场景/14s/2s；重置恢复初始状态 |
| 云端启动脚本 | `DISPLAY=:99 ./scripts/run-desktop-cloud.sh` 能重新启动并正常显示；软件渲染与可写缓存配置后运行日志为空 |
| 功能清单一致性 | Python AST 提取 13 个实际 `@mcp.tool()` 注册名，与 Kotlin `MontageTool` 枚举逐项一致 |
| 原版静态检查 | 72 个 Python 文件 AST 解析成功；没有声称原版测试/服务已运行 |
| 原版保留 | 相对 `19de972`，mcp_client/mcp_montage/sign_server/deploy/third_party/LICENSE/README 无差异 |

五个领域测试覆盖：转场缓冲不侵蚀可见时长；空/单场景无边界缓冲；无效时长和重复 ID 拒绝；新增分镜保持原草稿不变且避免 ID 冲突；包含多语言文本的 JSON 往返。

## 原始 Linux 产物与证据

- APK：`kmp/build/tasks/_android-app_buildAndroidDebug/gradle-project-debug.apk`（约 12 MB）。
- APK SHA-256：`52b9bbe2f99a0b183eae67c0d07355053d897de8890ada2bbe7192c20fbc3714`。
- 桌面 JAR：`kmp/build/tasks/_desktop-app_jarJvm/desktop-app-jvm.jar`，不是自包含安装包；使用 Kotlin CLI run 启动。
- 测试 XML：`kmp/build/reports/core/{jvm,android}/TEST-junit-jupiter.xml`。
- [新增场景的实际运行截图](evidence/desktop-add-scene.png)。
- 构建任务日志：`kmp/build/logs/`；依赖图已用 `./kotlin show dependencies -m desktop-app` 检查，解析的 Kotlin stdlib 为 2.4.21。

构建产物/日志被忽略，不纳入源码提交。上述原始 Linux 截图不替代对应平台的运行验证；本次四端报告与截图见文首。完整视频生成仍未实现和验证。

## 已诊断并解决的初次失败

1. 平台初始只 fetch main，自动跟踪 rewrite/kmp 失败：补充对应 fetch refspec，正常切换，未覆盖用户文件。
2. 官方安装与 JetBrains CDN 403：保存域名配置，用户应用后重试成功，未绕过代理。
3. Toolchain 不允许 `settings@android.jvm.release`：共享字节码统一 17，桌面宿主覆盖为 21。
4. 默认 Android SDK/Skiko 缓存写入只读 HOME：通过支持的环境变量/`skiko.data.path` 指向 `/workspace/.tooling`。
5. Google Maven 别名域名不在初始策略中：按官方 Toolchain 文档将 `mavenGoogle` 配置到 Google 自己的 `https://dl.google.com/dl/android/maven2/`。
6. SDK 许可首次未接受：通过 Toolchain 的交互许可流程完成；随后非交互构建成功。当前新版 sdkmanager 提示 `--licenses` 已无作用，不能把该命令的 exit 0 当成 Toolchain 许可已接受。
7. 虚拟显示器缺少 GL context：初始渲染自动降级；云端 helper 显式使用 SOFTWARE 并配置 XDG/Skiko 缓存，随后启动日志为空。没有测量 GPU 性能。

## 未验证 / 不在本次实现内

- **桌面发行与扩展平台矩阵**：Windows x64/macOS Arm64 已通过真实窗口 UI 冒烟；macOS Intel、Windows Arm64、全面原生菜单/键盘/权限回归、MSI/DMG、签名和公证尚未验证。
- **iOS 真机与发行**：应用编译/链接、共享逻辑及模拟器 UI 已通过，Xcode 工程已审查；Team、真机签名、真机 UI、最低 iOS 15 的实际运行及发行仍未验证。
- **Android 扩展设备回归**：实际 APK 已安装到 API 35 模拟器并完成 UI 流程；真机、其他系统版本、旋转、权限及生命周期全面回归仍未验证。
- **设备端生成全流程**：提供商原生认证与 API 直连、Google Search、Gemini/Veo/Omni/Lyria/TTS、媒体合成/字幕/对齐和导出均未运行。本地草稿持久化的验证见文首；真实生成任务的执行、轮询及恢复尚未实现。目标不部署自建 ADK/MCP/GCS 签名后端；原版服务也未作集成验证。骨架没有伪造成功响应。
- **原版运行时测试**：未安装全部 Python/模型权重或启动三服务；当前仓库没有 README 所称 tests/ 套件。原实例曾配置 GOOGLE_APPLICATION_CREDENTIALS 绑定，但没有检查/泄露其值，也没有声称 GCP 授权可用。
- **功能迁移**：当前提供本地草稿编辑、显式保存/重载、检查点存储与视频导入基础；多项目/会话分叉、图片/音频选择、上传、视频分析/播放/拼接/导出、分享、提供商认证、聊天/任务执行/生成均是明确后续任务，不是最终范围删减。
- **发行审计**：传递依赖 SBOM、FFmpeg 构建许可证、MMS_FA 权重和远程演示素材授权未完成。

## 复现

在 `kmp/` 下：

```sh
. ./scripts/cloud-env.sh  # 仅此 /workspace 云环境；本地开发机不需要
./kotlin --version
./kotlin build -m desktop-app
./kotlin test -m core --platform jvm
./kotlin build -m android-app
./kotlin test -m core --platform android
```

首次全新机器的 Android SDK 许可需要在交互终端按 Toolchain 提示处理；已准备的 SDK/许可缓存可以随环境快照保留，不要复制凭据或伪造 license 文件。

云实例有 `/etc/X11/xorg.conf` 的 dummy driver。需要图形冒烟时可先运行 `Xorg :99 -config /etc/X11/xorg.conf -logfile /tmp/montage-xorg.log -noreset -nolisten tcp -ac`，再运行 `DISPLAY=:99 ./scripts/run-desktop-cloud.sh`，并实际执行新增/重置操作。此显示器只用于隔离云环境中的无敏感数据样例；不用作网络预览。完成后停止自己启动的应用/Xorg。

## 环境配置持久化

`install_script` 与 `start_skill` 已保存到云环境配置草稿，包含上述已执行的构建/测试、缓存/代理/CA 初始化与可选 Xorg/CMP 启动及界面检查步骤。本次已在新任务恢复现有 `rewrite/kmp` checkout，实际执行 Linux 桌面 UI 测试与 Android APK 构建，并通过连接的 GitHub 工具取回 CI 证据。没有再次修改配置草稿。此前启动的本地图形进程已停止，后续按启动说明重启。
