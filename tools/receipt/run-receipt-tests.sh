#!/usr/bin/env bash
#
# run-receipt-tests.sh — host-only verification entrypoint for the release/
# delivery receipt contract.
#
# Runs from a clean checkout with nothing but Python 3 and git: no Android
# SDK, no network, no signing key, no device. Exercises the validator against
# every committed positive and negative fixture, runs the unit test suite,
# checks the committed schema has not drifted from the validator, and scans
# the receipt contract files for public-path hygiene.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PY="${PYTHON:-python3}"
VALIDATOR="tools/receipt/validate_receipt.py"
FIXTURES="release/receipt/fixtures"
SCHEMA="release/receipt/schema/receipt-v1.schema.json"

ok()  { printf 'PASS %s\n' "$*"; }
die() { printf 'FAIL %s\n' "$*" >&2; exit 1; }

command -v "$PY" >/dev/null 2>&1 || die "python3 not found (set PYTHON=...)"

# 1. Committed schema must be exactly what the validator emits.
if ! diff -u "$SCHEMA" <("$PY" "$VALIDATOR" --emit-schema) >/dev/null; then
    die "committed schema drifted from validator; regenerate with --emit-schema"
fi
ok "committed schema matches validator (--emit-schema)"

# 2. Every valid fixture must validate with exit 0.
for f in "$FIXTURES"/valid/*.json; do
    "$PY" "$VALIDATOR" "$f" >/dev/null \
        || die "valid fixture rejected: $f"
done
ok "all valid fixtures accepted"

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
"$PY" tools/receipt/test_validate_receipt.py \
    || die "receipt unit tests failed"
ok "receipt unit tests passed"

# 5. Public-path hygiene over the whole repo (includes the receipt files).
bash scripts/check-public-hygiene.sh >/dev/null \
    || die "public hygiene scan failed"
ok "public-path hygiene scan passed"

echo "run-receipt-tests: PASS — receipt contract, fixtures, tests, and hygiene all green"
