# Release & Delivery Receipt Contract

A deterministic, public-safe, versioned record of one tx10-clock release
delivery. Later release/install automation must produce a receipt matching
this contract and validate it **before** making any delivery claim.

## Layout

- `schema/receipt-v1.schema.json` — JSON Schema (draft 2020-12) for contract
  version `1.0.0`. Generated from the validator via `--emit-schema`; a test
  fails if the two ever drift.
- `fixtures/valid/` — deterministic positive fixtures, one per legal
  delivery state (`built`, `published`, `delivered`, `rolled_back`).
- `fixtures/invalid/` — deterministic negative fixtures, one per rejection
  class (missing/unknown/type-invalid field, digest-format mismatch,
  impossible transitions, state inconsistencies, hygiene violations).

The validator and tests live in [`tools/receipt/`](../../tools/receipt/).

## Contract summary (v1.0.0)

Every field is required at every level; unknown fields are rejected.

| Section | Records |
|---|---|
| `source` | repository, 40-hex commit SHA, `vX.Y.Z` release tag |
| `artifact` | bare `.apk` filename, lowercase-hex SHA-256, size in bytes |
| `package` | Android application id, versionName, versionCode |
| `signing` | public SHA-256 certificate fingerprint reference only — never key material |
| `approval` | approving identity slug and UTC timestamp |
| `delivery` | current state plus full ordered history |
| `verification` | post-delivery verification state (`pending`/`passed`/`failed`) |
| `rollback` | reference (receipt id or tag) and single-line reason; non-null exactly when rolled back |

Delivery states advance strictly `built → published → delivered →
rolled_back` with no skips, repeats, or reversals, and history timestamps
must be non-decreasing. Verification may only pass/fail once delivery
reached `delivered` (or `rolled_back`).

Every string value is additionally screened against hygiene rules: local
absolute paths, private/LAN endpoints, and credential material are rejected,
and the validator never echoes an offending value into its output.

## Validating a receipt

```sh
python3 tools/receipt/validate_receipt.py path/to/receipt.json
```

Prints a stable machine-readable JSON verdict on stdout. Exit codes: `0`
valid, `1` invalid, `2` usage/IO error.

## Running the host-only tests

```sh
bash tools/receipt/run-receipt-tests.sh
```

Needs only Python 3 and bash — no Android SDK, network, signing key, or
device.
