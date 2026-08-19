# Apple TV (tvOS) port — plan of record

Status: v0 in progress on branch `feat/appletv-port`. Assessment that produced
these decisions: vault note `Dev/Areas/tx10-clock/planning/2026-08-19-appletv-port-assessment.md`
(private Management Home).

## Goal

The same clock, on an Apple TV, driven by the same strict external-config
contract, with a build/deploy loop the operator can run by hand on a corporate
MacBook (no SSH into the Mac; clone + one script, mirroring the proven
t3code-on-M4 workflow).

## Decisions of record

| Topic | Decision |
|---|---|
| Repo | Monorepo: tvOS port lives in `appletv/` on the existing repo. Primary remote for this work: `ssh://git@git.oklabs.uk/BeFeast/tx10-clock.git` (Forgejo). |
| Code layout | `appletv/Core` — SPM package `TX10ClockCore`, Foundation-only, `swift test`-able on Linux/macOS. `appletv/App` — UIKit sources (renderer, view, shell). |
| Design space | Renderer keeps Android's 1280x720 design coordinates verbatim; `ClockView` letterboxes to the real canvas (1920x1080 points) via CTM. Layout math ports unchanged. |
| Fonts | System font (San Francisco) via `UIFont.systemFont(ofSize:weight:)`. Pixel parity with Roboto is impossible either way; the visual contract is re-accepted on tvOS output (phase 2). |
| Config transport | **fastest wins:** `xcrun devicectl device copy to/from --domain-type appDataContainer` against the dev-signed install — the adb push/pull analog, zero in-app code. An in-app HTTP/Bonjour listener stays a future option and would require re-approving the "fully offline" posture. |
| Storage | `config.json`/`status.json` in `Library/Caches` (reachable by devicectl). tvOS may purge Caches, so the accepted last-known-good document is mirrored to `UserDefaults` and re-seeded on launch. |
| Boot start | No tvOS equivalent of `BOOT_COMPLETED`; requirement explicitly dropped by the operator (2026-08-19). After sleep the Apple TV resumes the foreground app; after power loss the app is launched with the remote. |
| Keep-awake | `isIdleTimerDisabled = true` plus a defensive 60 s re-toggle (API regressed in tvOS 11.2/14.6/26). Device-side: Sleep After = Never, auto-updates off. |
| Tick sound | New optional config key `tickSound: "off"\|"minute"\|"second"` (default `minute`), tvOS-only schema extension. Continuous `AVAudioEngine` output (silence between ticks keeps the Bluetooth soundbar's link streaming) + a gentle ~35 ms 880 Hz synthesized tick. Android ignores/rejects this key — configs are per-device. |
| Signing | Paid Apple Developer team (1-year development profile). `DEVELOPMENT_TEAM` comes from `TX10_TEAM_ID` env; never the free personal team used by the 2026-07 t3code build. Annual re-sign = rerun the build script. |
| Build host | Operator-run on the corporate MacBook M4 Pro: clone from Forgejo over SSH, `./appletv/scripts/m4-build-install.sh`. No CI for tvOS in v0. |

## Phases

### v0 — preview on device (this branch)
1. `TX10ClockCore`: faithful port of Json/ExternalConfig/ConfigStore/ClockConfig/
   ClockFormat/TimeSource/BurnInOffset + the ported unit-test matrix.
2. Renderer on CoreGraphics/CoreText + `ClockView` (CADisplayLink), app shell,
   `TickSound`, `KeepAwake`.
3. XcodeGen project + operator scripts + README runbook.
4. Operator runs Core tests + build + install on the Apple TV; iterate on
   compile/runtime fixes; visual sanity check by eye.

Definition of done for v0: Core test suite green on the M4, app installed and
foreground on the Apple TV, clock correct, config push/pull round-trip works,
tick audible at the configured cadence.

### Phase 2 — contract hardening (separate slice, after v0 is seen)
- Golden harness: XCTest + CGBitmapContext (or swift-snapshot-testing), the two
  golden-free invariants (8 px black margin, rigid whole-canvas shift) first;
  new reference frames recorded on a pinned simulator runtime.
- Operator re-acceptance of the tvOS visual contract (SF vs Roboto, 1920x1080).
- Screenshot path for the acceptance loop (pymobiledevice3 or XCUITest
  attachment; `simctl io screenshot` for simulator evidence).
- Decide whether tvOS joins the release/evidence machinery (signing evidence is
  necessarily weaker than the Android byte-identical claim — see assessment).

## Known deviations from the Android contract (documented, accepted)
- Zone validation accepts IANA identifiers only (bare `+05:00` offset forms are
  rejected; Foundation has no exact `ZoneId.of` equivalent for them).
- `tickSound` key exists only on tvOS.
- Caches purge risk is absorbed by the UserDefaults last-known-good mirror; the
  "a bad edit can never break a running clock" guarantee now also survives a
  storage purge.
- No boot receiver.

## Risks to watch
- FontMetrics sign convention and NSKern trailing-kern width: the two
  highest-probability silent-layout bugs; both are pinned by explicit rules in
  the renderer and should be eyeballed against the Android device on first run.
- `devicectl device copy` argument syntax shifts between Xcode releases; the
  push/pull scripts print the exact failing command for quick manual repair.
- First build on the M4 will surface cross-agent API mismatches; expected to be
  mechanical fixes.
