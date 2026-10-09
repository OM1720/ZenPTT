# ZenPTT browser client

Status: current; mobile physical acceptance remains pending

Purpose: own browser-client boundaries, local development, and verification.
The network contract remains in [`protocol.md`](protocol.md).

Audience: developers, testers, and operators.

Authority: this document owns browser behavior, audio and recovery boundaries,
local development, and subsystem verification steps. `protocol.md` owns the wire
contract; `TESTING.md` owns the full gate; `MANUAL_TESTING.md` owns physical checks.

Code anchors: `web/src`, `web/compose.yaml`, and `web/Dockerfile`.

Update when: browser behavior, ownership, prerequisites, or verification changes.

## Current scope

The audio client supports Chrome on a Windows desktop. Its installable web-app
metadata targets Android Chrome, iPhone Safari, and desktop Chromium browsers without
changing runtime capabilities. The client supports connection to the page's server,
normal channel selection, private ECHO admission, participant counts, and explicit
disconnect, live audio, mouse/Space-held PTT, and bounded recovery. **Connect** also
prepares microphone and playback; allow the browser's microphone permission when prompted.
PTT remains disabled until audio is ready. Audio preparation failures show **Retry audio**
without disconnecting the session. Listening needs no initial PTT press.
Main and Settings follow the Android Halo design. Settings and history persist
locally. Physical audio and browser installation require operator checks.
Recovery expiry requires Connect.
ECHO displays one user, following Android's visual contract; its assigned bot is
still present in the server's unmodified participant count.

The PWA layer consists only of a manifest, launcher icons, and standalone display
metadata. It has no service worker, offline cache, custom install prompt, background
service, notification integration, wake lock, or Bluetooth PTT. While the page is
active, the client retries a lost connection within the recovery window described
below. Installation does not provide background recovery or relax browser audio
restrictions.

`ZenPttClient` owns one transport independently of React. `connect`, `disconnect`,
`pttDown`, `pttUp`, `enableAudio`, `selectMicrophone`, `ping`, `diagnosticReport`,
`subscribe`, and `getState` form its UI boundary.
Changing channels waits for the old socket
to close, bounded by one second. Pending opens and stale socket events are fenced.
Page exit disconnects on a best-effort basis; a browser crash still follows server
presence and session expiry. React StrictMode and normal rerenders do not create
another client.

## Halo and browser settings

[`UI_DESIGN.md`](UI_DESIGN.md) and the Android implementation define the visual
baseline: white/black only, 48 px icon actions, an unlabeled channel field with a
32 px count column, and a Halo up to 380 px. Both Main and Settings use a centered
vertical layout at every window width.
The channel field is above the Halo. The ring has a 2 px stroke and
sixteen 22.5-degree sectors with cap-compensated gaps: 10% during good-quality
transitions, 25% for fair audio, and 50% for poor audio. Stable good quality is a
solid ring. Transmitting fills the core with a 24 px radius difference from the
outer ring. Transitions rotate once per six seconds. Reduced-motion preferences
disable rotation and blinking without hiding state from assistive technology.

Outgoing quality uses oldest unacknowledged frame age: below 10% of the recovery
horizon is good, 10–20% fair, and at least 20% poor. Incoming quality reflects
rendered burst losses (one to three fair, more than three poor) or blocked playback.
The last active path remains displayed at rest; RTT never changes the ring. Exact
RTT, grant delay, gap count, and blockage are available to screen readers and the
diagnostic report. Screen reader/Enter activation toggles PTT; navigation, blur,
page hiding, or pointer cancellation releases it. Outside text inputs, Space is
reserved for PTT on Main, even when a navigation button retains focus. On Settings
it is consumed without transmitting or activating buttons. Enter activates focused
buttons. Text fields retain normal Space input. Key repeat and key release cannot
activate navigation after returning from Settings.

Browser adaptations are deliberate: Main centers ZenPTT between power and Settings
in its header. Its footer offers an Android download on the right; the hover hint
uses the version from `/app/latest` and links to that version's APK. If metadata
is unavailable, the latest-download link remains available without a claimed version.
Connect starts audio preparation synchronously from the user gesture,
before waiting for the WebSocket handshake. The core starts listening only after
both admission and audio preparation complete. Preparation errors survive a later
snapshot and offer Retry audio; disconnect cancels pending preparation. The
saved channel is restored as a draft and requires Connect after reload, and only
browser-applicable settings are shown. Main/Settings navigation preserves the
session and receiving audio but releases PTT. History contains the four most
recent ordinary channels plus ECHO. Invalid submission opens history; Escape
dismisses it without resubmitting. Channel history and the selected microphone ID
are saved under `zenptt.web.preferences.v1`; denied/full/corrupt storage falls back
to in-memory settings with an explanatory error on a failed save.

Settings stacks Server/Ping, Audio, and Diagnostics vertically. Audio groups the
microphone selection and system-default output. Web build appears in small text
in the footer. There is no Controls section: hovering the Halo shows
"Hold the Halo or Space to talk. Release to listen." Other controls have contextual
hover hints and retain accessible names. Ping opens
an unjoined v4 connection for at most five seconds and never changes membership.
Microphone selection applies immediately; a live change replaces only the input
stream, keeping the playback graph. Failure keeps the old input. A saved device
that is no longer available requires selecting System default. Home is disabled
while microphone replacement is pending. Browser permission is requested only
through Connect, Retry audio, or an explicit microphone selection.

Local audio cues cover grant (100 ms, microphone capture delayed until it ends),
channel free, rejection, and interrupted transmission. Android's queue/wake cues
do not apply to this client's direct request/deny flow. Cues use the same running
AudioContext and default output; they are not fed into Opus. Local icon notices
are included at `/web/licenses/ICONS-LICENSE.txt`.

The diagnostic JSON is below 64 KiB: a whitelist of build/protocol, current state,
counts/timings, and at most 256 state transitions. It excludes audio, packets,
channel codes/history, session/member IDs, resume tokens, device IDs/names, URLs,
and raw error messages. It is downloaded locally and is never uploaded. Neither
diagnostics nor recovery queues are written to persistent browser storage.

The client offers `zenptt.v4`, waits for the matching snapshot, and sends application
pings every second. Open, snapshot, and unresponsive-transport waits are bounded.
A valid server operation error is displayed through the fixed protocol messages;
it does not turn a joined connection into a transport failure. Malformed data
closes the session without reflecting input in the UI or logs. All server controls
and media envelopes are validated. Audio preparation registers the eligible `listen`
cursor. Server-delivered frames are decoded and played while the burst is open.

The protocol module validates exact fields, duplicate keys, numeric values,
directions, UUIDs, byte limits, ranges, and packet lengths. Lossless JSON parsing
preserves 64-bit timestamps and counters, represented as bigint where needed.
Only bounded numbers become JavaScript numbers. Tests use independent literals,
not solely encoder/decoder round trips.

## Audio ownership and limits

`BrowserAudio` requests/resumes a 48 kHz AudioContext and microphone access from
the explicit user action. The microphone track stays disabled outside a granted
transmission. Its mono source feeds an AudioWorklet; there is no local microphone
monitor. Output uses the system default device. Chrome's echo cancellation, noise
suppression, and automatic gain control are requested without assuming a specific
hardware input sample rate.

The worklet low-pass filters and resamples capture from the context's 48 kHz to
16 kHz. libopus encodes 320-sample, mono, 20 ms frames at 16 kbit/s constrained VBR,
with DTX disabled and the voice signal setting matching Android. Receive decoding
produces 960 samples at 48 kHz. A new burst has a 150 ms initial playout delay;
playback is paced by the audio render clock, including short sealed bursts.
A temporary underrun within the same burst does not repeat the initial delay.
Authoritative gaps use at most three PLC frames, followed by silence and a decoder
reset. A server history reset discards the superseded queue. Frames from later
bursts do not overtake queued audio. The receive frame bound follows the v4 policy;
overflow reports an error and closes the session rather than silently evicting audio.

The core correlates grants with request IDs. It does not start capture or send
Opus before a grant. Releasing a pending request cancels it; a late grant cannot
start capture. Releasing a granted request stops the worklet, pads a partial final
frame, and sends `burst_end` with the exact exclusive sequence watermark. Requests,
stop confirmation, and server end confirmation have deadlines. The 3,000-frame /
60-second limit is enforced on both the main thread and the worklet render clock.
Mouse and Space share one hold: releasing one source while the other remains held
does not finish transmission. Pointer cancellation, window blur, and page hiding
release all holds. Space in a text field does not transmit. Returning to the page
never presses PTT automatically.

Disconnect, channel change, terminal protocol/audio failure, or recovery expiry stops the microphone,
disconnects the graph, closes the AudioContext, and discards in-memory audio.
Connect prepares audio again for a new connection. Permission denial, a missing or busy
microphone, codec load failure, and audio interruption have fixed user-facing errors.
No browser audio is stored on disk or uploaded outside `/ws`.

## Recovery ownership and limits

`ZenPttClient` separates the logical session from replaceable WebSockets. Resume
uses the opaque token and an increasing connection generation; stale socket
callbacks cannot affect the new transport. Retry delays are 0, 250, 500, 1000,
2000, then 5000 ms. Retries stop after `max(15000, 3H)` ms from the first failure,
where `H` is the snapshot's recovery horizon. Successful resume preserves the
microphone and playback graph. Explicit disconnect cancels all retries.

`UplinkBuffer` retains only original Opus bytes, bounded by `H / 20` frames and
age `H`. A cumulative exclusive ACK frees earlier packets. Resume replays retained
unacknowledged packets in bounded envelopes and repeats the immutable final
watermark when necessary. Duplicate ACKs do not renew the acknowledgement watchdog
(`clamp(0.6H, 500, 3000)` ms). Local expiry never invents server gaps.

During an outage, a previously granted, physically held capture continues for at
most `H`. Repeated connection attempts cannot extend its worklet deadline. If the
floor was lost, the old tail finishes before a new request can start while still
held. Horizon exhaustion requires a fresh physical release and press. Server
restart or resume rejection discards old custody and joins a fresh session, with
an explicit notice and no automatic transmission. Payload conflicts and terminal
protocol errors close the logical session and require Connect.

`ReceiveBuffer` keeps bounded identities of unplayed packets and server gaps.
The worklet acknowledges the cursor after rendering each frame and a sealed
burst's end. Resume requests that cursor, deduplicates buffered replay, and leaves
queued PCM in order. A history reset clears the superseded queue and changes the
playback epoch so old acknowledgements cannot advance the new cursor. A slow
consumer cannot grow the queue beyond `max(60000, 3H) / 20` frames; overflow fails
explicitly. All recovery tokens, queues, and cursors exist only in memory.

## Production hosting and delivery

`scripts/build-hosting-package.ps1` includes the verified `web/dist` tree inside
`zenptt-server.zip`. Its four external files and APK release metadata schema remain
unchanged. Caddy serves `/` and `/web/` directly from a read-only mount; no Vite, Node, or
Emscripten runs on the production host. `/web` redirects to `/web/`; missing files
return 404 rather than the application HTML. Main and Settings use in-memory
navigation, so no server-side SPA route fallback is required.

HTML, the web app manifest, launcher icons, and license notices use
`Cache-Control: no-cache`; successful hashed JS/CSS/WASM responses use one year of
immutable caching. Caddy provides the manifest, PNG, JavaScript, and WASM MIME types
and `nosniff`. CSP is scoped to `/` and `/web/*`: same-origin scripts, styles, images,
manifest, fetches and WebSockets, plus `wasm-unsafe-eval` for codec compilation. It
permits neither inline JavaScript nor general `unsafe-eval`.
See the [Caddy header documentation](https://caddyserver.com/docs/caddyfile/directives/header)
and [MDN script-src reference](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/script-src).

The build includes Opus, icon, and production npm dependency license texts under
`/web/licenses/`. The installer requires the manifest, launcher icons, licenses, and
compiled assets, then compares every served web file with the staged files during
activation. Failed activation
uses the existing rollback path; rollback to a pre-web runtime remains supported.
Linux lifecycle tests use distinct fixture HTML across updates and also install and
update the exact four-file delivery, with local HTTP/cgroup overrides outside its ZIP.
These checks do not claim public DNS/TLS or physical microphone coverage.

## Reproducible codec build

`web/codec/CMakeLists.txt` uses the same
[libopus 1.6.1 source and SHA-256](https://opus-codec.org/release/stable/2026/01/14/libopus-1_6_1.html)
as Android. `web/Dockerfile` pins Emscripten 4.0.23 by its Linux amd64 image digest
and uses [emcmake](https://emscripten.org/docs/compiling/Building-Projects.html).
The standalone WASM has fixed memory, no filesystem, no threads, and no external
runtime scripts. Each worklet owns one encoder and decoder. Vite hashes the WASM
and worklet assets in the production build; the Opus license is copied to
`/web/licenses/OPUS-LICENSE.txt`. Generated files are ignored by Git.

The build also compiles an independent native C program calling libopus directly.
The mandatory codec check cross-decodes native and WASM packets, verifies duration
and signal energy, compares decoded samples, and checks encoder/decoder reset.

## Start the isolated local preview

Install Docker Desktop with its Linux engine running and Docker Compose 2.24.4+
(the override uses `!override`). From the repository root:

```console
docker compose -p zenptt-web --env-file server.local.env -f compose.yaml -f web/compose.yaml up -d --build
```

The Dockerfile pins the Node image by digest, installs the locked dependencies,
and runs lint, unit tests, type checking, a production build, and native/WASM
cross-decoding before startup. The first Emscripten image download can take several minutes.
Open <http://127.0.0.1:5173/web/> in Chrome. The health probe is
<http://127.0.0.1:5173/health>. The development server proxies `/health`, `/app/` and
`/ws` through Caddy; FastAPI remains private. The separate Compose project keeps
its own volumes and uses loopback ports 5173 and 18080. Source changes require a
rebuild in this mode. This preview is not a production hosting configuration.

Stop it without removing volumes:

```console
docker compose -p zenptt-web --env-file server.local.env -f compose.yaml -f web/compose.yaml down
```

For host development, first build the isolated preview. Copy its Linux-generated
codec into the ignored source directory from the repository root:

```powershell
New-Item -ItemType Directory -Force web/src/generated | Out-Null
docker compose -p zenptt-web --env-file server.local.env -f compose.yaml -f web/compose.yaml cp web:/app/src/generated/. web/src/generated
New-Item -ItemType Directory -Force web/public/licenses | Out-Null
Copy-Item web/src/generated/OPUS-LICENSE.txt web/public/licenses/OPUS-LICENSE.txt
```

Use Node 22.13+ (Node 24 matches the isolated build), then
run `npm ci` inside `web`. With the ordinary local Caddy stack on port 8080,
`npm run dev` serves the same preview URL. The developer-only `ZENPTT_DEV_PROXY`
environment variable can select a different local Caddy port. There is no
user-selectable server or connection URL in the web application. Public hosting
requires HTTPS; plain HTTP is accepted only on localhost/loopback.

## Verification

Inside `web`, `npm run lint`, `npm run typecheck`, and `npm test` terminate.
The authoritative Linux build and the same checks run during the Docker build.
The full repository gate includes `scripts/test-web.ps1`, which builds inside the
pinned Linux image, exports `web/dist` and the development codec, starts the isolated
`zenptt-web-gate` stack, and runs Chrome against production Caddy on port 18081.
Node and installed Chrome are needed on the test host. CI uses the same gate.
The normal gate stops its stack; use `scripts/test-web.ps1 -KeepRunning` to retain
it for acceptance. `scripts/build-web.ps1` builds/exports without starting a server.

With the isolated stack running, run the real-server cases inside its pinned
Node environment:

```console
docker compose -p zenptt-web --env-file server.local.env -f compose.yaml -f web/compose.yaml exec -T web npm run test:live
```

These cases create unique channels and close their sessions. They test two
participants, keepalive beyond the presence deadline, repeated Connect,
disconnect, rejoin, channel switching, and admission through the actual ECHO
supervisor. They do not use a mock server or claim audio coverage.

For the desktop browser cases, install Google Chrome and run inside `web`:

```console
npm ci
npm run test:browser
npm run test:audio
```

Playwright launches the installed Chrome headlessly. Audio cases use Chrome's
synthetic WAV microphone with real `getUserMedia`, real AudioContext/AudioWorklet,
the built WASM, and the real WebSocket server/ECHO bot. Test observers tap the
worklet output with an AnalyserNode and a bounded test-only PCM tap, and inspect socket activity; they do not replace
encoding, decoding, resampling, rendering, or networking. Checks prove streaming
before release in both directions, ECHO output, no send before grant, and microphone
and context cleanup. Full PCM checks compare duration, envelope correlation and
spectral similarity with the microphone input; separate real-Opus negative controls
reject silence, noise, wrong tones, reordered sections and truncation.
Permission cases use actual Chrome permission denial and retry.
Recovery cases use Chrome's synthetic microphone and Playwright's WebSocket routing
to interrupt forwarding to the real server. They check lost grants, ACKs and end
confirmations, exact packet replay, playback deduplication, exhausted recovery,
resource cleanup, and mouse/Space cancellation. Worklet tests render with the real
WASM under a controlled clock, including slow-consumer overflow and capture deadlines.
The stack must already be
running. `ZENPTT_WEB_URL` overrides the local preview URL for tests only; it does
not change the app's same-origin policy. Browser reports and traces are ignored
build/test artifacts. Do not upload traces: they may contain test session tokens.

The production gate runs `npm run test:restart` last, after the browser and audio
suites. It restarts only `zenptt-web-gate`'s `zenptt-server`, checks a new member
identity after resume rejection, and verifies that a held PTT cannot restart until
released and pressed again. The runner sets `ZENPTT_WEB_TEST_PROJECT=zenptt-web-gate`
and `ZENPTT_WEB_URL=http://127.0.0.1:18081`; the restart configuration rejects missing
or different values. Docker Engine must be accessible. The preview stack and the
public deployment are not restarted.

## Physical acceptance

Use [`MANUAL_TESTING.md`](MANUAL_TESTING.md#browser-client) for real microphone,
speaker, browser interaction, Android interoperability, installed mobile browsers,
and hosted-page checks. The automated commands above cover protocol, codec,
recovery, and static-hosting regressions.
