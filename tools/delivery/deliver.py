#!/usr/bin/env python3
"""Approval-gated, exact-SHA TX10 release delivery.

The live path deliberately has no command-line knobs. Every private input is
resolved from the approved runtime environment, while the release identity is
pinned by the committed ``delivery/release-lock.json``. The implementation is
standard-library only and emits one public-safe JSON receipt: it never prints
the target serial/endpoint, local paths, command lines, command output, config
content, or rollback text.

Live environment (all paths and target values remain private):

  TX10_ADB_TARGET                 canonical device serial/endpoint (required)
  TX10_ADB                        adb executable (default: adb from PATH)
  TX10_DELIVERY_APPROVAL_FILE     Oleg approval assertion JSON (required)
  TX10_DELIVERY_CONFIG            app external config.json (required)
  TX10_DELIVERY_STATE_DIR         durable private claim/evidence root (required)
  TX10_GH                         gh executable (default: gh from PATH)
  TX10_AAPT2                      aapt2 executable (otherwise pinned SDK path)
  TX10_APKSIGNER                  apksigner executable (otherwise pinned SDK path)

Tests use TX10_DELIVERY_MODE=fixture with fake tools and local public release
fixtures. Fixture approval is a distinct mode and can never satisfy a live
claim.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.befeast.tx10clock"
ACTIVITY = PACKAGE + "/.MainActivity"
REPOSITORY = "BeFeast/tx10-clock"
RELEASE_TAG = "v0.1.0"
RELEASE_LOCK = ROOT / "delivery" / "release-lock.json"
RELEASE_EVIDENCE_NAME = "release-evidence.json"
CONFIG_DIR = "/sdcard/Android/data/com.befeast.tx10clock/files"
CONFIG_REMOTE = CONFIG_DIR + "/config.json"
STATUS_REMOTE = CONFIG_DIR + "/status.json"

SCHEMA_VERSION = "1.0.0"
RECEIPT_SCHEMA = "tx10-delivery-receipt/v1"
APPROVAL_RECEIPT_REF = "872"
LIVE_TIMEOUT_SECONDS = 60 * 60
LIVE_SOAK_SECONDS = 30 * 60
LIVE_SOAK_INTERVAL_SECONDS = 30
LIVE_BOOT_TIMEOUT_SECONDS = 180
COMMAND_OUTPUT_CAP = 1024 * 1024

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_USAGE = 2
EXIT_REPLAY = 3

SHA_RE = re.compile(r"^[0-9a-f]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")
TAG_RE = re.compile(r"^v[0-9]+\.[0-9]+\.[0-9]+$")
APK_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.apk$")
APP_ID_RE = re.compile(r"^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$")
SEMVER_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
CERT_RE = re.compile(r"^(?:[0-9A-F]{2}:){31}[0-9A-F]{2}$")
APPROVAL_ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{7,63}$")
TARGET_RE = re.compile(r"^[A-Za-z0-9._:\[\]-]{2,128}$")
COMPONENT_RE = re.compile(r"^[A-Za-z0-9._$]+/[A-Za-z0-9._$]+$")
REMOTE_APK_RE = re.compile(r"^/data/app/[A-Za-z0-9._=+~/-]{1,500}/base\.apk$")
PID_RE = re.compile(r"^[0-9]{1,10}$")
UTC_RE = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")


RELEASE_LOCK_SCHEMA: Dict[str, Any] = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "$id": (
        "https://github.com/BeFeast/tx10-clock/blob/main/"
        "delivery/schema/release-lock-v1.schema.json"
    ),
    "title": "tx10-clock-delivery-release-lock",
    "description": "Exact public release identity accepted by live delivery.",
    "type": "object",
    "additionalProperties": False,
    "required": [
        "schema_version", "repository", "release", "artifact", "package",
        "signing", "evidence",
    ],
    "properties": {
        "schema_version": {"type": "string", "const": SCHEMA_VERSION},
        "repository": {"type": "string", "const": REPOSITORY},
        "release": {
            "type": "object", "additionalProperties": False,
            "required": ["tag", "source_commit_sha", "tag_ref_sha"],
            "properties": {
                "tag": {"type": "string", "const": RELEASE_TAG},
                "source_commit_sha": {
                    "type": "string", "pattern": "^[0-9a-f]{40}$"},
                "tag_ref_sha": {
                    "type": "string", "pattern": "^[0-9a-f]{40}$"},
            },
        },
        "artifact": {
            "type": "object", "additionalProperties": False,
            "required": ["name", "sha256", "size_bytes"],
            "properties": {
                "name": {"type": "string", "pattern": APK_NAME_RE.pattern},
                "sha256": {"type": "string", "pattern": SHA_RE.pattern},
                "size_bytes": {"type": "integer", "minimum": 1},
            },
        },
        "package": {
            "type": "object", "additionalProperties": False,
            "required": ["application_id", "version_name", "version_code"],
            "properties": {
                "application_id": {"type": "string", "const": PACKAGE},
                "version_name": {"type": "string", "const": "0.1.0"},
                "version_code": {"type": "integer", "minimum": 1},
            },
        },
        "signing": {
            "type": "object", "additionalProperties": False,
            "required": ["certificate_sha256"],
            "properties": {"certificate_sha256": {
                "type": "string", "pattern": CERT_RE.pattern}},
        },
        "evidence": {
            "type": "object", "additionalProperties": False,
            "required": ["name", "sha256"],
            "properties": {
                "name": {"type": "string", "const": RELEASE_EVIDENCE_NAME},
                "sha256": {"type": "string", "pattern": SHA_RE.pattern},
            },
        },
    },
}


class DeliveryFailure(Exception):
    def __init__(self, stage: str, code: str, exit_code: int = EXIT_FAILED):
        super().__init__(code)
        self.stage = stage
        self.code = code
        self.exit_code = exit_code


class InterruptedDelivery(DeliveryFailure):
    pass


@dataclass
class CommandResult:
    returncode: int
    stdout: bytes
    stderr: bytes


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def json_bytes(value: Any) -> bytes:
    return (json.dumps(value, sort_keys=True, indent=2) + "\n").encode("utf-8")


def read_bytes(path: Path, limit: int, stage: str, code: str) -> bytes:
    try:
        data = path.read_bytes()
    except OSError:
        raise DeliveryFailure(stage, code, EXIT_USAGE)
    if len(data) > limit:
        raise DeliveryFailure(stage, code)
    return data


def strict_json_bytes(raw: bytes, stage: str, code: str) -> Dict[str, Any]:
    def pairs(items: List[Tuple[str, Any]]) -> Dict[str, Any]:
        result: Dict[str, Any] = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate key")
            result[key] = value
        return result

    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs)
    except (UnicodeDecodeError, ValueError, json.JSONDecodeError):
        raise DeliveryFailure(stage, code)
    if not isinstance(value, dict):
        raise DeliveryFailure(stage, code)
    return value


def exact_keys(value: Dict[str, Any], expected: Iterable[str], stage: str,
               code: str) -> None:
    if set(value) != set(expected):
        raise DeliveryFailure(stage, code)


def require_string(value: Any, pattern: re.Pattern[str], stage: str,
                   code: str) -> str:
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise DeliveryFailure(stage, code)
    return value


def nonzero_digest(value: Any, stage: str, code: str) -> str:
    digest = require_string(value, SHA_RE, stage, code)
    if digest == "0" * 64:
        raise DeliveryFailure(stage, code)
    return digest


def parse_utc(value: Any, stage: str, code: str) -> str:
    text = require_string(value, UTC_RE, stage, code)
    try:
        datetime.strptime(text, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        raise DeliveryFailure(stage, code)
    return text


def validate_release_lock(raw: bytes) -> Dict[str, Any]:
    stage = "release_lock"
    code = "release_lock_invalid"
    lock = strict_json_bytes(raw, stage, code)
    exact_keys(lock, RELEASE_LOCK_SCHEMA["required"], stage, code)
    if lock["schema_version"] != SCHEMA_VERSION or lock["repository"] != REPOSITORY:
        raise DeliveryFailure(stage, code)

    release = lock["release"]
    artifact = lock["artifact"]
    package = lock["package"]
    signing = lock["signing"]
    evidence = lock["evidence"]
    if not all(isinstance(v, dict) for v in
               (release, artifact, package, signing, evidence)):
        raise DeliveryFailure(stage, code)

    exact_keys(release, ("tag", "source_commit_sha", "tag_ref_sha"), stage, code)
    if release["tag"] != RELEASE_TAG:
        raise DeliveryFailure(stage, code)
    require_string(release["source_commit_sha"], COMMIT_RE, stage, code)
    require_string(release["tag_ref_sha"], COMMIT_RE, stage, code)

    exact_keys(artifact, ("name", "sha256", "size_bytes"), stage, code)
    name = require_string(artifact["name"], APK_NAME_RE, stage, code)
    if name != "tx10-clock-v0.1.0-release.apk":
        raise DeliveryFailure(stage, code)
    nonzero_digest(artifact["sha256"], stage, code)
    if isinstance(artifact["size_bytes"], bool) or not isinstance(
            artifact["size_bytes"], int) or artifact["size_bytes"] < 1:
        raise DeliveryFailure(stage, code)

    exact_keys(package, ("application_id", "version_name", "version_code"),
               stage, code)
    if package["application_id"] != PACKAGE or package["version_name"] != "0.1.0":
        raise DeliveryFailure(stage, code)
    if isinstance(package["version_code"], bool) or not isinstance(
            package["version_code"], int) or package["version_code"] < 1:
        raise DeliveryFailure(stage, code)

    exact_keys(signing, ("certificate_sha256",), stage, code)
    require_string(signing["certificate_sha256"], CERT_RE, stage, code)
    exact_keys(evidence, ("name", "sha256"), stage, code)
    if evidence["name"] != RELEASE_EVIDENCE_NAME:
        raise DeliveryFailure(stage, code)
    nonzero_digest(evidence["sha256"], stage, code)
    return lock


def validate_approval(raw: bytes, mode: str, delivery_sha: str,
                      lock_digest: str, config_digest: str) -> Dict[str, Any]:
    stage = "approval"
    code = "approval_invalid"
    approval = strict_json_bytes(raw, stage, code)
    expected = (
        "schema_version", "receipt_ref", "approval_id", "generation",
        "approved_by", "approved_at", "mode", "delivery_sha",
        "release_lock_sha256", "config_sha256",
    )
    exact_keys(approval, expected, stage, code)
    if (approval["schema_version"] != SCHEMA_VERSION
            or approval["receipt_ref"] != APPROVAL_RECEIPT_REF
            or approval["approved_by"] != "oleg"
            or approval["mode"] != mode
            or approval["delivery_sha"] != delivery_sha
            or approval["release_lock_sha256"] != lock_digest
            or approval["config_sha256"] != config_digest):
        raise DeliveryFailure(stage, code)
    require_string(approval["approval_id"], APPROVAL_ID_RE, stage, code)
    parse_utc(approval["approved_at"], stage, code)
    if isinstance(approval["generation"], bool) or not isinstance(
            approval["generation"], int) or approval["generation"] < 1:
        raise DeliveryFailure(stage, code)
    return approval


RUNTIME_CONFIG_DEFAULTS: Dict[str, Any] = {
    "schemaVersion": 1,
    "bootStart": True,
    "use24Hour": False,
    "showSeconds": True,
    "timeZone": None,
    "digitalColor": "white",
    "dateColor": "grey",
    "tickColor": "silver",
    "accentColor": "orange",
    "showDate": True,
    "digitalSizePercent": 100,
    "secondarySizePercent": 100,
    "burnInEnabled": True,
    "burnInMaxShiftPx": 8,
}
RUNTIME_CONFIG_KEYS = frozenset(RUNTIME_CONFIG_DEFAULTS)
COLOR_NAMES = frozenset(("white", "silver", "grey", "orange"))


def validate_runtime_config(raw: bytes) -> Dict[str, Any]:
    stage = "config"
    code = "config_invalid"
    if len(raw) > 8192:
        raise DeliveryFailure(stage, code)
    config = strict_json_bytes(raw, stage, code)
    if not set(config).issubset(RUNTIME_CONFIG_KEYS):
        raise DeliveryFailure(stage, code)
    effective = dict(RUNTIME_CONFIG_DEFAULTS)
    for key, value in config.items():
        if key in ("bootStart", "use24Hour", "showSeconds", "showDate",
                   "burnInEnabled"):
            if type(value) is not bool:
                raise DeliveryFailure(stage, code)
        elif key == "schemaVersion":
            if type(value) is not int or value != 1:
                raise DeliveryFailure(stage, code)
        elif key in ("digitalSizePercent", "secondarySizePercent"):
            if type(value) is not int or not 50 <= value <= 100:
                raise DeliveryFailure(stage, code)
        elif key == "burnInMaxShiftPx":
            if type(value) is not int or not 0 <= value <= 8:
                raise DeliveryFailure(stage, code)
        elif key in ("digitalColor", "dateColor", "tickColor", "accentColor"):
            if not isinstance(value, str) or value not in COLOR_NAMES:
                raise DeliveryFailure(stage, code)
        elif key == "timeZone":
            if not isinstance(value, str) or not 1 <= len(value) <= 64:
                raise DeliveryFailure(stage, code)
            if re.fullmatch(r"(?:UTC|[A-Za-z][A-Za-z0-9+_-]*(?:/[A-Za-z0-9+_-]+){1,2})",
                            value) is None:
                raise DeliveryFailure(stage, code)
            try:
                ZoneInfo(value)
            except ZoneInfoNotFoundError:
                raise DeliveryFailure(stage, code)
        effective[key] = value
    if effective["bootStart"] is not True:
        raise DeliveryFailure(stage, "config_boot_start_required")
    return effective


class Deadline:
    def __init__(self, seconds: int):
        self.started = time.monotonic()
        self.expires = self.started + seconds

    def remaining(self, stage: str) -> float:
        value = self.expires - time.monotonic()
        if value <= 0:
            raise DeliveryFailure(stage, "delivery_timeout")
        return value

    def timeout(self, desired: float, stage: str) -> float:
        return max(0.1, min(desired, self.remaining(stage)))


def command(argv: Sequence[str], deadline: Deadline, stage: str,
            timeout: float = 30, env: Optional[Dict[str, str]] = None) -> CommandResult:
    try:
        proc = subprocess.run(
            list(argv), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, timeout=deadline.timeout(timeout, stage), env=env,
        )
    except subprocess.TimeoutExpired:
        raise DeliveryFailure(stage, "command_timeout")
    except OSError:
        raise DeliveryFailure(stage, "tool_unavailable", EXIT_USAGE)
    return CommandResult(
        proc.returncode, proc.stdout[:COMMAND_OUTPUT_CAP],
        proc.stderr[:COMMAND_OUTPUT_CAP],
    )


def decoded(value: bytes) -> str:
    return value.decode("utf-8", "replace")


def resolve_tool(env_name: str, default: str, stage: str) -> str:
    value = os.environ.get(env_name, "").strip() or default
    if os.sep in value:
        path = Path(value)
        if not path.is_file() or not os.access(str(path), os.X_OK):
            raise DeliveryFailure(stage, "tool_unavailable", EXIT_USAGE)
        return str(path)
    resolved = shutil.which(value)
    if not resolved:
        raise DeliveryFailure(stage, "tool_unavailable", EXIT_USAGE)
    return resolved


def atomic_write(path: Path, value: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp",
                                     dir=str(path.parent))
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as handle:
            handle.write(json_bytes(value))
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def safe_receipt_base() -> Dict[str, Any]:
    return {
        "schema": RECEIPT_SCHEMA,
        "receipt_ref": APPROVAL_RECEIPT_REF,
        "ok": False,
        "failure_stage": None,
        "failure_code": None,
        "approval": None,
        "claim": None,
        "delivery_sha": None,
        "release_lock_sha256": None,
        "config_sha256": None,
        "release": None,
        "target_fingerprint": None,
        "timestamps": {"started_at": utc_now(), "finished_at": None},
        "exit_results": {},
        "verification": {
            "package": "pending", "version": "pending", "signature": "pending",
            "installed_apk": "pending", "foreground_start": "pending",
            "screenshot": "pending", "system_render_time": "pending",
            "home_exit": "pending", "back_exit": "pending", "restart": "pending",
            "reboot_autostart": "pending", "soak": "pending",
            "soak_duration_seconds": 0, "soak_samples": 0,
            "visual_acceptance": "pending",
        },
        "rollback": {
            "required": False, "apk_restore": "not_needed",
            "config_restore": "not_needed", "foreground_restore": "not_needed",
            "disposition": "not_needed",
        },
    }


class Delivery:
    def __init__(self) -> None:
        self.receipt = safe_receipt_base()
        self.mode = os.environ.get("TX10_DELIVERY_MODE", "live").strip()
        if self.mode not in ("live", "fixture"):
            raise DeliveryFailure("environment", "mode_invalid", EXIT_USAGE)
        self.timeout_seconds = LIVE_TIMEOUT_SECONDS
        self.soak_seconds = LIVE_SOAK_SECONDS
        self.soak_interval = LIVE_SOAK_INTERVAL_SECONDS
        self.boot_timeout = LIVE_BOOT_TIMEOUT_SECONDS
        if self.mode == "fixture":
            self.timeout_seconds = self._fixture_int("TX10_DELIVERY_TIMEOUT_SECONDS", 60, 5, 600)
            self.soak_seconds = self._fixture_int("TX10_DELIVERY_SOAK_SECONDS", 1, 0, 60)
            self.soak_interval = self._fixture_int(
                "TX10_DELIVERY_SOAK_INTERVAL_SECONDS", 1, 1, 10)
            self.boot_timeout = self._fixture_int(
                "TX10_DELIVERY_BOOT_TIMEOUT_SECONDS", 5, 1, 30)
        self.deadline = Deadline(self.timeout_seconds)
        self.delivery_sha = ""
        self.lock: Dict[str, Any] = {}
        self.lock_digest = ""
        self.config_digest = ""
        self.effective_config: Dict[str, Any] = {}
        self.approval: Dict[str, Any] = {}
        self.approval_digest = ""
        self.target = ""
        self.target_fingerprint = ""
        self.adb_path = ""
        self.gh_path = ""
        self.aapt2_path = ""
        self.apksigner_path = ""
        self.claim_dir: Optional[Path] = None
        self.claim_file: Optional[Path] = None
        self.private_evidence_file: Optional[Path] = None
        self.claim_id = ""
        self.release_apk: Optional[Path] = None
        self.release_evidence: Optional[Path] = None
        self.install_started = False
        self.prior: Dict[str, Any] = {
            "installed": False, "apk_backup": None, "config_present": False,
            "config_backup": None, "foreground": None, "apk_sha256": None,
            "certificate_sha256": None, "version_name": None, "version_code": None,
        }
        self.private_evidence: Dict[str, Any] = {
            "schema": "tx10-private-delivery-evidence/v1",
            "claim_id": None,
            "target_fingerprint": None,
            "delivery_sha": None,
            "release_lock_sha256": None,
            "config_sha256": None,
            "prior": None,
            "screenshots": {},
            "rollback": None,
            "result": "executing",
        }

    @staticmethod
    def _fixture_int(name: str, default: int, minimum: int, maximum: int) -> int:
        raw = os.environ.get(name, "").strip()
        if not raw:
            return default
        if re.fullmatch(r"[0-9]{1,6}", raw) is None:
            raise DeliveryFailure("environment", "fixture_timing_invalid", EXIT_USAGE)
        value = int(raw)
        if not minimum <= value <= maximum:
            raise DeliveryFailure("environment", "fixture_timing_invalid", EXIT_USAGE)
        return value

    def _record(self, stage: str, result: int = 0) -> None:
        self.receipt["exit_results"][stage] = result

    def _git(self, args: Sequence[str], stage: str) -> str:
        result = command(["git", "-C", str(ROOT), *args], self.deadline, stage)
        if result.returncode != 0:
            raise DeliveryFailure(stage, "git_state_invalid")
        return decoded(result.stdout).strip()

    def static_inputs(self) -> None:
        stage = "environment"
        self.target = os.environ.get("TX10_ADB_TARGET", "").strip()
        if not self.target:
            raise DeliveryFailure(stage, "target_missing", EXIT_USAGE)
        if TARGET_RE.fullmatch(self.target) is None:
            raise DeliveryFailure(stage, "target_invalid", EXIT_USAGE)

        approval_value = os.environ.get("TX10_DELIVERY_APPROVAL_FILE", "").strip()
        config_value = os.environ.get("TX10_DELIVERY_CONFIG", "").strip()
        state_value = os.environ.get("TX10_DELIVERY_STATE_DIR", "").strip()
        if not approval_value or not config_value or not state_value:
            raise DeliveryFailure(stage, "private_input_missing", EXIT_USAGE)
        approval_path = Path(approval_value)
        config_path = Path(config_value)
        state_dir = Path(state_value)
        if (not state_dir.is_absolute() or state_dir == Path("/")
                or state_dir.is_symlink()):
            raise DeliveryFailure(stage, "state_dir_invalid", EXIT_USAGE)

        self.delivery_sha = self._git(["rev-parse", "HEAD"], "exact_sha")
        require_string(self.delivery_sha, COMMIT_RE, "exact_sha", "delivery_sha_invalid")

        if self.mode == "live":
            lock_path = RELEASE_LOCK
            tracked = self._git(
                ["ls-tree", "--name-only", "HEAD", "--", "delivery/release-lock.json"],
                "release_lock")
            if tracked != "delivery/release-lock.json":
                raise DeliveryFailure("release_lock", "release_lock_not_committed")
            if self._git(["status", "--porcelain", "--untracked-files=no"],
                         "exact_sha"):
                raise DeliveryFailure("exact_sha", "tracked_worktree_dirty")
        else:
            lock_value = os.environ.get("TX10_DELIVERY_FIXTURE_LOCK", "").strip()
            if not lock_value:
                raise DeliveryFailure(stage, "fixture_lock_missing", EXIT_USAGE)
            lock_path = Path(lock_value)

        lock_raw = read_bytes(lock_path, 64 * 1024, "release_lock", "release_lock_unreadable")
        self.lock_digest = sha256_bytes(lock_raw)
        self.lock = validate_release_lock(lock_raw)

        config_raw = read_bytes(config_path, 8192, "config", "config_unreadable")
        self.config_digest = sha256_bytes(config_raw)
        self.effective_config = validate_runtime_config(config_raw)

        approval_raw = read_bytes(
            approval_path, 64 * 1024, "approval", "approval_unreadable")
        self.approval_digest = sha256_bytes(approval_raw)
        self.approval = validate_approval(
            approval_raw, self.mode, self.delivery_sha, self.lock_digest,
            self.config_digest)

        self.receipt["approval"] = {
            "approval_id": self.approval["approval_id"],
            "generation": self.approval["generation"],
            "approved_by": "oleg",
            "approved_at": self.approval["approved_at"],
        }
        self.receipt["delivery_sha"] = self.delivery_sha
        self.receipt["release_lock_sha256"] = self.lock_digest
        self.receipt["config_sha256"] = self.config_digest
        self.receipt["release"] = {
            "tag": self.lock["release"]["tag"],
            "source_commit_sha": self.lock["release"]["source_commit_sha"],
            "tag_ref_sha": self.lock["release"]["tag_ref_sha"],
            "asset_name": self.lock["artifact"]["name"],
            "apk_sha256": self.lock["artifact"]["sha256"],
            "certificate_sha256": self.lock["signing"]["certificate_sha256"],
        }

        self.state_dir = state_dir
        self.config_path = config_path
        self.adb_path = resolve_tool("TX10_ADB", "adb", stage)
        self.aapt2_path = self._android_tool("TX10_AAPT2", "aapt2", stage)
        self.apksigner_path = self._android_tool(
            "TX10_APKSIGNER", "apksigner", stage)
        if self.mode == "live":
            self.gh_path = resolve_tool("TX10_GH", "gh", stage)
            self._verify_newest_main()
        self._record("static_inputs")

    def _android_tool(self, env_name: str, name: str, stage: str) -> str:
        explicit = os.environ.get(env_name, "").strip()
        if explicit:
            return resolve_tool(env_name, name, stage)
        sdk = os.environ.get("ANDROID_SDK_ROOT", "").strip()
        if not sdk:
            raise DeliveryFailure(stage, "android_build_tool_missing", EXIT_USAGE)
        candidate = Path(sdk) / "build-tools" / "36.0.0" / name
        if not candidate.is_file() or not os.access(str(candidate), os.X_OK):
            raise DeliveryFailure(stage, "android_build_tool_missing", EXIT_USAGE)
        return str(candidate)

    def _verify_newest_main(self) -> None:
        stage = "exact_sha"
        result = command(
            [self.gh_path, "api", f"repos/{REPOSITORY}/commits/main", "--jq", ".sha"],
            self.deadline, stage)
        if result.returncode != 0:
            raise DeliveryFailure(stage, "main_resolution_failed")
        main_sha = decoded(result.stdout).strip()
        if main_sha != self.delivery_sha:
            raise DeliveryFailure(stage, "delivery_sha_not_newest_main")

    def acquire_release(self, work: Path) -> None:
        stage = "release"
        release_dir = work / "release"
        release_dir.mkdir(mode=0o700)
        if self.mode == "live":
            result = command(
                [self.gh_path, "release", "download", RELEASE_TAG, "--repo", REPOSITORY,
                 "--pattern", self.lock["artifact"]["name"], "--pattern",
                 RELEASE_EVIDENCE_NAME, "--dir", str(release_dir)],
                self.deadline, stage, timeout=180)
            if result.returncode != 0:
                raise DeliveryFailure(stage, "release_download_failed")
            metadata = self._live_release_metadata()
        else:
            fixture_value = os.environ.get(
                "TX10_DELIVERY_FIXTURE_RELEASE_DIR", "").strip()
            if not fixture_value:
                raise DeliveryFailure(stage, "fixture_release_missing", EXIT_USAGE)
            fixture = Path(fixture_value)
            for name in (self.lock["artifact"]["name"], RELEASE_EVIDENCE_NAME,
                         "release-metadata.json"):
                source = fixture / name
                if not source.is_file():
                    raise DeliveryFailure(stage, "fixture_release_incomplete")
                shutil.copyfile(source, release_dir / name)
            metadata = strict_json_bytes(
                read_bytes(release_dir / "release-metadata.json", 64 * 1024,
                           stage, "release_metadata_invalid"),
                stage, "release_metadata_invalid")

        self.release_apk = release_dir / self.lock["artifact"]["name"]
        self.release_evidence = release_dir / RELEASE_EVIDENCE_NAME
        self._verify_release_metadata(metadata)
        self._verify_release_files()
        self._record(stage)

    def _live_release_metadata(self) -> Dict[str, Any]:
        stage = "release"
        release_result = command(
            [self.gh_path, "api", f"repos/{REPOSITORY}/releases/tags/{RELEASE_TAG}"],
            self.deadline, stage)
        ref_result = command(
            [self.gh_path, "api", f"repos/{REPOSITORY}/git/ref/tags/{RELEASE_TAG}"],
            self.deadline, stage)
        if release_result.returncode != 0 or ref_result.returncode != 0:
            raise DeliveryFailure(stage, "release_metadata_resolution_failed")
        release = strict_json_bytes(
            release_result.stdout, stage, "release_metadata_invalid")
        ref = strict_json_bytes(ref_result.stdout, stage, "release_metadata_invalid")
        try:
            tag_ref_sha = ref["object"]["sha"]
            object_type = ref["object"]["type"]
        except (KeyError, TypeError):
            raise DeliveryFailure(stage, "release_metadata_invalid")
        source_sha = tag_ref_sha
        if object_type == "tag":
            tag_result = command(
                [self.gh_path, "api", f"repos/{REPOSITORY}/git/tags/{tag_ref_sha}"],
                self.deadline, stage)
            if tag_result.returncode != 0:
                raise DeliveryFailure(stage, "release_metadata_resolution_failed")
            tag_obj = strict_json_bytes(
                tag_result.stdout, stage, "release_metadata_invalid")
            try:
                source_sha = tag_obj["object"]["sha"]
            except (KeyError, TypeError):
                raise DeliveryFailure(stage, "release_metadata_invalid")
        return {
            "tag_name": release.get("tag_name"),
            "draft": release.get("draft"),
            "prerelease": release.get("prerelease"),
            "tag_ref_sha": tag_ref_sha,
            "source_commit_sha": source_sha,
            "assets": [
                {"name": item.get("name"), "size": item.get("size")}
                for item in release.get("assets", []) if isinstance(item, dict)
            ],
        }

    def _verify_release_metadata(self, metadata: Dict[str, Any]) -> None:
        stage = "release"
        if (metadata.get("tag_name") != self.lock["release"]["tag"]
                or metadata.get("draft") is not False
                or metadata.get("prerelease") is not False
                or metadata.get("tag_ref_sha") != self.lock["release"]["tag_ref_sha"]
                or metadata.get("source_commit_sha")
                != self.lock["release"]["source_commit_sha"]):
            raise DeliveryFailure(stage, "release_metadata_mismatch")
        assets = metadata.get("assets")
        if not isinstance(assets, list):
            raise DeliveryFailure(stage, "release_metadata_invalid")
        by_name = {item.get("name"): item for item in assets if isinstance(item, dict)}
        apk = by_name.get(self.lock["artifact"]["name"])
        evidence = by_name.get(RELEASE_EVIDENCE_NAME)
        if (not isinstance(apk, dict) or not isinstance(evidence, dict)
                or apk.get("size") != self.lock["artifact"]["size_bytes"]):
            raise DeliveryFailure(stage, "release_asset_mismatch")

    def _verify_release_files(self) -> None:
        stage = "release"
        assert self.release_apk is not None and self.release_evidence is not None
        if (not self.release_apk.is_file() or not self.release_evidence.is_file()
                or sha256_file(self.release_apk) != self.lock["artifact"]["sha256"]
                or self.release_apk.stat().st_size != self.lock["artifact"]["size_bytes"]
                or sha256_file(self.release_evidence) != self.lock["evidence"]["sha256"]):
            raise DeliveryFailure(stage, "release_file_mismatch")

        validator = command(
            [sys.executable, str(ROOT / "tools" / "release-evidence"
                                  / "validate_release_evidence.py"),
             str(self.release_evidence)], self.deadline, stage)
        if validator.returncode != 0:
            raise DeliveryFailure(stage, "release_evidence_invalid")
        evidence = strict_json_bytes(
            read_bytes(self.release_evidence, 1024 * 1024, stage,
                       "release_evidence_invalid"), stage, "release_evidence_invalid")
        try:
            comparisons = (
                evidence["source"]["repository"] == REPOSITORY,
                evidence["source"]["release_tag"] == self.lock["release"]["tag"],
                evidence["source"]["commit_sha"]
                    == self.lock["release"]["source_commit_sha"],
                evidence["artifact"]["filename"] == self.lock["artifact"]["name"],
                evidence["artifact"]["sha256"] == self.lock["artifact"]["sha256"],
                evidence["artifact"]["size_bytes"]
                    == self.lock["artifact"]["size_bytes"],
                evidence["package"] == self.lock["package"],
                evidence["signing"]["certificate_sha256_fingerprint"]
                    == self.lock["signing"]["certificate_sha256"],
                evidence["signing"]["apksigner_verified"] is True,
                evidence["native_libraries"]["present"] is False,
            )
        except (KeyError, TypeError):
            raise DeliveryFailure(stage, "release_evidence_mismatch")
        if not all(comparisons):
            raise DeliveryFailure(stage, "release_evidence_mismatch")

        try:
            with zipfile.ZipFile(self.release_apk) as archive:
                if any(name.startswith("lib/") or name.endswith(".so")
                       for name in archive.namelist()):
                    raise DeliveryFailure(stage, "native_library_present")
        except (OSError, zipfile.BadZipFile):
            raise DeliveryFailure(stage, "apk_archive_invalid")
        identity = self._inspect_apk(self.release_apk, stage)
        if (identity["application_id"] != self.lock["package"]["application_id"]
                or identity["version_name"] != self.lock["package"]["version_name"]
                or identity["version_code"] != self.lock["package"]["version_code"]
                or identity["certificate_sha256"]
                != self.lock["signing"]["certificate_sha256"]):
            raise DeliveryFailure(stage, "apk_identity_mismatch")

    def _inspect_apk(self, path: Path, stage: str) -> Dict[str, Any]:
        signed = command(
            [self.apksigner_path, "verify", "--print-certs", "-Werr", str(path)],
            self.deadline, stage)
        if signed.returncode != 0:
            raise DeliveryFailure(stage, "apksigner_failed")
        signing_text = decoded(signed.stdout + b"\n" + signed.stderr)
        match = re.search(r"SHA-?256 digest:\s*([0-9A-Fa-f: ]{64,128})", signing_text)
        if match is None:
            raise DeliveryFailure(stage, "certificate_unreadable")
        compact = re.sub(r"[^0-9A-F]", "", match.group(1).upper())
        if len(compact) != 64:
            raise DeliveryFailure(stage, "certificate_unreadable")
        cert = ":".join(compact[index:index + 2] for index in range(0, 64, 2))

        badging = command(
            [self.aapt2_path, "dump", "badging", str(path)],
            self.deadline, stage)
        if badging.returncode != 0:
            raise DeliveryFailure(stage, "manifest_unreadable")
        text = decoded(badging.stdout)
        package = re.search(
            r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'",
            text)
        if package is None:
            raise DeliveryFailure(stage, "manifest_unreadable")
        return {
            "application_id": package.group(1),
            "version_code": int(package.group(2)),
            "version_name": package.group(3),
            "certificate_sha256": cert,
        }

    def preflight(self) -> None:
        stage = "preflight"
        salt = "approval-" + self.approval_digest
        env = dict(os.environ)
        env["TX10_ADB_TARGET"] = self.target
        env["TX10_ADB"] = self.adb_path
        env["TX10_PREFLIGHT_SALT"] = salt
        result = command(
            [sys.executable, str(ROOT / "tools" / "adb-preflight" / "adb_preflight.py")],
            self.deadline, stage, timeout=120, env=env)
        try:
            report = json.loads(decoded(result.stdout))
            fingerprint = report["target"]["fingerprint"]
        except (ValueError, KeyError, TypeError):
            raise DeliveryFailure(stage, "preflight_report_invalid")
        if result.returncode != 0 or report.get("ok") is not True:
            raise DeliveryFailure(stage, "preflight_failed")
        if not isinstance(fingerprint, str) or re.fullmatch(
                r"tgt-[0-9a-f]{16}", fingerprint) is None:
            raise DeliveryFailure(stage, "preflight_report_invalid")
        self.target_fingerprint = fingerprint
        self.receipt["target_fingerprint"] = fingerprint
        self.private_evidence["target_fingerprint"] = fingerprint
        self._record(stage)

    def claim(self) -> None:
        stage = "claim"
        claim_material = (
            self.approval["approval_id"] + "\0"
            + str(self.approval["generation"])
        ).encode("utf-8")
        self.claim_id = "claim-" + sha256_bytes(claim_material)[:24]
        try:
            if self.state_dir.exists():
                created = False
            else:
                try:
                    self.state_dir.mkdir(parents=True, mode=0o700)
                    created = True
                except FileExistsError:
                    created = False
            if created:
                os.chmod(self.state_dir, 0o700)
            mode = stat.S_IMODE(self.state_dir.stat().st_mode)
            if (self.state_dir.is_symlink() or not self.state_dir.is_dir()
                    or mode & 0o077):
                raise DeliveryFailure(stage, "claim_store_insecure", EXIT_USAGE)
        except OSError:
            raise DeliveryFailure(stage, "claim_store_unavailable", EXIT_USAGE)
        self.claim_dir = self.state_dir / self.claim_id
        try:
            self.claim_dir.mkdir(mode=0o700)
        except FileExistsError:
            raise DeliveryFailure(stage, "approval_already_claimed", EXIT_REPLAY)
        except OSError:
            raise DeliveryFailure(stage, "claim_store_unavailable", EXIT_USAGE)
        self.claim_file = self.claim_dir / "claim.json"
        self.private_evidence_file = self.claim_dir / "private-evidence.json"
        claimed_at = utc_now()
        self.receipt["claim"] = {
            "claim_id": self.claim_id, "state": "executing",
            "claimed_at": claimed_at, "completed_at": None,
        }
        try:
            atomic_write(self.claim_file, self.receipt)
        except OSError:
            raise DeliveryFailure(stage, "claim_store_unavailable", EXIT_USAGE)
        self.private_evidence.update({
            "claim_id": self.claim_id,
            "delivery_sha": self.delivery_sha,
            "release_lock_sha256": self.lock_digest,
            "config_sha256": self.config_digest,
        })
        if not self._write_private_evidence():
            raise DeliveryFailure(stage, "private_evidence_write_failed")
        self._record(stage)

    def _write_private_evidence(self) -> bool:
        if self.private_evidence_file is None:
            return True
        try:
            atomic_write(self.private_evidence_file, self.private_evidence)
            return True
        except OSError:
            return False

    def adb(self, args: Sequence[str], stage: str, timeout: float = 30) -> CommandResult:
        return command(
            [self.adb_path, "-s", self.target, *args], self.deadline, stage, timeout)

    def adb_ok(self, args: Sequence[str], stage: str, code: str,
               timeout: float = 30) -> CommandResult:
        result = self.adb(args, stage, timeout)
        if result.returncode != 0:
            raise DeliveryFailure(stage, code)
        return result

    def snapshot_prior(self) -> None:
        stage = "snapshot"
        assert self.claim_dir is not None
        rollback_dir = self.claim_dir / "rollback"
        rollback_dir.mkdir(mode=0o700)
        foreground = self._foreground(stage)
        self.prior["foreground"] = foreground

        packages = self.adb_ok(
            ["shell", "pm", "list", "packages", PACKAGE], stage,
            "package_query_failed")
        installed = any(line.strip() == "package:" + PACKAGE
                        for line in decoded(packages.stdout).splitlines())
        self.prior["installed"] = installed
        if installed:
            identity = self._device_package_identity(stage)
            remote = self._installed_apk_remote(stage)
            backup = rollback_dir / "prior.apk"
            self.adb_ok(["pull", remote, str(backup)], stage, "prior_apk_capture_failed",
                        timeout=120)
            if not backup.is_file() or backup.stat().st_size < 1:
                raise DeliveryFailure(stage, "prior_apk_capture_failed")
            os.chmod(backup, 0o600)
            inspected = self._inspect_apk(backup, stage)
            self.prior.update({
                "apk_backup": backup,
                "apk_sha256": sha256_file(backup),
                "certificate_sha256": inspected["certificate_sha256"],
                "version_name": identity["version_name"],
                "version_code": identity["version_code"],
            })

        config_test = self.adb(
            ["shell", "test", "-f", CONFIG_REMOTE], stage)
        if config_test.returncode == 0:
            backup = rollback_dir / "prior-config.json"
            self.adb_ok(["pull", CONFIG_REMOTE, str(backup)], stage,
                        "prior_config_capture_failed")
            if not backup.is_file():
                raise DeliveryFailure(stage, "prior_config_capture_failed")
            os.chmod(backup, 0o600)
            self.prior["config_present"] = True
            self.prior["config_backup"] = backup
        elif config_test.returncode != 1:
            raise DeliveryFailure(stage, "prior_config_query_failed")
        self.private_evidence["prior"] = {
            "package_installed": self.prior["installed"],
            "apk_sha256": self.prior["apk_sha256"],
            "certificate_sha256": self.prior["certificate_sha256"],
            "version_name": self.prior["version_name"],
            "version_code": self.prior["version_code"],
            "config_present": self.prior["config_present"],
            "config_sha256": (
                sha256_file(self.prior["config_backup"])
                if isinstance(self.prior["config_backup"], Path) else None),
            "foreground_component": self.prior["foreground"],
        }
        if not self._write_private_evidence():
            raise DeliveryFailure(stage, "private_evidence_write_failed")
        self._record(stage)

    def _installed_apk_remote(self, stage: str) -> str:
        result = self.adb_ok(
            ["shell", "pm", "path", PACKAGE], stage, "installed_apk_path_failed")
        paths = [line[len("package:"):].strip() for line in decoded(result.stdout).splitlines()
                 if line.startswith("package:")]
        base = [path for path in paths if path.endswith("/base.apk")]
        if len(base) != 1 or REMOTE_APK_RE.fullmatch(base[0]) is None:
            raise DeliveryFailure(stage, "installed_apk_path_invalid")
        return base[0]

    def _device_package_identity(self, stage: str) -> Dict[str, Any]:
        result = self.adb_ok(
            ["shell", "dumpsys", "package", PACKAGE], stage,
            "package_identity_failed")
        text = decoded(result.stdout)
        version_name = re.search(r"(?m)^\s*versionName=([^\s]+)\s*$", text)
        version_code = re.search(r"(?m)^\s*versionCode=([0-9]+)(?:\s|$)", text)
        if version_name is None or version_code is None:
            raise DeliveryFailure(stage, "package_identity_invalid")
        return {"version_name": version_name.group(1),
                "version_code": int(version_code.group(1))}

    def _foreground(self, stage: str) -> Optional[str]:
        result = self.adb_ok(
            ["shell", "dumpsys", "activity", "activities"], stage,
            "foreground_query_failed")
        text = decoded(result.stdout)
        matches = re.findall(
            r"(?:mResumedActivity|ResumedActivity)[^\n]*?\s([A-Za-z0-9._$]+/[A-Za-z0-9._$]+)",
            text)
        if not matches:
            return None
        component = matches[0]
        if COMPONENT_RE.fullmatch(component) is None:
            return None
        return component

    def install_and_configure(self) -> None:
        stage = "install"
        assert self.release_apk is not None
        self.install_started = True
        result = self.adb(
            ["install", "-r", str(self.release_apk)], stage, timeout=180)
        if result.returncode != 0 or "Success" not in decoded(result.stdout + result.stderr):
            raise DeliveryFailure(stage, "adb_install_failed")
        self._record("install")

        stage = "config_publish"
        suffix = self.claim_id.replace("claim-", "")[:12]
        remote_tmp = CONFIG_DIR + "/config.json.delivery-" + suffix + ".tmp"
        self.adb_ok(["shell", "mkdir", "-p", CONFIG_DIR], stage,
                    "config_dir_failed")
        self.adb_ok(["push", str(self.config_path), remote_tmp], stage,
                    "config_push_failed")
        self.adb_ok(["shell", "mv", "-f", remote_tmp, CONFIG_REMOTE], stage,
                    "config_publish_failed")
        self._record(stage)

    def verify_installed_identity(self, work: Path) -> None:
        stage = "installed_identity"
        packages = self.adb_ok(
            ["shell", "pm", "list", "packages", PACKAGE], stage,
            "package_query_failed")
        if not any(line.strip() == "package:" + PACKAGE
                   for line in decoded(packages.stdout).splitlines()):
            raise DeliveryFailure(stage, "package_missing")
        self.receipt["verification"]["package"] = "passed"
        identity = self._device_package_identity(stage)
        if (identity["version_name"] != self.lock["package"]["version_name"]
                or identity["version_code"] != self.lock["package"]["version_code"]):
            raise DeliveryFailure(stage, "installed_version_mismatch")
        self.receipt["verification"]["version"] = "passed"
        remote = self._installed_apk_remote(stage)
        installed = work / "installed.apk"
        self.adb_ok(["pull", remote, str(installed)], stage,
                    "installed_apk_pull_failed", timeout=120)
        if sha256_file(installed) != self.lock["artifact"]["sha256"]:
            raise DeliveryFailure(stage, "installed_apk_digest_mismatch")
        self.receipt["verification"]["installed_apk"] = "passed"
        inspected = self._inspect_apk(installed, stage)
        if inspected["certificate_sha256"] != self.lock["signing"]["certificate_sha256"]:
            raise DeliveryFailure(stage, "installed_signature_mismatch")
        self.receipt["verification"]["signature"] = "passed"
        self._record(stage)

    def launch_and_verify(self, expected_boot: bool, screenshot_name: str) -> None:
        stage = "foreground"
        start = self.adb(
            ["shell", "am", "start", "-W", "-n", ACTIVITY], stage, timeout=60)
        if start.returncode != 0 or "Error" in decoded(start.stdout + start.stderr):
            raise DeliveryFailure(stage, "foreground_start_failed")
        self._wait_foreground(ACTIVITY, stage, 30)
        self.receipt["verification"]["foreground_start"] = "passed"
        self._verify_status_time(expected_boot, stage)
        self._capture_screenshot(screenshot_name, stage)
        self._record(stage)

    def _wait_foreground(self, expected: str, stage: str, seconds: int) -> None:
        end = time.monotonic() + seconds
        while True:
            if self._foreground(stage) == expected:
                return
            if time.monotonic() >= end:
                raise DeliveryFailure(stage, "foreground_mismatch")
            self._sleep(1, stage)

    def _read_status(self, stage: str) -> Dict[str, Any]:
        result = self.adb_ok(
            ["shell", "cat", STATUS_REMOTE], stage, "status_read_failed")
        status = strict_json_bytes(result.stdout, stage, "status_invalid")
        required = {
            "statusSchemaVersion", "configSource", "lastReloadRejected",
            "lastRejectReason", "bootLaunch", "updatedAtEpochMillis",
            "effectiveConfig",
        }
        if set(status) != required or status["statusSchemaVersion"] != 1:
            raise DeliveryFailure(stage, "status_invalid")
        return status

    def _verify_status_time(self, expected_boot: bool, stage: str,
                            require_fresh: bool = True) -> None:
        status = self._read_status(stage)
        if (status.get("configSource") != "external"
                or status.get("lastReloadRejected") is not False
                or status.get("lastRejectReason") is not None
                or status.get("bootLaunch") is not expected_boot):
            raise DeliveryFailure(stage, "runtime_status_mismatch")
        effective = status.get("effectiveConfig")
        if not isinstance(effective, dict):
            raise DeliveryFailure(stage, "runtime_status_mismatch")
        for key, value in self.effective_config.items():
            if effective.get(key) != value:
                raise DeliveryFailure(stage, "effective_config_mismatch")
        updated = status.get("updatedAtEpochMillis")
        if isinstance(updated, bool) or not isinstance(updated, int) or updated < 1:
            raise DeliveryFailure(stage, "runtime_time_invalid")
        device = self.adb_ok(
            ["shell", "date", "+%s"], stage, "device_time_failed")
        epoch_text = decoded(device.stdout).strip()
        if re.fullmatch(r"[0-9]{9,11}", epoch_text) is None:
            raise DeliveryFailure(stage, "device_time_invalid")
        skew = abs(updated // 1000 - int(epoch_text))
        if require_fresh and skew > 300:
            raise DeliveryFailure(stage, "system_render_time_skew")
        timezone_result = self.adb_ok(
            ["shell", "getprop", "persist.sys.timezone"], stage,
            "device_timezone_failed")
        if not decoded(timezone_result.stdout).strip():
            raise DeliveryFailure(stage, "device_timezone_invalid")
        self.receipt["verification"]["system_render_time"] = "passed"

    def _capture_screenshot(self, name: str, stage: str) -> None:
        assert self.claim_dir is not None
        result = self.adb_ok(
            ["exec-out", "screencap", "-p"], stage, "screenshot_failed",
            timeout=60)
        data = result.stdout
        if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
            raise DeliveryFailure(stage, "screenshot_invalid")
        width = int.from_bytes(data[16:20], "big")
        height = int.from_bytes(data[20:24], "big")
        if width < 1 or height < 1 or width > 8192 or height > 8192:
            raise DeliveryFailure(stage, "screenshot_invalid")
        evidence_dir = self.claim_dir / "evidence"
        evidence_dir.mkdir(exist_ok=True, mode=0o700)
        screenshot = evidence_dir / name
        screenshot.write_bytes(data)
        os.chmod(screenshot, 0o600)
        self.private_evidence["screenshots"][name] = sha256_bytes(data)
        if not self._write_private_evidence():
            raise DeliveryFailure(stage, "private_evidence_write_failed")
        self.receipt["verification"]["screenshot"] = "passed"

    def verify_navigation_and_restart(self) -> None:
        stage = "navigation"
        self.adb_ok(["shell", "input", "keyevent", "KEYCODE_HOME"], stage,
                    "home_key_failed")
        self._sleep(1, stage)
        if self._foreground(stage) == ACTIVITY:
            raise DeliveryFailure(stage, "home_did_not_exit")
        self.receipt["verification"]["home_exit"] = "passed"

        self._restart(stage)
        self.adb_ok(["shell", "input", "keyevent", "KEYCODE_BACK"], stage,
                    "back_key_failed")
        self._sleep(1, stage)
        if self._foreground(stage) == ACTIVITY:
            raise DeliveryFailure(stage, "back_did_not_exit")
        self.receipt["verification"]["back_exit"] = "passed"
        self._restart(stage)
        self.receipt["verification"]["restart"] = "passed"
        self._record(stage)

    def _restart(self, stage: str) -> None:
        self.adb_ok(["shell", "am", "force-stop", PACKAGE], stage,
                    "force_stop_failed")
        self.adb_ok(["shell", "am", "start", "-W", "-n", ACTIVITY], stage,
                    "restart_failed")
        self._wait_foreground(ACTIVITY, stage, 30)
        self._verify_status_time(False, stage)

    def reboot_and_verify(self) -> None:
        stage = "reboot"
        self.adb_ok(["reboot"], stage, "reboot_command_failed")
        self.adb_ok(["wait-for-device"], stage, "reconnect_failed",
                    timeout=self.boot_timeout)
        end = time.monotonic() + self.boot_timeout
        while True:
            result = self.adb(
                ["shell", "getprop", "sys.boot_completed"], stage)
            if result.returncode == 0 and decoded(result.stdout).strip() == "1":
                break
            if time.monotonic() >= end:
                raise DeliveryFailure(stage, "boot_timeout")
            self._sleep(1, stage)
        self._wait_foreground(ACTIVITY, stage, self.boot_timeout)
        self._verify_status_time(True, stage)
        self._capture_screenshot("after-reboot.png", stage)
        self.receipt["verification"]["reboot_autostart"] = "passed"
        self._record(stage)

    def soak(self) -> None:
        stage = "soak"
        started = time.monotonic()
        initial_pid = self._pid(stage)
        samples = 0
        while True:
            current_pid = self._pid(stage)
            if current_pid != initial_pid:
                raise DeliveryFailure(stage, "pid_changed")
            if self._foreground(stage) != ACTIVITY:
                raise DeliveryFailure(stage, "foreground_lost")
            self._verify_status_time(True, stage, require_fresh=False)
            app_logs = self.adb_ok(
                ["shell", "logcat", "-d", "-v", "brief", "--pid", current_pid,
                 "-t", "2000"], stage, "logcat_failed")
            system_logs = self.adb_ok(
                ["shell", "logcat", "-d", "-v", "brief", "-t", "2000"],
                stage, "logcat_failed")
            self._check_logs(
                decoded(app_logs.stdout + b"\n" + app_logs.stderr),
                decoded(system_logs.stdout + b"\n" + system_logs.stderr), stage)
            samples += 1
            elapsed = time.monotonic() - started
            if elapsed >= self.soak_seconds:
                break
            self._sleep(min(self.soak_interval, self.soak_seconds - elapsed), stage)
        duration = int(time.monotonic() - started)
        if self.mode == "live" and duration < LIVE_SOAK_SECONDS:
            raise DeliveryFailure(stage, "soak_too_short")
        self.receipt["verification"]["soak"] = "passed"
        self.receipt["verification"]["soak_duration_seconds"] = duration
        self.receipt["verification"]["soak_samples"] = samples
        self._record(stage)

    def _pid(self, stage: str) -> str:
        result = self.adb_ok(
            ["shell", "pidof", PACKAGE], stage, "pid_query_failed")
        values = decoded(result.stdout).strip().split()
        if len(values) != 1 or PID_RE.fullmatch(values[0]) is None:
            raise DeliveryFailure(stage, "pid_invalid")
        return values[0]

    @staticmethod
    def _check_logs(app_text: str, system_text: str, stage: str) -> None:
        if any(marker in app_text for marker in
               ("FATAL EXCEPTION", "AndroidRuntime", "am_crash", "am_anr")):
            raise DeliveryFailure(stage, "crash_or_anr_detected")
        package_markers = (
            "ANR in " + PACKAGE, "Process " + PACKAGE + " has died",
            "Force finishing activity " + PACKAGE, "am_crash", "am_anr",
        )
        for line in system_text.splitlines():
            if PACKAGE in line and any(marker in line for marker in package_markers):
                raise DeliveryFailure(stage, "crash_or_anr_detected")

    def _sleep(self, seconds: float, stage: str) -> None:
        if seconds <= 0:
            self.deadline.remaining(stage)
            return
        remaining = self.deadline.remaining(stage)
        requested = min(seconds, remaining)
        # Fixture mode still crosses the real scheduling boundary, but does not
        # turn Home/Back/reboot polling into multi-second host tests.
        if self.mode == "fixture":
            requested = min(requested, 0.01)
        time.sleep(requested)
        self.deadline.remaining(stage)

    def rollback(self) -> None:
        # The 60-minute delivery deadline stops acceptance work. Recovery gets
        # a separate, bounded five-minute window so timeout cannot suppress the
        # permitted in-place restore attempt.
        self.deadline = Deadline(5 * 60)
        self.receipt["rollback"]["required"] = True
        apk_status = "unavailable"
        config_status = "unavailable"
        foreground_status = "unavailable"
        stage = "rollback"
        try:
            if self.prior["installed"] and isinstance(self.prior["apk_backup"], Path):
                result = self.adb(
                    ["install", "-r", str(self.prior["apk_backup"])], stage,
                    timeout=180)
                apk_status = "restored" if (
                    result.returncode == 0
                    and "Success" in decoded(result.stdout + result.stderr)) else "failed"
            else:
                apk_status = "not_available"
        except DeliveryFailure:
            apk_status = "failed"
        try:
            if self.prior["config_present"] and isinstance(
                    self.prior["config_backup"], Path):
                remote_tmp = CONFIG_DIR + "/config.json.rollback-" + self.claim_id[-12:] + ".tmp"
                first = self.adb(
                    ["push", str(self.prior["config_backup"]), remote_tmp], stage)
                second = self.adb(
                    ["shell", "mv", "-f", remote_tmp, CONFIG_REMOTE], stage)
                config_status = "restored" if (
                    first.returncode == 0 and second.returncode == 0) else "failed"
            else:
                # Absence cannot be restored without deleting the newly written
                # file, and automatic deletion is intentionally forbidden.
                config_status = "not_available"
        except DeliveryFailure:
            config_status = "failed"
        try:
            component = self.prior.get("foreground")
            if isinstance(component, str) and COMPONENT_RE.fullmatch(component):
                result = self.adb(
                    ["shell", "am", "start", "-W", "-n", component], stage,
                    timeout=60)
                foreground_status = "restored" if result.returncode == 0 else "failed"
            else:
                foreground_status = "not_available"
        except DeliveryFailure:
            foreground_status = "failed"
        self.receipt["rollback"].update({
            "apk_restore": apk_status,
            "config_restore": config_status,
            "foreground_restore": foreground_status,
        })
        restored = (
            self.prior["installed"] is True
            and apk_status == "restored"
            and self.prior["config_present"] is True
            and config_status == "restored"
        )
        if restored:
            disposition = "in_place_restore_completed"
        elif foreground_status == "restored":
            disposition = "prior_foreground_restored_awaiting_destructive_approval"
        else:
            disposition = "awaiting_destructive_approval"
        self.receipt["rollback"]["disposition"] = disposition
        self.private_evidence["rollback"] = {
            "apk_restore": apk_status,
            "config_restore": config_status,
            "foreground_restore": foreground_status,
            "disposition": disposition,
        }
        self._write_private_evidence()

    def finish(self, ok: bool, failure: Optional[DeliveryFailure]) -> bool:
        self.receipt["ok"] = ok
        self.receipt["timestamps"]["finished_at"] = utc_now()
        if failure is not None:
            self.receipt["failure_stage"] = failure.stage
            self.receipt["failure_code"] = failure.code
            self._record(failure.stage, failure.exit_code)
        if self.receipt.get("claim") is not None:
            self.receipt["claim"]["state"] = "completed" if ok else "failed"
            self.receipt["claim"]["completed_at"] = utc_now()
            self.private_evidence["result"] = "passed" if ok else "failed"
        if self.claim_file is not None:
            try:
                atomic_write(self.claim_file, self.receipt)
            except OSError:
                if failure is None:
                    self.receipt["ok"] = False
                    self.receipt["failure_stage"] = "claim"
                    self.receipt["failure_code"] = "claim_result_write_failed"
                    self.private_evidence["result"] = "failed"
        if not self._write_private_evidence():
            if failure is None:
                self.receipt["ok"] = False
                self.receipt["failure_stage"] = "claim"
                self.receipt["failure_code"] = "private_evidence_write_failed"
                self.private_evidence["result"] = "failed"
                if self.claim_file is not None:
                    try:
                        atomic_write(self.claim_file, self.receipt)
                    except OSError:
                        pass
        sys.stdout.buffer.write(json_bytes(self.receipt))
        return self.receipt["ok"] is True

    def run(self) -> int:
        work_context = tempfile.TemporaryDirectory(prefix="tx10-delivery-")
        failure: Optional[DeliveryFailure] = None
        try:
            self.static_inputs()
            self.acquire_release(Path(work_context.name))
            self.preflight()
            self.claim()
            self.snapshot_prior()
            self.install_and_configure()
            self.verify_installed_identity(Path(work_context.name))
            self.launch_and_verify(False, "after-install.png")
            self.verify_navigation_and_restart()
            self.reboot_and_verify()
            self.soak()
            self.receipt["ok"] = True
            return EXIT_OK if self.finish(True, None) else EXIT_FAILED
        except DeliveryFailure as exc:
            failure = exc
            if self.install_started:
                self.rollback()
            self.finish(False, failure)
            return failure.exit_code
        except Exception:
            failure = DeliveryFailure("internal", "unexpected_internal_failure")
            if self.install_started:
                self.rollback()
            self.finish(False, failure)
            return EXIT_FAILED
        finally:
            work_context.cleanup()


def emit_schema() -> int:
    sys.stdout.buffer.write(json_bytes(RELEASE_LOCK_SCHEMA))
    return 0


def validate_lock_cli(path_value: str) -> int:
    receipt = {"tool": "tx10-release-lock-validate", "valid": False,
               "error": None}
    try:
        raw = read_bytes(Path(path_value), 64 * 1024, "release_lock",
                         "release_lock_unreadable")
        validate_release_lock(raw)
        receipt["valid"] = True
        code = 0
    except DeliveryFailure as exc:
        receipt["error"] = exc.code
        code = 1 if exc.exit_code != EXIT_USAGE else 2
    sys.stdout.buffer.write(json_bytes(receipt))
    return code


def signal_failure(signum: int, _frame: Any) -> None:
    raise InterruptedDelivery("signal", "delivery_interrupted")


def main(argv: Sequence[str]) -> int:
    signal.signal(signal.SIGINT, signal_failure)
    signal.signal(signal.SIGTERM, signal_failure)
    if list(argv) == ["--emit-release-lock-schema"]:
        return emit_schema()
    if len(argv) == 2 and argv[0] == "--validate-release-lock":
        return validate_lock_cli(argv[1])
    if argv:
        sys.stdout.buffer.write(json_bytes({
            "schema": RECEIPT_SCHEMA, "ok": False,
            "failure_stage": "arguments", "failure_code": "arguments_forbidden",
        }))
        return EXIT_USAGE
    try:
        return Delivery().run()
    except DeliveryFailure as exc:
        receipt = safe_receipt_base()
        receipt["failure_stage"] = exc.stage
        receipt["failure_code"] = exc.code
        receipt["timestamps"]["finished_at"] = utc_now()
        receipt["exit_results"][exc.stage] = exc.exit_code
        sys.stdout.buffer.write(json_bytes(receipt))
        return exc.exit_code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
