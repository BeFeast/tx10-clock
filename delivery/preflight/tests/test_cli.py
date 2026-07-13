"""CLI-level test driving the real subprocess runner against a fake `adb`
executable (still no device, no network, no SDK)."""

import contextlib
import io
import json
import os
import stat
import sys
import tempfile
import unittest

# Import the CLI module by path (it lives outside the package tree).
_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
sys.path.insert(0, os.path.join(_REPO_ROOT, "tools", "adb-preflight"))
import adb_preflight  # noqa: E402


FAKE_ADB_TEMPLATE = r"""#!/usr/bin/env bash
# Minimal read-only fake adb for the CLI test. Ignores a leading `-s SERIAL`.
if [ "$1" = "-s" ]; then shift 2; fi
sub="$1"; shift || true
case "$sub" in
  devices)
    printf 'List of devices attached\n'
    printf 'FAKESERIALX\tdevice product:tx10 model:TX10Pro device:tx10 transport_id:1\n'
    ;;
  shell)
    tool="$1"; shift || true
    case "$tool" in
      getprop)
        case "$1" in
          ro.build.version.sdk) echo "__SDK__";;
          ro.build.version.release) echo "11";;
          ro.product.cpu.abi) echo "arm64-v8a";;
          ro.product.cpu.abilist) echo "arm64-v8a,armeabi-v7a";;
          persist.sys.timezone) echo "Europe/Amsterdam";;
          *) echo "";;
        esac
        ;;
      df) printf 'Filesystem 1K-blocks Used Available Use%% Mounted on\n/dev/dm-0 2000000 100 1500000 1%% /data\n';;
      date) echo "1700000000";;
      pm) exit 1;;
      cmd) echo "com.android.tv.launcher/.MainActivity";;
      *) echo "";;
    esac
    ;;
  *) echo "";;
esac
"""


def _write_fake_adb(directory, sdk):
    path = os.path.join(directory, "adb")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(FAKE_ADB_TEMPLATE.replace("__SDK__", str(sdk)))
    os.chmod(path, os.stat(path).st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)
    return path


def _run_cli(args):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = adb_preflight.main(args)
    return code, out.getvalue()


class CliTest(unittest.TestCase):
    def test_ready_device_exits_zero(self):
        with tempfile.TemporaryDirectory() as d:
            adb = _write_fake_adb(d, sdk=30)
            code, stdout = _run_cli(["--adb", adb])
            self.assertEqual(code, 0)
            report = json.loads(stdout)
            self.assertTrue(report["ready"])
            self.assertNotIn("FAKESERIALX", stdout)  # device serial never echoed

    def test_low_api_exits_one(self):
        with tempfile.TemporaryDirectory() as d:
            adb = _write_fake_adb(d, sdk=25)
            code, stdout = _run_cli(["--adb", adb])
            self.assertEqual(code, 1)
            report = json.loads(stdout)
            self.assertFalse(report["ready"])
            self.assertIn("api_level", report["failures"])

    def test_missing_adb_is_usage_error(self):
        code, stdout = _run_cli(["--adb", "/nonexistent/path/to/adb"])
        self.assertEqual(code, 2)
        self.assertEqual(stdout, "")  # no report emitted on usage error

    def test_target_from_flag_is_not_echoed(self):
        with tempfile.TemporaryDirectory() as d:
            adb = _write_fake_adb(d, sdk=30)
            # The fake lists FAKESERIALX; use it as the target so connection passes.
            code, stdout = _run_cli(["--adb", adb, "--target", "FAKESERIALX"])
            self.assertEqual(code, 0)
            self.assertNotIn("FAKESERIALX", stdout)


if __name__ == "__main__":
    unittest.main()
