# adb-preflight

A host-side, **read-only** ADB delivery preflight for TX10 Clock. It proves that
the delivery automation's ADB preconditions and redaction rules are correct
**before** any approval-gated install is attempted.

- No device mutation. No install / uninstall / push / reboot / grant / start /
  `settings put`. Enforced *by construction* (see the guard below).
- No Android SDK download and no license acceptance.
- The live serial / endpoint is read only from the runtime environment, is never
  committed or echoed, and appears in reports only as a **non-reversible
  fingerprint**.
- Emits **deterministic**, machine-readable JSON and exits **nonzero** on any
  unmet prerequisite.

## Layout

| Path | Purpose |
| --- | --- |
| `delivery/preflight/` | Library: read-only ADB client + guard, checks, redaction, report. |
| `tools/adb-preflight/adb_preflight.py` | CLI entrypoint. |
| `delivery/preflight/tests/` | Fake-ADB unit tests (no real device, no network). |

## Usage

```sh
# Against the single connected device (JSON report to stdout):
python3 tools/adb-preflight/adb_preflight.py

# Target supplied by the private execution environment (never echoed):
ADB_PREFLIGHT_TARGET="$SERIAL" python3 tools/adb-preflight/adb_preflight.py --human
```

Target resolution order: `--target`, then `$ADB_PREFLIGHT_TARGET`, then
`$ANDROID_SERIAL`. With no target, exactly one device must be connected.

### Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Ready — every required check passed. |
| `1` | Not ready — at least one required precondition is unmet. |
| `2` | Usage / configuration error (e.g. bad requirements file, `adb` not found). |

### Options

| Flag | Default | Meaning |
| --- | --- | --- |
| `--target` | env | Device serial or `host:port`. Never echoed. |
| `--adb` | `adb` (or `$ADB_PREFLIGHT_ADB`) | Path to the adb binary. |
| `--requirements FILE` | — | JSON file of requirement overrides. |
| `--min-api N` | `29` | Minimum Android API level (app `minSdk`). |
| `--abi ABI` | any known ABI | Allowed device ABI (repeatable). The release APK has no native code. |
| `--min-free-mb N` | `64` | Minimum free MB on `/data`. |
| `--package ID` | `com.befeast.tx10clock` | Application id to probe. |
| `--timeout SEC` | `10` | Per-command adb timeout. |
| `--fingerprint-salt S` | env / built-in | Salt for the target fingerprint. |
| `--human` | off | Also print a redacted summary to stderr. |

## Checks

| id | required | Queries | Read-only ADB used |
| --- | --- | --- | --- |
| `connection` | yes | online + authorized, single/target device | `adb devices -l` |
| `api_level` | yes | API ≥ `min_api` | `getprop ro.build.version.sdk` |
| `abi` | yes | device ABI ∈ allowed | `getprop ro.product.cpu.abi[list]` |
| `storage` | yes | free `/data` ≥ minimum | `df -k /data` |
| `package_state` | no | already installed? | `pm path <pkg>` |
| `launcher_state` | no | current HOME launcher | `cmd package resolve-activity` |
| `clock_timezone` | no | device clock/timezone set | `date +%s`, `getprop persist.sys.timezone` |

## Read-only by construction

Every ADB call flows through `assert_readonly()` in `delivery/preflight/adb.py`
before a process is spawned. It allows only the `devices`, `get-state`,
`version`, and `shell` subcommands; for `shell` it allows only a small set of
query tools (`getprop`, `df`, `date`, `pm path/list`, `cmd package
resolve-activity`, ...), rejects every shell metacharacter, and specifically
refuses clock-setting forms such as `date -s`. A mutating argv is rejected with
`ReadOnlyViolation` and never reaches `adb`.

The tool assumes the device/endpoint is already reachable in the adb server
(established by the private execution environment). It does **not** run
`adb connect`, so it never mutates connection state.

## Tests

Tests inject a fake `adb` (no real device, no network, no SDK):

```sh
tools/adb-preflight/run-tests.sh
# or, from the repo root:
python3 -m unittest discover -s delivery/preflight/tests -t .
```
