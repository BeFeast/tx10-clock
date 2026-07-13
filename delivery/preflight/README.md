# delivery/preflight

Library for the read-only ADB delivery preflight. See
[`tools/adb-preflight/README.md`](../../tools/adb-preflight/README.md) for the
CLI, usage, exit codes, and the full read-only contract.

Modules:

- `adb.py` — the read-only `AdbClient` and the `assert_readonly()` guard that
  rejects any mutating ADB invocation *before* a process is spawned.
- `redaction.py` — non-reversible target fingerprint + output redaction.
- `requirements.py` — delivery preconditions (defaults track `app/build.gradle`).
- `checks.py` — the individual read-only checks.
- `report.py` — the deterministic, machine-readable report.
- `preflight.py` — orchestration and readiness/exit-code logic.
- `runner.py` — the real subprocess adb runner (CLI only; tests inject a fake).
- `tests/` — fake-ADB unit tests.
