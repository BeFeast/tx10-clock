#!/usr/bin/env bash
#
# release-sign-and-verify.sh <unsigned.apk> — align the signer, sign the APK,
# and verify the signer, emitting the signed artifact to release-out/.
#
# Order matters (acceptance/verification: the documented signer aligns before
# signing and requires apksigner verify --print-certs -Werr):
#
#   1. Align: compute the keystore certificate's SHA-256 fingerprint and require
#      it to equal the documented signer BEFORE any signing happens. A mismatch
#      aborts — we never sign with an unexpected key.
#   2. zipalign the unsigned APK.
#   3. apksigner sign with the resolved key (passwords via env, never argv).
#   4. apksigner verify --print-certs -Werr and re-assert the printed SHA-256
#      certificate digest equals the documented signer.
#
# Secrets arrive via env from release-resolve-signing.sh and are never echoed.
set -euo pipefail

UNSIGNED="${1:?usage: release-sign-and-verify.sh <unsigned.apk>}"
: "${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT must point at the SDK}"
: "${EXPECTED_CERT_SHA256:?RELEASE_SIGNING_CERT_SHA256 var (documented signer) is required}"
: "${RELEASE_KEYSTORE_PATH:?resolve signing material first}"
: "${RELEASE_KEYSTORE_PASSWORD:?}" "${RELEASE_KEY_ALIAS:?}" "${RELEASE_KEY_PASSWORD:?}"

die() { echo "release-sign-and-verify: $*" >&2; exit 1; }

# Normalise a fingerprint to lowercase hex with no colons for comparison.
norm() { printf '%s' "$1" | tr 'A-F' 'a-f' | tr -cd '0-9a-f'; }

BT="$(ls -d "$ANDROID_SDK_ROOT"/build-tools/36.0.0 2>/dev/null || true)"
[ -n "$BT" ] || die "build-tools 36.0.0 not found under $ANDROID_SDK_ROOT/build-tools"
APKSIGNER="$BT/apksigner"
ZIPALIGN="$BT/zipalign"
[ -x "$APKSIGNER" ] || die "apksigner not found in build-tools 36.0.0"
[ -x "$ZIPALIGN" ] || die "zipalign not found in build-tools 36.0.0"

EXPECTED="$(norm "$EXPECTED_CERT_SHA256")"
[ "${#EXPECTED}" -eq 64 ] || die "documented signer SHA-256 must be 64 hex chars"

# --- 1. Align the signer BEFORE signing -------------------------------------
KEYTOOL_OUT="$(keytool -list -v \
    -keystore "$RELEASE_KEYSTORE_PATH" \
    -alias "$RELEASE_KEY_ALIAS" \
    -storepass "$RELEASE_KEYSTORE_PASSWORD" 2>/dev/null)" \
    || die "cannot read keystore certificate (alias/password?)"
KEYSTORE_SHA="$(norm "$(printf '%s\n' "$KEYTOOL_OUT" \
    | sed -n 's/.*SHA-*256: *//p' | head -1)")"
[ "${#KEYSTORE_SHA}" -eq 64 ] || die "could not extract keystore SHA-256 fingerprint"
if [ "$KEYSTORE_SHA" != "$EXPECTED" ]; then
    die "signer alignment failed: keystore certificate does not match the documented signer"
fi
echo "release-sign-and-verify: signer aligned with the documented certificate"

# --- 2. zipalign ------------------------------------------------------------
mkdir -p release-out
ALIGNED="release-out/app-release-aligned.apk"
SIGNED="release-out/app-release-signed.apk"
rm -f "$ALIGNED" "$SIGNED"
"$ZIPALIGN" -p -f 4 "$UNSIGNED" "$ALIGNED" || die "zipalign failed"

# --- 3. Sign (passwords via env, never on the command line) -----------------
KEYSTORE_PASSWORD="$RELEASE_KEYSTORE_PASSWORD" \
KEY_PASSWORD="$RELEASE_KEY_PASSWORD" \
"$APKSIGNER" sign \
    --ks "$RELEASE_KEYSTORE_PATH" \
    --ks-key-alias "$RELEASE_KEY_ALIAS" \
    --ks-pass env:KEYSTORE_PASSWORD \
    --key-pass env:KEY_PASSWORD \
    --out "$SIGNED" \
    "$ALIGNED" || die "apksigner sign failed"
rm -f "$ALIGNED"

# --- 4. Verify the signer (-Werr) and re-assert the documented fingerprint ---
VERIFY_OUT="$("$APKSIGNER" verify --print-certs -Werr "$SIGNED")" \
    || die "apksigner verify --print-certs -Werr failed"
VERIFIED_SHA="$(norm "$(printf '%s\n' "$VERIFY_OUT" \
    | sed -n 's/.*SHA-*256 digest: *//p' | head -1)")"
[ "${#VERIFIED_SHA}" -eq 64 ] || die "apksigner did not print a SHA-256 certificate digest"
if [ "$VERIFIED_SHA" != "$EXPECTED" ]; then
    die "signed artifact certificate does not match the documented signer"
fi

# Record the verified fingerprint (uppercase colon form) for the evidence step.
printf '%s' "$EXPECTED_CERT_SHA256" > release-out/cert-sha256.txt
echo "release-sign-and-verify: signed and verified against the documented signer"
