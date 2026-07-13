#!/usr/bin/env python3
"""tx10-clock release/delivery receipt validator.

Validates one release-receipt JSON document against the versioned receipt
contract and prints a stable, machine-readable JSON verdict on stdout.

The receipt contract is the public, deterministic record that later
release/install automation must produce and validate before making any
delivery claim. This tool is host-only and dependency-free: Python 3
standard library only — no network, no Android SDK, no signing material,
and no device access.

The committed schema file (release/receipt/schema/receipt-v1.schema.json)
is generated from SPEC below via `--emit-schema` and a test asserts the
two never drift apart; SPEC is the single source of truth.

Usage:
    validate_receipt.py <receipt.json | ->    validate a receipt document
    validate_receipt.py --emit-schema         print the JSON Schema

Exit codes:
    0  receipt is valid
    1  receipt is invalid (structural, semantic, hygiene, or JSON error)
    2  usage error or unreadable input
"""

import json
import re
import sys
from datetime import datetime

TOOL_NAME = "tx10-receipt-validate"
TOOL_VERSION = "1.0.0"
CONTRACT_NAME = "tx10-clock-release-receipt"
SUPPORTED_SCHEMA_VERSIONS = ("1.0.0",)
SCHEMA_ID = (
    "https://github.com/BeFeast/tx10-clock/blob/main/"
    "release/receipt/schema/receipt-v1.schema.json"
)

# --- Field formats -----------------------------------------------------------

SEMVER = r"^[0-9]+\.[0-9]+\.[0-9]+$"
RECEIPT_ID = r"^[a-z0-9][a-z0-9-]{7,63}$"
REPOSITORY = r"^[A-Za-z0-9_.-]{1,64}/[A-Za-z0-9_.-]{1,64}$"
COMMIT_SHA = r"^[0-9a-f]{40}$"
RELEASE_TAG = r"^v[0-9]+\.[0-9]+\.[0-9]+$"
APK_FILENAME = r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.apk$"
SHA256_LOWER_HEX = r"^[0-9a-f]{64}$"
APPLICATION_ID = r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$"
CERT_FINGERPRINT = r"^(?:[0-9A-F]{2}:){31}[0-9A-F]{2}$"
IDENTITY_SLUG = r"^[a-z0-9][a-z0-9._-]{1,63}$"
UTC_TIMESTAMP = r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"
ROLLBACK_REFERENCE = r"^(?:v[0-9]+\.[0-9]+\.[0-9]+|[a-z0-9][a-z0-9-]{7,63})$"
PRINTABLE_LINE = r"^[!-~](?:[ -~]{0,254}[!-~])?$"

DELIVERY_STATES = ("built", "published", "delivered", "rolled_back")
VERIFICATION_STATES = ("pending", "passed", "failed")

# The only legal delivery-state moves; anything else (skips, repeats,
# reversals) is an impossible transition.
ALLOWED_TRANSITIONS = frozenset(
    [("built", "published"), ("published", "delivered"), ("delivered", "rolled_back")]
)

SEMANTIC_RULES = (
    "delivery.history must start with state 'built'",
    "consecutive delivery.history entries must follow built -> published -> "
    "delivered -> rolled_back with no skips, repeats, or reversals",
    "delivery.history[*].at timestamps must be non-decreasing",
    "delivery.state must equal the final delivery.history entry's state",
    "verification.state 'passed' or 'failed' requires delivery.state "
    "'delivered' or 'rolled_back'",
    "verification.verified_at must be null exactly when verification.state "
    "is 'pending'",
    "rollback must be non-null exactly when delivery.state is 'rolled_back'",
)

ERROR_CODES = (
    "format_invalid",
    "hygiene_violation",
    "io_error",
    "json_invalid",
    "missing_field",
    "schema_version_unsupported",
    "state_invalid",
    "transition_invalid",
    "type_invalid",
    "unknown_field",
    "usage_error",
)

# --- Node constructors for SPEC ----------------------------------------------


def _string(pattern, description, timestamp=False, nullable=False):
    return {
        "kind": "string",
        "pattern": pattern,
        "description": description,
        "timestamp": timestamp,
        "nullable": nullable,
    }


def _integer(minimum, description):
    return {"kind": "integer", "minimum": minimum, "description": description}


def _enum(values, description):
    return {"kind": "enum", "values": tuple(values), "description": description}


def _array(items, description, min_items=1):
    return {
        "kind": "array",
        "items": items,
        "min_items": min_items,
        "description": description,
    }


def _object(properties, description, nullable=False):
    return {
        "kind": "object",
        "properties": properties,
        "description": description,
        "nullable": nullable,
    }


# Receipt contract v1. Every property at every level is required and no
# unknown properties are accepted anywhere.
SPEC = _object(
    {
        "schema_version": _string(
            SEMVER, "Receipt contract version this document claims to follow."
        ),
        "receipt_id": _string(
            RECEIPT_ID, "Stable lowercase slug identifying this receipt."
        ),
        "source": _object(
            {
                "repository": _string(
                    REPOSITORY, "GitHub owner/name the release was built from."
                ),
                "commit_sha": _string(
                    COMMIT_SHA, "Exact 40-hex source commit the artifact was built at."
                ),
                "release_tag": _string(
                    RELEASE_TAG, "Release tag (vMAJOR.MINOR.PATCH) for the artifact."
                ),
            },
            "Exact public source identity of the release.",
        ),
        "artifact": _object(
            {
                "filename": _string(
                    APK_FILENAME,
                    "Bare artifact filename (no directory separators).",
                ),
                "sha256": _string(
                    SHA256_LOWER_HEX,
                    "SHA-256 digest of the artifact, 64 lowercase hex chars.",
                ),
                "size_bytes": _integer(1, "Artifact size in bytes."),
            },
            "The delivered artifact and its digest.",
        ),
        "package": _object(
            {
                "application_id": _string(
                    APPLICATION_ID, "Android application id of the package."
                ),
                "version_name": _string(
                    SEMVER, "Package versionName (MAJOR.MINOR.PATCH)."
                ),
                "version_code": _integer(1, "Package versionCode."),
            },
            "Package/version identity carried by the artifact.",
        ),
        "signing": _object(
            {
                "certificate_sha256_fingerprint": _string(
                    CERT_FINGERPRINT,
                    "Public SHA-256 certificate fingerprint reference: 32 "
                    "colon-separated uppercase hex pairs. Never key material.",
                ),
            },
            "Reference to the signing certificate by public fingerprint only.",
        ),
        "approval": _object(
            {
                "approved_by": _string(
                    IDENTITY_SLUG, "Operator identity slug that approved delivery."
                ),
                "approved_at": _string(
                    UTC_TIMESTAMP,
                    "UTC approval timestamp (YYYY-MM-DDThh:mm:ssZ).",
                    timestamp=True,
                ),
            },
            "Who approved this delivery and when.",
        ),
        "delivery": _object(
            {
                "state": _enum(DELIVERY_STATES, "Current delivery state."),
                "history": _array(
                    _object(
                        {
                            "state": _enum(
                                DELIVERY_STATES, "Delivery state entered."
                            ),
                            "at": _string(
                                UTC_TIMESTAMP,
                                "UTC timestamp the state was entered.",
                                timestamp=True,
                            ),
                        },
                        "One delivery state change.",
                    ),
                    "Ordered delivery state history, starting at 'built'.",
                ),
            },
            "Delivery state and its full ordered history.",
        ),
        "verification": _object(
            {
                "state": _enum(
                    VERIFICATION_STATES, "Post-delivery verification state."
                ),
                "verified_at": _string(
                    UTC_TIMESTAMP,
                    "UTC verification timestamp, or null while pending.",
                    timestamp=True,
                    nullable=True,
                ),
            },
            "Post-delivery verification state.",
        ),
        "rollback": _object(
            {
                "reference": _string(
                    ROLLBACK_REFERENCE,
                    "Receipt id or release tag restored by the rollback.",
                ),
                "reason": _string(
                    PRINTABLE_LINE, "Single-line printable-ASCII rollback reason."
                ),
            },
            "Rollback record; null unless delivery.state is 'rolled_back'.",
            nullable=True,
        ),
    },
    "Deterministic, public-safe record of one tx10-clock release delivery.",
)

# --- Hygiene rules ------------------------------------------------------------
# Applied to every string value in the document. The regex sources are written
# so their literal text never matches this repository's public-hygiene scan.

HYGIENE_RULES = (
    (
        "local_absolute_path",
        re.compile(
            r"(?:/(?:home|Users|root|srv|opt|etc|var|tmp|mnt|media|private)/"
            r"|[A-Za-z]:\\|~/)"
        ),
    ),
    (
        "private_endpoint",
        re.compile(
            r"(?:\b(?:10|127)\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\b"
            r"|\b192\.168\.[0-9]{1,3}\.[0-9]{1,3}\b"
            r"|\b172\.(?:1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3}\b"
            r"|\blocalhost\b"
            r"|\.(?:local|lan|internal)\b)"
        ),
    ),
    (
        "credential_material",
        re.compile(
            r"(?:-----BEGIN[ A-Z]+PRIVATE KEY"
            r"|\bghp_[A-Za-z0-9]{8,}"
            r"|\bgithub_pat_[A-Za-z0-9_]{8,}"
            r"|\bxox[abprs]-[A-Za-z0-9-]{6,}"
            r"|\bAKIA[0-9A-Z]{8,}"
            r"|\bAIza[0-9A-Za-z_-]{10,}"
            r"|\bssh-(?:rsa|ed25519)\s+AAAA"
            r"|(?i:\bbearer\s+[a-z0-9._~+/=-]{8,})"
            r"|(?i:\b(?:api[_-]?key|access[_-]?key|secret|password|passwd"
            r"|token|credential)s?\s*[:=]))"
        ),
    ),
)

HYGIENE_CATEGORIES = tuple(name for name, _ in HYGIENE_RULES)

STRUCTURAL_CODES = frozenset(
    ["missing_field", "unknown_field", "type_invalid", "format_invalid"]
)


def _error(code, path, message):
    return {"code": code, "path": path, "message": message}


# --- Structural validation ------------------------------------------------


def _parse_utc(value):
    try:
        return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        return None


def _check_node(spec, value, path, errors):
    kind = spec["kind"]
    if value is None:
        if spec.get("nullable"):
            return
        errors.append(_error("type_invalid", path, "value must not be null"))
        return

    if kind == "string":
        if not isinstance(value, str):
            errors.append(_error("type_invalid", path, "value must be a string"))
            return
        if not re.match(spec["pattern"], value):
            errors.append(
                _error("format_invalid", path, "value does not match required format")
            )
            return
        if spec["timestamp"] and _parse_utc(value) is None:
            errors.append(
                _error("format_invalid", path, "value is not a valid UTC timestamp")
            )
    elif kind == "integer":
        if isinstance(value, bool) or not isinstance(value, int):
            errors.append(_error("type_invalid", path, "value must be an integer"))
            return
        if value < spec["minimum"]:
            errors.append(
                _error(
                    "format_invalid",
                    path,
                    "value must be >= %d" % spec["minimum"],
                )
            )
    elif kind == "enum":
        if not isinstance(value, str):
            errors.append(_error("type_invalid", path, "value must be a string"))
            return
        if value not in spec["values"]:
            errors.append(
                _error(
                    "format_invalid",
                    path,
                    "value must be one of: %s" % ", ".join(spec["values"]),
                )
            )
    elif kind == "array":
        if not isinstance(value, list):
            errors.append(_error("type_invalid", path, "value must be an array"))
            return
        if len(value) < spec["min_items"]:
            errors.append(
                _error(
                    "format_invalid",
                    path,
                    "array must have at least %d item(s)" % spec["min_items"],
                )
            )
        for i, item in enumerate(value):
            _check_node(spec["items"], item, "%s[%d]" % (path, i), errors)
    elif kind == "object":
        if not isinstance(value, dict):
            errors.append(_error("type_invalid", path, "value must be an object"))
            return
        properties = spec["properties"]
        for name in properties:
            child = "%s.%s" % (path, name)
            if name not in value:
                errors.append(
                    _error("missing_field", child, "required field is missing")
                )
            else:
                _check_node(properties[name], value[name], child, errors)
        for name in value:
            if name not in properties:
                errors.append(
                    _error(
                        "unknown_field",
                        "%s.%s" % (path, name),
                        "field is not part of the receipt contract",
                    )
                )


# --- Semantic validation ----------------------------------------------------


def _check_semantics(doc, errors):
    delivery = doc["delivery"]
    verification = doc["verification"]
    rollback = doc["rollback"]
    history = delivery["history"]
    state = delivery["state"]

    if history[0]["state"] != "built":
        errors.append(
            _error(
                "transition_invalid",
                "$.delivery.history[0].state",
                "delivery history must start with state 'built'",
            )
        )
    for i in range(1, len(history)):
        step = (history[i - 1]["state"], history[i]["state"])
        if step not in ALLOWED_TRANSITIONS:
            errors.append(
                _error(
                    "transition_invalid",
                    "$.delivery.history[%d]" % i,
                    "impossible delivery state transition '%s' -> '%s'" % step,
                )
            )
        if _parse_utc(history[i]["at"]) < _parse_utc(history[i - 1]["at"]):
            errors.append(
                _error(
                    "transition_invalid",
                    "$.delivery.history[%d].at" % i,
                    "history timestamps must be non-decreasing",
                )
            )
    if history[-1]["state"] != state:
        errors.append(
            _error(
                "state_invalid",
                "$.delivery.state",
                "delivery.state must equal the final history entry's state",
            )
        )
    if verification["state"] in ("passed", "failed") and state not in (
        "delivered",
        "rolled_back",
    ):
        errors.append(
            _error(
                "state_invalid",
                "$.verification.state",
                "verification requires delivery.state 'delivered' or 'rolled_back'",
            )
        )
    if (verification["state"] == "pending") != (verification["verified_at"] is None):
        errors.append(
            _error(
                "state_invalid",
                "$.verification.verified_at",
                "verified_at must be null exactly when verification is 'pending'",
            )
        )
    if (state == "rolled_back") != (rollback is not None):
        errors.append(
            _error(
                "state_invalid",
                "$.rollback",
                "rollback must be non-null exactly when delivery.state is "
                "'rolled_back'",
            )
        )


# --- Hygiene validation --------------------------------------------------


def _iter_strings(value, path):
    if isinstance(value, str):
        yield path, value
    elif isinstance(value, dict):
        for key in value:
            yield from _iter_strings(value[key], "%s.%s" % (path, key))
    elif isinstance(value, list):
        for i, item in enumerate(value):
            yield from _iter_strings(item, "%s[%d]" % (path, i))


def _check_hygiene(doc, errors):
    for path, text in _iter_strings(doc, "$"):
        for category, rx in HYGIENE_RULES:
            if rx.search(text):
                # Never echo the offending value into the verdict.
                errors.append(
                    _error(
                        "hygiene_violation",
                        path,
                        "string value matches forbidden category '%s'" % category,
                    )
                )


# --- Document validation ----------------------------------------------------


def validate_document(doc):
    """Validate a parsed receipt document. Returns a sorted error list."""
    errors = []
    if not isinstance(doc, dict):
        return [_error("type_invalid", "$", "receipt document must be a JSON object")]

    version = doc.get("schema_version")
    if "schema_version" not in doc:
        return [_error("missing_field", "$.schema_version", "required field is missing")]
    if not isinstance(version, str):
        return [_error("type_invalid", "$.schema_version", "value must be a string")]
    if version not in SUPPORTED_SCHEMA_VERSIONS:
        return [
            _error(
                "schema_version_unsupported",
                "$.schema_version",
                "supported versions: %s" % ", ".join(SUPPORTED_SCHEMA_VERSIONS),
            )
        ]

    _check_node(SPEC, doc, "$", errors)
    if not any(e["code"] in STRUCTURAL_CODES for e in errors):
        _check_semantics(doc, errors)
    _check_hygiene(doc, errors)
    errors.sort(key=lambda e: (e["path"], e["code"], e["message"]))
    return errors


# --- JSON loading -----------------------------------------------------------


def _reject_duplicate_keys(pairs):
    seen = set()
    obj = {}
    for key, value in pairs:
        if key in seen:
            raise ValueError("duplicate object key: %r" % key)
        seen.add(key)
        obj[key] = value
    return obj


def _reject_constant(name):
    raise ValueError("non-finite number %r is not allowed" % name)


def parse_receipt(text):
    """Parse receipt text. Returns (document, errors)."""
    try:
        doc = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_constant,
        )
    except ValueError as exc:
        return None, [_error("json_invalid", "$", "input is not strict JSON: %s" % exc)]
    return doc, []


# --- Schema emission ----------------------------------------------------------


def _node_to_json_schema(spec):
    kind = spec["kind"]
    if kind == "string":
        out = {
            "type": ["string", "null"] if spec["nullable"] else "string",
            "pattern": spec["pattern"],
            "description": spec["description"],
        }
        if spec["timestamp"]:
            out["format"] = "date-time"
        return out
    if kind == "integer":
        return {
            "type": "integer",
            "minimum": spec["minimum"],
            "description": spec["description"],
        }
    if kind == "enum":
        return {
            "type": "string",
            "enum": list(spec["values"]),
            "description": spec["description"],
        }
    if kind == "array":
        return {
            "type": "array",
            "items": _node_to_json_schema(spec["items"]),
            "minItems": spec["min_items"],
            "description": spec["description"],
        }
    if kind == "object":
        return {
            "type": ["object", "null"] if spec["nullable"] else "object",
            "properties": {
                name: _node_to_json_schema(node)
                for name, node in spec["properties"].items()
            },
            "required": sorted(spec["properties"]),
            "additionalProperties": False,
            "description": spec["description"],
        }
    raise AssertionError("unhandled spec kind: %r" % kind)


def emit_schema():
    schema = _node_to_json_schema(SPEC)
    schema.update(
        {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "$id": SCHEMA_ID,
            "title": CONTRACT_NAME,
            "x-contract": {
                "supported_schema_versions": list(SUPPORTED_SCHEMA_VERSIONS),
                "semantic_rules": list(SEMANTIC_RULES),
                "hygiene_categories": list(HYGIENE_CATEGORIES),
                "error_codes": list(ERROR_CODES),
            },
        }
    )
    return json.dumps(schema, indent=2, sort_keys=True) + "\n"


# --- CLI ----------------------------------------------------------------------


def build_report(input_name, errors):
    return {
        "tool": TOOL_NAME,
        "tool_version": TOOL_VERSION,
        "contract": {
            "name": CONTRACT_NAME,
            "supported_schema_versions": list(SUPPORTED_SCHEMA_VERSIONS),
        },
        "input": input_name,
        "valid": not errors,
        "error_count": len(errors),
        "errors": errors,
    }


def _print_report(report):
    sys.stdout.write(json.dumps(report, indent=2, sort_keys=True) + "\n")


def main(argv):
    if argv == ["--emit-schema"]:
        sys.stdout.write(emit_schema())
        return 0
    if len(argv) != 1 or (argv[0].startswith("-") and argv[0] != "-"):
        _print_report(
            build_report(
                None,
                [
                    _error(
                        "usage_error",
                        "$",
                        "usage: validate_receipt.py <receipt.json | -> "
                        "| --emit-schema",
                    )
                ],
            )
        )
        return 2

    input_name = argv[0]
    try:
        if input_name == "-":
            text = sys.stdin.read()
        else:
            with open(input_name, "r", encoding="utf-8") as fh:
                text = fh.read()
    except OSError as exc:
        _print_report(
            build_report(
                input_name,
                [_error("io_error", "$", "cannot read input: %s" % exc.strerror)],
            )
        )
        return 2

    doc, errors = parse_receipt(text)
    if not errors:
        errors = validate_document(doc)
    _print_report(build_report(input_name, errors))
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
