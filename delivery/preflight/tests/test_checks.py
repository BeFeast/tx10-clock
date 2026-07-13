"""Individual check behavior across pass / fail / error paths."""

import unittest

from delivery.preflight import checks
from delivery.preflight.redaction import Redactor
from delivery.preflight.report import ERROR, FAIL, PASS, WARN
from delivery.preflight.requirements import Requirements
from delivery.preflight.tests.fake_adb import FakeAdb, FakeDevice, make_client


def _run(check_fn, fake=None, req=None, target=None):
    client, fake = make_client(fake, target=target)
    return check_fn(client, req or Requirements(), Redactor(target))


class ApiLevelTest(unittest.TestCase):
    def test_pass(self):
        res = _run(checks.check_api_level)
        self.assertEqual(res.status, PASS)
        self.assertEqual(res.data["api_level"], 30)

    def test_below_minimum_fails(self):
        dev = FakeDevice()
        dev.props["ro.build.version.sdk"] = "28"
        res = _run(checks.check_api_level, FakeAdb(device=dev))
        self.assertEqual(res.status, FAIL)

    def test_unparseable_is_error_not_pass(self):
        dev = FakeDevice()
        dev.props["ro.build.version.sdk"] = "not-a-number"
        res = _run(checks.check_api_level, FakeAdb(device=dev))
        self.assertEqual(res.status, ERROR)


class AbiTest(unittest.TestCase):
    def test_pass(self):
        res = _run(checks.check_abi)
        self.assertEqual(res.status, PASS)

    def test_unsupported_abi_fails(self):
        req = Requirements(allowed_abis=["x86_64"])
        res = _run(checks.check_abi, req=req)
        self.assertEqual(res.status, FAIL)

    def test_empty_abi_is_error(self):
        dev = FakeDevice()
        dev.props["ro.product.cpu.abi"] = ""
        dev.props["ro.product.cpu.abilist"] = ""
        res = _run(checks.check_abi, FakeAdb(device=dev))
        self.assertEqual(res.status, ERROR)


class StorageTest(unittest.TestCase):
    def test_pass(self):
        res = _run(checks.check_storage)
        self.assertEqual(res.status, PASS)

    def test_insufficient_storage_fails(self):
        dev = FakeDevice(df_available_kb=1024)  # 1 MiB, below the 64 MiB default
        res = _run(checks.check_storage, FakeAdb(device=dev))
        self.assertEqual(res.status, FAIL)

    def test_unparseable_df_is_error(self):
        fake = FakeAdb(overrides={("shell", "df", "-k", "/data"): (0, "totally unparseable\n", "")})
        res = _run(checks.check_storage, fake)
        self.assertEqual(res.status, ERROR)


class PackageTest(unittest.TestCase):
    def test_not_installed_is_pass(self):
        res = _run(checks.check_package)
        self.assertEqual(res.status, PASS)
        self.assertFalse(res.data["installed"])

    def test_installed_is_warn(self):
        res = _run(checks.check_package, FakeAdb(device=FakeDevice(package_installed=True)))
        self.assertEqual(res.status, WARN)
        self.assertTrue(res.data["installed"])
        self.assertFalse(res.required)


class LauncherTest(unittest.TestCase):
    def test_resolves(self):
        res = _run(checks.check_launcher)
        self.assertEqual(res.status, PASS)
        self.assertEqual(res.data["launcher_package"], "com.android.tv.launcher")

    def test_unresolved_is_warn(self):
        fake = FakeAdb(overrides={
            ("shell", "cmd", "package", "resolve-activity", "--brief",
             "-c", "android.intent.category.HOME", "-a", "android.intent.action.MAIN"): (0, "No activity found\n", ""),
        })
        res = _run(checks.check_launcher, fake)
        self.assertEqual(res.status, WARN)


class ClockTest(unittest.TestCase):
    def test_set_clock_pass(self):
        res = _run(checks.check_clock)
        self.assertEqual(res.status, PASS)
        self.assertEqual(res.data["timezone"], "Europe/Amsterdam")

    def test_unset_clock_warns(self):
        res = _run(checks.check_clock, FakeAdb(device=FakeDevice(date_epoch=1000)))
        self.assertEqual(res.status, WARN)
        self.assertFalse(res.required)


class TimeoutTest(unittest.TestCase):
    def test_getprop_timeout_is_error(self):
        res = _run(checks.check_api_level, FakeAdb(timeouts=["getprop"]))
        self.assertEqual(res.status, ERROR)

    def test_df_timeout_is_error(self):
        res = _run(checks.check_storage, FakeAdb(timeouts=["df"]))
        self.assertEqual(res.status, ERROR)

    def test_devices_timeout_is_error(self):
        res = _run(checks.check_connection, FakeAdb(timeouts=["devices"]))
        self.assertEqual(res.status, ERROR)


if __name__ == "__main__":
    unittest.main()
