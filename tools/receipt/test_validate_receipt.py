#!/usr/bin/env python3
"""Host-only tests for the release-receipt validator.

Runs under plain Python 3 (standard library only): no Android SDK, no
network, no signing key, no device. Deterministic — every input is a
committed fixture or an in-memory mutation of one.
"""

import copy
import json
import os
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
FIXTURES = os.path.join(ROOT, "release", "receipt", "fixtures")
SCHEMA_FILE = os.path.join(ROOT, "release", "receipt", "schema", "receipt-v1.schema.json")
VALIDATOR = os.path.join(HERE, "validate_receipt.py")

sys.path.insert(0, HERE)
import validate_receipt as vr  # noqa: E402


def load_fixture(kind, name):
    with open(os.path.join(FIXTURES, kind, name), "r", encoding="utf-8") as fh:
        return fh.read()


def validate_text(text):
    doc, errors = vr.parse_receipt(text)
    if errors:
        return errors
    return vr.validate_document(doc)


def valid_doc():
    doc, errors = vr.parse_receipt(load_fixture("valid", "delivered-passed.json"))
    assert not errors
    return doc


def error_codes(errors):
    return sorted({e["code"] for e in errors})


class FixtureTests(unittest.TestCase):
    """Every committed fixture must land on the expected side."""

    def test_all_valid_fixtures_pass(self):
        names = sorted(os.listdir(os.path.join(FIXTURES, "valid")))
        self.assertEqual(len(names), 4)
        for name in names:
            errors = validate_text(load_fixture("valid", name))
            self.assertEqual(errors, [], "valid fixture %s rejected: %r" % (name, errors))

    def test_all_invalid_fixtures_fail(self):
        names = sorted(os.listdir(os.path.join(FIXTURES, "invalid")))
        self.assertEqual(len(names), 12)
        for name in names:
            errors = validate_text(load_fixture("invalid", name))
            self.assertTrue(errors, "invalid fixture %s was accepted" % name)

    def test_invalid_fixtures_fail_for_the_documented_reason(self):
        expected = {
            "missing-field.json": "missing_field",
            "unknown-field.json": "unknown_field",
            "type-invalid.json": "type_invalid",
            "digest-format-mismatch.json": "format_invalid",
            "transition-skip.json": "transition_invalid",
            "transition-reversal.json": "transition_invalid",
            "state-history-mismatch.json": "state_invalid",
            "verification-before-delivery.json": "state_invalid",
            "rollback-without-rolled-back.json": "state_invalid",
            "hygiene-local-path.json": "hygiene_violation",
            "hygiene-private-endpoint.json": "hygiene_violation",
            "hygiene-credential-material.json": "hygiene_violation",
        }
        self.assertEqual(
            sorted(expected), sorted(os.listdir(os.path.join(FIXTURES, "invalid")))
        )
        for name, code in expected.items():
            errors = validate_text(load_fixture("invalid", name))
            self.assertIn(
                code,
                error_codes(errors),
                "%s: expected code %s, got %r" % (name, code, errors),
            )


class StructuralTests(unittest.TestCase):
    def test_every_field_is_required(self):
        # Deleting any single field anywhere in a valid document must fail
        # with missing_field at that exact path.
        base = valid_doc()

        def paths(node, prefix):
            for key in node:
                yield prefix + [key]
                if isinstance(node[key], dict):
                    yield from paths(node[key], prefix + [key])

        for path in paths(base, []):
            doc = copy.deepcopy(base)
            parent = doc
            for key in path[:-1]:
                parent = parent[key]
            del parent[path[-1]]
            errors = vr.validate_document(doc)
            self.assertTrue(errors, "deleting %s was accepted" % ".".join(path))
            self.assertIn("missing_field", error_codes(errors), ".".join(path))

    def test_unknown_nested_field_rejected(self):
        doc = valid_doc()
        doc["signing"]["keystore"] = "anything"
        errors = vr.validate_document(doc)
        self.assertIn("unknown_field", error_codes(errors))

    def test_non_object_document_rejected(self):
        for text in ("[]", '"receipt"', "42", "null", "true"):
            errors = validate_text(text)
            self.assertTrue(errors, text)

    def test_malformed_json_rejected(self):
        errors = validate_text("{not json")
        self.assertEqual(error_codes(errors), ["json_invalid"])

    def test_duplicate_keys_rejected(self):
        errors = validate_text('{"schema_version": "1.0.0", "schema_version": "1.0.0"}')
        self.assertEqual(error_codes(errors), ["json_invalid"])

    def test_non_finite_numbers_rejected(self):
        doc_text = load_fixture("valid", "built-pending.json").replace(
            "1048576", "NaN"
        )
        errors = validate_text(doc_text)
        self.assertEqual(error_codes(errors), ["json_invalid"])

    def test_unsupported_schema_version_rejected(self):
        doc = valid_doc()
        doc["schema_version"] = "9.0.0"
        errors = vr.validate_document(doc)
        self.assertEqual(error_codes(errors), ["schema_version_unsupported"])

    def test_boolean_not_accepted_as_integer(self):
        doc = valid_doc()
        doc["package"]["version_code"] = True
        errors = vr.validate_document(doc)
        self.assertIn("type_invalid", error_codes(errors))

    def test_null_only_where_nullable(self):
        base = valid_doc()
        doc = copy.deepcopy(base)
        doc["artifact"]["sha256"] = None
        self.assertIn("type_invalid", error_codes(vr.validate_document(doc)))
        # verified_at may be null, but only alongside a pending verification.
        pending, errors = vr.parse_receipt(load_fixture("valid", "built-pending.json"))
        self.assertEqual(errors, [])
        self.assertIsNone(pending["verification"]["verified_at"])
        self.assertEqual(vr.validate_document(pending), [])


class FormatTests(unittest.TestCase):
    def check_rejected(self, mutate, expected_code="format_invalid"):
        doc = valid_doc()
        mutate(doc)
        errors = vr.validate_document(doc)
        self.assertIn(expected_code, error_codes(errors), repr(errors))

    def test_commit_sha_must_be_40_lower_hex(self):
        for bad in ("abc123", "g" * 40, "A" * 40, "0" * 39, "0" * 41):
            self.check_rejected(lambda d, b=bad: d["source"].__setitem__("commit_sha", b))

    def test_artifact_sha256_must_be_64_lower_hex(self):
        for bad in (
            "e" * 63,
            "E" * 64,
            "xyz",
            "sha256:" + "e" * 64,
            "e" * 64 + "\n",
        ):
            self.check_rejected(lambda d, b=bad: d["artifact"].__setitem__("sha256", b))

    def test_printable_line_rejects_final_newline(self):
        doc, errors = vr.parse_receipt(
            load_fixture("valid", "rolled-back-failed.json")
        )
        self.assertEqual(errors, [])
        doc["rollback"]["reason"] += "\n"
        self.assertIn("format_invalid", error_codes(vr.validate_document(doc)))

    def test_cert_fingerprint_must_be_colon_separated_uppercase(self):
        for bad in ("AA" * 32, ("aa:" * 31) + "aa", ("AA:" * 30) + "AA"):
            self.check_rejected(
                lambda d, b=bad: d["signing"].__setitem__(
                    "certificate_sha256_fingerprint", b
                )
            )

    def test_release_tag_format(self):
        for bad in ("0.1.0", "v0.1", "release-1", "v0.1.0-rc1"):
            self.check_rejected(lambda d, b=bad: d["source"].__setitem__("release_tag", b))

    def test_filename_must_be_bare_apk_name(self):
        for bad in ("clock.zip", "dir/clock.apk", ".apk", "clock.apk.txt"):
            self.check_rejected(lambda d, b=bad: d["artifact"].__setitem__("filename", b))

    def test_timestamps_must_be_utc_z(self):
        for bad in (
            "2026-07-01 10:00:00Z",
            "2026-07-01T10:00:00+02:00",
            "2026-13-01T10:00:00Z",
            "2026-02-30T10:00:00Z",
        ):
            self.check_rejected(
                lambda d, b=bad: d["approval"].__setitem__("approved_at", b)
            )

    def test_size_bytes_must_be_positive(self):
        self.check_rejected(lambda d: d["artifact"].__setitem__("size_bytes", 0))
        self.check_rejected(lambda d: d["artifact"].__setitem__("size_bytes", -5))


class SemanticTests(unittest.TestCase):
    def test_history_must_start_at_built(self):
        doc = valid_doc()
        doc["delivery"]["history"] = doc["delivery"]["history"][1:]
        errors = vr.validate_document(doc)
        self.assertIn("transition_invalid", error_codes(errors))

    def test_repeated_state_rejected(self):
        doc = valid_doc()
        doc["delivery"]["history"].append(
            {"state": "delivered", "at": "2026-07-03T10:00:00Z"}
        )
        errors = vr.validate_document(doc)
        self.assertIn("transition_invalid", error_codes(errors))

    def test_history_timestamps_must_not_decrease(self):
        doc = valid_doc()
        doc["delivery"]["history"][2]["at"] = "2026-07-03T00:00:00Z"
        errors = vr.validate_document(doc)
        self.assertIn("transition_invalid", error_codes(errors))

    def test_rolled_back_requires_rollback_record(self):
        doc, errors = vr.parse_receipt(
            load_fixture("valid", "rolled-back-failed.json")
        )
        self.assertEqual(errors, [])
        doc["rollback"] = None
        self.assertIn("state_invalid", error_codes(vr.validate_document(doc)))

    def test_verified_at_must_track_pending(self):
        doc = valid_doc()
        doc["verification"]["verified_at"] = None
        self.assertIn("state_invalid", error_codes(vr.validate_document(doc)))

    def test_verified_at_must_not_precede_delivered_history(self):
        doc = valid_doc()
        doc["verification"]["verified_at"] = "2026-07-03T08:59:59Z"
        errors = vr.validate_document(doc)
        self.assertIn("state_invalid", error_codes(errors))
        self.assertTrue(
            any(error["path"] == "$.verification.verified_at" for error in errors)
        )

    def test_verified_delivery_without_delivered_history_is_rejected(self):
        doc = valid_doc()
        doc["delivery"]["history"] = doc["delivery"]["history"][:-1]
        errors = vr.validate_document(doc)
        self.assertIn("state_invalid", error_codes(errors))
        self.assertTrue(
            any(error["path"] == "$.delivery.history" for error in errors)
        )


class HygieneTests(unittest.TestCase):
    def check_reason_rejected(self, reason):
        doc, errors = vr.parse_receipt(
            load_fixture("valid", "rolled-back-failed.json")
        )
        self.assertEqual(errors, [])
        doc["rollback"]["reason"] = reason
        errors = vr.validate_document(doc)
        self.assertIn("hygiene_violation", error_codes(errors), repr(reason))
        # The verdict must never echo the offending value back.
        self.assertNotIn(reason, json.dumps(errors))

    def test_local_absolute_paths_rejected(self):
        self.check_reason_rejected("copied from /Users/nobody/Desktop/app.apk")
        self.check_reason_rejected("copied from /workspace/build/output.log")
        self.check_reason_rejected("copied from /data/releases/app.apk")
        self.check_reason_rejected("copied from /$WORKSPACE/output.log")
        self.check_reason_rejected("cache at C:\\temp\\build")
        self.check_reason_rejected("see ~/notes.txt")

    def test_private_endpoints_rejected(self):
        self.check_reason_rejected("pushed via 192.168.1.7 relay")
        self.check_reason_rejected("uploaded to nas.local share")
        self.check_reason_rejected("mirror on localhost port")

    def test_credential_material_rejected(self):
        self.check_reason_rejected("used token: not-even-a-real-one")
        self.check_reason_rejected("header was Bearer abcdefgh1234")
        # Assembled at runtime so the literal never trips the repo's own
        # public-hygiene scan over tracked files.
        self.check_reason_rejected("ghp" + "_" + "0123456789abcdef0123456789")
        jwt = ".".join(("eyJ" + "header00", "payload000", "signature0"))
        self.check_reason_rejected("authorization artifact " + jwt)

    def test_dotted_public_identifiers_and_urls_are_not_credentials_or_paths(self):
        doc = valid_doc()
        doc["package"]["application_id"] = "abcdefgh.ijklmnop.qrstuvwx"
        doc["approval"]["approved_by"] = "release-v2.automation.build-host"
        self.assertEqual(vr.validate_document(doc), [])

        doc, errors = vr.parse_receipt(
            load_fixture("valid", "rolled-back-failed.json")
        )
        self.assertEqual(errors, [])
        doc["rollback"]["reason"] = "see https://example.com/release-notes"
        self.assertNotIn("hygiene_violation", error_codes(vr.validate_document(doc)))

    def test_hygiene_scans_every_string_field(self):
        # 'localhost' satisfies the identity-slug format but is still a
        # private endpoint, so only the hygiene scan can catch it here.
        doc = valid_doc()
        doc["approval"]["approved_by"] = "localhost"
        errors = vr.validate_document(doc)
        self.assertEqual(error_codes(errors), ["hygiene_violation"])
        self.assertEqual(errors[0]["path"], "$.approval.approved_by")


class CliTests(unittest.TestCase):
    def run_cli(self, *args, stdin=None):
        proc = subprocess.run(
            [sys.executable, VALIDATOR, *args],
            input=stdin,
            capture_output=True,
            text=True,
        )
        return proc

    def test_valid_fixture_exits_zero_with_stable_json(self):
        path = os.path.join(FIXTURES, "valid", "delivered-passed.json")
        first = self.run_cli(path)
        second = self.run_cli(path)
        self.assertEqual(first.returncode, 0)
        self.assertEqual(first.stdout, second.stdout)
        report = json.loads(first.stdout)
        self.assertTrue(report["valid"])
        self.assertEqual(report["error_count"], 0)
        self.assertEqual(report["errors"], [])
        self.assertEqual(report["tool"], "tx10-receipt-validate")

    def test_invalid_fixture_exits_one_with_machine_readable_errors(self):
        path = os.path.join(FIXTURES, "invalid", "transition-skip.json")
        proc = self.run_cli(path)
        self.assertEqual(proc.returncode, 1)
        report = json.loads(proc.stdout)
        self.assertFalse(report["valid"])
        self.assertGreater(report["error_count"], 0)
        for err in report["errors"]:
            self.assertEqual(sorted(err), ["code", "message", "path"])
            self.assertIn(err["code"], vr.ERROR_CODES)

    def test_malformed_verified_history_returns_json_not_traceback(self):
        doc = valid_doc()
        doc["delivery"]["history"] = doc["delivery"]["history"][:-1]
        proc = self.run_cli("-", stdin=json.dumps(doc))
        self.assertEqual(proc.returncode, 1)
        self.assertEqual(proc.stderr, "")
        report = json.loads(proc.stdout)
        self.assertFalse(report["valid"])
        self.assertIn("state_invalid", error_codes(report["errors"]))

    def test_stdin_input(self):
        proc = self.run_cli("-", stdin=load_fixture("valid", "built-pending.json"))
        self.assertEqual(proc.returncode, 0)
        self.assertTrue(json.loads(proc.stdout)["valid"])

    def test_unreadable_input_exits_two(self):
        proc = self.run_cli(os.path.join(FIXTURES, "does-not-exist.json"))
        self.assertEqual(proc.returncode, 2)
        report = json.loads(proc.stdout)
        self.assertEqual(error_codes(report["errors"]), ["io_error"])

    def test_usage_error_exits_two(self):
        proc = self.run_cli("--bogus")
        self.assertEqual(proc.returncode, 2)
        self.assertEqual(
            error_codes(json.loads(proc.stdout)["errors"]), ["usage_error"]
        )

    def test_report_is_sorted_and_deterministic(self):
        path = os.path.join(FIXTURES, "invalid", "digest-format-mismatch.json")
        proc = self.run_cli(path)
        report = json.loads(proc.stdout)
        paths = [e["path"] for e in report["errors"]]
        self.assertEqual(paths, sorted(paths))
        # Round-trips through sorted-key dumping identically: stable output.
        self.assertEqual(
            proc.stdout, json.dumps(report, indent=2, sort_keys=True) + "\n"
        )


class SchemaFileTests(unittest.TestCase):
    def test_committed_schema_matches_validator(self):
        with open(SCHEMA_FILE, "r", encoding="utf-8") as fh:
            committed = fh.read()
        self.assertEqual(
            committed,
            vr.emit_schema(),
            "committed schema drifted from the validator; regenerate with "
            "--emit-schema",
        )

    def test_schema_covers_all_contract_fields(self):
        with open(SCHEMA_FILE, "r", encoding="utf-8") as fh:
            schema = json.load(fh)
        top = schema["properties"]
        self.assertEqual(
            sorted(top),
            [
                "approval",
                "artifact",
                "delivery",
                "package",
                "receipt_id",
                "rollback",
                "schema_version",
                "signing",
                "source",
                "verification",
            ],
        )
        self.assertIn("commit_sha", top["source"]["properties"])
        self.assertIn("release_tag", top["source"]["properties"])
        self.assertIn("filename", top["artifact"]["properties"])
        self.assertIn("sha256", top["artifact"]["properties"])
        self.assertIn("application_id", top["package"]["properties"])
        self.assertIn(
            "certificate_sha256_fingerprint", top["signing"]["properties"]
        )
        self.assertIn("approved_by", top["approval"]["properties"])
        self.assertIn("approved_at", top["approval"]["properties"])
        self.assertIn("state", top["delivery"]["properties"])
        self.assertIn("history", top["delivery"]["properties"])
        self.assertIn("state", top["verification"]["properties"])
        self.assertIn("reference", top["rollback"]["properties"])
        self.assertFalse(schema["additionalProperties"])

    def test_schema_is_public_safe(self):
        with open(SCHEMA_FILE, "r", encoding="utf-8") as fh:
            text = fh.read()
        doc = json.loads(text)
        # Run the validator's own hygiene scan over the schema document.
        errors = []
        vr._check_hygiene(doc, errors)
        self.assertEqual(errors, [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
