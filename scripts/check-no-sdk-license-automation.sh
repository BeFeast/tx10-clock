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

# Positively assert every setup-android use disables both explicit licence
# acceptance and the action's default package install. setup-android supplies
# affirmative input to sdkmanager for every package in its `packages` input;
# omitting this input is therefore unsafe because its default is non-empty.
workflow_files="$(git ls-files '.github/workflows/*.yml' '.github/workflows/*.yaml')"
setup_count="$(printf '%s\n' "$workflow_files" | xargs -r grep -hEc \
    'uses:[[:space:]]*android-actions/setup-android@' | awk '{s += $1} END {print s + 0}')"
accept_false_count="$(printf '%s\n' "$workflow_files" | xargs -r grep -hEc \
    '^[[:space:]]+accept-android-sdk-licenses:[[:space:]]*false[[:space:]]*$' | awk '{s += $1} END {print s + 0}')"
empty_packages_count="$(printf '%s\n' "$workflow_files" | xargs -r grep -hEc \
    "^[[:space:]]+packages:[[:space:]]*(''|\"\")[[:space:]]*$" | awk '{s += $1} END {print s + 0}')"

if [ "$setup_count" -eq 0 ]; then
    echo "check-no-sdk-license-automation: FAIL — no setup-android action found" >&2
    fail=1
elif [ "$accept_false_count" -ne "$setup_count" ]; then
    echo "check-no-sdk-license-automation: FAIL — every setup-android use must set accept-android-sdk-licenses: false" >&2
    fail=1
fi

if [ "$empty_packages_count" -ne "$setup_count" ]; then
    echo "check-no-sdk-license-automation: FAIL — every setup-android use must set packages: '' to disable its unsafe default" >&2
    fail=1
fi

nonempty_packages="$(printf '%s\n' "$workflow_files" | xargs -r grep -En \
    '^[[:space:]]+packages:' | grep -Ev ":.*packages:[[:space:]]*(''|\"\")[[:space:]]*$" || true)"
if [ -n "$nonempty_packages" ]; then
    echo "check-no-sdk-license-automation: FAIL — setup-android package input must be empty:" >&2
    printf '%s\n' "$nonempty_packages" >&2
    fail=1
fi

if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "check-no-sdk-license-automation: PASS — no automated SDK licence acceptance"
