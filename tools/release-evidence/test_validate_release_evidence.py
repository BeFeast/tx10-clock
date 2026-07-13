#!/usr/bin/env python3
"""Host-only tests for the release-evidence validator.

Runs under plain Python 3 (standard library only): no Android SDK, no
network, no signing key, no device. Deterministic — every input is a
committed fixture or an in-memory mutation of one.
"""

import copy
import json
import os
import re
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
FIXTURES = os.path.join(ROOT, "release", "evidence", "fixtures")
SCHEMA_FILE = os.path.join(
    ROOT, "release", "evidence", "schema", "evidence-v1.schema.json"
)
LOCK_FILE = os.path.join(ROOT, "release", "toolchain.lock.json")
VALIDATOR = os.path.join(HERE, "validate_release_evidence.py")

sys.path.insert(0, HERE)
import validate_release_evidence as ve  # noqa: E402


def load_fixture(kind, name):
    with open(os.path.join(FIXTURES, kind, name), "r", encoding="utf-8") as fh:
        return fh.read()


def validate_text(text):
    doc, errors = ve.parse_evidence(text)
    if errors:
        return errors
    return ve.validate_document(doc)


def valid_doc():
    doc, errors = ve.parse_evidence(load_fixture("valid", "v0.1.0-signed-release.json"))
    assert not errors
    return doc


def codes(errors):
    return sorted({e["code"] for e in errors})


class FixtureTests(unittest.TestCase):
    """Every committed fixture must land on the expected side."""

    def test_all_valid_fixtures_pass(self):
        names = sorted(os.listdir(os.path.join(FIXTURES, "valid")))
        self.assertEqual(len(names), 2)
        for name in names:
            errors = validate_text(load_fixture("valid", name))
            self.assertEqual(errors, [], "valid fixture %s rejected: %r" % (name, errors))

    def test_all_invalid_fixtures_fail(self):
        names = sorted(os.listdir(os.path.join(FIXTURES, "invalid")))
        self.assertEqual(len(names), 18)
        for name in names:
            errors = validate_text(load_fixture("invalid", name))
            self.assertTrue(errors, "invalid fixture %s was accepted" % name)

    def test_invalid_fixtures_fail_for_the_documented_reason(self):
        expected = {
            "missing-field.json": "missing_field",
            "unknown-field.json": "unknown_field",
            "type-invalid.json": "type_invalid",
            "digest-format-mismatch.json": "format_invalid",
            "toolchain-agp-unpinned.json": "pin_mismatch",
            "toolchain-gradle-sha-mismatch.json": "pin_mismatch",
            "dependency-dynamic-allowed.json": "policy_invalid",
            "actions-not-sha-pinned.json": "policy_invalid",
            "native-libraries-present.json": "policy_invalid",
            "signing-not-verified.json": "policy_invalid",
            "version-tag-mismatch.json": "state_invalid",
            "sdk-package-missing.json": "state_invalid",
            "reproducibility-unclaimed.json": "state_invalid",
            "reproducibility-false-claim.json": "state_invalid",
            "signing-key-material.json": "hygiene_violation",
            "hygiene-local-path.json": "hygiene_violation",
            "hygiene-private-endpoint.json": "hygiene_violation",
            "schema-version-unsupported.json": "schema_version_unsupported",
        }
        self.assertEqual(
            sorted(expected), sorted(os.listdir(os.path.join(FIXTURES, "invalid")))
        )
        for name, code in expected.items():
            errors = validate_text(load_fixture("invalid", name))
            self.assertIn(
                code, codes(errors), "%s: expected %s, got %r" % (name, code, errors)
            )


class SchemaAndLockDriftTests(unittest.TestCase):
    def test_committed_schema_matches_emitter(self):
        with open(SCHEMA_FILE, "r", encoding="utf-8") as fh:
            committed = fh.read()
        self.assertEqual(
            committed,
            ve.emit_schema(),
            "schema drifted from validator; regenerate with --emit-schema",
        )

    def test_committed_lock_matches_emitter(self):
        with open(LOCK_FILE, "r", encoding="utf-8") as fh:
            committed = fh.read()
        self.assertEqual(
            committed,
            ve.emit_lock(),
            "toolchain lock drifted from validator; regenerate with --emit-lock",
        )


class PinnedToolchainMatchesBuildFiles(unittest.TestCase):
    """The validator's pins are the single source of truth; the checked-in
    build files must declare exactly those versions."""

    def test_build_gradle_declares_pinned_agp(self):
        with open(os.path.join(ROOT, "build.gradle"), "r", encoding="utf-8") as fh:
            text = fh.read()
        self.assertIn(
            "id 'com.android.application' version '%s'"
            % ve.PINNED["android_gradle_plugin"],
            text,
        )

    def test_wrapper_declares_pinned_gradle_and_sha(self):
        path = os.path.join(ROOT, "gradle", "wrapper", "gradle-wrapper.properties")
        with open(path, "r", encoding="utf-8") as fh:
            text = fh.read()
        self.assertIn("gradle-%s-bin.zip" % ve.PINNED["gradle"], text)
        self.assertIn(
            "distributionSha256Sum=%s" % ve.PINNED["gradle_distribution_sha256"], text
        )

    def test_app_gradle_declares_pinned_build_tools(self):
        with open(os.path.join(ROOT, "app", "build.gradle"), "r", encoding="utf-8") as fh:
            text = fh.read()
        self.assertIn('buildToolsVersion "%s"' % ve.PINNED["build_tools"], text)


class ToolchainSemanticsTests(unittest.TestCase):
    def test_each_pin_mismatch_is_reported(self):
        for field, bad in (
            ("jdk_major", 21),
            ("android_gradle_plugin", "9.1.0"),
            ("gradle", "9.4.0"),
            ("gradle_distribution_sha256", "0" * 64),
            ("android_platform", 34),
            ("build_tools", "35.0.0"),
            ("command_line_tools", "11076708"),
        ):
            doc = valid_doc()
            doc["toolchain"][field] = bad
            self.assertIn("pin_mismatch", codes(ve.validate_document(doc)), field)

    def test_policy_flags_enforced(self):
        for field, bad in (
            ("dependency_verification", False),
            ("dependency_locking", False),
            ("allows_dynamic_versions", True),
            ("allows_snapshot_dependencies", True),
            ("actions_fully_sha_pinned", False),
        ):
            doc = valid_doc()
            doc["toolchain"][field] = bad
            self.assertIn("policy_invalid", codes(ve.validate_document(doc)), field)


class OtherSemanticTests(unittest.TestCase):
    def test_version_name_must_match_tag(self):
        doc = valid_doc()
        doc["package"]["version_name"] = "0.9.9"
        self.assertIn("state_invalid", codes(ve.validate_document(doc)))

    def test_required_sdk_packages_enforced(self):
        doc = valid_doc()
        doc["sdk_packages"] = [
            p for p in doc["sdk_packages"] if p["path"] != "platforms;android-29"
        ]
        self.assertIn("state_invalid", codes(ve.validate_document(doc)))

    def test_native_libraries_must_be_absent(self):
        doc = valid_doc()
        doc["native_libraries"]["present"] = True
        self.assertIn("policy_invalid", codes(ve.validate_document(doc)))
        doc = valid_doc()
        doc["native_libraries"]["entries"] = ["lib/x86/libfoo.so"]
        self.assertIn("state_invalid", codes(ve.validate_document(doc)))

    def test_apksigner_command_must_be_exact(self):
        doc = valid_doc()
        doc["signing"]["apksigner_command"] = "apksigner verify"
        self.assertIn("policy_invalid", codes(ve.validate_document(doc)))

    def test_byte_identical_requires_matching_and_compared(self):
        # Not compared but claimed identical.
        doc = valid_doc()
        doc["reproducibility"]["compared"] = False
        self.assertIn("state_invalid", codes(ve.validate_document(doc)))
        # Claimed identical but the builds differ.
        doc = valid_doc()
        doc["reproducibility"]["builds"][1]["artifact_sha256"] = "1" * 64
        self.assertIn("state_invalid", codes(ve.validate_document(doc)))

    def test_matching_builds_may_not_hide_reproducibility(self):
        doc = valid_doc()
        doc["reproducibility"]["byte_identical"] = False
        # builds already share one digest -> must not claim non-identical
        self.assertIn("state_invalid", codes(ve.validate_document(doc)))

    def test_artifact_digest_must_equal_reproduced_digest(self):
        doc = valid_doc()
        doc["artifact"]["sha256"] = "a" * 64
        self.assertIn("state_invalid", codes(ve.validate_document(doc)))


class SigningReferenceTests(unittest.TestCase):
    def test_only_private_store_references_accepted(self):
        for ref in (
            "https://example.com/key",
            "file://key.jks",
            "s3://bucket/key",
            "signing-key",
        ):
            doc = valid_doc()
            doc["signing"]["key_reference"] = ref
            self.assertIn("format_invalid", codes(ve.validate_document(doc)), ref)

    def test_private_store_references_accepted(self):
        for ref in (
            "infisical://tx10-clock/release/signing-key",
            "vaultwarden://tx10-clock-release/signing-key",
        ):
            doc = valid_doc()
            doc["signing"]["key_reference"] = ref
            self.assertEqual([], ve.validate_document(doc), ref)


class HygieneTests(unittest.TestCase):
    def test_key_material_rejected(self):
        doc = valid_doc()
        doc["signing"]["apksigner_command"] = "bearer abcdef0123456789"
        self.assertIn("hygiene_violation", codes(ve.validate_document(doc)))

    def test_private_endpoint_rejected(self):
        doc = valid_doc()
        doc["ci"]["provider"] = "runner"
        doc["signing"]["key_reference"] = "vaultwarden://host.internal/key"
        self.assertIn("hygiene_violation", codes(ve.validate_document(doc)))

    def test_validator_never_echoes_offending_value(self):
        doc = valid_doc()
        secret = "ghp_" + "z" * 36
        doc["signing"]["apksigner_command"] = secret
        report = ve.build_report("in", ve.validate_document(doc))
        self.assertNotIn(secret, json.dumps(report))


class JsonLoadingTests(unittest.TestCase):
    def test_duplicate_keys_rejected(self):
        text = '{"schema_version": "1.0.0", "schema_version": "1.0.0"}'
        _doc, errors = ve.parse_evidence(text)
        self.assertEqual(codes(errors), ["json_invalid"])

    def test_non_finite_rejected(self):
        text = '{"schema_version": "1.0.0", "artifact": {"size_bytes": NaN}}'
        _doc, errors = ve.parse_evidence(text)
        self.assertEqual(codes(errors), ["json_invalid"])

    def test_non_object_document_rejected(self):
        self.assertEqual(codes(ve.validate_document([])), ["type_invalid"])


class CliTests(unittest.TestCase):
    def _run(self, args, stdin=None):
        return subprocess.run(
            [sys.executable, VALIDATOR, *args],
            input=stdin,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )

    def test_valid_fixture_exit_zero(self):
        p = self._run([os.path.join(FIXTURES, "valid", "v0.1.0-signed-release.json")])
        self.assertEqual(p.returncode, 0)
        self.assertTrue(json.loads(p.stdout)["valid"])

    def test_invalid_fixture_exit_one(self):
        p = self._run([os.path.join(FIXTURES, "invalid", "missing-field.json")])
        self.assertEqual(p.returncode, 1)
        self.assertFalse(json.loads(p.stdout)["valid"])

    def test_missing_file_exit_two(self):
        p = self._run([os.path.join(ROOT, "does-not-exist.json")])
        self.assertEqual(p.returncode, 2)

    def test_usage_error_exit_two(self):
        p = self._run(["--bogus"])
        self.assertEqual(p.returncode, 2)

    def test_emit_schema_is_valid_json(self):
        p = self._run(["--emit-schema"])
        self.assertEqual(p.returncode, 0)
        json.loads(p.stdout)

    def test_emit_lock_is_valid_json(self):
        p = self._run(["--emit-lock"])
        self.assertEqual(p.returncode, 0)
        json.loads(p.stdout)

    def test_stdin_dash(self):
        p = self._run(["-"], stdin=load_fixture("valid", "v0.1.0-signed-release.json"))
        self.assertEqual(p.returncode, 0)


class ErrorCodeCoverageTests(unittest.TestCase):
    def test_error_codes_are_sorted_and_unique(self):
        self.assertEqual(list(ve.ERROR_CODES), sorted(set(ve.ERROR_CODES)))

    def test_pinned_sha_is_lowercase_hex(self):
        self.assertRegex(ve.PINNED["gradle_distribution_sha256"], r"^[0-9a-f]{64}$")


if __name__ == "__main__":
    unittest.main(verbosity=2)
