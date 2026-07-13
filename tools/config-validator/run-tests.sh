#!/usr/bin/env bash
#
# Host-only test entrypoint for the TX10 Clock configuration validator.
#
# Requires nothing but Python 3 (standard library only): no Android SDK, no
# network, no device, no signing material, and no third-party packages. Run it
# from a clean checkout to prove the contract is strict, bounded, and
# deterministic:
#
#   tools/config-validator/run-tests.sh
#
# Exits nonzero if any check fails.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

PY="${PYTHON:-python3}"
if ! command -v "$PY" >/dev/null 2>&1; then
    echo "run-tests: python3 not found on PATH" >&2
    exit 2
fi

exec "$PY" -m unittest -v test_validator
