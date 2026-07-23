# Approval-gated TX10 delivery

[`scripts/deliver.sh`](../scripts/deliver.sh) is the stable delivery entrypoint
for the exact merged delivery commit. It is intentionally unusable from an
ordinary checkout: live execution requires a committed release lock plus the
private approval, target, config, tool, and durable-state inputs supplied by the
approved runtime environment.

The script never discovers a live target from repository data or command-line
arguments, and its JSON receipt never contains the target serial/endpoint,
local paths, command lines, command output, config content, screenshots, or raw
rollback details. A salted non-reversible target fingerprint is the only target
identifier in the receipt.

## Public release lock

Live delivery reads only `delivery/release-lock.json` from the exact checked-out
commit. The document must satisfy
[`schema/release-lock-v1.schema.json`](schema/release-lock-v1.schema.json) and
pin all of the following:

- repository and release tag (`BeFeast/tx10-clock`, `v0.1.0`),
- the release source commit and exact tag-ref SHA,
- the signed APK name, byte size, and SHA-256,
- package id, `versionName`, and `versionCode`,
- signing-certificate SHA-256, and
- the SHA-256 of the release's `release-evidence.json`.

The live path resolves the public GitHub Release and tag again, downloads only
the two locked assets, validates the existing release-evidence contract, checks
the archive for native code, and independently verifies manifest and signing
identity with pinned Build Tools 36.0.0. It rejects draft/prerelease assets,
metadata drift, digest drift, or a release lock that is not committed at `HEAD`.

There is deliberately no speculative production lock in the repository before
the signed `v0.1.0` GitHub Release exists. Missing `delivery/release-lock.json`
is a hard live-delivery failure; the file must be populated from the published
release evidence and reviewed in the final exact-SHA delivery commit. A lock can
be checked without running delivery:

```sh
python3 tools/delivery/deliver.py \
  --validate-release-lock delivery/release-lock.json
```

## Private runtime contract

The approved runtime supplies these values without placing them in git:

| Variable | Purpose |
|---|---|
| `TX10_ADB_TARGET` | Canonical TX10 serial/endpoint. Required; no autodetection. |
| `TX10_ADB` | Approved `adb` executable (otherwise `adb` from `PATH`). |
| `TX10_DELIVERY_APPROVAL_FILE` | One Oleg approval assertion for receipt `872`. |
| `TX10_DELIVERY_CONFIG` | Exact app external `config.json`; its byte digest is approval-bound. |
| `TX10_DELIVERY_STATE_DIR` | Absolute, private, owner-only durable claim/evidence directory. |
| `TX10_GH` | Approved `gh` executable (otherwise `gh` from `PATH`). |
| `TX10_AAPT2` / `TX10_APKSIGNER` | Approved Build Tools executables, or resolve from `ANDROID_SDK_ROOT`. |

The approval assertion is strict JSON with exactly these fields:

```json
{
  "schema_version": "1.0.0",
  "receipt_ref": "872",
  "approval_id": "operator-generated-unique-id",
  "generation": 1,
  "approved_by": "oleg",
  "approved_at": "2026-07-23T10:00:00Z",
  "mode": "live",
  "delivery_sha": "40-lowercase-hex-commit-sha",
  "release_lock_sha256": "64-lowercase-hex-digest",
  "config_sha256": "64-lowercase-hex-digest"
}
```

For live execution, the script also resolves the current public `main` SHA and
requires it to equal both checked-out `HEAD` and the approved delivery SHA. All
static validation and the read-only ADB preflight finish before a claim is made.

## One-use claim and safe receipt

Immediately before device work, delivery atomically creates a claim directory
whose name is derived from the approval id, generation, and approval digest.
An existing directory rejects every replay—including a process interrupted
before it could finish—so one approval can create only one executing claim.
The claim file records only public-safe values:

- approval id/generation/timestamp and exact delivery SHA,
- release-lock and config digests,
- public release/tag/APK/certificate identity,
- target fingerprint and stage timestamps,
- safe stage exit codes and failure code,
- package/version/signature/installed-APK, foreground, screenshot, time/status,
  Home/Back, restart, reboot-autostart, and soak results,
- rollback result enums, and
- an explicitly separate `visual_acceptance` state, initially `pending`.

Screenshots plus prior APK/config copies are retained under the private claim
directory for later acceptance or rollback; their paths and bytes never enter
the receipt. A separate private evidence JSON binds the target fingerprint to
the prior APK/config hashes, package/version/signer identity, captured prior
foreground component, screenshot hashes, and rollback result enums. It still
contains no endpoint, filesystem path, config content, command output, or logs.

## Device sequence and failure policy

The live sequence is bounded by a 60-minute delivery deadline:

1. capture the prior foreground activity, package/version, installed APK and
   signer, and existing config without changing the device;
2. run `adb install -r` for the locked signed APK;
3. publish the approval-bound config by same-directory temporary push and rename;
4. prove package/version/signature and SHA-256 of the pulled installed APK;
5. start the clock and verify foreground, `status.json`, system-time freshness,
   effective config, timezone, and a valid PNG screenshot;
6. prove both Home and Back exit normally, then prove an explicit restart;
7. perform a normal reboot and require standard boot autostart plus a boot-status
   and screenshot check; and
8. hold a full 30-minute soak, sampling the same PID, foreground/status, app PID
   logcat, and package-specific system crash/ANR records.

Before `adb install -r` is invoked, failure leaves the package/config unchanged.
After install begins, the automated recovery path gets a separate bounded
five-minute window and may only:

- run `adb install -r` with the captured prior APK,
- atomically replace the config with the captured prior config, and
- start the captured prior foreground activity.

It never runs `adb uninstall`, never deletes a newly created package/config to
recreate prior absence, and never alters another installed clock. When a full
in-place restore is impossible, the receipt says destructive approval is still
required. Prior APK/config and screenshots remain private and durable.

## Harmless verification

The host-only suite runs the exact `scripts/deliver.sh` entrypoint with a fake
release, fake Build Tools, and a stateful fake ADB. It covers the complete
install/navigation/reboot/soak path, missing-target fail-closed behavior,
pre-install immutability, durable replay rejection, and post-install in-place
recovery. It never starts ADB or contacts a device or GitHub:

```sh
bash delivery/run-delivery-tests.sh
```
