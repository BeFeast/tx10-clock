#!/usr/bin/env python3
"""Stateful harmless ADB double for delivery tests; never contacts adb."""

import json
import os
import shutil
import sys
import time
from pathlib import Path


PACKAGE = "com.befeast.tx10clock"
ACTIVITY = PACKAGE + "/.MainActivity"
PRIOR = "com.example.clock/com.example.clock.Main"
HOME = "com.example.launcher/com.example.launcher.Main"
REMOTE_APK = "/data/app/com.befeast.tx10clock-1/base.apk"
CONFIG = "/sdcard/Android/data/com.befeast.tx10clock/files/config.json"
STATUS = "/sdcard/Android/data/com.befeast.tx10clock/files/status.json"

# A complete 1x1 transparent PNG. The delivery verifier only trusts the PNG
# signature/IHDR dimensions; retaining it also proves screenshot bytes are kept
# in private evidence instead of being printed.
PNG = bytes.fromhex(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
    "0000000d49444154789c6360606060000000050001a5f645400000000049454e44"
    "ae426082"
)


def load_state(root):
    path = root / "state.json"
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    state = {
        "new_package": False,
        "foreground": PRIOR,
        "rebooted": False,
        "config_published": False,
        "counts": {},
    }
    save_state(root, state)
    return state


def save_state(root, state):
    (root / "state.json").write_text(
        json.dumps(state, sort_keys=True), encoding="utf-8")


def normalized(args):
    values = list(args)
    if len(values) == 3 and values[:2] == ["install", "-r"]:
        values[2] = "<local>"
    elif len(values) == 3 and values[0] == "pull":
        values[2] = "<local>"
    elif len(values) == 3 and values[0] == "push":
        values[1] = "<local>"
    return " ".join(values)


def maybe_fail(state, key):
    state["counts"][key] = state["counts"].get(key, 0) + 1
    wanted = os.environ.get("FAKE_ADB_FAIL_KEY", "")
    occurrence = int(os.environ.get("FAKE_ADB_FAIL_OCCURRENCE", "1"))
    return key == wanted and state["counts"][key] == occurrence


def status_json(state):
    epoch = int(os.environ.get("FAKE_DEVICE_EPOCH", "1700000000"))
    return json.dumps({
        "statusSchemaVersion": 1,
        "configSource": "external",
        "lastReloadRejected": False,
        "lastRejectReason": None,
        "bootLaunch": state["rebooted"],
        "updatedAtEpochMillis": epoch * 1000,
        "effectiveConfig": {
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
        },
    }, sort_keys=True) + "\n"


def main(argv):
    root_value = os.environ.get("FAKE_ADB_DIR", "")
    if not root_value:
        return 125
    root = Path(root_value)
    root.mkdir(parents=True, exist_ok=True)
    with (root / "calls.log").open("a", encoding="utf-8") as log:
        log.write("\t".join(argv) + "\n")
    args = list(argv)
    if len(args) >= 2 and args[0] == "-s":
        args = args[2:]
    key = normalized(args)
    state = load_state(root)
    if maybe_fail(state, key):
        save_state(root, state)
        sys.stderr.write("fixture command failed\n")
        return 1
    sleep_key = os.environ.get("FAKE_ADB_SLEEP_KEY", "")
    sleep_occurrence = int(os.environ.get("FAKE_ADB_SLEEP_OCCURRENCE", "1"))
    if key == sleep_key and state["counts"][key] == sleep_occurrence:
        save_state(root, state)
        time.sleep(float(os.environ.get("FAKE_ADB_SLEEP_SECONDS", "10")))

    out = ""
    binary = None
    rc = 0
    if args == ["get-state"]:
        out = "device\n"
    elif args == ["shell", "getprop", "ro.build.version.sdk"]:
        out = "29\n"
    elif args == ["shell", "getprop", "ro.product.cpu.abilist"]:
        out = "armeabi-v7a\n"
    elif args == ["shell", "pm", "list", "packages", PACKAGE]:
        if state["new_package"] or os.environ.get("FAKE_PRIOR_PACKAGE_PRESENT", "1") == "1":
            out = "package:" + PACKAGE + "\n"
    elif args == ["shell", "cmd", "package", "resolve-activity", "--brief",
                  "-a", "android.intent.action.MAIN", "-c",
                  "android.intent.category.HOME"]:
        out = "priority=0 preferredOrder=0 isDefault=true\n" + HOME + "\n"
    elif args == ["shell", "df", "/data"]:
        out = ("Filesystem 1K-blocks Used Available Use% Mounted on\n"
               "/dev/block/dm-0 5000000 100000 4000000 3% /data\n")
    elif args == ["shell", "date", "+%s"]:
        out = os.environ.get("FAKE_DEVICE_EPOCH", "1700000000") + "\n"
    elif args == ["shell", "getprop", "persist.sys.timezone"]:
        out = "UTC\n"
    elif args == ["shell", "dumpsys", "activity", "activities"]:
        component = state["foreground"]
        out = ("mResumedActivity: ActivityRecord{ fixture u0 " + component + " t1}\n"
               if component else "mResumedActivity: null\n")
    elif args == ["shell", "dumpsys", "package", PACKAGE]:
        if state["new_package"]:
            out = "  versionCode=1 minSdk=29 targetSdk=29\n  versionName=0.1.0\n"
        else:
            out = "  versionCode=9 minSdk=29 targetSdk=29\n  versionName=0.0.9\n"
    elif args == ["shell", "pm", "path", PACKAGE]:
        out = "package:" + REMOTE_APK + "\n"
    elif len(args) == 3 and args[0] == "pull":
        destination = Path(args[2])
        destination.parent.mkdir(parents=True, exist_ok=True)
        if args[1] == REMOTE_APK:
            source = Path(os.environ[
                "FAKE_RELEASE_APK" if state["new_package"] else "FAKE_PRIOR_APK"])
        elif args[1] == CONFIG:
            source = Path(os.environ["FAKE_PRIOR_CONFIG"])
        else:
            return 1
        shutil.copyfile(source, destination)
        out = "1 file pulled\n"
    elif len(args) == 3 and args[:2] == ["install", "-r"]:
        if Path(args[2]).name == "prior.apk":
            state["new_package"] = False
        else:
            state["new_package"] = True
        out = "Performing Streamed Install\nSuccess\n"
    elif args[:3] == ["shell", "test", "-f"] and args[3:] == [CONFIG]:
        rc = 0 if os.environ.get("FAKE_PRIOR_CONFIG_PRESENT", "1") == "1" else 1
    elif args[:4] == ["shell", "mkdir", "-p", "/sdcard/Android/data/com.befeast.tx10clock/files"]:
        pass
    elif len(args) == 3 and args[0] == "push":
        out = "1 file pushed\n"
    elif len(args) == 5 and args[:3] == ["shell", "mv", "-f"]:
        if args[4] == CONFIG:
            state["config_published"] = True
    elif args == ["shell", "am", "start", "-W", "-n", ACTIVITY]:
        state["foreground"] = ACTIVITY
        out = "Status: ok\nActivity: " + ACTIVITY + "\n"
    elif args == ["shell", "am", "force-stop", PACKAGE]:
        state["foreground"] = None
    elif args == ["shell", "input", "keyevent", "KEYCODE_HOME"]:
        state["foreground"] = HOME
    elif args == ["shell", "input", "keyevent", "KEYCODE_BACK"]:
        state["foreground"] = HOME
    elif args == ["shell", "cat", STATUS]:
        out = status_json(state)
    elif args == ["exec-out", "screencap", "-p"]:
        binary = PNG
    elif args == ["reboot"]:
        state["rebooted"] = True
        state["foreground"] = (
            ACTIVITY if os.environ.get("FAKE_REBOOT_AUTOSTART", "1") == "1" else HOME)
    elif args == ["wait-for-device"]:
        pass
    elif args == ["shell", "getprop", "sys.boot_completed"]:
        out = "1\n"
    elif args == ["shell", "pidof", PACKAGE]:
        out = "4242\n"
    elif args == ["shell", "logcat", "-d", "-v", "brief", "-t", "2000"]:
        out = os.environ.get("FAKE_LOGCAT", "I/Tx10Clock: fixture healthy\n")
    elif args == ["shell", "logcat", "-d", "-v", "brief", "--pid", "4242",
                  "-t", "2000"]:
        out = os.environ.get("FAKE_APP_LOGCAT", "I/Tx10Clock: fixture healthy\n")
    elif len(args) == 6 and args[:5] == ["shell", "am", "start", "-W", "-n"]:
        component = args[5]
        state["foreground"] = component
        out = "Status: ok\nActivity: " + component + "\n"
    else:
        sys.stderr.write("unexpected fixture command\n")
        rc = 1

    save_state(root, state)
    if binary is not None:
        sys.stdout.buffer.write(binary)
    else:
        sys.stdout.write(out)
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
