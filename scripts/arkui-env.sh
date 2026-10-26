#!/usr/bin/env bash
# Configure the ArkUI-X / OpenHarmony toolchain used by the shared-frontend build.
#
# Usage:
#   source scripts/arkui-env.sh          # export variables into the current shell
#   scripts/arkui-env.sh --check         # report what was found; non-zero if incomplete
#
# Resolution order (nothing is hardcoded to a machine-specific location):
#   ARKUIX_SDK_HOME   -> $ARKUIX_SDK_HOME -> $HOME/env/arkui-x-sdk
#   OpenHarmony_HOME  -> $OpenHarmony_HOME -> $HOME/env/openharmony-sdk
#   ARKUI_BUILD_TOOLS -> $ARKUI_BUILD_TOOLS -> $HOME/env/arkui-build-tools
#
# The shared-frontend build additionally needs JDK 17 and the Android SDK;
# those are resolved by scripts/android-env.sh, which this script sources.

_clawmate_arkui_log() { printf '%s\n' "$*" >&2; }

_script_dir=$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)

_resolve_dir() {
    # $1 = explicit override, $2 = default root, $3 = marker path proving the dir is valid
    local override="$1" fallback="$2" marker="$3"
    if [ -n "$override" ] && [ -e "$override/$marker" ]; then printf '%s' "$override"; return 0; fi
    if [ -e "$fallback/$marker" ]; then printf '%s' "$fallback"; return 0; fi
    return 1
}

_clawmate_arkui_check() {
    local status=0
    local ax oh bt
    ax=$(_resolve_dir "${ARKUIX_SDK_HOME:-}" "$HOME/env/arkui-x-sdk" "20/arkui-x/sdkConfig.json") \
        || { _clawmate_arkui_log "MISSING: ArkUI-X SDK (set ARKUIX_SDK_HOME)"; status=1; }
    oh=$(_resolve_dir "${OpenHarmony_HOME:-}" "$HOME/env/openharmony-sdk" "20/ets/oh-uni-package.json") \
        || { _clawmate_arkui_log "MISSING: OpenHarmony SDK (set OpenHarmony_HOME)"; status=1; }
    bt=$(_resolve_dir "${ARKUI_BUILD_TOOLS:-}" "$HOME/env/arkui-build-tools" "node_modules/@ohos/hvigor") \
        || { _clawmate_arkui_log "MISSING: hvigor build tools (set ARKUI_BUILD_TOOLS)"; status=1; }

    if [ "$status" -eq 0 ]; then
        _clawmate_arkui_log "ARKUIX_SDK_HOME   = $ax"
        _clawmate_arkui_log "OpenHarmony_HOME  = $oh"
        _clawmate_arkui_log "ARKUI_BUILD_TOOLS = $bt"
        # The OpenHarmony SDK from the public mirror is a Beta build; newer
        # hvigor releases reject it with "SDK management mode has changed".
        local release
        release=$(sed -n 's/.*"releaseType"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
            "$oh/20/ets/oh-uni-package.json" 2>/dev/null | head -1)
        case "$release" in
            Beta*) _clawmate_arkui_log "WARN: OpenHarmony SDK releaseType is '$release'; hvigor 6.x may reject it. A Release build of the same version is required." ;;
        esac
        # JDK and Android SDK are also required by the Gradle stage.
        "$_script_dir/android-env.sh" --check >/dev/null 2>&1 || {
            _clawmate_arkui_log "WARN: Android toolchain incomplete (run scripts/android-env.sh --check)"; }
    else
        _clawmate_arkui_log ""
        _clawmate_arkui_log "Install hints:"
        _clawmate_arkui_log "  ArkUI-X SDK  : arkui-x-<platform>-x64-<version>-Release.zip"
        _clawmate_arkui_log "  OpenHarmony  : ohos-sdk-<platform>-public.tar.gz (Release build, not Beta)"
        _clawmate_arkui_log "  build tools  : npm install --registry=https://repo.harmonyos.com/npm/ \\"
        _clawmate_arkui_log "                   @ohos/hvigor@6.22.7 @ohos/hvigor-ohos-plugin@6.22.7 \\"
        _clawmate_arkui_log "                   @ohos/hvigor-ohos-arkui-x-plugin@4.22.7"
    fi
    return "$status"
}

if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
    case "${1:-}" in
        --check|"") _clawmate_arkui_check; exit $? ;;
        *) _clawmate_arkui_log "usage: scripts/arkui-env.sh [--check]"; exit 2 ;;
    esac
else
    if _ax=$(_resolve_dir "${ARKUIX_SDK_HOME:-}" "$HOME/env/arkui-x-sdk" "20/arkui-x/sdkConfig.json"); then
        export ARKUIX_SDK_HOME="$_ax"
    fi
    if _oh=$(_resolve_dir "${OpenHarmony_HOME:-}" "$HOME/env/openharmony-sdk" "20/ets/oh-uni-package.json"); then
        export OpenHarmony_HOME="$_oh"
    fi
    if _bt=$(_resolve_dir "${ARKUI_BUILD_TOOLS:-}" "$HOME/env/arkui-build-tools" "node_modules/@ohos/hvigor"); then
        export ARKUI_BUILD_TOOLS="$_bt"
        # hvigor is invoked through its JS entry point, so expose a convenience path.
        for _b in "$ARKUI_BUILD_TOOLS/node_modules/.bin" "$ARKUI_BUILD_TOOLS/bin"; do
            [ -d "$_b" ] || continue
            case ":$PATH:" in
                *":$_b:"*) ;;
                *) PATH="$PATH:$_b" ;;
            esac
        done
        unset _b
    fi
    export PATH

    # Bring in JDK/Android SDK too; failure there is reported by that script.
    if [ -r "$_script_dir/android-env.sh" ]; then
        . "$_script_dir/android-env.sh" || _clawmate_arkui_log "WARN: Android toolchain incomplete."
    fi
    unset _ax _oh _bt
fi
unset -f _resolve_dir
[ "${BASH_SOURCE[0]}" = "${0}" ] || unset -f _clawmate_arkui_check _clawmate_arkui_log
unset _script_dir
