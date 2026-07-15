# Strict Clock Configuration Contract

A versioned, strict, bounded, and deterministic host-side contract for the
tx10-clock configuration surface. It exists so the app's configuration can be
authored, validated, and canonicalized entirely on a host — **before** any
Android runtime integration — and can never carry an endpoint, serial, secret,
private path, or device identity into the repository.

> Scope: this is the **host-side contract** for the configuration surface. It
> is behavioural only and encodes **no** visual geometry, colours, typography,
> or asset tokens; those belong to the separate, immutable visual package.

## Layout

- `schema/config-v1.schema.json` — JSON Schema (draft 2020-12) for contract
  version `1.0.0`. Generated from the validator via `--emit-schema`; a test
  fails if the two ever drift.
- `fixtures/valid/` — deterministic positive fixtures.
- `fixtures/invalid/` — deterministic negative fixtures, one per rejection
  class.

The validator and tests live in
[`tools/config-validator/`](../tools/config-validator/).

## Contract summary (v1.0.0)

The document is a single JSON object. Every field at every level is required
and unknown fields are rejected.

| Section | Field | Type | Notes |
|---|---|---|---|
| — | `schemaVersion` | string | Must be `"1.0.0"`. |
| `clock` | `hourCycle` | enum | `"h12"` or `"h24"` (12-/24-hour readout). |
| `clock` | `showSeconds` | boolean | Show the seconds field / second hand. |
| `clock` | `showDate` | boolean | Show the Sunday-first 7×6 hybrid calendar, including muted adjacent-month dates, below the digital clock. |
| `clock` | `analogSweep` | enum | `"smooth"` sweep or `"tick"`. |
| `locale` | `policy` | enum | `"device"` or `"fixed"`. |
| `locale` | `tag` | string \| null | BCP-47 tag; set exactly when policy is `fixed`. |
| `timeZone` | `policy` | enum | `"device"` or `"fixed"`. |
| `timeZone` | `id` | string \| null | IANA id; set exactly when policy is `fixed`. |
| `burnIn` | `shiftEnabled` | boolean | Enable periodic burn-in pixel shift. |
| `burnIn` | `shiftRadiusPx` | integer | `0`–`64`; must be ≥ 1 when enabled. |
| `burnIn` | `shiftIntervalSeconds` | integer | `1`–`86400`. |
| `runtime` | `bootStart` | boolean | Auto-start after device boot. |
| `runtime` | `keepScreenOn` | boolean | Hold the screen on while foreground. |
| `runtime` | `safeRefresh` | boolean | Use the conservative low-flicker refresh path. |

### Semantic rules

- `locale.tag` is a valid tag exactly when `locale.policy` is `fixed`, and
  `null` when it is `device`.
- `timeZone.id` is a valid id exactly when `timeZone.policy` is `fixed`, and
  `null` when it is `device`.
- `burnIn.shiftRadiusPx` must be at least `1` when `burnIn.shiftEnabled` is
  `true`.

These rules are encoded in the schema as draft-2020-12 `if`/`then`/`else`
conditions, so a standard schema validator rejects the same negatives the
CLI does — schema-based authoring cannot produce a document that then fails at
ingestion.

### Fail-closed ingestion

A document is accepted only if it passes every check. Everything else is
rejected with a nonzero exit and a machine-readable reason:

- **Oversized** — larger than 8 KiB (bounded before parsing).
- **Malformed** — not strict JSON, duplicate object keys, non-finite numbers
  (`NaN`/`Infinity`), or nesting deeper than the depth bound (rejected before
  any recursive traversal, so a compact deep document fails closed instead of
  exhausting the recursion stack).
- **Unreadable** — a file or stdin that cannot be read, or whose bytes are not
  valid UTF-8, is reported as an `io_error` with exit code `2`.
- **Not an object**, **unknown field**, **missing field**, or **wrong type**.
- **Out of range** — a burn-in radius/interval or over-long string outside its
  bound.
- **Unsafe locale/time-zone input** — a tag or id that is not a strict BCP-47 /
  IANA shape (path traversal, separators, spaces, and control characters can
  never masquerade as one).
- **Hygiene violation** — any string value matching a private endpoint, local
  absolute path, or credential pattern. The validator never echoes the
  offending value into its output.

The `timeZone.id` is validated for shape only; the runtime resolves it against
the platform zone database. This keeps the contract deterministic and free of
any bundled time-zone database.

## Validating a configuration

```sh
python3 tools/config-validator/validate_config.py path/to/config.json
```

Prints a stable machine-readable JSON verdict on stdout. Exit codes: `0`
valid, `1` invalid, `2` usage/IO error.

## Canonical, public-safe output

```sh
python3 tools/config-validator/validate_config.py --canonicalize path/to/config.json
```

On an accepted document this prints the canonical form: sorted keys, normalized
locale-tag casing (e.g. `en-us` → `en-US`), and normalized time-zone-id casing
(e.g. `america/new_york` → `America/New_York`). Canonicalization is idempotent
and order-independent, so any two documents with the same accepted content
produce byte-identical output. Because the schema admits only enums, bounded
integers, booleans, and strict-shape locale/zone strings, the output cannot
contain an endpoint, serial, secret, private path, or device identity — and the
validator's hygiene scan is additionally run over it.

Zone-id casing is normalized on a best-effort basis (title-casing lower/mixed
words, preserving all-caps abbreviations such as `UTC` and `GMT+5`) without
consulting any bundled time-zone database; the runtime resolves the id against
the platform database.

## Running the host-only tests

```sh
bash tools/config-validator/run-config-tests.sh
```

Needs only Python 3 and bash — no Android SDK, network, signing key, or
device.
