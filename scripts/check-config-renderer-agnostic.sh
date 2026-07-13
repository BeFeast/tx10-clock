#!/usr/bin/env bash
#
# Proves the external-configuration core carries no visual VALUES: no packed or
# hex colour literals, no drawing/typography APIs, and no renderer-owned
# geometry fields. The config may select among approved colour-role NAMES and
# bounded size/burn-in numbers, but resolving a name to an actual colour value
# is exclusively the renderer mapping's job (ClockConfig.fromExternal), and the
# accepted contract's values must never appear in the config core itself.
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

# Visual-VALUE tokens that must never appear in the config core: packed
# 0xAARRGGBB or #RRGGBB colour literals, Android drawing/type classes, and
# renderer-only paint/geometry fields. The approved colour-role NAME keys
# (digitalColor/dateColor/tickColor/accentColor) are deliberately allowed —
# they carry no colour value; resolution happens in the renderer mapping.
forbidden='0x[0-9A-Fa-f]{6,8}|#[0-9A-Fa-f]{3,8}\b|\b(Paint|Canvas|Typeface|Bitmap|Drawable)\b|\b(drawColor|setColor|strokeWidth|setTextSize|faceColor|backgroundColor|hourHandColor|minuteHandColor|secondHandColor)\b'

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
