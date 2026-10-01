# ZenPTT configuration

Status: current

Purpose: describe every supported runtime and build-time setting, its ownership,
and the invariants that constrain changes.

Audience: developers and operators.

Authority: this document owns the catalog and meaning of supported settings and fixed
operational limits. Source files listed below remain authoritative for exact values.

Code anchors: `server.env.example`, `server.local.env`, `compose.yaml`, `Caddyfile`,
`server/app/settings.py`, `server/app/security_constants.py`,
`android/app/build.gradle.kts`, `web/vite.config.ts`, and Android/headless constants.

Update when: a supported setting, default, accepted range, fixed operational limit,
configuration source, or application procedure changes.

The authoritative values are the current working files named below. This
document explains their meaning; it does not create another configuration
source.

## Configuration files and precedence

| File | Use |
|---|---|
| `server.local.env` | Local Compose, cleartext Caddy on host port 8080, and development limits. |
| `server.env.example` | Public template for a self-hosted deployment. |
| `server.env` | Ignored, operator-specific DNS name, ports, and capacity settings. |
| `android/signing.properties.example` | Public description of the four signing fields. |
| `android/signing.properties` | Ignored key path and passwords for APK publication. |
| `server/app/settings.py` | Pydantic parsing, defaults, ranges, and cross-setting validation. |
| `server/app/security_constants.py` | Reviewed maximum security and capacity values. Environment files may lower but not raise them. |
| `compose.yaml` | Container resources, mounts, read-only policy, ports, and environment injection. |
| `examples/qrz_bot/compose.yaml` | Developer-only QRZ example settings and mounts. |
| `android/app/build.gradle.kts` | Android SDK levels, application version, signing, and default public server address. |
| `web/vite.config.ts` | Browser base path, local proxy, and build label. |
| `web/compose.yaml` | Isolated local browser preview and its ports. |
| Android Kotlin constants | Client protocol, reconnect, audio, and UI safety bounds. |

Pass the intended environment file explicitly:

```console
docker compose --env-file server.local.env up -d --build
```

`ZENPTT_ENV_FILE` in that file selects the environment file injected into the
containers. The same file also supplies Compose interpolation values.

## Compose and Caddy settings

| Variable | Local value | Public value | Meaning |
|---|---:|---:|---|
| `ZENPTT_DOMAIN` | `:80` | DNS name | Caddy site address. Replace the template value with a real DNS name. |
| `ZENPTT_ENV_FILE` | `server.local.env` | `server.env` | Environment file injected into both services. |
| `ZENPTT_HTTP_PORT` | `8080` | `80` | Host HTTP port mapped to Caddy. |
| `ZENPTT_HTTPS_PORT` | `8443` | `443` | Host HTTPS port mapped to Caddy. Local TLS is not required. |
| `ZENPTT_SERVER_MEMORY_LIMIT` | `768m` | `768m` | FastAPI container memory ceiling. |
| `ZENPTT_SERVER_PIDS_LIMIT` | `128` | `128` | FastAPI container PID ceiling. |
| `ZENPTT_CADDY_MEMORY_LIMIT` | `256m` | `256m` | Caddy container memory ceiling. |
| `ZENPTT_CADDY_PIDS_LIMIT` | `64` | `64` | Caddy container PID ceiling. |

FastAPI always listens on container port 8000 and is not published to the host.
Caddy is the only host-facing service.

Compose forces these application paths regardless of environment input:

- diagnostics: `/data/diagnostics` on the `diagnostics-data` volume;
- releases: `/data/releases` from the read-only `server/releases` bind mount.

Caddy state uses named `caddy-data` and `caddy-config` volumes.

## Headless bot settings

The QRZ configuration belongs to the developer-only example and is documented
in [`BOT_DEVELOPMENT.md`](BOT_DEVELOPMENT.md). Neither its code, settings, nor
audio assets are part of public server deployment.

### Echo supervisor

The main `compose.yaml` always starts `echo-supervisor`; no profile is required.

| Variable | Default | Meaning |
|---|---|---|
| `ZENPTT_ECHO_MAX_SESSIONS` | `16` | Supervisor capacity; valid range is 1 through 16. |
| `ZENPTT_ECHO_LOG_LEVEL` | `INFO` | Supervisor and per-session logging threshold. |
| `ZENPTT_ECHO_MEMORY_LIMIT` | `256m` | Container memory ceiling. |
| `ZENPTT_ECHO_PIDS_LIMIT` | `32` | Container PID ceiling. |

Production uses `ws://zenptt-server:8000/ws` for bot sessions and
`ws://zenptt-server:8000/internal/echo/control` for control. These private URLs
are fixed in Compose. Each session uses two receive and send queue entries and a
4 MiB PCM budget.

`ClientConfig.event_queue_size` bounds library-client events separately from
runner queues and defaults to eight.

Library bots can lower the shared PCM budget with `BotConfig.send_queue_bytes`
(default and maximum 64 MiB). It covers admitted input and output PCM together,
including active operations. `busy_retry_seconds` configures the client's correlated
PTT retries and must be at least one second. `receive_queue_size` accepts integers
from 1 through 4, `send_queue_size` accepts integers from 1 through 32, and
`shutdown_seconds` must be positive and at most 5. These limits may be lowered,
but exceeding the contract raises `ValueError` at configuration time.
After `payload_mismatch`, the standalone
bot remains idle until an explicit container restart; its normal restart policy
therefore cannot hide a terminal protocol conflict.

## Server feature settings

| Variable | Default/current value | Accepted range or rule | Meaning |
|---|---:|---|---|
| `MAX_CHANNEL_CODE_LENGTH` | `256` | `1..256` | Maximum normalized normal-channel code length. |
| `MAX_CHANNEL_PARTICIPANTS` | `12` | `1..12` | Maximum active sessions in one normal channel. |
| `RECOVERY_HORIZON_MS` | `5000` | `1000..60000`, multiple of `20` | Base recovery horizon advertised by protocol v4. |
| `LOG_LEVEL` | `INFO` | Python logging level | Application logger threshold. |

The Android client always validates channel input against its protocol maximum
of 256 characters. Lowering the server value is compatible but causes longer
codes to be rejected by the server.

`RECOVERY_HORIZON_MS` is the only configurable audio recovery value. Server and
Android derive the uplink frame limit (`H / 20`), ACK watchdog
(`clamp(0.6 * H, 500, 3000)`), server history (`max(15000, 3 * H)`), and receive
FIFO (`max(60000, server history) / 20`) from it. Floor leases, reconnect
backoff, the ping watchdog, 60-second burst limit, AudioTrack write-ahead, and
transport queues remain independent.

## Server security and capacity settings

| Variable | Current ceiling | Meaning |
|---|---:|---|
| `MAX_ACTIVE_SESSIONS` | `180` | Active WebSocket sessions admitted by one server process. |
| `MAX_ACTIVE_SESSIONS_PER_CLIENT_IP` | `30` | Active sessions admitted from one trusted client address. |
| `MAX_CONTROL_MESSAGES_PER_SECOND` | `50` | Accepted control messages per session per one-second window. |
| `MAX_AUDIO_MESSAGES_PER_SECOND` | `200` | Media envelopes admitted per session per second, including idempotent duplicates. |
| `DIAGNOSTICS_MAX_BODY_BYTES` | `131072` | Maximum diagnostic request body in both Caddy and FastAPI. |
| `DIAGNOSTICS_MAX_REPORTS` | `500` | Maximum retained report files. |
| `DIAGNOSTICS_RETENTION_SECONDS` | `604800` | Maximum report age, currently seven days. |
| `DIAGNOSTICS_MAX_UPLOADS_PER_MINUTE` | `30` | Global diagnostic upload attempts per rate window. |
| `DIAGNOSTICS_MAX_UPLOADS_PER_CLIENT_PER_MINUTE` | `10` | Diagnostic upload attempts per trusted client address. |
| `DIAGNOSTICS_MAX_CONCURRENT_SAVES` | `2` | Concurrent filesystem saves before FastAPI returns busy. |

Cross-setting validation requires:

- per-client session limit not greater than the global session limit;
- per-client diagnostic limit not greater than the global diagnostic limit;

Raising a ceiling requires editing `security_constants.py`, rebuilding the
server image, and reviewing memory, CPU, disk, and abuse consequences. An
environment-only change may lower values but cannot exceed a reviewed ceiling.

Server live-media timing diagnostics use a fixed `100 ms` threshold, keep at most
`10` events per type, and track at most `30` bursts per session. These are bounded
diagnostic constants in `server/app/media_timing.py`, not deployment settings.

## Uvicorn transport settings

`server/app/run.py` fixes these non-environment values:

| Setting | Value | Reason |
|---|---:|---|
| workers | `1` | Floor ownership and channels are process-local. |
| concurrency limit | `200` | Leaves a small reserve above active WebSocket capacity. |
| maximum WebSocket message | `4096` bytes | Matches the control and binary protocol limit. |
| WebSocket receive queue | `16` | Bounds transport-side queued messages. |
| transport ping interval | `20` seconds | Detects a lost peer without application traffic. |
| transport ping timeout | `60` seconds | Allows temporary mobile and screen-off scheduling delays. |

Android also sends a JSON `ping` every second after a successful `snapshot`.
It reconnects after ten sent pings without current-transport inbound activity;
valid controls and incoming binary traffic clear the pending-ping count. That
ping measures RTT and does not replace the transport heartbeat. Headless sends
one application ping per second and replaces the transport after ten seconds
without a pong. The participant count treats a
transport as present for three seconds after its last inbound activity without
closing a transport that ages out of the count.

## Android build configuration

The first line of `server.env` must be:

```dotenv
ZENPTT_DOMAIN=example.your-domain.net
```

`android/app/build.gradle.kts` reads that line and generates
`BuildConfig.DEFAULT_SERVER_ADDRESS` as `wss://<domain>`. The value must contain
only letters, digits, dots, and hyphens. Changing it requires rebuilding the
APK.

The optional `ZENPTT_ANDROID_LEGACY_SERVER_ADDRESS` and
`ZENPTT_ANDROID_PRE_SNAPSHOT_SERVER_ADDRESS` values are build-time-only inputs
for migrating older Android defaults. Leave them empty for a new installation.
For an existing installation, set them to the addresses embedded in its earlier
APKs. A stored custom address is preserved.

Current platform and build rules:

| Setting | Value/source |
|---|---|
| minimum Android | API 31 / Android 12 |
| target and compile SDK | API 35 |
| Java/Kotlin target | 17 |
| application version | `versionCode` and `versionName` in `android/app/build.gradle.kts` |
| debug signing | automatic local debug key, or the key specified in ignored `android/signing.properties` |
| cleartext traffic | enabled only by `src/debug/AndroidManifest.xml` |
| release/public address | `wss://` generated from `server.env` |

Increment `versionCode` for every distinct APK; never reuse a published code for
different bytes. `versionName` is user-facing release metadata.

## Browser build and local proxy

`web/vite.config.ts` sets the `/web/` asset path and the displayed `VITE_WEB_BUILD`
label at build time. The local Vite server listens on `127.0.0.1:5173` and proxies
`/ws`, `/health`, and `/app/` to Caddy. `ZENPTT_DEV_PROXY` can change that local
proxy target; its default is `http://127.0.0.1:8080`. The browser app has no
user-selectable server address. Production uses the page's origin and serves the
built files through Caddy without Vite or `ZENPTT_DEV_PROXY`. See
[`WEB_CLIENT.md`](WEB_CLIENT.md) for build and preview commands.

## Android runtime constants

| Behavior | Current value | Source |
|---|---:|---|
| PTT response timeout while awake | `2 s` | `ChannelViewModel` |
| PTT response timeout after route wake | `5 s` | `ChannelViewModel` |
| reconnect delays | `0, 0.25, 0.5, 1, 2 s`, then `5 s` | `ZenWebSocketClient` |
| reconnect jitter | `0..100 ms` | `ZenWebSocketClient` |
| application ping interval | `1 s`; reconnect after ten sent pings without current-transport inbound activity | `ZenWebSocketClient` |
| power-save timeout | default `10 min`, range `1..1440 min` | `ConnectionPreferences` |
| hardware PTT | default `off` with explicit factory SPP rule; reconnect `1, 3, 7, 10 s`, then `10 s` | `ConnectionPreferences`, `HeadsetController` |
| headset setup | quiet `3 s`; holds `4/6/4/5/4 s`; reaction `2 s`; minimum hold `1.5 s`; per source `120 s`, `2048` events, `256 KiB` | `ButtonLearner`, `HeadsetController`; see `HEADSET_PTT.md` |
| deferred PTT deadline | `5 s` from the original physical press; reconnect does not extend it | `ChannelViewModel` |
| frequency history | four ordinary frequencies plus fixed `ECHO` | `ConnectionPreferences` |
| route verification timeout | `3 s` | `AudioRouteController` |
| capture format | mono PCM, `16 kHz`, 20 ms frames | `AudioConstants` |
| capture pacing | at most one new frame per `20 ms`, including Bluetooth SCO reads | `AudioCapture` |
| Opus target bitrate | mono `16 kbit/s`, constrained VBR | `OpusCodec` |
| Opus implementation | pinned libopus `1.6.1`, SHA-256 verified by CMake | `CMakeLists.txt` |
| maximum burst | `60 s` / `3000` encoded frames, independent of `H` | server domain and `OutgoingAudioStore` |
| playback format | mono PCM, `48 kHz` | `AudioConstants` |
| sender unacknowledged memory | `H / 20` frames retained for `H` | snapshot `AudioPolicy` |
| receiver FIFO | `max(60000, max(15000, 3 * H)) / 20` frames; no local eviction | snapshot `AudioPolicy` |
| uplink ACK watchdog | `clamp(0.6 * H, 500, 3000)` without cumulative progress | snapshot `AudioPolicy` |
| application WebSocket queue | `8 KiB` | `ZenWebSocketClient` |
| snapshot readiness watchdog | `5 s` after WebSocket open | `ZenWebSocketClient` |
| receive playout start | five encoded frames / `100 ms`, fixed after underrun | `ReliableAudioState` |
| AudioTrack start threshold | one decoded frame, at most `20 ms` | `AudioPlayback` |
| actual AudioTrack write-ahead | at most `100 ms` of written, unplayed PCM | `AudioPlayback` |
| compressed playback handoff queue | at most `4 s` / `200` frames before upstream retry | `AudioPipeline`, `AudioPlayback` |
| playback drain deadline | expected queued PCM plus `0.5 s` grace, with the total clamped to `0.5..5 s` | `PlaybackDrain` |
| channel-free indicator | damped `680 Hz`, `40 ms`, amplitude `0.22` | `AudioIndicator` |
| PTT-queued indicator | `32 ms` wooden tap at `0..4800 ms` in `800 ms` steps, peak `0.14` | `AudioIndicator`, `AudioPipeline` |
| PTT-rejected indicator | `360 Hz`, `70 + 50 + 70 ms`, amplitude `0.35` | `AudioIndicator` |
| interrupted indicator | sweep `700 -> 350 Hz`, `220 ms`, amplitude `0.45` | `AudioIndicator` |
| PTT partial wake lock | at most `65 s`, covering the `60 s` burst boundary and cleanup | `PttForegroundService` |
| update download | maximum `200 MiB`; retries after `1, 3, 7 s`; `60 s` read and `10 min` operation limits | `AppUpdateClient` |
| maximum report details | `48000` characters | `DiagnosticReport` |
| receive/source timing diagnostics | intervals over `100 ms`; at most `10` events per type/burst and `30` retained | `ReceivePathDiagnostics`, `SourceSendDiagnostics` |

Change these values only with their protocol, timing, battery, or memory
invariants and tests. The only runtime audio setting is power saving; service
indicators are always enabled.
A fresh install selects `ECHO`. Hardware PTT stores versioned `HeadsetSettings`
JSON in `headset_settings_v1`, including enabled state and a complete source,
device selection, event address, recognition rule, and Hold/Toggle configuration.
The initial explicit BM008 factory rule is disabled. Migration preserves legacy
`bm008Enabled` and media assignment choices. Invalid configurations stay disabled
until configured or reset; they never silently activate a factory fallback.
Only successful synchronous persistence accepts a new setup. Reset restores the
disabled factory rule and Undo restores the entire previous value.
SharedPreferences keeps packaged-default snapshots for the server address and
power-save timeout so updates replace old defaults while preserving custom values.
Legacy BM008 keys are retained for migration; the hardware PTT choice is migrated
once and is then independent of those legacy defaults. The server advertises `H`
in each snapshot. There are no separate user-selectable sender, receiver,
server-history, or playback-buffer durations.

## Applying changes

- Server environment change: edit the selected env file and recreate containers.
- Reviewed server ceiling or launcher change: edit code, rebuild the image, and
  run the full server and Caddy gates.
- DNS/default client address change: edit the first line of local `server.env` and
  rebuild the APK.
- Android version or client constant change: rebuild and republish the APK.
- Browser source or build setting change: rebuild `web/dist` and, for public
  deployment, the hosting package.
- Public deployment change: rebuild the four-file hosting package and run the
  installer/update procedure.

Verification commands are in [`TESTING.md`](TESTING.md). Deployment steps are
in [`DEPLOYMENT.md`](DEPLOYMENT.md).

The headset enabled flag also selects the communication-audio policy: Off uses the
phone microphone and loudspeaker; On prefers Bluetooth with phone fallback. Pending
changes wait for a phrase boundary in service memory and are not persisted early.
