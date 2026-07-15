#!/usr/bin/env bash
#
# run-config-jvm-tests.sh — deterministic, Android-free verification of the
# external configuration core.
#
# The configuration core (Json, ExternalConfig, ConfigException, ConfigStore)
# and the renderer config model/mapping (ClockConfig) deliberately have NO
# Android dependency, so strict-ingestion, last-known-good, reload,
# atomic-replacement, status, and name-to-colour mapping behaviours can be
# compiled and exercised under a plain JVM with JUnit — no Android SDK, no
# device, and without touching the Gradle Android build.
#
# The same tests also run inside `./gradlew test`; this script is the offline
# entrypoint that needs neither Gradle nor the Android SDK.
#
# JUnit/Hamcrest jars are located via (in order): $JUNIT_JAR / $HAMCREST_JAR
# env overrides, then a scan of the Gradle module cache. No host path is
# hardcoded.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SRC="app/src/main/java/com/befeast/tx10clock"
TST="app/src/test/java/com/befeast/tx10clock"

# The Android-free sources and their tests — intentionally excludes the Android
# shell (MainActivity, ClockView, BootReceiver, ...), which needs android.jar.
# The pure burn-in offset engine rides along: it is likewise Android-free and
# its bounds/cadence/determinism contract is verified under the same plain JVM.
SOURCES=(
    "$SRC/ConfigException.java"
    "$SRC/Json.java"
    "$SRC/ExternalConfig.java"
    "$SRC/ConfigStore.java"
    "$SRC/ClockConfig.java"
    "$SRC/BurnInOffset.java"
    "$TST/ExternalConfigTest.java"
    "$TST/ConfigStoreTest.java"
    "$TST/ClockConfigTest.java"
    "$TST/BurnInOffsetTest.java"
)

TESTS=(
    com.befeast.tx10clock.ExternalConfigTest
    com.befeast.tx10clock.ConfigStoreTest
    com.befeast.tx10clock.ClockConfigTest
    com.befeast.tx10clock.BurnInOffsetTest
)

find_jar() {
    # $1 = env override value, $2 = filename glob, $3 = optional -path filter.
    local override="$1" glob="$2" pathfilter="${3:-}" hit root
    if [ -n "$override" ] && [ -f "$override" ]; then
        printf '%s' "$override"; return 0
    fi
    # Search the Gradle module cache and any local Gradle distribution libs.
    for root in "${GRADLE_USER_HOME:-$HOME/.gradle}/caches" "$HOME/.gradle/wrapper" \
                "$HOME"/gradle-*/lib; do
        [ -d "$root" ] || continue
        if [ -n "$pathfilter" ]; then
            hit="$(find "$root" -path "$pathfilter" -name "$glob" -type f 2>/dev/null \
                    | sort -V | tail -1 || true)"
        else
            hit="$(find "$root" -name "$glob" -type f 2>/dev/null | sort -V | tail -1 || true)"
        fi
        if [ -n "$hit" ]; then printf '%s' "$hit"; return 0; fi
    done
    return 1
}

# assertThrows requires JUnit >= 4.13; prefer the plain junit:junit coordinate
# over shaded copies (e.g. Robolectric's junit-4.11.x, which lacks it).
JUNIT_JAR="$(find_jar "${JUNIT_JAR:-}" 'junit-4.13*.jar' '*/junit/junit/*')" \
    || JUNIT_JAR="$(find_jar "${JUNIT_JAR:-}" 'junit-4.13*.jar')" \
    || { echo "run-config-jvm-tests: JUnit >= 4.13 jar not found (set JUNIT_JAR=...)" >&2; exit 2; }
HAMCREST_JAR="$(find_jar "${HAMCREST_JAR:-}" 'hamcrest-core-*.jar')" \
    || { echo "run-config-jvm-tests: hamcrest-core jar not found (set HAMCREST_JAR=...)" >&2; exit 2; }

echo "==> JUnit:    $JUNIT_JAR"
echo "==> Hamcrest: $HAMCREST_JAR"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

echo "==> Compiling config core + tests (plain javac, no Android)"
javac -d "$OUT" -cp "$JUNIT_JAR:$HAMCREST_JAR" "${SOURCES[@]}"

echo "==> Running JUnit"
java -cp "$OUT:$JUNIT_JAR:$HAMCREST_JAR" org.junit.runner.JUnitCore "${TESTS[@]}"

echo "run-config-jvm-tests: PASS — config core verified under a plain JVM"
