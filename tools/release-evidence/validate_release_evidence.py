#!/usr/bin/env python3
"""tx10-clock release-evidence validator.

Validates one release-evidence JSON document against the versioned
release-evidence contract and prints a stable, machine-readable JSON verdict
on stdout.

Release evidence is the public, deterministic provenance record for one signed
GitHub Release. It pins the exact reviewed source, the pinned build toolchain,
the resolved SDK packages, the artifact digest, the package/version identity,
the proven absence of native libraries, the signing certificate fingerprint
(reference only), the reproducibility double-build comparison, and the CI run.
Signing material and passwords never appear here: signing is recorded only as a
public certificate fingerprint plus a private-store *reference*
(`infisical://…` or `vaultwarden://…`), and every string is screened so key
material, local paths, and private endpoints can never leak in.

This tool is host-only and dependency-free: Python 3 standard library only — no
network, no Android SDK, no signing material, and no device access.

The committed schema (release/evidence/schema/evidence-v1.schema.json) and the
committed toolchain lock (release/toolchain.lock.json) are both generated from
this file (`--emit-schema` / `--emit-lock`); tests assert they never drift.
This file is the single source of truth for the pinned toolchain.

Usage:
    validate_release_evidence.py <evidence.json | ->   validate a document
    validate_release_evidence.py --emit-schema         print the JSON Schema
    validate_release_evidence.py --emit-lock           print the toolchain lock

Exit codes:
    0  evidence is valid
    1  evidence is invalid (structural, semantic, hygiene, or JSON error)
    2  usage error or unreadable input
"""

import json
import re
import sys

TOOL_NAME = "tx10-release-evidence-validate"
TOOL_VERSION = "1.0.0"
CONTRACT_NAME = "tx10-clock-release-evidence"
SUPPORTED_SCHEMA_VERSIONS = ("1.0.0",)
SCHEMA_ID = (
    "https://github.com/BeFeast/tx10-clock/blob/main/"
    "release/evidence/schema/evidence-v1.schema.json"
)

# --- Pinned release toolchain (single source of truth) -----------------------
# These are the exact, non-dynamic release inputs. `--emit-lock` renders them
# to release/toolchain.lock.json and a test asserts the two never drift; the
# host-only pin verifier (scripts/check-release-pins.sh) checks the build files
# against that lock.

PINNED = {
    "jdk_major": 17,
    "android_gradle_plugin": "9.2.1",
    "gradle": "9.4.1",
    "gradle_distribution_sha256": (
        "2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb"
    ),
    "android_platform": 29,
    "build_tools": "36.0.0",
    "command_line_tools": "14742923",
}

# The build.gradle plugin coordinate and Gradle wrapper distribution the lock
# maps the pins onto, so the pin verifier can locate them without re-deriving.
LOCK_BUILD_FILES = {
    "agp_plugin_id": "com.android.application",
    "gradle_wrapper_property": "distributionUrl",
    "gradle_distribution_zip": "gradle-9.4.1-bin.zip",
}

REQUIRED_APKSIGNER_COMMAND = "apksigner verify --print-certs -Werr"
REQUIRED_SDK_PACKAGE_PATHS = ("platforms;android-29", "build-tools;36.0.0")

# --- Field formats -----------------------------------------------------------

SEMVER = r"^[0-9]+\.[0-9]+\.[0-9]+$"
EVIDENCE_ID = r"^[a-z0-9][a-z0-9-]{7,63}$"
REPOSITORY = r"^[A-Za-z0-9_.-]{1,64}/[A-Za-z0-9_.-]{1,64}$"
COMMIT_SHA = r"^[0-9a-f]{40}$"
RELEASE_TAG = r"^v[0-9]+\.[0-9]+\.[0-9]+$"
APK_FILENAME = r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.apk$"
SHA256_LOWER_HEX = r"^[0-9a-f]{64}$"
APPLICATION_ID = r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$"
CERT_FINGERPRINT = r"^(?:[0-9A-F]{2}:){31}[0-9A-F]{2}$"
# A reference into a private secret store — scheme plus slash-separated slug
# path only. This deliberately cannot express inline key material, host names,
# query strings, or credentials.
SECRET_REFERENCE = (
    r"^(?:infisical|vaultwarden)://"
    r"[A-Za-z0-9][A-Za-z0-9._-]*(?:/[A-Za-z0-9][A-Za-z0-9._-]*)+$"
)
SDK_PACKAGE_PATH = r"^[a-z][a-z0-9-]*(?:;[A-Za-z0-9][A-Za-z0-9._-]*)+$"
SDK_REVISION = r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$"
CMDLINE_TOOLS_BUILD = r"^[0-9]{6,12}$"
ENVIRONMENT_ID = r"^[a-z0-9][a-z0-9._-]{1,63}$"
CI_PROVIDER = r"^[a-z0-9][a-z0-9-]{1,31}$"
WORKFLOW_FILE = r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}\.ya?ml$"
# A public https GitHub Actions run URL — no credentials, no host with a
# private-endpoint suffix.
RUN_URL = (
    r"^https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/actions/runs/"
    r"[0-9]{1,32}$"
)

ERROR_CODES = (
    "format_invalid",
    "hygiene_violation",
    "io_error",
    "json_invalid",
    "missing_field",
    "pin_mismatch",
    "policy_invalid",
    "schema_version_unsupported",
    "state_invalid",
    "type_invalid",
    "unknown_field",
    "usage_error",
)

SEMANTIC_RULES = (
    "toolchain.jdk_major, .android_gradle_plugin, .gradle, "
    ".gradle_distribution_sha256, .android_platform, .build_tools, and "
    ".command_line_tools must equal the pinned release toolchain",
    "toolchain.dependency_verification and .dependency_locking must be true; "
    ".allows_dynamic_versions and .allows_snapshot_dependencies must be false; "
    ".actions_fully_sha_pinned must be true",
    "sdk_packages must include the pinned 'platforms;android-29' and "
    "'build-tools;36.0.0' packages, each with a resolved revision and digest",
    "package.version_name must equal source.release_tag without its leading 'v'",
    "native_libraries.present must be false and native_libraries.entries empty "
    "(the release APK carries no native code)",
    "signing.apksigner_verified must be true and signing.apksigner_command must "
    "be exactly 'apksigner verify --print-certs -Werr'",
    "reproducibility.builds must record at least two builds; when "
    "reproducibility.byte_identical is true, .compared must be true, every "
    "build digest must be equal, and artifact.sha256 must equal that digest",
    "reproducibility.byte_identical must be true whenever .compared is true and "
    "all build digests are equal (no unclaimed reproducibility)",
)

# --- Node constructors for SPEC ----------------------------------------------


def _string(pattern, description, nullable=False):
    return {
        "kind": "string",
        "pattern": pattern,
        "description": description,
        "nullable": nullable,
    }


def _integer(minimum, description):
    return {"kind": "integer", "minimum": minimum, "description": description}


def _boolean(description):
    return {"kind": "boolean", "description": description}


def _array(items, description, min_items=1):
    return {
        "kind": "array",
        "items": items,
        "min_items": min_items,
        "description": description,
    }


def _object(properties, description):
    return {"kind": "object", "properties": properties, "description": description}


# Release-evidence contract v1. Every property at every level is required and
# no unknown properties are accepted anywhere.
SPEC = _object(
    {
        "schema_version": _string(
            SEMVER, "Release-evidence contract version this document claims."
        ),
        "evidence_id": _string(
            EVIDENCE_ID, "Stable lowercase slug identifying this evidence record."
        ),
        "source": _object(
            {
                "repository": _string(
                    REPOSITORY, "GitHub owner/name the release was built from."
                ),
                "commit_sha": _string(
                    COMMIT_SHA,
                    "Exact 40-hex reviewed commit the tag and artifact resolve to.",
                ),
                "release_tag": _string(
                    RELEASE_TAG, "SemVer release tag (vMAJOR.MINOR.PATCH)."
                ),
            },
            "Exact public source identity the release was built from.",
        ),
        "toolchain": _object(
            {
                "jdk_major": _integer(1, "Pinned JDK major version."),
                "android_gradle_plugin": _string(
                    SEMVER, "Pinned Android Gradle Plugin version."
                ),
                "gradle": _string(SEMVER, "Pinned Gradle version."),
                "gradle_distribution_sha256": _string(
                    SHA256_LOWER_HEX,
                    "SHA-256 of the pinned Gradle distribution archive.",
                ),
                "android_platform": _integer(1, "Pinned Android platform API level."),
                "build_tools": _string(SEMVER, "Pinned SDK Build Tools version."),
                "command_line_tools": _string(
                    CMDLINE_TOOLS_BUILD, "Pinned Command-line Tools build number."
                ),
                "dependency_verification": _boolean(
                    "Gradle dependency verification was enforced."
                ),
                "dependency_locking": _boolean(
                    "Gradle dependency locking was enforced."
                ),
                "allows_dynamic_versions": _boolean(
                    "Whether dynamic version selectors were permitted (must be false)."
                ),
                "allows_snapshot_dependencies": _boolean(
                    "Whether SNAPSHOT dependencies were permitted (must be false)."
                ),
                "actions_fully_sha_pinned": _boolean(
                    "Every GitHub Action used was pinned to a full commit SHA."
                ),
            },
            "The pinned, non-dynamic build inputs.",
        ),
        "sdk_packages": _array(
            _object(
                {
                    "path": _string(
                        SDK_PACKAGE_PATH,
                        "sdkmanager package path, e.g. 'platforms;android-29'.",
                    ),
                    "revision": _string(
                        SDK_REVISION, "Resolved package revision."
                    ),
                    "sha256": _string(
                        SHA256_LOWER_HEX, "Resolved package archive digest."
                    ),
                },
                "One resolved SDK package with its revision and digest.",
            ),
            "Resolved SDK packages with revisions and digests.",
        ),
        "artifact": _object(
            {
                "filename": _string(
                    APK_FILENAME, "Bare artifact filename (no directory separators)."
                ),
                "sha256": _string(
                    SHA256_LOWER_HEX,
                    "SHA-256 digest of the signed artifact, 64 lowercase hex.",
                ),
                "size_bytes": _integer(1, "Artifact size in bytes."),
            },
            "The signed release artifact and its digest.",
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
        "native_libraries": _object(
            {
                "present": _boolean(
                    "Whether the artifact contains native libraries (must be false)."
                ),
                "entries": _array(
                    _string(
                        r"^[!-~][ -~]{0,255}$", "One native-library archive entry."
                    ),
                    "Native-library entries found (must be empty).",
                    min_items=0,
                ),
            },
            "Proven absence of native libraries in the artifact.",
        ),
        "signing": _object(
            {
                "certificate_sha256_fingerprint": _string(
                    CERT_FINGERPRINT,
                    "Public SHA-256 certificate fingerprint: 32 colon-separated "
                    "uppercase hex pairs. Never key material.",
                ),
                "key_reference": _string(
                    SECRET_REFERENCE,
                    "Private-store reference to the signing key "
                    "(infisical://… or vaultwarden://…). Never key material.",
                ),
                "apksigner_verified": _boolean(
                    "apksigner verification of the signed artifact succeeded."
                ),
                "apksigner_command": _string(
                    r"^[ -~]{1,128}$",
                    "The exact apksigner verification command that was run.",
                ),
            },
            "Signing certificate reference and verification result.",
        ),
        "reproducibility": _object(
            {
                "compared": _boolean(
                    "Two independent clean-environment builds were compared."
                ),
                "byte_identical": _boolean(
                    "The compared builds were byte-for-byte identical."
                ),
                "builds": _array(
                    _object(
                        {
                            "environment": _string(
                                ENVIRONMENT_ID,
                                "Identifier of the clean build environment.",
                            ),
                            "artifact_sha256": _string(
                                SHA256_LOWER_HEX,
                                "SHA-256 of this environment's built artifact.",
                            ),
                        },
                        "One independent clean-environment build.",
                    ),
                    "Independent clean-environment builds (at least two).",
                    min_items=2,
                ),
            },
            "Reproducibility double-build comparison.",
        ),
        "ci": _object(
            {
                "provider": _string(CI_PROVIDER, "CI provider slug."),
                "workflow": _string(
                    WORKFLOW_FILE, "Workflow file that produced the release."
                ),
                "run_url": _string(RUN_URL, "Public URL of the CI run."),
            },
            "The CI run that produced and published the release.",
        ),
    },
    "Deterministic, public-safe provenance record for one tx10-clock release.",
)

# --- Hygiene rules -----------------------------------------------------------
# Applied to every string value in the document. The regex sources are written
# so their literal text never matches this repository's public-hygiene scan.

HYGIENE_RULES = (
    (
        "local_absolute_path",
        re.compile(r"(?:(?:^|[\s(\"'=\[])/(?!/)[^\s\"'<>]*|[A-Za-z]:\\|~/)"),
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
            r"|\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"
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


# --- Structural validation ---------------------------------------------------


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
        if re.fullmatch(spec["pattern"], value) is None:
            errors.append(
                _error("format_invalid", path, "value does not match required format")
            )
    elif kind == "integer":
        if isinstance(value, bool) or not isinstance(value, int):
            errors.append(_error("type_invalid", path, "value must be an integer"))
            return
        if value < spec["minimum"]:
            errors.append(
                _error("format_invalid", path, "value must be >= %d" % spec["minimum"])
            )
    elif kind == "boolean":
        if not isinstance(value, bool):
            errors.append(_error("type_invalid", path, "value must be a boolean"))
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
                        "field is not part of the release-evidence contract",
                    )
                )


# --- Semantic validation -----------------------------------------------------


def _check_semantics(doc, errors):
    _check_toolchain(doc["toolchain"], errors)
    _check_sdk_packages(doc["sdk_packages"], errors)
    _check_version(doc, errors)
    _check_native(doc["native_libraries"], errors)
    _check_signing(doc["signing"], errors)
    _check_reproducibility(doc, errors)


def _check_toolchain(toolchain, errors):
    for field, expected in PINNED.items():
        if toolchain[field] != expected:
            errors.append(
                _error(
                    "pin_mismatch",
                    "$.toolchain.%s" % field,
                    "value must equal the pinned release toolchain",
                )
            )
    policy = {
        "dependency_verification": True,
        "dependency_locking": True,
        "allows_dynamic_versions": False,
        "allows_snapshot_dependencies": False,
        "actions_fully_sha_pinned": True,
    }
    for field, expected in policy.items():
        if toolchain[field] is not expected:
            errors.append(
                _error(
                    "policy_invalid",
                    "$.toolchain.%s" % field,
                    "value must be %s for a pinned release" % str(expected).lower(),
                )
            )


def _check_sdk_packages(packages, errors):
    present = {pkg["path"] for pkg in packages}
    for required in REQUIRED_SDK_PACKAGE_PATHS:
        if required not in present:
            errors.append(
                _error(
                    "state_invalid",
                    "$.sdk_packages",
                    "resolved SDK packages must include '%s'" % required,
                )
            )


def _check_version(doc, errors):
    tag = doc["source"]["release_tag"]
    version_name = doc["package"]["version_name"]
    if tag[1:] != version_name:
        errors.append(
            _error(
                "state_invalid",
                "$.package.version_name",
                "version_name must equal release_tag without its leading 'v'",
            )
        )


def _check_native(native, errors):
    if native["present"] is not False:
        errors.append(
            _error(
                "policy_invalid",
                "$.native_libraries.present",
                "the release artifact must contain no native libraries",
            )
        )
    if native["entries"]:
        errors.append(
            _error(
                "state_invalid",
                "$.native_libraries.entries",
                "native_libraries.entries must be empty when present is false",
            )
        )


def _check_signing(signing, errors):
    if signing["apksigner_verified"] is not True:
        errors.append(
            _error(
                "policy_invalid",
                "$.signing.apksigner_verified",
                "the signed artifact must pass apksigner verification",
            )
        )
    if signing["apksigner_command"] != REQUIRED_APKSIGNER_COMMAND:
        errors.append(
            _error(
                "policy_invalid",
                "$.signing.apksigner_command",
                "apksigner_command must be exactly '%s'"
                % REQUIRED_APKSIGNER_COMMAND,
            )
        )


def _check_reproducibility(doc, errors):
    repro = doc["reproducibility"]
    digests = {build["artifact_sha256"] for build in repro["builds"]}
    all_equal = len(digests) == 1
    if repro["byte_identical"]:
        if not repro["compared"]:
            errors.append(
                _error(
                    "state_invalid",
                    "$.reproducibility.compared",
                    "byte_identical requires compared to be true",
                )
            )
        if not all_equal:
            errors.append(
                _error(
                    "state_invalid",
                    "$.reproducibility.builds",
                    "byte_identical requires every build digest to be equal",
                )
            )
        elif doc["artifact"]["sha256"] not in digests:
            errors.append(
                _error(
                    "state_invalid",
                    "$.artifact.sha256",
                    "byte_identical requires artifact.sha256 to equal the "
                    "reproduced build digest",
                )
            )
    elif repro["compared"] and all_equal:
        errors.append(
            _error(
                "state_invalid",
                "$.reproducibility.byte_identical",
                "byte_identical must be true when compared builds share one digest",
            )
        )


# --- Hygiene validation ------------------------------------------------------


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


# --- Document validation -----------------------------------------------------


def validate_document(doc):
    """Validate a parsed evidence document. Returns a sorted error list."""
    errors = []
    if not isinstance(doc, dict):
        return [_error("type_invalid", "$", "evidence document must be a JSON object")]

    if "schema_version" not in doc:
        return [
            _error("missing_field", "$.schema_version", "required field is missing")
        ]
    version = doc.get("schema_version")
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


# --- JSON loading ------------------------------------------------------------


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


def parse_evidence(text):
    """Parse evidence text. Returns (document, errors)."""
    try:
        doc = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_constant,
        )
    except ValueError as exc:
        return None, [_error("json_invalid", "$", "input is not strict JSON: %s" % exc)]
    return doc, []


# --- Schema / lock emission --------------------------------------------------


def _node_to_json_schema(spec):
    kind = spec["kind"]
    if kind == "string":
        return {
            "type": ["string", "null"] if spec["nullable"] else "string",
            "pattern": spec["pattern"],
            "description": spec["description"],
        }
    if kind == "integer":
        return {
            "type": "integer",
            "minimum": spec["minimum"],
            "description": spec["description"],
        }
    if kind == "boolean":
        return {"type": "boolean", "description": spec["description"]}
    if kind == "array":
        return {
            "type": "array",
            "items": _node_to_json_schema(spec["items"]),
            "minItems": spec["min_items"],
            "description": spec["description"],
        }
    if kind == "object":
        return {
            "type": "object",
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
                "pinned_toolchain": dict(PINNED),
                "required_sdk_packages": list(REQUIRED_SDK_PACKAGE_PATHS),
                "required_apksigner_command": REQUIRED_APKSIGNER_COMMAND,
                "semantic_rules": list(SEMANTIC_RULES),
                "hygiene_categories": list(HYGIENE_CATEGORIES),
                "error_codes": list(ERROR_CODES),
            },
        }
    )
    return json.dumps(schema, indent=2, sort_keys=True) + "\n"


def emit_lock():
    """Render the pinned release toolchain to the committed lock document."""
    lock = {
        "contract": CONTRACT_NAME,
        "lock_version": "1.0.0",
        "note": (
            "Generated by tools/release-evidence/validate_release_evidence.py "
            "--emit-lock. Single source of truth for the pinned release "
            "toolchain; do not edit by hand."
        ),
        "toolchain": dict(PINNED),
        "build_files": dict(LOCK_BUILD_FILES),
        "policy": {
            "dependency_verification": True,
            "dependency_locking": True,
            "allows_dynamic_versions": False,
            "allows_snapshot_dependencies": False,
            "actions_fully_sha_pinned": True,
        },
        "required_sdk_packages": list(REQUIRED_SDK_PACKAGE_PATHS),
        "required_apksigner_command": REQUIRED_APKSIGNER_COMMAND,
    }
    return json.dumps(lock, indent=2, sort_keys=True) + "\n"


# --- CLI ---------------------------------------------------------------------


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
    if argv == ["--emit-lock"]:
        sys.stdout.write(emit_lock())
        return 0
    if len(argv) != 1 or (argv[0].startswith("-") and argv[0] != "-"):
        _print_report(
            build_report(
                None,
                [
                    _error(
                        "usage_error",
                        "$",
                        "usage: validate_release_evidence.py "
                        "<evidence.json | -> | --emit-schema | --emit-lock",
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

    doc, errors = parse_evidence(text)
    if not errors:
        errors = validate_document(doc)
    _print_report(build_report(input_name, errors))
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
