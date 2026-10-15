# 验证记录

用户时区 America/Tijuana：2026-10-13。执行主机记录为 2026-10-14 UTC。Linux x86_64，4 CPU 配额、32 GiB 内存。此记录针对当前实例和此次骨架，不代表已经推送、发布环境或在新任务/四端真机验证。

## 已通过

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

## 产物与证据

- APK：`kmp/build/tasks/_android-app_buildAndroidDebug/gradle-project-debug.apk`（约 12 MB）。
- APK SHA-256：`52b9bbe2f99a0b183eae67c0d07355053d897de8890ada2bbe7192c20fbc3714`。
- 桌面 JAR：`kmp/build/tasks/_desktop-app_jarJvm/desktop-app-jvm.jar`，不是自包含安装包；使用 Kotlin CLI run 启动。
- 测试 XML：`kmp/build/reports/core/{jvm,android}/TEST-junit-jupiter.xml`。
- [新增场景的实际运行截图](evidence/desktop-add-scene.png)。
- 构建任务日志：`kmp/build/logs/`；依赖图已用 `./kotlin show dependencies -m desktop-app` 检查，解析的 Kotlin stdlib 为 2.4.21。

构建产物/日志被忽略，不纳入源码提交。保留截图作为界面冒烟证据；它不证明原生平台集成或完整视频生成。

## 已诊断并解决的初次失败

1. 平台初始只 fetch main，自动跟踪 rewrite/kmp 失败：补充对应 fetch refspec，正常切换，未覆盖用户文件。
2. 官方安装与 JetBrains CDN 403：保存域名配置，用户应用后重试成功，未绕过代理。
3. Toolchain 不允许 `settings@android.jvm.release`：共享字节码统一 17，桌面宿主覆盖为 21。
4. 默认 Android SDK/Skiko 缓存写入只读 HOME：通过支持的环境变量/`skiko.data.path` 指向 `/workspace/.tooling`。
5. Google Maven 别名域名不在初始策略中：按官方 Toolchain 文档将 `mavenGoogle` 配置到 Google 自己的 `https://dl.google.com/dl/android/maven2/`。
6. SDK 许可首次未接受：通过 Toolchain 的交互许可流程完成；随后非交互构建成功。当前新版 sdkmanager 提示 `--licenses` 已无作用，不能把该命令的 exit 0 当成 Toolchain 许可已接受。
7. 虚拟显示器缺少 GL context：初始渲染自动降级；云端 helper 显式使用 SOFTWARE 并配置 XDG/Skiko 缓存，随后启动日志为空。没有测量 GPU 性能。

## 未验证 / 不在本次实现内

- **远端 CI（10 月 15 日补充）**：已提交并推送。修复 iOS Swift 类型重名后，运行 38729383572 的 Windows/macOS 桌面编译和测试、Android APK 和 host 测试、iOS 应用编译和模拟器 core 测试全部成功。证据与限制见 [CI.md](CI.md)。本记录上方的 Linux 实测结果保留。

- **Windows/macOS 原生运行与分发**：已通过 Windows x64/macOS Arm64 CI 编译与 core 测试，但没有验证桌面 UI 启动；Windows x64、macOS x64/Arm64 的 Skiko 运行、菜单/键盘/权限、MSI/DMG、签名、公证需对应主机。
- **iOS 编译/链接/运行/签名**：Linux 没有 Xcode/Apple SDK；远端 macOS CI 已通过 Swift/Kotlin 应用编译和 core 模拟器测试，但未验证应用 UI 启动或签名；首次 Mac 构建生成的 Xcode 工程、bundle ID、最低 iOS 版本和 Team 需检查。不可据 JVM 成功推断 iOS 已通过。
- **Android 设备 UI**：APK 生成成功，但未安装到模拟器/真机，未做 instrumentation、旋转、权限或生命周期测试。
- **云端全流程**：ADK/MCP 实际连接、身份验证、Google Search、Gemini/Veo/Omni/Lyria/TTS、GCS 与签名下载、FFmpeg/字幕/对齐均未运行。骨架没有伪造成功响应。
- **原版运行时测试**：未安装全部 Python/模型权重或启动三服务；当前仓库没有 README 所称 tests/ 套件。环境中存在 GOOGLE_APPLICATION_CREDENTIALS 绑定，但没有检查/泄露其值，也没有声称 GCP 授权可用。
- **功能迁移**：仅内存草稿预览可用；持久化、上传、播放、分享、登录、聊天/任务/生成均是明确后续任务，不是最终范围删减。
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

`install_script` 与 `start_skill` 已保存到云环境配置草稿，包含上述已执行的构建/测试、缓存/代理/CA 初始化与可选 Xorg/CMP 启动及界面检查步骤。网络域名配置也已保存并验证实际下载成功。保存草稿不代表发布环境；尚未执行新任务恢复验证。当前图形冒烟进程已停止，后续按启动说明重启。
