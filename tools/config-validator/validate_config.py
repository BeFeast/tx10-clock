#!/usr/bin/env python3
"""tx10-clock host-side clock configuration validator.

Validates one clock-configuration JSON document against the versioned,
strict config contract and prints a stable, machine-readable JSON verdict on
stdout, or (with ``--canonicalize``) the deterministic canonical form of an
accepted document.

The config contract is the public, deterministic surface the app is driven
by. This tool exists so the surface can be exercised — strict, bounded,
canonicalized, and tested — entirely on a host, *before* any Android runtime
integration. It is host-only and dependency-free: Python 3 standard library
only — no network, no Android SDK, no device access, and no signing material.

The committed schema file (config/schema/config-v1.schema.json) is generated
from SPEC below via ``--emit-schema`` and a test asserts the two never drift
apart; SPEC is the single source of truth.

Usage:
    validate_config.py <config.json | ->    validate a config document
    validate_config.py --canonicalize <f>   print canonical form of a document
    validate_config.py --emit-schema        print the JSON Schema

Exit codes:
    0  document is valid (or canonical form printed)
    1  document is invalid (structural, semantic, hygiene, or JSON error)
    2  usage error or unreadable input
"""

import json
import re
import sys

TOOL_NAME = "tx10-config-validate"
TOOL_VERSION = "1.0.0"
CONTRACT_NAME = "tx10-clock-config"
SUPPORTED_SCHEMA_VERSIONS = ("1.0.0",)
SCHEMA_ID = (
    "https://github.com/BeFeast/tx10-clock/blob/main/"
    "config/schema/config-v1.schema.json"
)

# Hard upper bound on an accepted document. Anything larger fails closed
# before it is even parsed, so an oversized or maliciously deep input can
# never exhaust host resources.
MAX_DOCUMENT_BYTES = 8192

# Hard upper bound on JSON nesting depth. The contract is only two levels deep
# (root object -> section object -> scalar), but a compact document can nest
# far deeper while staying under the byte ceiling. Such input is rejected
# before any recursive traversal (structural check, hygiene scan), so a
# maliciously deep document fails closed with a JSON verdict instead of
# exhausting the interpreter's recursion stack.
MAX_NESTING_DEPTH = 32

# --- Field formats & value domains -------------------------------------------

SEMVER = r"^[0-9]+\.[0-9]+\.[0-9]+$"
# BCP-47 (RFC 5646) locale tag: a primary language subtag with optional
# script, region, variant(s), extension(s), and a private-use suffix — so
# valid tags such as ``de-CH-1996`` (variant) and ``en-US-u-ca-gregory``
# (extension) are accepted, not just language/script/region. Only ASCII
# letters, digits, and hyphens are admitted, so a path, separator, or space
# can never masquerade as a tag. Accepted case-insensitively and canonicalized
# to standard subtag casing on output. The overall length is additionally
# bounded (see MAX_LOCALE_TAG_LENGTH), which also keeps the alternations here
# safe from pathological backtracking.
BCP47_TAG = (
    r"^"
    r"[A-Za-z]{2,3}"                                    # primary language
    r"(?:-[A-Za-z]{4})?"                               # optional script
    r"(?:-(?:[A-Za-z]{2}|[0-9]{3}))?"                  # optional region
    r"(?:-(?:[A-Za-z0-9]{5,8}|[0-9][A-Za-z0-9]{3}))*"  # variant(s)
    r"(?:-[0-9A-WY-Za-wy-z](?:-[A-Za-z0-9]{2,8})+)*"   # extension(s)
    r"(?:-[Xx](?:-[A-Za-z0-9]{1,8})+)?"                # private use
    r"$"
)
# IANA-style zone id (Area/Location[/Sublocation]) or the literal UTC. No
# dots, spaces, or leading slash, so path-traversal and absolute paths cannot
# masquerade as a zone id. The runtime resolves the id against the platform
# zone database; this contract validates its shape only.
IANA_TZ_ID = r"^(?:UTC|[A-Za-z][A-Za-z0-9+_-]*(?:/[A-Za-z0-9+_-]+){1,2})$"

MAX_LOCALE_TAG_LENGTH = 35
MAX_TZ_ID_LENGTH = 64

HOUR_CYCLES = ("h12", "h24")
SWEEP_MODES = ("smooth", "tick")
SOURCE_POLICIES = ("device", "fixed")

# Bounds for the burn-in shift engine, kept deliberately conservative.
MIN_SHIFT_RADIUS_PX = 0
MAX_SHIFT_RADIUS_PX = 64
MIN_SHIFT_INTERVAL_SECONDS = 1
MAX_SHIFT_INTERVAL_SECONDS = 86400

SEMANTIC_RULES = (
    "locale.tag must be a valid tag exactly when locale.policy is 'fixed', "
    "and null when locale.policy is 'device'",
    "timeZone.id must be a valid id exactly when timeZone.policy is 'fixed', "
    "and null when timeZone.policy is 'device'",
    "burnIn.shiftRadiusPx must be at least 1 when burnIn.shiftEnabled is true",
)

ERROR_CODES = (
    "format_invalid",
    "hygiene_violation",
    "io_error",
    "json_invalid",
    "missing_field",
    "oversized",
    "range_invalid",
    "schema_version_unsupported",
    "state_invalid",
    "type_invalid",
    "unknown_field",
    "usage_error",
)

# --- Node constructors for SPEC ----------------------------------------------


def _string(pattern, description, nullable=False, max_length=None):
    return {
        "kind": "string",
        "pattern": pattern,
        "description": description,
        "nullable": nullable,
        "max_length": max_length,
    }


def _boolean(description):
    return {"kind": "boolean", "description": description}


def _integer(minimum, maximum, description):
    return {
        "kind": "integer",
        "minimum": minimum,
        "maximum": maximum,
        "description": description,
    }


def _enum(values, description):
    return {"kind": "enum", "values": tuple(values), "description": description}


def _object(properties, description, constraints=None):
    node = {"kind": "object", "properties": properties, "description": description}
    if constraints:
        # Draft-2020-12 conditional subschemas (if/then/else) that encode the
        # SEMANTIC_RULES the emitted JSON Schema would otherwise only describe
        # in prose. They are inert to _check_node (which walks properties only);
        # _check_semantics enforces the same rules for the validator itself.
        node["constraints"] = constraints
    return node


# Conditional schema fragments mirroring SEMANTIC_RULES, so a standard
# draft-2020-12 validator rejects the same negatives validate_document does:
# a 'fixed' policy demands a non-null tag/id and a 'device' policy demands
# null, and an enabled burn-in shift demands a radius of at least 1.
_LOCALE_CONSTRAINTS = [
    {
        "if": {"properties": {"policy": {"const": "fixed"}}, "required": ["policy"]},
        "then": {"properties": {"tag": {"type": "string"}}},
        "else": {"properties": {"tag": {"type": "null"}}},
    }
]
_TIMEZONE_CONSTRAINTS = [
    {
        "if": {"properties": {"policy": {"const": "fixed"}}, "required": ["policy"]},
        "then": {"properties": {"id": {"type": "string"}}},
        "else": {"properties": {"id": {"type": "null"}}},
    }
]
_BURNIN_CONSTRAINTS = [
    {
        "if": {
            "properties": {"shiftEnabled": {"const": True}},
            "required": ["shiftEnabled"],
        },
        "then": {"properties": {"shiftRadiusPx": {"minimum": 1}}},
    }
]


# Config contract v1. Every property at every level is required and no unknown
# properties are accepted anywhere. Nullability is used only where a companion
# policy field decides whether a value is present (see SEMANTIC_RULES).
SPEC = _object(
    {
        "schemaVersion": _string(
            SEMVER, "Config contract version this document claims to follow."
        ),
        "clock": _object(
            {
                "hourCycle": _enum(
                    HOUR_CYCLES,
                    "Digital readout hour cycle: 'h12' (12-hour) or 'h24'.",
                ),
                "showSeconds": _boolean(
                    "Whether the seconds field and second hand are shown."
                ),
                "showDate": _boolean("Whether the digital date line is shown."),
                "analogSweep": _enum(
                    SWEEP_MODES,
                    "Analog second-hand motion: 'smooth' sweep or 'tick'.",
                ),
            },
            "Clock face and digital readout behaviour.",
        ),
        "locale": _object(
            {
                "policy": _enum(
                    SOURCE_POLICIES,
                    "Locale source: follow the 'device' or use a 'fixed' tag.",
                ),
                "tag": _string(
                    BCP47_TAG,
                    "BCP-47 locale tag; null unless policy is 'fixed'.",
                    nullable=True,
                    max_length=MAX_LOCALE_TAG_LENGTH,
                ),
            },
            "Locale selection policy.",
            constraints=_LOCALE_CONSTRAINTS,
        ),
        "timeZone": _object(
            {
                "policy": _enum(
                    SOURCE_POLICIES,
                    "Time-zone source: follow the 'device' or use a 'fixed' id.",
                ),
                "id": _string(
                    IANA_TZ_ID,
                    "IANA time-zone id; null unless policy is 'fixed'.",
                    nullable=True,
                    max_length=MAX_TZ_ID_LENGTH,
                ),
            },
            "Time-zone selection policy.",
            constraints=_TIMEZONE_CONSTRAINTS,
        ),
        "burnIn": _object(
            {
                "shiftEnabled": _boolean(
                    "Whether the periodic burn-in pixel shift is enabled."
                ),
                "shiftRadiusPx": _integer(
                    MIN_SHIFT_RADIUS_PX,
                    MAX_SHIFT_RADIUS_PX,
                    "Burn-in shift radius in pixels.",
                ),
                "shiftIntervalSeconds": _integer(
                    MIN_SHIFT_INTERVAL_SECONDS,
                    MAX_SHIFT_INTERVAL_SECONDS,
                    "Whole seconds between burn-in shifts.",
                ),
            },
            "Burn-in mitigation shift settings.",
            constraints=_BURNIN_CONSTRAINTS,
        ),
        "runtime": _object(
            {
                "bootStart": _boolean(
                    "Whether the clock auto-starts after device boot."
                ),
                "keepScreenOn": _boolean(
                    "Whether the screen is held on while the clock is foreground."
                ),
                "safeRefresh": _boolean(
                    "Whether the conservative low-flicker refresh path is used."
                ),
            },
            "Safe runtime behaviour toggles.",
        ),
    },
    "Deterministic, public-safe tx10-clock host configuration.",
)

# --- Hygiene rules ------------------------------------------------------------
# Applied to every string value in the document AND to the canonical output.
# The regex sources are written so their literal text never matches this
# repository's public-hygiene scan.

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
    ["missing_field", "unknown_field", "type_invalid", "format_invalid", "range_invalid"]
)


def _error(code, path, message):
    return {"code": code, "path": path, "message": message}


# --- Structural validation ------------------------------------------------


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
        if spec["max_length"] is not None and len(value) > spec["max_length"]:
            errors.append(
                _error(
                    "range_invalid",
                    path,
                    "string exceeds %d characters" % spec["max_length"],
                )
            )
            return
        if re.fullmatch(spec["pattern"], value) is None:
            errors.append(
                _error("format_invalid", path, "value does not match required format")
            )
    elif kind == "boolean":
        if not isinstance(value, bool):
            errors.append(_error("type_invalid", path, "value must be a boolean"))
    elif kind == "integer":
        if isinstance(value, bool) or not isinstance(value, int):
            errors.append(_error("type_invalid", path, "value must be an integer"))
            return
        if value < spec["minimum"] or value > spec["maximum"]:
            errors.append(
                _error(
                    "range_invalid",
                    path,
                    "value must be between %d and %d inclusive"
                    % (spec["minimum"], spec["maximum"]),
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
                        "field is not part of the config contract",
                    )
                )


# --- Semantic validation ----------------------------------------------------


def _check_semantics(doc, errors):
    locale = doc["locale"]
    if (locale["policy"] == "fixed") != (locale["tag"] is not None):
        errors.append(
            _error(
                "state_invalid",
                "$.locale.tag",
                "locale.tag must be set exactly when locale.policy is 'fixed'",
            )
        )

    zone = doc["timeZone"]
    if (zone["policy"] == "fixed") != (zone["id"] is not None):
        errors.append(
            _error(
                "state_invalid",
                "$.timeZone.id",
                "timeZone.id must be set exactly when timeZone.policy is 'fixed'",
            )
        )

    burn_in = doc["burnIn"]
    if burn_in["shiftEnabled"] and burn_in["shiftRadiusPx"] < 1:
        errors.append(
            _error(
                "state_invalid",
                "$.burnIn.shiftRadiusPx",
                "enabled burn-in shift requires shiftRadiusPx of at least 1",
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
    """Validate a parsed config document. Returns a sorted error list."""
    errors = []
    if not isinstance(doc, dict):
        return [_error("type_invalid", "$", "config document must be a JSON object")]

    if "schemaVersion" not in doc:
        return [
            _error("missing_field", "$.schemaVersion", "required field is missing")
        ]
    version = doc.get("schemaVersion")
    if not isinstance(version, str):
        return [_error("type_invalid", "$.schemaVersion", "value must be a string")]
    if version not in SUPPORTED_SCHEMA_VERSIONS:
        return [
            _error(
                "schema_version_unsupported",
                "$.schemaVersion",
                "supported versions: %s" % ", ".join(SUPPORTED_SCHEMA_VERSIONS),
            )
        ]

    _check_node(SPEC, doc, "$", errors)
    if not any(e["code"] in STRUCTURAL_CODES for e in errors):
        _check_semantics(doc, errors)
    _check_hygiene(doc, errors)
    errors.sort(key=lambda e: (e["path"], e["code"], e["message"]))
    return errors


# --- Canonicalization -------------------------------------------------------


def _canonical_locale_tag(tag):
    """Normalize a validated BCP-47 tag to standard subtag casing.

    Following RFC 5646 case conventions and the subtag order the tag pattern
    admits: the language subtag is lowercased, an optional script subtag is
    titlecased, an alphabetic region subtag is uppercased, and every remaining
    subtag (variants, extensions, private use) is lowercased. This makes
    canonicalization idempotent and order-independent regardless of the input's
    casing, so any two spellings of one tag collapse to identical bytes.
    """
    parts = tag.split("-")
    out = [parts[0].lower()]
    i = 1
    n = len(parts)
    # Optional script subtag: exactly four letters -> Titlecase.
    if i < n and len(parts[i]) == 4 and parts[i].isalpha():
        out.append(parts[i][:1].upper() + parts[i][1:].lower())
        i += 1
    # Optional region subtag: two letters -> UPPER, three digits -> unchanged.
    if i < n and len(parts[i]) == 2 and parts[i].isalpha():
        out.append(parts[i].upper())
        i += 1
    elif i < n and len(parts[i]) == 3 and parts[i].isdigit():
        out.append(parts[i])
        i += 1
    # Variants, extensions, and private use are all lowercase in canonical form.
    while i < n:
        out.append(parts[i].lower())
        i += 1
    return "-".join(out)


def _canonical_zone_id(zone_id):
    """Normalize a validated IANA-style zone id to canonical subtag casing.

    Each ``/``-separated segment is title-cased word-by-word (words are split
    on ``_`` / ``-``): a word carrying any lowercase letter is upper-cased on
    its first letter and lowercased elsewhere, so ``america/new_york`` and
    ``America/New_York`` collapse to one canonical value. A word with no
    lowercase letter (an all-caps abbreviation such as ``UTC`` or ``GMT+5``) is
    left byte-for-byte, since such tokens carry their own fixed casing that no
    title-casing rule could recover. Canonicalization consults no time-zone
    database, so an irregularly cased id (e.g. a lowercase French connector or
    an all-uppercase input) may not round-trip to its exact IANA spelling; the
    runtime resolves the id against the platform database.
    """
    segments = []
    for segment in zone_id.split("/"):
        words = re.split(r"([_-])", segment)  # keep the delimiters
        normalized = []
        for word in words:
            if word in ("_", "-") or not any(ch.islower() for ch in word):
                normalized.append(word)
            else:
                normalized.append(word[:1].upper() + word[1:].lower())
        segments.append("".join(normalized))
    return "/".join(segments)


def canonicalize_document(doc):
    """Return the deterministic canonical form of a validated document.

    The only value normalizations are locale-tag and time-zone-id casing;
    every other accepted value is already canonical. Key ordering is imposed at
    serialization time (``sort_keys``), so the output is a pure function of the
    accepted content.
    """
    canonical = json.loads(json.dumps(doc))  # deep copy without shared refs
    if canonical["locale"]["tag"] is not None:
        canonical["locale"]["tag"] = _canonical_locale_tag(canonical["locale"]["tag"])
    if canonical["timeZone"]["id"] is not None:
        canonical["timeZone"]["id"] = _canonical_zone_id(canonical["timeZone"]["id"])
    return canonical


def canonical_text(doc):
    return json.dumps(canonicalize_document(doc), indent=2, sort_keys=True) + "\n"


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


def _exceeds_max_depth(text):
    """Return True if the raw text nests brackets deeper than the bound.

    Scans the text once without recursion, ignoring braces/brackets that occur
    inside JSON strings, so it can reject a pathologically deep document before
    it is parsed or recursively traversed.
    """
    depth = 0
    in_string = False
    escaped = False
    for ch in text:
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
        elif ch == '"':
            in_string = True
        elif ch == "{" or ch == "[":
            depth += 1
            if depth > MAX_NESTING_DEPTH:
                return True
        elif ch == "}" or ch == "]":
            depth -= 1
    return False


def parse_config(text):
    """Parse config text. Returns (document, errors).

    Fails closed on oversized input, excessively nested input, malformed JSON,
    duplicate keys, and non-finite numbers (NaN / Infinity) before any document
    is produced.
    """
    if len(text.encode("utf-8")) > MAX_DOCUMENT_BYTES:
        return None, [
            _error(
                "oversized",
                "$",
                "document exceeds %d byte limit" % MAX_DOCUMENT_BYTES,
            )
        ]
    if _exceeds_max_depth(text):
        return None, [
            _error(
                "json_invalid",
                "$",
                "input nests deeper than %d levels" % MAX_NESTING_DEPTH,
            )
        ]
    try:
        doc = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_constant,
        )
    except RecursionError:
        return None, [_error("json_invalid", "$", "input nested too deeply")]
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
        if spec["max_length"] is not None:
            out["maxLength"] = spec["max_length"]
        return out
    if kind == "boolean":
        return {"type": "boolean", "description": spec["description"]}
    if kind == "integer":
        return {
            "type": "integer",
            "minimum": spec["minimum"],
            "maximum": spec["maximum"],
            "description": spec["description"],
        }
    if kind == "enum":
        return {
            "type": "string",
            "enum": list(spec["values"]),
            "description": spec["description"],
        }
    if kind == "object":
        out = {
            "type": "object",
            "properties": {
                name: _node_to_json_schema(node)
                for name, node in spec["properties"].items()
            },
            "required": sorted(spec["properties"]),
            "additionalProperties": False,
            "description": spec["description"],
        }
        if spec.get("constraints"):
            out["allOf"] = spec["constraints"]
        return out
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
                "max_document_bytes": MAX_DOCUMENT_BYTES,
                "max_nesting_depth": MAX_NESTING_DEPTH,
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


def _print_report(report, stream=sys.stdout):
    stream.write(json.dumps(report, indent=2, sort_keys=True) + "\n")


def _read_input(input_name):
    if input_name == "-":
        return sys.stdin.read()
    with open(input_name, "r", encoding="utf-8") as fh:
        return fh.read()


def _usage_report():
    return build_report(
        None,
        [
            _error(
                "usage_error",
                "$",
                "usage: validate_config.py <config.json | -> "
                "| --canonicalize <config.json | -> | --emit-schema",
            )
        ],
    )


def main(argv):
    if argv == ["--emit-schema"]:
        sys.stdout.write(emit_schema())
        return 0

    canonicalize = False
    if argv and argv[0] == "--canonicalize":
        canonicalize = True
        argv = argv[1:]

    if len(argv) != 1 or (argv[0].startswith("-") and argv[0] != "-"):
        _print_report(_usage_report())
        return 2

    input_name = argv[0]
    try:
        text = _read_input(input_name)
    except OSError as exc:
        _print_report(
            build_report(
                input_name,
                [_error("io_error", "$", "cannot read input: %s" % exc.strerror)],
            )
        )
        return 2
    except UnicodeDecodeError:
        # Invalid UTF-8 in a file or on stdin is an input problem, not a
        # contract violation: report the documented io_error verdict rather
        # than letting the decode error escape as an uncaught traceback.
        _print_report(
            build_report(
                input_name,
                [_error("io_error", "$", "input is not valid UTF-8")],
            )
        )
        return 2

    doc, errors = parse_config(text)
    if not errors:
        errors = validate_document(doc)

    if canonicalize:
        if errors:
            # Keep the machine-readable verdict on stderr so stdout carries
            # only canonical output on success — never a partial document.
            _print_report(build_report(input_name, errors), stream=sys.stderr)
            return 1
        sys.stdout.write(canonical_text(doc))
        return 0

    _print_report(build_report(input_name, errors))
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
