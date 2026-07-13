"""Read-only ADB client.

The client is the single choke point through which every ADB call flows. It is
*read-only by construction*: :func:`assert_readonly` validates the argv against
an allowlist before any process is spawned, so a mutating command (install,
uninstall, push, reboot, grant, start, ``settings put``, ``date -s`` ...) is
rejected up front rather than being trusted not to run.

The client never spawns a process itself; it delegates to an injected
``runner`` callable. Tests inject a fake runner (see ``tests/fake_adb.py``);
the CLI injects :class:`~delivery.preflight.runner.SubprocessRunner`. Neither
the client nor the guard ever downloads an SDK or accepts a license.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Callable, List, Optional, Sequence

from .errors import ReadOnlyViolation


@dataclass(frozen=True)
class AdbResult:
    """Outcome of a single ADB invocation."""

    returncode: int
    stdout: str
    stderr: str


# A runner takes the full argv (already including the adb binary and any ``-s``
# selector) plus a timeout in seconds and returns an :class:`AdbResult`. It may
# raise :class:`~delivery.preflight.errors.AdbTimeout`.
Runner = Callable[[Sequence[str], float], AdbResult]


# --- Read-only allowlist ---------------------------------------------------
#
# Only these top-level adb subcommands may run. Everything else -- install,
# install-multiple, uninstall, push, pull, sync, reboot, root, remount,
# sideload, backup, restore, forward, reverse, tcpip, connect, disconnect,
# emu, ... -- is rejected because it is absent from this set.
_READONLY_SUBCOMMANDS = frozenset({"devices", "get-state", "version", "shell"})

# For ``adb shell``, only these device tools may be invoked. Each is a pure
# query. Notably absent: am, svc, input, monkey, pm install/uninstall/grant,
# settings put, content, reboot, stop, start, kill, rm, mv, dd, mkdir, ...
_READONLY_SHELL_TOOLS = frozenset(
    {"getprop", "df", "date", "uptime", "pm", "cmd", "settings", "dumpsys"}
)

# Multi-verb device tools carry their own mutation surface, so the first
# non-flag argument (the "verb") is itself allowlisted.
_SHELL_TOOL_VERBS = {
    # `pm path`/`pm list` are read-only; `pm install`, `pm grant`, `pm clear`,
    # `pm set-*`, `pm disable`, ... are not present and thus rejected.
    "pm": frozenset({"path", "list", "dump"}),
    # `settings get`/`settings list` read; `settings put`/`delete`/`reset`
    # write and are rejected.
    "settings": frozenset({"get", "list"}),
    # `cmd` can address any system service; restrict it to read verbs of the
    # package service only (used for launcher resolution).
    "cmd": frozenset({"package"}),
}

# For ``cmd package`` the *service verb* is allowlisted a second level down.
_CMD_PACKAGE_VERBS = frozenset({"resolve-activity", "list", "path", "dump"})

# A device-shell argument may only contain these characters. This rejects every
# shell metacharacter (; & | > < ` $ ( ) newline quotes whitespace \\ * ? etc.)
# so an allowlisted verb cannot be chained into a mutation or a redirect.
_SAFE_SHELL_TOKEN = re.compile(r"^[A-Za-z0-9._+\-/%:@,=]+$")


def _reject(reason: str, argv: Sequence[str]) -> "ReadOnlyViolation":
    # The argv here is tool-constructed (never raw device output), so it is safe
    # to echo for diagnostics. It never contains the live target selector, which
    # the client prepends only after this guard has passed.
    return ReadOnlyViolation(f"{reason}: {' '.join(argv)!r}")


def assert_readonly(argv: Sequence[str]) -> None:
    """Validate that ``argv`` (an adb subcommand, without the ``-s`` selector or
    the adb binary) is provably read-only, or raise :class:`ReadOnlyViolation`.

    ``argv[0]`` is the adb subcommand; for ``shell`` the remaining tokens are
    the device command line.
    """
    if not argv:
        raise _reject("empty adb invocation", argv)

    sub = argv[0]
    if sub.startswith("-"):
        # A leading global flag (e.g. an injected selector or transport switch)
        # must never appear here; the client owns selector injection.
        raise _reject("unexpected leading flag", argv)
    if sub not in _READONLY_SUBCOMMANDS:
        raise _reject(f"subcommand {sub!r} is not on the read-only allowlist", argv)

    if sub != "shell":
        # devices / get-state / version take only benign flags; still forbid
        # metacharacters defensively.
        for tok in argv[1:]:
            if not _SAFE_SHELL_TOKEN.match(tok):
                raise _reject(f"unsafe token {tok!r}", argv)
        return

    _assert_readonly_shell(argv)


def _assert_readonly_shell(argv: Sequence[str]) -> None:
    shell_argv = list(argv[1:])
    if not shell_argv:
        raise _reject("empty shell command", argv)

    # Every device token must be metacharacter-free so an allowlisted verb
    # cannot be chained, quoted, redirected, or expanded into a mutation.
    for tok in shell_argv:
        if not _SAFE_SHELL_TOKEN.match(tok):
            raise _reject(f"unsafe shell token {tok!r}", argv)

    tool = shell_argv[0]
    if tool not in _READONLY_SHELL_TOOLS:
        raise _reject(f"shell tool {tool!r} is not on the read-only allowlist", argv)

    rest = shell_argv[1:]

    if tool == "date":
        # `date` reads the clock, but `date -s`/`date --set`/`date MMDDhhmm`
        # writes it. Permit only format specifiers (a lone `+FORMAT`).
        for tok in rest:
            if not tok.startswith("+"):
                raise _reject(f"date may only read (rejected {tok!r})", argv)
        return

    if tool in _SHELL_TOOL_VERBS:
        verb = _first_non_flag(rest)
        if verb is None or verb not in _SHELL_TOOL_VERBS[tool]:
            raise _reject(f"{tool} verb {verb!r} is not a read-only verb", argv)
        if tool == "cmd":
            # cmd package <service-verb>
            after = rest[rest.index(verb) + 1:]
            svc_verb = _first_non_flag(after)
            if svc_verb is None or svc_verb not in _CMD_PACKAGE_VERBS:
                raise _reject(
                    f"cmd package verb {svc_verb!r} is not a read-only verb", argv
                )
        return

    # getprop, df, uptime, dumpsys: read-only tools whose flags are already
    # constrained by the safe-token check above.
    return


def _first_non_flag(tokens: Sequence[str]) -> Optional[str]:
    for tok in tokens:
        if not tok.startswith("-"):
            return tok
    return None


class AdbClient:
    """A read-only view onto a device reachable via ``adb``.

    The client prepends the ``-s <target>`` selector (when a target is given)
    *after* the read-only guard has passed, so the raw target is never part of
    the validated/echoed subcommand.
    """

    def __init__(
        self,
        runner: Runner,
        *,
        adb_path: str = "adb",
        target: Optional[str] = None,
        default_timeout: float = 10.0,
    ) -> None:
        self._runner = runner
        self._adb_path = adb_path
        self._target = target
        self._default_timeout = default_timeout

    @property
    def target(self) -> Optional[str]:
        return self._target

    def run(self, argv: Sequence[str], *, timeout: Optional[float] = None) -> AdbResult:
        """Run a single read-only adb subcommand.

        ``argv`` is the subcommand and its arguments *without* the adb binary or
        the ``-s`` selector, e.g. ``["shell", "getprop", "ro.build.version.sdk"]``.
        Raises :class:`ReadOnlyViolation` before spawning if the call is not
        provably read-only.
        """
        argv = list(argv)
        assert_readonly(argv)  # by construction: reject mutations before spawn
        full: List[str] = [self._adb_path]
        if self._target is not None:
            full += ["-s", self._target]
        full += argv
        return self._runner(full, timeout if timeout is not None else self._default_timeout)

    # -- Convenience read-only accessors ------------------------------------

    def devices(self, *, timeout: Optional[float] = None) -> AdbResult:
        return self.run(["devices", "-l"], timeout=timeout)

    def getprop(self, name: str, *, timeout: Optional[float] = None) -> AdbResult:
        return self.run(["shell", "getprop", name], timeout=timeout)

    def shell(self, *args: str, timeout: Optional[float] = None) -> AdbResult:
        return self.run(["shell", *args], timeout=timeout)
