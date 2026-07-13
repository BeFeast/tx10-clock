"""The read-only-by-construction contract: mutating adb calls are rejected
before any process is spawned."""

import unittest

from delivery.preflight.adb import AdbClient, assert_readonly
from delivery.preflight.errors import ReadOnlyViolation


# Commands the preflight is allowed to run.
ALLOWED = [
    ["devices", "-l"],
    ["get-state"],
    ["version"],
    ["shell", "getprop", "ro.build.version.sdk"],
    ["shell", "getprop", "persist.sys.timezone"],
    ["shell", "df", "-k", "/data"],
    ["shell", "date"],
    ["shell", "date", "+%s"],
    ["shell", "pm", "path", "com.befeast.tx10clock"],
    ["shell", "pm", "list", "packages"],
    ["shell", "cmd", "package", "resolve-activity", "--brief",
     "-c", "android.intent.category.HOME", "-a", "android.intent.action.MAIN"],
    ["shell", "settings", "get", "global", "auto_time"],
]

# Every mutation the acceptance criteria forbids, plus escape attempts.
FORBIDDEN = [
    # install / uninstall / push / pull / sync
    ["install", "/tmp/app.apk"],
    ["install-multiple", "/tmp/a.apk", "/tmp/b.apk"],
    ["uninstall", "com.befeast.tx10clock"],
    ["push", "/tmp/x", "/data/local/tmp/x"],
    ["pull", "/data/x", "/tmp/x"],
    ["sync"],
    # reboot / connection / transport mutation
    ["reboot"],
    ["root"],
    ["unroot"],
    ["remount"],
    ["disable-verity"],
    ["tcpip", "5555"],
    ["connect", "192.0.2.1:5555"],
    ["disconnect"],
    ["forward", "tcp:1", "tcp:2"],
    ["reverse", "tcp:1", "tcp:2"],
    ["sideload", "/tmp/ota.zip"],
    # shell-level mutations
    ["shell", "pm", "install", "/data/local/tmp/app.apk"],
    ["shell", "pm", "uninstall", "com.befeast.tx10clock"],
    ["shell", "pm", "grant", "com.x", "android.permission.CAMERA"],
    ["shell", "pm", "clear", "com.x"],
    ["shell", "am", "start", "-n", "com.x/.Main"],
    ["shell", "am", "force-stop", "com.x"],
    ["shell", "svc", "power", "reboot"],
    ["shell", "settings", "put", "global", "adb_enabled", "1"],
    ["shell", "settings", "delete", "global", "x"],
    ["shell", "cmd", "package", "install", "x"],
    ["shell", "cmd", "activity", "start", "x"],
    ["shell", "date", "-s", "2020-01-01"],          # sets the clock
    ["shell", "date", "01011200"],                  # sets the clock (positional)
    ["shell", "setprop", "persist.sys.x", "1"],
    ["shell", "input", "keyevent", "26"],
    ["shell", "monkey", "-p", "com.x", "1"],
    ["shell", "rm", "-rf", "/data"],
    ["shell", "content", "insert", "--uri", "x"],
    # metacharacter / chaining / redirection escapes on an allowed verb
    ["shell", "getprop;reboot"],
    ["shell", "getprop", "ro.x;reboot"],
    ["shell", "getprop", "ro.x&&reboot"],
    ["shell", "getprop", "$(reboot)"],
    ["shell", "getprop", "ro.x`reboot`"],
    ["shell", "df", ">/data/x"],
    ["shell", "getprop", "ro.x|sh"],
    # leading global flag must not appear at the guard
    ["-s", "SER", "shell", "getprop", "ro.x"],
    [],
]


class RecordingRunner:
    """A runner that fails loudly if it is ever invoked."""

    def __init__(self):
        self.called = False

    def __call__(self, argv, timeout):
        self.called = True
        raise AssertionError(f"runner must not be reached for {argv!r}")


class ReadOnlyGuardTest(unittest.TestCase):
    def test_allowed_commands_pass(self):
        for argv in ALLOWED:
            with self.subTest(argv=argv):
                assert_readonly(argv)  # must not raise

    def test_forbidden_commands_rejected(self):
        for argv in FORBIDDEN:
            with self.subTest(argv=argv):
                with self.assertRaises(ReadOnlyViolation):
                    assert_readonly(argv)

    def test_client_rejects_mutation_before_spawn(self):
        runner = RecordingRunner()
        client = AdbClient(runner, target="SER")
        for argv in FORBIDDEN[:20]:
            with self.subTest(argv=argv):
                with self.assertRaises(ReadOnlyViolation):
                    client.run(argv)
        self.assertFalse(runner.called, "no process may be spawned for a rejected command")

    def test_client_allows_readonly(self):
        # A benign read-only call reaches the runner exactly once.
        from delivery.preflight.tests.fake_adb import FakeAdb

        fake = FakeAdb()
        client = AdbClient(fake)
        client.run(["shell", "getprop", "ro.build.version.sdk"])
        self.assertEqual(len(fake.calls), 1)


if __name__ == "__main__":
    unittest.main()
