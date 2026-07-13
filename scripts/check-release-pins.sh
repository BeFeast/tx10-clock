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
INFISICAL_VERSION="$(lock '["signing_resolver"]["infisical_cli_version"]')"
INFISICAL_SHA="$(lock '["signing_resolver"]["infisical_cli_linux_amd64_sha256"]')"

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

# 4. Dependency locking and checksum verification must be real committed Gradle
#    controls, not evidence booleans that merely claim the policy was enforced.
LOCKFILE="app/gradle.lockfile"
VERIFY="gradle/verification-metadata.xml"
[ -s "$LOCKFILE" ] || die "missing populated $LOCKFILE"
grep -Fq 'junit:junit:4.13.2=' "$LOCKFILE" \
    && grep -Fq 'org.robolectric:robolectric:4.11.1=' "$LOCKFILE" \
    || die "$LOCKFILE does not contain the declared test dependency graph"
grep -Fq 'lockAllConfigurations()' build.gradle \
    && grep -Fq 'lockMode = LockMode.STRICT' build.gradle \
    || die "build.gradle does not enforce strict locking for all configurations"
grep -Fq 'org.gradle.dependency.verification=strict' gradle.properties \
    || die "gradle.properties does not enforce strict dependency verification"
[ -s "$VERIFY" ] || die "missing populated $VERIFY"
grep -Fq '<verify-metadata>true</verify-metadata>' "$VERIFY" \
    && grep -Eq '<sha256 value="[0-9a-f]{64}"' "$VERIFY" \
    && grep -Fq '<components>' "$VERIFY" \
    || die "$VERIFY does not contain strict SHA-256 component verification state"
ok "strict Gradle dependency locks and SHA-256 verification metadata are committed"

# 5. App module pins the Build Tools version.
grep -Fq "buildToolsVersion \"${BUILD_TOOLS}\"" app/build.gradle \
    || die "app/build.gradle does not pin buildToolsVersion ${BUILD_TOOLS}"
ok "app/build.gradle pins Build Tools ${BUILD_TOOLS}"

# 6. The release workflow installs the pinned SDK and signing-resolver inputs.
REL=".github/workflows/release.yml"
[ -f "$REL" ] || die "missing $REL"
grep -Fq "platforms;android-\${{ env.ANDROID_PLATFORM }}" "$REL" \
    && grep -Fq "ANDROID_PLATFORM: '${PLATFORM}'" "$REL" \
    || die "release workflow does not pin platform ${PLATFORM}"
grep -Fq "ANDROID_BUILD_TOOLS: '${BUILD_TOOLS}'" "$REL" \
    || die "release workflow does not pin build-tools ${BUILD_TOOLS}"
grep -Fq "ANDROID_CMDLINE_TOOLS_BUILD: '${CMDLINE}'" "$REL" \
    || die "release workflow does not pin command-line-tools ${CMDLINE}"
grep -Fq "INFISICAL_CLI_VERSION: '${INFISICAL_VERSION}'" "$REL" \
    && grep -Fq "INFISICAL_CLI_LINUX_AMD64_SHA256: '${INFISICAL_SHA}'" "$REL" \
    && grep -Fq 'sha256sum --check' "$REL" \
    || die "release workflow does not install checksum-verified Infisical CLI ${INFISICAL_VERSION}"
ok "release workflow pins SDK inputs and checksum-verified Infisical CLI ${INFISICAL_VERSION}"

# 7. Every workflow action is pinned to a full 40-hex commit SHA.
bad_actions="$(grep -rnE '^[[:space:]]*uses:' .github/workflows/ \
    | grep -vE 'uses:[[:space:]]*[^@]+@[0-9a-f]{40}([[:space:]]|$)' || true)"
if [ -n "$bad_actions" ]; then
    echo "check-release-pins: FAIL — actions not pinned to a full commit SHA:" >&2
    printf '%s\n' "$bad_actions" >&2
    exit 1
fi
ok "all workflow actions pinned to full commit SHAs"

# 8. No dynamic or SNAPSHOT dependencies in the Gradle build. Comments are
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

# 9. Passwords and Universal Auth secrets stay out of process argv.
grep -Fq -- '-storepass:env RELEASE_KEYSTORE_PASSWORD' scripts/release-sign-and-verify.sh \
    || die "keytool keystore password is not passed through its environment modifier"
if grep -Eq -- '--(client-secret|storepass|token)=' scripts/release-*.sh; then
    die "release scripts expose a secret in process arguments"
fi
grep -Fq 'INFISICAL_UNIVERSAL_AUTH_CLIENT_SECRET' scripts/release-resolve-signing.sh \
    || die "Infisical Universal Auth secret is not sourced from environment"
grep -Fq 'export INFISICAL_DOMAIN="${INFISICAL_API_URL%/}"' scripts/release-resolve-signing.sh \
    || die "Infisical CLI domain is not bound to the configured API URL"
grep -Fq -- '--env="$INFISICAL_ENVIRONMENT"' scripts/release-resolve-signing.sh \
    && grep -Fq 'INFISICAL_ENVIRONMENT: ${{ vars.INFISICAL_ENVIRONMENT }}' "$REL" \
    || die "Infisical signing environment is not explicit"
ok "release signing credentials remain out of process arguments"

echo "check-release-pins: PASS — release inputs are pinned to the locked toolchain"
