#!/usr/bin/env bash
#
# release-resolve-signing.sh — resolve the release signing material from the
# private Infisical/Vaultwarden stores into masked, runner-local state.
#
# Contract (acceptance: signing material and passwords resolve only from private
# Infisical/Vaultwarden references and never enter git or logs):
#
#   * The ONLY release secrets GitHub holds are the machine-identity credentials
#     that let the runner read the private store. The keystore and its passwords
#     live in the store and are pulled here at runtime.
#   * Every resolved value is registered with ::add-mask:: before use so it can
#     never appear in the logs, and nothing is ever echoed.
#   * The keystore is written only under $RUNNER_TEMP with 0600 perms — never
#     into the workspace, and therefore never into git.
#
# This runs only on the operator-gated release runner; it is intentionally not
# exercisable from a developer checkout (there is no key material to resolve).
set -euo pipefail

: "${RUNNER_TEMP:?RUNNER_TEMP must be set (GitHub-hosted runner env)}"
: "${GITHUB_ENV:?GITHUB_ENV must be set (GitHub Actions env)}"
: "${SIGNING_KEY_REFERENCE:?RELEASE_SIGNING_KEY_REFERENCE var is required}"

die() { echo "release-resolve-signing: $*" >&2; exit 1; }

# The reference must be a private-store URI only — the same contract the
# release-evidence validator enforces. No inline material, no other schemes.
case "$SIGNING_KEY_REFERENCE" in
    infisical://*|vaultwarden://*) : ;;
    *) die "signing key reference must be an infisical:// or vaultwarden:// URI" ;;
esac

KEYSTORE_PATH="$RUNNER_TEMP/release.keystore"

# mask <value> — register a value for log redaction, never printing it.
mask() { printf '::add-mask::%s\n' "$1"; }

# put <NAME> <value> — mask then export to later steps via $GITHUB_ENV.
put() {
    mask "$2"
    printf '%s<<__RELSIGN_EOF__\n%s\n__RELSIGN_EOF__\n' "$1" "$2" >> "$GITHUB_ENV"
}

fetch_infisical() {
    command -v infisical >/dev/null 2>&1 || die "infisical CLI not found on runner"
    : "${INFISICAL_UNIVERSAL_AUTH_CLIENT_ID:?}" \
      "${INFISICAL_UNIVERSAL_AUTH_CLIENT_SECRET:?}" "${INFISICAL_PROJECT:?}"
    local token
    # Infisical reads Universal Auth credentials from env, so the client
    # secret never appears in process arguments or logs.
    token="$(infisical login --method=universal-auth --silent --plain)" \
        || die "infisical machine-identity login failed"
    mask "$token"
    local base path
    path="${SIGNING_KEY_REFERENCE#infisical://}"          # project/folder path
    # Use the documented token environment variable. Neither the Universal
    # Auth secret nor the short-lived access token enters process argv.
    export INFISICAL_TOKEN="$token"
    base=(infisical secrets get --projectId="$INFISICAL_PROJECT" --plain)
    KEYSTORE_B64="$("${base[@]}" "TX10_RELEASE_KEYSTORE_B64" --path="/$path")"
    KEYSTORE_PASSWORD="$("${base[@]}" "TX10_RELEASE_KEYSTORE_PASSWORD" --path="/$path")"
    KEY_ALIAS="$("${base[@]}" "TX10_RELEASE_KEY_ALIAS" --path="/$path")"
    KEY_PASSWORD="$("${base[@]}" "TX10_RELEASE_KEY_PASSWORD" --path="/$path")"
    unset INFISICAL_TOKEN token
}

fetch_vaultwarden() {
    command -v bw >/dev/null 2>&1 || die "bitwarden (bw) CLI not found on runner"
    : "${BW_SESSION:?BW_SESSION (Vaultwarden session) is required}"
    local item="${SIGNING_KEY_REFERENCE#vaultwarden://}"
    KEYSTORE_B64="$(bw get attachment keystore.b64 --itemid "$item" --raw)"
    KEYSTORE_PASSWORD="$(bw get password "$item")"
    KEY_ALIAS="$(bw get username "$item")"
    KEY_PASSWORD="$(bw get item "$item" | python3 -c 'import sys,json;print(next(f["value"] for f in json.load(sys.stdin).get("fields",[]) if f["name"]=="key_password"))')"
}

case "$SIGNING_KEY_REFERENCE" in
    infisical://*) fetch_infisical ;;
    vaultwarden://*) fetch_vaultwarden ;;
esac

[ -n "${KEYSTORE_B64:-}" ] || die "resolved keystore is empty"
[ -n "${KEYSTORE_PASSWORD:-}" ] || die "resolved keystore password is empty"
[ -n "${KEY_ALIAS:-}" ] || die "resolved key alias is empty"
[ -n "${KEY_PASSWORD:-}" ] || die "resolved key password is empty"

# Materialise the keystore under RUNNER_TEMP only, 0600, never in the workspace.
umask 077
printf '%s' "$KEYSTORE_B64" | base64 -d > "$KEYSTORE_PATH" \
    || die "failed to decode resolved keystore"
unset KEYSTORE_B64

put RELEASE_KEYSTORE_PATH "$KEYSTORE_PATH"
put RELEASE_KEYSTORE_PASSWORD "$KEYSTORE_PASSWORD"
put RELEASE_KEY_ALIAS "$KEY_ALIAS"
put RELEASE_KEY_PASSWORD "$KEY_PASSWORD"

echo "release-resolve-signing: signing material resolved from the private store (masked)"
