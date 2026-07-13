"""Individual read-only preflight checks.

Each check runs one or more allowlisted ADB queries and returns a
:class:`CheckResult`. Checks treat all device output as untrusted: values are
parsed strictly (so malformed or injected output yields ``error`` rather than a
false ``pass``) and every device-derived string is scrubbed by the redactor
before it enters the result.
"""

from __future__ import annotations

import re
from typing import List, Optional, Tuple

from .adb import AdbClient, AdbResult
from .errors import AdbTimeout, ReadOnlyViolation
from .redaction import Redactor
from .report import CheckResult, ERROR, FAIL, PASS, SKIP, WARN
from .requirements import Requirements

_INT_RE = re.compile(r"^\d+$")
_USE_PCT_RE = re.compile(r"^\d+%$")


def _error(check_id: str, title: str, required: bool, redactor: Redactor, exc: Exception) -> CheckResult:
    detail = redactor.scrub(str(exc)) or exc.__class__.__name__
    return CheckResult(
        id=check_id,
        title=title,
        status=ERROR,
        required=required,
        summary=f"check could not complete: {detail}",
        data={"error": exc.__class__.__name__},
    )


def _guarded(check_id: str, title: str, required: bool, redactor: Redactor, fn):
    """Run ``fn`` and convert timeouts / value errors into an ``error`` result.

    A :class:`ReadOnlyViolation` is a tool bug (a check tried to run a mutating
    command) and is intentionally *not* swallowed.
    """
    try:
        return fn()
    except ReadOnlyViolation:
        raise
    except AdbTimeout as exc:
        return _error(check_id, title, required, redactor, exc)
    except (ValueError, OSError) as exc:
        return _error(check_id, title, required, redactor, exc)


def _clean_value(result: AdbResult, redactor: Redactor) -> str:
    """Single-line, redacted view of a command's stdout."""
    return redactor.scrub(result.stdout.strip())


def _parse_int(text: str) -> Optional[int]:
    """Parse a clean non-negative integer, or ``None`` if the text is not one.

    Strict by design: junk / injected trailing content makes this return
    ``None`` so the caller reports ``error`` instead of trusting the value.
    """
    token = text.strip()
    if _INT_RE.match(token):
        return int(token)
    return None


# --- connection ------------------------------------------------------------


def _parse_devices(stdout: str) -> List[Tuple[str, str]]:
    """Parse ``adb devices -l`` into ``(serial, state)`` pairs.

    Serials are used only for internal matching/counting; callers must not emit
    them.
    """
    devices: List[Tuple[str, str]] = []
    for line in stdout.splitlines():
        line = line.strip()
        if not line or line.lower().startswith("list of devices"):
            continue
        if line.startswith("*") or line.startswith("adb "):
            # daemon chatter ("* daemon started successfully")
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        devices.append((parts[0], parts[1]))
    return devices


def check_connection(client: AdbClient, req: Requirements, redactor: Redactor) -> CheckResult:
    cid, title, required = "connection", "ADB connection state", True

    def run() -> CheckResult:
        result = client.devices()
        devices = _parse_devices(result.stdout)
        device_count = len(devices)

        target = client.target
        state: Optional[str] = None
        if target is not None:
            match = next((s for s in devices if s[0] == target), None)
            if match is None:
                return CheckResult(
                    cid, title, FAIL, required,
                    "target is not present in the adb device list",
                    {"device_count": device_count, "target_present": False},
                )
            state = match[1]
        else:
            if device_count == 0:
                return CheckResult(
                    cid, title, FAIL, required,
                    "no device is connected",
                    {"device_count": 0},
                )
            if device_count > 1:
                return CheckResult(
                    cid, title, FAIL, required,
                    "multiple devices connected; a target selector is required",
                    {"device_count": device_count},
                )
            state = devices[0][1]

        data = {"state": state, "device_count": device_count}
        if state == "device":
            return CheckResult(cid, title, PASS, required, "device is online and authorized", data)
        if state == "unauthorized":
            return CheckResult(cid, title, FAIL, required, "device is unauthorized (approve the adb key)", data)
        if state == "offline":
            return CheckResult(cid, title, FAIL, required, "device is offline", data)
        return CheckResult(cid, title, FAIL, required, f"device is not ready (state={state})", data)

    return _guarded(cid, title, required, redactor, run)


# --- api level -------------------------------------------------------------


def check_api_level(client: AdbClient, req: Requirements, redactor: Redactor) -> CheckResult:
    cid, title, required = "api_level", "Android API level", True

    def run() -> CheckResult:
        sdk_raw = client.getprop("ro.build.version.sdk").stdout
        api = _parse_int(sdk_raw)
        release = _clean_value(client.getprop("ro.build.version.release"), redactor)
        if api is None:
            return CheckResult(
                cid, title, ERROR, required,
                "device reported an unparseable API level",
                {"min_api_level": req.min_api_level, "android_release": release},
            )
        data = {"api_level": api, "min_api_level": req.min_api_level, "android_release": release}
        if api >= req.min_api_level:
            return CheckResult(cid, title, PASS, required, f"API {api} meets minimum {req.min_api_level}", data)
        return CheckResult(cid, title, FAIL, required, f"API {api} is below minimum {req.min_api_level}", data)

    return _guarded(cid, title, required, redactor, run)


# --- abi -------------------------------------------------------------------


def check_abi(client: AdbClient, req: Requirements, redactor: Redactor) -> CheckResult:
    cid, title, required = "abi", "CPU ABI", True

    def run() -> CheckResult:
        primary = _clean_value(client.getprop("ro.product.cpu.abi"), redactor)
        abilist = _clean_value(client.getprop("ro.product.cpu.abilist"), redactor)
        device_abis = [a for a in ([primary] + abilist.split(",")) if a]
        allowed = set(req.allowed_abis)
        matched = [a for a in device_abis if a in allowed]
        data = {
            "primary_abi": primary,
            "device_abis": device_abis,
            "allowed_abis": list(req.allowed_abis),
        }
        if not device_abis:
            return CheckResult(cid, title, ERROR, required, "device reported no ABI", data)
        if matched:
            return CheckResult(cid, title, PASS, required, f"device ABI {matched[0]} is supported", data)
        return CheckResult(cid, title, FAIL, required, f"device ABI {primary} is not in the allowed set", data)

    return _guarded(cid, title, required, redactor, run)


# --- storage ---------------------------------------------------------------


def _parse_df_available_kb(stdout: str) -> Optional[int]:
    """Return available kilobytes from ``df -k`` output, or ``None``.

    Robust to a wrapped filesystem column: locates the ``Use%`` token and reads
    the Available column immediately before it.
    """
    lines = [ln for ln in stdout.splitlines() if ln.strip()]
    for line in reversed(lines):
        if line.lower().startswith("filesystem"):
            continue
        tokens = line.split()
        for i, tok in enumerate(tokens):
            if _USE_PCT_RE.match(tok) and i >= 1:
                avail = tokens[i - 1]
                if _INT_RE.match(avail):
                    return int(avail)
    return None


def check_storage(client: AdbClient, req: Requirements, redactor: Redactor) -> CheckResult:
    cid, title, required = "storage", "Free storage on /data", True

    def run() -> CheckResult:
        result = client.shell("df", "-k", "/data")
        avail_kb = _parse_df_available_kb(result.stdout)
        if avail_kb is None:
            return CheckResult(
                cid, title, ERROR, required,
                "could not parse free storage from df output",
                {"min_free_bytes": req.min_free_bytes},
            )
        free_bytes = avail_kb * 1024
        data = {"free_bytes": free_bytes, "min_free_bytes": req.min_free_bytes}
        if free_bytes >= req.min_free_bytes:
            return CheckResult(cid, title, PASS, required, "sufficient free storage on /data", data)
        return CheckResult(cid, title, FAIL, required, "insufficient free storage on /data", data)

    return _guarded(cid, title, required, redactor, run)


# --- package state (informational) -----------------------------------------


def check_package(client: AdbClient, req: Requirements, redactor: Redactor) -> CheckResult:
    cid, title, required = "package_state", "Target package install state", False

    def run() -> CheckResult:
        result = client.shell("pm", "path", req.package)
        installed = result.returncode == 0 and "package:" in result.stdout
        data = {"package": req.package, "installed": installed}
        if installed:
            # Non-blocking: delivery will replace an existing install.
            return CheckResult(cid, title, WARN, required, "package is already installed", data)
        return CheckResult(cid, title, PASS, required, "package is not yet installed", data)

    return _guarded(cid, title, required, redactor, run)


# --- launcher state (informational) ----------------------------------------


def _parse_launcher_package(stdout: str) -> Optional[str]:
    for line in reversed(stdout.splitlines()):
        line = line.strip()
        if "/" in line:
            component = line.split()[-1] if line.split() else line
            pkg = component.split("/", 1)[0]
            if pkg:
                return pkg
    return None


def check_launcher(client: AdbClient, req: Requirements, redactor: Redactor) -> CheckResult:
    cid, title, required = "launcher_state", "Default HOME launcher", False

    def run() -> CheckResult:
        result = client.shell(
            "cmd", "package", "resolve-activity", "--brief",
            "-c", "android.intent.category.HOME",
            "-a", "android.intent.action.MAIN",
        )
        pkg = _parse_launcher_package(result.stdout)
        if not pkg:
            return CheckResult(cid, title, WARN, required, "could not resolve the default launcher", {"launcher_package": None})
        pkg = redactor.scrub(pkg)
        return CheckResult(cid, title, PASS, required, f"current launcher is {pkg}", {"launcher_package": pkg})

    return _guarded(cid, title, required, redactor, run)


# --- clock / timezone (informational) --------------------------------------


def check_clock(client: AdbClient, req: Requirements, redactor: Redactor) -> CheckResult:
    cid, title, required = "clock_timezone", "Device clock and timezone", False

    def run() -> CheckResult:
        epoch = _parse_int(client.shell("date", "+%s").stdout)
        tz = _clean_value(client.getprop("persist.sys.timezone"), redactor)
        data = {"device_epoch": epoch, "timezone": tz or None, "min_clock_epoch": req.min_clock_epoch}
        if epoch is None:
            return CheckResult(cid, title, WARN, required, "device clock is unreadable", data)
        if epoch < req.min_clock_epoch:
            return CheckResult(cid, title, WARN, required, "device clock looks unset", data)
        return CheckResult(cid, title, PASS, required, "device clock and timezone are set", data)

    return _guarded(cid, title, required, redactor, run)


# Ordered check pipeline. ``connection`` first; the rest depend on it.
ALL_CHECKS = [
    check_connection,
    check_api_level,
    check_abi,
    check_storage,
    check_package,
    check_launcher,
    check_clock,
]


def skipped(check_fn, redactor: Redactor) -> CheckResult:
    """A placeholder result for a check not evaluated because the device is not ready."""
    probe = check_fn.__name__.replace("check_", "")
    # Reconstruct id/title/required by invoking with a sentinel is overkill;
    # map from the known pipeline metadata instead.
    meta = _CHECK_META[check_fn]
    return CheckResult(
        meta["id"], meta["title"], SKIP, meta["required"],
        "not evaluated: device connection is not ready", {},
    )


# Static metadata mirror so a skipped check can be rendered without running it.
_CHECK_META = {
    check_connection: {"id": "connection", "title": "ADB connection state", "required": True},
    check_api_level: {"id": "api_level", "title": "Android API level", "required": True},
    check_abi: {"id": "abi", "title": "CPU ABI", "required": True},
    check_storage: {"id": "storage", "title": "Free storage on /data", "required": True},
    check_package: {"id": "package_state", "title": "Target package install state", "required": False},
    check_launcher: {"id": "launcher_state", "title": "Default HOME launcher", "required": False},
    check_clock: {"id": "clock_timezone", "title": "Device clock and timezone", "required": False},
}
