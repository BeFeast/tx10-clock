#!/usr/bin/env bash
#
# Proves the external-configuration core is renderer-agnostic: it must encode no
# visual contract from the (unaccepted) visual package — no colours, drawing
# APIs, typography, or geometry. The visual contract, geometry, type, colours,
# assets, and screenshot tolerances are owned by a separate issue and must not
# leak into this configuration slice.
#
# Only the configuration MODEL/transport sources are scanned (not the renderer,
# and not the tests — the tests intentionally feed rejected visual-looking keys
# like "faceColor" as negative cases).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BASE="app/src/main/java/com/befeast/tx10clock"
FILES=(
    "$BASE/ExternalConfig.java"
    "$BASE/Json.java"
    "$BASE/ConfigException.java"
    "$BASE/ConfigStore.java"
)

# Visual-decision tokens that must never appear in the config core: packed
# 0xAARRGGBB or #RRGGBB colours, Android drawing/type classes, and geometry or
# colour field names owned by the renderer.
forbidden='0x[0-9A-Fa-f]{6,8}|#[0-9A-Fa-f]{3,8}\b|\b(Paint|Canvas|Typeface|Bitmap|Drawable)\b|\b(drawColor|setColor|strokeWidth|setTextSize|faceColor|tickColor|backgroundColor|hourHandColor|minuteHandColor|secondHandColor|digitalColor|dateColor)\b'

fail=0
for f in "${FILES[@]}"; do
    if [ ! -f "$f" ]; then
        echo "check-config-renderer-agnostic: FAIL — missing $f" >&2
        fail=1
        continue
    fi
    hits="$(grep -EnI "$forbidden" "$f" || true)"
    if [ -n "$hits" ]; then
        echo "check-config-renderer-agnostic: FAIL — visual token in $f:" >&2
        printf '%s\n' "$hits" >&2
        fail=1
    fi
done

if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "check-config-renderer-agnostic: PASS — config core carries no visual decisions"
