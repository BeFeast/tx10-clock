#!/usr/bin/env python3
"""Host-only tests for the approval-gated delivery entrypoint."""

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
DELIVER = ROOT / "scripts" / "deliver.sh"
TOOL = ROOT / "tools" / "delivery" / "deliver.py"
FAKE_ADB = Path(__file__).with_name("fake_adb.py")
FAKE_APKSIGNER = Path(__file__).with_name("fake_apksigner.py")
FAKE_AAPT2 = Path(__file__).with_name("fake_aapt2.py")
CERT = ("AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
        "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99")
TARGET = "FIXTURE_SERIAL_001"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class DeliveryHarness(unittest.TestCase):
    maxDiff = None

    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="delivery-fixture-"))
        self.addCleanup(shutil.rmtree, self.root, True)
        self.release_dir = self.root / "release"
        self.release_dir.mkdir()
        self.state_dir = self.root / "state"
        self.fake_dir = self.root / "fake-adb"
        self.fake_dir.mkdir()
        self.delivery_sha = subprocess.check_output(
            ["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True).strip()

        self.apk = self.release_dir / "tx10-clock-v0.1.0-release.apk"
        with zipfile.ZipFile(self.apk, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("AndroidManifest.xml", b"harmless fixture")
            archive.writestr("classes.dex", b"not executable")
        self.prior_apk = self.root / "prior-source.apk"
        with zipfile.ZipFile(self.prior_apk, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("AndroidManifest.xml", b"prior harmless fixture")
        self.prior_config = self.root / "prior-config.json"
        self.prior_config.write_text("{}\n", encoding="utf-8")
        self.config = self.root / "config.json"
        self.config.write_text(
            '{"schemaVersion":1,"bootStart":true}\n', encoding="utf-8")

        evidence = json.loads((ROOT / "release" / "evidence" / "fixtures" / "valid"
                               / "v0.1.0-signed-release.json").read_text(
                                   encoding="utf-8"))
        evidence["source"]["commit_sha"] = self.delivery_sha
        evidence["artifact"]["sha256"] = sha(self.apk)
        evidence["artifact"]["size_bytes"] = self.apk.stat().st_size
        evidence["signing"]["certificate_sha256_fingerprint"] = CERT
        self.evidence = self.release_dir / "release-evidence.json"
        self.evidence.write_text(
            json.dumps(evidence, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        self.tag_ref_sha = "b" * 40
        lock = {
            "schema_version": "1.0.0",
            "repository": "BeFeast/tx10-clock",
            "release": {
                "tag": "v0.1.0",
                "source_commit_sha": self.delivery_sha,
                "tag_ref_sha": self.tag_ref_sha,
            },
            "artifact": {
                "name": self.apk.name,
                "sha256": sha(self.apk),
                "size_bytes": self.apk.stat().st_size,
            },
            "package": {
                "application_id": "com.befeast.tx10clock",
                "version_name": "0.1.0",
                "version_code": 1,
            },
            "signing": {"certificate_sha256": CERT},
            "evidence": {
                "name": "release-evidence.json",
                "sha256": sha(self.evidence),
            },
        }
        self.lock = self.root / "release-lock.json"
        self.lock.write_text(
            json.dumps(lock, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        metadata = {
            "tag_name": "v0.1.0",
            "draft": False,
            "prerelease": False,
            "tag_ref_sha": self.tag_ref_sha,
            "source_commit_sha": self.delivery_sha,
            "assets": [
                {"name": self.apk.name, "size": self.apk.stat().st_size},
                {"name": "release-evidence.json", "size": self.evidence.stat().st_size},
            ],
        }
        (self.release_dir / "release-metadata.json").write_text(
            json.dumps(metadata, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        approval = {
            "schema_version": "1.0.0",
            "receipt_ref": "872",
            "approval_id": "oleg-fixture-approval-0001",
            "generation": 1,
            "approved_by": "oleg",
            "approved_at": "2026-07-23T10:00:00Z",
            "mode": "fixture",
            "delivery_sha": self.delivery_sha,
            "release_lock_sha256": sha(self.lock),
            "config_sha256": sha(self.config),
        }
        self.approval = self.root / "approval.json"
        self.approval.write_text(
            json.dumps(approval, sort_keys=True, indent=2) + "\n", encoding="utf-8")

    def env(self):
        env = {key: value for key, value in os.environ.items()
               if not key.startswith(("TX10_", "FAKE_ADB", "FAKE_CERT",
                                      "FAKE_DEVICE", "FAKE_REBOOT", "FAKE_LOGCAT"))}
        env.update({
            "TX10_DELIVERY_MODE": "fixture",
            "TX10_ADB_TARGET": TARGET,
            "TX10_ADB": str(FAKE_ADB),
            "TX10_AAPT2": str(FAKE_AAPT2),
            "TX10_APKSIGNER": str(FAKE_APKSIGNER),
            "TX10_DELIVERY_APPROVAL_FILE": str(self.approval),
            "TX10_DELIVERY_CONFIG": str(self.config),
            "TX10_DELIVERY_STATE_DIR": str(self.state_dir),
            "TX10_DELIVERY_FIXTURE_LOCK": str(self.lock),
            "TX10_DELIVERY_FIXTURE_RELEASE_DIR": str(self.release_dir),
            "TX10_DELIVERY_TIMEOUT_SECONDS": "30",
            "TX10_DELIVERY_SOAK_SECONDS": "0",
            "TX10_DELIVERY_BOOT_TIMEOUT_SECONDS": "2",
            "TX10_PREFLIGHT_HOST_EPOCH": "1700000000",
            "FAKE_ADB_DIR": str(self.fake_dir),
            "FAKE_RELEASE_APK": str(self.apk),
            "FAKE_PRIOR_APK": str(self.prior_apk),
            "FAKE_PRIOR_CONFIG": str(self.prior_config),
            "FAKE_CERT": CERT,
            "FAKE_DEVICE_EPOCH": "1700000000",
            "FAKE_REBOOT_AUTOSTART": "1",
        })
        return env

    def run_delivery(self, env=None):
        return subprocess.run(
            [str(DELIVER)], cwd=ROOT, env=env or self.env(),
            capture_output=True, text=True, timeout=60)

    def receipt(self, result):
        try:
            return json.loads(result.stdout)
        except ValueError:
            self.fail("delivery stdout is not JSON: %r" % result.stdout[:1000])

    def calls(self):
        path = self.fake_dir / "calls.log"
        if not path.exists():
            return []
        return [line.rstrip("\n").split("\t")
                for line in path.read_text(encoding="utf-8").splitlines()]

    def assert_no_leak(self, result):
        for output in (result.stdout, result.stderr):
            self.assertNotIn(TARGET, output)
            self.assertNotIn(str(self.root), output)
            self.assertNotIn(str(FAKE_ADB), output)


class ContractTests(DeliveryHarness):
    def test_emitted_release_lock_schema_matches_committed_schema(self):
        emitted = subprocess.check_output(
            ["python3", str(TOOL), "--emit-release-lock-schema"], text=True)
        committed = (ROOT / "delivery" / "schema"
                     / "release-lock-v1.schema.json").read_text(encoding="utf-8")
        self.assertEqual(emitted, committed)

    def test_release_lock_validator_accepts_fixture_and_rejects_digest_drift(self):
        accepted = subprocess.run(
            ["python3", str(TOOL), "--validate-release-lock", str(self.lock)],
            capture_output=True, text=True)
        self.assertEqual(accepted.returncode, 0, accepted.stdout)
        self.assertTrue(json.loads(accepted.stdout)["valid"])
        value = json.loads(self.lock.read_text(encoding="utf-8"))
        value["artifact"]["sha256"] = "0" * 64
        self.lock.write_text(json.dumps(value), encoding="utf-8")
        rejected = subprocess.run(
            ["python3", str(TOOL), "--validate-release-lock", str(self.lock)],
            capture_output=True, text=True)
        self.assertEqual(rejected.returncode, 1)
        self.assertFalse(json.loads(rejected.stdout)["valid"])


class EntrypointTests(DeliveryHarness):
    def test_missing_target_fails_closed_without_claim_or_adb(self):
        env = self.env()
        del env["TX10_ADB_TARGET"]
        result = self.run_delivery(env)
        self.assertEqual(result.returncode, 2, result.stdout)
        receipt = self.receipt(result)
        self.assertEqual(receipt["failure_code"], "target_missing")
        self.assertEqual(self.calls(), [])
        self.assertFalse(self.state_dir.exists())
        self.assert_no_leak(result)

    def test_harmless_fixture_runs_full_delivery_and_leaves_visual_pending(self):
        result = self.run_delivery()
        self.assertEqual(result.returncode, 0, result.stdout)
        receipt = self.receipt(result)
        self.assertTrue(receipt["ok"])
        self.assertEqual(receipt["claim"]["state"], "completed")
        self.assertEqual(receipt["approval"]["approved_by"], "oleg")
        self.assertEqual(receipt["approval"]["generation"], 1)
        self.assertEqual(receipt["delivery_sha"], self.delivery_sha)
        self.assertEqual(receipt["config_sha256"], sha(self.config))
        self.assertEqual(receipt["verification"]["visual_acceptance"], "pending")
        for key in ("package", "version", "signature", "installed_apk",
                    "foreground_start", "screenshot", "system_render_time",
                    "home_exit", "back_exit", "restart", "reboot_autostart", "soak"):
            self.assertEqual(receipt["verification"][key], "passed", key)
        calls = self.calls()
        self.assertTrue(any(call[-3:-1] == ["install", "-r"] for call in calls))
        self.assertTrue(any(call[-1:] == ["reboot"] for call in calls))
        self.assertFalse(any("uninstall" in call for call in calls))
        claim_dirs = list(self.state_dir.glob("claim-*"))
        self.assertEqual(len(claim_dirs), 1)
        self.assertTrue((claim_dirs[0] / "rollback" / "prior.apk").is_file())
        self.assertTrue((claim_dirs[0] / "rollback" / "prior-config.json").is_file())
        self.assertTrue((claim_dirs[0] / "evidence" / "after-install.png").is_file())
        self.assertTrue((claim_dirs[0] / "evidence" / "after-reboot.png").is_file())
        private = json.loads((claim_dirs[0] / "private-evidence.json").read_text(
            encoding="utf-8"))
        self.assertEqual(private["result"], "passed")
        self.assertEqual(private["prior"]["version_name"], "0.0.9")
        self.assertEqual(private["prior"]["certificate_sha256"], CERT)
        self.assertEqual(private["prior"]["foreground_component"],
                         "com.example.clock/com.example.clock.Main")
        self.assertEqual(set(private["screenshots"]),
                         {"after-install.png", "after-reboot.png"})
        private_text = json.dumps(private)
        self.assertNotIn(TARGET, private_text)
        self.assertNotIn(str(self.root), private_text)
        self.assert_no_leak(result)

    def test_completed_approval_cannot_replay(self):
        first = self.run_delivery()
        self.assertEqual(first.returncode, 0, first.stdout)
        first_install_count = sum(
            1 for call in self.calls() if "install" in call and "-r" in call)
        second = self.run_delivery()
        self.assertEqual(second.returncode, 3, second.stdout)
        receipt = self.receipt(second)
        self.assertEqual(receipt["failure_code"], "approval_already_claimed")
        second_install_count = sum(
            1 for call in self.calls() if "install" in call and "-r" in call)
        self.assertEqual(second_install_count, first_install_count)
        self.assert_no_leak(second)

        # Rewriting non-identity assertion metadata cannot turn the same
        # approval id/generation into a fresh claim.
        approval = json.loads(self.approval.read_text(encoding="utf-8"))
        approval["approved_at"] = "2026-07-23T10:00:01Z"
        self.approval.write_text(
            json.dumps(approval, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        third = self.run_delivery()
        self.assertEqual(third.returncode, 3, third.stdout)
        third_install_count = sum(
            1 for call in self.calls() if "install" in call and "-r" in call)
        self.assertEqual(third_install_count, first_install_count)
        self.assert_no_leak(third)

    def test_preflight_failure_never_claims_or_installs(self):
        env = self.env()
        env["FAKE_ADB_FAIL_KEY"] = "get-state"
        result = self.run_delivery(env)
        self.assertEqual(result.returncode, 1, result.stdout)
        receipt = self.receipt(result)
        self.assertEqual(receipt["failure_stage"], "preflight")
        self.assertFalse(self.state_dir.exists())
        self.assertFalse(any("install" in call for call in self.calls()))
        self.assert_no_leak(result)

    def test_post_install_failure_uses_only_in_place_restore_and_foreground(self):
        env = self.env()
        env["FAKE_ADB_FAIL_KEY"] = "exec-out screencap -p"
        result = self.run_delivery(env)
        self.assertEqual(result.returncode, 1, result.stdout)
        receipt = self.receipt(result)
        self.assertEqual(receipt["failure_stage"], "foreground")
        self.assertTrue(receipt["rollback"]["required"])
        self.assertEqual(receipt["rollback"]["apk_restore"], "restored")
        self.assertEqual(receipt["rollback"]["config_restore"], "restored")
        self.assertEqual(receipt["rollback"]["foreground_restore"], "restored")
        calls = self.calls()
        installs = [call for call in calls if "install" in call and "-r" in call]
        self.assertEqual(len(installs), 2)
        self.assertFalse(any("uninstall" in call for call in calls))
        self.assertTrue(any(call[-1:] == ["com.example.clock/com.example.clock.Main"]
                            for call in calls))
        self.assert_no_leak(result)

    def test_timeout_after_install_still_gets_bounded_recovery_attempt(self):
        env = self.env()
        env["TX10_DELIVERY_TIMEOUT_SECONDS"] = "5"
        env["FAKE_ADB_SLEEP_KEY"] = "exec-out screencap -p"
        env["FAKE_ADB_SLEEP_SECONDS"] = "10"
        result = self.run_delivery(env)
        self.assertEqual(result.returncode, 1, result.stdout)
        receipt = self.receipt(result)
        self.assertEqual(receipt["failure_code"], "command_timeout")
        self.assertEqual(receipt["rollback"]["apk_restore"], "restored")
        self.assertEqual(receipt["rollback"]["config_restore"], "restored")
        installs = [call for call in self.calls() if "install" in call and "-r" in call]
        self.assertEqual(len(installs), 2)
        self.assertFalse(any("uninstall" in call for call in self.calls()))
        self.assert_no_leak(result)

    def test_crash_marker_fails_soak_and_restores_in_place(self):
        env = self.env()
        env["FAKE_APP_LOGCAT"] = "E/AndroidRuntime: FATAL EXCEPTION: main\n"
        result = self.run_delivery(env)
        self.assertEqual(result.returncode, 1, result.stdout)
        receipt = self.receipt(result)
        self.assertEqual(receipt["failure_stage"], "soak")
        self.assertEqual(receipt["failure_code"], "crash_or_anr_detected")
        self.assertEqual(receipt["rollback"]["apk_restore"], "restored")
        self.assertFalse(any("uninstall" in call for call in self.calls()))
        self.assert_no_leak(result)

    def test_failed_reboot_autostart_is_not_accepted(self):
        env = self.env()
        env["FAKE_REBOOT_AUTOSTART"] = "0"
        result = self.run_delivery(env)
        self.assertEqual(result.returncode, 1, result.stdout)
        receipt = self.receipt(result)
        self.assertEqual(receipt["failure_stage"], "reboot")
        self.assertEqual(receipt["failure_code"], "foreground_mismatch")
        self.assertEqual(receipt["verification"]["reboot_autostart"], "pending")
        self.assertEqual(receipt["rollback"]["apk_restore"], "restored")
        self.assert_no_leak(result)

    def test_absent_prior_package_and_config_are_never_deleted(self):
        env = self.env()
        env["FAKE_PRIOR_PACKAGE_PRESENT"] = "0"
        env["FAKE_PRIOR_CONFIG_PRESENT"] = "0"
        env["FAKE_ADB_FAIL_KEY"] = "exec-out screencap -p"
        result = self.run_delivery(env)
        self.assertEqual(result.returncode, 1, result.stdout)
        receipt = self.receipt(result)
        self.assertEqual(receipt["rollback"]["apk_restore"], "not_available")
        self.assertEqual(receipt["rollback"]["config_restore"], "not_available")
        self.assertEqual(
            receipt["rollback"]["disposition"],
            "prior_foreground_restored_awaiting_destructive_approval")
        calls = self.calls()
        installs = [call for call in calls if "install" in call and "-r" in call]
        self.assertEqual(len(installs), 1)
        self.assertFalse(any("uninstall" in call for call in calls))
        self.assertFalse(any("rm" in call for call in calls))
        self.assert_no_leak(result)


if __name__ == "__main__":
    unittest.main()
