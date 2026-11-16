# Mika core

Source baseline: [wangzan101/Mika at 9fd0be4](https://github.com/wangzan101/Mika/tree/9fd0be4ab7aec2e1167af4bb8583728b78c384ec) (0.1.45).

`core/src/main` is the pinned Kotlin/JVM module source and resources. The Gradle
project file is adapted to the ClawMate dependency catalog. `LICENSE`, `NOTICE`
and the upstream third-party notices are included in the module resources.

The ClawMate build uses Kotlin 1.9.24, coroutines 1.8.1 and serialization 1.6.3.
Compatibility edits are marked in the source of `LocalPortScan.kt`,
`AppTools.kt`, `RequestConfirmation.kt`, `DeviceCode.kt` and `XaiAuth.kt`.
ClawMate also adds an optional per-host capability allowlist in
`TaskTools.kt` and `RunAssembler.kt`. The default retains the upstream assembly.

Upgrade history:

- `eb10b34` → `9fd0be4` (2026-11-17): 18 files under `core/src/main`, resources
  unchanged. The five compatibility edits and the allowlist above were not
  touched upstream, so they carry over unchanged. See `docs/MIKA_UPSTREAM_DIFF.md`.
