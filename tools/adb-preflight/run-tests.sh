#!/usr/bin/env bash
# Run the host-only, fake-ADB preflight test suite from a clean checkout.
# No real device, no network, no Android SDK is required.
set -euo pipefail

# Resolve the repo root regardless of the caller's working directory.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

cd "${REPO_ROOT}"
exec python3 -m unittest discover -s delivery/preflight/tests -t . -v
