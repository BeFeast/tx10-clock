#!/usr/bin/env bash
#
# verify-visual-contract.sh — host-only integrity & schema gate for the
# committed TX10 Clock visual contract v0.1.0.
#
# It needs no Android SDK, device, or install step. It:
#   1. Confirms both contract trees exist and are byte-identical mirrors.
#   2. Recomputes every content hash and checks it against SHA256SUMS.
#   3. Validates manifest.json: schema, per-file digests, the package digest
#      (recomputed with the pinned algorithm), and the accepted-source digest.
#   4. Validates JSON schema/required keys, the exact 1280x720 composition
#      strings, and the binding forbidden-content assertions.
#   5. Validates SVG and PNG image dimensions are exactly 1280x720.
#   6. Proves the committed contract carries no Hebrew glyphs, no bundled
#      third-party/vendor font or image asset, and only the expected files.
#
# Exit status is non-zero if any check fails; every failure is reported.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

python3 - <<'PY'
import hashlib, json, os, re, sys, struct

TREES = [
    "design/contract/v0.1.0",
    "app/src/test/resources/visual-contract/v0.1.0",
]

# The accepted (private) design package this public contract was sanitized from.
# This digest is public provenance (recorded in the acceptance issue); the
# sanitized public package necessarily has its own, distinct package_sha256.
EXPECTED_SOURCE_DIGEST = \
    "6c506c95d8b49e79a819d05e8bc021777507cf45810eb97fa41741c719f78105"

VIEWPORT = [1280, 720]

EXPECTED_FILES = {
    "README.md", "SHA256SUMS", "manifest.json",
    "assets.json", "comparison.json", "content-fixtures.json",
    "geometry.json", "motion.json", "tokens.json",
    "reference-12h-1280x720.svg", "reference-24h-overlay.svg",
    "reference-12h-1280x720.png", "reference-24h-1280x720.png",
    "reference-burnin-minus8-1280x720.png",
    "reference-burnin-plus8-1280x720.png",
}
JSON_SPECS = ["assets.json", "comparison.json", "content-fixtures.json",
              "geometry.json", "motion.json", "tokens.json"]
SVGS = ["reference-12h-1280x720.svg", "reference-24h-overlay.svg"]
PNGS = ["reference-12h-1280x720.png", "reference-24h-1280x720.png",
        "reference-burnin-minus8-1280x720.png",
        "reference-burnin-plus8-1280x720.png"]
ALLOWED_EXT = {".json", ".svg", ".png", ".md"}  # SHA256SUMS is extension-less

errors = []
def check(cond, msg):
    if not cond:
        errors.append(msg)
    return cond

def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()

def png_dims(p):
    with open(p, "rb") as f:
        data = f.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", data[16:24])

for tree in TREES:
    if not check(os.path.isdir(tree), f"[tree] missing directory: {tree}"):
        continue

    present = {f for f in os.listdir(tree) if os.path.isfile(os.path.join(tree, f))}
    check(present == EXPECTED_FILES,
          f"[{tree}] file set mismatch: unexpected={sorted(present-EXPECTED_FILES)} "
          f"missing={sorted(EXPECTED_FILES-present)}")

    for f in present:
        ext = os.path.splitext(f)[1]
        check(f == "SHA256SUMS" or ext in ALLOWED_EXT,
              f"[{tree}] disallowed file extension (possible non-redistributable "
              f"asset): {f}")

    # --- SHA256SUMS: recompute every listed hash ---------------------------
    sums_path = os.path.join(tree, "SHA256SUMS")
    listed = {}
    if os.path.isfile(sums_path):
        for line in open(sums_path):
            line = line.rstrip("\n")
            if not line:
                continue
            digest, name = line.split("  ", 1)
            listed[name] = digest
        check("SHA256SUMS" not in listed,
              f"[{tree}] SHA256SUMS must not list itself")
        check(set(listed) == (EXPECTED_FILES - {"SHA256SUMS"}),
              f"[{tree}] SHA256SUMS coverage mismatch")
        for name, digest in listed.items():
            fp = os.path.join(tree, name)
            if check(os.path.isfile(fp), f"[{tree}] SHA256SUMS lists missing {name}"):
                check(sha256_file(fp) == digest,
                      f"[{tree}] hash mismatch for {name}")
    else:
        errors.append(f"[{tree}] missing SHA256SUMS")

    # --- manifest.json -----------------------------------------------------
    man_path = os.path.join(tree, "manifest.json")
    try:
        man = json.load(open(man_path))
    except Exception as e:
        errors.append(f"[{tree}] manifest.json unreadable: {e}")
        man = None
    if man is not None:
        check(man.get("schema_version") == 1, f"[{tree}] manifest schema_version != 1")
        check(man.get("contract_version") == "0.1.0",
              f"[{tree}] manifest contract_version != 0.1.0")
        check(man.get("viewport_px") == VIEWPORT,
              f"[{tree}] manifest viewport_px != {VIEWPORT}")
        check(man.get("state") == "accepted", f"[{tree}] manifest state != accepted")
        check(man.get("source_package_sha256") == EXPECTED_SOURCE_DIGEST,
              f"[{tree}] manifest source_package_sha256 != accepted-source digest")
        cf = man.get("content_files", {})
        check("manifest.json" not in cf and "SHA256SUMS" not in cf,
              f"[{tree}] content_files must exclude manifest.json and SHA256SUMS")
        check(set(cf) == (EXPECTED_FILES - {"manifest.json", "SHA256SUMS"}),
              f"[{tree}] content_files coverage mismatch")
        for name, digest in cf.items():
            fp = os.path.join(tree, name)
            if check(os.path.isfile(fp), f"[{tree}] content_files lists missing {name}"):
                check(sha256_file(fp) == digest,
                      f"[{tree}] content_files hash mismatch for {name}")
        # Recompute the package digest with the pinned algorithm.
        records = "".join(f"{cf[n]}  {n}\n" for n in sorted(cf))
        recomputed = hashlib.sha256(records.encode()).hexdigest()
        check(recomputed == man.get("package_sha256"),
              f"[{tree}] package_sha256 does not match recomputation")
        check(recomputed != EXPECTED_SOURCE_DIGEST,
              f"[{tree}] sanitized package digest unexpectedly equals source digest")
        ev = str(man.get("approval", {}).get("evidence", ""))
        check("#2" in ev, f"[{tree}] approval.evidence missing acceptance reference")

    # --- JSON specs: parse + required keys ---------------------------------
    specs = {}
    for f in JSON_SPECS:
        try:
            specs[f] = json.load(open(os.path.join(tree, f)))
        except Exception as e:
            errors.append(f"[{tree}] {f} invalid JSON: {e}")
    for f, obj in specs.items():
        check(obj.get("schema_version") == 1, f"[{tree}] {f} schema_version != 1")

    tok = specs.get("tokens.json", {})
    check(tok.get("viewport", {}).get("width_px") == 1280
          and tok.get("viewport", {}).get("height_px") == 720,
          f"[{tree}] tokens.json viewport != 1280x720")
    check(tok.get("colors", {}).get("background") == "#000000",
          f"[{tree}] tokens.json background not pure black")
    check(tok.get("colors", {}).get("accent") == "#FF9F0A",
          f"[{tree}] tokens.json accent not warm-orange #FF9F0A")

    check(specs.get("geometry.json", {}).get("viewport_px") == VIEWPORT,
          f"[{tree}] geometry.json viewport_px != {VIEWPORT}")
    check(specs.get("comparison.json", {}).get("ci_golden", {}).get("viewport_px") == VIEWPORT,
          f"[{tree}] comparison.json ci_golden viewport_px != {VIEWPORT}")

    cfx = specs.get("content-fixtures.json", {})
    check(cfx.get("twelve_hour", {}).get("main") == "10:09",
          f"[{tree}] 12h main string != 10:09")
    check(cfx.get("twelve_hour", {}).get("secondary") == "PM SUN, JUL 12 42",
          f"[{tree}] 12h secondary string != 'PM SUN, JUL 12 42'")
    check(cfx.get("twenty_four_hour", {}).get("main") == "22:09",
          f"[{tree}] 24h main string != 22:09")
    asrt = cfx.get("assertions", {})
    for key, want in {
        "main_seconds_forbidden": True,
        "secondary_seconds_required": True,
        "twelve_hour_ampm_required": True,
        "twenty_four_hour_ampm_forbidden": True,
        "calendar_grid_forbidden": True,
        "hebrew_date_forbidden": True,
    }.items():
        check(asrt.get(key) is want,
              f"[{tree}] content-fixtures assertion {key} != {want}")

    assets = specs.get("assets.json", {})
    check(assets.get("bundled_assets") == [],
          f"[{tree}] assets.json bundled_assets not empty (no asset may be bundled)")
    check(assets.get("bundled_fonts") == [],
          f"[{tree}] assets.json bundled_fonts not empty (no font may be bundled)")

    # --- SVG dimensions ----------------------------------------------------
    for f in SVGS:
        txt = open(os.path.join(tree, f), encoding="utf-8").read()
        check('width="1280"' in txt and 'height="720"' in txt
              and 'viewBox="0 0 1280 720"' in txt,
              f"[{tree}] {f} is not a 1280x720 SVG")

    # --- PNG dimensions + format ------------------------------------------
    for f in PNGS:
        dims = png_dims(os.path.join(tree, f))
        check(dims == (1280, 720), f"[{tree}] {f} is not a 1280x720 PNG (got {dims})")

    # --- No Hebrew glyphs in any text file (Unicode block U+0590..U+05FF) --
    hebrew = re.compile("[\u0590-\u05ff]")
    for f in EXPECTED_FILES - set(PNGS):
        txt = open(os.path.join(tree, f), encoding="utf-8", errors="replace").read()
        check(not hebrew.search(txt),
              f"[{tree}] {f} contains Hebrew glyphs")

# --- Byte-identical parity between the two trees --------------------------
if all(os.path.isdir(t) for t in TREES):
    a, b = TREES
    for f in sorted(EXPECTED_FILES):
        pa, pb = os.path.join(a, f), os.path.join(b, f)
        if os.path.isfile(pa) and os.path.isfile(pb):
            check(sha256_file(pa) == sha256_file(pb),
                  f"[parity] {f} differs between the two contract trees")

if errors:
    print("verify-visual-contract: FAIL")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print("verify-visual-contract: PASS")
print("  - both contract trees byte-identical, self-consistent (hashes + manifest)")
print("  - package digest recomputes; accepted-source provenance recorded")
print("  - JSON schema, 1280x720 composition strings, forbidden-content assertions OK")
print("  - SVG/PNG dimensions exactly 1280x720; no Hebrew glyphs; nothing bundled")
PY