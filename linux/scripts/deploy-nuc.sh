#!/usr/bin/env bash
# Deploy the linux kiosk jar to a NUC-style kiosk host.
#
# Usage: ./scripts/deploy-nuc.sh [user@host]
#   default target: $TX10CLOCK_SSH, else root@nuc
#   optional jump:  $TX10CLOCK_SSH_HOP (jar is copied through it first)
#
# After a successful deploy the remote java process must be restarted to pick
# up the new jar (reboot the host, or kill the process and re-run the kiosk
# start script).
set -euo pipefail

HOST=${1:-${TX10CLOCK_SSH:-root@nuc}}
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAR="$REPO_ROOT/linux/build/libs/tx10-clock-linux.jar"

(cd "$REPO_ROOT" && ./gradlew :linux:jar -q)

echo "deploying $(basename "$JAR") -> $HOST:/root/"
if [ -n "${TX10CLOCK_SSH_HOP:-}" ]; then
    scp -q "$JAR" "$TX10CLOCK_SSH_HOP:/tmp/tx10-clock-linux.jar"
    ssh "$TX10CLOCK_SSH_HOP" "scp -q /tmp/tx10-clock-linux.jar $HOST:/root/"
else
    scp -q "$JAR" "$HOST:/root/"
fi

echo "deployed. restart the remote clock process to pick it up."
