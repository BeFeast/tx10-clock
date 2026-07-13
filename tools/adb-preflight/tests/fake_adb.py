#!/usr/bin/env python3
"""fake_adb — the injectable ADB double for adb-preflight tests.

Behaviour is driven entirely by a scenario directory named in the
FAKE_ADB_DIR environment variable:

  spec.json   maps a canonical command key to a response:
                key:   the argument vector AFTER any leading `-s <serial>`,
                       joined with single spaces
                value: {"rc": int, "stdout": str, "stderr": str,
                        "sleep": seconds (optional)}
  calls.log   every invocation's full argv (tab-separated, one per line)
              is appended here so tests can prove exactly which commands
              were executed.

Unknown commands fail with rc 1 — the double never silently succeeds, so a
preflight that drifted outside its allowlisted query set breaks the tests.
No real device, ADB server, or Android SDK is involved.
"""

import json
import os
import sys
import time


def main(argv):
    scenario_dir = os.environ.get("FAKE_ADB_DIR")
    if not scenario_dir:
        sys.stderr.write("fake_adb: FAKE_ADB_DIR is not set\n")
        return 125

    with open(os.path.join(scenario_dir, "calls.log"), "a", encoding="utf-8") as log:
        log.write("\t".join(argv) + "\n")

    args = list(argv)
    if len(args) >= 2 and args[0] == "-s":
        args = args[2:]
    key = " ".join(args)

    with open(os.path.join(scenario_dir, "spec.json"), encoding="utf-8") as handle:
        spec = json.load(handle)

    response = spec.get(key)
    if response is None:
        sys.stderr.write("fake_adb: unexpected command: %s\n" % key)
        return 1

    if response.get("sleep"):
        time.sleep(response["sleep"])
    sys.stdout.write(response.get("stdout", ""))
    sys.stderr.write(response.get("stderr", ""))
    return response.get("rc", 0)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
