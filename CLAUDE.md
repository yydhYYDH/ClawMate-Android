# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ClawMate Android is the Android migration of the HarmonyOS app "ClawMate" (upstream reference: `/home/yydh/mobiinfra-oh`, https://github.com/doulujiyao12/mobiinfra-oh). The HarmonyOS source is **reference-only — never modify files under `/home/yydh/mobiinfra-oh`**. Single Gradle module (`app`), Kotlin 1.9.24 + Jetpack Compose (Material3), compileSdk/targetSdk 34, minSdk 26.

**Core product principle — honest capability boundaries:** anything unsupported or unconfigured (cloud model, local MNN inference, HarmonyOS HiAI, GUI automation, etc.) must surface an explicit `NOT_CONFIGURED` / `DISABLED` / `BLOCKED` state. Never fabricate data or fake a success to make a feature look real. This rule recurs throughout the code (see `UnconfiguredCapabilities`, gate/policy classes, settings UI).

## Build & Test Commands

Builds need a JDK 17 and Android SDK on the machine, but install paths are **not**
recorded here. Resolve them with the helper scripts:

```bash
scripts/android-env.sh --check      # Compose baseline toolchain; explicit MISSING lines
source scripts/android-env.sh
./gradlew :app:assembleDebug --console=plain
```

For the shared frontend (ArkTS/ArkUI-X) use `scripts/arkui-env.sh`, which also
sources `android-env.sh`:

```bash
scripts/arkui-env.sh --check
source scripts/arkui-env.sh
```

Gradle writes its wrapper and caches outside the repo; keep `GRADLE_USER_HOME`
at `$HOME/.gradle` (the default) rather than a project-local cache.

- Run all unit tests: `source scripts/android-env.sh && ./gradlew :app:testDebugUnitTest --console=plain`
- Run a single test: `./gradlew :app:testDebugUnitTest --tests "com.clawmate.app.chat.agent.AgentLoopTest" --console=plain`
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Install on a device: `adb install -r app/build/outputs/apk/debug/app-debug.apk`, then `adb shell monkey -p com.clawmate.app 1`. Confirm `adb devices -l` shows an authorized device first. Device inspection: `adb logcat`, `adb exec-out screencap -p`, `adb shell uiautomator dump`.
- If a sandbox rejects wrapper/cache access, rerun with elevated permissions after confirming the resolved paths.
- Pinned versions (owned by `gradle/libs.versions.toml`, `app/build.gradle.kts`): JDK 17, platform `android-34`, build-tools `34.0.0`, NDK `25.2.9519653`, CMake `3.22.1`, Gradle 8.9; shared frontend adds ArkUI-X `6.0.0.103`, OpenHarmony SDK `6.0.0.48`, hvigor `6.22.7`.

Native code: `app/src/main/cpp` builds a JNI **stub** (`clawmate_local_model`) via CMake 3.22.1 / NDK 25.2.9519653 — it links no MNN runtime, it only reports honest capability boundaries.

## Configuration

- Cloud model endpoints/keys are **never in source**. Template: `app/src/main/res/raw/config_example.properties`. Local debugging: copy to `local_config.properties` (git-ignored) and fill `cloudPlannerBaseUrl` / `cloudPlannerApiKey` / `cloudPlannerModel`. Loaded via `config/EndpointConfig.kt` → `chat/cloud/ChatServerConfig`.
- Without configuration, chat shows an unconfigured state and sends no requests.
- Never commit API keys, signing files, local databases, or device state.

## Architecture

**Shell:** single-activity Compose app. `MainActivity` → `ui/ClawMateRoot.kt` (first-launch onboarding gate) → `MainTabScaffold` with a hand-rolled 5-tab bottom bar (首页 Home | 汇总 Summary | 聊天 Chat | 任务 Task | 设置 Settings). Tabs are **local saved state, not a Navigation graph** — deliberate, to keep broken optional features from taking down the shell. Don't introduce a NavHost casually.

**Manual DI:** no DI framework. Each feature has a top-level `object` "Component" container — `ChatComponent`, `GalleryComponent`, `SettingsComponent`, `TaskComponent`, `LogComponent` — that lazily wires its Room database, OkHttp client, and repositories (double-checked with `@Volatile`). These are the production construction seams; new real-capability wiring belongs here.

**Feature package layout** (under `app/src/main/java/com/clawmate/app/`): each feature is layered as
- `data/` — Room entities/DAOs, repositories, mappers, DataStore-backed stores
- `domain/` — pure logic (no Android imports where possible; this is what gets unit-tested)
- `ui/` — ViewModel (+ `XxxViewModelFactory` for constructor injection) + Compose `Screen`
- plus adapter sub-packages for platform capabilities (e.g. `gallery/ocr`, `gallery/geocode`, `chat/voice/asr|tts`, `task/hdc`)

Key packages: `chat/` (OpenAI-compatible SSE streaming client, agent loop in `chat/agent/AgentLoop.kt` + `ToolRegistry`, Room persistence), `gallery/` (Room photo store, Photo Picker + MediaStore, ML Kit Chinese OCR, geocoding, collections, monthly insights), `home/` (onboarding, profile, recommendations), `task/` (Workflow JSON parser/state machine/runner, HDC bridge client for PC-side device control), `settings/` (DataStore settings, `AppSettings` snapshot), `datamanager/` (app-private file-based daily logs, consolidation/index/profile pipelines under `filesDir`, JobScheduler-triggered periodic consolidation), `accessibility/` (opt-in AccessibilityService executing a strict typed action allowlist), `overlay/` (lifecycle-safe overlay scaffold), `localmodel/` (JNI stub + capability inventory), `profile/` (avatar, NFC "profile knock" share, `.mobi-profile` import/export), `log/` (in-app ring-buffer log viewer with sensitive-value redaction), `wechat/` (collection gate), `config/`, `ui/theme/` (shared design tokens).

**Persistence:** Room (gallery, chat, tasks; schemas exported to `app/schemas/` — bump version + export when changing entities), DataStore preferences (settings, onboarding, profile).

**Networking:** OkHttp + Retrofit + kotlinx.serialization. Every client must have timeouts, cancellation, and error mapping.

## Testing Conventions

- Unit tests are **pure JVM** (JUnit4, MockWebServer, kotlinx-coroutines-test; no Robolectric). Test packages mirror `main` packages 1:1 under `app/src/test/`. Domain logic and gate/policy classes are expected to have tests.
- `Fake*Repository`/`Fake*Provider` classes live in `main` (not test sources) as injectable fakes; tests inject them through the Component/ViewModel seams.
- A `ThemeContractTest` / `DestinationTest` guards the shell contract.

## Working Rules (from AGENTS.md)

- Work only in `/home/yydh/ClawMate-Android`. Keep commits small; every meaningful phase ends with a commit + a short entry in `MIGRATION_STATUS.md` (the large phase/feature-mapping log — append, don't rewrite history).
- Never claim a build/test/runtime check passed unless the command actually ran and its output supports it.
- Prefer an explicit adapter or controlled disabled state over speculative infrastructure; don't silently drop a feature.
- Check `git diff` before deleting/overwriting target files.

## UI Conventions

- Shared design tokens live in `ui/theme/DesignTokens.kt` and theme files. Settings keeps HarmonyOS-matched metrics (page bg `#F4F8FF`, primary `#4F68FF`, purple `#765BFF`, primary text `#11182F`, card radius 18dp, control radius 12dp).
- Reuse `ClawMateHeader`, `ClawMateDesign`-based components, and existing Compose components — don't redefine colors/radii/spacing per page.
