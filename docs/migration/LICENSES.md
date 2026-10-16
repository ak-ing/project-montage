# 许可证与外部依赖盘点

## 2026-10-16：新增媒体预览依赖

桌面最终采用 JavaCV/JavaCPP 1.5.14（所用类可选择 Apache-2.0 许可）和默认 FFmpeg 8.1.2 原生构建；不使用可选 `-gpl` 包。测试直接读取 `avcodec_license()` 和构建配置，当前 Linux 报告为 **LGPL version 3 or later**，未启用 `--enable-gpl` / `--enable-nonfree`；每端 CI 保留 `preview-runtime.json` 核对实际加载版本、许可证和配置。这不等于完整传递依赖审计。保留 [JavaCV 许可](https://github.com/bytedeco/javacv/blob/1.5.14/LICENSE.txt)、[JavaCPP 许可](https://github.com/bytedeco/javacpp/blob/1.5.14/LICENSE.txt)、[FFmpeg LGPLv3](https://github.com/FFmpeg/FFmpeg/blob/n8.1.2/COPYING.LGPLv3) 及 [对应预设构建源码](https://github.com/bytedeco/javacpp-presets/tree/1.5.14/ffmpeg)；发行包须提供所选二进制的声明、准确源码/补丁及动态替换或重链接材料。尚未交付完整安装包。

先前测试过的 OpenJFX 27 已从当前桌面依赖移除。Android MediaPlayer/MediaMetadataRetriever 与 iOS AVFoundation 为系统 API，没有复制原版 Python/FFmpeg/Torch 实现。八秒蓝/红视频与低音量正弦音轨为自行生成的测试数据，无第三方影片/音乐。用于制作测试片的命令行 FFmpeg 与运行时 JNI 解码依赖分别记录。

检查基线：`19de972cadefabfc18920a95b873b3cfb6f9a3a6`。本文件是源码与直接依赖盘点，不是完整 SBOM 或法律意见；实际发行前须对锁定的所有传递依赖、原生二进制、模型权重和素材逐项留档。

## 原工程

- 根 `LICENSE`：Apache License 2.0。保留 Google LLC 版权头、许可副本；分发修改版需显著标明修改并保留适用归属/NOTICE。迁移新增代码标记 `SPDX-License-Identifier: Apache-2.0`。本仓库未发现独立根 NOTICE。
- `CONTRIBUTING.md`：向 Google 上游贡献需遵循 Google CLA 与评审要求；私有分支工作不等于已经向上游提交。
- `third_party/fonts/consolidated/OFL.txt`：SIL Open Font License 1.1。保留 Noto 版权与许可，字体不得单独售卖，修改字体需遵守 Reserved Font Name 条件。新增加的各文字体系字体需要核实各自版权归属，不把一份拉丁字体 OFL 头当成所有来源已完整审计。

| 直接依赖 / 系统依赖 | 用途 | 通常许可 / 发行前核实 |
| --- | --- | --- |
| google-adk[all], google-genai, google-cloud-storage, Google auth | 编排、模型、GCS、身份 | Apache-2.0；`[all]` 引入大量传递依赖，按 lock 再审计 |
| mcp, FastAPI, Starlette, Uvicorn, HTTPX, python-dotenv, json-repair | MCP/HTTP/配置与数据修复 | 通常 MIT / BSD-3-Clause（Starlette、Uvicorn、HTTPX）；以具体包分发元数据为准 |
| asyncpg | 可选 PostgreSQL 会话 | Apache-2.0 |
| Pillow, NumPy, pydub, audioop-lts, PyOpenSSL | 图片、数组、音频、TLS | Pillow HPND，NumPy BSD-3-Clause，pydub MIT，audioop-lts PSF-2.0，PyOpenSSL Apache-2.0；需核实二进制附带组件 |
| torch, torchaudio, torchcodec | 强制对齐与音频处理 | BSD 风格；不能据此推断下载的模型权重许可 |
| torchaudio MMS_FA 模型权重 | 多语言语音强制对齐，Docker 预下载 | 权重/训练数据条款独立，需核实 checkpoint/model card 与商业使用限制；未完成核实前不将其打包进客户端 |
| FFmpeg/ffprobe, libass, fontconfig, codecs | 拼接、字幕、混音、编码 | FFmpeg 视构建为 LGPL/GPL，启用非自由组件可能不可再分发；保存实际 configure flags 和 codec 清单；libass ISC、fontconfig MIT 风格 |
| Tailwind、Iconify、Lucide、Three.js、Google Fonts/CDN 素材 | 原 Web UI | 多为 MIT/ISC/OFL，但图标集合与远程图片各自许可独立；不要直接复用未知 Supabase 演示图 |
| Python/uv 与开发工具 | 运行/构建 | PSF-2.0、uv MIT/Apache-2.0 等；开发依赖和最终分发依赖分开盘点 |

原版三份 `pyproject.toml` 与 `uv.lock` 原地保留，不为本次 KMP 骨架升级 Python 依赖。迁移文档中的清单版本范围以源码为准，实际版本来自各 lock；`requirements.txt` 并不自动等于 lock。

## 新骨架

| 组件 | 许可与处理 |
| --- | --- |
| Kotlin Toolchain / 官方 wrapper | Apache-2.0，保留 JetBrains 版权头和官方校验机制；工具链内部组件另有许可 |
| Kotlin / stdlib / kotlin-test / serialization | Apache-2.0，新增源码不复制完整上游模型实现 |
| Compose Multiplatform / Material3 / AndroidX Activity | Apache-2.0；Skiko/Skia 与打包 JRE 的许可证需随分发收集 |
| JDK | 依供应商发行版，OpenJDK 常见 GPL-2.0 with Classpath Exception；不要混用收费发行许可 |
| Apple SDK / Android SDK | 平台 SDK 条款独立；构建工具不会赋予 App Store/Google Play 发布资质 |

Google 模型服务的使用条款、生成内容权利、上传素材授权、云端计费与软件开源许可是不同事项。KMP 改写不改变这些条件。

发行门槛：解析实际依赖图与原生库 → 生成 SBOM/third-party notices → 确认字体及权重来源与许可 → 检查服务使用条款 → 分平台打包验证。此次没有宣称完成上述发行审计。
