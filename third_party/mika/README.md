# Mika core

Source baseline: [wangzan101/Mika at eb10b34](https://github.com/wangzan101/Mika/tree/eb10b349f86654125e0db5325714e410b5431054).

`core/src/main` is the pinned Kotlin/JVM module source and resources. The Gradle
project file is adapted to the ClawMate dependency catalog. `LICENSE`, `NOTICE`
and the upstream third-party notices are included in the module resources.

The ClawMate build uses Kotlin 1.9.24, coroutines 1.8.1 and serialization 1.6.3.
Compatibility edits are marked in the source of `LocalPortScan.kt`,
`AppTools.kt`, `RequestConfirmation.kt`, `DeviceCode.kt` and `XaiAuth.kt`.
ClawMate also adds an optional per-host capability allowlist in
`TaskTools.kt` and `RunAssembler.kt`. The default retains the upstream assembly.
