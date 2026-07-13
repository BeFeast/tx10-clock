#!/usr/bin/env bash
#
# Proves no Android SDK licence acceptance is automated anywhere in the repo.
# Licence acceptance is a human/operator gate; the build must never run
# `sdkmanager --licenses`, pipe `yes` into it, or ask an action to accept
# licences on its behalf.
#
# This complements the CI gate, which runs android-actions/setup-android with
# `accept-android-sdk-licenses: false`.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SELF="scripts/check-no-sdk-license-automation.sh"

# Patterns that would indicate automated licence acceptance.
forbidden=(
    'sdkmanager[^\n]*--licenses'          # sdkmanager --licenses
    'yes[[:space:]]*\|[[:space:]]*sdkmanager'  # yes | sdkmanager ...
    'accept-android-sdk-licenses:[[:space:]]*true'
    'accept-android-sdk-licenses[[:space:]]*=[[:space:]]*true'
    '--accept-licenses'
)

fail=0
tracked="$(git ls-files | grep -vF "$SELF" || true)"

for pat in "${forbidden[@]}"; do
    hits="$(printf '%s\n' "$tracked" | tr '\n' '\0' \
        | xargs -0 -r grep -EnI "$pat" 2>/dev/null || true)"
    if [ -n "$hits" ]; then
        echo "check-no-sdk-license-automation: FAIL — licence automation /$pat/:" >&2
        printf '%s\n' "$hits" >&2
        fail=1
    fi
done

# Positively assert the CI SDK action keeps licence acceptance disabled.
CI=".github/workflows/ci.yml"
if [ -f "$CI" ]; then
    if ! grep -Eq 'accept-android-sdk-licenses:[[:space:]]*false' "$CI"; then
        echo "check-no-sdk-license-automation: FAIL — CI must set accept-android-sdk-licenses: false" >&2
        fail=1
    fi
fi

if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "check-no-sdk-license-automation: PASS — no automated SDK licence acceptance"
