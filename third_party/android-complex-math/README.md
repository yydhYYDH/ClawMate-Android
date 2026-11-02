# Meeting SDK complex math dependency

The meeting SDK ships a Termux `libandroid-complex-math.so` with 4 KB ELF load
alignment. This directory pins the BSD-licensed NetBSD implementation used by
Termux 0.2, including its headers, license and original recipe. Imported files
are unchanged; `source-lock.json` records their upstream Git blobs and SHA256.

`gradle/meeting-native.gradle` applies only to the optional meeting SDK project.
It stages its JNI inputs without the old prebuilt and builds this replacement
using the project's NDK 25.2.9519653 / CMake 3.22.1. Both ELF page-size linker
options are explicitly 16384; CMake checks pinned sources on configuration and
again whenever those inputs change. No Gradle cache or dependency checkout
source is patched. Other native libraries are retained byte for byte.

The SDK's generated outputs are relocated to the host's ignored `build/` and
`.cxx/` directories, avoiding Ninja's non-ASCII Windows path failure.
The replacement is ARM64, matching the existing shared application.

The original license and per-source notices ship in
`assets/notices/android-complex-math.txt`. Numerical smoke checks and ABI export
comparison accompany the APK's full ELF audit; static alignment alone is not
proof of operation on a 16 KB kernel.

Sources:
- [Pinned Termux package](https://github.com/termux/termux-packages/tree/706caf5e5ec39084ec19dc4c4fb99282fca85434/packages/libandroid-complex-math)
- [Android page-size build and runtime checks](https://developer.android.com/guide/practices/page-sizes)
