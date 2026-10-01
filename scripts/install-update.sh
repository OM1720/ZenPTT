#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly ARCHIVE="$SCRIPT_DIR/zenptt-server.zip"
readonly APK="$SCRIPT_DIR/zenptt.apk"
readonly SERVER_ENV="$SCRIPT_DIR/server.env"
readonly RUNTIME_DIR="$SCRIPT_DIR/runtime"
readonly PREVIOUS_DIR="$SCRIPT_DIR/previous"
readonly STATE_DIR="$SCRIPT_DIR/state"

staging_dir=""

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
}
trap cleanup EXIT

env_value() {
    local file="$1"
    local key="$2"
    awk -F= -v wanted="$key" '$1 == wanted { value = substr($0, index($0, "=") + 1); count++ } END { if (count == 1) print value }' "$file" |
        tr -d '\r'
}

require_host() {
    [[ "$(id -u)" -eq 0 ]] || fail "Run this script with sudo."
    [[ "$SCRIPT_DIR" != "/" ]] || fail "The installation directory cannot be /."

    [[ -r /etc/os-release ]] || fail "Unable to identify the operating system."
    # shellcheck disable=SC1091
    . /etc/os-release
    [[ "${ID:-}" == "ubuntu" && "${VERSION_ID:-}" == "24.04" ]] ||
        fail "Ubuntu 24.04 LTS is required."
}

validate_inputs() {
    [[ -f "$ARCHIVE" ]] || fail "Missing zenptt-server.zip."
    [[ -f "$APK" ]] || fail "Missing zenptt.apk."
    [[ -f "$SERVER_ENV" ]] || fail "Missing server.env."

    local first_line domain env_file http_port https_port
    first_line="$(head -n 1 "$SERVER_ENV" | tr -d '\r')"
    [[ "$first_line" == ZENPTT_DOMAIN=* ]] ||
        fail "ZENPTT_DOMAIN must be the first line of server.env."

    domain="${first_line#ZENPTT_DOMAIN=}"
    [[ "$domain" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ && "$domain" == *.* ]] ||
        fail "ZENPTT_DOMAIN must contain a valid DNS name."
    case "$domain" in
        example.com|*.example.com) fail "Replace the example DNS name in server.env before installation." ;;
    esac

    env_file="$(env_value "$SERVER_ENV" ZENPTT_ENV_FILE)"
    http_port="$(env_value "$SERVER_ENV" ZENPTT_HTTP_PORT)"
    https_port="$(env_value "$SERVER_ENV" ZENPTT_HTTPS_PORT)"
    [[ "$env_file" == "server.env" ]] || fail "ZENPTT_ENV_FILE must be server.env."
    [[ "$http_port" == "80" ]] || fail "ZENPTT_HTTP_PORT must be 80."
    [[ "$https_port" == "443" ]] || fail "ZENPTT_HTTPS_PORT must be 443."

    if ! getent ahosts "$domain" >/dev/null 2>&1; then
        fail "DNS name $domain does not resolve yet."
    fi
}

install_base_tools() {
    if command -v curl >/dev/null && command -v unzip >/dev/null; then
        return
    fi
    log "Installing required system tools..."
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y ca-certificates curl unzip
}

install_docker() {
    if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then
        systemctl enable --now docker
        return
    fi

    log "Installing Docker Engine and Docker Compose..."
    export DEBIAN_FRONTEND=noninteractive
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc

    local architecture codename
    architecture="$(dpkg --print-architecture)"
    codename="${UBUNTU_CODENAME:-$VERSION_CODENAME}"
    printf '%s\n' \
        'Types: deb' \
        'URIs: https://download.docker.com/linux/ubuntu' \
        "Suites: $codename" \
        'Components: stable' \
        "Architectures: $architecture" \
        'Signed-By: /etc/apt/keyrings/docker.asc' \
        >/etc/apt/sources.list.d/docker.sources

    apt-get update
    apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    systemctl enable --now docker
    docker compose version >/dev/null
}

validate_archive_paths() {
    local entry
    while IFS= read -r entry; do
        entry="${entry//$'\r'/}"
        [[ -n "$entry" ]] || continue
        case "/$entry/" in
            *"/../"*|*"/./"*|*"\\"*) fail "Unsafe path in zenptt-server.zip: $entry" ;;
        esac
        [[ "$entry" != /* ]] || fail "Absolute path in zenptt-server.zip: $entry"
        case "$entry" in
            */qrz_bot.py|qrz_bot.py|*/QRZ.pcm|QRZ.pcm|headless/compose.yaml|examples/qrz_bot/*)
                fail "QRZ example files are not allowed in zenptt-server.zip: $entry"
                ;;
        esac
    done < <(unzip -Z1 "$ARCHIVE")
}

validate_bundle() {
    log "Validating deployment files..."
    unzip -tq "$ARCHIVE" >/dev/null || fail "zenptt-server.zip is damaged."
    validate_archive_paths

    staging_dir="$(mktemp -d "$SCRIPT_DIR/.staging.XXXXXX")"
    unzip -q "$ARCHIVE" -d "$staging_dir"

    local manifest="$staging_dir/bundle-manifest.env"
    local release="$staging_dir/server/releases/release.json"
    [[ -f "$staging_dir/compose.yaml" ]] || fail "compose.yaml is missing from the archive."
    [[ -f "$staging_dir/Caddyfile" ]] || fail "Caddyfile is missing from the archive."
    [[ -f "$staging_dir/index.html" ]] || fail "index.html is missing from the archive."
    [[ -s "$staging_dir/web/dist/index.html" ]] || fail "Web client is missing from the archive."
    local web_asset
    [[ -s "$staging_dir/web/dist/manifest.webmanifest" ]] || fail "Web app manifest is missing from the archive."
    for web_asset in apple-touch-icon.png zenptt-192.png zenptt-512.png; do
        [[ -s "$staging_dir/web/dist/icons/$web_asset" ]] || fail "Web app icon is missing: $web_asset"
    done
    for web_asset in OPUS-LICENSE.txt ICONS-LICENSE.txt THIRD-PARTY-NOTICES.txt; do
        [[ -s "$staging_dir/web/dist/licenses/$web_asset" ]] || fail "Web license is missing: $web_asset"
    done
    for web_asset in js css wasm; do
        compgen -G "$staging_dir/web/dist/assets/*.$web_asset" >/dev/null || fail "Web $web_asset asset is missing."
    done
    [[ -f "$staging_dir/collect-logs.sh" ]] || fail "collect-logs.sh is missing from the archive."
    [[ -f "$staging_dir/server/Dockerfile" ]] || fail "Server Dockerfile is missing from the archive."
    [[ -f "$staging_dir/headless/Dockerfile" ]] || fail "Headless Dockerfile is missing from the archive."
    [[ -f "$staging_dir/headless/src/zenptt_headless/echo_supervisor.py" ]] ||
        fail "Echo supervisor is missing from the archive."
    [[ -f "$manifest" ]] || fail "bundle-manifest.env is missing from the archive."
    [[ -f "$release" ]] || fail "APK release metadata is missing from the archive."

    local schema manifest_domain configured_domain version_code version_name expected_hash expected_size actual_hash actual_size
    schema="$(env_value "$manifest" BUNDLE_SCHEMA_VERSION)"
    manifest_domain="$(env_value "$manifest" ZENPTT_DOMAIN)"
    configured_domain="$(env_value "$SERVER_ENV" ZENPTT_DOMAIN)"
    version_code="$(env_value "$manifest" APK_VERSION_CODE)"
    version_name="$(env_value "$manifest" APK_VERSION_NAME)"
    expected_hash="$(env_value "$manifest" APK_SHA256)"
    expected_size="$(env_value "$manifest" APK_SIZE_BYTES)"

    [[ "$schema" == "1" ]] || fail "Unsupported bundle manifest version."
    [[ "$manifest_domain" == "$configured_domain" ]] ||
        fail "server.env DNS name does not match the APK bundle. Rebuild the hosting package."
    [[ "$version_code" =~ ^[1-9][0-9]*$ ]] || fail "Invalid APK version code in the bundle."
    [[ "$version_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "Invalid APK version name in the bundle."
    [[ "$expected_hash" =~ ^[0-9a-f]{64}$ ]] || fail "Invalid APK hash in the bundle."
    [[ "$expected_size" =~ ^[1-9][0-9]*$ ]] || fail "Invalid APK size in the bundle."

    actual_hash="$(sha256sum "$APK" | awk '{print $1}')"
    actual_size="$(stat -c '%s' "$APK")"
    [[ "$actual_hash" == "$expected_hash" ]] || fail "zenptt.apk belongs to another bundle."
    [[ "$actual_size" == "$expected_size" ]] || fail "zenptt.apk has an unexpected size."
    grep -Eq "\"sha256\"[[:space:]]*:[[:space:]]*\"$expected_hash\"" "$release" ||
        fail "APK metadata does not match the bundle manifest."
    grep -Eq "\"version_code\"[[:space:]]*:[[:space:]]*$version_code([,[:space:]]|$)" "$release" ||
        fail "APK version code does not match release metadata."
    grep -Eq "\"version_name\"[[:space:]]*:[[:space:]]*\"$version_name\"" "$release" ||
        fail "APK version name does not match release metadata."
    grep -Eq "\"size_bytes\"[[:space:]]*:[[:space:]]*$expected_size([,[:space:]]|$)" "$release" ||
        fail "APK size does not match release metadata."

    mkdir -p "$staging_dir/server/releases"
    install -m 0644 "$APK" "$staging_dir/server/releases/zenptt-$version_code.apk"
    if [[ -d "$RUNTIME_DIR/server/releases" ]]; then
        local retained target name
        shopt -s nullglob
        for retained in "$RUNTIME_DIR/server/releases"/zenptt-*.apk; do
            name="$(basename -- "$retained")"
            target="$staging_dir/server/releases/$name"
            if [[ -f "$target" ]]; then
                cmp -s -- "$retained" "$target" ||
                    fail "versionCode $version_code already exists with different APK bytes. Publish a higher versionCode."
            else
                install -m 0644 "$retained" "$target"
            fi
        done
        shopt -u nullglob
    fi
    install -m 0600 "$SERVER_ENV" "$staging_dir/server.env"
}

wait_for_local_runtime() {
    local manifest="$RUNTIME_DIR/bundle-manifest.env"
    local version_code version_name expected_hash expected_size
    version_code="$(env_value "$manifest" APK_VERSION_CODE)"
    version_name="$(env_value "$manifest" APK_VERSION_NAME)"
    expected_hash="$(env_value "$manifest" APK_SHA256)"
    expected_size="$(env_value "$manifest" APK_SIZE_BYTES)"

    for _ in $(seq 1 30); do
        if (
            cd "$RUNTIME_DIR"
            echo_required=false
            if docker compose --env-file server.env config --services | grep -Fxq echo-supervisor; then
                echo_required=true
                [[ "$(docker compose --env-file server.env ps --status running --services echo-supervisor)" == "echo-supervisor" ]]
            fi
            docker compose --env-file server.env exec -T zenptt-server \
                python -c 'import hashlib,json,pathlib,sys,urllib.request
health=json.load(urllib.request.urlopen("http://127.0.0.1:8000/health", timeout=2))
release=json.load(urllib.request.urlopen("http://127.0.0.1:8000/app/latest", timeout=2))
apk=pathlib.Path(f"/data/releases/zenptt-{sys.argv[1]}.apk")
assert health == {"status": "ok"}
if sys.argv[5] == "true":
    echo=json.load(urllib.request.urlopen("http://127.0.0.1:8000/internal/echo/status", timeout=2))
    assert echo["status"] == "ready" and echo["controller_connected"] is True
assert release["version_code"] == int(sys.argv[1])
assert release["version_name"] == sys.argv[2]
assert release["sha256"] == sys.argv[3]
assert release["size_bytes"] == int(sys.argv[4])
assert apk.stat().st_size == int(sys.argv[4])
assert hashlib.sha256(apk.read_bytes()).hexdigest() == sys.argv[3]' \
                "$version_code" "$version_name" "$expected_hash" "$expected_size" "$echo_required"
        ) >/dev/null 2>&1; then
            return 0
        fi
        sleep 2
    done
    return 1
}

release_json_matches_manifest() {
    local response="$1"
    local manifest="$2"
    local version_code version_name expected_hash expected_size
    version_code="$(env_value "$manifest" APK_VERSION_CODE)"
    version_name="$(env_value "$manifest" APK_VERSION_NAME)"
    expected_hash="$(env_value "$manifest" APK_SHA256)"
    expected_size="$(env_value "$manifest" APK_SIZE_BYTES)"

    grep -Eq "\"version_code\"[[:space:]]*:[[:space:]]*$version_code([,}])" <<<"$response" &&
        grep -Fq "\"version_name\":\"$version_name\"" <<<"$response" &&
        grep -Fq "\"sha256\":\"$expected_hash\"" <<<"$response" &&
        grep -Eq "\"size_bytes\"[[:space:]]*:[[:space:]]*$expected_size([,}])" <<<"$response"
}

verify_web_runtime() {
    local domain="$1" asset relative downloaded
    # The previous runtime can predate the web client; keep rollback compatible.
    [[ -d "$RUNTIME_DIR/web/dist" ]] || return 0
    downloaded="$(mktemp)"
    while IFS= read -r -d '' asset; do
        relative="${asset#"$RUNTIME_DIR/web/dist/"}"
        if ! curl -fsS --max-time 10 "https://$domain/web/$relative" -o "$downloaded" || ! cmp -s "$asset" "$downloaded"; then
            rm -f -- "$downloaded"
            return 1
        fi
    done < <(find "$RUNTIME_DIR/web/dist" -type f -print0)
    rm -f -- "$downloaded"
}

wait_for_public_runtime() {
    local domain="$1"
    local health release
    for _ in $(seq 1 60); do
        health="$(curl -fsS --max-time 5 "https://$domain/health" 2>/dev/null || true)"
        release="$(curl -fsS --max-time 5 "https://$domain/app/latest" 2>/dev/null || true)"
        if grep -Fq '"status":"ok"' <<<"$health" &&
            release_json_matches_manifest "$release" "$RUNTIME_DIR/bundle-manifest.env" &&
            verify_web_runtime "$domain"; then
            return 0
        fi
        sleep 2
    done
    return 1
}

rollback() {
    log "Deployment failed; restoring the previous version..."
    local failed_dir
    failed_dir="$SCRIPT_DIR/failed-$(date -u +%Y%m%dT%H%M%SZ)"
    if [[ -d "$RUNTIME_DIR" ]]; then
        mv -- "$RUNTIME_DIR" "$failed_dir"
    fi

    if [[ -d "$PREVIOUS_DIR" ]]; then
        mv -- "$PREVIOUS_DIR" "$RUNTIME_DIR"
        local domain status=0
        domain="$(env_value "$RUNTIME_DIR/server.env" ZENPTT_DOMAIN)"
        (
            cd "$RUNTIME_DIR"
            docker compose --env-file server.env up -d --build --force-recreate --remove-orphans
        ) || status=$?
        if [[ "$status" -eq 0 ]]; then
            wait_for_local_runtime || status=$?
        fi
        if [[ "$status" -eq 0 ]]; then
            wait_for_public_runtime "$domain" || status=$?
        fi
        if [[ "$status" -ne 0 ]]; then
            log "Rollback failed. Runtime files: $RUNTIME_DIR; failed files: $failed_dir"
            return 1
        fi
        log "Previous version restored. Failed files: $failed_dir"
    else
        (
            cd "$failed_dir"
            docker compose --env-file server.env down
        ) || true
        log "First installation was stopped. Failed files: $failed_dir"
    fi
}

deploy() {
    local domain
    domain="$(env_value "$SERVER_ENV" ZENPTT_DOMAIN)"

    (
        cd "$staging_dir"
        docker compose --env-file server.env config --quiet
    )

    log "Building the server image..."
    (
        cd "$staging_dir"
        docker compose --env-file server.env build zenptt-server echo-supervisor
        docker compose --env-file server.env pull caddy
    )

    if [[ -d "$PREVIOUS_DIR" ]]; then
        rm -rf -- "$PREVIOUS_DIR"
    fi
    if [[ -d "$RUNTIME_DIR" ]]; then
        mv -- "$RUNTIME_DIR" "$PREVIOUS_DIR"
    fi
    mv -- "$staging_dir" "$RUNTIME_DIR"
    staging_dir=""

    set +e
    (
        cd "$RUNTIME_DIR"
        docker compose --env-file server.env up -d --force-recreate --remove-orphans
    )
    local status=$?
    if [[ "$status" -eq 0 ]]; then
        wait_for_local_runtime
        status=$?
    fi
    if [[ "$status" -eq 0 ]]; then
        wait_for_public_runtime "$domain"
        status=$?
    fi
    set -e

    if [[ "$status" -ne 0 ]]; then
        if ! rollback; then
            fail "Deployment and rollback checks failed. Run from the runtime directory: docker compose --env-file server.env logs"
        fi
        fail "Deployment checks failed. The previous version was restored when available."
    fi

    mkdir -p "$STATE_DIR"
    install -m 0600 "$RUNTIME_DIR/server.env" "$STATE_DIR/last-applied.env"
    install -m 0644 "$RUNTIME_DIR/bundle-manifest.env" "$STATE_DIR/last-applied-bundle.env"

    log "Installation/update completed successfully."
    log "Server address: wss://$domain"
    log "Health check: https://$domain/health"
    log "Logs: cd $RUNTIME_DIR && sudo docker compose --env-file server.env logs -f"
}

main() {
    require_host
    validate_inputs
    install_base_tools
    validate_bundle
    install_docker
    deploy
}

main "$@"
