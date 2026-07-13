"""The machine-readable preflight report.

The report is deterministic: given the same requirements, target, salt, and ADB
responses it serializes to byte-identical JSON. It carries no wall-clock
timestamp for exactly this reason -- readiness is a pure function of its inputs.
Readiness is defined as "every required check passed"; the process exit code is
derived from it.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from .version import SCHEMA_VERSION, TOOL_NAME

# Check statuses.
PASS = "pass"     # required condition met
FAIL = "fail"     # required condition unmet -> not ready
WARN = "warn"     # non-blocking observation
SKIP = "skip"     # not evaluated (a prerequisite check did not pass)
ERROR = "error"   # the check itself could not complete (timeout, bad output)

# Exit codes.
EXIT_READY = 0
EXIT_NOT_READY = 1
EXIT_USAGE = 2


@dataclass
class CheckResult:
    """Result of a single preflight check."""

    id: str
    title: str
    status: str
    required: bool
    summary: str
    data: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "title": self.title,
            "status": self.status,
            "required": self.required,
            "summary": self.summary,
            "data": self.data,
        }


@dataclass
class Report:
    """The full preflight result."""

    target_fingerprint: str
    target_kind: str
    requirements: Dict[str, Any]
    checks: List[CheckResult] = field(default_factory=list)

    @property
    def failures(self) -> List[str]:
        """Ids of required checks that did not pass, in evaluation order."""
        return [c.id for c in self.checks if c.required and c.status != PASS]

    @property
    def ready(self) -> bool:
        """True iff every required check passed."""
        return not self.failures

    @property
    def exit_code(self) -> int:
        return EXIT_READY if self.ready else EXIT_NOT_READY

    def to_dict(self) -> Dict[str, Any]:
        return {
            "schema": SCHEMA_VERSION,
            "tool": TOOL_NAME,
            "ready": self.ready,
            "target": {
                "fingerprint": self.target_fingerprint,
                "kind": self.target_kind,
            },
            "requirements": self.requirements,
            "checks": [c.to_dict() for c in self.checks],
            "failures": self.failures,
        }

    def to_json(self, *, indent: Optional[int] = 2) -> str:
        """Serialize deterministically (sorted keys, no timestamp)."""
        return json.dumps(self.to_dict(), sort_keys=True, indent=indent, ensure_ascii=True)
