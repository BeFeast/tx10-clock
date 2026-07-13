"""Exception types for the preflight tool."""


class PreflightError(Exception):
    """Base class for all preflight errors."""


class ReadOnlyViolation(PreflightError):
    """Raised when an ADB invocation is not provably read-only.

    This is the enforcement point for the "dry-run / read-only by
    construction" contract: the offending argv never reaches a subprocess.
    """


class AdbTimeout(PreflightError):
    """Raised when an ADB invocation exceeds its deadline."""
