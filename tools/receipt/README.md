# tools/receipt — host-only receipt validator

Validates TX10 Clock release/delivery receipts against the contract in
[`release/receipt/`](../../release/receipt/). Pure Python 3 standard library:
**no** third-party packages, Android SDK, network, signing key, or device, and
it mutates nothing.

## Files

- `receipt_validator.py` — the validation library (schema subset, digest formats,
  delivery state machine, cross-field invariants, and the public-safety hygiene
  scanner).
- `validate.py` — CLI that emits stable, machine-readable JSON.
- `test_receipt.py` — host-only test suite driving every fixture.
- `run-tests.sh` — the test entrypoint.

## Run the tests

```sh
./tools/receipt/run-tests.sh
# or:
python3 -m unittest -v test_receipt      # from tools/receipt/
```

The suite proves that every `fixtures/valid/` receipt passes, every
`fixtures/invalid/` receipt is rejected with its expected error code, the CLI's
exit codes and byte output are stable, no offending value is ever echoed, and the
shipped files carry no host path, operator marker, or secret.

## CLI usage

```sh
python3 tools/receipt/validate.py RECEIPT [RECEIPT ...]
python3 tools/receipt/validate.py --schema PATH RECEIPT ...
python3 tools/receipt/validate.py -            # read one receipt from stdin
```

Output is a single JSON object (keys sorted, errors ordered) — safe to diff,
pipe, or store. Hygiene findings report a field path and category only.

### Exit codes

| Code | Meaning |
|------|---------|
| `0` | every receipt is valid |
| `1` | at least one receipt failed validation |
| `2` | usage / I/O / JSON-parse error (nothing could be evaluated) |

### Example

```sh
$ python3 tools/receipt/validate.py release/receipt/fixtures/valid/planned.receipt.json
{
  "count": 1,
  "ok": true,
  "results": [ { "errors": [], "ok": true, "target": "..." } ],
  "schema_version": "1.0.0",
  "tool": "tx10-receipt-validate",
  "tool_version": "1.0.0"
}
```

## Error codes

`missing_field`, `unknown_field`, `type_invalid`, `enum_invalid`, `const_invalid`,
`pattern_invalid`, `length_invalid`, `range_invalid`, `digest_format_mismatch`,
`state_transition_invalid`, `state_invariant_invalid`, `hygiene_secret`,
`hygiene_credential`, `hygiene_private_endpoint`, `hygiene_absolute_path`,
plus the CLI-level `parse_error`, `io_error`, and `schema_load_error`.
