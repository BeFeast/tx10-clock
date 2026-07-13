"""A fake ADB runner for host-only tests.

``FakeAdb`` is a drop-in ``Runner`` for :class:`~delivery.preflight.adb.AdbClient`.
It models a device from a small :class:`FakeDevice` description and answers the
exact read-only queries the checks issue -- no real ``adb``, no device, no
network. Timeouts and raw/malicious output can be injected to drive the failure
and redaction paths.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

from delivery.preflight.adb import AdbResult
from delivery.preflight.errors import AdbTimeout


def default_props() -> Dict[str, str]:
    return {
        "ro.build.version.sdk": "30",
        "ro.build.version.release": "11",
        "ro.product.cpu.abi": "arm64-v8a",
        "ro.product.cpu.abilist": "arm64-v8a,armeabi-v7a,armeabi",
        "persist.sys.timezone": "Europe/Amsterdam",
    }


@dataclass
class FakeDevice:
    """Everything the fake needs to answer shell queries."""

    props: Dict[str, str] = field(default_factory=default_props)
    df_available_kb: int = 512 * 1024  # 512 MiB free
    package_installed: bool = False
    launcher: str = "com.android.tv.launcher/.MainActivity"
    date_epoch: int = 1_700_000_000  # 2023-11-14, comfortably "set"


@dataclass
class FakeAdb:
    """A callable ``Runner`` that emulates read-only adb responses.

    Parameters:
        device_lines: ``(serial, state)`` pairs emitted by ``adb devices -l``.
        device: the :class:`FakeDevice` answering shell/getprop queries.
        timeouts: substrings; a matching normalized command raises ``AdbTimeout``.
        overrides: exact normalized-signature -> ``(rc, stdout, stderr)`` to
            inject raw or malicious output.
    """

    device_lines: List[Tuple[str, str]] = field(default_factory=lambda: [("FAKESERIAL123", "device")])
    device: FakeDevice = field(default_factory=FakeDevice)
    timeouts: List[str] = field(default_factory=list)
    overrides: Dict[Tuple[str, ...], Tuple[int, str, str]] = field(default_factory=dict)
    calls: List[List[str]] = field(default_factory=list)

    # -- Runner protocol ----------------------------------------------------

    def __call__(self, argv: Sequence[str], timeout: float) -> AdbResult:
        argv = list(argv)
        self.calls.append(argv)
        sig = self._signature(argv)

        joined = " ".join(sig)
        for needle in self.timeouts:
            if needle in joined:
                raise AdbTimeout(f"fake adb timed out on {needle!r} after {timeout:g}s")

        if sig in self.overrides:
            rc, out, err = self.overrides[sig]
            return AdbResult(rc, out, err)

        if not sig:
            return AdbResult(1, "", "usage: adb ...")
        if sig[0] == "devices":
            return self._devices()
        if sig[0] == "shell":
            return self._shell(list(sig[1:]))
        if sig[0] == "get-state":
            state = self.device_lines[0][1] if self.device_lines else "unknown"
            return AdbResult(0, state + "\n", "")
        return AdbResult(1, "", f"fake adb: unhandled {sig[0]!r}")

    # -- helpers ------------------------------------------------------------

    @staticmethod
    def _signature(argv: Sequence[str]) -> Tuple[str, ...]:
        """Normalize argv to the adb subcommand, dropping the binary and ``-s SER``."""
        rest = list(argv[1:])  # drop the adb binary path
        if len(rest) >= 2 and rest[0] == "-s":
            rest = rest[2:]
        return tuple(rest)

    def _devices(self) -> AdbResult:
        lines = ["List of devices attached"]
        for serial, state in self.device_lines:
            lines.append(f"{serial}\t{state} product:tx10 model:TX10Pro device:tx10 transport_id:1")
        return AdbResult(0, "\n".join(lines) + "\n", "")

    def _shell(self, args: List[str]) -> AdbResult:
        if not args:
            return AdbResult(1, "", "")
        tool = args[0]
        if tool == "getprop":
            name = args[1] if len(args) > 1 else ""
            return AdbResult(0, self.device.props.get(name, "") + "\n", "")
        if tool == "df":
            return AdbResult(0, self._df_output(), "")
        if tool == "date":
            return AdbResult(0, f"{self.device.date_epoch}\n", "")
        if tool == "pm" and len(args) >= 2 and args[1] == "path":
            if self.device.package_installed:
                return AdbResult(0, "package:/data/app/~~abc==/base.apk\n", "")
            return AdbResult(1, "", "")
        if tool == "cmd" and len(args) >= 3 and args[1] == "package" and args[2] == "resolve-activity":
            return AdbResult(0, f"{self.device.launcher}\n", "")
        return AdbResult(1, "", f"fake adb shell: unhandled {tool!r}")

    def _df_output(self) -> str:
        avail = self.device.df_available_kb
        used = 1_000_000
        total = used + avail
        return (
            "Filesystem     1K-blocks     Used Available Use% Mounted on\n"
            f"/dev/block/dm-0 {total:>9} {used:>8} {avail:>9}  12% /data\n"
        )


def make_client(fake: Optional[FakeAdb] = None, *, target: Optional[str] = None, timeout: float = 5.0):
    """Construct an :class:`AdbClient` wired to a fake runner."""
    from delivery.preflight.adb import AdbClient

    fake = fake or FakeAdb()
    return AdbClient(fake, adb_path="adb", target=target, default_timeout=timeout), fake
