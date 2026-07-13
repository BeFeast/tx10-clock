"""Connection-state coverage: success, offline, unauthorized, multiple, and
target-present handling."""

import unittest

from delivery.preflight import checks
from delivery.preflight.redaction import Redactor
from delivery.preflight.report import FAIL, PASS
from delivery.preflight.requirements import Requirements
from delivery.preflight.tests.fake_adb import FakeAdb, make_client


def _run(device_lines, target=None):
    fake = FakeAdb(device_lines=device_lines)
    client, fake = make_client(fake, target=target)
    return check_connection(client), fake


def check_connection(client):
    return checks.check_connection(client, Requirements(), Redactor(client.target))


class ConnectionTest(unittest.TestCase):
    def test_single_online_device_passes(self):
        res, _ = _run([("SER1", "device")])
        self.assertEqual(res.status, PASS)
        self.assertEqual(res.data["device_count"], 1)

    def test_no_device_fails(self):
        res, _ = _run([])
        self.assertEqual(res.status, FAIL)
        self.assertEqual(res.data["device_count"], 0)

    def test_offline_device_fails(self):
        res, _ = _run([("SER1", "offline")])
        self.assertEqual(res.status, FAIL)
        self.assertIn("offline", res.summary)

    def test_unauthorized_device_fails(self):
        res, _ = _run([("SER1", "unauthorized")])
        self.assertEqual(res.status, FAIL)
        self.assertIn("unauthorized", res.summary)

    def test_multiple_devices_without_target_fails(self):
        res, _ = _run([("SER1", "device"), ("SER2", "device")])
        self.assertEqual(res.status, FAIL)
        self.assertEqual(res.data["device_count"], 2)

    def test_target_selects_among_multiple(self):
        # With a target, multiple connected devices is fine; the target is used.
        res, _ = _run([("SER1", "device"), ("SER2", "device")], target="SER2")
        self.assertEqual(res.status, PASS)
        self.assertTrue(res.data.get("device_count"), 2)

    def test_target_not_present_fails(self):
        res, _ = _run([("SER1", "device")], target="MISSING")
        self.assertEqual(res.status, FAIL)
        self.assertFalse(res.data["target_present"])

    def test_target_unauthorized_fails(self):
        res, _ = _run([("SER2", "unauthorized")], target="SER2")
        self.assertEqual(res.status, FAIL)

    def test_no_serial_leaks_in_result(self):
        res, _ = _run([("SUPERSECRETSERIAL", "device")], target="SUPERSECRETSERIAL")
        blob = str(res.to_dict())
        self.assertNotIn("SUPERSECRETSERIAL", blob)


if __name__ == "__main__":
    unittest.main()
