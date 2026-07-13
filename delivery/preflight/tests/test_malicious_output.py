"""Hostile-device output must not break the report, forge a pass, or leak the
target."""

import json
import unittest

from delivery.preflight.preflight import run_preflight
from delivery.preflight.report import ERROR, PASS
from delivery.preflight.requirements import Requirements
from delivery.preflight.tests.fake_adb import FakeAdb, FakeDevice, make_client


TARGET = "LEAKYSERIAL42"


def _malicious_device() -> FakeDevice:
    dev = FakeDevice()
    # Command-injection / JSON-breaking junk where a number is expected.
    dev.props["ro.build.version.sdk"] = '30\n"; reboot #\x1b[31m'
    # The device echoes the live target back at us, with control characters.
    dev.props["ro.build.version.release"] = f"11 {TARGET}\x00\x1b[0m " + ("A" * 5000)
    dev.props["ro.product.cpu.abi"] = "arm64-v8a\x07"
    dev.props["persist.sys.timezone"] = f"Etc/{TARGET}"
    return dev


class MaliciousOutputTest(unittest.TestCase):
    def setUp(self):
        fake = FakeAdb(
            device_lines=[(TARGET, "device")],
            device=_malicious_device(),
            overrides={("shell", "df", "-k", "/data"): (0, "rm -rf /\n<script>\n", "")},
        )
        self.client, self.fake = make_client(fake, target=TARGET)
        self.report = run_preflight(self.client, Requirements())
        self.blob = self.report.to_json()

    def test_output_is_valid_json(self):
        json.loads(self.blob)  # must not raise

    def test_target_never_leaks(self):
        self.assertNotIn(TARGET, self.blob)

    def test_no_control_characters_survive(self):
        # ensure_ascii escapes control chars as \u00xx; none should be present
        # because device-derived strings are stripped before serialization.
        for esc in ("\\u0000", "\\u001b", "\\u0007"):
            self.assertNotIn(esc, self.blob)

    def test_injected_api_level_is_error_not_pass(self):
        api = next(c for c in self.report.checks if c.id == "api_level")
        self.assertEqual(api.status, ERROR)

    def test_unparseable_df_is_error(self):
        storage = next(c for c in self.report.checks if c.id == "storage")
        self.assertEqual(storage.status, ERROR)

    def test_not_ready(self):
        self.assertFalse(self.report.ready)

    def test_echoed_release_is_capped_and_redacted(self):
        api = next(c for c in self.report.checks if c.id == "api_level")
        release = api.data["android_release"]
        self.assertNotIn(TARGET, release)
        self.assertLessEqual(len(release), 201)


if __name__ == "__main__":
    unittest.main()
