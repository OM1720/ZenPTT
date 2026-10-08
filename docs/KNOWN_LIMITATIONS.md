# Known limitations

Status: current

Purpose: list intentional constraints of the implemented ZenPTT system.

Audience: users, developers, operators, product owners, and AI coding assistants.

Authority: this document owns intentional exclusions and acknowledged operational
constraints of the current product.

Code anchors: `docs/PRODUCT.md`, `ARCHITECTURE.md`, and the affected subsystem code.

Update when: an exclusion is implemented, a supported guarantee changes, or a new
intentional constraint is accepted.

- There are no user accounts, identities, authentication, or authorization.
  Privacy of a normal channel depends on an unguessable channel code.
- One server process is mandatory. There is no horizontal scaling or shared
  floor-control state.
- One client joins one channel at a time. A normal channel accepts at most the
  configured participant limit, currently 12.
- One headless bot instance serves one ordinary channel. Its response is heard
  by every eligible listener on that channel. Two automatic responders can
  trigger an indefinite exchange; no loop detection is provided.
- Headless jobs and audio are RAM-only. There is no durable job delivery after
  restart, plugin registry, or background-handler scheduling. The Echo supervisor
  provides only bounded per-session bot lifecycle management.
- The four-file server hosting package and production headless image include the
  required Echo runtime but exclude the QRZ example code, profile, and audio.
- Channels, membership, and floor ownership are memory-only and disappear on
  process restart.
- There are no names, contacts, presence list, invitations, roles, private
  calls, text messages, call history, or persistent channel metadata.
- Conversation audio is never written to disk. The server temporarily retains
  `max(15 seconds, 3 * recovery horizon)` of encoded history in RAM. ECHO uses
  the same temporary channel history as ordinary conversations.
- A cumulative uplink ACK is not a playback guarantee. Server restart, Android process
  termination, explicit Disconnect, or a new channel incarnation loses the
  corresponding RAM-only audio state.
- Android Force stop, the system Active apps stop control, and phone reboot end
  the foreground session. ZenPTT does not restart a session after boot.
- Device-specific battery managers may suspend networking during long
  screen-off periods unless unrestricted battery use is allowed.
- Installing the browser client as a web app adds a launcher icon and standalone
  window only. It does not provide guaranteed background operation, screen-off
  reception, Bluetooth PTT, notifications, wake locks, or offline operation. The
  active page retries a lost connection only within its recovery window; an
  installed web app has no separate background recovery. Mobile microphone,
  playback, and lifecycle behavior remain dependent on the browser and device.
- Hardware PTT learns bounded key, delimited SPP, and BLE message/state/pulse
  rules. Arbitrary binary framing, encryption, checksums, and unknown activation
  commands are not inferred. Only one setup is stored. Global media rules can
  respond to another device with the same key; ordinary HID may require the app
  on screen. Locked-screen delivery depends on Android and the accessory.
  Physical acceptance of the new wizard remains required, including BM008 with
  protocol hints disabled. See `HEADSET_PTT.md`.
- SPP connection does not guarantee that Android can establish the matching
  SCO/BLE communication audio route on every device or firmware version.
- There is no guaranteed seamless handover between Wi-Fi and mobile networks.
  Logical resume restarts ordered delivery from the client's playback cursor.
  An active burst continues only when the authoritative snapshot still shows
  the client as floor owner; otherwise its retained tail is drained before a
  replacement floor request proceeds.
- The Android UI is phone-oriented and currently English-only. Portrait and
  landscape layouts are supported, but tablets are not a separate design target.
- Native libopus, Bluetooth routing, battery policy, and audible timing remain
  device-dependent even though the repository provides automated and manual checks.
- RTT and PTT grant time are support diagnostics, not mouth-to-ear latency.
  Precise latency measurement requires a separate acoustic method.
- Temporary congestion is recoverable only within the configured horizon and
  available RAM. A larger horizon preserves more speech but deliberately
  increases head-of-line blocking and audible queue delay. Playback stays 1x,
  so a backlog cannot be skipped or caught up by time compression.
- Even when every audio frame reaches the browser, jitter can empty its playback
  queue and insert short silence. A longer startup buffer reduced measured
  stalls but also delayed speech onset; no new permanent buffer was selected.
  The two-second floor renewal lease favors quicker channel release over keeping
  a burst alive through longer sender blackouts. See the dated
  [`poor-link study`](research/poor-link-2026-10-07.md).
- Control and audio share one ordered WebSocket/TCP stream. Head-of-line
  blocking can delay PTT release until `burst_end` arrives or the lease expires.
  A server writer blocked for ten seconds closes that socket and relies on
  process-local resume; it cannot preserve audio across a client/server process
  exit or expired history.
  An unintentional socket loss or transport replacement does not release the
  floor by itself. Explicit Disconnect stops local resources immediately, but
  its graceful control/close delivery has only a one-second opportunity; an
  already broken network therefore falls back to the same floor lease.
  The recovery horizon is an operator setting; floor and transport timeouts are
  independent protocol constants.
- Eligibility and slow-listener isolation mean two listeners are not guaranteed
  to receive identical complete subsets. Android renders authoritative missing
  positions as PLC or a short static cue. Headless represents them as PCM silence
  and absolute loss ranges; decode errors are reported separately.
- Android does not evict receive audio to survive FIFO overflow. It resumes once
  from the playback cursor; overflow again at the same cursor is treated as a
  protocol error and requires a manual reconnect.
- A server `payload_mismatch` rejection also requires a manual reconnect. The
  client intentionally discards its local custody state because replaying the
  conflicting bytes cannot repair the server's already accepted frame.
- Support reports and bundles are point-in-time diagnostics, not centralized
  monitoring, alerting, or backup. Rotated logs and expired reports are not recoverable.
- APK updates are distributed by the ZenPTT server and installed through the
  Android package installer. There is no Google Play publication or silent update.
