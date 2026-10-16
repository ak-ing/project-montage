# Project Montage migration instructions

## Scope and source of truth

- Work in the existing checkout on `rewrite/kmp`. Cloud tasks are already isolated; do not create a worktree unless explicitly requested. Inspect `git status` before switching or editing; preserve user changes.
- `origin` is `https://github.com/ak-ing/project-montage-private.git`; `upstream` is `https://github.com/google/project-montage.git`. Baseline: `19de972cadefabfc18920a95b873b3cfb6f9a3a6` (2026-10-13 inspection).
- Keep `mcp_client/`, `mcp_montage/`, `sign_server/`, `deploy/`, `third_party/` and their licenses as the runnable/reference upstream implementation. Do not move, delete, simplify or rewrite them during scaffold work.
- The current milestone is analysis plus a minimal KMP/CMP skeleton in `kmp/`. Every capability in `docs/migration/PLAN.md` remains in final scope. Unimplemented is not removed.
- Follow the user's Kotlin Toolchain requirement: use the pinned official `kotlin` / `kotlin.bat` wrapper and YAML modules. Do not add Gradle scripts or a Gradle wrapper. Toolchain currently uses AGP/Gradle internally for Android; document that upstream limitation, do not hide it or implement a second build.

## Architecture

- `core`: platform-independent draft models, state transitions, contracts, later wire DTOs/use cases. No Android, Apple, Java desktop, cloud SDK, filesystem or UI imports.
- `shared-ui`: Compose Multiplatform UI shared by all apps. Inject platform services through interfaces; label sample/local-only behavior accurately.
- `desktop-app`: Windows/macOS JVM entry point and eventual desktop adapters. `android-app`: Activity and Android adapters. `ios-app`: Swift host, Compose controller and Apple adapters.
- The user requires a device-run product with no self-hosted backend. Run orchestration, local project persistence, media processing and export on Windows/macOS/Android/iOS. Devices may call external model APIs directly; inference at the provider is allowed and is not a requirement for fully offline inference.
- Port upstream ADK/MCP behavior into device use cases and provider/platform adapters; do not require Python services, Cloud Run, Cloud SQL, GCS buckets or a signing server to use the KMP app. Preserve upstream service code as reference.
- Never ship shared API keys, ADC, service-account JSON or signing credentials. Use provider-supported native-app authentication or user-supplied credentials kept in platform secure storage. Verify each provider's direct-client API/auth support; record unsupported capabilities rather than silently adding a backend.
- Execute composition, mixing, subtitles and encoding through device media adapters. Desktop FFmpeg and mobile native/library integrations need capability, performance and license validation; do not assume mobile Python/subprocess support. Bound concurrency and support cancellation/recovery within device resources.
- Shared logic must not assume a local file path for a picked image. Use opaque handles and explicit platform adapters.
- Preserve product behavior including session forks, all 13 MCP tool capabilities, buffered transitions, optional audio/subtitles, multilingual rendering and both orchestration profiles. Server deployment/auth/storage mechanisms are reference implementations, not mandatory target infrastructure. See the feature matrix before changing scope.

## Versions, validation and licensing

- Use official latest stable releases, pinned explicitly. Toolchain itself is officially Alpha; pin latest published Toolchain release. Never use dynamic `+`/`latest` dependencies or silently downgrade to fix a build.
- Shared versions live in `kmp/common.module-template.yaml` and `kmp/compose.module-template.yaml`; dependency versions are in the relevant module YAML. Record metadata sources and compatibility exceptions in `docs/migration/TOOLCHAIN.md`.
- Run commands from `kmp/`: `./kotlin build -m desktop-app`, `./kotlin test --platform jvm`, and `./kotlin build -m android-app`. Windows uses `kotlin.bat`. Read `kmp/README.md` and `docs/migration/VALIDATION.md` first.
- Test meaningful domain behavior in `core/test/`; check actual test counts. Desktop JVM compilation on Linux does not validate Windows/macOS UI or installers. iOS needs macOS/Xcode. Distinguish passed, failed, blocked and unrun checks.
- Keep `.github/workflows/kmp.yml` aligned with the wrapper and module names. See `docs/migration/CI.md`; validate workflow edits with actionlint and retain the executed-test guard. Do not report configured remote jobs as passed without an actual run.
- Keep TLS and artifact checksum verification enabled. Inspect only presence/names of credentials, never values. Do not invoke paid generation/deployment as a scaffold test.
- Preserve Apache-2.0 and Google notices, upstream third-party licenses, and JetBrains wrapper notices. Fonts are OFL; native codecs and model weights need separate distribution review. Do not call the complete transitive dependency graph license-audited without an actual audit.
