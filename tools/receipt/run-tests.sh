#!/usr/bin/env bash
# Host-only test entrypoint for the TX10 Clock release/delivery receipt contract.
#
# Requires only Python 3 (>= 3.8) from the standard library. It runs with no
# Android SDK, no network, no signing key, and no device, and mutates nothing.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required to run the receipt contract tests" >&2
  exit 2
fi

echo "== TX10 Clock receipt contract :: host-only tests =="
python3 -m unittest -v test_receipt
echo "== all fixtures and public-path hygiene checks passed =="
