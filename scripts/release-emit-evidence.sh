#!/usr/bin/env bash
#
# release-emit-evidence.sh — assemble the release-evidence document for the
# signed artifact, validate it against the contract, and stage the release
# outputs (final APK name, SHA256SUMS, release notes).
#
# It records exactly what the acceptance criteria require: the source commit and
# tag, the pinned toolchain (read from release/toolchain.lock.json — the single
# source of truth), the resolved SDK package revisions/digests, the APK SHA-256
# and size, the package/version, the proven absence of native libraries, the
# signing certificate fingerprint (reference only), the reproducibility
# comparison, and the CI run. It then validates the document and fails the
# release if the evidence does not satisfy the contract.
set -euo pipefail

: "${RELEASE_TAG:?}" "${SOURCE_COMMIT:?}" "${REPO:?}" "${RUN_URL:?}"
: "${SHA_A:?}" "${SHA_B:?}" "${BYTE_IDENTICAL:?}" "${SIGNING_KEY_REFERENCE:?}"
: "${ANDROID_SDK_ROOT:?}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# The authoritative source commit is the checked-out HEAD (the tagged commit),
# which is correct for both a tag push and a manual dispatch of an existing tag.
if command -v git >/dev/null 2>&1 && git rev-parse --git-dir >/dev/null 2>&1; then
    SOURCE_COMMIT="$(git rev-parse HEAD)"
fi

OUT="release-out"
SIGNED="$OUT/app-release-signed.apk"
[ -f "$SIGNED" ] || { echo "signed APK missing at $SIGNED" >&2; exit 1; }

die() { echo "release-emit-evidence: $*" >&2; exit 1; }

# Back the policy booleans below with a fail-closed proof of the committed
# Gradle locks, verification metadata, workflow actions, and resolver pins.
bash scripts/check-release-pins.sh >/dev/null \
    || die "release pin/lock/verification proof failed"

# Final, published artifact name (bare, no directories).
APK_NAME="tx10-clock-${RELEASE_TAG}-release.apk"
cp "$SIGNED" "$OUT/$APK_NAME"
printf '%s' "$APK_NAME" > "$OUT/apk-name.txt"

APK_SHA="$(sha256sum "$OUT/$APK_NAME" | cut -d' ' -f1)"
APK_SIZE="$(stat -c '%s' "$OUT/$APK_NAME")"
CERT_SHA="$(cat "$OUT/cert-sha256.txt")"

# --- Package metadata + native-code proof from the signed APK ----------------
AAPT2="$(ls "$ANDROID_SDK_ROOT"/build-tools/36.0.0/aapt2 2>/dev/null || true)"
[ -x "$AAPT2" ] || die "aapt2 not found in build-tools 36.0.0"
BADGING="$("$AAPT2" dump badging "$OUT/$APK_NAME")"
APP_ID="$(printf '%s\n' "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
VERSION_NAME="$(printf '%s\n' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
VERSION_CODE="$(printf '%s\n' "$BADGING" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")"

# Absence of native libraries is a hard requirement — prove it here.
bash scripts/check-no-native-libs.sh "$OUT/$APK_NAME" >/dev/null \
    || die "signed APK contains native libraries"

# --- Resolved SDK package revisions + digests --------------------------------
# A stable per-package digest: sha256 over the sorted list of file hashes in the
# installed package directory. Deterministic for a given resolved package.
pkg_digest() {
    ( cd "$1" && find . -type f -exec sha256sum {} + | sort | sha256sum | cut -d' ' -f1 )
}
pkg_revision() {
    sed -n 's/^Pkg\.Revision=//p' "$1/source.properties" 2>/dev/null | head -1
}

PLATFORM_DIR="$ANDROID_SDK_ROOT/platforms/android-29"
BT_DIR="$ANDROID_SDK_ROOT/build-tools/36.0.0"
CT_DIR="$ANDROID_SDK_ROOT/cmdline-tools/latest"
[ -d "$PLATFORM_DIR" ] || die "platform android-29 not installed"
[ -d "$BT_DIR" ] || die "build-tools 36.0.0 not installed"
[ -d "$CT_DIR" ] || die "command-line tools not installed under cmdline-tools/latest"

# --- Assemble + validate the evidence document -------------------------------
EVIDENCE="$OUT/release-evidence.json"
# Export the computed values so the assembler (a separate process) can read them.
export CT_DIR APK_NAME APK_SHA APK_SIZE APP_ID VERSION_NAME VERSION_CODE CERT_SHA
python3 - "$EVIDENCE" <<'PY'
import hashlib, json, os, sys

out_path = sys.argv[1]
root = os.getcwd()
lock = json.load(open("release/toolchain.lock.json"))
tc = lock["toolchain"]

def digest(path):
    files = []
    for dp, _dn, fn in os.walk(path):
        for f in fn:
            files.append(os.path.join(dp, f))
    h = hashlib.sha256()
    for fp in sorted(files):
        with open(fp, "rb") as fh:
            h.update(hashlib.sha256(fh.read()).hexdigest().encode())
    return h.hexdigest()

def rev(path):
    sp = os.path.join(path, "source.properties")
    if os.path.exists(sp):
        for line in open(sp):
            if line.startswith("Pkg.Revision="):
                return line.split("=", 1)[1].strip()
    return "unknown"

sdk = os.environ["ANDROID_SDK_ROOT"]
platform_dir = os.path.join(sdk, "platforms", "android-29")
bt_dir = os.path.join(sdk, "build-tools", "36.0.0")
packages = [
    {"path": "platforms;android-29", "revision": rev(platform_dir),
     "sha256": digest(platform_dir)},
    {"path": "build-tools;36.0.0", "revision": rev(bt_dir),
     "sha256": digest(bt_dir)},
]
ct = os.environ["CT_DIR"].rstrip("/")
packages.append({"path": "cmdline-tools;%s" % rev(ct),
                 "revision": rev(ct), "sha256": digest(ct)})

byte_identical = os.environ["BYTE_IDENTICAL"] == "true"
tag = os.environ["RELEASE_TAG"]
doc = {
    "schema_version": "1.0.0",
    "evidence_id": ("tx10-clock-" + tag.replace(".", "-"))[:64],
    "source": {
        "repository": os.environ["REPO"],
        "commit_sha": os.environ["SOURCE_COMMIT"],
        "release_tag": tag,
    },
    "toolchain": {
        "jdk_major": tc["jdk_major"],
        "android_gradle_plugin": tc["android_gradle_plugin"],
        "gradle": tc["gradle"],
        "gradle_distribution_sha256": tc["gradle_distribution_sha256"],
        "android_platform": tc["android_platform"],
        "build_tools": tc["build_tools"],
        "command_line_tools": tc["command_line_tools"],
        "dependency_verification": True,
        "dependency_locking": True,
        "allows_dynamic_versions": False,
        "allows_snapshot_dependencies": False,
        "actions_fully_sha_pinned": True,
    },
    "sdk_packages": packages,
    "artifact": {
        "filename": os.environ["APK_NAME"],
        "sha256": os.environ["APK_SHA"],
        "size_bytes": int(os.environ["APK_SIZE"]),
    },
    "package": {
        "application_id": os.environ["APP_ID"],
        "version_name": os.environ["VERSION_NAME"],
        "version_code": int(os.environ["VERSION_CODE"]),
    },
    "native_libraries": {"present": False, "entries": []},
    "signing": {
        "certificate_sha256_fingerprint": os.environ["CERT_SHA"],
        "key_reference": os.environ["SIGNING_KEY_REFERENCE"],
        "apksigner_verified": True,
        "apksigner_command": "apksigner verify --print-certs -Werr",
    },
    "reproducibility": {
        "compared": True,
        "byte_identical": byte_identical,
        "builds": [
            {"environment": "ci-clean-a", "artifact_sha256": os.environ["SHA_A"]},
            {"environment": "ci-clean-b", "artifact_sha256": os.environ["SHA_B"]},
        ],
    },
    "ci": {
        "provider": "github-actions",
        "workflow": "release.yml",
        "run_url": os.environ["RUN_URL"],
    },
}
with open(out_path, "w") as fh:
    json.dump(doc, fh, indent=2)
    fh.write("\n")
PY

python3 tools/release-evidence/validate_release_evidence.py "$EVIDENCE" >/dev/null \
    || die "assembled release evidence failed contract validation"

# --- Stage SHA256SUMS + public-safe release notes ----------------------------
( cd "$OUT" && sha256sum "$APK_NAME" "release-evidence.json" > SHA256SUMS )

cat > "$OUT/RELEASE_NOTES.md" <<EOF
# TX10 Clock ${RELEASE_TAG}

Signed release APK for the TX10 Pro (analog + digital clock).

- Source commit: \`${SOURCE_COMMIT}\`
- Package: \`${APP_ID}\` ${VERSION_NAME} (versionCode ${VERSION_CODE})
- APK SHA-256: \`${APK_SHA}\`
- Native libraries: none (ABI-neutral, pure DEX + resources)
- Signing certificate SHA-256: \`${CERT_SHA}\`
- Reproducibility: two clean-environment builds compared, byte-identical=${BYTE_IDENTICAL}

Full machine-readable provenance is attached as \`release-evidence.json\`
(schema: \`release/evidence/schema/evidence-v1.schema.json\`). Verify the asset
independently with \`scripts/inspect-release-apk.sh\` — see \`docs/releasing.md\`.
EOF

echo "release-emit-evidence: evidence assembled and validated; outputs staged in $OUT/"
