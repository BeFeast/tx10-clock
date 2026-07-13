"""Delivery preconditions the preflight checks a device against.

Defaults track the TX10 Clock app contract (``app/build.gradle``): ``minSdk
29`` and an APK with no native code (``abiFilters.clear()``), so any known
Android ABI is acceptable by default. All values are overridable at runtime so
tests can force each failure path (unsupported API, unsupported ABI, insufficient
storage) without touching the defaults.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import List, Mapping

# The app declares minSdk 29; a device below that cannot run the build.
DEFAULT_MIN_API_LEVEL = 29

# The release APK carries no native libraries, so it is ABI-independent. Accept
# every ABI Android currently ships; a stricter policy can be injected.
DEFAULT_ALLOWED_ABIS: List[str] = ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"]

# Headroom for the (small, DEX-only) APK plus its installed footprint.
DEFAULT_MIN_FREE_MB = 64

DEFAULT_PACKAGE = "com.befeast.tx10clock"

# Earliest epoch (2021-01-01 UTC) considered a "set" device clock. Below this
# the device time looks unconfigured, which is worth a non-blocking warning.
DEFAULT_MIN_CLOCK_EPOCH = 1609459200


@dataclass(frozen=True)
class Requirements:
    """Immutable set of delivery preconditions."""

    min_api_level: int = DEFAULT_MIN_API_LEVEL
    allowed_abis: List[str] = field(default_factory=lambda: list(DEFAULT_ALLOWED_ABIS))
    min_free_bytes: int = DEFAULT_MIN_FREE_MB * 1024 * 1024
    package: str = DEFAULT_PACKAGE
    min_clock_epoch: int = DEFAULT_MIN_CLOCK_EPOCH

    def to_dict(self) -> dict:
        return {
            "min_api_level": self.min_api_level,
            "allowed_abis": list(self.allowed_abis),
            "min_free_bytes": self.min_free_bytes,
            "package": self.package,
            "min_clock_epoch": self.min_clock_epoch,
        }

    @classmethod
    def from_mapping(cls, data: Mapping) -> "Requirements":
        """Build from a plain mapping (e.g. a parsed ``--requirements`` file).

        Unknown keys are ignored; ``min_free_mb`` is accepted as a convenience
        and converted to bytes.
        """
        kwargs: dict = {}
        if "min_api_level" in data:
            kwargs["min_api_level"] = int(data["min_api_level"])
        if "allowed_abis" in data:
            kwargs["allowed_abis"] = [str(a) for a in data["allowed_abis"]]
        if "min_free_bytes" in data:
            kwargs["min_free_bytes"] = int(data["min_free_bytes"])
        elif "min_free_mb" in data:
            kwargs["min_free_bytes"] = int(data["min_free_mb"]) * 1024 * 1024
        if "package" in data:
            kwargs["package"] = str(data["package"])
        if "min_clock_epoch" in data:
            kwargs["min_clock_epoch"] = int(data["min_clock_epoch"])
        return cls(**kwargs)

    @classmethod
    def from_json(cls, text: str) -> "Requirements":
        return cls.from_mapping(json.loads(text))
