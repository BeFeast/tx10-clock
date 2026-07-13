#!/usr/bin/env python3
"""Host-only tests for the clock-configuration validator.

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
FIXTURES = os.path.join(ROOT, "config", "fixtures")
SCHEMA_FILE = os.path.join(ROOT, "config", "schema", "config-v1.schema.json")
VALIDATOR = os.path.join(HERE, "validate_config.py")

sys.path.insert(0, HERE)
import validate_config as vc  # noqa: E402


def load_fixture(kind, name):
    with open(os.path.join(FIXTURES, kind, name), "r", encoding="utf-8") as fh:
        return fh.read()


def validate_text(text):
    doc, errors = vc.parse_config(text)
    if errors:
        return errors
    return vc.validate_document(doc)


def valid_doc(name="fixed-locale-and-zone.json"):
    doc, errors = vc.parse_config(load_fixture("valid", name))
    assert not errors, errors
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
        self.assertEqual(len(names), 14)
        for name in names:
            errors = validate_text(load_fixture("invalid", name))
            self.assertTrue(errors, "invalid fixture %s was accepted" % name)

    def test_invalid_fixtures_fail_for_the_documented_reason(self):
        expected = {
            "unknown-field.json": "unknown_field",
            "missing-field.json": "missing_field",
            "type-invalid.json": "type_invalid",
            "hour-cycle-invalid.json": "format_invalid",
            "sweep-mode-invalid.json": "format_invalid",
            "shift-radius-out-of-range.json": "range_invalid",
            "shift-interval-out-of-range.json": "range_invalid",
            "locale-tag-unsafe.json": "format_invalid",
            "timezone-id-unsafe.json": "format_invalid",
            "locale-policy-mismatch.json": "state_invalid",
            "timezone-fixed-missing-id.json": "state_invalid",
            "burn-in-enabled-zero-radius.json": "state_invalid",
            "hygiene-endpoint-timezone.json": "hygiene_violation",
            "schema-version-unsupported.json": "schema_version_unsupported",
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
            errors = vc.validate_document(doc)
            self.assertTrue(errors, "deleting %s was accepted" % ".".join(path))
            self.assertIn("missing_field", error_codes(errors), ".".join(path))

    def test_unknown_nested_field_rejected(self):
        doc = valid_doc()
        doc["clock"]["blink"] = True
        errors = vc.validate_document(doc)
        self.assertIn("unknown_field", error_codes(errors))

    def test_non_object_document_rejected(self):
        for text in ("[]", '"config"', "42", "null", "true"):
            errors = validate_text(text)
            self.assertTrue(errors, text)

    def test_malformed_json_rejected(self):
        errors = validate_text("{not json")
        self.assertEqual(error_codes(errors), ["json_invalid"])

    def test_duplicate_keys_rejected(self):
        errors = validate_text('{"schemaVersion": "1.0.0", "schemaVersion": "1.0.0"}')
        self.assertEqual(error_codes(errors), ["json_invalid"])

    def test_non_finite_numbers_rejected(self):
        for token in ("NaN", "Infinity", "-Infinity"):
            text = load_fixture("valid", "device-defaults.json").replace("60", token)
            errors = validate_text(text)
            self.assertEqual(error_codes(errors), ["json_invalid"], token)

    def test_deeply_nested_json_fails_closed(self):
        # Deep but under the byte ceiling: an adversarially nested document is
        # rejected with a clean verdict and never crashes the validator.
        # Some Python builds raise RecursionError inside the parser (caught and
        # reported as json_invalid); others parse it into a nested array that
        # the contract then rejects as a non-object (type_invalid). Either way
        # it must fail closed with no exception escaping.
        depth = 2000
        text = "[" * depth + "]" * depth
        self.assertLessEqual(len(text.encode("utf-8")), vc.MAX_DOCUMENT_BYTES)
        errors = validate_text(text)
        self.assertTrue(errors)
        self.assertTrue(
            set(error_codes(errors)) <= {"json_invalid", "type_invalid"},
            error_codes(errors),
        )

    def test_deeply_nested_object_fails_closed(self):
        # A compact object nested past the interpreter's recursion limit can
        # stay under the byte ceiling. It must be rejected with a JSON verdict
        # before any recursive traversal (hygiene scan) can raise RecursionError.
        depth = 1000
        text = '{"a":' * depth + "1" + "}" * depth
        self.assertLessEqual(len(text.encode("utf-8")), vc.MAX_DOCUMENT_BYTES)
        errors = validate_text(text)
        self.assertEqual(error_codes(errors), ["json_invalid"])

    def test_oversized_document_rejected(self):
        base = valid_doc("device-defaults.json")
        # A huge, otherwise-ignored... there are no free-text fields, so pad the
        # raw serialization with whitespace past the byte ceiling instead.
        text = json.dumps(base) + (" " * (vc.MAX_DOCUMENT_BYTES + 1))
        errors = validate_text(text)
        self.assertEqual(error_codes(errors), ["oversized"])

    def test_unsupported_schema_version_rejected(self):
        doc = valid_doc()
        doc["schemaVersion"] = "2.0.0"
        errors = vc.validate_document(doc)
        self.assertEqual(error_codes(errors), ["schema_version_unsupported"])

    def test_boolean_not_accepted_as_integer(self):
        doc = valid_doc()
        doc["burnIn"]["shiftRadiusPx"] = True
        errors = vc.validate_document(doc)
        self.assertIn("type_invalid", error_codes(errors))

    def test_integer_not_accepted_as_boolean(self):
        doc = valid_doc()
        doc["clock"]["showSeconds"] = 1
        errors = vc.validate_document(doc)
        self.assertIn("type_invalid", error_codes(errors))

    def test_fractional_number_rejected_as_integer(self):
        doc = valid_doc()
        doc["burnIn"]["shiftIntervalSeconds"] = 60.5
        errors = vc.validate_document(doc)
        self.assertIn("type_invalid", error_codes(errors))

    def test_null_only_where_nullable(self):
        doc = valid_doc()
        doc["clock"]["hourCycle"] = None
        self.assertIn("type_invalid", error_codes(vc.validate_document(doc)))
        # tag/id may be null, but only alongside a 'device' policy.
        device = valid_doc("device-defaults.json")
        self.assertIsNone(device["locale"]["tag"])
        self.assertIsNone(device["timeZone"]["id"])
        self.assertEqual(vc.validate_document(device), [])


class RangeAndFormatTests(unittest.TestCase):
    def check_rejected(self, mutate, expected_code):
        doc = valid_doc()
        mutate(doc)
        errors = vc.validate_document(doc)
        self.assertIn(expected_code, error_codes(errors), repr(errors))

    def test_shift_radius_bounds(self):
        for bad in (-1, 65, 1000):
            self.check_rejected(
                lambda d, b=bad: d["burnIn"].__setitem__("shiftRadiusPx", b),
                "range_invalid",
            )

    def test_shift_interval_bounds(self):
        for bad in (0, -5, 86401):
            self.check_rejected(
                lambda d, b=bad: d["burnIn"].__setitem__("shiftIntervalSeconds", b),
                "range_invalid",
            )

    def test_shift_bounds_inclusive_edges_accepted(self):
        doc = valid_doc("device-defaults.json")
        doc["burnIn"]["shiftRadiusPx"] = 64
        doc["burnIn"]["shiftIntervalSeconds"] = 86400
        self.assertEqual(vc.validate_document(doc), [])

    def test_hour_cycle_enum(self):
        for bad in ("h48", "12h", "H24", "", "twenty-four"):
            self.check_rejected(
                lambda d, b=bad: d["clock"].__setitem__("hourCycle", b),
                "format_invalid",
            )

    def test_sweep_mode_enum(self):
        for bad in ("glide", "sweep", "Tick", "step"):
            self.check_rejected(
                lambda d, b=bad: d["clock"].__setitem__("analogSweep", b),
                "format_invalid",
            )

    def test_policy_enum(self):
        for bad in ("auto", "system", "Device", "manual"):
            self.check_rejected(
                lambda d, b=bad: d["timeZone"].__setitem__("policy", b),
                "format_invalid",
            )

    def test_locale_tag_format(self):
        for bad in ("en/US", "en_US", "e", "english", "en-USA-extra", "123"):
            self.check_rejected(
                lambda d, b=bad: d["locale"].__setitem__("tag", b), "format_invalid"
            )

    def test_locale_tag_accepts_valid_shapes(self):
        for good in ("en", "en-US", "fr", "es-419", "zh-Hant-TW"):
            doc = valid_doc()
            doc["locale"]["policy"] = "fixed"
            doc["locale"]["tag"] = good
            self.assertEqual(vc.validate_document(doc), [], good)

    def test_locale_tag_accepts_variants_and_extensions(self):
        # BCP-47 tags with variants and extensions are valid and must be
        # accepted, not just language/script/region shapes.
        for good in (
            "de-CH-1996",          # variant
            "sl-rozaj",            # variant
            "en-US-u-ca-gregory",  # unicode extension
            "de-DE-u-co-phonebk",  # unicode extension
            "en-Latn-US-x-priv",   # script + region + private use
        ):
            doc = valid_doc()
            doc["locale"]["policy"] = "fixed"
            doc["locale"]["tag"] = good
            self.assertEqual(vc.validate_document(doc), [], good)

    def test_timezone_id_format(self):
        for bad in (
            "Continent/../secret",
            "America/New York",
            "/etc/localtime",
            "America\\New_York",
            "..",
            "Zone.With.Dots",
        ):
            self.check_rejected(
                lambda d, b=bad: d["timeZone"].__setitem__("id", b), "format_invalid"
            )

    def test_timezone_id_accepts_valid_shapes(self):
        for good in ("UTC", "America/New_York", "Europe/Berlin", "Etc/GMT+5",
                     "America/Argentina/Buenos_Aires"):
            doc = valid_doc()
            doc["timeZone"]["policy"] = "fixed"
            doc["timeZone"]["id"] = good
            self.assertEqual(vc.validate_document(doc), [], good)

    def test_locale_tag_over_max_length_rejected(self):
        doc = valid_doc()
        doc["locale"]["policy"] = "fixed"
        # A structurally tag-shaped but absurdly long value trips the bound.
        doc["locale"]["tag"] = "en" + ("-abcd" * 10)
        self.assertIn("range_invalid", error_codes(vc.validate_document(doc)))


class SemanticTests(unittest.TestCase):
    def test_fixed_locale_requires_tag(self):
        doc = valid_doc()
        doc["locale"]["policy"] = "fixed"
        doc["locale"]["tag"] = None
        errors = vc.validate_document(doc)
        self.assertIn("state_invalid", error_codes(errors))
        self.assertTrue(any(e["path"] == "$.locale.tag" for e in errors))

    def test_device_locale_forbids_tag(self):
        doc = valid_doc("device-defaults.json")
        doc["locale"]["tag"] = "en-US"
        self.assertIn("state_invalid", error_codes(vc.validate_document(doc)))

    def test_fixed_zone_requires_id(self):
        doc = valid_doc("device-defaults.json")
        doc["timeZone"]["policy"] = "fixed"
        doc["timeZone"]["id"] = None
        errors = vc.validate_document(doc)
        self.assertIn("state_invalid", error_codes(errors))
        self.assertTrue(any(e["path"] == "$.timeZone.id" for e in errors))

    def test_device_zone_forbids_id(self):
        doc = valid_doc("device-defaults.json")
        doc["timeZone"]["id"] = "UTC"
        self.assertIn("state_invalid", error_codes(vc.validate_document(doc)))

    def test_enabled_burn_in_requires_nonzero_radius(self):
        doc = valid_doc("device-defaults.json")
        doc["burnIn"]["shiftEnabled"] = True
        doc["burnIn"]["shiftRadiusPx"] = 0
        errors = vc.validate_document(doc)
        self.assertIn("state_invalid", error_codes(errors))
        self.assertTrue(any(e["path"] == "$.burnIn.shiftRadiusPx" for e in errors))

    def test_disabled_burn_in_allows_zero_radius(self):
        doc = valid_doc("device-defaults.json")
        self.assertFalse(doc["burnIn"]["shiftEnabled"])
        self.assertEqual(doc["burnIn"]["shiftRadiusPx"], 0)
        self.assertEqual(vc.validate_document(doc), [])


class HygieneTests(unittest.TestCase):
    def check_zone_rejected(self, zone_id):
        # Force the id past the format gate is unnecessary; these values are
        # format-valid zone shapes whose content the hygiene scan must catch.
        doc = valid_doc()
        doc["timeZone"]["policy"] = "fixed"
        doc["timeZone"]["id"] = zone_id
        errors = vc.validate_document(doc)
        self.assertIn("hygiene_violation", error_codes(errors), repr(zone_id))
        # The verdict must never echo the offending value back.
        self.assertNotIn(zone_id, json.dumps(errors))

    def test_private_endpoint_zone_rejected(self):
        self.check_zone_rejected("Region/localhost")

    def test_hygiene_scans_arbitrary_string_fields(self):
        # A credential-shaped value assembled at runtime so the literal never
        # trips the repo's own public-hygiene scan over tracked files.
        doc = valid_doc()
        doc["locale"]["tag"] = "en-US"
        # Inject into a string node the structural check would otherwise pass.
        doc["timeZone"]["policy"] = "fixed"
        doc["timeZone"]["id"] = "Zone/" + "ghp" + "_" + "0123456789abcdef0123"
        errors = vc.validate_document(doc)
        self.assertIn("hygiene_violation", error_codes(errors))

    def test_hygiene_runs_over_canonical_output(self):
        # Every accepted document's canonical form is public-safe by scan.
        for name in sorted(os.listdir(os.path.join(FIXTURES, "valid"))):
            doc = valid_doc(name)
            canonical = json.loads(vc.canonical_text(doc))
            errors = []
            vc._check_hygiene(canonical, errors)
            self.assertEqual(errors, [], name)


class CanonicalTests(unittest.TestCase):
    def test_canonical_form_is_stable(self):
        for name in sorted(os.listdir(os.path.join(FIXTURES, "valid"))):
            doc = valid_doc(name)
            once = vc.canonical_text(doc)
            reparsed, errors = vc.parse_config(once)
            self.assertEqual(errors, [], name)
            twice = vc.canonical_text(reparsed)
            self.assertEqual(once, twice, "canonical form not idempotent: %s" % name)

    def test_canonical_form_is_key_order_independent(self):
        # Two byte-different serializations of the same content canonicalize
        # identically.
        doc = valid_doc()
        a = vc.canonical_text(doc)
        shuffled = json.dumps(doc, sort_keys=False, indent=4)
        reparsed, errors = vc.parse_config(shuffled)
        self.assertEqual(errors, [])
        self.assertEqual(a, vc.canonical_text(reparsed))

    def test_canonical_form_normalizes_locale_casing(self):
        outs = set()
        for tag in ("en-US", "en-us", "EN-US", "en-Us"):
            doc = valid_doc()
            doc["locale"]["tag"] = tag
            outs.add(vc.canonical_text(doc))
        self.assertEqual(len(outs), 1)
        self.assertEqual(json.loads(outs.pop())["locale"]["tag"], "en-US")

    def test_canonical_form_normalizes_script_and_region(self):
        doc = valid_doc()
        doc["locale"]["tag"] = "ZH-hant-tw"
        self.assertEqual(
            json.loads(vc.canonical_text(doc))["locale"]["tag"], "zh-Hant-TW"
        )

    def test_canonical_form_normalizes_timezone_casing(self):
        # Any letter casing of one zone must produce a single canonical value,
        # so canonical output is a stable identity / deduplication key.
        outs = set()
        for zone in (
            "America/New_York",
            "america/new_york",
            "America/new_york",
            "america/New_York",
        ):
            doc = valid_doc()
            doc["timeZone"]["policy"] = "fixed"
            doc["timeZone"]["id"] = zone
            outs.add(json.loads(vc.canonical_text(doc))["timeZone"]["id"])
        self.assertEqual(outs, {"America/New_York"})

    def test_canonical_zone_preserves_abbreviations(self):
        # All-caps abbreviations carry fixed casing no rule could recover, so
        # they are preserved verbatim (and canonicalization stays idempotent).
        for zone in ("UTC", "Etc/GMT+5", "Europe/Berlin"):
            once = vc._canonical_zone_id(zone)
            self.assertEqual(once, zone)
            self.assertEqual(vc._canonical_zone_id(once), once)

    def test_canonical_form_has_sorted_keys(self):
        doc = valid_doc()
        text = vc.canonical_text(doc)
        loaded = json.loads(text)
        self.assertEqual(text, json.dumps(loaded, indent=2, sort_keys=True) + "\n")


class CliTests(unittest.TestCase):
    def run_cli(self, *args, stdin=None):
        return subprocess.run(
            [sys.executable, VALIDATOR, *args],
            input=stdin,
            capture_output=True,
            text=True,
        )

    def test_valid_fixture_exits_zero_with_stable_json(self):
        path = os.path.join(FIXTURES, "valid", "device-defaults.json")
        first = self.run_cli(path)
        second = self.run_cli(path)
        self.assertEqual(first.returncode, 0)
        self.assertEqual(first.stdout, second.stdout)
        report = json.loads(first.stdout)
        self.assertTrue(report["valid"])
        self.assertEqual(report["error_count"], 0)
        self.assertEqual(report["errors"], [])
        self.assertEqual(report["tool"], "tx10-config-validate")

    def test_invalid_fixture_exits_one_with_machine_readable_errors(self):
        path = os.path.join(FIXTURES, "invalid", "shift-radius-out-of-range.json")
        proc = self.run_cli(path)
        self.assertEqual(proc.returncode, 1)
        report = json.loads(proc.stdout)
        self.assertFalse(report["valid"])
        self.assertGreater(report["error_count"], 0)
        for err in report["errors"]:
            self.assertEqual(sorted(err), ["code", "message", "path"])
            self.assertIn(err["code"], vc.ERROR_CODES)

    def test_stdin_input(self):
        proc = self.run_cli("-", stdin=load_fixture("valid", "ticking-minimal.json"))
        self.assertEqual(proc.returncode, 0)
        self.assertTrue(json.loads(proc.stdout)["valid"])

    def test_canonicalize_valid_prints_canonical_stdout(self):
        path = os.path.join(FIXTURES, "valid", "fixed-locale-and-zone.json")
        proc = self.run_cli("--canonicalize", path)
        self.assertEqual(proc.returncode, 0)
        self.assertEqual(proc.stderr, "")
        doc = valid_doc("fixed-locale-and-zone.json")
        self.assertEqual(proc.stdout, vc.canonical_text(doc))

    def test_canonicalize_invalid_exits_one_verdict_on_stderr(self):
        path = os.path.join(FIXTURES, "invalid", "type-invalid.json")
        proc = self.run_cli("--canonicalize", path)
        self.assertEqual(proc.returncode, 1)
        self.assertEqual(proc.stdout, "")
        report = json.loads(proc.stderr)
        self.assertFalse(report["valid"])

    def test_canonicalize_is_stable_across_processes(self):
        path = os.path.join(FIXTURES, "valid", "burn-in-enabled.json")
        first = self.run_cli("--canonicalize", path)
        second = self.run_cli("--canonicalize", "-", stdin=first.stdout)
        self.assertEqual(first.returncode, 0)
        self.assertEqual(second.returncode, 0)
        self.assertEqual(first.stdout, second.stdout)

    def test_unreadable_input_exits_two(self):
        proc = self.run_cli(os.path.join(FIXTURES, "does-not-exist.json"))
        self.assertEqual(proc.returncode, 2)
        report = json.loads(proc.stdout)
        self.assertEqual(error_codes(report["errors"]), ["io_error"])

    def test_invalid_utf8_file_exits_two(self):
        # A file whose bytes are not valid UTF-8 is an io_error with exit 2 and
        # a machine-readable verdict — never an uncaught UnicodeDecodeError.
        import tempfile

        fd, path = tempfile.mkstemp(suffix=".json")
        try:
            with os.fdopen(fd, "wb") as fh:
                fh.write(b'{"schemaVersion": "\xff\xfe1.0.0"}')
            proc = self.run_cli(path)
        finally:
            os.remove(path)
        self.assertEqual(proc.returncode, 2)
        self.assertNotIn("Traceback", proc.stderr)
        report = json.loads(proc.stdout)
        self.assertEqual(error_codes(report["errors"]), ["io_error"])

    def test_invalid_utf8_stdin_exits_two(self):
        env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUTF8="1")
        proc = subprocess.run(
            [sys.executable, VALIDATOR, "-"],
            input=b"\xff\xfe\xfa",
            capture_output=True,
            env=env,
        )
        self.assertEqual(proc.returncode, 2)
        self.assertNotIn(b"Traceback", proc.stderr)
        report = json.loads(proc.stdout.decode("utf-8"))
        self.assertEqual(error_codes(report["errors"]), ["io_error"])

    def test_deeply_nested_object_cli_fails_closed(self):
        # The CLI must fail closed on a deep object with exit 1 and no traceback.
        depth = 1000
        text = '{"a":' * depth + "1" + "}" * depth
        proc = self.run_cli("-", stdin=text)
        self.assertEqual(proc.returncode, 1)
        self.assertNotIn("Traceback", proc.stderr)
        self.assertFalse(json.loads(proc.stdout)["valid"])

    def test_usage_error_exits_two(self):
        proc = self.run_cli("--bogus")
        self.assertEqual(proc.returncode, 2)
        self.assertEqual(
            error_codes(json.loads(proc.stdout)["errors"]), ["usage_error"]
        )

    def test_report_is_sorted_and_deterministic(self):
        path = os.path.join(FIXTURES, "invalid", "unknown-field.json")
        proc = self.run_cli(path)
        report = json.loads(proc.stdout)
        paths = [e["path"] for e in report["errors"]]
        self.assertEqual(paths, sorted(paths))
        self.assertEqual(
            proc.stdout, json.dumps(report, indent=2, sort_keys=True) + "\n"
        )


class SchemaFileTests(unittest.TestCase):
    def test_committed_schema_matches_validator(self):
        with open(SCHEMA_FILE, "r", encoding="utf-8") as fh:
            committed = fh.read()
        self.assertEqual(
            committed,
            vc.emit_schema(),
            "committed schema drifted from the validator; regenerate with "
            "--emit-schema",
        )

    def test_schema_covers_all_contract_sections(self):
        with open(SCHEMA_FILE, "r", encoding="utf-8") as fh:
            schema = json.load(fh)
        top = schema["properties"]
        self.assertEqual(
            sorted(top),
            ["burnIn", "clock", "locale", "runtime", "schemaVersion", "timeZone"],
        )
        self.assertEqual(
            sorted(top["clock"]["properties"]),
            ["analogSweep", "hourCycle", "showDate", "showSeconds"],
        )
        self.assertEqual(sorted(top["locale"]["properties"]), ["policy", "tag"])
        self.assertEqual(sorted(top["timeZone"]["properties"]), ["id", "policy"])
        self.assertEqual(
            sorted(top["burnIn"]["properties"]),
            ["shiftEnabled", "shiftIntervalSeconds", "shiftRadiusPx"],
        )
        self.assertEqual(
            sorted(top["runtime"]["properties"]),
            ["bootStart", "keepScreenOn", "safeRefresh"],
        )
        self.assertFalse(schema["additionalProperties"])

    def test_schema_is_public_safe(self):
        with open(SCHEMA_FILE, "r", encoding="utf-8") as fh:
            doc = json.load(fh)
        errors = []
        vc._check_hygiene(doc, errors)
        self.assertEqual(errors, [])

    def test_schema_encodes_semantic_conditions(self):
        # The semantic rules must be enforceable draft-2020-12 conditions, not
        # only descriptive x-contract prose, so a standard schema validator
        # rejects the same negatives validate_document does.
        with open(SCHEMA_FILE, "r", encoding="utf-8") as fh:
            schema = json.load(fh)
        props = schema["properties"]

        locale = props["locale"]["allOf"][0]
        self.assertEqual(locale["if"]["properties"]["policy"]["const"], "fixed")
        self.assertEqual(locale["then"]["properties"]["tag"]["type"], "string")
        self.assertEqual(locale["else"]["properties"]["tag"]["type"], "null")

        zone = props["timeZone"]["allOf"][0]
        self.assertEqual(zone["if"]["properties"]["policy"]["const"], "fixed")
        self.assertEqual(zone["then"]["properties"]["id"]["type"], "string")
        self.assertEqual(zone["else"]["properties"]["id"]["type"], "null")

        burn = props["burnIn"]["allOf"][0]
        self.assertEqual(burn["if"]["properties"]["shiftEnabled"]["const"], True)
        self.assertEqual(burn["then"]["properties"]["shiftRadiusPx"]["minimum"], 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
