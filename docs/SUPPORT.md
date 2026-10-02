# ZenPTT support and diagnostics

Status: current

Purpose: explain diagnostic data, privacy boundaries, report retrieval, and
bounded log collection for local and public servers.

Audience: users, support engineers, developers, and operators.

Authority: this document owns diagnostic contents, privacy boundaries, report storage,
collection, and initial triage.

Code anchors: Android diagnostics modules, `server/app/diagnostics.py`,
`scripts/collect-logs.sh`, and `scripts/fetch-logs.ps1`.

Update when: report fields or bounds, redaction, retention, collection output, support
commands, or triage signals change.

## Support paths

ZenPTT exposes two user actions:

- **Share debug info** creates a redacted local text report and opens Android's
  share sheet. Nothing is uploaded automatically.
- **Send debug info** posts a structured report to `/diagnostics` and returns a
  short code such as `260724-AB12`.

Use the short code to identify one server-side JSON file. It is not an account,
access token, or permanent identifier.

## Public bug reports

Reproducible software bugs can be submitted through the
[GitHub Bug report form](https://github.com/OM1720/ZenPTT/issues/new?template=bug_report.yml).
Issues are public, so do not attach a full diagnostic report, raw logs, audio,
channel codes, credentials, or server addresses. Share only the relevant
redacted details after reviewing them. Security vulnerabilities belong in the
private reporting channel described in [SECURITY.md](../SECURITY.md).
External pull requests are not reviewed, and issue response times are not
guaranteed; see [CONTRIBUTING.md](../CONTRIBUTING.md).

## Report contents

The structured report contains:

- schema and creation time;
- application and Android versions;
- current network, headset, and audio-route summaries;
- bounded connection and PTT events;
- RTT, grant timing, sequence gaps, and audio queue metrics;
- recovery horizon and derived limits, cumulative uplink cursor, oldest
  unacknowledged-frame age, and retransmission count;
- exact server-declared gaps, current and maximum receive FIFO size, aggregate
  fast-history sessions/frames/bytes and starting cursors, and bounded reconnect,
  reset, overflow, and rejection events;
- bounded headset source, connection, setup, accepted/rejected rule, key-event,
  and synthetic-release metadata; full observed payload streams are not uploaded;
- available/selected/routed Android audio devices;
- route preparation, power-save, tone, capture, playback, and wake-lock events;
- foreground-service and battery-optimization state.

The client limits report details to 48000 characters. Bounded in-memory event
collections prevent diagnostics from growing for the lifetime of the service.
Diagnostic collection and sharing failures must not crash the active session.

## Privacy contract

Reports and logs must not contain:

- audio payload bytes or recorded conversation audio;
- a clear normal-channel code;
- Android signing keys, SSH keys, passwords, or environment files;
- server tracebacks returned to a client.

Client reports use only `channel=none`, `channel=ECHO`, or
`channel=normal (redacted)`. The server rejects other `channel=` values and any
`audio_payload=` marker. Server logs hash normal channel codes and retain only a
short sanitized suffix of request IDs.

Reports may contain operational data such as server address, client IP in Caddy
logs, device model, Android version, Bluetooth identifiers, and route state.
Treat support data as private operational material.

## Server retention and limits

Caddy and FastAPI both enforce `DIAGNOSTICS_MAX_BODY_BYTES`. FastAPI also
applies global and per-client rate limits and bounds concurrent filesystem
writes.

Accepted reports are stored under `/data/diagnostics/<report-code>.json` in the
`diagnostics-data` Docker volume. On each save, the server removes reports older
than `DIAGNOSTICS_RETENTION_SECONDS` and trims the directory to
`DIAGNOSTICS_MAX_REPORTS`. Current values are documented in
[`CONFIGURATION.md`](CONFIGURATION.md).

Retention is best-effort support storage, not monitoring, backup, or durable
business data.

## Inspect a local report

For a validated report code:

```console
docker compose --env-file server.local.env exec zenptt-server cat /data/diagnostics/<report-code>.json
```

List retained reports without copying the complete volume:

```console
docker compose --env-file server.local.env exec zenptt-server sh -c "ls -1 /data/diagnostics"
```

Do not expose the diagnostics directory through Caddy or another file server.

## Local logs

Application server:

```console
docker compose --env-file server.local.env logs --since 10m zenptt-server
```

Caddy access/proxy logs:

```console
docker compose --env-file server.local.env logs --since 10m caddy
```

Android process:

```console
$zenPttPid = adb shell pidof app.zenptt
adb logcat --pid=$zenPttPid
```

Use bounded `--since` output for support. `docker compose logs -f` is suitable
for interactive operation but not for attaching a finite diagnostic snapshot.

## Fetch a public support bundle

From the Windows development computer:

```console
.\scripts\fetch-logs.ps1
```

By default the script:

1. reads `ZENPTT_DOMAIN` from local `server.env`;
2. connects to `root@<domain>` using normal OpenSSH configuration;
3. runs `/opt/zenptt/runtime/collect-logs.sh` through `sudo`;
4. downloads and validates the archive;
5. extracts it under `support/inbox/<UTC-timestamp>`.

Override the SSH target or installation path when needed:

```console
.\scripts\fetch-logs.ps1 -SshTarget admin@example.your-domain.net -RemoteDirectory /srv/zenptt
```

A non-root account needs passwordless `sudo` for the collector. Windows must
provide `ssh`, `scp`, and `tar`. Credentials are resolved by OpenSSH and are
never read from repository files.

## Bundle contents

The downloaded directory contains:

- `summary.txt` with collection time, installed version, and container state;
- `server.log` with retained application output from at most the last seven days;
- `caddy.log` with retained proxy/access output from at most the last seven days;
- `diagnostics/` with currently retained uploaded reports.

The bundle excludes:

- `server.env` and other configuration secrets;
- APK files and signing material;
- Caddy certificates and private keys;
- SSH configuration and credentials.

The remote collector atomically replaces one server-side
`zenptt-support-latest.tar.gz`; it does not accumulate archives. Docker uses
rotated local logs, so older output may already be unavailable.

## Manual public collection

On the server:

```console
cd /opt/zenptt
sudo bash runtime/collect-logs.sh
```

Copy `/opt/zenptt/support/zenptt-support-latest.tar.gz` to the support
computer and extract it in a private working directory.

## Initial triage order

1. Confirm the application/server versions and the exact server address.
2. Probe `/health` through the same address used by the phone.
3. Identify whether the failure is connection, floor control, SPP input, audio
   route, capture, playback, power saving, or update installation.
4. Obtain a report code or local debug text immediately after reproducing.
5. Inspect server and Caddy logs for the same time window.
6. Compare the observation with the invariants in
   [`BLUETOOTH_PTT_AUDIO.md`](BLUETOOTH_PTT_AUDIO.md) and the protocol contract.
7. Use [`MANUAL_TESTING.md`](MANUAL_TESTING.md) for one bounded reproduction.

For live-audio delay, correlate `source_send_events` from the transmitting
phone, `media_timing` lines from `server.log`, and `receive_path_events` from
the receiving phone by burst suffix and sequence. Interpret the first large
interval in order:

| First large interval | Location |
|---|---|
| `source_send_gap` | Capture or client uplink enqueue path |
| `server_receive_gap` with a normal source interval | Uplink network/TCP |
| `server_handoff_delay` or `server_send_delay` | Server processing or outbound queue |
| Receiver `ws_gap` with normal server send timing | Downlink network/TCP or Android network callback |

Server `send_bytes` completion is the final application boundary; it does not
prove that the corresponding TCP bytes have reached the phone.

Do not add ad-hoc audio recording or unbounded debug logging to diagnose a
support case.
