#!/usr/bin/env bash
#
# run-preflight-tests.sh — host-only verification of the ADB delivery
# preflight and its dry-run contract.
#
# Injects the fake `adb` double from tools/adb-preflight/tests/; needs only a
# Python 3 standard library. No Android SDK download, no licence acceptance,
# no device, no network, and no live ADB call.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PYTHON="${PYTHON:-python3}"
command -v "$PYTHON" >/dev/null 2>&1 \
    || { echo "run-preflight-tests: python3 not found" >&2; exit 2; }

echo "==> Running adb-preflight fake-ADB test suite"
"$PYTHON" tools/adb-preflight/tests/test_adb_preflight.py

echo "run-preflight-tests: PASS — dry-run contract verified with a fake adb"
