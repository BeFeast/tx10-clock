#!/usr/bin/env python3
"""adb-preflight: safe, read-only ADB delivery preflight for TX10 Clock.

Runs a set of read-only ADB queries against a device and emits a deterministic,
machine-readable JSON report on stdout. Exits 0 only when every required
precondition is met; any unmet prerequisite yields a nonzero exit.

The tool performs no device mutation (no install / uninstall / push / reboot /
grant / start / settings write), downloads no Android SDK, and accepts no
license. The live serial / endpoint is read only from the runtime environment
and never echoed -- reports carry a non-reversible fingerprint instead.

Target resolution (first that is set wins):
    --target  |  $ADB_PREFLIGHT_TARGET  |  $ANDROID_SERIAL
When no target is given, adb must see exactly one device.

Examples:
    # Report against the single connected device, JSON to stdout:
    python3 tools/adb-preflight/adb_preflight.py

    # Target a specific device supplied by the private environment:
    ADB_PREFLIGHT_TARGET="$SERIAL" python3 tools/adb-preflight/adb_preflight.py --human
"""

from __future__ import annotations

import argparse
import json
import os
import sys

# Make the repo-root packages importable when run as a standalone script.
_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from delivery.preflight.adb import AdbClient  # noqa: E402
from delivery.preflight.errors import PreflightError  # noqa: E402
from delivery.preflight.preflight import run_preflight  # noqa: E402
from delivery.preflight.redaction import DEFAULT_FINGERPRINT_SALT, Redactor  # noqa: E402
from delivery.preflight.report import EXIT_USAGE  # noqa: E402
from delivery.preflight.requirements import Requirements  # noqa: E402
from delivery.preflight.runner import SubprocessRunner  # noqa: E402


def _resolve_target(explicit) -> "str | None":
    for value in (explicit, os.environ.get("ADB_PREFLIGHT_TARGET"), os.environ.get("ANDROID_SERIAL")):
        if value:
            return value
    return None


def _build_requirements(args) -> Requirements:
    merged: dict = {}
    if args.requirements:
        with open(args.requirements, "r", encoding="utf-8") as fh:
            merged.update(json.load(fh))
    if args.min_api is not None:
        merged["min_api_level"] = args.min_api
    if args.abi:
        merged["allowed_abis"] = args.abi
    if args.min_free_mb is not None:
        merged["min_free_mb"] = args.min_free_mb
    if args.package:
        merged["package"] = args.package
    return Requirements.from_mapping(merged)


def _parse_args(argv):
    parser = argparse.ArgumentParser(
        prog="adb-preflight",
        description="Read-only ADB delivery preflight (no device mutation, no SDK download).",
    )
    parser.add_argument("--target", default=None,
                        help="Device serial or host:port. Defaults to $ADB_PREFLIGHT_TARGET / $ANDROID_SERIAL. Never echoed.")
    parser.add_argument("--adb", default=os.environ.get("ADB_PREFLIGHT_ADB", "adb"),
                        help="Path to the adb binary (default: 'adb' on PATH).")
    parser.add_argument("--requirements", default=None,
                        help="Path to a JSON file of requirement overrides.")
    parser.add_argument("--min-api", type=int, default=None, help="Minimum acceptable Android API level.")
    parser.add_argument("--abi", action="append", default=None, help="Allowed device ABI (repeatable).")
    parser.add_argument("--min-free-mb", type=int, default=None, help="Minimum free MB required on /data.")
    parser.add_argument("--package", default=None, help="Target application id to probe.")
    parser.add_argument("--timeout", type=float, default=10.0, help="Per-command adb timeout in seconds.")
    parser.add_argument("--fingerprint-salt",
                        default=os.environ.get("ADB_PREFLIGHT_FINGERPRINT_SALT", DEFAULT_FINGERPRINT_SALT),
                        help="Salt for the non-reversible target fingerprint.")
    parser.add_argument("--human", action="store_true",
                        help="Also print a redacted human-readable summary to stderr.")
    return parser.parse_args(argv)


def _print_human_summary(report, stream) -> None:
    print(f"adb-preflight: ready={report.ready} target={report.target_kind}:{report.target_fingerprint}", file=stream)
    for check in report.checks:
        print(f"  [{check.status:>5}] {check.id}: {check.summary}", file=stream)


def main(argv=None) -> int:
    args = _parse_args(sys.argv[1:] if argv is None else argv)

    target = _resolve_target(args.target)
    redactor = Redactor(target)

    try:
        requirements = _build_requirements(args)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        # Do not leak the target; requirements errors never contain it, but scrub anyway.
        print(f"adb-preflight: invalid requirements: {redactor.scrub(str(exc))}", file=sys.stderr)
        return EXIT_USAGE

    client = AdbClient(SubprocessRunner(), adb_path=args.adb, target=target, default_timeout=args.timeout)

    try:
        report = run_preflight(client, requirements, salt=args.fingerprint_salt, redactor=redactor)
    except PreflightError as exc:
        print(f"adb-preflight: {redactor.scrub(str(exc))}", file=sys.stderr)
        return EXIT_USAGE

    # Deterministic JSON to stdout; the exit code carries the verdict.
    print(report.to_json())
    if args.human:
        _print_human_summary(report, sys.stderr)
    return report.exit_code


if __name__ == "__main__":
    raise SystemExit(main())
