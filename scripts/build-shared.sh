#!/usr/bin/env bash
# Build the ArkTS/ArkUI-X shared frontend into an Android APK, from a clean tree.
#
# Usage:
#   scripts/build-shared.sh                 # full pipeline
#   scripts/build-shared.sh --upstream DIR  # also restage from a HarmonyOS source checkout
#   scripts/build-shared.sh --skip-gradle   # stop after the HAP is produced
#
# Stages: wait for reference input -> prepare.mjs -> patch generated project ->
#         hvigor assembleHap -> package-android.py -> Gradle :shared-app:assembleDebug
#
# Every patch below fixes a concrete mismatch between what prepare.mjs emits and
# what hvigor 6.22.7 / the Release SDK accepts. Each is idempotent, so running the
# script repeatedly (or after prepare regenerates the project) is safe.

set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
scratch="$repo/.arkui-x-validation"
project="$scratch/application/project"
runtime="$scratch/application/runtime"
reference="$scratch/reference"
upstream=""

SKIP_GRADLE=0
while [ $# -gt 0 ]; do
    case "$1" in
        --upstream) upstream=${2:?--upstream needs a path}; shift 2 ;;
        --skip-gradle) SKIP_GRADLE=1; shift ;;
        -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

log() { printf '\n==> %s\n' "$*"; }

# --- 0. toolchain ------------------------------------------------------------
log "Resolving toolchain"
# shellcheck source=./arkui-env.sh
. "$repo/scripts/arkui-env.sh"
for v in ARKUIX_SDK_HOME OpenHarmony_HOME ARKUI_BUILD_TOOLS JAVA_HOME ANDROID_HOME; do
    [ -n "${!v:-}" ] || { echo "missing $v; run scripts/arkui-env.sh --check" >&2; exit 1; }
done
hvigor_js="$ARKUI_BUILD_TOOLS/node_modules/@ohos/hvigor/bin/hvigor.js"
[ -f "$hvigor_js" ] || { echo "hvigor not found at $hvigor_js" >&2; exit 1; }

# --- 1. reference source -----------------------------------------------------
if [ -n "$upstream" ]; then
    log "Staging upstream source from $upstream"
    ref=$(python3 -c "import json;print(json.load(open('$repo/shared-frontend/upstream-lock.json'))['ref'])")
    actual=$(git -C "$upstream" rev-parse HEAD 2>/dev/null || true)
    if [ "$actual" != "$ref" ]; then
        echo "upstream HEAD ($actual) does not match upstream-lock.json ($ref)" >&2
        exit 1
    fi
    rm -rf "$reference/entry/src/main/ets" "$reference/entry/src/main/resources"
    mkdir -p "$reference/entry/src/main"
    cp -r "$upstream/entry/src/main/ets" "$reference/entry/src/main/ets"
    cp -r "$upstream/entry/src/main/resources" "$reference/entry/src/main/resources"
fi
[ -d "$reference/entry/src/main/ets" ] || {
    echo "reference source missing; pass --upstream <harmonyos checkout>" >&2; exit 1; }

# prepare.mjs expects the SDKs under the scratch dir; keep them pointing at ~/env.
ln -sfn "$OpenHarmony_HOME" "$scratch/openharmony-sdk"
mkdir -p "$scratch/sdk"
ln -sfn "$ARKUIX_SDK_HOME" "$scratch/sdk-link"
[ -e "$scratch/sdk/arkui-x" ] || ln -sfn "$ARKUIX_SDK_HOME/20/arkui-x" "$scratch/sdk/arkui-x"

# --- 2. generate project -----------------------------------------------------
log "Generating shared ArkTS project"
( cd "$repo" && node shared-frontend/prepare.mjs )

# prepare.mjs symlinks node_modules to <scratch>/build-tools/node_modules, which
# no longer exists now that the toolchain lives outside the repo. Repoint it at
# the installed hvigor so hvigorfile.ts can resolve its plugins.
rm -f "$project/node_modules"
ln -s "$ARKUI_BUILD_TOOLS/node_modules" "$project/node_modules"

# --- 3. patch the generated project -----------------------------------------
log "Applying hvigor/Release-SDK compatibility patches"
python3 - "$project" <<'PY'
import json, pathlib, re, shutil, sys

project = pathlib.Path(sys.argv[1])
MODEL = "6.0.0"
notes = []

# (a) hvigor-config: 6.x replaces hvigorVersion with modelVersion and rejects
#     unknown keys, so drop hvigorVersion and pin the plugin versions we install.
cfg = project / "hvigor/hvigor-config.json5"
s = cfg.read_text()
s = re.sub(r'\s*"hvigorVersion":\s*"[^"]*",', '', s)
s = s.replace('"@ohos/hvigor-ohos-plugin": "4.1.2"', '"@ohos/hvigor-ohos-plugin": "6.22.7"')
s = s.replace('"@ohos/hvigor-ohos-arkui-x-plugin": "3.1.1"', '"@ohos/hvigor-ohos-arkui-x-plugin": "4.22.7"')
if '"modelVersion"' not in s:
    s = s.replace('{', '{\n  "modelVersion": "%s",' % MODEL, 1)
cfg.write_text(s)
notes.append("hvigor-config: modelVersion=%s, plugins pinned" % MODEL)

# (b) oh-package.json5 also needs modelVersion, and it must match (a).
pkg = project / "oh-package.json5"
s = pkg.read_text()
if '"modelVersion"' not in s:
    s = s.replace('{', '{\n  "modelVersion": "%s",' % MODEL, 1)
    pkg.write_text(s)
    notes.append("oh-package: modelVersion=%s" % MODEL)

# (c) The empty project template names the module "module_name"; hvigor requires
#     it to match build-profile.json5.
mod = project / "entry/src/main/module.json5"
s = mod.read_text()
if '"module_name"' in s:
    mod.write_text(s.replace('"module_name"', '"entry"', 1))
    notes.append("module.json5: module_name -> entry")

# (d) prepare.mjs only copies the template resources; the upstream element
#     definitions (colors, strings, floats) must be merged in or the ArkTS
#     compiler reports hundreds of "Unknown resource name" errors.
res = project / "entry/src/main/resources"
up = project.parent.parent / "reference/entry/src/main/resources"
if up.is_dir():
    for sub in ("base", "dark"):
        if (up / sub).is_dir():
            shutil.copytree(up / sub, res / sub, dirs_exist_ok=True)
    if (up / "rawfile").is_dir():
        shutil.copytree(up / "rawfile", res / "rawfile", dirs_exist_ok=True)
    notes.append("resources: merged upstream base/dark/rawfile")

# (e) main_pages.json lists HarmonyOS-only pages that reference APIs absent from
#     ArkUI-X (@kit.UIDesignKit, HDC input test). They are also not staged by
#     prepare.mjs, so the page list has to drop them.
pages_file = res / "base/profile/main_pages.json"
ETS_ONLY = {"pages/EdgeFlowWindow", "pages/FloatWindow", "pages/settings/SelfHdcInputTestPage"}
if pages_file.is_file():
    data = json.loads(pages_file.read_text())
    kept = [p for p in data["src"] if p not in ETS_ONLY]
    dropped = len(data["src"]) - len(kept)
    if dropped:
        data["src"] = kept
        pages_file.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
        notes.append("main_pages: dropped %d HarmonyOS-only page(s)" % dropped)
for p in ("entry/src/main/ets/pages/EdgeFlowWindow.ets",
          "entry/src/main/ets/pages/FloatWindow.ets",
          "entry/src/main/ets/pages/settings/SelfHdcInputTestPage.ets"):
    f = project / p
    if f.is_file():
        f.unlink()
for n in notes:
    print("   -", n)
PY

# --- 4. compile ArkTS --------------------------------------------------------
log "Compiling ArkTS (hvigor assembleHap)"
( cd "$project" && node "$hvigor_js" --mode module \
    -p module=entry@default -p product=default -p buildMode=debug assembleHap \
    --no-daemon --no-parallel )
hap="$project/entry/build/default/outputs/default/entry-default-unsigned.hap"
[ -f "$hap" ] || { echo "HAP not produced at $hap" >&2; exit 1; }

# --- 5. export runtime -------------------------------------------------------
log "Packaging ArkUI-X runtime"
python3 "$repo/experiments/arkui-x/package-android.py" \
    --project "$project" --sdk "$ARKUIX_SDK_HOME/20/arkui-x" --android-app "$runtime"
[ -f "$runtime/src/main/assets/arkui-x/entry/ets/modules.abc" ] || {
    echo "modules.abc missing; runtime export failed" >&2; exit 1; }

# --- 6. Android APK ----------------------------------------------------------
if [ "$SKIP_GRADLE" -eq 1 ]; then
    log "Skipping Gradle (:shared-app) as requested"
    exit 0
fi
log "Building :shared-app:assembleDebug"
cd_ok=$( cd "$repo" && GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}" \
    ./gradlew :shared-app:assembleDebug --console=plain )
printf '%s\n' "$cd_ok" | tail -5
apk="$repo/shared-app/build/outputs/apk/debug/shared-app-debug.apk"
log "APK: $apk ($(du -h "$apk" | cut -f1))"
