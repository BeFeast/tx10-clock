# TX10 Clock — Release & Delivery Receipt Contract

A **receipt** is a deterministic, public-safe record of a single release/delivery
event for the TX10 Clock app. Later release and install automation validates a
receipt against this contract **before making any delivery claim** — so a claim
like "v1.2.0 is installed and verified" is only ever asserted against a receipt
that already passed validation.

This directory is host-only and self-contained. It carries **no** device data,
signing material, private endpoint, or absolute path — only public references.

- `schema/receipt.schema.json` — the versioned contract (JSON Schema 2020-12 subset).
- `fixtures/valid/` — receipts that must pass.
- `fixtures/invalid/` — receipts that must be rejected, one per rejection reason.
- `fixtures/expected.json` — maps each invalid fixture to the error code it must raise.

The validator, CLI, and host-only tests live in [`tools/receipt/`](../../tools/receipt/).

## Versioning

`schema_version` is pinned to **`1.0.0`**. Consumers reject any receipt whose
`schema_version` does not match a version they understand, so the contract can
evolve without silently mis-reading old or new receipts.

## Fields

Every field below is required unless noted. Unknown fields are rejected.

| Path | Meaning |
|------|---------|
| `schema_version` | Contract version, pinned to `1.0.0`. |
| `receipt_id` | Opaque, public identifier for this receipt. |
| `note` | *(optional)* Free-text note; still scanned by the hygiene layer. |
| `source.repo` | `owner/name` slug of the source repository. |
| `source.commit` | Source commit the build came from. |
| `source.ref` | Fully-qualified git ref, e.g. `refs/tags/v1.2.0`. |
| `release.tag` | Release tag, e.g. `v1.2.0`. |
| `release.name` | *(optional)* Human-readable release name. |
| `release.path` | *(optional)* Repo-relative release path; never an absolute endpoint. |
| `asset.filename` | Asset filename only (no path separators), `.apk` / `.aab`. |
| `asset.sha256` | SHA-256 digest of the asset. |
| `asset.size_bytes` | Asset size in bytes. |
| `package.id` | Application/package id, e.g. `com.befeast.tx10clock`. |
| `package.version_name` | Human-readable version name. |
| `package.version_code` | Monotonic integer version code. |
| `signing.certificate_fingerprint_sha256` | Public SHA-256 fingerprint **reference** of the signing certificate. |
| `signing.certificate_ref` | *(optional)* Opaque public label for the certificate. |
| `approval.approved_by` | Approver identity (role or public handle). |
| `approval.approved_at` | Approval timestamp (RFC 3339 UTC, `Z`). |
| `delivery.state` | Current delivery state (see state machine). |
| `delivery.previous_state` | State transitioned from; `null` only for the initial receipt. |
| `delivery.updated_at` | Delivery-state timestamp. |
| `verification.state` | `unverified` / `passed` / `failed`. |
| `verification.method` | *(optional)* `sha256-match` / `signature-match` / `boot-check` / `manual`. |
| `verification.checked_at` | *(optional)* Verification timestamp. |
| `rollback` | Rollback target object, or `null`. Non-null only when `delivery.state` is `rolled_back`. |

The signing field is deliberately a **fingerprint reference only**: a receipt
never carries a key, keystore, or certificate body.

## Delivery state machine

`delivery.previous_state → delivery.state` must be a legal edge. The initial
receipt (`previous_state: null`) may only enter `planned`.

```
(initial) → planned
planned   → published | failed
published → delivered | rolled_back | failed
delivered → installed | rolled_back | failed
installed → rolled_back | failed
failed    → published | rolled_back
rolled_back → published
```

## Cross-field invariants

- `rollback` is non-null **iff** `delivery.state` is `rolled_back`.
- `delivery.state == installed` requires `verification.state == passed` — an
  install is only claimed once verification has passed.

## Digest formats

- `asset.sha256` must be exactly 64 lowercase hex characters. A value of another
  digest length (e.g. an SHA-1) is reported as a digest-format mismatch.
- `signing.certificate_fingerprint_sha256` must be 32 colon-separated hex byte
  pairs (an Android-style SHA-256 fingerprint).

## Public-safety (hygiene)

Every string value is scanned. A receipt is rejected if any field contains:

- **raw secrets / credential material** — private-key or certificate blocks,
  JWTs, cloud access keys, GitHub/Slack tokens, bearer/basic auth material, or
  `keyword: value` credential assignments;
- **private endpoints** — loopback, private RFC 1918 ranges (`10/8`,
  `172.16/12`, `192.168/16`), link-local addresses, mDNS names, `.onion`
  hosts, or local file-scheme URIs;
- **local absolute paths** — POSIX home/system paths, Windows drive paths, or
  UNC paths.

Findings report the **field path and category only** — never the matched value —
so validator output is itself public-safe.

## Example (valid)

See [`fixtures/valid/planned.receipt.json`](fixtures/valid/planned.receipt.json)
for the initial state, and
[`fixtures/valid/installed-verified.receipt.json`](fixtures/valid/installed-verified.receipt.json)
for a fully delivered, installed, and verified release.
