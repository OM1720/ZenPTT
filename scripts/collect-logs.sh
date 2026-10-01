#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
INSTALL_DIR="$(dirname -- "$SCRIPT_DIR")"
readonly INSTALL_DIR
readonly SUPPORT_DIR="$INSTALL_DIR/support"
readonly ARCHIVE="$SUPPORT_DIR/zenptt-support-latest.tar.gz"
readonly LOG_SINCE="168h"

staging_dir=""
temporary_archive=""

log() {
    printf '[ZenPTT] %s\n' "$*"
}

fail() {
    printf '[ZenPTT] ERROR: %s\n' "$*" >&2
    exit 1
}

cleanup() {
    if [[ -n "$staging_dir" && -d "$staging_dir" ]]; then
        rm -rf -- "$staging_dir"
    fi
    if [[ -n "$temporary_archive" && -f "$temporary_archive" ]]; then
        rm -f -- "$temporary_archive"
    fi
}
trap cleanup EXIT

manifest_value() {
    local key="$1"
    awk -F= -v wanted="$key" '$1 == wanted { print substr($0, index($0, "=") + 1); exit }' \
        "$SCRIPT_DIR/bundle-manifest.env" | tr -d '\r'
}

require_host() {
    [[ "$(id -u)" -eq 0 ]] || fail "Run this script with sudo."
    [[ -f "$SCRIPT_DIR/compose.yaml" ]] || fail "compose.yaml is missing from the runtime directory."
    [[ -f "$SCRIPT_DIR/server.env" ]] || fail "server.env is missing from the runtime directory."
    [[ -f "$SCRIPT_DIR/bundle-manifest.env" ]] || fail "bundle-manifest.env is missing from the runtime directory."
    command -v docker >/dev/null || fail "Docker is not installed."
    docker compose version >/dev/null 2>&1 || fail "Docker Compose is not installed."
    command -v tar >/dev/null || fail "tar is not installed."
}

write_summary() {
    local version
    version="$(manifest_value APK_VERSION_NAME)"
    {
        printf 'Collected at (UTC): %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        printf 'ZenPTT version: %s\n' "${version:-unknown}"
        printf 'Docker logs requested since: %s\n' "$LOG_SINCE"
        printf '\nContainer status:\n'
        docker compose --env-file server.env ps --all
    } >"$staging_dir/summary.txt"
}

copy_diagnostics() {
    local container_id
    mkdir -p "$staging_dir/diagnostics"
    container_id="$(docker compose --env-file server.env ps -aq zenptt-server)"
    if [[ -z "$container_id" ]]; then
        log "Server container was not found; diagnostics directory will be empty."
        return
    fi
    docker cp "$container_id:/data/diagnostics/." "$staging_dir/diagnostics"
}

collect() {
    mkdir -p "$SUPPORT_DIR"
    staging_dir="$(mktemp -d "$SUPPORT_DIR/.staging.XXXXXX")"
    temporary_archive="$(mktemp "$SUPPORT_DIR/.bundle.XXXXXX")"

    (
        cd "$SCRIPT_DIR"
        docker compose --env-file server.env logs --since "$LOG_SINCE" \
            --no-color --timestamps zenptt-server >"$staging_dir/server.log"
        docker compose --env-file server.env logs --since "$LOG_SINCE" \
            --no-color --timestamps caddy >"$staging_dir/caddy.log"
        write_summary
        copy_diagnostics
    )

    tar -C "$staging_dir" -czf "$temporary_archive" .
    chmod 0644 "$temporary_archive"
    mv -f -- "$temporary_archive" "$ARCHIVE"
    temporary_archive=""
    log "Support bundle created: $ARCHIVE"
}

main() {
    require_host
    collect
}

main "$@"
