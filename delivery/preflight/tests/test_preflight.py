"""End-to-end orchestration: readiness, exit codes, determinism, and skip
propagation."""

import json
import unittest

from delivery.preflight.preflight import run_preflight
from delivery.preflight.report import (
    EXIT_NOT_READY,
    EXIT_READY,
    PASS,
    SKIP,
)
from delivery.preflight.requirements import Requirements
from delivery.preflight.tests.fake_adb import FakeAdb, FakeDevice, make_client


class PreflightSuccessTest(unittest.TestCase):
    def test_all_green_is_ready(self):
        client, _ = make_client(target="SER1")
        fake = client._runner
        fake.device_lines = [("SER1", "device")]
        report = run_preflight(client, Requirements())
        self.assertTrue(report.ready)
        self.assertEqual(report.exit_code, EXIT_READY)
        self.assertEqual([c.id for c in report.checks if c.required and c.status != PASS], [])

    def test_report_has_fingerprint_not_raw_target(self):
        client, _ = make_client(FakeAdb(device_lines=[("R58N-SECRET", "device")]), target="R58N-SECRET")
        report = run_preflight(client, Requirements())
        blob = report.to_json()
        self.assertNotIn("R58N-SECRET", blob)
        self.assertEqual(report.target_kind, "serial")
        self.assertEqual(len(report.target_fingerprint), 16)


class PreflightFailureTest(unittest.TestCase):
    def test_low_api_not_ready_exit_1(self):
        dev = FakeDevice()
        dev.props["ro.build.version.sdk"] = "21"
        client, _ = make_client(FakeAdb(device_lines=[("SER1", "device")], device=dev), target="SER1")
        report = run_preflight(client, Requirements())
        self.assertFalse(report.ready)
        self.assertEqual(report.exit_code, EXIT_NOT_READY)
        self.assertIn("api_level", report.failures)

    def test_offline_device_skips_dependent_checks(self):
        client, _ = make_client(FakeAdb(device_lines=[("SER1", "offline")]), target="SER1")
        report = run_preflight(client, Requirements())
        self.assertFalse(report.ready)
        by_id = {c.id: c for c in report.checks}
        self.assertEqual(by_id["connection"].status, "fail")
        # Every non-connection check is skipped, not run against a dead device.
        for cid in ("api_level", "abi", "storage", "package_state", "launcher_state", "clock_timezone"):
            self.assertEqual(by_id[cid].status, SKIP)

    def test_offline_issues_no_shell_calls(self):
        fake = FakeAdb(device_lines=[("SER1", "offline")])
        client, _ = make_client(fake, target="SER1")
        run_preflight(client, Requirements())
        # Only `adb devices` ran; no shell query was issued against the offline device.
        shell_calls = [c for c in fake.calls if "shell" in c]
        self.assertEqual(shell_calls, [])


class DeterminismTest(unittest.TestCase):
    def test_identical_inputs_produce_identical_json(self):
        def build():
            client, _ = make_client(FakeAdb(device_lines=[("SER1", "device")]), target="SER1")
            return run_preflight(client, Requirements()).to_json()

        self.assertEqual(build(), build())

    def test_report_is_valid_json(self):
        client, _ = make_client(FakeAdb(device_lines=[("SER1", "device")]), target="SER1")
        report = run_preflight(client, Requirements())
        parsed = json.loads(report.to_json())
        self.assertEqual(parsed["schema"], "tx10-clock/adb-preflight/v1")
        self.assertIn("checks", parsed)
        self.assertNotIn("timestamp", parsed)  # determinism: no wall-clock


if __name__ == "__main__":
    unittest.main()
