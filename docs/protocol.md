# ZenPTT network protocol v4

Status: current and normative

Purpose: define compatible WebSocket clients and the existing HTTP endpoints.

Audience: client/server developers and protocol testers.

Authority: this document owns the compatible WebSocket and HTTP contract. Android UI,
audio rendering, and hardware behavior are outside its scope.

Code anchors: `server/app/protocol.py`, `server/app/session.py`,
`android/app/src/main/java/app/zenptt/ControlProtocol.kt`,
`android/app/src/main/java/app/zenptt/AudioFrameCodec.kt`, and
`headless/src/zenptt_headless/protocol.py`.

Update when: message framing, schemas, ordering, recovery semantics, close/error codes,
or public HTTP routes change.

Protocol v4 uses `GET /ws` with the required WebSocket subprotocol
`zenptt.v4`. The binary media envelope version is `4`. This is a coordinated,
v4-only client/server cutover; v3 is not accepted.

All protocol state is RAM-only. A client or server process exit may discard
unplayed or unacknowledged speech. There is no playback ACK and no proof that a
listener heard a frame.

## Control framing and common types

Control messages are UTF-8 JSON text messages. Every message is one object with
exactly the fields defined below. Missing fields, extra fields, invalid JSON,
and wrong JSON types produce an `invalid_message` error. The application message
limit is 4,096 UTF-8 bytes. The production WebSocket transport closes with
`1009` before dispatching a larger message; an oversized text message delivered
directly to the application produces `invalid_message`. JSON integers must be
integral number values; booleans and numeric strings are not accepted as
integers. Object field order is not significant.

The complete control-message catalog is:

| Direction | Message types |
|---|---|
| client to server | `join`, `join_echo`, `join_echo_bot`, `resume`, `listen`, `ptt_request`, `ptt_cancel`, `burst_end`, `ping`, `disconnect` |
| server to client | `snapshot`, `resume_rejected`, `channel_state`, `ptt_granted`, `ptt_denied`, `uplink_ack`, `audio_rejected`, `burst_started`, `burst_released`, `burst_gaps`, `burst_sealed`, `listen_reset`, `ptt_ended`, `pong`, `error` |

Common field constraints are:

| Field | Constraint |
|---|---|
| `burst_id` | Canonical lowercase, hyphenated UUID string. |
| `burst_index` | Integer from `0` through `2^31 - 1`. |
| `first_sequence`, `next_sequence`, `final_next_sequence` | Integer from `0` through `2^32 - 1`; a v4 burst itself contains at most 3,000 positions. |
| `request_id` | Non-empty string of at most 128 characters; opaque to the server. |
| `resume_token` | Non-empty opaque string of at most 256 characters. |
| `generation` | Integer from `2` through `2^31 - 1` in a `resume` command. A fresh join starts at generation `1`. |

Sequence watermarks are exclusive. For example, `next_sequence=32` describes
positions `0` through `31`. Clients must treat server-provided identifiers and
tokens as opaque even when the current implementation generates UUIDs.

## Recovery policy

The server has one recovery setting, `RECOVERY_HORIZON_MS`. Its default is
5,000 ms; valid values are 1,000 through 60,000 ms in 20 ms increments. Every
snapshot carries only the authoritative base value:

```json
{"audio_policy":{"recovery_horizon_ms":5000}}
```

Let `H` be that value. Both implementations derive the same policy:

| Value | Formula |
|---|---:|
| uplink recovery frames | `H / 20` |
| uplink retention and maximum late age | `H` |
| ACK watchdog | `clamp(0.6 * H, 500, 3000)` |
| Fair / Poor uplink age | `0.1 * H` / `0.2 * H` |
| server history and resume | `max(15000, 3 * H)` |
| Android receive FIFO frames | `max(60000, server history) / 20` |

Fixed v4 properties are 20 ms Opus frames, mono 16 kbit/s constrained VBR,
100 ms initial playout, and a 60-second maximum burst. Floor leases, PTT
request timeouts, the ten-second ping watchdog, reconnect backoff, AudioTrack
write-ahead, transport queues, and the burst duration limit do not derive from
`H`.

Android uses frame-count bounds and the existing maximum Opus packet size; it
does not silently evict retained audio or apply a second FIFO byte limit. The
server keeps independent 1 MiB per-channel and 160 MiB per-process ceilings.
Reaching either ceiling produces an explicit `server_busy` rejection.

## Session and floor

A transport starts with exactly one join command or `resume`. A successful
reply is a `snapshot` containing membership, generation, channel incarnation,
eligibility, current floor, and `audio_policy`. A higher resume generation
fences an older transport.

```json
{"type":"join","channel":"ROOM1"}
{"type":"join_echo"}
{"type":"resume","resume_token":"opaque","generation":2}
```

Normal channel codes are uppercased and match
`[A-Z0-9]+(?:\.[A-Z0-9]+)*`. The input is not trimmed and is at most 256
characters, or a lower configured server limit. `ECHO` is reserved and cannot
be entered through `join`. Android requests it with the fieldless `join_echo`
command. The server creates a private ephemeral
channel instance and withholds the first snapshot until its assigned bot joins;
that first snapshot reports two participants. A supervisor-created bot joins
with `{"type":"join_echo_bot","ticket":"opaque"}`. The ticket is a one-time,
assignment-bound 256-bit credential that expires after five seconds. Bot join is
an implementation interface; applications must never request or persist tickets.

A successful `join` or `resume` returns the exact snapshot shape below. `floor`
is `null` when the channel has no current owner; otherwise `owned` states
whether the recipient owns that floor.

```json
{
  "type":"snapshot",
  "channel":"ROOM1",
  "member_id":"00000000-0000-0000-0000-000000000001",
  "resume_token":"opaque",
  "generation":1,
  "channel_incarnation_id":"00000000-0000-0000-0000-000000000002",
  "revision":1,
  "participant_count":1,
  "eligible_from_index":0,
  "next_burst_index":0,
  "audio_policy":{"recovery_horizon_ms":5000},
  "floor":null
}
```

The nested non-null floor shape is:

```json
{"burst_id":"00000000-0000-0000-0000-000000000003","burst_index":8,"owned":true}
```

`revision`, `eligible_from_index`, and `next_burst_index` are non-negative
integers; `participant_count` and `generation` are positive integers. The
server follows the snapshot with `channel_state` and sends that event again
when the revision, live participant count, next burst index, or floor changes:

```json
{"type":"channel_state","revision":2,"participant_count":2,"next_burst_index":8,"floor":null}
```

Inbound activity keeps a transport present for three seconds. Presence only
affects `participant_count`; membership, resume eligibility, the floor, and
retained-audio eligibility follow their own lifetimes.

A rejected resume leaves the transport unjoined and has this shape:

```json
{"type":"resume_rejected","reason":"invalid_token"}
```

The reason is `invalid_token`, `expired`, or `stale_generation`. An accepted
higher generation fences the older transport, which is closed with code `1001`.

PTT requests remain idempotent per logical member:

```json
{"type":"ptt_request","request_id":"uuid"}
{"type":"ptt_granted","request_id":"uuid","burst_id":"uuid","burst_index":8,"lease_remaining_ms":5000}
{"type":"ptt_denied","request_id":"uuid","reason":"channel_busy"}
```

`ptt_denied.reason` is `channel_busy`, `invalid_state`, or `server_busy`.
Replaying the request ID of the current open owned burst returns its original
grant. Reusing an ID whose burst is draining or sealed returns `invalid_state`.
`lease_remaining_ms` is a non-negative integer. A new grant starts with a
five-second lease, each envelope that stores new audio renews it to two seconds,
and the 60-second burst duration remains an absolute cap.

A pending or granted request can be canceled with:

```json
{"type":"ptt_cancel","request_id":"uuid"}
```

Canceling an unknown request is an idempotent no-op. A known request is sealed
and the owner receives its terminal state. The same `ptt_ended` shape is sent
after explicit end, cancellation, lease expiry, or the duration limit:

```json
{"type":"ptt_ended","burst_id":"00000000-0000-0000-0000-000000000003","burst_index":8,"state":"draining","reason":"released","final_next_sequence":125}
```

`state` is `draining` or `sealed`. `final_next_sequence` is a non-negative
integer when known and `null` when the floor ended before the sender declared a
watermark. Reasons visible here are `released`, `complete`, `expired`,
`canceled`, `lease_expired`, or `duration_limit`.

Accepted audio renews the floor lease, but recovery never extends it. Explicit
release, cancel, lease expiry, or the 60-second burst limit frees the floor.
`burst_released` may therefore precede completion of that burst's audio.

While PTT remains held after transport loss, Android continues capture for at
most `H` from the first transport loss and within the burst's original
60-second limit. Repeated reconnect attempts do not restart either deadline.
Resume continues the same burst when the snapshot confirms floor ownership. If
the floor is gone, the client stops the old capture, uploads its retained tail
and `burst_end`, then requests a new floor only after the old burst has
completed. Expiry of `H` stops capture and requires a physical release and new
press.

## Burst lifecycle and cumulative uplink ACK

A burst is `open`, `draining`, then `sealed`. The sender declares its immutable
exclusive watermark when capture ends:

```json
{"type":"burst_end","burst_id":"uuid","final_next_sequence":125}
```

`final_next_sequence` is at most 3,000. Repeating the same watermark is
idempotent. A conflicting watermark, a watermark below an accepted frame, or a
frame at or beyond it is rejected with `invalid_range`.

### Binary media envelope

All multibyte integers use network byte order (big-endian). The fixed header is
24 bytes:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 1 | Envelope version, exactly `0x04`. |
| 1 | 1 | Media type: `0x01` uplink or `0x02` downlink. |
| 2 | 16 | RFC 4122 UUID bytes for `burst_id`. |
| 18 | 4 | Unsigned `first_sequence`. |
| 22 | 2 | Unsigned packet count. |

The header is followed by one entry per packet: an unsigned 16-bit packet
length and exactly that many Opus bytes. Each packet represents one 20 ms frame,
and packets in an envelope occupy consecutive sequence positions.

| Limit | Value |
|---|---:|
| complete binary message | 4,096 bytes |
| packets per message | 1 through 50 |
| one Opus packet | 1 through 1,275 bytes |
| one burst | 3,000 packets / 60 seconds |

The final sequence position must not overflow unsigned 32-bit space. Empty
messages, trailing bytes, truncated entries, the wrong version or direction,
and any violated limit make the complete envelope invalid. Envelopes may arrive
out of order. Exact duplicates are idempotent, including retained frames whose
nominal position is now older than `H`; a different payload for an accepted
sequence rejects the complete envelope.

Expiry is position-scoped. If one otherwise valid envelope contains both
expired positions and acceptable positions, the server leaves the expired
positions lost and stores the acceptable frames atomically. The normal
cumulative `uplink_ack` and `burst_gaps` then describe the resolved prefix. An
envelope containing only expired positions is rejected with `expired`.

For uplink validation, a frame's nominal position is the grant time plus its
sequence multiplied by 20 ms. A position more than 500 ms ahead of server time
is `invalid_range`; a position more than `H` behind server time is `expired`.

Uplink acknowledgement is cumulative:

```json
{"type":"uplink_ack","burst_id":"uuid","next_sequence":32}
```

`next_sequence=32` permanently resolves every position below 32: the server
stored its frame or authoritatively declared it lost. The client deletes those
positions and never resends them. It resends unresolved retained positions
after reconnect, starting at the last cumulative cursor.

The server does not invent future positions for an open burst. Once a known
missing position is older than `H`, it becomes permanently lost. The server
sends `burst_gaps`, advances the cumulative ACK, and may then forward later
frames. Resolution is timer-driven and does not require another unique uplink
packet. A late packet for that range receives:

```json
{"type":"audio_rejected","burst_id":"uuid","first_sequence":24,"next_sequence":32,"reason":"expired"}
```

This operation-scoped event is also used for a well-formed envelope or
`burst_end` that cannot be accepted. Its reason is `unknown_burst`, `expired`,
`invalid_range`, `payload_mismatch`, or `server_busy`. The half-open range
identifies the complete rejected operation. A malformed binary envelope instead
produces the generic `invalid_message` error.

`payload_mismatch` is a terminal client protocol conflict: the sender clears
its process-local custody state, closes the logical session, and waits for a
manual reconnect. Automatically replaying the conflicting local frame would
repeat the same rejection because the server keeps the first accepted payload.

If the final watermark is still unknown after floor release, the server waits
`H`, derives it from the highest known sequence, and seals the burst. Every gap
range is final and non-retractable. Only the server creates loss events; local
sender retention expiry does not.

## Strict ordered downlink

The listener registers its current playback cursor:

```json
{"type":"listen","burst_index":8,"next_sequence":40}
```

The server emits each eligible stream in this order:

```text
burst_started -> frames and/or authoritative gaps -> burst_sealed
```

`burst_released` is independent and may appear earlier. No frame or gap is sent
before every preceding sequence is resolved, and a later burst does not pass an
unresolved earlier burst. Lifecycle controls have these exact forms:

```json
{"type":"burst_started","burst_id":"uuid","burst_index":8}
{"type":"burst_released","burst_id":"uuid","burst_index":8,"reason":"released"}
{"type":"burst_gaps","burst_id":"uuid","burst_index":8,"ranges":[{"first_sequence":41,"count":2}]}
{"type":"burst_sealed","burst_id":"uuid","burst_index":8,"final_next_sequence":50,"reason":"expired"}
```

Every gap range has a non-negative `first_sequence` and positive `count`.
Ranges are ordered, non-overlapping, and final. `burst_released.reason` is
`released`, `canceled`, `lease_expired`, `duration_limit`, `member_left`, or
`echo`. `burst_sealed.reason` is `complete`, `expired`, `canceled`, or
`member_left`.

After reconnect the server starts from the `listen` cursor and sends retained
history as fast as the socket budget allows. Playback remains strictly 1x. A
16 KiB per-socket outbound budget isolates slow listeners. History production
pauses when that budget is full and resumes when the socket writer frees space;
the cursor advances only for accepted outbound events. A socket send that makes
no progress for ten seconds is closed with code `1001`; resume then recovers from
the listener's playback cursor without blocking other clients. This writer
watchdog is fixed and does not derive from `H`.

If requested history is no longer retained, the server sends an authoritative
reset. The client does not reconstruct discarded server history:

```json
{"type":"listen_reset","burst_index":9,"next_sequence":0,"reason":"expired"}
```

`listen_reset.reason` is `expired`, `not_eligible`, or `invalid_cursor`. The
returned cursor is authoritative and is the position the client must register
or continue from. Positions below the returned history floor are skipped rather
than converted into client-generated gaps, PLC, or a static cue. A requested
`next_sequence` above the 3,000-frame burst limit is a syntactically valid
unsigned value but produces `invalid_cursor`.

## Keepalive and intentional disconnect

The application keepalive echoes both integer fields unchanged:

```json
{"type":"ping","id":7,"sent_at_ms":123456}
{"type":"pong","id":7,"sent_at_ms":123456}
```

`id` ranges from `0` through `2^31 - 1`; `sent_at_ms` ranges from `0` through
`2^63 - 1`. Ping is allowed before join. Android sends one ping per second and
replaces the transport after ten sent pings without current-transport inbound
activity. Valid controls and incoming binary messages clear its pending-ping
count. Headless instead measures ten seconds since the last application pong.

An explicit leave uses the exact command below and WebSocket close code `1000`:

```json
{"type":"disconnect"}
```

The server removes the logical member and releases any owned floor. A normal
close with code `1000` has the same intentional-leave meaning. Other transport
loss preserves resumable state for the derived server-history window.

## Client playback integration

The wire contract delivers only ordered frames, authoritative gaps, resets, and burst
boundaries. Android-specific FIFO overflow handling, Opus PLC, the long-loss cue,
decoder reset, AudioTrack pacing, and UI drain behavior are defined in
[`BLUETOOTH_PTT_AUDIO.md`](BLUETOOTH_PTT_AUDIO.md). Headless conversion to PCM and
handler delivery are defined in [`HEADLESS_CLIENT.md`](HEADLESS_CLIENT.md).

## ECHO

ECHO uses the same v4 audio, floor, ordered-listen, resume, generation-fencing,
cumulative ACK, gaps, memory, and 60-second burst rules as a normal channel.
Replay is performed by one real headless bot session per Android session.
Each Android connection has a distinct hidden channel instance whose snapshot
exposes the public code `ECHO`. The bot decodes received Opus to PCM and encodes
a new response after the source burst completes.

If the bot is unavailable, `participant_count` is one and `ptt_request` is denied
with `invalid_state`; an active source burst is canceled. A replacement restores
the count to two. The Android and bot may resume within the normal recovery
window. The internal supervisor uses `/internal/echo/control` with subprotocol
`zenptt.echo-control.v1`; its status endpoint is `/internal/echo/status`. Neither
interface is part of the public application protocol, and Caddy blocks the whole
`/internal` namespace.

## Errors and compatibility

Strict schemas reject missing, extra, or incorrectly typed fields. Malformed
controls produce this exact envelope; stable media rejection instead uses
`audio_rejected`:

```json
{"type":"error","code":"invalid_message","message":"Invalid message"}
```

| Code | Message | Meaning |
|---|---|---|
| `invalid_message` | `Invalid message` | Malformed JSON/binary, wrong fields or types, unsupported command, or duplicate join/resume. |
| `invalid_channel` | `Invalid channel` | Channel code failed normalization or configured length validation. |
| `channel_full` | `Channel is full` | The normal channel reached its configured participant limit. |
| `not_joined` | `Join a channel first` | The operation requires an authoritative joined member. |
| `invalid_state` | `Action is not available` | The operation is not valid in the current session or domain state. |
| `server_busy` | `Server is busy` | A bounded server resource cannot accept the operation. |
| `internal_error` | `Server error` | An unexpected internal failure occurred; no internal detail is exposed. |

Malformed application messages normally leave the socket open. Admission,
traffic, and backpressure use these close codes:

| Code | Meaning |
|---:|---|
| `1000` | Intentional disconnect and membership removal. |
| `1001` | Unintentional server-side transport termination or replacement, including fencing by a higher resume generation. |
| `1002` | Required `zenptt.v4` subprotocol was not offered. |
| `1008` | Inbound rate limit or 16 KiB server outbound-byte budget exceeded. |
| `1009` | WebSocket message exceeded the 4,096-byte transport limit. |
| `1013` | Global or per-client-IP active-session admission limit reached. |

The default inbound limits are 50 control messages and 200 binary media
envelopes per transport per second; deployment configuration may lower them. The
Android sender independently admits a send only when the encoded message plus
OkHttp's queued bytes fits its 8 KiB transport budget.

Android requires a snapshot within five seconds after open and replaces a
transport after ten application pings without inbound activity or lack of cumulative ACK progress
for the derived watchdog interval. Playback underrun and receive FIFO growth do
not replace the transport. Protocol v3 is not accepted and there is no v3/v4
negotiation or translation path.

## HTTP endpoints

HTTP responses use their own schemas, separate from WebSocket control messages.
Caddy serves the browser client at `GET /` (also `/web/`); FastAPI exposes the endpoints below
through that proxy. `/docs`, `/redoc`, and `/openapi.json` are disabled.

| Endpoint | Successful response | Failure behavior |
|---|---|---|
| `GET /health` | `200`, `{"status":"ok"}` | Describes process availability, not end-to-end audio delivery. |
| `GET /app/latest` | `200`, release metadata described below | `404` when current metadata or the matching APK is unavailable or invalid. |
| `GET /app/download` | Current APK, `application/vnd.android.package-archive` | `404` when the current release is unavailable. |
| `GET /app/releases/{version_code}/download` | Retained immutable APK of that positive integer version | `404` for a missing/nonpositive version; `422` for a non-integer path value. |
| `POST /diagnostics` | `201`, `{"report_id":"260724-AB12"}` | Schema validation `422`, body limit `413`, admission/concurrency limit `429`, or unavailable storage `503`. |

APK downloads support HTTP Range requests, including `206` partial responses.
`/app/latest` has exactly four fields:

| Field | Value |
|---|---|
| `version_code` | Positive integer. |
| `version_name` | 1 through 40 characters from `0-9`, `A-Z`, `a-z`, `.`, `_`, `-`. |
| `sha256` | 64 hexadecimal characters. |
| `size_bytes` | Positive integer matching the APK size. |

The publication command verifies the artifact hash. Android independently
checks size and SHA-256 before installation and rejects downloads above 200 MiB.
Release storage and immutable publication are described in
[`server/releases/README.md`](../server/releases/README.md).

Diagnostic requests have exactly these fields; extra fields are rejected:

| Field | Value |
|---|---|
| `schemaVersion` | `1`. |
| `createdAtMs` | Non-negative integer timestamp. |
| `appVersion` | String, 1 through 40 characters. |
| `androidVersion` | String, 1 through 80 characters. |
| `networkStatus` | String, 1 through 40 characters. |
| `headsetStatus` | String, 1 through 80 characters. |
| `audioRoute` | String, 1 through 40 characters. |
| `details` | String, at most 48000 characters; channel values must be redacted and audio payload markers are rejected. |

FastAPI application errors use `{"detail":"..."}`; schema/path validation
uses FastAPI's `detail` error list, not the WebSocket `error` envelope. An invalid
diagnostic `Content-Length` reaching the route produces `400`. Rate-limited
diagnostic uploads include `Retry-After: 60`; the concurrent-save busy response
does not. Proxy-generated errors may use Caddy's response format. See
[`SUPPORT.md`](SUPPORT.md) for privacy and retention and
[`CONFIGURATION.md`](CONFIGURATION.md) for the enforced ceilings.
