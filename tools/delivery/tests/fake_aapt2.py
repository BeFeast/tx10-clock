#!/usr/bin/env python3
import os
import sys


if sys.argv[1:3] != ["dump", "badging"] or len(sys.argv) != 4:
    sys.exit(2)
name = os.path.basename(sys.argv[3])
if name == "prior.apk":
    version_code, version_name = "9", "0.0.9"
else:
    version_code, version_name = "1", "0.1.0"
print("package: name='com.befeast.tx10clock' versionCode='%s' versionName='%s'" %
      (version_code, version_name))
