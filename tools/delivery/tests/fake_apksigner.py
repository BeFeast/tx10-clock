#!/usr/bin/env python3
import os
import sys


if sys.argv[1:4] != ["verify", "--print-certs", "-Werr"] or len(sys.argv) != 5:
    sys.exit(2)
fingerprint = os.environ["FAKE_CERT"].replace(":", "")
print("Signer #1 certificate SHA-256 digest: " + fingerprint)
