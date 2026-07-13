#!/usr/bin/env python3
"""Host-only tests for the TX10 Clock configuration validator.

Pure Python 3 standard library: no Android SDK, no network, no device, no
signing material, and no third-party packages. Run via ``run-tests.sh`` or
``python3 -m unittest``.

The suite proves three things the issue requires:
  * every positive fixture validates and canonicalizes deterministically,
  * canonical output is stable and carries no endpoint/serial/secret/path/
    device identity,
  * every negative fixture fails closed with a nonzero CLI exit.
"""

import json
import re
import subprocess
import sys
import unittest
from copy import deepcopy
from pathlib import Path

import clock_config_validator as validator

HERE = Path(__file__).resolve().parent
MODULE = HERE / "clock_config_validator.py"
FIXTURES = HERE / "fixtures"
VALID_DIR = FIXTURES / "valid"
INVALID_DIR = FIXTURES / "invalid"

# Substrings/patterns that must never appear in canonical output. Endpoints,
# serials, secrets, private paths, and device identity have no home in the
# contract; this is defense in depth on top of the closed schema.
FORBIDDEN_PATTERNS = [
    re.compile(r"https?://"),
    re.compile(r"\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b"),  # IPv4-ish endpoint
    re.compile(r"/home/[a-z]"),                             # private host path
    re.compile(r"-----BEGIN"),                              # key material
    re.compile(r"\bghp_[A-Za-z0-9]{10,}"),                  # GitHub token
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),                    # AWS key id
    re.compile(r"\bserial\b", re.IGNORECASE),
    re.compile(r"Obsidian"),                                # operator vault name
]


def run_cli(path: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(MODULE), str(path)],
        capture_output=True,
        text=True,
    )


class SchemaSelfChecks(unittest.TestCase):
    def test_schema_loads_and_is_versioned(self):
        schema = validator.load_schema()
        self.assertEqual(schema["properties"]["schemaVersion"]["const"], 1)
        self.assertIn("schemaVersion", schema["required"])
        self.assertFalse(schema.get("additionalProperties", True))

    def test_schema_covers_every_required_concept(self):
        schema = validator.load_schema()
        props = schema["properties"]
        # 12h/24h selection, seconds + date visibility.
        self.assertEqual(
            set(props["display"]["properties"]["hourCycle"]["enum"]), {"12h", "24h"}
        )
        self.assertEqual(
            props["display"]["properties"]["hourCycle"]["default"], "12h"
        )
        self.assertIn("showSeconds", props["display"]["properties"])
        self.assertIn("showDate", props["display"]["properties"])
        # Locale / timezone policy.
        self.assertIn("timeZone", props["locale"]["properties"])
        self.assertIn("language", props["locale"]["properties"])
        # Smooth analog sweep.
        self.assertIn("smoothSweep", props["analog"]["properties"])
        # Burn-in shift enable / radius / interval.
        burn = props["burnInMitigation"]["properties"]
        self.assertIn("shiftEnabled", burn)
        self.assertIn("shiftRadiusPx", burn)
        self.assertIn("shiftIntervalSeconds", burn)
        # Refresh / runtime booleans.
        runtime = props["runtime"]["properties"]
        self.assertIn("allowManualRefresh", runtime)
        self.assertIn("keepScreenOn", runtime)
        self.assertIn("restoreOnBoot", runtime)


class PositiveFixtures(unittest.TestCase):
    def test_fixtures_exist(self):
        self.assertTrue(sorted(VALID_DIR.glob("*.json")), "no valid fixtures found")

    def test_each_valid_fixture_passes_and_is_clean(self):
        for path in sorted(VALID_DIR.glob("*.json")):
            with self.subTest(fixture=path.name):
                raw = path.read_bytes()
                canonical = validator.validate_to_canonical(raw)

                # Canonical output must parse and contain no forbidden content.
                json.loads(canonical)
                for pattern in FORBIDDEN_PATTERNS:
                    self.assertIsNone(
                        pattern.search(canonical),
                        f"{path.name}: forbidden content {pattern.pattern!r}",
                    )

                # The CLI agrees and exits zero with identical bytes.
                result = run_cli(path)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, canonical)

    def test_canonicalization_is_idempotent(self):
        for path in sorted(VALID_DIR.glob("*.json")):
            with self.subTest(fixture=path.name):
                once = validator.validate_to_canonical(path.read_bytes())
                twice = validator.validate_to_canonical(once.encode("utf-8"))
                self.assertEqual(once, twice, f"{path.name}: canonical not a fixed point")

    def test_canonicalization_ignores_key_order_and_whitespace(self):
        # Reordering keys and reformatting whitespace must not change output.
        document = json.loads((VALID_DIR / "full-explicit.json").read_bytes())
        baseline = validator.validate_to_canonical(json.dumps(document).encode("utf-8"))

        reordered = {k: document[k] for k in reversed(list(document))}
        spaced = json.dumps(reordered, indent=4).encode("utf-8")
        compact = json.dumps(reordered, separators=(",", ":")).encode("utf-8")

        self.assertEqual(validator.validate_to_canonical(spaced), baseline)
        self.assertEqual(validator.validate_to_canonical(compact), baseline)

    def test_minimal_config_normalizes_to_approved_12h_default(self):
        minimal = VALID_DIR / "minimal.json"
        expected = VALID_DIR / "minimal-normalized.json"

        normalized = validator.validate(minimal.read_bytes())
        self.assertEqual(normalized["display"]["hourCycle"], "12h")
        self.assertEqual(
            validator.canonicalize(normalized),
            validator.canonicalize(json.loads(expected.read_bytes())),
        )

    def test_schema_defaults_are_validated_by_normalizer(self):
        schema = deepcopy(validator.load_schema())
        schema["properties"]["display"]["properties"]["hourCycle"]["default"] = (
            "invalid-cycle"
        )

        with self.assertRaises(validator.ConfigValidationError) as raised:
            validator.validate(b'{"schemaVersion": 1}', schema)
        self.assertEqual(raised.exception.pointer, "/display/hourCycle")


class NegativeFixtures(unittest.TestCase):
    def test_fixtures_exist(self):
        self.assertTrue(sorted(INVALID_DIR.glob("*.json")), "no invalid fixtures found")

    def test_each_invalid_fixture_fails_closed(self):
        for path in sorted(INVALID_DIR.glob("*.json")):
            with self.subTest(fixture=path.name):
                raw = path.read_bytes()
                with self.assertRaises(
                    validator.ConfigValidationError,
                    msg=f"{path.name} was accepted but must fail closed",
                ):
                    validator.validate(raw)

                # The CLI must exit nonzero and emit nothing on stdout.
                result = run_cli(path)
                self.assertNotEqual(result.returncode, 0, f"{path.name} exited zero")
                self.assertEqual(result.stdout, "", f"{path.name} produced stdout")


class BoundaryChecks(unittest.TestCase):
    def test_oversized_document_is_rejected(self):
        # Generated in-test to avoid committing a large blob. A valid core is
        # padded past the byte ceiling with harmless whitespace.
        core = b'{"schemaVersion": 1}'
        padding = b" " * (validator.MAX_DOCUMENT_BYTES + 1 - len(core))
        oversized = padding + core
        self.assertGreater(len(oversized), validator.MAX_DOCUMENT_BYTES)
        with self.assertRaises(validator.ConfigValidationError):
            validator.validate(oversized)

    def test_document_at_limit_is_accepted(self):
        core = b'{"schemaVersion": 1}'
        padding = b" " * (validator.MAX_DOCUMENT_BYTES - len(core))
        at_limit = padding + core
        self.assertEqual(len(at_limit), validator.MAX_DOCUMENT_BYTES)
        # Should validate without raising.
        validator.validate(at_limit)

    def test_range_boundaries_are_inclusive(self):
        for radius in (0, 64):
            validator.validate(
                json.dumps({"schemaVersion": 1,
                            "burnInMitigation": {"shiftRadiusPx": radius}}).encode()
            )
        for seconds in (10, 21600):
            validator.validate(
                json.dumps({"schemaVersion": 1,
                            "burnInMitigation": {"shiftIntervalSeconds": seconds}}).encode()
            )

    def test_boolean_is_not_accepted_as_integer(self):
        with self.assertRaises(validator.ConfigValidationError):
            validator.validate(b'{"schemaVersion": true}')

    def test_usage_error_exit_code(self):
        # Two path arguments is a usage error (exit 2), distinct from a
        # validation failure (exit 1).
        result = subprocess.run(
            [sys.executable, str(MODULE), "a", "b"],
            capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    unittest.main()
