# Meeting native library and 16 KB checks

SH-16 replaces the legacy `libandroid-complex-math.so` in the shared host's
meeting dependency. The original was imported from Termux by the meeting SDK's
`android/package_native.py` / `prepare_app.py` pipeline. It has 4 KB PT_LOAD
alignment and an unnecessary Termux RUNPATH. Upgrading the Chaquopy plugin
alone would not replace that SDK JNI input.

## Build integration

`third_party/android-complex-math/` contains the unchanged Termux 0.2 NetBSD
sources pinned to `706caf5e5ec39084ec19dc4c4fb99282fca85434`, plus a host-owned
CMake recipe. All 24 imported files have Git blob and SHA256 checksums. Git
attributes preserve those exact bytes on Windows. The SDK checkout and its
original prebuilt remain unchanged.

The root applies `gradle/meeting-native.gradle` only when `:meeting-sdk` exists.
It stages the SDK JNI files without the old math library, and CMake supplies
the replacement using NDK 25.2.9519653 / CMake 3.22.1. Both page-size link
options are 16384. `--as-needed` keeps dependencies at Android libc/libm;
there is no Termux search path. The SDK's build outputs and CMake intermediates
are relocated into this host's ignored workspace, avoiding Windows Ninja's
failure on the dependency checkout's non-ASCII output path.

The shared package includes the original license and every source copyright
under `assets/notices/android-complex-math.txt`. The Compose baseline has no
new native dependency and can still build without the meeting checkout.

Both `shared-frontend/build.ps1` and `scripts/build-shared.sh` now fail unless:

1. Pinned math source bytes, the packaged 22-symbol ABI, the other 28 meeting
   JNI inputs, dependencies and notices pass `verify-meeting-native.py`.
2. The complete APK ELF audit passes, including Python extensions nested in
   Chaquopy archives.
3. Build-tools 36.0.0 `zipalign -c -P 16 4` passes. Build-tools 34's zipalign
   does not support `-P`; it remains the baseline's pinned build-tools version.

## Reproduce

Run the normal shared build with the local JDK/SDK/meeting checkout parameters.
It runs the static gates above. Standalone checks from the repository root:

```powershell
python -B shared-frontend/verify-meeting-native.py --sdk-root '<meeting checkout>' --ndk '<Android SDK>/ndk/25.2.9519653' --apk shared-app/build/outputs/apk/debug/shared-app-debug.apk
python -B shared-frontend/audit-apk.py shared-app/build/outputs/apk/debug/shared-app-debug.apk --runtime-root .arkui-x-validation/application/runtime --require-16kb
```

After building/installing the current shared APK, the Windows device check
compiles a separate ARM64 test executable, resolves all 22 symbols from the
installed library and checks their numerical behavior. The executable is
removed from `/data/local/tmp` afterward; it does not touch application data.

```powershell
./scripts/test-meeting-native.ps1 -AndroidSdk '<Android SDK>' -EvidenceName '<new-lowercase-name>'
```

The evidence records the actual kernel page size and installed library SHA256.
Existing shared UI/meeting regression remains `scripts/test-shared-device.ps1`.

## Runtime follow-up

SH-16 used an OPPO PKB110 with `PAGE_SIZE=4096`. Its Windows firmware query and
absence of an Android SDK AVD did not exhaust WSL's full-system emulation
capabilities. SH-17 subsequently booted an actual ARM64 16 KB Android kernel
inside QEMU TCG, with both compatibility controls disabled. The installed
library passed all 22 numerical checks, and the unchanged APK produced a real
public-audio transcript. This is an ARM64 guest, not ARM libraries translated
inside an x86 Android process. See [runtime setup and boundaries](ANDROID_16KB_RUNTIME.md).

For each runtime acceptance run, use a supported ARM64 16 KB environment,
record `adb shell getconf PAGE_SIZE` returning **16384**, and run startup,
numerical smoke, real public-audio meeting transcription and core UI regression
with page-size compatibility mode disabled. Static gates and a 4 KB phone
cannot replace this check. See the
[Android build and runtime guidance](https://developer.android.com/guide/practices/page-sizes).

Actual commands, results and failures are recorded in
`shared-frontend/verification-native-16kb.json` and `MIGRATION_STATUS.md`.
