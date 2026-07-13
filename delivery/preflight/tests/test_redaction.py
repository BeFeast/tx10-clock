"""Fingerprint non-reversibility/determinism and output redaction."""

import unittest

from delivery.preflight.redaction import (
    REDACTED,
    Redactor,
    classify_target,
    fingerprint,
)


class FingerprintTest(unittest.TestCase):
    def test_deterministic(self):
        self.assertEqual(fingerprint("SERIAL123"), fingerprint("SERIAL123"))

    def test_salt_changes_output(self):
        self.assertNotEqual(
            fingerprint("SERIAL123", salt="a"),
            fingerprint("SERIAL123", salt="b"),
        )

    def test_does_not_contain_raw_target(self):
        # RFC 5737 TEST-NET-1 documentation address; not a real host.
        target = "192.0.2.10:5555"
        fp = fingerprint(target)
        self.assertNotIn(target, fp)
        self.assertNotIn("192.0.2.10", fp)
        # 16 hex chars, no reversible structure.
        self.assertEqual(len(fp), 16)
        self.assertTrue(all(c in "0123456789abcdef" for c in fp))

    def test_distinct_targets_distinct_fingerprints(self):
        self.assertNotEqual(fingerprint("A"), fingerprint("B"))

    def test_classify(self):
        self.assertEqual(classify_target("192.0.2.10:5555"), "endpoint")
        self.assertEqual(classify_target("emulator-5554"), "serial")
        self.assertEqual(classify_target("host.local:5037"), "endpoint")
        self.assertEqual(classify_target("R58N12345XY"), "serial")


class RedactorTest(unittest.TestCase):
    def test_scrubs_exact_target(self):
        r = Redactor("R58N12345XY")
        out = r.scrub("device R58N12345XY reported ready")
        self.assertNotIn("R58N12345XY", out)
        self.assertIn(REDACTED, out)

    def test_scrubs_endpoint_and_host(self):
        r = Redactor("192.0.2.10:5555")
        out = r.scrub("connected to 192.0.2.10:5555 and host 192.0.2.10")
        self.assertNotIn("192.0.2.10", out)
        self.assertIn(REDACTED, out)

    def test_scrubs_generic_ipv4_even_without_target(self):
        r = Redactor(None)
        out = r.scrub("saw 198.51.100.7:5555 in output")
        self.assertNotIn("198.51.100.7", out)

    def test_strips_control_characters(self):
        r = Redactor(None)
        out = r.scrub("value\x00\x1b[31mred\x07\n")
        self.assertNotIn("\x00", out)
        self.assertNotIn("\x1b", out)
        self.assertNotIn("\x07", out)

    def test_caps_length(self):
        r = Redactor(None)
        out = r.scrub("x" * 5000)
        self.assertLessEqual(len(out), 201)


if __name__ == "__main__":
    unittest.main()
