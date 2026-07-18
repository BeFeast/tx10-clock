# AGENTS.md — TX10 Clock

Project ID: `85b0356b-b775-4762-a03a-a98239526dca`
Management Home: `Dev/Areas/tx10-clock` in the operator's synced Obsidian vault.

The Management Home is outside this repository. Never hardcode or commit its absolute host path. Executable product requirements live in this repo and assigned GitHub issues; private exploration/discussion is not an implementation contract unless distilled into an approved repo document or issue.

## Pull Request Contract

- Work only on the assigned issue.
- Open a ready PR and include verification evidence.
- Do not deploy, mutate target devices/runtimes, or operate Maestro.

## Hygiene

- No secrets, private paths, LAN addresses, raw design packages, logs, build outputs, or user data.

## Cursor Cloud specific instructions

This is a single-module Android TV app (pure Java + Android `Canvas`, package
`com.befeast.tx10clock`). Standard build/test/run commands live in `README.md`;
the canonical end-to-end check is `scripts/verify-outcome.sh`. Notes below are
the non-obvious environment caveats.

- Toolchain is pinned (see `release/toolchain.lock.json`): JDK 17, Gradle 9.4.1
  (wrapper, SHA-verified), Android SDK `platforms;android-29` +
  `build-tools;36.0.0`. JDK 17 is the VM default and the SDK lives at
  `~/android-sdk`, wired to Gradle via an untracked `local.properties`
  (`sdk.dir=`). Do not build with the base image's JDK 21 — AGP 9.2.1 needs 17.
- Dependency verification/locking is STRICT (`gradle/verification-metadata.xml`,
  `app/gradle.lockfile`). New/updated dependencies fail the build until both the
  lockfile and verification metadata are regenerated — expect resolution
  failures, not silent upgrades.
- No `/dev/kvm` in this VM, so an Android emulator/device is not available.
  Verify the renderer visually with the offscreen golden harness instead: it
  runs the real `ClockRenderer` under Robolectric `graphicsMode=NATIVE` into a
  1280x720 `ARGB_8888` bitmap (`ClockRendererRenderTest`, part of
  `./gradlew test`). On mismatch it writes actual/expected/diff PNGs to
  `app/build/golden-output/`.
- `scripts/verify-outcome.sh` passes `-Pandroid.aapt2FromMavenOverride` at the
  pinned build-tools `aapt2`; run it (not raw `assembleRelease`) for the full
  documented outcome checks.
- The Android-free config core has two independent entry points: inside
  `./gradlew test`, and offline via `scripts/run-config-jvm-tests.sh` (plain
  `javac`/JUnit). The offline script locates JUnit/Hamcrest from the Gradle
  module cache, so run a Gradle build at least once first to populate it.
- Host-only Python contract/hygiene checks (`tools/*/run-*-tests.sh`,
  `scripts/check-*.sh`) need only Python 3 + bash — no JDK, SDK, network, or
  device.
- The `config/fixtures/**` documents follow the Python config-validator schema,
  which is distinct from the Java `ExternalConfig` runtime schema
  (`schemaVersion:1`, `use24Hour`, `digitalColor`, ...). Don't feed one to the
  other.
