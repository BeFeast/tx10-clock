# ADB delivery preflight — dry-run contract

Before any approval-gated install is attempted, delivery automation must prove
that its target satisfies the install prerequisites without touching the
device. This directory holds that contract and its host-only verification
entrypoint; the implementation lives in [`tools/adb-preflight/`](../../tools/adb-preflight/).

## Contract

1. **Read-only by construction.** The preflight may query connection state,
   Android/API level, ABI, package and launcher state, storage, and
   clock/timezone. It cannot install, uninstall, push, pull, reboot, grant,
   start, write settings, or mutate configuration: every ADB invocation
   passes through a single allowlist gate (`assert_read_only`), mutating
   verbs are additionally deny-listed, and shell arguments are restricted to
   a metacharacter-free charset. There is no code path that reaches a
   mutating command.
2. **The live target never enters the repository or the report.** The device
   serial / endpoint is accepted only at runtime from the private execution
   environment (`TX10_ADB_TARGET`); it is rejected as a command-line
   argument. Reports and diagnostics identify the target only by a salted
   SHA-256 fingerprint (`tgt-…`), and a redactor rewrites every occurrence of
   the serial, endpoint, and adb binary path in any outbound text.
3. **Device output is untrusted.** Everything ADB returns is size-capped,
   stripped of control characters, and validated against expected formats
   before use; unparseable values fail the corresponding check rather than
   propagating.
4. **Deterministic, machine-readable outcome.** The tool prints one JSON
   report (schema `tx10-adb-preflight/v1`, sorted keys, fixed check order, no
   timestamps) and exits `0` only when every prerequisite is met, `1` when
   any check fails, `2` on usage/configuration errors.

## Checks

| id | pass condition |
|---|---|
| `adb-binary` | an adb binary is available (`TX10_ADB` or `PATH`) |
| `connection-state` | the target (or the single attached device) is in state `device` |
| `android-api` | `ro.build.version.sdk` ≥ 29 |
| `abi` | ABI list contains a supported ABI (`armeabi-v7a` first-class for the TX10) |
| `package-state` | package query succeeds; reports whether `com.befeast.tx10clock` is installed |
| `launcher-state` | the current HOME activity resolves |
| `storage` | free space on `/data` ≥ `TX10_PREFLIGHT_MIN_FREE_KIB` (default 64 MiB) |
| `clock-timezone` | device/host clock skew ≤ `TX10_PREFLIGHT_MAX_CLOCK_SKEW_SECONDS` (default 300 s) and a plausible timezone is set |

When the connection prerequisite is unmet, the device checks are reported as
`skip` — never silently omitted — and the exit code is nonzero.

## Verification

Host-only; injects a fake `adb` and requires no Android SDK, no licence
acceptance, no device, and no network:

```sh
bash delivery/preflight/run-preflight-tests.sh
```

The suite covers success (env target and single-device autodetect), offline /
unauthorized / multiple-device / no-device states, unsupported API level and
ABI, insufficient storage, clock skew, per-invocation timeout, malicious
device output, redaction, report determinism, and the read-only allowlist —
including an assertion that every command the tool actually executed
satisfies it.

This directory authorizes no live ADB call and no device mutation. Actual
installation remains a separate, operator-approved step outside this
repository's automation.
