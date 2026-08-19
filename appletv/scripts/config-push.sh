#!/usr/bin/env bash
#
# Push a config.json to the TX10 Clock app data container on an Apple TV.
#
#   Usage: config-push.sh <config.json> [device-id]
#
# device-id defaults to $TX10_ATV_ID. The file lands at Library/Caches/config.json
# inside the app data container; the app reloads it on next foreground/resume and
# mirrors the accepted result into UserDefaults (Caches is purgeable).

set -euo pipefail

BUNDLE_ID="com.befeast.tx10clock.tv"
MAX_BYTES=8192

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

if [[ $# -lt 1 || $# -gt 2 ]]; then
  die "usage: config-push.sh <config.json> [device-id]
device-id defaults to \$TX10_ATV_ID (see: xcrun devicectl list devices)"
fi

SRC="$1"
DEVICE="${2:-${TX10_ATV_ID:-}}"

[[ -n "$DEVICE" ]] || die "no device id: pass it as the 2nd argument or export TX10_ATV_ID.
List devices with: xcrun devicectl list devices"
[[ -f "$SRC" ]] || die "not a readable file: $SRC"

SIZE="$(wc -c < "$SRC" | tr -d '[:space:]')"
if (( SIZE > MAX_BYTES )); then
  die "$SRC is ${SIZE} bytes; the app rejects any config over ${MAX_BYTES} bytes (8 KiB)."
fi

# Well-formedness pre-check only; the app's contract is stricter (unknown keys,
# duplicate keys, wrong types, out-of-range values all reject — see README).
if command -v python3 >/dev/null 2>&1; then
  python3 -m json.tool "$SRC" >/dev/null 2>&1 \
    || die "$SRC is not well-formed JSON — the app would reject it. Fix and retry."
fi

CMD=(xcrun devicectl device copy to
  --device "$DEVICE"
  --source "$SRC"
  --destination "Library/Caches/config.json"
  --domain-type appDataContainer
  --domain-identifier "$BUNDLE_ID")

if ! "${CMD[@]}"; then
  {
    echo "error: devicectl copy failed. Exact command that failed:"
    printf '  %q' "${CMD[@]}"; echo
    echo "devicectl flag names have shifted between Xcode releases — compare against:"
    echo "  xcrun devicectl device copy to --help"
    echo "and adjust the flags in this script if your Xcode expects different names."
  } >&2
  exit 1
fi

echo "Pushed $SRC (${SIZE} bytes) to ${BUNDLE_ID}:Library/Caches/config.json on $DEVICE."
echo "The app applies it on next resume; verify with: appletv/scripts/config-pull-status.sh"
