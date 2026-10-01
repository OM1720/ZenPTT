# ZenPTT product requirements

Status: current and normative

Purpose: define the supported product, its user-visible outcomes, and its intentional
scope without duplicating implementation or wire-level detail.

Audience: product owners, developers, testers, operators, and AI coding assistants.

Authority: this document owns product intent. `protocol.md`, `UI_DESIGN.md`, and
`BLUETOOTH_PTT_AUDIO.md` own their respective technical contracts; source code and
executable tests show the currently implemented behavior.

Code anchors: `android/app/src/main`, `server/app`, `headless/src/zenptt_headless`,
and `web/src`.

Update when: supported use cases, platforms, user-visible behavior, security outcomes,
or intentional product scope changes.

## Product purpose

ZenPTT is a small Push-to-Talk system for Android. Users connect to one server and one
channel code, take turns transmitting, and automatically hear the active transmitter.
The product also provides a private ECHO test, optional BM008 or assigned-button PTT, an
independent Python headless client and reference bots, support diagnostics, and server-hosted
APK updates.

The design favors predictable bounded behavior over feature breadth. It deliberately
uses one server process with in-memory channel state and has no account or message
service.

A browser client for Chrome on Windows supports connecting to
the page's server, selecting channels, live audio prepared by the Connect gesture,
grant-gated mouse/Space PTT, bounded session/audio recovery, the Halo UI, microphone
selection, channel history, and a local diagnostic download. The static `/web/`
build is part of the hosting package. See [`WEB_CLIENT.md`](WEB_CLIENT.md).

## Supported system

- Android 12/API 31 or newer, with phone-oriented portrait and landscape layouts.
- Desktop Chrome on Windows for the browser audio client; installed mobile browser
  behavior requires physical checks on each target device.
- One FastAPI/Uvicorn worker behind Caddy as the only public ingress.
- Docker Compose for local development and the supported Ubuntu 24.04 deployment.
- Public `wss://` transport through Caddy; debug-only `ws://` for trusted local use.
- English application UI.
- One active channel per client and one active transmitter per normal channel.

Implementation topology and ownership are defined in
[`ARCHITECTURE.md`](../ARCHITECTURE.md). Configuration and supported deployment limits
are defined in [`CONFIGURATION.md`](CONFIGURATION.md).

## Core user experience

The Android application provides two screens:

- Main: connect/disconnect, select a frequency, see the participant count, and use the
  Concentric Halo press-and-hold PTT control.
- Settings: server health, PTT button setup, audio power saving, diagnostics, battery settings,
  and application updates.

The foreground service, rather than the UI, owns the active WebSocket, PTT state,
Bluetooth control, audio pipeline, notification, and wake lock. Destroying or recreating
the activity must not create a second session. Exact visual and accessibility behavior
is defined in [`UI_DESIGN.md`](UI_DESIGN.md); user procedures are in
[`USER_GUIDE.md`](USER_GUIDE.md).

## Normal-channel communication

- Channel codes use uppercase `A-Z`/`0-9` segments separated by single periods.
- The server arbitrates the floor atomically and does not maintain a waiter queue.
- Microphone audio starts only after the matching server grant.
- Release makes the floor available while bounded recovery may finish the old audio.
- Listeners receive server-resolved frames and losses in strict order and remain in the
  receiving state until local playback drains.
- Transport loss may resume the logical session and bounded audio custody, but must not
  create duplicate or untracked transmissions.
- A final recovery failure requires the user to release and press PTT again.

The compatible control messages, media framing, recovery policy, sequencing, HTTP
routes, and failure semantics are defined only in [`protocol.md`](protocol.md).

## ECHO

`ECHO` is a private resumable session used to verify capture, transport, server,
headless response, and playback. The server assigns one real headless bot through a
short-lived ticket, and both participants use the ordinary v4 media path. ECHO sessions
are isolated from each other and audio remains memory-only.

## BM008, audio, and background operation

- UNIWA BM008 control uses an already paired Bluetooth SPP device.
- The common, non-transmitting wizard checks SPP, BLE, then media keys (or a selected
  external HID input) using bounded observed-signal rules. It shows the current
  method and stops at the first verified result. Three training cycles and two independent
  verification cycles are required. One uniform setup is saved; the initial
  disabled setup is an explicit BM008 rule. Physical compatibility, especially
  with the screen locked, must be tested. See `HEADSET_PTT.md`.
- SPP commands control PTT; Android SCO/BLE communication devices carry speech audio.
- **Headset on/off** controls hardware PTT and audio together. Off explicitly uses
  the phone microphone and loudspeaker. On uses Bluetooth when available, with phone
  fallback; manual changes and Bluetooth recovery wait for phrase boundaries.
- Grant and service indicators follow the selected communication route.
- The route may sleep only while capture and playback are idle; the WebSocket and SPP
  control connection remain active.
- An active session runs in a foreground service and is not intentionally stopped by
  activity recreation, screen lock, or removal from Recents.
- Explicit Disconnect releases all owned network, Bluetooth, audio, wake-lock, and
  notification resources.
- Force stop, Android's Active apps stop control, and reboot may terminate the session;
  ZenPTT does not restart it automatically.

The coupled hardware and audio invariants are defined in
[`BLUETOOTH_PTT_AUDIO.md`](BLUETOOTH_PTT_AUDIO.md).

## Headless client and reference bots

The independent Python package implements protocol v4 without importing server code.
It owns reconnect/resume, ordered burst assembly, Opus, serialized transmission,
bounded queues, logical-session fencing, and bounded shutdown. A handler receives one
completed burst and may return immutable PCM or no response.

The required Echo supervisor runs one isolated headless response session for each
personal ECHO user and is included in the server hosting package. The QRZ bot is a
runnable, tested developer example that joins an ordinary channel and responds with
its own generated QRZ Morse audio; its code and audio are excluded from the production
image and delivery. The runtime API is defined in [`HEADLESS_CLIENT.md`](HEADLESS_CLIENT.md),
and example development is defined in [`BOT_DEVELOPMENT.md`](BOT_DEVELOPMENT.md).

## Diagnostics and updates

ZenPTT provides local sharing and bounded server upload of redacted diagnostic reports.
Reports must not contain audio payloads, conversation audio, secrets, or clear normal
channel codes. Retention and support handling are defined in [`SUPPORT.md`](SUPPORT.md).

The server publishes validated version metadata and immutable APK artifacts. Android
downloads within a fixed bound, verifies size and SHA-256, and then delegates user
consent, signature, and version enforcement to the Android package installer. There is
no silent installation.

## Security and resource outcomes

The supported system must preserve:

- Caddy-only public ingress and public TLS;
- replacement of client-supplied forwarding chains;
- strict and bounded WebSocket and HTTP input;
- global and per-client admission limits;
- bounded network queues, audio retention, diagnostics, filesystem work, and bot PCM;
- safe public errors without internal tracebacks;
- no conversation audio in logs, reports, or persistent storage;
- read-only container roots, declared writable volumes, dropped capabilities, and
  resource ceilings;
- a single Uvicorn worker while channel state remains process-local.

Exact configurable and fixed limits are cataloged in
[`CONFIGURATION.md`](CONFIGURATION.md).

## Acceptance

Relevant automated checks in [`TESTING.md`](TESTING.md) must pass. Changes involving
physical Bluetooth, Android audio routing, audible behavior, screen-off operation,
public TLS, or reboot also require the applicable scenario in
[`MANUAL_TESTING.md`](MANUAL_TESTING.md). Requirement-to-evidence traceability is kept
in [`RELIABILITY_MATRIX.md`](RELIABILITY_MATRIX.md).

## Intentional exclusions

See [`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md) for the current exclusions and
their operational effects. The supported browser client is described above;
there is no native iOS client.
