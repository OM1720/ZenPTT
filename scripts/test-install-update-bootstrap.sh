#!/usr/bin/env bash
set -Eeuo pipefail

readonly DEPLOYMENT=/deployment
readonly TEST_PATH=/test-bin

fail() {
    printf '[bootstrap-gate] ERROR: %s\n' "$*" >&2
    exit 1
}

mkdir -p "$TEST_PATH" "$DEPLOYMENT"
cat >"$TEST_PATH/curl" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
args=()
for arg in "$@"; do
    case "$arg" in
        https://ptt.test/*)
            path="${arg#https://ptt.test}"
            args+=("http://127.0.0.1$path")
            ;;
        *)
            args+=("$arg")
            ;;
    esac
done
exec /usr/bin/curl "${args[@]}"
EOF
cat >"$TEST_PATH/systemctl" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${1:-}" != "enable" || "${2:-}" != "--now" || "${3:-}" != "docker" ]]; then
    exit 0
fi

if /usr/bin/docker info >/dev/null 2>&1; then
    exit 0
fi

rm -f /var/run/docker.pid
nohup /usr/bin/dockerd \
    --host=unix:///var/run/docker.sock \
    --storage-driver=vfs \
    >/tmp/dockerd.log 2>&1 &

for _ in $(seq 1 90); do
    if /usr/bin/docker info >/dev/null 2>&1; then
        exit 0
    fi
    sleep 1
done

cat /tmp/dockerd.log >&2
exit 1
EOF
chmod +x "$TEST_PATH/curl" "$TEST_PATH/systemctl"
printf '127.0.0.1 ptt.test\n' >>/etc/hosts
export PATH="$TEST_PATH:$PATH"

command -v docker >/dev/null 2>&1 && fail "Ubuntu fixture already contains Docker"
test -x /usr/bin/curl && fail "Ubuntu fixture already contains curl"
command -v unzip >/dev/null 2>&1 && fail "Ubuntu fixture already contains unzip"

cp -a /fixtures/good-v1/. "$DEPLOYMENT/"
chmod +x "$DEPLOYMENT/install-update.sh"
(
    cd "$DEPLOYMENT"
    bash ./install-update.sh
)

/usr/bin/docker compose version >/dev/null
/usr/bin/dpkg-query -W \
    docker-ce \
    docker-ce-cli \
    containerd.io \
    docker-buildx-plugin \
    docker-compose-plugin \
    >/dev/null
test -f /etc/apt/sources.list.d/docker.sources
test -x /usr/bin/curl
command -v unzip >/dev/null

health="$(/usr/bin/curl -fsS http://127.0.0.1/health)"
grep -Fq '"status":"ok"' <<<"$health" || fail "Bootstrapped server is unhealthy"

homepage="$(/usr/bin/curl -fsS http://127.0.0.1/)"
if ! grep -Fq '/web/assets/' <<<"$homepage"; then
    fail "Bootstrapped web home page is invalid"
fi

metadata="$(/usr/bin/curl -fsS http://127.0.0.1/app/latest)"
expected_hash="$(sha256sum "$DEPLOYMENT/zenptt.apk" | awk '{print $1}')"
expected_size="$(stat -c '%s' "$DEPLOYMENT/zenptt.apk")"
grep -Eq '"version_code"[[:space:]]*:[[:space:]]*1([,}])' <<<"$metadata" ||
    fail "Bootstrapped release version is invalid"
grep -Fq "\"sha256\":\"$expected_hash\"" <<<"$metadata" ||
    fail "Bootstrapped release hash is invalid"
grep -Eq "\"size_bytes\"[[:space:]]*:[[:space:]]*$expected_size([,}])" <<<"$metadata" ||
    fail "Bootstrapped release size is invalid"

/usr/bin/curl -fsS http://127.0.0.1/app/download -o /tmp/zenptt-bootstrap.apk
downloaded_hash="$(sha256sum /tmp/zenptt-bootstrap.apk | awk '{print $1}')"
[[ "$downloaded_hash" == "$expected_hash" ]] ||
    fail "Bootstrapped APK content is invalid"

grep -Fq 'APK_VERSION_CODE=1' "$DEPLOYMENT/state/last-applied-bundle.env" ||
    fail "Bootstrap state was not saved"

printf '[bootstrap-gate] Clean Ubuntu package installation passed\n'
