#!/usr/bin/env bash
# Stable, approval-gated TX10 delivery entrypoint. Private target, credential,
# approval, state, config, and tool resolution is accepted only from the
# approved runtime environment; no endpoint or secret is embedded here.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "$ROOT/tools/delivery/deliver.py" "$@"
