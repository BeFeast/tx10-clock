# TX10 Clock — Visual Contract v0.1.0

Accepted, binding visual contract for the TX10 Clock renderer. This package is
the executable design source that the Android `Canvas` renderer and its host
golden tests reproduce. It is a clean-room, original composition; it bundles no
third-party or vendor-proprietary fonts or image assets and does not reproduce
any third-party product's exact clock geometry.

## Binding composition

- Canvas is exactly `1280×720`, opaque sRGB, pure black background.
- One composition: a large analog face on the left, a maximally legible digital
  clock on the right.
- Reference state is `2026-07-12T22:09:42+03:00` (`Asia/Jerusalem`).
- The main digital line is exactly `10:09`.
- The smaller second line is exactly `PM SUN, JUL 12 42`; AM/PM, the compact
  English date, and the digital seconds share one line.
- The analog second hand and the digital `42` use the restrained warm-orange
  accent (`#FF9F0A`).
- No calendar grid and no Hebrew date. No weather, alarm, or settings surface.
- The whole composition translates as one unit for burn-in protection; the
  package includes the center frame and both `±8 px` corner-extreme frames.

## Files

| File | Role |
|---|---|
| `reference-12h-1280x720.svg` | Editable deterministic vector source and exact geometry reference |
| `reference-12h-1280x720.png` | Binding center-position raster (12-hour reference state) |
| `reference-24h-overlay.svg` | Deterministic 24-hour reflow overlay source |
| `reference-24h-1280x720.png` | Binding 24-hour reflow raster |
| `reference-burnin-plus8-1280x720.png` | Derived `(+8,+8)` whole-composition frame |
| `reference-burnin-minus8-1280x720.png` | Derived `(-8,-8)` whole-composition frame |
| `tokens.json` | Binding colors, typography intent, stroke widths, and timing state |
| `geometry.json` | Binding anchors/regions and burn-in transform |
| `content-fixtures.json` | Exact 12-hour/24-hour strings and forbidden-content assertions |
| `motion.json` | Sweep, digital tick, and burn-in cadence contract |
| `comparison.json` | CI-golden and live-device comparison tolerances |
| `assets.json` | Asset/font bundling policy (nothing bundled) and exclusions |
| `manifest.json` | Immutable content inventory and content digests |
| `SHA256SUMS` | File-level integrity list |

## Implementation boundary

The Android implementation reproduces this package with native `Canvas`
drawing. The SVG is a geometry specification, not production code. The runtime
font is the Android system `sans-serif`, selected deliberately; no font or
image asset is bundled by this package.

## Provenance & acceptance

This is the accepted contract, version `0.1.0`. It is the public, sanitized
form of the operator-accepted design package; the sanitization removed only
private planning metadata and prose and preserved every binding artifact
(geometry, tokens, motion, comparison tolerances, fixtures, and all reference
renders) byte-for-byte. The originating accepted-package digest is recorded in
[`manifest.json`](manifest.json) under `source_package_sha256`, and acceptance
is recorded in issue `BeFeast/tx10-clock#2`.

Integrity is self-checking: `manifest.json` pins the SHA-256 of every content
file and the package digest, `SHA256SUMS` lists the same file hashes, and
[`scripts/verify-visual-contract.sh`](../../../scripts/verify-visual-contract.sh)
recomputes them along with JSON schema and image-dimension checks. That verifier
is host-only; it needs no Android SDK, device, or install step.
