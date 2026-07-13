"""Deterministic, dependency-free validator for TX10 Clock release/delivery receipts.

This module is host-only: it uses nothing beyond the Python 3 standard library, so
it runs with no Android SDK, no network, no signing key, and no device. It never
executes anything from the receipt; it only inspects data.

Validation runs in four layers and collects every error found:

  1. structural  -- a small JSON Schema subset (type/required/properties/
                    additionalProperties/enum/const/pattern/minimum/maxLength).
  2. digest      -- exact SHA-256 asset digest and SHA-256 certificate fingerprint
                    formats, with mismatched-digest detection (e.g. an SHA-1 value
                    in an SHA-256 field).
  3. state       -- the delivery state machine (legal transitions) plus cross-field
                    invariants tying delivery, verification, and rollback together.
  4. hygiene     -- rejects any raw secret, private endpoint, local absolute path,
                    or credential material appearing in any string value. Findings
                    report the field path and a category only -- never the matched
                    value -- so validator output stays public-safe.

Public API:
    load_schema(path=None) -> dict
    validate_receipt(receipt, schema=None) -> list[Error]
    Error (namedtuple: code, path, message)
"""

from __future__ import annotations

import json
import os
import re
from collections import namedtuple

# --------------------------------------------------------------------------- #
# Locations
# --------------------------------------------------------------------------- #

_THIS_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(_THIS_DIR))
SCHEMA_PATH = os.path.join(REPO_ROOT, "release", "receipt", "schema", "receipt.schema.json")

SCHEMA_VERSION = "1.0.0"

Error = namedtuple("Error", ["code", "path", "message"])


# --------------------------------------------------------------------------- #
# Structural validation: a deliberately small JSON Schema subset
# --------------------------------------------------------------------------- #

_TYPE_CHECKS = {
    "object": lambda v: isinstance(v, dict),
    "array": lambda v: isinstance(v, list),
    "string": lambda v: isinstance(v, str),
    # bool is a subclass of int in Python; exclude it from integer/number.
    "integer": lambda v: isinstance(v, int) and not isinstance(v, bool),
    "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
    "boolean": lambda v: isinstance(v, bool),
    "null": lambda v: v is None,
}


def _type_matches(value, type_spec):
    types = type_spec if isinstance(type_spec, list) else [type_spec]
    return any(_TYPE_CHECKS[t](value) for t in types if t in _TYPE_CHECKS)


def _join(path, key):
    return f"{path}.{key}" if path else str(key)


def _validate_schema(value, schema, path, errors):
    """Validate ``value`` against the schema subset, appending Errors in place."""
    # type
    type_spec = schema.get("type")
    if type_spec is not None and not _type_matches(value, type_spec):
        errors.append(
            Error(
                "type_invalid",
                path or "(root)",
                f"expected type {type_spec}, got {_typename(value)}",
            )
        )
        # Type is wrong; deeper checks would be noise.
        return

    # const
    if "const" in schema and value != schema["const"]:
        errors.append(
            Error("const_invalid", path or "(root)", f"must equal {schema['const']!r}")
        )

    # enum (note: null may be a legal enum member)
    if "enum" in schema and value not in schema["enum"]:
        errors.append(
            Error(
                "enum_invalid",
                path or "(root)",
                f"must be one of {sorted(str(x) for x in schema['enum'])}",
            )
        )

    if isinstance(value, str):
        pattern = schema.get("pattern")
        if pattern is not None and re.search(pattern, value) is None:
            errors.append(
                Error("pattern_invalid", path, "does not match required pattern")
            )
        max_length = schema.get("maxLength")
        if max_length is not None and len(value) > max_length:
            errors.append(
                Error("length_invalid", path, f"longer than {max_length} characters")
            )

    if _TYPE_CHECKS["integer"](value) or _TYPE_CHECKS["number"](value):
        minimum = schema.get("minimum")
        if minimum is not None and value < minimum:
            errors.append(Error("range_invalid", path, f"must be >= {minimum}"))

    if isinstance(value, dict):
        _validate_object(value, schema, path, errors)


def _validate_object(value, schema, path, errors):
    props = schema.get("properties", {})

    for req in schema.get("required", []):
        if req not in value:
            errors.append(
                Error("missing_field", _join(path, req), "required field is missing")
            )

    if schema.get("additionalProperties") is False:
        for key in value:
            if key not in props:
                errors.append(
                    Error("unknown_field", _join(path, key), "field is not permitted")
                )

    for key, subschema in props.items():
        if key in value:
            _validate_schema(value[key], subschema, _join(path, key), errors)


def _typename(value):
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, str):
        return "string"
    if isinstance(value, int):
        return "integer"
    if isinstance(value, float):
        return "number"
    if isinstance(value, list):
        return "array"
    if isinstance(value, dict):
        return "object"
    return type(value).__name__


# --------------------------------------------------------------------------- #
# Digest-format validation
# --------------------------------------------------------------------------- #

_HEX_RE = re.compile(r"^[0-9a-f]+$")
# Well-known hex digest byte lengths, for helpful mismatch messages.
_KNOWN_DIGEST_BYTES = {16: "md5", 20: "sha1", 32: "sha256", 48: "sha384", 64: "sha512"}


def _classify_hex_digest(hex_str):
    """Return the likely algorithm name for a bare hex digest, or None."""
    return _KNOWN_DIGEST_BYTES.get(len(hex_str) // 2)


def _check_asset_digest(receipt, errors):
    asset = receipt.get("asset")
    if not isinstance(asset, dict) or "sha256" not in asset:
        return
    value = asset["sha256"]
    if not isinstance(value, str):
        return
    path = "asset.sha256"
    if value != value.lower():
        errors.append(
            Error("digest_format_mismatch", path, "SHA-256 digest must be lowercase hex")
        )
        return
    if _HEX_RE.match(value) is None:
        errors.append(
            Error("digest_format_mismatch", path, "SHA-256 digest must be bare hex")
        )
        return
    if len(value) != 64:
        alg = _classify_hex_digest(value)
        detail = f" (looks like {alg})" if alg and alg != "sha256" else ""
        errors.append(
            Error(
                "digest_format_mismatch",
                path,
                f"SHA-256 digest must be 64 hex chars, got {len(value)}{detail}",
            )
        )


def _check_cert_fingerprint(receipt, errors):
    signing = receipt.get("signing")
    if not isinstance(signing, dict) or "certificate_fingerprint_sha256" not in signing:
        return
    value = signing["certificate_fingerprint_sha256"]
    if not isinstance(value, str):
        return
    path = "signing.certificate_fingerprint_sha256"
    groups = value.split(":")
    if any(len(g) != 2 for g in groups) or any(
        _HEX_RE.match(g.lower()) is None for g in groups
    ):
        errors.append(
            Error(
                "digest_format_mismatch",
                path,
                "certificate fingerprint must be colon-separated hex byte pairs",
            )
        )
        return
    if len(groups) != 32:
        alg = _KNOWN_DIGEST_BYTES.get(len(groups))
        detail = f" (looks like {alg})" if alg and alg != "sha256" else ""
        errors.append(
            Error(
                "digest_format_mismatch",
                path,
                f"SHA-256 fingerprint must be 32 bytes, got {len(groups)}{detail}",
            )
        )


# --------------------------------------------------------------------------- #
# Delivery state machine + cross-field invariants
# --------------------------------------------------------------------------- #

# Legal delivery transitions: previous_state -> {allowed current states}.
# ``None`` (initial receipt) may only enter "planned".
_TRANSITIONS = {
    None: {"planned"},
    "planned": {"published", "failed"},
    "published": {"delivered", "rolled_back", "failed"},
    "delivered": {"installed", "rolled_back", "failed"},
    "installed": {"rolled_back", "failed"},
    "failed": {"published", "rolled_back"},
    "rolled_back": {"published"},
}

_DELIVERY_STATES = {
    "planned",
    "published",
    "delivered",
    "installed",
    "rolled_back",
    "failed",
}


def _check_state_machine(receipt, errors):
    delivery = receipt.get("delivery")
    if not isinstance(delivery, dict):
        return
    state = delivery.get("state")
    previous = delivery.get("previous_state", "<<missing>>")

    # Only evaluate transitions when both endpoints are structurally sane; the
    # structural layer already reports bad enums / missing fields.
    if state in _DELIVERY_STATES and (previous is None or previous in _DELIVERY_STATES):
        allowed = _TRANSITIONS.get(previous, set())
        if state not in allowed:
            frm = "(initial)" if previous is None else previous
            errors.append(
                Error(
                    "state_transition_invalid",
                    "delivery.state",
                    f"illegal transition {frm} -> {state}",
                )
            )


def _check_invariants(receipt, errors):
    delivery = receipt.get("delivery")
    verification = receipt.get("verification")
    rollback = receipt.get("rollback", "<<missing>>")

    state = delivery.get("state") if isinstance(delivery, dict) else None
    vstate = verification.get("state") if isinstance(verification, dict) else None

    # Rollback target presence must agree with the rolled_back state.
    if rollback != "<<missing>>":
        if state == "rolled_back" and rollback is None:
            errors.append(
                Error(
                    "state_invariant_invalid",
                    "rollback",
                    "delivery.state is rolled_back but rollback target is null",
                )
            )
        if state != "rolled_back" and isinstance(rollback, dict):
            errors.append(
                Error(
                    "state_invariant_invalid",
                    "rollback",
                    f"rollback target present but delivery.state is {state}, not rolled_back",
                )
            )

    # An installed delivery may only be claimed once verification has passed.
    if state == "installed" and vstate is not None and vstate != "passed":
        errors.append(
            Error(
                "state_invariant_invalid",
                "verification.state",
                f"delivery.state is installed but verification.state is {vstate}, not passed",
            )
        )


# --------------------------------------------------------------------------- #
# Hygiene: reject secrets, private endpoints, absolute paths, credentials
# --------------------------------------------------------------------------- #

# Each rule: (error_code, category, compiled_regex). The matched text is NEVER
# emitted -- only the field path and category -- so validator output is public-safe.
_HYGIENE_RULES = [
    # --- credential material / raw secrets ---
    ("hygiene_secret", "private_key_block",
     re.compile(r"-----BEGIN[ A-Z]*PRIVATE KEY-----")),
    ("hygiene_secret", "certificate_block",
     re.compile(r"-----BEGIN CERTIFICATE-----")),
    ("hygiene_secret", "openssh_private_key",
     re.compile(r"-----BEGIN OPENSSH PRIVATE KEY-----")),
    ("hygiene_secret", "pgp_private_key",
     re.compile(r"-----BEGIN PGP PRIVATE KEY BLOCK-----")),
    ("hygiene_secret", "jwt",
     re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}")),
    ("hygiene_credential", "aws_access_key_id",
     re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("hygiene_credential", "github_token",
     re.compile(
         r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b"
     )),
    ("hygiene_credential", "slack_token",
     re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}")),
    ("hygiene_credential", "google_api_key",
     re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("hygiene_credential", "bearer_token",
     re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/-]{8,}=*")),
    ("hygiene_credential", "basic_auth_header",
     re.compile(r"(?i)\bbasic\s+[A-Za-z0-9+/]{12,}=*")),
    ("hygiene_credential", "authorization_header",
     re.compile(r"(?i)\bauthorization\s*[:=]")),
    ("hygiene_credential", "secret_assignment",
     re.compile(r"(?i)\b(?:password|passwd|pwd|secret|api[_-]?key|access[_-]?key|"
                r"secret[_-]?key|client[_-]?secret|private[_-]?key|token|credential)"
                r"\s*[:=]\s*\S")),
    ("hygiene_credential", "userinfo_in_url",
     re.compile(r"[a-zA-Z][a-zA-Z0-9+.-]*://[^/\s:@]+:[^/\s@]+@")),
    # --- private endpoints / LAN addresses ---
    ("hygiene_private_endpoint", "loopback",
     re.compile(r"(?i)\b(?:localhost|127\.(?:\d{1,3}\.){2}\d{1,3}|::1)\b")),
    ("hygiene_private_endpoint", "private_ipv6",
     re.compile(
         r"(?i)(?:"
         r"\[(?:::1|(?:f[cd][0-9a-f]{2}|fe[89ab][0-9a-f]):[0-9a-f:.]+)\]"
         r"|(?<![0-9a-f:])(?:::1|(?:f[cd][0-9a-f]{2}|fe[89ab][0-9a-f]):"
         r"[0-9a-f:]+)(?![0-9a-f:])"
         r")"
     )),
    ("hygiene_private_endpoint", "rfc1918_10",
     re.compile(r"\b10\.(?:\d{1,3})\.(?:\d{1,3})\.(?:\d{1,3})\b")),
    ("hygiene_private_endpoint", "rfc1918_192_168",
     re.compile(r"\b192\.168\.\d{1,3}\.\d{1,3}\b")),
    ("hygiene_private_endpoint", "rfc1918_172",
     re.compile(r"\b172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}\b")),
    ("hygiene_private_endpoint", "link_local",
     re.compile(r"\b169\.254\.\d{1,3}\.\d{1,3}\b")),
    ("hygiene_private_endpoint", "mdns_local",
     re.compile(r"(?i)\b[a-z0-9][a-z0-9-]*\.local\b")),
    ("hygiene_private_endpoint", "onion",
     re.compile(r"(?i)\b[a-z2-7]{16,56}\.onion\b")),
    ("hygiene_private_endpoint", "file_uri",
     re.compile(r"(?i)\bfile://")),
    # --- local absolute paths ---
    ("hygiene_absolute_path", "posix_home",
     re.compile(r"(?:^|[\s\"'(=:])/(?:home|Users|root|mnt|media|srv)/")),
    ("hygiene_absolute_path", "posix_system",
     re.compile(r"(?:^|[\s\"'(=:])/(?:var|etc|opt|tmp|usr/local)/")),
    ("hygiene_absolute_path", "windows_drive",
     re.compile(r"\b[A-Za-z]:\\\\?[A-Za-z0-9._$-]")),
    ("hygiene_absolute_path", "unc_path",
     re.compile(r"\\\\[A-Za-z0-9._-]+\\")),
]


def _iter_strings(node, path):
    """Yield (path, string) for every string leaf, keys included as context."""
    if isinstance(node, str):
        yield path, node
    elif isinstance(node, dict):
        for key, sub in node.items():
            yield from _iter_strings(sub, _join(path, key))
    elif isinstance(node, list):
        for i, sub in enumerate(node):
            yield from _iter_strings(sub, f"{path}[{i}]")


def _check_hygiene(receipt, errors):
    seen = set()
    for path, text in _iter_strings(receipt, ""):
        for code, category, rx in _HYGIENE_RULES:
            if rx.search(text):
                key = (path, code, category)
                if key not in seen:
                    seen.add(key)
                    errors.append(
                        Error(
                            code,
                            path or "(root)",
                            f"forbidden content ({category}); value withheld",
                        )
                    )


# --------------------------------------------------------------------------- #
# Public API
# --------------------------------------------------------------------------- #


def load_schema(path=None):
    """Load and return the receipt JSON schema as a dict."""
    with open(path or SCHEMA_PATH, "r", encoding="utf-8") as fh:
        return json.load(fh)


def validate_receipt(receipt, schema=None):
    """Validate a parsed receipt object; return a deterministic list of Errors.

    An empty list means the receipt satisfies the contract.
    """
    if schema is None:
        schema = load_schema()

    errors: list[Error] = []
    _validate_schema(receipt, schema, "", errors)

    # Semantic layers run only when the top level is an object; otherwise the
    # structural layer has already reported the problem and deeper access would
    # raise.
    if isinstance(receipt, dict):
        _check_asset_digest(receipt, errors)
        _check_cert_fingerprint(receipt, errors)
        _check_state_machine(receipt, errors)
        _check_invariants(receipt, errors)
        _check_hygiene(receipt, errors)

    return _sorted_unique(errors)


def _sorted_unique(errors):
    """Stable ordering (by path, code, message) with duplicates removed."""
    seen = set()
    out = []
    for e in sorted(errors, key=lambda e: (e.path, e.code, e.message)):
        key = (e.code, e.path, e.message)
        if key not in seen:
            seen.add(key)
            out.append(e)
    return out
