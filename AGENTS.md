# ClawMate Android Migration

## Scope

This repository is the Android migration target for the HarmonyOS application at
`~/mobiinfra-oh` (`/home/yydh/mobiinfra-oh`). Upstream source repository:
https://github.com/doulujiyao12/mobiinfra-oh. The HarmonyOS source is
reference-only: do not modify files under `~/mobiinfra-oh`.

The target must be a real Android application, not a screenshot mock or a
minimal demo. Preserve the user-visible product flows and data contracts where
the Android platform can support them.

The objective is the full HarmonyOS ClawMate feature migration. Meeting/audio-note
integration and its dedicated runtime tests are the final phase, after the other
ClawMate features have been adapted. Preserve existing meeting code, but defer new
meeting feature work and exclude meeting tests from ordinary migration regression
until that final phase (unless the user reprioritizes). General chat ASR/TTS and
streaming voice remain part of the main migration. Disabled adapters are tracked
as pending, not counted as completed functionality.

## Working rules

- Approved workspaces are /home/yydh/ClawMate-Android and the current Windows
  repository C:\Users\Askeladd\Documents\ChatGPT\ClawMate_Android. The user
  authorized Windows work while the remote host was unavailable.
- Follow the user's accepted shared-frontend strategy: reuse ArkTS/ArkUI with
  ArkUI-X, and keep Android-specific adapters in Kotlin. The shared Android host
  is shared-app/ and the source preparation/overlays are shared-frontend/.
  Root Gradle settings manage both hosts. Put reusable Android system capability
  implementations in android-capabilities/, with no dependency on either app.
  Preserve app/ (the existing Compose product) as a regression baseline until a
  production switch and data migration have been separately verified.
- Keep commits small and reviewable. Every meaningful phase must end with a
  commit and a short note in MIGRATION_STATUS.md.
- Never claim a build, test, or runtime check passed unless the command actually
  ran and its output supports that claim.
- Do not copy secrets, signing keys, tokens, local databases, or device-specific
  state from the HarmonyOS project.
- Do not delete or overwrite target files without checking git diff first.
- Do not change the HarmonyOS source project.
- Keep generated build output, local SDK paths, and IDE state out of Git.

## Required first phase

Before implementing broad UI:

1. Inventory the source tree and identify the source of truth for each major
   feature.
2. Create a feature mapping in MIGRATION_STATUS.md covering:
   onboarding, home/recommendations, chat and agent loop, gallery/collection,
   data manager, workflows/tasks, settings, voice/avatar, local model, cloud
   model, HDC/GUI automation, Python bridge, and native C++/MNN.
3. Record which Android implementation strategy will be used for each feature.
4. Create a compiling Android skeleton with application id, navigation shell,
   theme, resources, and test source sets.
5. Add a .gitignore before importing generated files or dependencies.

## Product requirements

The migration should preserve these flows in priority order:

1. App launch, onboarding, home, navigation, and settings.
2. Chat sessions, streaming model replies, tool/agent status, persistence, and
   error states.
3. Gallery analysis, collections, recommendations, and local data storage.
4. Workflow/task browsing, execution state, and result display.
5. Cloud model configuration and local model capability boundaries.
6. Optional integrations: voice, digital avatar, HDC/GUI automation, Python
   services, and native inference.

For platform-specific features that cannot be ported directly, implement a
clear Android adapter or a controlled disabled state with an actionable error.
Do not silently remove a feature or pretend that a mock response is real.

## Networking and security

- Keep endpoints and credentials out of source control.
- Use Android-compatible HTTP/WebSocket clients and structured serialization.
- Preserve streaming, cancellation, retries, timeouts, and error handling where
  the source has them.
- Put environment-specific endpoints in a local configuration mechanism and
  provide a safe example configuration.
- Treat imported data and model output as untrusted input.

## Environment notes

- A real phone may be attached; `adb` is resolved by `scripts/android-env.sh`. Use it to
  install the debug APK and inspect the live app (logcat, screenshots, UI dumps)
  when a device is connected.
- Google's `android` CLI skill is installed at `~/.codex/skills/android-cli/`
  (project creation, device/emulator interaction, UI layout inspection,
  Android doc lookup). Consult its `references/` for device-interaction details.

## Build environment

For the shared frontend (ArkTS/ArkUI-X host), resolve its toolchain the same
environment-agnostic way instead of recording install paths:

```bash
scripts/arkui-env.sh --check     # ArkUI-X SDK, OpenHarmony SDK, hvigor, plus JDK/Android SDK
source scripts/arkui-env.sh
```

`scripts/arkui-env.sh` sources `scripts/android-env.sh`, so one `source` sets up
both stages. See `shared-frontend/README.md` for the build stages; on Windows use
`shared-frontend/build.ps1`, which takes the JDK and Android SDK paths as
parameters. Keep generated SDKs, upstream inputs and runtime libraries in the
ignored workspace. Do not replace the original application ID or copy its
private data as part of ordinary frontend checks.

The OpenHarmony mirror publishes Beta SDKs under some `*-Release` directories.
`scripts/arkui-env.sh --check` warns when `releaseType` is not `Release`. hvigor
6.x also requires components nested by API level (`<sdk>/20/ets`,
`<sdk>/20/toolchains`); a flat layout fails with "SDK management mode has
changed".

Gradle writes its wrapper distribution and dependency cache outside the
repository. Keep `GRADLE_USER_HOME` at its default (`$HOME/.gradle`) rather than
a project-local cache, and never commit generated Gradle state. Resolve the SDK
location with `scripts/android-env.sh`; do not copy absolute paths into
application source or configuration files.

Build the Compose baseline with:

```bash
source scripts/android-env.sh
./gradlew :app:assembleDebug
```

If a sandbox rejects wrapper or cache access, rerun the same command with
elevated permissions after confirming the resolved target paths.

### Pinned SDK components

Versions are owned by the project (`gradle/libs.versions.toml`,
`app/build.gradle.kts`) and verified by `scripts/android-env.sh --check`. Install
roots are machine-specific and intentionally not recorded.

| Component | Pinned version |
|-----------|----------------|
| JDK | 17 |
| Android platform | `platforms;android-34` |
| Build tools | `build-tools;34.0.0` |
| NDK | `25.2.9519653` |
| CMake | `3.22.1` |
| Gradle | 8.9 (wrapper) |
| ArkUI-X SDK | `6.0.0.103` (API 20) |
| OpenHarmony SDK | `6.0.0.47` (API 20, Release; source of truth: experiments/arkui-x/toolchain-lock.json) |
| hvigor | `6.22.7` + arkui-x plugin `4.22.7` |

## Verification

At each phase run the strongest checks available. At minimum use Gradle
configuration/build checks when the Android SDK is available, Kotlin/static
checks where available, and unit tests for storage, parsing, routing, and
workflow state. If the remote machine lacks Java, Android SDK, or Gradle,
record that blocker and still run repository-level checks that do not need
those tools.

Before asking for review, report:

- changed files and feature coverage;
- commands run and their actual results;
- known gaps and platform blockers;
- the commit hash for the phase.

## Collaboration protocol

- After each completed feature phase, update MIGRATION_BACKLOG.md and include
  the current completed/pending checklist in the reply to the user. Distinguish
  code integration from runtime/service acceptance and retain pending gaps.

### User manual maintenance

- Maintain the Chinese illustrated handbook at `docs/user-manual/` when changing
  user-visible flows, button labels, settings, permissions, or capability limits.
- Edit `content.json`, regenerate with `python docs/user-manual/build.py`, and run
  `python docs/user-manual/build.py --check`. Keep screenshot provenance and
  version differences current; use only reviewed, non-private UI captures.
- Document entry points, prerequisites, steps, completion indicators, recovery,
  and data destinations. Do not describe unavailable Android features as working.

The migration is reviewed in phases. Do not start a large unrelated refactor
while a previous phase has failing checks. Prefer an explicit TODO or adapter
over speculative infrastructure. Keep the source-to-target mapping current as
implementation decisions change.
