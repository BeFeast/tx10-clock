# Release-Evidence Contract

A deterministic, public-safe, versioned **provenance record** for one signed
tx10-clock GitHub Release. The pinned, operator-gated release workflow
([`.github/workflows/release.yml`](../../.github/workflows/release.yml)) must
produce a release-evidence document and validate it **before** it publishes the
release, so every claim in the release notes is backed by machine-checkable
evidence.

This is distinct from the delivery
[receipt](../receipt/README.md): the receipt records the *delivery lifecycle*
(built → published → delivered → rolled_back); this evidence records how the
artifact was *produced* — source, toolchain, digests, and verification.

## Layout

- `schema/evidence-v1.schema.json` — JSON Schema (draft 2020-12) for contract
  version `1.0.0`. Generated from the validator via `--emit-schema`; a test
  fails if the two ever drift.
- `fixtures/valid/` — deterministic positive fixtures.
- `fixtures/invalid/` — deterministic negative fixtures, one per rejection
  class.

The pinned toolchain the evidence enforces is the single source of truth in the
validator; it is rendered to [`release/toolchain.lock.json`](../toolchain.lock.json)
via `--emit-lock` (also drift-tested). The validator and tests live in
[`tools/release-evidence/`](../../tools/release-evidence/).

## Contract summary (v1.0.0)

Every field is required at every level; unknown fields are rejected.

| Section | Records |
|---|---|
| `source` | repository, 40-hex reviewed commit, `vX.Y.Z` release tag |
| `toolchain` | pinned JDK / AGP / Gradle (+ distribution SHA-256) / platform / build-tools / command-line-tools, plus the verification/locking/no-dynamic/no-SNAPSHOT/actions-SHA-pinned policy flags |
| `sdk_packages` | resolved SDK package paths, revisions, and digests |
| `artifact` | bare `.apk` filename, lowercase-hex SHA-256, size in bytes |
| `package` | Android application id, versionName, versionCode |
| `native_libraries` | proven absence: `present:false`, empty `entries` |
| `signing` | public certificate SHA-256 fingerprint + **private-store reference only** (`infisical://…`/`vaultwarden://…`) + `apksigner verify --print-certs -Werr` result |
| `reproducibility` | the two independent clean-environment build digests and whether they were compared / byte-identical |
| `ci` | provider, workflow file, public run URL |

### Enforced semantics

- The `toolchain` values must equal the **pinned release toolchain** exactly
  (no dynamic or SNAPSHOT inputs); the policy flags must all hold.
- `sdk_packages` must include the pinned `platforms;android-29`,
  `build-tools;36.0.0`, and `cmdline-tools;19.0` packages with resolved
  revisions and digests.
- `package.version_name` must equal `source.release_tag` without its `v`.
- `native_libraries.present` must be `false` and `entries` empty.
- `signing.apksigner_verified` must be `true` and `signing.apksigner_command`
  exactly `apksigner verify --print-certs -Werr`.
- Byte reproducibility may be **claimed only after the comparison passes**:
  `byte_identical: true` requires `compared: true` and all unsigned build
  digests equal. The separately signed release APK has its own independent
  `artifact.sha256`. A comparison that ran but did not match is recorded
  honestly (`compared: true`, `byte_identical: false`).

Every string value is additionally screened against hygiene rules: local
absolute paths, private/LAN endpoints, and credential material are rejected, and
the validator never echoes an offending value into its output. Signing key
material and passwords therefore cannot enter this record — only public
fingerprints and private-store references.

## Validating an evidence document

```sh
python3 tools/release-evidence/validate_release_evidence.py path/to/evidence.json
```

Prints a stable machine-readable JSON verdict on stdout. Exit codes: `0` valid,
`1` invalid, `2` usage/IO error.

## Running the host-only tests

```sh
bash tools/release-evidence/run-release-evidence-tests.sh
```

Needs only Python 3 and bash — no Android SDK, network, signing key, or device.
