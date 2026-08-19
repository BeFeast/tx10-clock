#!/usr/bin/env bash
#
# Pull the TX10 Clock status.json from an Apple TV and print it.
#
#   Usage: config-pull-status.sh [device-id]
#
# device-id defaults to $TX10_ATV_ID. Reads Library/Caches/status.json from the
# app data container into a temp file, prints its path, and cats it.

set -euo pipefail

BUNDLE_ID="com.befeast.tx10clock.tv"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

DEVICE="${1:-${TX10_ATV_ID:-}}"
[[ -n "$DEVICE" ]] || die "no device id: pass it as the 1st argument or export TX10_ATV_ID.
List devices with: xcrun devicectl list devices"

TMP_DIR="$(mktemp -d -t tx10-status)"
DEST="$TMP_DIR/status.json"

CMD=(xcrun devicectl device copy from
  --device "$DEVICE"
  --source "Library/Caches/status.json"
  --destination "$DEST"
  --domain-type appDataContainer
  --domain-identifier "$BUNDLE_ID")

if ! "${CMD[@]}"; then
  {
    echo "error: devicectl copy failed. Exact command that failed:"
    printf '  %q' "${CMD[@]}"; echo
    echo "If the file simply does not exist yet, launch the app once so it publishes status.json."
    echo "devicectl flag names have shifted between Xcode releases — compare against:"
    echo "  xcrun devicectl device copy from --help"
    echo "and adjust the flags in this script if your Xcode expects different names."
  } >&2
  exit 1
fi

echo "status.json pulled to: $DEST"
echo "---"
cat "$DEST"
echo
