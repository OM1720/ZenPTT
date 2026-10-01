# Ubuntu deployment

Status: current

Purpose: build, install, update, roll back, and operate the supported public
ZenPTT deployment.

Audience: release owners and server operators.

Authority: this document owns the supported public installation, activation, rollback,
operation, and verification procedure.

Code anchors: `scripts/install-update.sh`, `scripts/build-hosting-package.ps1`,
`compose.yaml`, `Caddyfile`, and `server.env`.

Update when: the hosting package, installer lifecycle, runtime paths, deployment
topology, rollback, or operator commands change.

ZenPTT uses one Ubuntu 24.04 host with Docker Compose. Caddy is the only
public container and terminates TLS. FastAPI port 8000 remains private to the
Compose network.

## Production target

- public DNS: the `ZENPTT_DOMAIN` value on the first line of `server.env`;
- installation directory: `/opt/zenptt`;
- Docker Compose project: `zenptt`;
- application service: `zenptt-server`;
- persistent volumes: `zenptt_caddy-config`, `zenptt_caddy-data`, and
  `zenptt_diagnostics-data`.

The first line of `server.env` is the authoritative production DNS setting. Treat
changes to the installation directory, Compose project, service name, or volume
namespace as deployment contract changes and update the configuration, installer,
tests, and this document together.

## Prerequisites

- a real DNS name controlled by the operator;
- an Ubuntu 24.04 host with an SSH key;
- a firewall that allows the required ports;
- a Windows build computer with Docker, JDK/Android SDK, PowerShell, and the
  project virtual environment.

Size the host for the configured container ceilings. Current ceilings and their
sources are in [`CONFIGURATION.md`](CONFIGURATION.md).

## Build the delivery package

1. Copy `server.env.example` to the ignored `server.env` and set the real DNS
   name in its first line.
2. Increment Android `versionCode` for changed APK bytes and set the intended
   `versionName`. An identical already-published APK can keep its version code.
3. Run from the repository root:

```console
.\scripts\build-hosting-package.ps1
```

On Windows build-directory locking, use `-AndroidWorkDirectory C:\tmp\zenptt-checks`
to run the full gate and publish its matching APK from a dedicated temporary build
directory. All delivery checks still run; do not combine this with `-SkipChecks`.

For a server-only update that must retain the already published Android APK, use
`-ReusePublishedApk`. The full gate and delivery checks still run, and the builder
verifies the existing APK against `server/releases/release.json`. Do not use this
option when the Android changes are intended for release; publish them with a new
`versionCode` instead.

Without `-SkipChecks`, the command runs the full local gate, normally publishes the APK,
validates the local Caddy stack, exercises the Ubuntu installer lifecycle, and
runs ShellCheck. It creates exactly four files in `dist/hosting`:

- `install-update.sh`;
- `zenptt.apk`;
- `server.env`;
- `zenptt-server.zip`.

The ZIP contains the server and minimal Echo headless runtimes, Caddy configuration, the legacy download page,
the compiled web client and its licenses under `web/dist`,
release metadata, log collector, and a manifest binding the server bundle to
the APK version, size, and SHA-256. The APK is already signed; never copy an
Android signing key to the server.

Caddy opens the web client directly at `/`; `/web/` remains available. The Main
footer downloads Android, with the hosted APK version shown on hover.

The full gate includes the pinned Linux web build and separate Chrome UI/audio
checks against production Caddy. After assembling the delivery, the builder tests
the exact four files through installation and update in isolated Linux. Local
transport and cgroup overrides stay outside the ZIP; public TLS is checked on site.
Build tools for the web client are not required on the deployed host.

The required `echo-supervisor` image is built from the bundled minimal headless runtime.
The QRZ developer example, its Compose profile, code, and PCM remain in the source
checkout and are rejected if present in the production ZIP.

## Prepare the host

1. Prepare an Ubuntu 24.04 LTS host and add the administrator SSH key.
2. Point the DNS A record at the host IPv4 address and wait for propagation.
3. Allow TCP 80 and 443 from the internet.
4. Allow TCP 22 only from the administrator's address or trusted network.
5. Copy the four delivery files to one directory, normally `/opt/zenptt`.

The installer rejects reserved example domains. Caddy needs the real DNS record
and publicly reachable ports 80/443 before certificate issuance can succeed.
Do not publish port 8000 in Docker or the host firewall.

## Install or update

Use the same command for first installation and every update:

```console
cd /opt/zenptt
sudo bash install-update.sh
```

On a clean Ubuntu host the script installs Docker Engine and the Docker Compose
plugin from Docker's official Ubuntu repository. It validates all four delivery
files and builds the new application image before changing the active runtime.

Activation verifies:

- internal application health;
- public Caddy health;
- release metadata;
- APK size, SHA-256, and content;
- the intended bundle version.
- web HTML, JavaScript, CSS, WASM and licenses match the files in the bundle.

For an update, replace all four delivery files and run the command again. The
script force-recreates containers so bind-mounted configuration and release
files cannot stay attached to an old runtime.

## Rollback model

The active runtime is `runtime`; the last known runtime is `previous`.
Diagnostics and Caddy state live in Docker volumes and survive normal updates.

If activation fails, the installer restores `previous` and verifies it again.
Failed delivery/runtime material is retained in a timestamped `failed-*`
directory for diagnosis. If both activation and rollback checks fail, inspect:

```console
cd /opt/zenptt/runtime
sudo docker compose --env-file server.env logs
```

Do not manually merge files between `runtime`, `previous`, and a failed staging
directory.

## Verify the public deployment

A successful installation prints addresses equivalent to:

```text
wss://example.your-domain.net
https://example.your-domain.net/
https://example.your-domain.net/health
```

Verify:

1. HTTPS root page and security headers.
2. `/health` returns `{"status":"ok"}`.
3. `/app/latest` matches the intended Android version.
4. `/app/releases/<version_code>/download` returns the APK and supports Range requests.
5. Port 8000 is unreachable from the internet.
6. One physical ECHO and one two-phone normal-channel session work through WSS.
7. **Check for updates** finds the hosted APK when the installed version is older.
8. `/web/` loads in desktop Chrome, prompts for the microphone on Connect, and
   supports ECHO and a conversation with Android. JS/WASM load without CSP errors.
   HTML revalidates and hashed assets use immutable caching.

The physical and reboot checks are listed in
[`MANUAL_TESTING.md`](MANUAL_TESTING.md).

## Operate the runtime

From `/opt/zenptt/runtime`:

```console
sudo docker compose --env-file server.env ps
sudo docker compose --env-file server.env logs -f
sudo docker compose --env-file server.env restart
```

The containers use `restart: unless-stopped` and should return after a host
reboot. Verify health after every host update or reboot instead of assuming the
restart policy succeeded.

Docker's local logging driver keeps five files of at most 10 MB per service.
Caddy access logs contain client IP addresses and are private operational data.
Use [`SUPPORT.md`](SUPPORT.md) for bounded log and report collection.

## Runtime hardening

The deployed topology enforces:

- Caddy-only public ingress and automatic TLS;
- HSTS, content-type, referrer, framing, and restrictive content-security headers;
- replacement of client-supplied forwarding chains;
- request body limits before FastAPI;
- global/per-client WebSocket and diagnostic admission limits;
- bounded message rates, outbound queues, diagnostic files, and filesystem work;
- read-only container roots and small no-exec temporary filesystems;
- dropped capabilities, no privilege escalation, memory/PID ceilings;
- pinned Caddy image digest, pinned Python base image, and hashed Python packages.

Changes to this boundary require the Caddy/live-stack and installer gates.

## Update configuration

The local, ignored `server.env` is the deployment configuration. Its first line must remain:

```dotenv
ZENPTT_DOMAIN=example.your-domain.net
```

To apply a changed environment file, rebuild the four-file package and run the
installer again. Do not copy only `server.env` into the active runtime; package
validation deliberately treats the delivery as one unit.

Environment values may lower but not exceed reviewed ceilings in
`server/app/security_constants.py`.

## Collect support data

From the Windows support computer:

```console
.\scripts\fetch-logs.ps1
```

The command securely runs the deployed collector and extracts a bounded bundle
under `support/inbox`. Overrides, contents, exclusions, privacy handling, and a
manual fallback are documented in [`SUPPORT.md`](SUPPORT.md).
