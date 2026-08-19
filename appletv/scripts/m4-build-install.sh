#!/usr/bin/env bash
#
# TX10 Clock tvOS — build, sign, install, and launch on an Apple TV.
#
# Run from the repo root or from appletv/ (the script locates itself):
#
#   export TX10_TEAM_ID=XXXXXXXXXX     # paid Apple Developer team id (required)
#   export TX10_ATV_ID=<device-uuid>   # from: xcrun devicectl list devices
#   ./appletv/scripts/m4-build-install.sh
#
# Flags / env:
#   --skip-tests   skip the Core `swift test` gate (not recommended)
#   APP_ONLY=1     build only; skip device install + launch
#
# Idempotent: safe to rerun any time (this is also the annual re-sign ritual).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APPLETV_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

XCODEGEN_VERSION="2.43.0"
XCODEGEN_ZIP_URL="https://github.com/yonaskolb/XcodeGen/releases/download/${XCODEGEN_VERSION}/xcodegen.zip"
BUNDLE_ID="com.befeast.tx10clock.tv"

die()  { printf 'error: %s\n' "$*" >&2; exit 1; }
note() { printf '==> %s\n' "$*"; }

SKIP_TESTS=0
for arg in "$@"; do
  case "$arg" in
    --skip-tests) SKIP_TESTS=1 ;;
    -h|--help)
      sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) die "unknown argument: $arg (only --skip-tests is supported; see --help)" ;;
  esac
done

# --- 1. Toolchain sanity -----------------------------------------------------

command -v xcodebuild >/dev/null 2>&1 \
  || die "xcodebuild not found. Install Xcode from the App Store, then run:
  sudo xcode-select -s /Applications/Xcode.app"

if ! xcodebuild -version >/dev/null 2>&1; then
  die "xcodebuild exists but is not usable — usually an unaccepted Xcode license. Run:
  sudo xcodebuild -license accept
and rerun this script."
fi
note "Using $(xcodebuild -version | tr '\n' ' ')"

# --- 2. Core unit tests (pure SwiftPM, no Xcode project needed) --------------

if [[ "$SKIP_TESTS" -eq 1 ]]; then
  note "Skipping TX10ClockCore tests (--skip-tests)"
else
  note "Running TX10ClockCore tests: swift test"
  ( cd "$APPLETV_DIR/Core" && swift test ) \
    || die "TX10ClockCore tests failed. Fix them before installing, or rerun with --skip-tests to bypass (not recommended)."
fi

# --- 3. Ensure xcodegen ------------------------------------------------------

XCODEGEN_BIN=""
if command -v xcodegen >/dev/null 2>&1; then
  XCODEGEN_BIN="$(command -v xcodegen)"
elif [[ -x "$APPLETV_DIR/.tools/xcodegen/bin/xcodegen" ]]; then
  XCODEGEN_BIN="$APPLETV_DIR/.tools/xcodegen/bin/xcodegen"
else
  note "xcodegen not on PATH; downloading XcodeGen ${XCODEGEN_VERSION} into appletv/.tools/ (no brew needed)"
  mkdir -p "$APPLETV_DIR/.tools"
  curl -fL --retry 3 -o "$APPLETV_DIR/.tools/xcodegen.zip" "$XCODEGEN_ZIP_URL" \
    || die "Could not download XcodeGen from:
  $XCODEGEN_ZIP_URL
Check network access, or install xcodegen yourself and put it on PATH, then rerun."
  unzip -oq "$APPLETV_DIR/.tools/xcodegen.zip" -d "$APPLETV_DIR/.tools" \
    || die "Could not unzip $APPLETV_DIR/.tools/xcodegen.zip — delete appletv/.tools/ and rerun."
  rm -f "$APPLETV_DIR/.tools/xcodegen.zip"
  chmod +x "$APPLETV_DIR/.tools/xcodegen/bin/xcodegen"
  XCODEGEN_BIN="$APPLETV_DIR/.tools/xcodegen/bin/xcodegen"
fi
note "xcodegen: $XCODEGEN_BIN ($("$XCODEGEN_BIN" version 2>/dev/null || echo 'version unknown'))"

# --- 4. Generate the Xcode project ------------------------------------------

note "Generating TX10ClockTV.xcodeproj from project.yml"
( cd "$APPLETV_DIR" && "$XCODEGEN_BIN" generate )

# --- 5. Signed Release build -------------------------------------------------

if [[ -z "${TX10_TEAM_ID:-}" ]]; then
  die "TX10_TEAM_ID is not set.
Export the ten-character Apple Developer team id of a team with a *paid*
Apple Developer Program membership (1-year provisioning; a free personal
team signs 7-day profiles and the clock would die weekly):
  export TX10_TEAM_ID=XXXXXXXXXX
Find it at developer.apple.com > Account > Membership details, or in the
'listTeams' section of a previous xcodebuild -allowProvisioningUpdates log.
Note: a personal team KEEPS its Team ID when the same Apple ID enrolls in
the paid program — check the Membership page says 'Apple Developer Program',
not which id it is."
fi

# A generic destination cannot auto-register the Apple TV in the developer
# portal, and a team with zero registered tvOS devices then fails with "Your
# team has no devices from which to generate a provisioning profile". Building
# against the concrete device lets -allowProvisioningDeviceRegistration
# register its UDID on first build. TX10_ATV_UDID is the hardware UDID (the
# 40-hex "Identifier" in Xcode's Devices window — NOT the dashed devicectl
# CoreDevice UUID); when unset we try to derive it from TX10_ATV_ID.
if [[ -z "${TX10_ATV_UDID:-}" && -n "${TX10_ATV_ID:-}" ]]; then
  TX10_ATV_UDID="$(xcrun devicectl device info details --device "$TX10_ATV_ID" 2>/dev/null \
    | awk -F': *' 'tolower($1) ~ /udid/ { print $2; exit }' || true)"
fi
if [[ -n "${TX10_ATV_UDID:-}" ]]; then
  DESTINATION="platform=tvOS,id=$TX10_ATV_UDID"
  note "Building against device $TX10_ATV_UDID (registers it in the portal on first build)"
else
  DESTINATION="generic/platform=tvOS"
  note "TX10_ATV_UDID/TX10_ATV_ID not set — generic destination; this FAILS on a team with no registered tvOS devices"
fi

note "Building Release for tvOS (team $TX10_TEAM_ID)"
( cd "$APPLETV_DIR" && xcodebuild \
    -project TX10ClockTV.xcodeproj \
    -scheme TX10ClockTV \
    -configuration Release \
    -destination "$DESTINATION" \
    -derivedDataPath .build-xcode \
    -allowProvisioningUpdates \
    -allowProvisioningDeviceRegistration \
    DEVELOPMENT_TEAM="$TX10_TEAM_ID" \
    TX10_TEAM_ID="$TX10_TEAM_ID" \
    build ) \
  || die "xcodebuild failed. Common causes:
  - Not signed in: Xcode > Settings > Accounts > add the Apple ID for team $TX10_TEAM_ID.
  - tvOS SDK missing: xcodebuild -downloadPlatform tvOS
  - Signing/provisioning: open appletv/TX10ClockTV.xcodeproj in Xcode once and
    check Signing & Capabilities for the concrete error."

# --- 6. Locate the built .app ------------------------------------------------

APP_PATH="$APPLETV_DIR/.build-xcode/Build/Products/Release-appletvos/TX10ClockTV.app"
if [[ ! -d "$APP_PATH" ]]; then
  die "Build succeeded but the app bundle is missing at:
  $APP_PATH
Look under $APPLETV_DIR/.build-xcode/Build/Products/ for where Xcode actually put it."
fi
note "Built app: $APP_PATH"

# --- 7. Install + launch on the Apple TV ------------------------------------

if [[ "${APP_ONLY:-0}" == "1" ]]; then
  note "APP_ONLY=1 — skipping device install/launch. Done."
  exit 0
fi

if [[ -z "${TX10_ATV_ID:-}" ]]; then
  echo "TX10_ATV_ID is not set. Known devices:" >&2
  xcrun devicectl list devices >&2 || true
  die "Pick the Apple TV row above, copy its Identifier (UUID), then:
  export TX10_ATV_ID=<identifier>
and rerun. If no Apple TV is listed, pair it once: Apple TV Settings >
Remotes and Devices > Remote App and Devices, then Xcode > Window >
Devices and Simulators > Pair (both on the same LAN). To build without a
device, rerun with APP_ONLY=1."
fi

note "Installing to Apple TV $TX10_ATV_ID"
xcrun devicectl device install app --device "$TX10_ATV_ID" "$APP_PATH" \
  || die "Install failed. Check that the Apple TV is on, on the same LAN, paired
(Xcode > Window > Devices and Simulators), and running tvOS 17+
(devicectl cannot talk to older tvOS). Then rerun this script."

note "Launching $BUNDLE_ID"
xcrun devicectl device process launch --device "$TX10_ATV_ID" "$BUNDLE_ID" \
  || die "Install succeeded but launch failed. If this is the first install,
launch it once from the Apple TV home screen (accept any developer trust
prompt), then rerun. Otherwise retry:
  xcrun devicectl device process launch --device $TX10_ATV_ID $BUNDLE_ID"

note "Done: $BUNDLE_ID is installed and running on $TX10_ATV_ID."
