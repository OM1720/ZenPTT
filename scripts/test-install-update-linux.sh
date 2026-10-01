#!/usr/bin/env bash
set -Eeuo pipefail

readonly DEPLOYMENT=/deployment
readonly TEST_PATH=/test-bin

fail() {
    printf '[installer-gate] ERROR: %s\n' "$*" >&2
    exit 1
}

step() {
    printf '[installer-gate] %s\n' "$*"
}

cat >/etc/os-release <<'EOF'
ID=ubuntu
VERSION_ID="24.04"
VERSION_CODENAME=noble
UBUNTU_CODENAME=noble
EOF

mkdir -p "$TEST_PATH"
cat >"$TEST_PATH/systemctl" <<'EOF'
#!/bin/sh
exit 0
EOF
cat >"$TEST_PATH/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
args=()
for arg in "$@"; do
    case "$arg" in
        https://ptt.test/*|https://"${ZENPTT_TEST_DOMAIN:-ptt.test}"/*)
            path="/${arg#https://*/}"
            args+=("http://127.0.0.1$path")
            ;;
        *)
            args+=("$arg")
            ;;
    esac
done
exec /usr/bin/curl "${args[@]}"
EOF
chmod +x "$TEST_PATH/systemctl" "$TEST_PATH/curl"
printf '127.0.0.1 ptt.test other.test\n' >>/etc/hosts
export PATH="$TEST_PATH:$PATH"
mkdir -p "$DEPLOYMENT"

copy_package() {
    local name="$1"
    rm -f -- \
        "$DEPLOYMENT/install-update.sh" \
        "$DEPLOYMENT/zenptt.apk" \
        "$DEPLOYMENT/server.env" \
        "$DEPLOYMENT/zenptt-server.zip"
    cp -a "/fixtures/$name/." "$DEPLOYMENT/"
    chmod +x "$DEPLOYMENT/install-update.sh"
}

run_installer() {
    (
        cd "$DEPLOYMENT"
        bash ./install-update.sh
    )
}

runtime_compose() {
    (
        cd "$DEPLOYMENT/runtime"
        docker compose --env-file server.env "$@"
    )
}

wait_for_http() {
    for _ in $(seq 1 60); do
        if /usr/bin/curl -fsS --max-time 2 http://127.0.0.1/health |
            grep -Fq '"status":"ok"'; then
            return 0
        fi
        sleep 1
    done
    return 1
}

assert_release() {
    local expected_version="$1"
    local expected_hash="$2"
    local expected_size="$3"
    local homepage metadata downloaded_hash

    homepage="$(/usr/bin/curl -fsS http://127.0.0.1/)"
    if ! grep -Fq '/web/assets/' <<<"$homepage"; then
        fail "Web home page is invalid"
    fi

    metadata="$(/usr/bin/curl -fsS http://127.0.0.1/app/latest)"
    python3 -c 'import json,sys
data=json.load(sys.stdin)
assert data["version_code"] == int(sys.argv[1])
assert data["sha256"] == sys.argv[2]
assert data["size_bytes"] == int(sys.argv[3])' \
        "$expected_version" "$expected_hash" "$expected_size" <<<"$metadata" ||
        fail "Published APK metadata does not match version $expected_version"

    /usr/bin/curl -fsS http://127.0.0.1/app/download -o /tmp/zenptt-gate.apk
    downloaded_hash="$(sha256sum /tmp/zenptt-gate.apk | awk '{print $1}')"
    [[ "$downloaded_hash" == "$expected_hash" ]] ||
        fail "Published APK content does not match version $expected_version"
    assert_versioned_release "$expected_version" "$expected_hash" "$expected_size"
}

assert_versioned_release() {
    local expected_version="$1"
    local expected_hash="$2"
    local expected_size="$3"
    local downloaded_hash headers_file apk_file range_file
    apk_file="/tmp/zenptt-version-$expected_version.apk"
    range_file="/tmp/zenptt-version-$expected_version.range"
    headers_file="/tmp/zenptt-version-$expected_version.headers"

    /usr/bin/curl -fsS "http://127.0.0.1/app/releases/$expected_version/download" -o "$apk_file"
    downloaded_hash="$(sha256sum "$apk_file" | awk '{print $1}')"
    [[ "$downloaded_hash" == "$expected_hash" ]] ||
        fail "Versioned APK $expected_version changed"

    /usr/bin/curl -fsS \
        -H 'Range: bytes=0-3' \
        -D "$headers_file" \
        "http://127.0.0.1/app/releases/$expected_version/download" \
        -o "$range_file"
    [[ "$(stat -c '%s' "$range_file")" == "4" ]] ||
        fail "Versioned APK $expected_version Range returned the wrong size"
    grep -Eiq "^content-range:[[:space:]]*bytes 0-3/$expected_size\r?$" "$headers_file" ||
        fail "Versioned APK $expected_version Range headers are invalid"
}

assert_unknown_release_missing() {
    local status
    status="$(/usr/bin/curl -sS -o /dev/null -w '%{http_code}' \
        http://127.0.0.1/app/releases/999999/download)"
    [[ "$status" == "404" ]] || fail "Unknown versioned APK returned HTTP $status"
}

assert_persistent_data() {
    local report_id="$1"
    runtime_compose exec -T zenptt-server python -c \
        'import pathlib,sys; assert pathlib.Path("/data/diagnostics", sys.argv[1] + ".json").is_file()' \
        "$report_id" >/dev/null ||
        fail "Diagnostics volume was not preserved"
    runtime_compose exec -T caddy test -f /data/installer-gate-marker ||
        fail "Caddy data volume was not preserved"
}

assert_v2_state() {
    grep -Fq 'APK_VERSION_CODE=2' "$DEPLOYMENT/runtime/bundle-manifest.env" ||
        fail "Runtime manifest is not version 2"
    grep -Fq 'APK_VERSION_CODE=2' "$DEPLOYMENT/state/last-applied-bundle.env" ||
        fail "Last applied state is not version 2"
}

expect_rejected() {
    local name="$1"
    local before_container after_container before_failed after_failed
    before_container="$(runtime_compose ps -q zenptt-server)"
    before_failed="$(find "$DEPLOYMENT" -maxdepth 1 -type d -name 'failed-*' | wc -l | tr -d ' ')"
    step "Rejecting invalid package: $name"
    copy_package "$name"
    if run_installer; then
        fail "Invalid package $name was accepted"
    fi
    after_container="$(runtime_compose ps -q zenptt-server)"
    after_failed="$(find "$DEPLOYMENT" -maxdepth 1 -type d -name 'failed-*' | wc -l | tr -d ' ')"
    [[ "$after_container" == "$before_container" ]] ||
        fail "Invalid package $name recreated the running server"
    [[ "$after_failed" == "$before_failed" ]] ||
        fail "Invalid package $name reached deployment instead of prevalidation"
}

v1_hash="$(sha256sum /fixtures/good-v1/zenptt.apk | awk '{print $1}')"
v1_size="$(stat -c '%s' /fixtures/good-v1/zenptt.apk)"
v2_hash="$(sha256sum /fixtures/good-v2/zenptt.apk | awk '{print $1}')"
v2_size="$(stat -c '%s' /fixtures/good-v2/zenptt.apk)"

step "First installation"
copy_package good-v1
run_installer
wait_for_http || fail "First installation did not become healthy"
assert_release 1 "$v1_hash" "$v1_size"
first_container="$(runtime_compose ps -q zenptt-server)"
[[ -n "$first_container" ]] || fail "Server container is missing"

report_id="$(
    /usr/bin/curl -fsS \
        -H 'Content-Type: application/json' \
        --data '{"schemaVersion":1,"createdAtMs":1,"appVersion":"installer-gate","androidVersion":"automated","networkStatus":"Connected","headsetStatus":"Not checked","audioRoute":"Not checked","details":"channel=none"}' \
        http://127.0.0.1/diagnostics |
        python3 -c 'import json,sys; print(json.load(sys.stdin)["report_id"])'
)"
runtime_compose exec -T caddy sh -c 'printf marker >/data/installer-gate-marker'
assert_persistent_data "$report_id"

step "Idempotent reinstall"
copy_package good-v1
run_installer
second_container="$(runtime_compose ps -q zenptt-server)"
[[ "$second_container" != "$first_container" ]] ||
    fail "Idempotent reinstall did not recreate the bind-mounted container"
assert_release 1 "$v1_hash" "$v1_size"
assert_persistent_data "$report_id"

step "APK-only update"
copy_package good-v2
run_installer
third_container="$(runtime_compose ps -q zenptt-server)"
[[ "$third_container" != "$second_container" ]] ||
    fail "APK-only update did not recreate the bind-mounted container"
assert_release 2 "$v2_hash" "$v2_size"
assert_versioned_release 1 "$v1_hash" "$v1_size"
assert_unknown_release_missing
assert_persistent_data "$report_id"
assert_v2_state

step "Conflicting versionCode is rejected before deployment"
expect_rejected conflict-v2
assert_release 2 "$v2_hash" "$v2_size"
assert_versioned_release 1 "$v1_hash" "$v1_size"

step "Failed release and rollback"
copy_package broken-v3
if run_installer; then
    fail "Broken release unexpectedly succeeded"
fi
wait_for_http || fail "Rollback did not restore a healthy server"
assert_release 2 "$v2_hash" "$v2_size"
assert_versioned_release 1 "$v1_hash" "$v1_size"
assert_persistent_data "$report_id"
assert_v2_state
find "$DEPLOYMENT" -maxdepth 1 -type d -name 'failed-*' | grep -q . ||
    fail "Failed release files were not retained"

expect_rejected bad-release-size
assert_release 2 "$v2_hash" "$v2_size"
expect_rejected bad-zip
assert_release 2 "$v2_hash" "$v2_size"
expect_rejected bad-qrz
assert_release 2 "$v2_hash" "$v2_size"
expect_rejected bad-apk
assert_release 2 "$v2_hash" "$v2_size"
expect_rejected bad-dns
assert_release 2 "$v2_hash" "$v2_size"
expect_rejected missing-apk
assert_release 2 "$v2_hash" "$v2_size"
expect_rejected missing-web
assert_release 2 "$v2_hash" "$v2_size"
assert_persistent_data "$report_id"
assert_v2_state

step "Volume preservation after restart"
runtime_compose restart
wait_for_http || fail "Server did not recover after restart"
assert_release 2 "$v2_hash" "$v2_size"
assert_persistent_data "$report_id"
assert_v2_state

if [[ -d /fixtures/actual ]]; then
    step "Install and update the exact four-file delivery in local Linux"
    runtime_compose down
    mv "$DEPLOYMENT" /deployment-fixtures
    mkdir -p "$DEPLOYMENT"
    copy_package actual
    ZENPTT_TEST_DOMAIN="$(head -n 1 "$DEPLOYMENT/server.env" | cut -d= -f2 | tr -d '\r')"
    export ZENPTT_TEST_DOMAIN
    printf '127.0.0.1 %s\n' "$ZENPTT_TEST_DOMAIN" >>/etc/hosts
    ZENPTT_TEST_DOCKER="$(command -v docker)"
    export ZENPTT_TEST_DOCKER
    # Override only local transport and nested cgroup limitations outside the ZIP.
    cat >/fixtures/actual.override.yaml <<'EOF'
services:
  zenptt-server:
    mem_limit: 0
    pids_limit: -1
  echo-supervisor:
    mem_limit: 0
    pids_limit: -1
  caddy:
    environment:
      ZENPTT_DOMAIN: ":80"
    mem_limit: 0
    pids_limit: -1
EOF
    cat >"$TEST_PATH/docker" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
if [[ "${1:-}" == compose ]]; then
    exec "$ZENPTT_TEST_DOCKER" compose -f compose.yaml -f /fixtures/actual.override.yaml "${@:2}"
fi
exec "$ZENPTT_TEST_DOCKER" "$@"
EOF
    chmod +x "$TEST_PATH/docker"
    hash -r
    run_installer
    run_installer
    for file in install-update.sh zenptt-server.zip zenptt.apk server.env; do
        cmp -s "/fixtures/actual/$file" "$DEPLOYMENT/$file" || fail "Delivery file changed: $file"
    done
    /usr/bin/curl -fsS http://127.0.0.1/app/download -o /tmp/actual-download.apk
    cmp -s "$DEPLOYMENT/zenptt.apk" /tmp/actual-download.apk || fail "Actual APK download differs"
    /usr/bin/curl -fsS http://127.0.0.1/web/ -o /tmp/actual-web.html
    cmp -s "$DEPLOYMENT/runtime/web/dist/index.html" /tmp/actual-web.html || fail "Actual web entry differs"
    step "Exact delivery installation and update passed; public TLS remains a deployment check"
fi

step "All Linux installer lifecycle checks passed"
