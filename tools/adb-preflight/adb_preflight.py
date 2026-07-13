#!/usr/bin/env python3
"""adb-preflight — read-only ADB delivery preflight for the TX10 clock app.

Proves that a delivery target satisfies the install prerequisites WITHOUT
mutating anything. The tool is dry-run by construction:

  * Every ADB invocation flows through one choke point (`run_adb`) that
    validates the full argument vector against a strict allowlist of
    read-only queries. Mutating verbs (install, uninstall, push, reboot,
    grant, start, settings put, ...) are structurally unreachable.
  * The live endpoint/serial is accepted ONLY at runtime from the private
    execution environment (env var TX10_ADB_TARGET). It is never accepted
    as a command-line argument, never written to the report, and never
    echoed; reports and diagnostics carry a non-reversible salted SHA-256
    fingerprint instead.
  * Output captured from ADB is treated as hostile: control characters are
    stripped, size is capped, every value is validated against an expected
    format before use, and known-sensitive strings are redacted.

Emits a deterministic machine-readable JSON report (sorted keys, fixed check
order, no timestamps) on stdout and exits 0 only when every prerequisite is
met. Requires nothing beyond a Python 3 standard library — no Android SDK
download and no licence acceptance.

Environment:
  TX10_ADB_TARGET                        device serial or host:port (optional;
                                         otherwise exactly one attached device
                                         is required)
  TX10_ADB                               path to the adb binary (default: PATH)
  TX10_PREFLIGHT_SALT                    fingerprint salt; when unset an
                                         ephemeral random salt is used, so the
                                         fingerprint is stable only per run
  TX10_PREFLIGHT_ADB_TIMEOUT_SECONDS     per-invocation timeout (default 10)
  TX10_PREFLIGHT_MIN_FREE_KIB            required free space on /data
                                         (default 65536 = 64 MiB)
  TX10_PREFLIGHT_MAX_CLOCK_SKEW_SECONDS  allowed |device - host| clock skew
                                         (default 300)
  TX10_PREFLIGHT_HOST_EPOCH              host epoch override for tests
"""

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time

SCHEMA = "tx10-adb-preflight/v1"
TOOL = "adb-preflight"
PACKAGE = "com.befeast.tx10clock"

REQUIRED_MIN_SDK = 29
SUPPORTED_ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

DEFAULT_TIMEOUT_SECONDS = 10
DEFAULT_MIN_FREE_KIB = 64 * 1024
DEFAULT_MAX_CLOCK_SKEW_SECONDS = 300
OUTPUT_CAP_BYTES = 64 * 1024

EXIT_OK = 0
EXIT_PREREQ_UNMET = 1
EXIT_USAGE = 2

# Shape of a plausible ADB serial / tcp endpoint. Used only to validate the
# runtime-provided target before it is passed to `adb -s`; never echoed.
_TARGET_RE = re.compile(r"^[A-Za-z0-9._:\[\]-]{2,128}$")

# Device-reported values must match these before they are trusted.
_SDK_RE = re.compile(r"^\d{1,3}$")
_EPOCH_RE = re.compile(r"^\d{9,11}$")
_TZ_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_+/-]{0,63}$")
_ABI_RE = re.compile(r"^[a-z0-9-]{2,32}$")
_COMPONENT_RE = re.compile(r"^[A-Za-z0-9._$]+/[A-Za-z0-9._$]+$")
_DEVICE_STATE_WORDS = ("device", "offline", "unauthorized", "recovery",
                       "sideload", "bootloader", "rescue", "host",
                       "no permissions")

# Shell argument tokens are restricted to this charset so a compromised or
# spoofed value can never smuggle `;`, `|`, `$(...)`, redirects, or quotes
# into the device shell.
_SHELL_TOKEN_RE = re.compile(r"^[A-Za-z0-9._/+%:=-]+$")

# Documented mutating verbs. run_adb refuses them explicitly (defence in
# depth on top of the allowlist) so a future edit that widens the allowlist
# still cannot reach them silently.
FORBIDDEN_ADB_VERBS = frozenset({
    "install", "install-multiple", "install-multi-package", "uninstall",
    "push", "pull", "sideload", "backup", "restore", "reboot", "root",
    "unroot", "remount", "disable-verity", "enable-verity", "tcpip", "usb",
    "connect", "disconnect", "pair", "forward", "reverse", "emu", "wait-for",
})
FORBIDDEN_SHELL_LEADERS = frozenset({
    "am", "settings", "setprop", "svc", "input", "wm", "reboot", "stop",
    "start", "rm", "cp", "mv", "dd", "mount", "umount", "sh", "su",
    "kill", "killall", "sync", "mkdir", "touch", "chmod", "chown",
})

_ALLOWED_PROPS = frozenset({
    "ro.build.version.sdk",
    "ro.build.version.release",
    "ro.product.cpu.abilist",
    "ro.product.cpu.abi",
    "persist.sys.timezone",
})


class ReadOnlyViolation(Exception):
    """An ADB invocation outside the read-only allowlist was attempted."""


class UsageError(Exception):
    """Invalid invocation or runtime environment configuration."""


def assert_read_only(args):
    """Validate a full adb argument vector (after the binary, including any
    `-s <serial>` prefix) against the read-only allowlist. Raises
    ReadOnlyViolation otherwise. This is the single dry-run gate."""
    args = list(args)
    if len(args) >= 2 and args[0] == "-s":
        args = args[2:]
    if not args:
        raise ReadOnlyViolation("empty adb command")

    verb = args[0]
    if verb in FORBIDDEN_ADB_VERBS or verb.startswith("wait-for"):
        raise ReadOnlyViolation("mutating or session-altering adb verb: %s" % verb)

    if args == ["devices"] or args == ["devices", "-l"]:
        return
    if args == ["get-state"]:
        return
    if verb == "shell":
        _assert_read_only_shell(args[1:])
        return
    raise ReadOnlyViolation("adb verb not in read-only allowlist: %s" % verb)


def _assert_read_only_shell(tokens):
    if not tokens:
        raise ReadOnlyViolation("empty shell command")
    for tok in tokens:
        if not _SHELL_TOKEN_RE.match(tok):
            raise ReadOnlyViolation("shell token outside safe charset")
    leader = tokens[0]
    if leader in FORBIDDEN_SHELL_LEADERS:
        raise ReadOnlyViolation("mutating shell command: %s" % leader)

    if leader == "getprop":
        if len(tokens) == 2 and tokens[1] in _ALLOWED_PROPS:
            return
        raise ReadOnlyViolation("getprop restricted to allowlisted properties")
    if leader == "pm":
        if (len(tokens) == 4 and tokens[1] == "list" and tokens[2] == "packages"
                and re.match(r"^[A-Za-z0-9._]+$", tokens[3])):
            return
        raise ReadOnlyViolation("pm restricted to `pm list packages <pkg>`")
    if leader == "cmd":
        if tokens == ["cmd", "package", "resolve-activity", "--brief",
                      "-a", "android.intent.action.MAIN",
                      "-c", "android.intent.category.HOME"]:
            return
        raise ReadOnlyViolation("cmd restricted to HOME resolve-activity query")
    if tokens == ["df", "/data"]:
        return
    if tokens == ["date", "+%s"]:
        return
    raise ReadOnlyViolation("shell command not in read-only allowlist: %s" % leader)


def sanitize(text):
    """Strip control characters (including ANSI escapes) from device output
    and cap its size. Device output is untrusted."""
    if isinstance(text, bytes):
        text = text.decode("utf-8", "replace")
    text = text[:OUTPUT_CAP_BYTES]
    return "".join(ch for ch in text if ch == "\n" or ch == "\t" or
                   (ch >= " " and ch != "\x7f"))


class Redactor:
    """Replaces known-sensitive strings (target endpoint/serial, adb path)
    in any outbound text with non-reversible placeholders."""

    def __init__(self):
        self._map = {}

    def add(self, secret, replacement):
        if secret:
            self._map[secret] = replacement

    def redact(self, text):
        for secret in sorted(self._map, key=len, reverse=True):
            text = text.replace(secret, self._map[secret])
        return text


def redact_values(value, redactor):
    """Redact known-sensitive substrings from every string leaf of a
    JSON-serializable value, leaving numbers, booleans, None, dict keys, and
    structure untouched.

    Applied to the report BEFORE serialization: redacting the serialized JSON
    text would rewrite unquoted numeric fields too (a short serial equal to
    "29" would corrupt "min_api": 29 into invalid JSON), whereas redacting
    string leaves keeps the report valid and machine-readable."""
    if isinstance(value, str):
        return redactor.redact(value)
    if isinstance(value, list):
        return [redact_values(v, redactor) for v in value]
    if isinstance(value, dict):
        return {k: redact_values(v, redactor) for k, v in value.items()}
    return value


def fingerprint(salt, value):
    digest = hashlib.sha256()
    digest.update(salt.encode("utf-8"))
    digest.update(b"\x00")
    digest.update(value.encode("utf-8"))
    return "tgt-" + digest.hexdigest()[:16]


def _env_int(env, name, default):
    raw = env.get(name, "").strip()
    if not raw:
        return default
    if not re.match(r"^\d{1,10}$", raw):
        raise UsageError("%s must be a non-negative integer" % name)
    return int(raw)


class AdbRunner:
    def __init__(self, adb_path, serial, timeout_seconds, redactor):
        self.adb_path = adb_path
        self.serial = serial
        self.timeout_seconds = timeout_seconds
        self.redactor = redactor

    def run(self, args, with_serial=True):
        """Run one allowlisted read-only adb command.

        Returns (rc, stdout, stderr) with sanitized, redacted output, or
        rc = -1 with stderr "timeout" when the deadline elapses.
        """
        argv = list(args)
        if with_serial and self.serial:
            argv = ["-s", self.serial] + argv
        assert_read_only(argv)
        try:
            proc = subprocess.run(
                [self.adb_path] + argv,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=self.timeout_seconds,
            )
            out = self.redactor.redact(sanitize(proc.stdout))
            err = self.redactor.redact(sanitize(proc.stderr))
            return proc.returncode, out, err
        except subprocess.TimeoutExpired:
            return -1, "", "timeout after %ds" % self.timeout_seconds
        except OSError as exc:
            return -1, "", self.redactor.redact(sanitize(str(exc)))


def _check(check_id, status, detail):
    return {"id": check_id, "status": status, "detail": detail}


def _skip(check_id):
    return _check(check_id, "skip", "not evaluated: connection prerequisite unmet")


DEVICE_CHECK_IDS = ("android-api", "abi", "package-state", "launcher-state",
                    "storage", "clock-timezone")


def _resolve_target(env, redactor, salt):
    """Determine the target serial. Returns (serial_or_None, source, error).

    The serial comes only from the private execution environment; when the
    env var is unset, autodetection later requires exactly one device."""
    raw = env.get("TX10_ADB_TARGET", "")
    raw = raw.strip()
    if not raw:
        return None, "autodetect", None
    if not _TARGET_RE.match(raw):
        # Do not echo the malformed value.
        return None, "env", "TX10_ADB_TARGET has an unsupported format"
    redactor.add(raw, fingerprint(salt, raw))
    return raw, "env", None


def _parse_devices(stdout, salt, redactor):
    """Parse `adb devices` output into (serial, state) pairs, registering
    every discovered serial for redaction before anything is reported."""
    entries = []
    for line in stdout.splitlines():
        line = line.strip()
        if (not line or line.startswith("*")
                or line.lower().startswith("list of devices")):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        serial, state = parts[0], parts[1]
        redactor.add(serial, fingerprint(salt, serial))
        if state not in _DEVICE_STATE_WORDS:
            state = "unknown"
        entries.append((serial, state))
    return entries


def _connection_check(runner, target, salt, redactor):
    """Returns (check_dict, resolved_serial_or_None)."""
    if target is not None:
        rc, out, err = runner.run(["get-state"])
        if rc == -1 and "timeout" in err:
            return _check("connection-state", "fail", err), None
        state = out.strip().splitlines()[0].strip() if out.strip() else ""
        if rc == 0 and state == "device":
            return _check("connection-state", "pass", "state=device"), target
        if state not in _DEVICE_STATE_WORDS:
            state = "unknown"
        detail = "state=%s" % state
        if rc != 0 and not out.strip():
            # e.g. "device '<serial>' not found" — serial already redacted.
            first = err.strip().splitlines()[0] if err.strip() else "adb error"
            detail = "not connected: %s" % first[:200]
        return _check("connection-state", "fail", detail), None

    rc, out, err = runner.run(["devices"], with_serial=False)
    if rc == -1 and "timeout" in err:
        return _check("connection-state", "fail", err), None
    if rc != 0:
        return _check("connection-state", "fail",
                      "adb devices failed (rc=%d)" % rc), None
    entries = _parse_devices(out, salt, redactor)
    if not entries:
        return _check("connection-state", "fail", "no devices attached"), None
    if len(entries) > 1:
        return _check("connection-state", "fail",
                      "%d devices attached; set TX10_ADB_TARGET to select one"
                      % len(entries)), None
    serial, state = entries[0]
    if state != "device":
        return _check("connection-state", "fail", "state=%s" % state), None
    return _check("connection-state", "pass", "state=device"), serial


def _shell_line(runner, args):
    """Run an allowlisted shell query, return (ok, first_line, error_detail)."""
    rc, out, err = runner.run(["shell"] + args)
    if rc == -1:
        return False, "", err or "adb invocation failed"
    if rc != 0:
        first = err.strip().splitlines()[0] if err.strip() else "rc=%d" % rc
        return False, "", "query failed: %s" % first[:200]
    lines = out.strip().splitlines()
    return True, (lines[0].strip() if lines else ""), None


def _api_check(runner):
    ok, line, err = _shell_line(runner, ["getprop", "ro.build.version.sdk"])
    if not ok:
        return _check("android-api", "fail", err)
    if not _SDK_RE.match(line):
        return _check("android-api", "fail",
                      "unparseable ro.build.version.sdk value")
    sdk = int(line)
    if sdk < REQUIRED_MIN_SDK:
        return _check("android-api", "fail",
                      "api=%d unsupported (requires >= %d)"
                      % (sdk, REQUIRED_MIN_SDK))
    return _check("android-api", "pass", "api=%d" % sdk)


def _abi_check(runner):
    ok, line, err = _shell_line(runner, ["getprop", "ro.product.cpu.abilist"])
    if ok and not line:
        ok, line, err = _shell_line(runner, ["getprop", "ro.product.cpu.abi"])
    if not ok:
        return _check("abi", "fail", err)
    abis = [a.strip() for a in line.split(",") if a.strip()]
    if not abis or not all(_ABI_RE.match(a) for a in abis):
        return _check("abi", "fail", "unparseable abi list")
    supported = [a for a in abis if a in SUPPORTED_ABIS]
    if not supported:
        return _check("abi", "fail",
                      "no supported abi in [%s]" % ",".join(abis))
    return _check("abi", "pass", "abis=%s" % ",".join(abis))


def _shell_all(runner, args, check_id):
    """Run an allowlisted shell query, return (out_or_None, fail_check)."""
    rc, out, err = runner.run(["shell"] + args)
    if rc == -1:
        return None, _check(check_id, "fail", err or "adb invocation failed")
    if rc != 0:
        first = err.strip().splitlines()[0] if err.strip() else "rc=%d" % rc
        return None, _check(check_id, "fail", "query failed: %s" % first[:200])
    return out, None


def _package_check(runner):
    out, fail = _shell_all(runner, ["pm", "list", "packages", PACKAGE],
                           "package-state")
    if fail:
        return fail
    installed = any(l.strip() == "package:%s" % PACKAGE
                    for l in out.splitlines())
    return _check("package-state", "pass",
                  "installed=%s" % ("true" if installed else "false"))


def _launcher_check(runner):
    out, fail = _shell_all(
        runner,
        ["cmd", "package", "resolve-activity", "--brief",
         "-a", "android.intent.action.MAIN",
         "-c", "android.intent.category.HOME"],
        "launcher-state")
    if fail:
        return fail
    # --brief prints a match-details line first; the component follows.
    component = next((l.strip() for l in out.splitlines()
                      if _COMPONENT_RE.match(l.strip())), None)
    if component is None:
        # resolve-activity succeeded but named no component (e.g. "No activity
        # found"). The documented prerequisite is that the current HOME
        # activity resolves, so an unresolved launcher is a failure, not a pass.
        return _check("launcher-state", "fail",
                      "current HOME activity did not resolve")
    return _check("launcher-state", "pass", "home=%s" % component)


def _storage_check(runner, min_free_kib):
    rc, out, err = runner.run(["shell", "df", "/data"])
    if rc != 0:
        detail = err.strip().splitlines()[0][:200] if err.strip() else "rc=%d" % rc
        return _check("storage", "fail", "query failed: %s" % detail)
    avail_kib = None
    for line in out.splitlines():
        tokens = line.split()
        if len(tokens) >= 5 and tokens[-1] == "/data" and tokens[-3].isdigit():
            avail_kib = int(tokens[-3])
            break
    if avail_kib is None:
        return _check("storage", "fail", "unparseable df output")
    if avail_kib < min_free_kib:
        return _check("storage", "fail",
                      "free=%dKiB below required %dKiB" % (avail_kib, min_free_kib))
    return _check("storage", "pass",
                  "free=%dKiB (required %dKiB)" % (avail_kib, min_free_kib))


def _clock_check(runner, host_epoch, max_skew):
    ok, line, err = _shell_line(runner, ["date", "+%s"])
    if not ok:
        return _check("clock-timezone", "fail", err)
    if not _EPOCH_RE.match(line):
        return _check("clock-timezone", "fail", "unparseable device epoch")
    skew = abs(int(line) - host_epoch)

    ok, tz, err = _shell_line(runner, ["getprop", "persist.sys.timezone"])
    if not ok:
        return _check("clock-timezone", "fail", err)
    tz_valid = bool(tz) and bool(_TZ_RE.match(tz))

    if skew > max_skew:
        return _check("clock-timezone", "fail",
                      "clock skew %ds exceeds %ds" % (skew, max_skew))
    if not tz_valid:
        return _check("clock-timezone", "fail",
                      "timezone unset or unparseable")
    return _check("clock-timezone", "pass",
                  "skew=%ds (max %ds) tz=%s" % (skew, max_skew, tz))


def run_preflight(env, argv):
    out_path = None
    args = list(argv)
    while args:
        arg = args.pop(0)
        if arg == "--out":
            if not args:
                raise UsageError("--out requires a file path")
            out_path = args.pop(0)
        elif arg in ("-h", "--help"):
            raise UsageError(__doc__.strip().splitlines()[0] +
                             "\nusage: adb_preflight.py [--out report.json]\n"
                             "The target is read from TX10_ADB_TARGET only.")
        else:
            # No positional arguments: a serial/endpoint on the command line
            # would leak into shell history and process listings.
            raise UsageError("unexpected argument; the target is accepted "
                             "only via the TX10_ADB_TARGET environment variable")

    redactor = Redactor()
    salt = env.get("TX10_PREFLIGHT_SALT", "") or os.urandom(16).hex()
    salt_source = "env" if env.get("TX10_PREFLIGHT_SALT") else "ephemeral"

    timeout_s = _env_int(env, "TX10_PREFLIGHT_ADB_TIMEOUT_SECONDS",
                         DEFAULT_TIMEOUT_SECONDS)
    min_free_kib = _env_int(env, "TX10_PREFLIGHT_MIN_FREE_KIB",
                            DEFAULT_MIN_FREE_KIB)
    max_skew = _env_int(env, "TX10_PREFLIGHT_MAX_CLOCK_SKEW_SECONDS",
                        DEFAULT_MAX_CLOCK_SKEW_SECONDS)
    host_epoch = _env_int(env, "TX10_PREFLIGHT_HOST_EPOCH", int(time.time()))

    target, source, target_err = _resolve_target(env, redactor, salt)
    if target_err:
        raise UsageError(target_err)

    configured_adb = env.get("TX10_ADB", "").strip()
    if configured_adb and not os.path.dirname(configured_adb):
        adb_path = shutil.which(configured_adb)
    else:
        adb_path = configured_adb or shutil.which("adb")
    if adb_path:
        # The adb location may embed private host paths; never report it.
        redactor.add(adb_path, "[adb]")
        redactor.add(os.path.dirname(adb_path), "[adb-dir]")

    checks = []
    resolved = None
    if not adb_path or not os.path.isfile(adb_path):
        checks.append(_check("adb-binary", "fail",
                             "adb binary not found (set TX10_ADB or PATH)"))
        checks.append(_check("connection-state", "skip",
                             "not evaluated: adb binary unavailable"))
        checks.extend(_skip(cid) for cid in DEVICE_CHECK_IDS)
    else:
        checks.append(_check("adb-binary", "pass", "adb binary present"))
        runner = AdbRunner(adb_path, target, timeout_s, redactor)
        conn, resolved = _connection_check(runner, target, salt, redactor)
        checks.append(conn)
        if resolved is None:
            checks.extend(_skip(cid) for cid in DEVICE_CHECK_IDS)
        else:
            runner.serial = resolved
            checks.append(_api_check(runner))
            checks.append(_abi_check(runner))
            checks.append(_package_check(runner))
            checks.append(_launcher_check(runner))
            checks.append(_storage_check(runner, min_free_kib))
            checks.append(_clock_check(runner, host_epoch, max_skew))

    ok = all(c["status"] == "pass" for c in checks)
    fingerprint_value = fingerprint(salt, resolved or target or "")
    report = {
        "schema": SCHEMA,
        "tool": TOOL,
        "package": PACKAGE,
        "target": {
            "fingerprint": fingerprint_value if (resolved or target) else None,
            "source": source,
            "salt_source": salt_source,
        },
        "requirements": {
            "min_api": REQUIRED_MIN_SDK,
            "supported_abis": list(SUPPORTED_ABIS),
            "min_free_kib": min_free_kib,
            "max_clock_skew_seconds": max_skew,
        },
        "checks": checks,
        "ok": ok,
    }

    # Defence-in-depth redaction pass. Applied to string leaves before
    # serialization so numeric fields stay intact and the output is valid JSON.
    report = redact_values(report, redactor)
    # The fingerprint is a non-reversible token that is safe to expose; restore
    # it verbatim in case a short serial happened to be a substring of its hash.
    report["target"]["fingerprint"] = (
        fingerprint_value if (resolved or target) else None)

    # Deterministic serialization (sorted keys, fixed order, no timestamps).
    text = json.dumps(report, sort_keys=True, indent=2) + "\n"

    if out_path:
        try:
            with open(out_path, "w", encoding="utf-8") as handle:
                handle.write(text)
        except OSError as exc:
            # A bad --out destination (a directory, a missing parent, an
            # unwritable path) is a configuration error, not an unmet device
            # prerequisite. Still emit the report to stdout so it is not lost,
            # then fail cleanly instead of surfacing a traceback.
            sys.stdout.write(text)
            raise UsageError("cannot write report to --out path: %s"
                             % (exc.strerror or "write failed"))
    sys.stdout.write(text)

    failed = sum(1 for c in checks if c["status"] != "pass")
    summary = "%s: %s (%d/%d checks passed)" % (
        TOOL, "PASS" if ok else "FAIL", len(checks) - failed, len(checks))
    sys.stderr.write(redactor.redact(summary) + "\n")
    return EXIT_OK if ok else EXIT_PREREQ_UNMET


def main(argv):
    try:
        return run_preflight(os.environ, argv)
    except UsageError as exc:
        sys.stderr.write("%s: %s\n" % (TOOL, exc))
        return EXIT_USAGE
    except ReadOnlyViolation as exc:
        # Should be unreachable: indicates a programming error, and the
        # offending command was blocked before execution.
        sys.stderr.write("%s: read-only contract violation blocked: %s\n"
                         % (TOOL, exc))
        return EXIT_USAGE


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
