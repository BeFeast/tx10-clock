# config/schema

Versioned configuration contract for the TX10 Clock app.

- [`clock-config.v1.schema.json`](clock-config.v1.schema.json) — the **v1**
  contract (JSON Schema 2020-12). It is the single source of truth for the app
  configuration surface: 12h/24h selection, digital seconds/date visibility,
  locale/timezone policy, smooth analog sweep, burn-in shift enable/radius/
  interval, and safe refresh/runtime booleans.

The schema deliberately carries **no** visual geometry or design tokens, **no**
endpoints, and **no** device identity. Numeric bounds (e.g. burn-in shift radius
and interval) are behavioural safety limits, not accepted product geometry.

## Versioning

`schemaVersion` is required and, for this file, must equal `1`. A future
breaking change ships as a new `clock-config.v2.schema.json` with
`schemaVersion == 2`; documents are never silently coerced across versions.

## Enforcement

The schema is enforced by the host-only validator in
[`tools/config-validator`](../../tools/config-validator), which adds fail-closed
passes JSON Schema does not express (duplicate keys, `NaN`/`Infinity`, an input
byte-size ceiling) and emits deterministic canonical output. Run its tests with:

```bash
tools/config-validator/run-tests.sh
```
