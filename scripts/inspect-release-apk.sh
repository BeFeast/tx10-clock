#!/usr/bin/env bash
#
# inspect-release-apk.sh <app.apk> <release-evidence.json>
#
# Independently inspect a downloaded GitHub Release asset against its evidence
# (acceptance/verification: download the release asset and independently inspect
# its SHA-256, manifest, absence of native libraries, version, signing
# certificate, and source/toolchain evidence).
#
# The digest, size, no-native-library, and evidence-contract checks are fully
# offline (Python 3 + bash + unzip). The manifest/package and signing-certificate
# checks additionally require Build Tools 36.0.0 (aapt2 + apksigner); when the
# SDK is not present they are reported as skipped rather than silently passed.
set -euo pipefail

APK="${1:?usage: inspect-release-apk.sh <app.apk> <release-evidence.json>}"
EVID="${2:?usage: inspect-release-apk.sh <app.apk> <release-evidence.json>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

[ -f "$APK" ]  || { echo "inspect: APK not found: $APK" >&2; exit 2; }
[ -f "$EVID" ] || { echo "inspect: evidence not found: $EVID" >&2; exit 2; }

ok()   { printf '\033[32mPASS\033[0m %s\n' "$*"; }
skip() { printf '\033[33mSKIP\033[0m %s\n' "$*"; }
die()  { printf '\033[31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

field() { python3 -c 'import json,sys;d=json.load(open(sys.argv[1]))
for k in sys.argv[2].split("."):
    d=d[k]
print(d)' "$EVID" "$1"; }

# --- 0. Evidence must satisfy the contract before we trust its claims --------
python3 "$ROOT/tools/release-evidence/validate_release_evidence.py" "$EVID" >/dev/null \
    || die "release evidence does not satisfy the contract"
ok "release evidence validates against the contract (source & toolchain evidence)"

EXP_SHA="$(field artifact.sha256)"
EXP_SIZE="$(field artifact.size_bytes)"
EXP_APPID="$(field package.application_id)"
EXP_VNAME="$(field package.version_name)"
EXP_VCODE="$(field package.version_code)"
EXP_TAG="$(field source.release_tag)"
EXP_CERT="$(field signing.certificate_sha256_fingerprint)"

# --- 1. SHA-256 --------------------------------------------------------------
GOT_SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
[ "$GOT_SHA" = "$EXP_SHA" ] || die "SHA-256 mismatch: apk=$GOT_SHA evidence=$EXP_SHA"
ok "SHA-256 matches evidence ($GOT_SHA)"

# --- 2. Size -----------------------------------------------------------------
GOT_SIZE="$(stat -c '%s' "$APK" 2>/dev/null || stat -f '%z' "$APK")"
[ "$GOT_SIZE" = "$EXP_SIZE" ] || die "size mismatch: apk=$GOT_SIZE evidence=$EXP_SIZE"
ok "size matches evidence ($GOT_SIZE bytes)"

# --- 3. No native libraries (offline archive inspection) ---------------------
bash "$ROOT/scripts/check-no-native-libs.sh" "$APK" >/dev/null \
    || die "APK contains native libraries (evidence claims none)"
ok "no lib/** or *.so entries (matches evidence: native_libraries.present=false)"

# --- 4. Manifest / package metadata (needs aapt2 from build-tools 36.0.0) -----
AAPT2="$(ls "${ANDROID_SDK_ROOT:-/nonexistent}"/build-tools/36.0.0/aapt2 2>/dev/null || true)"
if [ -n "$AAPT2" ] && [ -x "$AAPT2" ]; then
    B="$("$AAPT2" dump badging "$APK")"
    printf '%s\n' "$B" | grep -q "package: name='$EXP_APPID'" \
        || die "application id mismatch (want $EXP_APPID)"
    printf '%s\n' "$B" | grep -q "versionName='$EXP_VNAME'" \
        || die "versionName mismatch (want $EXP_VNAME)"
    printf '%s\n' "$B" | grep -q "versionCode='$EXP_VCODE'" \
        || die "versionCode mismatch (want $EXP_VCODE)"
    if printf '%s\n' "$B" | grep -q "native-code:"; then
        die "APK declares native-code"
    fi
    [ "v$EXP_VNAME" = "$EXP_TAG" ] \
        || die "version_name $EXP_VNAME does not match release tag $EXP_TAG"
    ok "manifest: $EXP_APPID $EXP_VNAME (code $EXP_VCODE), no native-code, matches tag $EXP_TAG"
else
    skip "manifest/package inspection — set ANDROID_SDK_ROOT to an SDK with build-tools;36.0.0 (aapt2)"
fi

# --- 5. Signing certificate (needs apksigner from build-tools 36.0.0) ---------
APKSIGNER="$(ls "${ANDROID_SDK_ROOT:-/nonexistent}"/build-tools/36.0.0/apksigner 2>/dev/null || true)"
if [ -n "$APKSIGNER" ] && [ -x "$APKSIGNER" ]; then
    VOUT="$("$APKSIGNER" verify --print-certs -Werr "$APK")" \
        || die "apksigner verify --print-certs -Werr failed"
    GOT_CERT="$(printf '%s\n' "$VOUT" | sed -n 's/.*SHA-*256 digest: *//p' | head -1 \
        | tr 'a-f' 'A-F' | tr -cd '0-9A-F')"
    EXP_CERT_N="$(printf '%s' "$EXP_CERT" | tr -cd '0-9A-F')"
    [ -n "$GOT_CERT" ] || die "apksigner did not print a SHA-256 certificate digest"
    [ "$GOT_CERT" = "$EXP_CERT_N" ] \
        || die "signing certificate SHA-256 does not match evidence"
    ok "apksigner verify -Werr passed; certificate SHA-256 matches evidence"
else
    skip "signing-certificate inspection — set ANDROID_SDK_ROOT to an SDK with build-tools;36.0.0 (apksigner)"
fi

echo ""
echo "inspect-release-apk: offline checks passed; SDK-gated checks reported above."
