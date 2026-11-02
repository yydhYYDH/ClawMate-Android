# ARM64 16 KB runtime acceptance

SH-17 runs the unchanged SH-16 shared APK inside a full-system ARM64 Android
guest with a real 16 KB kernel. This is QEMU TCG on an x86-64 WSL host, not an
x86 Android guest translating ARM libraries. It establishes runtime compatibility
for this environment, not physical-device performance or production approval.

## Pinned public inputs

`experiments/android-16kb/runtime-lock.json` records the official Android CI
artifact URLs, build IDs, sizes and SHA256 values. Keep downloaded images,
host tools, kernels, guest data and generated logs in ignored storage.
No signing keys, source-device databases or private recordings are needed.

The image/host pair is build `16644040`, target
`aosp_cf_arm64_only_phone-userdebug` (Android 17 / API 37). The kernel and matching
module initramfs are build `16733175`, target `kernel_virt_aarch64_16k`, with
`CONFIG_ARM64_16K_PAGES=y`. Follow the
[official Cuttlefish kernel override instructions](https://source.android.com/docs/core/architecture/16kb-page-size/getting-started-cf-arm64-pgagnostic).

Use the matching Cuttlefish host tools and a short Linux ext4 working directory;
the Windows-mounted image directory was too slow and long Unix socket paths
failed. The test used 4 CPUs, 4096 MB RAM, QEMU `qemu_cli`,
`gpu_mode=guest_swiftshader`, no WebRTC, host audio or host Bluetooth, and an
isolated guest with no external network. ADB used its vsock transport.

The custom kernel panicked in optional Gunyah hypervisor detection under TCG.
For this environment only, the boot arguments excluded both initcalls:
`initcall_blacklist=gunyah_init,gunyah_rm_init`. This does not change the app or
the ARM64 page size, but this kernel/TCG workaround is part of the test boundary.
The first isolated guest boot entered recovery after user-0 initialization
failed; only this fresh, empty guest was wiped, then it booted normally.

## Run the checks

Select the emulator explicitly when a phone is also attached. Check
`getprop sys.boot_completed` is `1` and dismiss the empty emulator's keyguard.
Disable both compatibility controls **before installing** the APK, as described
in the [Android page-size guide](https://developer.android.com/guide/practices/page-sizes):

```powershell
adb -s '<serial>' root
adb -s '<serial>' shell setprop bionic.linker.16kb.app_compat.enabled false
adb -s '<serial>' shell setprop pm.16kb.app_compat.disabled true
adb -s '<serial>' shell getconf PAGE_SIZE
adb -s '<serial>' shell uname -m
adb -s '<serial>' install --no-streaming -r shared-app/build/outputs/apk/debug/shared-app-debug.apk
./scripts/test-meeting-native.ps1 -AndroidSdk '<Android SDK>' -Serial '<serial>' -Require16Kb -EvidenceName '<new-native-name>'
./scripts/test-shared-device.ps1 -Adb '<adb.exe>' -Serial '<serial>' -Require16Kb -TimeoutMultiplier 5 -EvidenceName '<new-ui-name>'
```

Both drivers record the selected serial, kernel page size and architecture, and
the two compatibility controls. `-Require16Kb` refuses a different environment
before compilation/upload or APK installation. These volatile properties must
be rechecked after a reboot. The numerical driver also verifies the installed
library against the APK and removes its standalone executable afterward.

`TimeoutMultiplier` defaults to 1 and accepts only 1–5. It sets the
`deviceTimeoutMultiplier` instrumentation argument. The TCG run uses 5 for
the meeting fixture's 90-second engine-start/transcription deadlines and the
UI fixtures' 15-second OCR waits. The product APK, SDK timeouts, UI actions and
result/provenance assertions remain unchanged. The first two default-budget
meeting attempts failed while Python was still importing modules; the first
service reached ready about two seconds after the assertion failed. Chat also
exceeded its original OCR wait on this emulator.

With the longer meeting budget, the server started and accepted the public
audio, but a subsequent read failed with a connection reset during model load.
The slow-mode fixture now retries only the read-only meeting-status query, at
most five times and within the transcription deadline, cancelling each failed
request. Upload/create/update/import operations are never replayed. Default
mode retains one attempt and its original 15-second per-read wait. Record these
test-only allowances when interpreting the results; they do not certify
production latency or transport resilience under load.

Slow-mode chat/workflow navigation uses the current visible accessibility node
for the stable main tab labels; OCR had failed to locate those labels after
recreation on this 720-pixel guest. Meeting buttons first use fresh virtual
nodes, then OCR if absent. Before tapping an OCR-only meeting button, the
fixture compares its text region with a new screenshot and rejects coordinates
if the page moved during recognition. Receipt verification uses the existing
`files/workflows/meeting_imports` contract with a real deadline instead of
repeatedly walking Python's entire extracted module tree.

On a fresh fixture, complete the real four-page onboarding before the meeting
test. Prepare only the public model/audio inputs listed in the runtime lock,
checking their hashes. The meeting regression expects the extracted files under
the test app's `files/meeting/models`; maintain its UID and SELinux labels when
provisioning a root-enabled emulator. Do not weaken SELinux globally or import
another app's private data. Tests restore their preference/index fixtures;
the public meeting sample follows the existing reversible-trash policy.

## Results and limits

Exact commands, results, artifact identities and raw-evidence hashes are recorded
in `shared-frontend/verification-16kb-runtime.json` and `MIGRATION_STATUS.md`.
The numerical smoke passed all 22 exports. Six native method checks and the
final independent meeting test passed; the latter took 313.094 seconds and
verified real public-audio transcription, page save, immutable provenance,
duplicate suppression and service availability after Activity destruction.
Final chat and workflow checks failed: chat OCR timed out with an additional
ActivityScenario destruction timeout; workflow reached the cancel action but
its failed-run message did not contain the expected cancellation reason. Its
actual message was not logged, so further attribution is not justified. The
phase has **7 passed / 2 failed** distinct device checks, not a passing full
nine-test run. Capture the missing failure detail and thread stacks before
changing product behavior or extending waits again.

The app was stopped, fixture backups were absent after restoration, the native
smoke executable was absent, and the official `stop_cvd` successfully stopped
this guest. Public inputs and emulator state remain ignored for later reuse.
The earlier SH-16 Windows firmware query did not establish WSL capability:
WSL `/dev/kvm` and the official Cuttlefish host tools were available, and full
ARM64 system emulation supplied the missing runtime environment.

This isolated guest cannot certify real cloud-service quality, microphone or
camera capture, vendor background policies, TalkBack, release signing or old
data migration. Physical 16 KB devices and additional Android/vendor versions
remain separate acceptance targets. The existing Compose application and its
production package/data are unchanged.

Two earlier combined instrumentation runs crashed the separate
`com.clawmate.shared.test` helper process in ART's `MarkCompact` collector
(SIGSEGV, then `Unexpected class flags: 0 for java.lang.Class<byte>`). The saved
tombstone's mappings did not contain the app's ArkUI-X/Python/ONNX libraries;
this narrows the observation but does not establish a root cause. Preserve
these failures and validate stability on another supported environment or a
physical 16 KB device. Successful individual feature checks do not erase them.
