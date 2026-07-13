#!/usr/bin/env bash
#
# Proves an APK is ABI-neutral: it must contain no native libraries
# (no lib/** directory and no *.so members). A pure Java + Android Canvas
# build installs and runs on the TX10's 32-bit armeabi-v7a runtime precisely
# because it ships zero native code.
#
# Usage: scripts/check-no-native-libs.sh <path-to.apk>
set -euo pipefail

APK="${1:?usage: check-no-native-libs.sh <path-to.apk>}"
if [ ! -f "$APK" ]; then
    echo "check-no-native-libs: APK not found: $APK" >&2
    exit 2
fi

# List archive members (names only, one per line) in a portable way.
if command -v zipinfo >/dev/null 2>&1; then
    entries="$(zipinfo -1 "$APK")"
else
    entries="$(unzip -Z1 "$APK")"
fi

native="$(printf '%s\n' "$entries" | grep -E '(^|/)lib/|\.so$' || true)"
if [ -n "$native" ]; then
    echo "check-no-native-libs: FAIL — APK contains native library entries:" >&2
    printf '%s\n' "$native" >&2
    exit 1
fi

echo "check-no-native-libs: PASS — no lib/** or *.so entries in $APK"
