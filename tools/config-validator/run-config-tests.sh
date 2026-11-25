#!/usr/bin/env bash
#
# run-config-tests.sh — host-only verification entrypoint for the strict
# clock-configuration contract.
#
# Runs from a clean checkout with nothing but Python 3 and git: no Android
# SDK, no network, no signing key, no device. It checks the committed schema
# has not drifted from the validator, exercises the validator against every
# committed positive and negative fixture, proves canonicalization is stable,
# runs the unit test suite, and scans the config contract files for public
# hygiene.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PY="${PYTHON:-python3}"
VALIDATOR="tools/config-validator/validate_config.py"
FIXTURES="config/fixtures"
SCHEMA="config/schema/config-v1.schema.json"

ok()  { printf 'PASS %s\n' "$*"; }
die() { printf 'FAIL %s\n' "$*" >&2; exit 1; }

command -v "$PY" >/dev/null 2>&1 || die "python3 not found (set PYTHON=...)"

# 1. Committed schema must be exactly what the validator emits.
if ! diff -u "$SCHEMA" <("$PY" "$VALIDATOR" --emit-schema) >/dev/null; then
    die "committed schema drifted from validator; regenerate with --emit-schema"
fi
ok "committed schema matches validator (--emit-schema)"

# 2. Every valid fixture must validate with exit 0, and its canonical form
#    must be stable across a re-canonicalization.
for f in "$FIXTURES"/valid/*.json; do
    "$PY" "$VALIDATOR" "$f" >/dev/null \
        || die "valid fixture rejected: $f"
    canon="$("$PY" "$VALIDATOR" --canonicalize "$f")"
    recanon="$(printf '%s' "$canon" | "$PY" "$VALIDATOR" --canonicalize -)"
    [ "$canon" = "$recanon" ] \
        || die "canonical form not stable for $f"
done
ok "all valid fixtures accepted; canonical form stable"

# 3. Every invalid fixture must fail with exit 1 (not 2 — they must be
#    readable, well-formed inputs rejected by the contract).
for f in "$FIXTURES"/invalid/*.json; do
    set +e
    "$PY" "$VALIDATOR" "$f" >/dev/null
    rc=$?
    set -e
    [ "$rc" -eq 1 ] || die "invalid fixture: expected exit 1, got $rc: $f"
done
ok "all invalid fixtures rejected with exit 1"

# 4. Full unit test suite.
"$PY" tools/config-validator/test_validate_config.py \
    || die "config validator unit tests failed"
ok "config validator unit tests passed"

# 5. Public-path hygiene over the whole repo (includes the config files).
bash scripts/check-public-hygiene.sh >/dev/null \
    || die "public hygiene scan failed"
ok "public-path hygiene scan passed"

echo "run-config-tests: PASS — config contract, fixtures, canonicalization, tests, and hygiene all green"
