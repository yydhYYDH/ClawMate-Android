#!/usr/bin/env bash
# Locate the Android toolchain without hardcoding machine-specific paths.
#
# Usage:
#   source scripts/android-env.sh        # export variables into the current shell
#   scripts/android-env.sh --check       # print what was found and exit non-zero if incomplete
#
# Resolution order for each component:
#   JAVA_HOME        -> $JAVA_HOME -> sibling of `java` on PATH
#   ANDROID_HOME     -> $ANDROID_HOME -> $ANDROID_SDK_ROOT -> `sdkmanager` on PATH
#   GRADLE_USER_HOME -> $GRADLE_USER_HOME -> ${HOME}/.gradle (Gradle's own default)
#
# Nothing here is exported unless it is actually usable, so a missing SDK fails
# loudly at build time instead of silently producing the wrong configuration.

_clawmate_log() { printf '%s\n' "$*" >&2; }

# Conventional install root for this project's toolchain. Only used as a
# fallback; explicit environment variables always win.
_CLAWMATE_ENV_ROOT="${CLAWMATE_ENV_ROOT:-$HOME/env}"

_resolve_java_home() {
    local candidate
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        printf '%s' "$JAVA_HOME"; return 0
    fi
    for candidate in "$_CLAWMATE_ENV_ROOT"/jdk-*; do
        [ -x "$candidate/bin/java" ] && { printf '%s' "$candidate"; return 0; }
    done
    if command -v java >/dev/null 2>&1; then
        local bin
        bin=$(readlink -f "$(command -v java)" 2>/dev/null) || return 1
        dirname "$(dirname "$bin")"; return 0
    fi
    return 1
}

_resolve_android_home() {
    local candidate
    for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
                     "$_CLAWMATE_ENV_ROOT/android" "$HOME/Android/Sdk"; do
        if [ -n "$candidate" ] && [ -x "$candidate/platform-tools/adb" ]; then
            printf '%s' "$candidate"; return 0
        fi
    done
    if command -v sdkmanager >/dev/null 2>&1; then
        # sdkmanager lives in <sdk>/cmdline-tools/<ver>/bin/
        local real
        real=$(readlink -f "$(command -v sdkmanager)" 2>/dev/null) || return 1
        ( cd "$(dirname "$real")/../../.." 2>/dev/null && pwd ); return 0
    fi
    return 1
}

_clawmate_check() {
    local status=0
    local jh ah gh
    jh=$(_resolve_java_home) || { _clawmate_log "MISSING: JDK 17 (set JAVA_HOME)"; status=1; }
    ah=$(_resolve_android_home) || { _clawmate_log "MISSING: Android SDK (set ANDROID_HOME)"; status=1; }
    gh=${GRADLE_USER_HOME:-${HOME}/.gradle}

    if [ "$status" -eq 0 ]; then
        _clawmate_log "JAVA_HOME        = $jh"
        _clawmate_log "ANDROID_HOME     = $ah"
        _clawmate_log "GRADLE_USER_HOME = $gh"

        # The versions below must match gradle/libs.versions.toml and app/build.gradle.kts.
        [ -d "$ah/platforms/android-34" ]        || { _clawmate_log "MISSING: platforms;android-34";   status=1; }
        [ -d "$ah/build-tools/34.0.0" ]          || { _clawmate_log "MISSING: build-tools;34.0.0";     status=1; }
        [ -d "$ah/ndk/25.2.9519653" ]            || { _clawmate_log "MISSING: ndk;25.2.9519653";       status=1; }
        [ -d "$ah/cmake/3.22.1" ]                || { _clawmate_log "MISSING: cmake;3.22.1";           status=1; }
        [ -x "$ah/platform-tools/adb" ]          || { _clawmate_log "MISSING: platform-tools (adb)";    status=1; }

        local major
        major=$("$jh/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
        [ "$major" = "17" ] || { _clawmate_log "WARN: JDK major version is '${major:-unknown}', build expects 17"; }
    fi

    if [ "$status" -eq 0 ]; then
        _clawmate_log "OK: toolchain looks complete."
    else
        _clawmate_log ""
        _clawmate_log "Install hints (adjust to your platform):"
        _clawmate_log "  JDK 17      : a Temurin/JDK 17 distribution, export JAVA_HOME"
        _clawmate_log "  Android SDK : cmdline-tools, then sdkmanager with the versions listed above"
        _clawmate_log "  licenses    : sdkmanager --licenses"
    fi
    return "$status"
}

if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
    # Executed directly.
    case "${1:-}" in
        --check|"") _clawmate_check; exit $? ;;
        *) _clawmate_log "usage: scripts/android-env.sh [--check]"; exit 2 ;;
    esac
else
    # Sourced: export what we found, but fail loudly when something is missing.
    _clawmate_ok=1
    if _jh=$(_resolve_java_home); then
        export JAVA_HOME="$_jh"
    else
        _clawmate_log "ERROR: could not resolve JAVA_HOME; set it or put java on PATH."
        _clawmate_ok=0
    fi
    if _ah=$(_resolve_android_home); then
        export ANDROID_HOME="$_ah"
        export ANDROID_SDK_ROOT="$_ah"
        if [ -d "$_ah/ndk/25.2.9519653" ]; then export ANDROID_NDK_HOME="$_ah/ndk/25.2.9519653"; fi
    else
        _clawmate_log "ERROR: could not resolve ANDROID_HOME; set it or put sdkmanager on PATH."
        _clawmate_ok=0
    fi
    export GRADLE_USER_HOME="${GRADLE_USER_HOME:-${HOME}/.gradle}"

    # Guard each path: an empty variable must never expand to "/bin" or "/platform-tools".
    if [ -n "${JAVA_HOME:-}" ]; then
        case ":$PATH:" in
            *":$JAVA_HOME/bin:"*) ;;
            *) PATH="$PATH:$JAVA_HOME/bin" ;;
        esac
    fi
    if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/platform-tools" ]; then
        case ":$PATH:" in
            *":$ANDROID_HOME/platform-tools:"*) ;;
            *) PATH="$PATH:$ANDROID_HOME/platform-tools" ;;
        esac
    fi
    export PATH

    if [ "$_clawmate_ok" -ne 1 ]; then
        _clawmate_log "Run 'scripts/android-env.sh --check' for install hints."
        unset _clawmate_ok _jh _ah
        return 1 2>/dev/null || exit 1
    fi
    unset _clawmate_ok _jh _ah
fi
