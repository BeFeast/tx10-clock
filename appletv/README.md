# TX10 Clock — Apple TV (tvOS) port

Operator runbook for building the tvOS clock from source and installing it on
an Apple TV. Everything runs on a Mac with Xcode; no Homebrew, no CI, no
account provisioning beyond a signed-in Apple ID. The build tooling
(XcodeGen) is auto-downloaded into `appletv/.tools/` by the build script.

Layout:

- `Core/` — SwiftPM package `TX10ClockCore` (Foundation-only port of the
  Android config/format/time logic; `swift test` runs anywhere, including Linux).
- `App/Sources/` — UIKit app target `TX10ClockTV`
  (bundle id `com.befeast.tx10clock.tv`, tvOS 17.0+).
- `project.yml` — XcodeGen spec; `TX10ClockTV.xcodeproj` is generated, never
  committed.
- `scripts/` — the three operator scripts described below.

## Prerequisites

- macOS with **Xcode 26.x** installed and the license accepted
  (`sudo xcodebuild -license accept`).
- Apple ID signed in under Xcode > Settings > Accounts, member of a **paid**
  Apple Developer team. Note the ten-character team id
  (developer.apple.com > Account > Membership).
- Apple TV running **tvOS 17 or newer** (older tvOS is invisible to
  `devicectl`), on the same LAN as the Mac.
- One-time wireless pairing: on the Apple TV open
  **Settings > Remotes and Devices > Remote App and Devices**, then on the Mac
  open **Xcode > Window > Devices and Simulators**, select the Apple TV, click
  **Pair**, and enter the code shown on the TV.

## Get the source

Clone via SSH from the Forgejo mirror and switch to the port branch:

```sh
git clone ssh://git@git.oklabs.uk/BeFeast/tx10-clock.git
cd tx10-clock
git checkout feat/appletv-port
```

## Build and install (happy path)

```sh
export TX10_TEAM_ID=XXXXXXXXXX   # paid team id
export TX10_ATV_ID=<device-uuid> # from: xcrun devicectl list devices
./appletv/scripts/m4-build-install.sh
```

The script is idempotent and does, in order: Core `swift test` (skip with
`--skip-tests`), ensure/auto-download XcodeGen, `xcodegen generate`, a signed
Release `xcodebuild` for tvOS, then `devicectl` install and launch on
`$TX10_ATV_ID`. Set `APP_ONLY=1` to stop after the build. Without
`TX10_ATV_ID` it lists paired devices and tells you which column to copy.

## Runtime configuration

Same strict contract as the Android app (see `docs/external-config.md`), with
the files living in the app data container at `Library/Caches/config.json`
(input) and `Library/Caches/status.json` (output). Caches is purgeable, so the
app also mirrors the last accepted config into UserDefaults — a purge never
loses the last-known-good.

```sh
# Push a config (validates <= 8 KiB and JSON well-formedness first):
./appletv/scripts/config-push.sh my-config.json            # uses $TX10_ATV_ID
./appletv/scripts/config-push.sh my-config.json <device-uuid>

# Read back what the app actually applied:
./appletv/scripts/config-pull-status.sh
```

The app reloads `config.json` when it comes to the foreground. Rejected
documents (unknown keys, duplicate keys, wrong types, out-of-range values,
oversized) leave the last-known-good in force; the coarse reason appears in
`status.json`.

### tvOS-only key: `tickSound`

On top of the Android schema, the tvOS port accepts one extra optional key:

| Key | Type | Values | Default |
|---|---|---|---|
| `tickSound` | string | `"off"` \| `"minute"` \| `"second"` | `"minute"` |

A gentle tick at the chosen cadence; the default per-minute tick exists to
keep a Bluetooth soundbar from auto-sleeping on silence. `"off"` is fully
silent, `"second"` ticks every second. Any other value rejects the document
(same strictness as every other key).

```json
{
  "schemaVersion": 1,
  "use24Hour": true,
  "timeZone": "Europe/Berlin",
  "tickSound": "minute"
}
```

## Troubleshooting

- **Signing errors / "requires a development team"** — sign in via Xcode >
  Settings > Accounts with the Apple ID that belongs to `$TX10_TEAM_ID`. If
  it persists, open `appletv/TX10ClockTV.xcodeproj` once and read the concrete
  error under Signing & Capabilities.
- **"tvOS SDK not installed"** — fresh Xcode ships without device platforms:
  `xcodebuild -downloadPlatform tvOS` (one-time, several GB).
- **App installed but won't launch after first install** — launch it once from
  the Apple TV home screen and accept the developer trust prompt, then rerun
  the script (or the launch command it prints).
- **Free personal team vs paid team** — a free team (like the one used for the
  t3code iPhone build) signs with 7-day profiles and tight device limits; the
  clock would die weekly. Use the paid team id for `TX10_TEAM_ID`. If you do
  not know it, it also appears in the `listTeams` section of an
  `xcodebuild -allowProvisioningUpdates` log.
- **Apple TV not listed by `devicectl`** — it must run tvOS 17+ (older devices
  report unsupported pairing), be awake, on the same LAN, and paired via the
  Devices and Simulators window (see Prerequisites).
- **`devicectl device copy` rejects the flags** — the syntax has shifted
  between Xcode releases. Both config scripts print the exact failing command;
  compare it with `xcrun devicectl device copy to --help` and adjust.

## Annual re-sign

Paid-team development provisioning expires after one year. The ritual is
simply rerunning the same script on the Mac with the same two exports — it
re-signs, reinstalls, and relaunches in place. Nothing else to do.
