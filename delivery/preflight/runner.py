"""The real ADB runner used by the CLI.

This is the only place a process is actually spawned, and it is used solely when
the CLI runs against a live device. Tests never touch it -- they inject a fake
runner into :class:`~delivery.preflight.adb.AdbClient` directly. Even here the
runner only *executes* an argv the read-only guard has already approved; it adds
no commands of its own and downloads nothing.
"""

from __future__ import annotations

import subprocess
from typing import Sequence

from .adb import AdbResult
from .errors import AdbTimeout, PreflightError


class SubprocessRunner:
    """Runner that shells out to a real ``adb`` binary with a hard timeout."""

    def __call__(self, argv: Sequence[str], timeout: float) -> AdbResult:
        try:
            proc = subprocess.run(
                list(argv),
                capture_output=True,
                text=True,
                timeout=timeout,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise AdbTimeout(f"adb timed out after {timeout:g}s") from exc
        except FileNotFoundError as exc:
            raise PreflightError(
                "adb binary not found; set --adb or ADB_PREFLIGHT_ADB"
            ) from exc
        return AdbResult(
            returncode=proc.returncode,
            stdout=proc.stdout or "",
            stderr=proc.stderr or "",
        )
