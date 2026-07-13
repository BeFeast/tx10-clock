#!/usr/bin/env python3
"""Strict, host-only validator for the TX10 Clock configuration contract.

This is a pure Python 3 standard-library tool. It requires no Android SDK, no
network, no device, no signing material, and no third-party packages. Its single
source of truth is the versioned JSON Schema at
``config/schema/clock-config.v1.schema.json``; on top of the schema it applies
fail-closed passes that plain JSON Schema does not express:

  * an input byte-size ceiling (oversized documents are rejected before parsing),
  * duplicate-key detection at every object level,
  * rejection of ``NaN`` / ``Infinity`` / ``-Infinity`` (both the JSON literal
    tokens and non-finite floats produced by overflowing numeric literals).

On success it emits a deterministic canonical JSON document: every optional field
is filled from, and validated against, the schema defaults (including the approved
12-hour display default), keys are sorted, and the encoding is compact and
ASCII-only, so identical meaning always produces identical bytes. Because the
schema forbids unknown fields and constrains every value, the canonical output
structurally cannot carry an endpoint, serial, secret, private path, or device
identity.

Library entry points:
    validate(raw: bytes) -> dict          # normalized document (defaults filled)
    canonicalize(document: dict) -> str    # deterministic canonical JSON text
    validate_to_canonical(raw: bytes) -> str

CLI:
    clock_config_validator.py [PATH]       # PATH, or '-'/omitted for stdin
        exit 0  -> canonical JSON on stdout
        exit 1  -> validation error on stderr
        exit 2  -> usage error on stderr
"""

from __future__ import annotations

import json
import math
import re
import sys
from copy import deepcopy
from pathlib import Path

# Largest configuration document we will even attempt to parse. A well-formed
# document is well under 1 KiB; this bounds pathological input without being
# tight enough to reject a reasonable hand-written config.
MAX_DOCUMENT_BYTES = 16 * 1024

# The versioned schema is the single source of truth. It lives two directories
# up from this file: <repo>/config/schema, while this file is <repo>/tools/
# config-validator. Resolving relative to __file__ keeps the tool checkout-
# location independent and free of any hardcoded host path.
SCHEMA_PATH = (
    Path(__file__).resolve().parents[2] / "config" / "schema" / "clock-config.v1.schema.json"
)


class ConfigValidationError(ValueError):
    """A configuration document failed the contract.

    ``pointer`` is a JSON Pointer (RFC 6901) to the offending location, or an
    empty string for whole-document problems. Messages describe the rule that
    was violated; they never echo secrets because the contract admits none.
    """

    def __init__(self, message: str, pointer: str = "") -> None:
        self.pointer = pointer
        where = pointer if pointer else "<document>"
        super().__init__(f"{where}: {message}")


def load_schema() -> dict:
    """Load and return the versioned contract schema as a dict."""
    with SCHEMA_PATH.open("rb") as handle:
        return json.loads(handle.read().decode("utf-8"))


# --------------------------------------------------------------------------- #
# Parsing with fail-closed hooks
# --------------------------------------------------------------------------- #

def _reject_constant(token: str) -> None:
    # json calls this for the non-standard literals NaN, Infinity, -Infinity.
    raise ConfigValidationError(f"non-finite number literal is not allowed: {token}")


def _no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ConfigValidationError(f"duplicate key: {key!r}")
        result[key] = value
    return result


def _reject_nonfinite(value, pointer: str = "") -> None:
    # Overflowing numeric literals (e.g. 1e400) parse to float('inf') without
    # going through parse_constant, so walk the tree and reject any non-finite
    # float wherever it appears.
    if isinstance(value, float) and not math.isfinite(value):
        raise ConfigValidationError("non-finite number is not allowed", pointer)
    if isinstance(value, dict):
        for key, sub in value.items():
            _reject_nonfinite(sub, f"{pointer}/{key}")
    elif isinstance(value, list):
        for index, sub in enumerate(value):
            _reject_nonfinite(sub, f"{pointer}/{index}")


def _parse(raw: bytes):
    if len(raw) > MAX_DOCUMENT_BYTES:
        raise ConfigValidationError(
            f"document is {len(raw)} bytes; limit is {MAX_DOCUMENT_BYTES} bytes"
        )
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ConfigValidationError(f"input is not valid UTF-8: {exc}") from exc
    try:
        parsed = json.loads(
            text,
            parse_constant=_reject_constant,
            object_pairs_hook=_no_duplicate_keys,
        )
    except ConfigValidationError:
        raise
    except json.JSONDecodeError as exc:
        raise ConfigValidationError(f"input is not valid JSON: {exc}") from exc
    _reject_nonfinite(parsed)
    return parsed


# --------------------------------------------------------------------------- #
# Schema evaluation (a deterministic evaluator for the subset of JSON Schema
# keywords this contract uses). Unknown properties are always rejected; the
# schema declares additionalProperties:false on every object for documentation.
# --------------------------------------------------------------------------- #

def _normalize(value, schema: dict, pointer: str):
    schema_type = schema.get("type")

    if schema_type == "object":
        return _normalize_object(value, schema, pointer)
    if schema_type == "integer":
        return _normalize_integer(value, schema, pointer)
    if schema_type == "boolean":
        return _normalize_boolean(value, schema, pointer)
    if schema_type == "string":
        return _normalize_string(value, schema, pointer)
    # The contract only uses the types above; anything else is a schema bug.
    raise ConfigValidationError(f"unsupported schema type: {schema_type!r}", pointer)


def _normalize_object(value, schema: dict, pointer: str):
    if not isinstance(value, dict):
        raise ConfigValidationError(f"expected an object, got {_typename(value)}", pointer)

    properties = schema.get("properties", {})

    for key in value:
        if key not in properties:
            raise ConfigValidationError(f"unknown field: {key!r}", pointer)

    for required in schema.get("required", []):
        if required not in value:
            raise ConfigValidationError(f"missing required field: {required!r}", pointer)

    result = {}
    for name, subschema in properties.items():
        child_pointer = f"{pointer}/{name}"
        if name in value:
            result[name] = _normalize(value[name], subschema, child_pointer)
        elif "default" in subschema:
            # Defaults are data too: run them through the same type/enum/range
            # checks as user input so a malformed schema cannot silently emit
            # an invalid canonical document.
            result[name] = _normalize(
                deepcopy(subschema["default"]), subschema, child_pointer
            )
        elif subschema.get("type") == "object":
            # Fill an absent optional sub-object with its own defaults so the
            # canonical output is always the same fully-populated shape.
            result[name] = _normalize({}, subschema, child_pointer)
        # else: an optional scalar with no default is simply omitted. The only
        # field with no default (schemaVersion) is required, so it is present.
    return result


def _normalize_integer(value, schema: dict, pointer: str):
    # bool is a subclass of int; reject it explicitly so `true` never counts.
    if isinstance(value, bool) or not isinstance(value, int):
        raise ConfigValidationError(f"expected an integer, got {_typename(value)}", pointer)
    if "const" in schema and value != schema["const"]:
        raise ConfigValidationError(f"must equal {schema['const']}", pointer)
    if "minimum" in schema and value < schema["minimum"]:
        raise ConfigValidationError(f"must be >= {schema['minimum']}", pointer)
    if "maximum" in schema and value > schema["maximum"]:
        raise ConfigValidationError(f"must be <= {schema['maximum']}", pointer)
    return value


def _normalize_boolean(value, schema: dict, pointer: str):
    if not isinstance(value, bool):
        raise ConfigValidationError(f"expected a boolean, got {_typename(value)}", pointer)
    return value


def _normalize_string(value, schema: dict, pointer: str):
    if not isinstance(value, str):
        raise ConfigValidationError(f"expected a string, got {_typename(value)}", pointer)
    if "enum" in schema and value not in schema["enum"]:
        allowed = ", ".join(repr(option) for option in schema["enum"])
        raise ConfigValidationError(f"must be one of: {allowed}", pointer)
    if "maxLength" in schema and len(value) > schema["maxLength"]:
        raise ConfigValidationError(
            f"is longer than {schema['maxLength']} characters", pointer
        )
    if "pattern" in schema and re.fullmatch(schema["pattern"], value) is None:
        raise ConfigValidationError("does not match the allowed pattern", pointer)
    return value


def _typename(value) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, int):
        return "integer"
    if isinstance(value, float):
        return "number"
    if isinstance(value, str):
        return "string"
    if isinstance(value, list):
        return "array"
    if isinstance(value, dict):
        return "object"
    return type(value).__name__


# --------------------------------------------------------------------------- #
# Public API
# --------------------------------------------------------------------------- #

def validate(raw: bytes, schema: dict | None = None) -> dict:
    """Validate raw bytes against the contract and return the normalized dict.

    Raises ConfigValidationError on any violation.
    """
    if schema is None:
        schema = load_schema()
    parsed = _parse(raw)
    return _normalize(parsed, schema, "")


def canonicalize(document: dict) -> str:
    """Serialize a normalized document to deterministic canonical JSON text."""
    return json.dumps(
        document, sort_keys=True, separators=(",", ":"), ensure_ascii=True
    ) + "\n"


def validate_to_canonical(raw: bytes, schema: dict | None = None) -> str:
    """Validate raw bytes and return the canonical JSON text."""
    return canonicalize(validate(raw, schema))


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #

_USAGE = "usage: clock_config_validator.py [PATH]   (PATH, or '-'/omitted, reads stdin)"


def main(argv: list[str]) -> int:
    args = argv[1:]
    if args and args[0] in ("-h", "--help"):
        print(_USAGE)
        return 0
    if len(args) > 1:
        print(_USAGE, file=sys.stderr)
        return 2

    source = args[0] if args else "-"
    try:
        if source == "-":
            raw = sys.stdin.buffer.read()
        else:
            raw = Path(source).read_bytes()
    except OSError as exc:
        print(f"cannot read input: {exc}", file=sys.stderr)
        return 2

    try:
        canonical = validate_to_canonical(raw)
    except ConfigValidationError as exc:
        print(f"invalid configuration: {exc}", file=sys.stderr)
        return 1

    sys.stdout.write(canonical)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
