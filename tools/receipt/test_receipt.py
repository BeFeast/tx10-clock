"""Host-only test suite for the TX10 Clock release/delivery receipt contract.

Runs with nothing but the Python 3 standard library: no Android SDK, no network,
no signing key, no device. It drives every bundled fixture through the validator,
proves the CLI's exit codes and output are stable, and scans the shipped tree for
private-path / secret leakage.

Run:  python3 -m unittest -v test_receipt      (or ./run-tests.sh)
"""

import json
import os
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import receipt_validator as rv  # noqa: E402

REPO_ROOT = rv.REPO_ROOT
FIXTURES = os.path.join(REPO_ROOT, "release", "receipt", "fixtures")
VALID_DIR = os.path.join(FIXTURES, "valid")
INVALID_DIR = os.path.join(FIXTURES, "invalid")
EXPECTED_PATH = os.path.join(FIXTURES, "expected.json")
CLI = os.path.join(HERE, "validate.py")

# Directories/files that ship for the express purpose of exercising the
# rejection paths (invalid fixtures) or that define the hygiene detectors
# themselves. They are excluded from the shipped-tree hygiene scan.
_HYGIENE_SCAN_SKIP_DIRS = {os.path.join(INVALID_DIR, "")}
_HYGIENE_SCAN_SKIP_FILES = {"receipt_validator.py", "test_receipt.py"}

# Concrete execution-host / operator markers that must never appear in any
# shipped file, invalid fixtures included.
_HOST_LEAK_MARKERS = ["/home/god", "Obsidian", "10.10.0."]

SHIPPED_ROOTS = [
    os.path.join(REPO_ROOT, "release", "receipt"),
    os.path.join(REPO_ROOT, "tools", "receipt"),
]


def _read_json(path):
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def _read_text(path):
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return fh.read()


def _list_json(directory):
    return sorted(
        os.path.join(directory, n)
        for n in os.listdir(directory)
        if n.endswith(".json")
    )


def _iter_shipped_files():
    for root in SHIPPED_ROOTS:
        for dirpath, _dirs, files in os.walk(root):
            if "__pycache__" in dirpath:
                continue
            for name in sorted(files):
                yield os.path.join(dirpath, name)


def _run_cli(*args, stdin=None):
    return subprocess.run(
        [sys.executable, CLI, *args],
        cwd=HERE,
        input=stdin,
        capture_output=True,
        text=True,
    )


class SchemaTest(unittest.TestCase):
    def test_schema_loads_and_is_versioned(self):
        schema = rv.load_schema()
        self.assertEqual(schema.get("type"), "object")
        self.assertEqual(
            schema["properties"]["schema_version"]["const"], rv.SCHEMA_VERSION
        )
        self.assertIs(schema.get("additionalProperties"), False)

    def test_schema_requires_every_contract_field(self):
        schema = rv.load_schema()
        required = set(schema["required"])
        # Each acceptance-criteria concept has a home in the required set.
        for field in [
            "schema_version", "source", "release", "asset", "package",
            "signing", "approval", "delivery", "verification", "rollback",
        ]:
            self.assertIn(field, required)
        self.assertIn("commit", schema["properties"]["source"]["required"])
        self.assertIn("sha256", schema["properties"]["asset"]["required"])
        self.assertIn(
            "certificate_fingerprint_sha256",
            schema["properties"]["signing"]["required"],
        )


class ValidFixtureTest(unittest.TestCase):
    def test_valid_fixtures_have_no_errors(self):
        files = _list_json(VALID_DIR)
        self.assertGreaterEqual(len(files), 5, "expected a spread of valid fixtures")
        for path in files:
            with self.subTest(fixture=os.path.basename(path)):
                errors = rv.validate_receipt(_read_json(path))
                self.assertEqual(
                    errors, [], f"unexpected errors: {[e.code for e in errors]}"
                )


class InvalidFixtureTest(unittest.TestCase):
    def setUp(self):
        self.expected = _read_json(EXPECTED_PATH)["expected_error_codes"]

    def test_manifest_covers_exactly_the_invalid_dir(self):
        on_disk = {os.path.basename(p) for p in _list_json(INVALID_DIR)}
        self.assertEqual(
            on_disk,
            set(self.expected),
            "expected.json is out of sync with fixtures/invalid/",
        )

    def test_invalid_fixtures_emit_expected_code(self):
        for path in _list_json(INVALID_DIR):
            name = os.path.basename(path)
            with self.subTest(fixture=name):
                errors = rv.validate_receipt(_read_json(path))
                self.assertNotEqual(errors, [], "expected at least one error")
                codes = {e.code for e in errors}
                self.assertIn(
                    self.expected[name],
                    codes,
                    f"{name}: expected {self.expected[name]}, got {sorted(codes)}",
                )


class CliContractTest(unittest.TestCase):
    def test_valid_fixture_exits_zero(self):
        proc = _run_cli(*_list_json(VALID_DIR))
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        report = json.loads(proc.stdout)
        self.assertTrue(report["ok"])

    def test_invalid_fixture_exits_nonzero(self):
        proc = _run_cli(os.path.join(INVALID_DIR, "unknown-field.receipt.json"))
        self.assertEqual(proc.returncode, 1)
        self.assertFalse(json.loads(proc.stdout)["ok"])

    def test_bad_json_exits_two(self):
        proc = _run_cli("-", stdin="{ this is not json")
        self.assertEqual(proc.returncode, 2)

    def test_missing_file_exits_two(self):
        proc = _run_cli(os.path.join(INVALID_DIR, "does-not-exist.json"))
        self.assertEqual(proc.returncode, 2)

    def test_output_is_byte_stable(self):
        args = _list_json(INVALID_DIR)
        first = _run_cli(*args).stdout
        second = _run_cli(*args).stdout
        self.assertEqual(first, second, "validator output is not deterministic")

    def test_output_never_echoes_the_offending_value(self):
        # Each hygiene fixture embeds a synthetic secret; none of it may surface
        # in the machine-readable output.
        leaks = {
            "hygiene-private-key.receipt.json": "EXAMPLE-NOT-REAL",
            "hygiene-github-token.receipt.json": "ghp_0123456789abcdefghij",
            "hygiene-private-endpoint.receipt.json": "10.0.0.5",
            "hygiene-absolute-path.receipt.json": "/home/example",
        }
        for fixture, needle in leaks.items():
            with self.subTest(fixture=fixture):
                proc = _run_cli(os.path.join(INVALID_DIR, fixture))
                self.assertEqual(proc.returncode, 1)
                self.assertNotIn(needle, proc.stdout)
                self.assertFalse(json.loads(proc.stdout)["ok"])


class ShippedTreeHygieneTest(unittest.TestCase):
    """The files this feature ships must themselves be public-safe."""

    def test_no_host_or_operator_markers_anywhere(self):
        for path in _iter_shipped_files():
            if path.endswith(".pyc"):
                continue
            # The detector-defining modules name these markers on purpose.
            if os.path.basename(path) in _HYGIENE_SCAN_SKIP_FILES:
                continue
            with self.subTest(file=os.path.relpath(path, REPO_ROOT)):
                text = _read_text(path)
                for marker in _HOST_LEAK_MARKERS:
                    self.assertNotIn(
                        marker, text, f"host/operator marker {marker!r} leaked"
                    )

    def test_detector_finds_nothing_in_public_files(self):
        # Run the real hygiene detector over the shipped tree, excluding the
        # by-design forbidden invalid fixtures and the modules that define the
        # detector patterns.
        scanned = 0
        for path in _iter_shipped_files():
            rel = os.path.relpath(path, REPO_ROOT)
            if os.path.basename(path) in _HYGIENE_SCAN_SKIP_FILES:
                continue
            if any(path.startswith(skip) for skip in _HYGIENE_SCAN_SKIP_DIRS):
                continue
            if path.endswith(".pyc"):
                continue
            text = _read_text(path)
            with self.subTest(file=rel):
                findings = []
                rv._check_hygiene({"file": text}, findings)
                self.assertEqual(
                    findings,
                    [],
                    f"{rel}: {[ (f.code, f.message) for f in findings]}",
                )
            scanned += 1
        self.assertGreater(scanned, 0, "no public files were scanned")


if __name__ == "__main__":
    unittest.main()
