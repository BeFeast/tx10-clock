# Releasing a signed TX10 Clock APK

This is the runbook for cutting a signed, evidence-backed GitHub Release of the
TX10 Clock. It is an **operator delivery step**: it produces an installable,
signed artifact and publishes it. Nothing here runs automatically on a branch
push — a release happens only when an operator pushes a SemVer tag and the SDK
gate is open.

The release inputs are pinned once, in
[`release/toolchain.lock.json`](../release/toolchain.lock.json) (the single
source of truth, rendered from the release-evidence validator). The build files,
CI, and the release workflow all agree with that lock, enforced by
[`scripts/check-release-pins.sh`](../scripts/check-release-pins.sh).

## Pinned release inputs

| Input | Pin |
|---|---|
| JDK | 17 (Temurin) |
| Android Gradle Plugin | 9.2.1 (`build.gradle`) |
| Gradle | 9.4.1 + distribution SHA-256 (`gradle/wrapper/gradle-wrapper.properties`) |
| Android platform | 29 (compile/target/min = the TX10 runtime) |
| SDK Build Tools | 36.0.0 (`app/build.gradle`, workflows) |
| Command-line Tools | build `14742923` |
| Dependencies | strict committed `app/gradle.lockfile` + SHA-256 `gradle/verification-metadata.xml`; no dynamic selectors, SNAPSHOTs, or `mavenLocal()` |
| Signing resolver | Infisical CLI 0.43.96 + pinned archive SHA-256 |
| GitHub Actions | every `uses:` pinned to a full commit SHA |

## Prerequisites (operator, one-time)

1. **SDK licence gate.** Set the repository variable
   `ANDROID_SDK_LICENSE_ACCEPTED=true` only on a runner image that already has
   the Android SDK licences accepted. Licence acceptance is never automated in
   this repo (`scripts/check-no-sdk-license-automation.sh` enforces that).
2. **Signing material in a private store.** Put the signing keystore and its
   passwords in Infisical — **never in git, never in GitHub secrets directly**.
   GitHub holds only the machine-identity credentials that let the runner read
   the store: secrets `INFISICAL_CLIENT_ID`, `INFISICAL_CLIENT_SECRET`;
     variables `INFISICAL_API_URL`, `INFISICAL_PROJECT`, `RELEASE_SIGNING_KEY_REFERENCE`
     (an `infisical://…` path). The store holds `TX10_RELEASE_KEYSTORE_B64`,
     `TX10_RELEASE_KEYSTORE_PASSWORD`, `TX10_RELEASE_KEY_ALIAS`,
     `TX10_RELEASE_KEY_PASSWORD`.
   The resolver also supports `vaultwarden://…` on a private runner that
   provides a pinned `bw` CLI and `BW_SESSION`; the committed GitHub-hosted
   release workflow installs and uses the checksum-pinned Infisical CLI.
3. **Documented signer.** Set variable `RELEASE_SIGNING_CERT_SHA256` to the
   expected signing-certificate SHA-256 fingerprint. The workflow refuses to
   sign with any other key and re-checks it after signing.

Signing material and passwords resolve only from the private store, are masked
with `::add-mask::`, are written only under `$RUNNER_TEMP`, and never enter git
or the logs (see [`scripts/release-resolve-signing.sh`](../scripts/release-resolve-signing.sh)).

## Cutting the release

1. Confirm `main` is at the exact reviewed commit and green in CI (the
   `host-checks` job plus the operator-gated `verify` job — the same clean
   build, static/unit, and offscreen golden-verifier gates the release re-runs).
2. Tag that commit and push the tag:

   ```sh
   git tag -a v0.1.0 -m "TX10 Clock v0.1.0" <reviewed-sha>
   git push origin v0.1.0
   ```

   The tag identifies the exact reviewed commit; the SemVer tag pattern
   (`v[0-9]+.[0-9]+.[0-9]+`) is what triggers
   [`.github/workflows/release.yml`](../.github/workflows/release.yml).
3. The release workflow then, gated on the SDK acceptance:
   - **builds twice** in two independent clean environments (`clean-a`,
     `clean-b`), running the full `verify-outcome.sh` gates in each, and
     **compares** the two unsigned APK digests. Byte reproducibility is claimed
     only if the comparison matches.
   - **aligns the signer before signing**: it reads the keystore certificate’s
     SHA-256 and aborts unless it equals `RELEASE_SIGNING_CERT_SHA256`.
   - `zipalign`s, signs, and runs `apksigner verify --print-certs -Werr`,
     re-asserting the certificate fingerprint.
   - **assembles and validates release evidence** (source commit, resolved SDK
     package revisions/digests, APK SHA-256, package/version, proven absence of
     native libraries, signing-certificate fingerprint, reproducibility result,
     CI run) against
     [`release/evidence/schema/evidence-v1.schema.json`](../release/evidence/schema/evidence-v1.schema.json).
   - **publishes** the SemVer GitHub Release with the signed APK,
     `release-evidence.json`, and `SHA256SUMS`.

If the two builds are not byte-identical, the workflow still publishes but
records `byte_identical: false` — reproducibility is claimed **only after the
comparison passes**, never assumed.

## Independently verifying a published asset

Anyone can download the release asset and evidence and check them without
trusting the CI logs:

```sh
gh release download v0.1.0 --repo BeFeast/tx10-clock \
    --pattern 'tx10-clock-*-release.apk' --pattern 'release-evidence.json'

# Offline: SHA-256, size, no native libraries, and the evidence contract.
# Manifest/version and signing-certificate checks additionally use
# build-tools;36.0.0 (aapt2 + apksigner) when ANDROID_SDK_ROOT is set.
scripts/inspect-release-apk.sh tx10-clock-v0.1.0-release.apk release-evidence.json
```

The inspector recomputes the SHA-256, confirms the size, proves the archive has
no `lib/**` / `*.so` entries, validates the evidence against the contract, and —
with the SDK present — checks the manifest package/version, the absence of
native code, and that `apksigner verify --print-certs -Werr` yields the
documented certificate fingerprint.

## Reproducing the build yourself

To independently reproduce byte-for-byte from the tag in a clean environment:

```sh
git clone https://github.com/BeFeast/tx10-clock && cd tx10-clock
git checkout v0.1.0
# JDK 17 + an SDK with platforms;android-29 and build-tools;36.0.0 installed via
# Command-line Tools build 14742923 (see the pin table above).
./gradlew --no-daemon clean :app:assembleRelease
sha256sum app/build/outputs/apk/release/app-release-unsigned.apk
```

Compare the digest against a second clean build (a different host/runner). Claim
byte reproducibility only once the two unsigned digests match. The signed
artifact’s digest is recorded in `release-evidence.json`.

> **Toolchain note.** This release pins the toolchain forward (AGP 7.4.2 → 9.2.1,
> Gradle 7.6.4 → 9.4.1, Build Tools 29.0.3 → 36.0.0) to the operator-approved
> release inputs. The Gradle/AGP execution path is operator-gated (Android SDK
> licence acceptance) and cannot be exercised outside that gate; the two
> independent clean builds above are the reproducibility check that confirms the
> pinned toolchain builds the reviewed source before any byte-reproducibility
> claim is made.
