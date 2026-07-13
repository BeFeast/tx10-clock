#!/usr/bin/env python3
"""Host-only CLI for validating TX10 Clock release/delivery receipts.

Usage:
    validate.py RECEIPT [RECEIPT ...]
    validate.py --schema PATH RECEIPT ...
    validate.py -            # read a single receipt from stdin

Output is stable, machine-readable JSON on stdout (keys sorted, errors ordered).
No secret, private endpoint, absolute path, or credential value is ever echoed:
hygiene findings report a field path and category only.

Exit codes:
    0  every receipt is valid
    1  at least one receipt failed validation
    2  usage / I/O / JSON-parse error (no receipt could be evaluated)
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import receipt_validator as rv


def _result_for(name, text, schema):
    """Return a (result_dict, ok, hard_error) tuple for one receipt document."""
    try:
        receipt = json.loads(text)
    except json.JSONDecodeError as exc:
        return (
            {
                "target": name,
                "ok": False,
                "errors": [
                    {
                        "code": "parse_error",
                        "path": "(root)",
                        "message": f"invalid JSON: {exc.msg} at line {exc.lineno} column {exc.colno}",
                    }
                ],
            },
            False,
            True,
        )

    errors = rv.validate_receipt(receipt, schema)
    result = {
        "target": name,
        "ok": not errors,
        "errors": [
            {"code": e.code, "path": e.path, "message": e.message} for e in errors
        ],
    }
    return result, not errors, False


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="validate.py",
        description="Validate TX10 Clock release/delivery receipts (host-only).",
    )
    parser.add_argument("receipts", nargs="+", metavar="RECEIPT",
                        help="receipt JSON file(s), or - for stdin")
    parser.add_argument("--schema", metavar="PATH", default=None,
                        help="override the schema path")
    args = parser.parse_args(argv)

    try:
        schema = rv.load_schema(args.schema)
    except OSError:
        _emit({"ok": False, "errors": [
            {
                "code": "schema_load_error",
                "path": "(schema)",
                "message": "could not read schema",
            }
        ]})
        return 2
    except UnicodeDecodeError:
        _emit({"ok": False, "errors": [
            {
                "code": "schema_load_error",
                "path": "(schema)",
                "message": "schema is not valid UTF-8",
            }
        ]})
        return 2
    except json.JSONDecodeError as exc:
        _emit({"ok": False, "errors": [
            {
                "code": "schema_load_error",
                "path": "(schema)",
                "message": (
                    f"invalid schema JSON at line {exc.lineno} column {exc.colno}"
                ),
            }
        ]})
        return 2

    results = []
    all_ok = True
    hard_error = False

    for name in args.receipts:
        display = "(stdin)" if name == "-" else os.path.basename(name)
        try:
            if name == "-":
                text = sys.stdin.read()
            else:
                with open(name, "r", encoding="utf-8") as fh:
                    text = fh.read()
        except OSError:
            results.append({
                "target": display,
                "ok": False,
                "errors": [{
                    "code": "io_error",
                    "path": "(file)",
                    "message": "could not read receipt",
                }],
            })
            all_ok = False
            hard_error = True
            continue
        except UnicodeDecodeError:
            results.append({
                "target": display,
                "ok": False,
                "errors": [{
                    "code": "decode_error",
                    "path": "(file)",
                    "message": "receipt is not valid UTF-8",
                }],
            })
            all_ok = False
            hard_error = True
            continue

        result, ok, hard = _result_for(display, text, schema)
        results.append(result)
        all_ok = all_ok and ok
        hard_error = hard_error or hard

    report = {
        "tool": "tx10-receipt-validate",
        "tool_version": "1.0.0",
        "schema_version": rv.SCHEMA_VERSION,
        "ok": all_ok,
        "count": len(results),
        "results": results,
    }
    _emit(report)

    if hard_error:
        return 2
    return 0 if all_ok else 1


def _emit(obj):
    json.dump(obj, sys.stdout, sort_keys=True, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    sys.exit(main())
