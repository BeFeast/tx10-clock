#!/usr/bin/env bash
#
# check-release-pins.sh — host-only static proof that the release inputs are
# pinned exactly as the contract requires (acceptance: release inputs are
# pinned … full-SHA-pinned GitHub Actions … no dynamic or SNAPSHOT
# dependencies).
#
# The single source of truth is release/toolchain.lock.json, rendered from the
# release-evidence validator. This script asserts the checked-in build files and
# workflows agree with that lock. It needs only bash + python3 — no Android SDK,
# no network, no Gradle run.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

LOCK="release/toolchain.lock.json"
ok()   { printf 'PASS %s\n' "$*"; }
die()  { printf 'FAIL %s\n' "$*" >&2; exit 1; }

[ -f "$LOCK" ] || die "missing $LOCK"
command -v python3 >/dev/null 2>&1 || die "python3 required"

lock() { python3 -c 'import json,sys;print(json.load(open("'"$LOCK"'"))'"$1"')'; }

AGP="$(lock '["toolchain"]["android_gradle_plugin"]')"
GRADLE="$(lock '["toolchain"]["gradle"]')"
GRADLE_SHA="$(lock '["toolchain"]["gradle_distribution_sha256"]')"
BUILD_TOOLS="$(lock '["toolchain"]["build_tools"]')"
PLATFORM="$(lock '["toolchain"]["android_platform"]')"
CMDLINE="$(lock '["toolchain"]["command_line_tools"]')"

# 1. Lock must itself match what the validator emits (no hand edits).
if ! diff -u "$LOCK" \
    <(python3 tools/release-evidence/validate_release_evidence.py --emit-lock) >/dev/null; then
    die "$LOCK drifted from the validator; regenerate with --emit-lock"
fi
ok "toolchain lock matches the validator (single source of truth)"

# 2. Root build.gradle pins the AGP version exactly.
grep -Eq "id 'com\.android\.application' version '${AGP//./\\.}'" build.gradle \
    || die "build.gradle does not pin AGP ${AGP}"
ok "build.gradle pins AGP ${AGP}"

# 3. Gradle wrapper pins the distribution and its SHA-256.
WRAP="gradle/wrapper/gradle-wrapper.properties"
grep -Fq "gradle-${GRADLE}-bin.zip" "$WRAP" \
    || die "wrapper does not pin Gradle ${GRADLE}"
grep -Fq "distributionSha256Sum=${GRADLE_SHA}" "$WRAP" \
    || die "wrapper does not pin the Gradle distribution SHA-256"
ok "wrapper pins Gradle ${GRADLE} + distribution SHA-256"

# 4. App module pins the Build Tools version.
grep -Fq "buildToolsVersion \"${BUILD_TOOLS}\"" app/build.gradle \
    || die "app/build.gradle does not pin buildToolsVersion ${BUILD_TOOLS}"
ok "app/build.gradle pins Build Tools ${BUILD_TOOLS}"

# 5. The release workflow installs the pinned SDK inputs.
REL=".github/workflows/release.yml"
[ -f "$REL" ] || die "missing $REL"
grep -Fq "platforms;android-\${{ env.ANDROID_PLATFORM }}" "$REL" \
    && grep -Fq "ANDROID_PLATFORM: '${PLATFORM}'" "$REL" \
    || die "release workflow does not pin platform ${PLATFORM}"
grep -Fq "ANDROID_BUILD_TOOLS: '${BUILD_TOOLS}'" "$REL" \
    || die "release workflow does not pin build-tools ${BUILD_TOOLS}"
grep -Fq "ANDROID_CMDLINE_TOOLS_BUILD: '${CMDLINE}'" "$REL" \
    || die "release workflow does not pin command-line-tools ${CMDLINE}"
ok "release workflow pins platform ${PLATFORM}, build-tools ${BUILD_TOOLS}, cmdline-tools ${CMDLINE}"

# 6. Every workflow action is pinned to a full 40-hex commit SHA.
bad_actions="$(grep -rnE '^[[:space:]]*uses:' .github/workflows/ \
    | grep -vE 'uses:[[:space:]]*[^@]+@[0-9a-f]{40}([[:space:]]|$)' || true)"
if [ -n "$bad_actions" ]; then
    echo "check-release-pins: FAIL — actions not pinned to a full commit SHA:" >&2
    printf '%s\n' "$bad_actions" >&2
    exit 1
fi
ok "all workflow actions pinned to full commit SHAs"

# 7. No dynamic or SNAPSHOT dependencies in the Gradle build. Comments are
#    stripped first so documentation of this very rule cannot trip it.
gradle_files=(build.gradle app/build.gradle settings.gradle gradle.properties)
strip_comments() { sed -E 's://.*$::; s:^[[:space:]]*#.*$::' "$@"; }
dynamic="$(strip_comments "${gradle_files[@]}" \
    | grep -nE \
        "SNAPSHOT|latest\.(release|integration)|['\"][A-Za-z0-9._:-]*\+['\"]|mavenLocal\(" \
    2>/dev/null || true)"
if [ -n "$dynamic" ]; then
    echo "check-release-pins: FAIL — dynamic/SNAPSHOT dependency or mavenLocal:" >&2
    printf '%s\n' "$dynamic" >&2
    exit 1
fi
ok "no dynamic/SNAPSHOT dependencies (and no mavenLocal)"

echo "check-release-pins: PASS — release inputs are pinned to the locked toolchain"
