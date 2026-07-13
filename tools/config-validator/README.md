# config-validator

A strict, host-only validator for the TX10 Clock configuration contract. It is
pure Python 3 (standard library only): **no Android SDK, no network, no device,
no signing material, and no third-party packages.** It exists so the app
configuration surface is versioned, bounded, deterministic, and testable *before*
Android runtime integration.

Its single source of truth is the versioned schema at
[`config/schema/clock-config.v1.schema.json`](../../config/schema/clock-config.v1.schema.json).

## Run the tests

From a clean checkout:

```bash
tools/config-validator/run-tests.sh
```

The suite proves the positive fixtures validate and canonicalize
deterministically, and that **every** negative fixture fails closed with a
nonzero exit. It needs only `python3` on `PATH`.

## Validate a document

```bash
# From a file (canonical JSON on stdout, exit 0 on success):
python3 tools/config-validator/clock_config_validator.py path/to/config.json

# From stdin:
echo '{"schemaVersion": 1}' | python3 tools/config-validator/clock_config_validator.py -
```

Exit codes: `0` success (canonical JSON on stdout), `1` validation failure
(reason on stderr), `2` usage error.

## What it enforces

Beyond the structural schema (types, enums, ranges, `additionalProperties:
false`, required `schemaVersion == 1`), the validator adds fail-closed passes
that plain JSON Schema does not express:

| Case | Result |
| --- | --- |
| Unknown top-level or nested field | rejected |
| Duplicate keys (any object level) | rejected |
| Wrong type (incl. `true` as an integer, float for an integer) | rejected |
| Unsafe timezone/locale (path traversal, separators, injection, overlong) | rejected |
| Out-of-range shift radius / interval | rejected |
| `NaN` / `Infinity` / `-Infinity`, and overflow (`1e400`) | rejected |
| Document larger than the byte ceiling | rejected before parsing |

## Canonical output

On success the validator emits a **deterministic** canonical JSON document:
every optional field is filled from the schema defaults, keys are sorted, and the
encoding is compact and ASCII-only — identical meaning always yields identical
bytes, regardless of input key order or whitespace. Because the schema is closed
and every value is bounded or pattern-restricted, the canonical output
structurally cannot carry an endpoint, serial, secret, private path, or device
identity.

Defaults are validated through the same type/enum/range checks as supplied
values. In particular, a minimal `{"schemaVersion": 1}` document normalizes to
`display.hourCycle == "12h"`; `24h` remains a valid explicit override.

## Timezone / locale policy

`timeZone` and `language` each accept either a device-delegated policy token
(`auto` / `system`) or a shape-validated explicit value (an IANA-style zone id;
a BCP-47 language tag). Validation checks **safety and shape only** — it does not
ship a timezone or locale database, so existence is deferred to the Android
runtime. Anything with traversal, separators, whitespace, shell metacharacters,
or excess length is rejected.

## Layout

```
tools/config-validator/
├── clock_config_validator.py   # validator library + CLI
├── test_validator.py           # host-only unittest suite
├── run-tests.sh                # test entrypoint (python3 stdlib only)
└── fixtures/
    ├── valid/                  # positive fixtures
    └── invalid/                # negative fixtures (one fail-closed case each)
```
