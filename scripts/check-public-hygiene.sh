#!/usr/bin/env bash
#
# Public-path hygiene gate. Scans tracked files for content that must never
# appear in this public repository: private host paths, the operator's vault
# name, LAN / device endpoints, secrets, and committed build outputs.
#
# The scan intentionally skips this script itself, which necessarily contains
# the forbidden patterns as detection rules.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SELF="scripts/check-public-hygiene.sh"

# Content patterns that must not appear in any tracked, public file.
content_patterns=(
    'Obsidian Vault'                      # operator's private vault
    '10\.10\.[0-9]{1,3}\.[0-9]{1,3}'      # LAN / device endpoints
    '/home/[a-z_][a-z0-9_-]*/'            # absolute host home paths
    '-----BEGIN [A-Z ]*PRIVATE KEY'       # private keys
    'ghp_[A-Za-z0-9]{20,}'                # GitHub personal access tokens
    'github_pat_[A-Za-z0-9_]{20,}'        # GitHub fine-grained tokens
    'xox[baprs]-[A-Za-z0-9-]{10,}'        # Slack tokens
    'AKIA[0-9A-Z]{16}'                    # AWS access key ids
    'AIza[0-9A-Za-z_-]{35}'               # Google API keys
    '\[\[Dev/'                            # Obsidian vault wikilinks (planning notes)
    '^related_to:'                        # Obsidian frontmatter backlink key
    '[Aa]pproval pending'                 # private acceptance/discussion trail
    '[Dd]irectional interview'            # private interview trail
    '\binterview\b'                       # private interview/discussion trail
    'awaiting[[:space:]]+[A-Za-z]+[[:space:]]+acceptance'  # private approval trail
    'Статус'                              # Cyrillic status label from private notes
)

# Tracked paths that should never exist in the repo (build output / local files).
path_patterns=(
    '(^|/)local\.properties$'
    '(^|/)build/'
    '\.apk$'
    '\.aab$'
    '\.(keystore|jks)$'
    '(^|/)\.gradle/'
)

fail=0
tracked="$(git ls-files | grep -vF "$SELF" || true)"

for pat in "${content_patterns[@]}"; do
    hits="$(printf '%s\n' "$tracked" | tr '\n' '\0' \
        | xargs -0 -r grep -EnI "$pat" 2>/dev/null || true)"
    if [ -n "$hits" ]; then
        echo "check-public-hygiene: FAIL — forbidden content /$pat/:" >&2
        printf '%s\n' "$hits" >&2
        fail=1
    fi
done

for pat in "${path_patterns[@]}"; do
    hits="$(git ls-files | grep -E "$pat" || true)"
    if [ -n "$hits" ]; then
        echo "check-public-hygiene: FAIL — forbidden tracked path /$pat/:" >&2
        printf '%s\n' "$hits" >&2
        fail=1
    fi
done

if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "check-public-hygiene: PASS — no private paths, endpoints, secrets, or build outputs tracked"
