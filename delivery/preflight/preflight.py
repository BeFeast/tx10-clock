"""Orchestration: run the check pipeline and assemble the report.

``connection`` runs first. If the device is not online and authorized, the
remaining checks are recorded as ``skip`` (not run) so the report is complete
and deterministic without issuing further ADB calls against an unusable device.
"""

from __future__ import annotations

from typing import Optional

from .adb import AdbClient
from .checks import ALL_CHECKS, check_connection, skipped
from .redaction import Redactor, classify_target, fingerprint, DEFAULT_FINGERPRINT_SALT
from .report import PASS, Report
from .requirements import Requirements


def run_preflight(
    client: AdbClient,
    requirements: Requirements,
    *,
    salt: str = DEFAULT_FINGERPRINT_SALT,
    redactor: Optional[Redactor] = None,
) -> Report:
    """Execute all checks against ``client`` and return a :class:`Report`.

    The report carries only a non-reversible fingerprint of the target; the raw
    target (``client.target``) is never placed in the report.
    """
    target = client.target
    if redactor is None:
        redactor = Redactor(target)

    if target is not None:
        target_fp = fingerprint(target, salt=salt)
        target_kind = classify_target(target)
    else:
        # No explicit target: adb selects the single connected device. There is
        # nothing to fingerprint or leak.
        target_fp = "unspecified"
        target_kind = "unspecified"

    checks = []
    connection = check_connection(client, requirements, redactor)
    checks.append(connection)

    if connection.status == PASS:
        for fn in ALL_CHECKS[1:]:
            checks.append(fn(client, requirements, redactor))
    else:
        for fn in ALL_CHECKS[1:]:
            checks.append(skipped(fn, redactor))

    return Report(
        target_fingerprint=target_fp,
        target_kind=target_kind,
        requirements=requirements.to_dict(),
        checks=checks,
    )
