"""Host-side, read-only ADB delivery preflight for TX10 Clock.

This package proves that the read-only ADB preconditions and redaction rules
for delivery automation are correct *before* any approval-gated install is
attempted. It performs no device mutation, downloads no Android SDK, and
accepts no legal license.

Design invariants (see ``adb.py`` and ``redaction.py``):

* Every ADB invocation is validated against a read-only allowlist *by
  construction*: install / uninstall / push / reboot / grant / start /
  settings-write and any command outside the allowlist are rejected before a
  process is ever spawned.
* The live serial / network endpoint is supplied only at runtime; it is never
  committed, never logged, and never echoed. Reports carry a non-reversible
  fingerprint instead.
* Output is deterministic, machine-readable JSON. The process exits nonzero if
  any required precondition is unmet.
"""

from .version import SCHEMA_VERSION, TOOL_NAME
from .errors import PreflightError, ReadOnlyViolation, AdbTimeout
from .requirements import Requirements
from .preflight import run_preflight
from .report import Report, CheckResult

__all__ = [
    "SCHEMA_VERSION",
    "TOOL_NAME",
    "PreflightError",
    "ReadOnlyViolation",
    "AdbTimeout",
    "Requirements",
    "run_preflight",
    "Report",
    "CheckResult",
]
