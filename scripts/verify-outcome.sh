#!/usr/bin/env bash
#
# verify-outcome.sh — the stable, post-merge outcome entrypoint for tx10-clock.
#
# Run from an isolated exact-SHA checkout (CI or the authoritative execution
# host) it performs the documented repository outcome checks and exits non-zero
# if any fails:
#
#   1. Clean build of the release APK (pure Java + Android Canvas, SDK 29).
#   2. Static analysis (Android Lint) + unit/static scene/format/config checks.
#   3. Offscreen golden verifier (1280x720 ARGB_8888 in the API 29 test env);
#      a mismatch emits actual/expected/diff PNGs.
#   4. APK manifest/package inspection: package id, versionName v0.1.0, SDK 29,
#      and proof the artifact has no lib/** native entries.
#   5. Public-path hygiene scan.
#   6. Scope hygiene: the config core remains renderer-agnostic.
#
# No device is required and nothing is deployed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

log()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[32mPASS\033[0m %s\n' "$*"; }
die()  { printf '\033[31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

GRADLE_OUTPUT_DIR="app/build/golden-output"

# On any failure, surface the golden triage images if the verifier produced them.
on_error() {
    if [ -d "$GRADLE_OUTPUT_DIR" ] && ls "$GRADLE_OUTPUT_DIR"/*.png >/dev/null 2>&1; then
        printf '\n\033[31mGolden verifier emitted diagnostic images:\033[0m\n' >&2
        ls -1 "$GRADLE_OUTPUT_DIR"/*.png >&2
    fi
}
trap on_error ERR

# --- Resolve the Android SDK -------------------------------------------------
resolve_sdk() {
    if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/platforms" ]; then return; fi
    if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/platforms" ]; then
        export ANDROID_SDK_ROOT="$ANDROID_HOME"; return
    fi
    if [ -f local.properties ]; then
        local sdk
        # Accept any Gradle-valid `sdk.dir` entry, including surrounding
        # whitespace (e.g. `sdk.dir = /opt/android-sdk`).
        sdk="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' local.properties | head -1 || true)"
        if [ -n "$sdk" ] && [ -d "$sdk/platforms" ]; then export ANDROID_SDK_ROOT="$sdk"; return; fi
    fi
    local cand
    for cand in "$HOME/android-sdk" "$HOME/Android/Sdk" "/opt/android-sdk" "/usr/lib/android-sdk"; do
        if [ -d "$cand/platforms" ]; then export ANDROID_SDK_ROOT="$cand"; return; fi
    done
    die "Android SDK not found. Set ANDROID_SDK_ROOT to an SDK containing platforms/android-29."
}

resolve_sdk
export ANDROID_SDK_ROOT
log "Android SDK: $ANDROID_SDK_ROOT"

PINNED_BUILD_TOOLS="$(python3 -c \
    'import json; print(json.load(open("release/toolchain.lock.json"))["toolchain"]["build_tools"])')"
[ -n "$PINNED_BUILD_TOOLS" ] || die "build-tools pin missing from release/toolchain.lock.json"
AAPT2_OVERRIDE="$ANDROID_SDK_ROOT/build-tools/$PINNED_BUILD_TOOLS/aapt2"
[ -x "$AAPT2_OVERRIDE" ] \
    || die "pinned aapt2 not found at $AAPT2_OVERRIDE"
GRADLE="./gradlew --no-daemon --console=plain -Pandroid.aapt2FromMavenOverride=$AAPT2_OVERRIDE"

# --- 1..3  clean build + static + unit/golden --------------------------------
log "Clean build, Android Lint, and unit/static/golden checks"
# shellcheck disable=SC2086
$GRADLE clean :app:lint :app:testDebugUnitTest :app:assembleRelease \
    || die "gradle clean/lint/test/assemble failed"
ok "gradle clean + lint + unit/static/golden + release build"

# --- 4  APK manifest / package metadata + no native libs ---------------------
APK="app/build/outputs/apk/release/app-release-unsigned.apk"
[ -f "$APK" ] || die "release APK not found at $APK"

# Package/version/target-SDK metadata is a mandatory part of the release
# contract, so a missing badging tool is a hard failure — never a silent skip
# that could report success for a non-conforming APK. Prefer aapt; fall back to
# `aapt2 dump badging` (compatible output) when only aapt2 is installed.
AAPT="$(ls "$ANDROID_SDK_ROOT"/build-tools/*/aapt 2>/dev/null | sort -V | tail -1 || true)"
AAPT2="$(ls "$ANDROID_SDK_ROOT"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)"
if [ -n "$AAPT" ] && [ -x "$AAPT" ]; then
    BADGING="$("$AAPT" dump badging "$APK")"
elif [ -n "$AAPT2" ] && [ -x "$AAPT2" ]; then
    BADGING="$("$AAPT2" dump badging "$APK")"
else
    die "aapt/aapt2 not found in $ANDROID_SDK_ROOT/build-tools — cannot verify APK package metadata"
fi

log "APK manifest / package metadata"
printf '%s\n' "$BADGING" | grep -E "package:|sdkVersion|targetSdkVersion|launchable-activity|native-code" || true

printf '%s\n' "$BADGING" | grep -q "package: name='com.befeast.tx10clock'" \
    || die "unexpected package id (want com.befeast.tx10clock)"
printf '%s\n' "$BADGING" | grep -q "versionName='0.1.0'" \
    || die "unexpected versionName (want 0.1.0 — the v0.1.0 artifact)"
printf '%s\n' "$BADGING" | grep -q "targetSdkVersion:'29'" \
    || die "unexpected targetSdkVersion (want 29)"
if printf '%s\n' "$BADGING" | grep -q "native-code:"; then
    die "APK declares native-code"
fi
ok "package com.befeast.tx10clock, versionName 0.1.0, SDK 29, no native-code"

log "APK native-library check (no lib/**)"
scripts/check-no-native-libs.sh "$APK" || die "APK contains native libraries"

# --- 5  visual-contract integrity (host-only) --------------------------------
log "Visual-contract integrity (hashes, schema, image dimensions)"
scripts/verify-visual-contract.sh || die "visual-contract verification failed"

# --- 6  public-path hygiene --------------------------------------------------
log "Public-path hygiene"
scripts/check-public-hygiene.sh || die "public-path hygiene check failed"

# --- 6  scope hygiene --------------------------------------------------------
log "Config core is renderer-agnostic (no visual decisions)"
scripts/check-config-renderer-agnostic.sh || die "config core carries visual decisions"

log "OUTCOME: PASS"
printf '\033[32mAll documented repository outcome checks passed.\033[0m\n'
