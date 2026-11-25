#!/usr/bin/env python3
"""Host-only tests for adb-preflight.

Every test injects the fake ADB double (fake_adb.py) via TX10_ADB — no real
device, no ADB server, no Android SDK, no licence acceptance. Coverage:

  * success (env-provided target and single-device autodetect)
  * offline / unauthorized / multiple-device / no-device connection states
  * unsupported API level and unsupported ABI
  * insufficient storage and excessive clock skew
  * per-invocation timeout
  * malicious device output (shell metacharacters, ANSI/control bytes)
  * redaction: the live serial/endpoint never appears in any output
  * deterministic report bytes
  * the read-only allowlist itself, plus proof that every command the tool
    actually executed satisfies it
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
TOOL_DIR = os.path.dirname(TESTS_DIR)
TOOL = os.path.join(TOOL_DIR, "adb_preflight.py")
FAKE_ADB = os.path.join(TESTS_DIR, "fake_adb.py")

sys.path.insert(0, TOOL_DIR)
import adb_preflight  # noqa: E402

SERIAL = "TESTSERIAL123"
SALT = "test-salt"
HOST_EPOCH = "1700000000"

RESOLVE_HOME_KEY = ("shell cmd package resolve-activity --brief "
                    "-a android.intent.action.MAIN "
                    "-c android.intent.category.HOME")

DF_OK = ("Filesystem      1K-blocks   Used Available Use%% Mounted on\n"
         "/dev/block/dm-0   5000000 100000   %d   3%% /data\n")


def success_spec():
    return {
        "get-state": {"stdout": "device\n"},
        "shell getprop ro.build.version.sdk": {"stdout": "29\n"},
        "shell getprop ro.product.cpu.abilist": {"stdout": "armeabi-v7a\n"},
        "shell pm list packages com.befeast.tx10clock": {"stdout": ""},
        RESOLVE_HOME_KEY: {
            "stdout": "priority=0 preferredOrder=0 isDefault=true\n"
                      "com.example.launcher/com.example.launcher.Main\n"},
        "shell df /data": {"stdout": DF_OK % 4000000},
        "shell date +%s": {"stdout": HOST_EPOCH + "\n"},
        "shell getprop persist.sys.timezone": {"stdout": "Europe/Berlin\n"},
    }


class PreflightHarness(unittest.TestCase):
    maxDiff = None

    def setUp(self):
        self.scenario = tempfile.mkdtemp(prefix="fake-adb-")
        self.addCleanup(shutil.rmtree, self.scenario, True)

    def run_tool(self, spec, target=SERIAL, args=(), env_overrides=None,
                 adb=FAKE_ADB):
        with open(os.path.join(self.scenario, "spec.json"), "w",
                  encoding="utf-8") as handle:
            json.dump(spec, handle)
        env = {k: v for k, v in os.environ.items()
               if not k.startswith(("TX10_", "FAKE_ADB"))}
        env["FAKE_ADB_DIR"] = self.scenario
        env["TX10_ADB"] = adb
        env["TX10_PREFLIGHT_SALT"] = SALT
        env["TX10_PREFLIGHT_HOST_EPOCH"] = HOST_EPOCH
        if target is not None:
            env["TX10_ADB_TARGET"] = target
        env.update(env_overrides or {})
        return subprocess.run(
            [sys.executable, TOOL, *args],
            env=env, capture_output=True, text=True, timeout=60)

    def report(self, result):
        try:
            return json.loads(result.stdout)
        except ValueError:
            self.fail("stdout is not valid JSON:\n%r" % result.stdout[:2000])

    def check_by_id(self, report, check_id):
        matches = [c for c in report["checks"] if c["id"] == check_id]
        self.assertEqual(len(matches), 1, "missing check %s" % check_id)
        return matches[0]

    def assert_no_leak(self, result, *secrets):
        for text in (result.stdout, result.stderr):
            for secret in secrets:
                self.assertNotIn(secret, text)

    def calls(self):
        path = os.path.join(self.scenario, "calls.log")
        if not os.path.exists(path):
            return []
        with open(path, encoding="utf-8") as handle:
            return [line.rstrip("\n").split("\t")
                    for line in handle if line.strip()]


class SuccessTests(PreflightHarness):

    def test_all_prerequisites_met(self):
        result = self.run_tool(success_spec())
        self.assertEqual(result.returncode, 0, result.stderr)
        report = self.report(result)
        self.assertTrue(report["ok"])
        self.assertEqual(report["schema"], "tx10-adb-preflight/v1")
        for check in report["checks"]:
            self.assertEqual(check["status"], "pass", check)
        expected = adb_preflight.fingerprint(SALT, SERIAL)
        self.assertEqual(report["target"]["fingerprint"], expected)
        self.assertEqual(report["target"]["source"], "env")
        self.assert_no_leak(result, SERIAL)

    def test_autodetect_single_device(self):
        spec = success_spec()
        del spec["get-state"]
        spec["devices"] = {
            "stdout": "List of devices attached\n%s\tdevice\n\n" % SERIAL}
        result = self.run_tool(spec, target=None)
        self.assertEqual(result.returncode, 0, result.stderr)
        report = self.report(result)
        self.assertTrue(report["ok"])
        self.assertEqual(report["target"]["source"], "autodetect")
        self.assertEqual(report["target"]["fingerprint"],
                         adb_preflight.fingerprint(SALT, SERIAL))
        self.assert_no_leak(result, SERIAL)

    def test_installed_package_is_reported(self):
        spec = success_spec()
        spec["shell pm list packages com.befeast.tx10clock"] = {
            "stdout": "package:com.befeast.tx10clock\n"}
        result = self.run_tool(spec)
        self.assertEqual(result.returncode, 0, result.stderr)
        check = self.check_by_id(self.report(result), "package-state")
        self.assertEqual(check["detail"], "installed=true")

    def test_report_is_deterministic(self):
        first = self.run_tool(success_spec())
        second = self.run_tool(success_spec())
        self.assertEqual(first.returncode, 0)
        self.assertEqual(first.stdout, second.stdout)


class ConnectionFailureTests(PreflightHarness):

    def assert_conn_fail_and_skips(self, result):
        self.assertEqual(result.returncode, 1)
        report = self.report(result)
        self.assertFalse(report["ok"])
        self.assertEqual(self.check_by_id(report, "connection-state")["status"],
                         "fail")
        for check_id in adb_preflight.DEVICE_CHECK_IDS:
            self.assertEqual(self.check_by_id(report, check_id)["status"],
                             "skip")
        return report

    def test_offline_device(self):
        spec = {"get-state": {"rc": 1, "stderr": "error: device offline\n"}}
        result = self.run_tool(spec)
        self.assert_conn_fail_and_skips(result)
        self.assert_no_leak(result, SERIAL)

    def test_unauthorized_device(self):
        spec = {"get-state": {
            "rc": 1,
            "stderr": "error: device unauthorized.\n"
                      "This adb server's $ADB_VENDOR_KEYS is not set\n"}}
        result = self.run_tool(spec)
        report = self.assert_conn_fail_and_skips(result)
        self.assertIn("unauthorized",
                      self.check_by_id(report, "connection-state")["detail"])

    def test_multiple_devices_without_target(self):
        spec = {"devices": {
            "stdout": "List of devices attached\n"
                      "SERIALAAA111\tdevice\nSERIALBBB222\tdevice\n\n"}}
        result = self.run_tool(spec, target=None)
        report = self.assert_conn_fail_and_skips(result)
        self.assertIn("2 devices",
                      self.check_by_id(report, "connection-state")["detail"])
        self.assert_no_leak(result, "SERIALAAA111", "SERIALBBB222")

    def test_no_devices_attached(self):
        spec = {"devices": {"stdout": "List of devices attached\n\n"}}
        result = self.run_tool(spec, target=None)
        report = self.assert_conn_fail_and_skips(result)
        self.assertIn("no devices",
                      self.check_by_id(report, "connection-state")["detail"])

    def test_adb_binary_missing(self):
        result = self.run_tool({}, adb=os.path.join(self.scenario, "no-adb"))
        self.assertEqual(result.returncode, 1)
        report = self.report(result)
        self.assertEqual(self.check_by_id(report, "adb-binary")["status"],
                         "fail")
        self.assertEqual(self.calls(), [])

    def test_timeout_is_reported_not_hung(self):
        spec = {"get-state": {"stdout": "device\n", "sleep": 5}}
        result = self.run_tool(
            spec, env_overrides={"TX10_PREFLIGHT_ADB_TIMEOUT_SECONDS": "1"})
        report = self.assert_conn_fail_and_skips(result)
        self.assertIn("timeout",
                      self.check_by_id(report, "connection-state")["detail"])


class PrerequisiteFailureTests(PreflightHarness):

    def failing(self, key, response, check_id):
        spec = success_spec()
        spec[key] = response
        result = self.run_tool(spec)
        self.assertEqual(result.returncode, 1)
        report = self.report(result)
        self.assertFalse(report["ok"])
        return self.check_by_id(report, check_id)

    def test_unsupported_api_level(self):
        check = self.failing("shell getprop ro.build.version.sdk",
                             {"stdout": "25\n"}, "android-api")
        self.assertEqual(check["status"], "fail")
        self.assertIn("api=25", check["detail"])

    def test_unsupported_abi(self):
        check = self.failing("shell getprop ro.product.cpu.abilist",
                             {"stdout": "mips,mips64\n"}, "abi")
        self.assertEqual(check["status"], "fail")
        self.assertIn("no supported abi", check["detail"])

    def test_insufficient_storage(self):
        check = self.failing("shell df /data",
                             {"stdout": DF_OK % 1024}, "storage")
        self.assertEqual(check["status"], "fail")
        self.assertIn("below required", check["detail"])

    def test_excessive_clock_skew(self):
        check = self.failing("shell date +%s",
                             {"stdout": "1700009999\n"}, "clock-timezone")
        self.assertEqual(check["status"], "fail")
        self.assertIn("skew", check["detail"])

    def test_missing_timezone(self):
        check = self.failing("shell getprop persist.sys.timezone",
                             {"stdout": "\n"}, "clock-timezone")
        self.assertEqual(check["status"], "fail")

    def test_unresolved_home_activity_fails(self):
        # resolve-activity succeeds but names no component: the current HOME
        # activity did not resolve, so launcher-state must fail (not pass with
        # home=unrecognized) and the overall report must be unsuccessful.
        check = self.failing(RESOLVE_HOME_KEY,
                             {"stdout": "No activity found\n"},
                             "launcher-state")
        self.assertEqual(check["status"], "fail")
        self.assertNotIn("unrecognized", check["detail"])

    def test_empty_property_value_fails_without_crashing(self):
        # An allowlisted query that exits 0 with empty stdout must produce a
        # failed prerequisite check, never terminate the tool without its JSON.
        check = self.failing("shell getprop ro.build.version.sdk",
                             {"stdout": ""}, "android-api")
        self.assertEqual(check["status"], "fail")


class HostileOutputTests(PreflightHarness):

    def test_malicious_property_value_is_rejected(self):
        check_spec = success_spec()
        check_spec["shell getprop ro.build.version.sdk"] = {
            "stdout": "29; reboot\n"}
        result = self.run_tool(check_spec)
        self.assertEqual(result.returncode, 1)
        check = self.check_by_id(self.report(result), "android-api")
        self.assertEqual(check["status"], "fail")
        self.assertIn("unparseable", check["detail"])
        self.assertNotIn("reboot", result.stdout)

    def test_control_bytes_and_serial_never_reach_the_report(self):
        spec = {"get-state": {
            "rc": 1,
            "stderr": "error: device '" + SERIAL +
                      "' not found\x1b[31m\x07\x08\n"}}
        result = self.run_tool(spec)
        self.assertEqual(result.returncode, 1)
        self.report(result)
        self.assert_no_leak(result, SERIAL, "\x1b", "\x07", "\x08")
        self.assertIn(adb_preflight.fingerprint(SALT, SERIAL), result.stdout)

    def test_unparseable_df_output(self):
        spec = success_spec()
        spec["shell df /data"] = {"stdout": "df: /data: Permission denied\n"}
        result = self.run_tool(spec)
        self.assertEqual(result.returncode, 1)
        check = self.check_by_id(self.report(result), "storage")
        self.assertEqual(check["status"], "fail")


class RedactionAndTargetHandlingTests(PreflightHarness):

    def test_target_only_from_environment(self):
        result = self.run_tool(success_spec(), args=["SOMESERIAL999"])
        self.assertEqual(result.returncode, 2)
        self.assertEqual(self.calls(), [])
        self.assertNotIn("SOMESERIAL999", result.stdout)

    def test_malformed_target_is_rejected_without_echo(self):
        bad = "evil target;rm -rf"
        result = self.run_tool(success_spec(), target=bad)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(self.calls(), [])
        self.assert_no_leak(result, bad, "evil")

    def test_fingerprint_is_salted_and_non_reversible(self):
        fp_a = adb_preflight.fingerprint("salt-a", SERIAL)
        fp_b = adb_preflight.fingerprint("salt-b", SERIAL)
        self.assertNotEqual(fp_a, fp_b)
        self.assertNotIn(SERIAL, fp_a)
        self.assertTrue(fp_a.startswith("tgt-"))

    def test_adb_location_is_not_reported(self):
        result = self.run_tool(success_spec())
        self.assertEqual(result.returncode, 0)
        self.assert_no_leak(result, FAKE_ADB, os.path.dirname(FAKE_ADB))

    def test_adb_command_name_resolves_through_path(self):
        path = TESTS_DIR + os.pathsep + os.environ.get("PATH", "")
        result = self.run_tool(
            success_spec(),
            adb=os.path.basename(FAKE_ADB),
            env_overrides={"PATH": path},
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(self.report(result)["ok"])

    def test_short_serial_keeps_report_valid_json(self):
        # A short serial that collides with a numeric field ("29" == min_api)
        # must not corrupt the machine-readable report: redaction applies to
        # string values only, so unquoted numbers stay intact and stdout parses.
        result = self.run_tool(success_spec(), target="29")
        report = self.report(result)  # fails the test if stdout is not JSON
        self.assertEqual(report["requirements"]["min_api"], 29)
        self.assertIsInstance(report["requirements"]["min_api"], int)


class OutputWritingTests(PreflightHarness):

    def test_out_file_matches_stdout(self):
        out_path = os.path.join(self.scenario, "report.json")
        result = self.run_tool(success_spec(), args=["--out", out_path])
        self.assertEqual(result.returncode, 0, result.stderr)
        with open(out_path, encoding="utf-8") as handle:
            self.assertEqual(handle.read(), result.stdout)

    def test_unwritable_out_path_is_a_clean_config_error(self):
        # --out naming a directory raises OSError on write; the tool must not
        # emit a traceback or masquerade as an unmet prerequisite. It emits the
        # JSON report on stdout and exits with the usage/configuration code.
        result = self.run_tool(success_spec(), args=["--out", self.scenario])
        self.assertEqual(result.returncode, 2)
        self.report(result)  # stdout still carries the JSON report
        self.assertNotIn("Traceback", result.stderr)
        self.assertNotIn("Traceback", result.stdout)


class ReadOnlyContractTests(PreflightHarness):

    MUTATING = [
        ["install", "app.apk"],
        ["install-multiple", "a.apk"],
        ["uninstall", "com.befeast.tx10clock"],
        ["push", "a", "/data/local/tmp/a"],
        ["pull", "/data/local/tmp/a"],
        ["reboot"],
        ["root"],
        ["remount"],
        ["sideload", "ota.zip"],
        ["tcpip", "5555"],
        ["connect", "host:5555"],
        ["forward", "tcp:1", "tcp:1"],
        ["wait-for-device"],
        ["shell", "am", "start", "-n", "x/y"],
        ["shell", "am", "force-stop", "com.befeast.tx10clock"],
        ["shell", "settings", "put", "global", "adb_enabled", "0"],
        ["shell", "setprop", "persist.sys.timezone", "UTC"],
        ["shell", "pm", "uninstall", "com.befeast.tx10clock"],
        ["shell", "pm", "grant", "p", "perm"],
        ["shell", "cmd", "package", "install-existing", "p"],
        ["shell", "rm", "-rf", "/data"],
        ["shell", "reboot"],
        ["shell", "svc", "power", "reboot"],
        ["shell", "getprop", "ro.serialno"],
        ["shell", "sh", "-c", "id"],
        ["shell", "echo", "hi;reboot"],
        ["-s", "X", "install", "app.apk"],
    ]

    ALLOWED = [
        ["devices"],
        ["devices", "-l"],
        ["get-state"],
        ["-s", "X", "get-state"],
        ["shell", "getprop", "ro.build.version.sdk"],
        ["shell", "getprop", "ro.product.cpu.abilist"],
        ["shell", "getprop", "persist.sys.timezone"],
        ["shell", "pm", "list", "packages", "com.befeast.tx10clock"],
        ["shell", "df", "/data"],
        ["shell", "date", "+%s"],
    ]

    def test_mutating_commands_are_structurally_unreachable(self):
        for argv in self.MUTATING:
            with self.assertRaises(adb_preflight.ReadOnlyViolation,
                                   msg="allowed: %r" % (argv,)):
                adb_preflight.assert_read_only(argv)

    def test_read_only_queries_are_allowed(self):
        for argv in self.ALLOWED:
            adb_preflight.assert_read_only(argv)  # must not raise

    def test_every_executed_command_satisfies_the_allowlist(self):
        result = self.run_tool(success_spec())
        self.assertEqual(result.returncode, 0)
        executed = self.calls()
        self.assertGreater(len(executed), 0)
        for argv in executed:
            adb_preflight.assert_read_only(argv)  # must not raise


if __name__ == "__main__":
    unittest.main()
