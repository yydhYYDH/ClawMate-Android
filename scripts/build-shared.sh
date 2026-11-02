#!/usr/bin/env bash
# Build the shared frontend with the same checked bootstrap/prepare/seal stages as Windows.
# Usage: scripts/build-shared.sh [--upstream DIR] [--node FILE] [--skip-gradle] [--offline]
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
project="$repo/.arkui-x-validation/application/project"
runtime="$repo/.arkui-x-validation/application/runtime"
upstream=""
build_node="${BUILD_NODE:-}"
skip_gradle=0
offline=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        --upstream) upstream=${2:?--upstream needs a path}; shift 2 ;;
        --node) build_node=${2:?--node needs a Node executable}; shift 2 ;;
        --skip-gradle) skip_gradle=1; shift ;;
        --offline) offline=1; shift ;;
        -h|--help) sed -n "2,3p" "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
. "$repo/scripts/arkui-env.sh"
build_node="${build_node:-$(command -v node)}"
for name in ARKUIX_SDK_HOME OpenHarmony_HOME ARKUI_BUILD_TOOLS JAVA_HOME ANDROID_HOME; do
    [ -n "${!name:-}" ] || { echo "missing $name; run scripts/arkui-env.sh --check" >&2; exit 1; }
done
bootstrap=(--check-toolchain --arkuix-sdk "$ARKUIX_SDK_HOME" --openharmony-sdk "$OpenHarmony_HOME"
           --build-tools "$ARKUI_BUILD_TOOLS" --build-node "$build_node")
[ -z "$upstream" ] || bootstrap+=(--source-root "$upstream")
cd "$repo"
"$build_node" shared-frontend/bootstrap.mjs "${bootstrap[@]}"
"$build_node" shared-frontend/prepare.mjs
(
    cd "$project"
    "$build_node" "$ARKUI_BUILD_TOOLS/node_modules/@ohos/hvigor/bin/hvigor.js" --mode module \
        -p module=entry@default -p product=default -p buildMode=debug assembleHap --no-daemon --no-parallel
)
python3 experiments/arkui-x/package-android.py --project "$project" --sdk "$ARKUIX_SDK_HOME/20/arkui-x" --android-app "$runtime"
"$build_node" shared-frontend/build-state.mjs seal
if [ "$skip_gradle" -eq 0 ]; then
    gradle_args=(:shared-app:assembleDebug --no-daemon --console=plain)
    [ "$offline" -eq 0 ] || gradle_args+=(--offline)
    GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}" ./gradlew "${gradle_args[@]}"
    apk="$repo/shared-app/build/outputs/apk/debug/shared-app-debug.apk"
    python3 -B shared-frontend/verify-meeting-native.py --sdk-root "${MEETING_SDK_ROOT:?Set MEETING_SDK_ROOT to the meeting checkout}" \
        --ndk "$ANDROID_HOME/ndk/25.2.9519653" --apk "$apk"
    python3 -B shared-frontend/audit-apk.py "$apk" --runtime-root "$runtime" \
        --output "$repo/.arkui-x-validation/application/apk-native-audit.json" --require-16kb
    "$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 4 "$apk"
fi
