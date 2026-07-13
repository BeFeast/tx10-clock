"""Target fingerprinting and output redaction.

Two independent guarantees live here:

1. **Non-reversible fingerprint.** The live serial / endpoint is mapped through
   a keyed one-way function (HMAC-SHA256, truncated) so a report can refer to
   "the target" without disclosing it. The mapping is deterministic given the
   same salt, so identical inputs produce byte-identical reports.

2. **Redaction.** Any free-text string that could carry the raw target (an
   error message, a line of device output echoed back) is scrubbed: the exact
   target, host:port endpoints, and bare IPv4 addresses are replaced, control
   characters are stripped, and length is capped. Device output is untrusted,
   so redaction runs on every device-derived string before it enters a report.
"""

from __future__ import annotations

import hashlib
import hmac
import re
from typing import Optional

# Public default salt. Overriding it via the environment (see the CLI) with a
# private value strengthens non-reversibility for a given deployment; the
# default keeps CI output reproducible without configuration.
DEFAULT_FINGERPRINT_SALT = "tx10-clock/adb-preflight/v1"

REDACTED = "[REDACTED]"

# Cap for any device-derived string that reaches a report. Bounds report size
# and blunts resource-exhaustion via maliciously huge ADB output.
_MAX_FIELD_LEN = 200

_CONTROL_CHARS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f]")
_IPV4_PORT = re.compile(r"\b\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?\b")


def fingerprint(target: str, *, salt: str = DEFAULT_FINGERPRINT_SALT) -> str:
    """Return a non-reversible, deterministic fingerprint of ``target``.

    Uses HMAC-SHA256 keyed by ``salt`` and truncates to 16 hex chars. The raw
    target cannot be recovered from the digest.
    """
    digest = hmac.new(salt.encode("utf-8"), target.encode("utf-8"), hashlib.sha256)
    return digest.hexdigest()[:16]


def classify_target(target: str) -> str:
    """Coarsely classify a target as a network ``endpoint`` or a USB ``serial``.

    Returns a non-sensitive label only; the value itself is never revealed.
    """
    if _IPV4_PORT.match(target.strip()):
        return "endpoint"
    if ":" in target and target.rsplit(":", 1)[-1].isdigit():
        return "endpoint"
    return "serial"


class Redactor:
    """Scrubs the live target (and generic endpoints) out of free text."""

    def __init__(self, target: Optional[str]) -> None:
        self._target = target.strip() if target else None

    def scrub(self, text: str) -> str:
        """Redact and sanitize a possibly-untrusted string.

        Removes the exact target and any host:port / IPv4 tokens, strips control
        characters, collapses whitespace, and caps length. Safe to embed in the
        report or print to a log.
        """
        if text is None:
            return ""
        out = str(text)
        if self._target:
            out = out.replace(self._target, REDACTED)
            # Also redact a host-only form of a host:port target.
            if ":" in self._target:
                host = self._target.rsplit(":", 1)[0]
                if host:
                    out = out.replace(host, REDACTED)
        out = _IPV4_PORT.sub(REDACTED, out)
        out = _CONTROL_CHARS.sub(" ", out)
        out = re.sub(r"\s+", " ", out).strip()
        if len(out) > _MAX_FIELD_LEN:
            out = out[:_MAX_FIELD_LEN] + "…"
        return out
