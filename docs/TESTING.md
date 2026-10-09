# Testing ZenPTT

Status: current

Purpose: define the automated checks that must pass for the current codebase.

Audience: developers, CI maintainers, and AI coding assistants.

Authority: this document owns executable verification commands and gate composition.
The tests themselves are authoritative for pass/fail behavior.

Code anchors: `scripts/test-*.ps1`, `server/tests`, `headless/tests`,
`android/app/src/test`, and `android/app/src/androidTest`.

Update when: a verification command, prerequisite, gate composition, or automation
boundary changes.

Device-dependent checks are intentionally separate in
[`MANUAL_TESTING.md`](MANUAL_TESTING.md). Requirement-to-evidence traceability is
maintained in [`RELIABILITY_MATRIX.md`](RELIABILITY_MATRIX.md).

## Prerequisites

- PowerShell for the repository orchestration scripts (`pwsh` on Linux CI);
- project virtual environment at `.venv` with `server[dev]` and `headless[dev]` installed;
- FFmpeg executable on PATH for the headless offline MP3 tests;
- JDK 17, Android SDK 35, NDK 27.0.12077973, and CMake 3.22.1;
- `ANDROID_HOME` set, or the SDK installed at the standard Windows location;
- Docker Engine and Docker Compose for container and live-stack checks;
- Node 22.13+ (24 recommended) and installed Google Chrome for browser checks;
- an API 31+ emulator or phone only for connected instrumentation.

Create the server environment when needed:

```console
python -m venv .venv
.venv\Scripts\python.exe -m pip install -e ".\server[dev]"
.venv\Scripts\python.exe -m pip install -e ".\headless[dev]"
```

The headless gate also runs the offline poor-link analyzer tests and Ruff over
`scripts/poor-link`. The network experiment below is manual and never runs in CI.
The selected transport behavior and dated comparison results are in the
[`poor-link study`](research/poor-link-2026-10-07.md).

## Poor-link measurement battery

The standalone battery runs two headless Linux containers built from the current
checkout against the existing test host. It does not change server configuration
or application versions. Docker Engine, `plink` with a loaded Pageant key, and
the project `.venv` are required. The test containers need Docker `NET_ADMIN`;
the traffic controls affect only those containers.

Create ignored `private/poor-link-ssh.json` locally:

```json
{
  "host": "test-host.example.net",
  "user": "test-operator",
  "host_key": "SHA256:replace-with-verified-fingerprint",
  "runtime_path": "/opt/zenptt/runtime",
  "server_url": "wss://test.example.net/ws"
}
```

Use the actual pinned SSH host key and the runtime directory for the existing
stand. The SSH account must read `docker compose logs` there. The public script
contains no operator address, account, or key. For a short control run:

```powershell
.\scripts\test-poor-link.ps1 -Profiles baseline -Repeats 1
```

For the full sequential matrix (12 profiles, three repeats and both directions):

```powershell
.\scripts\test-poor-link.ps1
```

`-Profiles`, `-Repeats`, `-ServerUrl`, `-SshConfigPath`, and `-OutputDirectory`
select a subset or override local settings. Each case uses a separate channel.
Quote multiple profile names, for example `-Profiles 'sender-24,listener-24'`.
The first run downloads and verifies the pinned LibriSpeech speech archive and
prepares the first 60 seconds with Linux FFmpeg. The archive is from
[OpenSLR SLR12](https://www.openslr.org/12/) under CC BY 4.0; source URL,
SHA-256, file selection, and attribution are in `scripts/poor-link/audio-source.json`.
Audio remains in ignored `acceptance/artifacts/poor-link/audio-cache`.

The default run applies sender egress shaping at 24/32/48 kbit/s with
200 ± 50 ms delay, listener ingress policing at the same rates, unstable
200 ± 100 ms with 1% loss, and sender or listener blackouts. Three fixed seeds
are used. Each case sends three 20-second speech bursts two seconds apart;
the blackout starts five seconds after the second grant. The sender does not
wait for receipt before the next burst. The receiver has up to 30 seconds
after the sender completes. The host is checked before and after the run.

Each timestamped result directory contains `source.json`, `results.json`,
`report.md`, and separate case folders with settings, bot stdout/stderr,
received PCM, `tc` counters, SSH server logs, and analysis. `complete` in a
protocol event does not establish full delivery: the report compares decoded
frames with all 1,000 planned frames per burst. Cases with missing or invalid
evidence are `test_system_error`; speech loss on an impaired link is
`measured_degradation`. Results are measurements without a preset quality
threshold. Initial server sessions are matched by the case channel. Resumed
sessions are correlated with a unique bot reconnection within one second;
`resume_correlations` preserves timestamps, role, and transport count. This
is time correlation because server resume events omit the channel identifier.
Ambiguous matches are excluded. Backlog counts represent queue-limit events,
not lost frames or distinct incidents. On normal
interruption, the wrapper preserves available bot logs and `tc` statistics,
marks the unfinished case, and removes only containers named in that run.

New server runs also report completed `outbound_backpressure` intervals with
duration, blocked-offer count, peak queued bytes, and recovery status. Legacy
`outbound_backlog_limit` events remain readable in saved older runs. An interval
does not itself indicate dropped audio. An unmatched final sequence after a
forced release is diagnostic evidence; classify missing planned speech as
measured degradation unless a separate protocol violation is established.

For a diagnostic bandwidth sweep, opt-in profiles add sender shaping at
64/80/96 kbit/s, listener policing at those rates, and listener ingress shaping
through an IFB interface at 48/64/80/96 kbit/s. The default matrix is unchanged.
Fractional sender outages `sender-blackout-1.25`, `sender-blackout-1.5`, and
`sender-blackout-1.75` bracket the two-second renewal lease. For example:

```powershell
.\scripts\test-poor-link.ps1 -Profiles 'sender-64,listener-shaped-64,sender-blackout-1.5' -Repeats 3
```

The probe logs sanitized outgoing controls, completed local socket sends,
sender terminal decisions, retention expiry, negotiated audio policy, and
one-second Linux interface counters. Socket-send completion establishes local
enqueue only. It does not prove server receipt. Interface counters include
protocol overhead and retransmissions. ACK resolves both stored and lost
positions, so an ACK watermark alone does not establish delivered speech.

Analyze saved experiments without contacting the stand:

```powershell
.venv\Scripts\python.exe scripts/poor-link/investigate.py `
  acceptance/artifacts/poor-link/<run-id>/results.json `
  --output acceptance/artifacts/poor-link/<investigation-id>
```

Supply multiple result files to combine runs. The investigation preserves raw
terminal evidence, per-second traffic, backlog timing, and profile comparisons.
Older probes have no local-final or socket-send evidence; missing measurements
remain explicit. Reported full-burst end delay excludes truncated bursts.

For the fixed R/A/B/AB experiment, run the same 17-profile matrix against each
saved server bundle and compare the four result directories offline:

```powershell
.venv\Scripts\python.exe scripts/poor-link/compare.py `
  --r acceptance/artifacts/poor-link/<r-run> `
  --a acceptance/artifacts/poor-link/<a-run> `
  --b acceptance/artifacts/poor-link/<b-run> `
  --ab acceptance/artifacts/poor-link/<ab-run> `
  --output-dir acceptance/artifacts/poor-link/<comparison-id>
```

The comparison writes `comparison.json` and `comparison.md`. It reads saved
bot events to distinguish media messages from frames and includes sampled
interface byte counts. Each new `source.json` records server source hashes,
the SHA-256 of the delivery ZIP installed on the test host at run start, and
an HTTP Date clock-offset estimate with uncertainty for server/client timing.

For the separate three-party lease experiment, install a saved AB package with
one fixed renewal lease, then pass that exact package directory to the runner.
It verifies that the installed ZIP and declared lease match before starting.
Each case records sender, receiver, and contender logs, the sender's actual
network outage counters, server logs, and contender request/grant times:

```powershell
.\scripts\test-poor-link-lease.ps1 -PackageDirectory <saved-package-dir> -LeaseSeconds 2
```

Repeat after installing the 3- and 4-second packages. Compare the three saved
outage runs offline with `scripts/poor-link/compare_lease.py --lease-2 <run>
--lease-3 <run> --lease-4 <run> --output-dir <ignored-report-dir>`.
Disappearance, voluntary release, and cancellation can be run separately with
`-Modes 'disappear,voluntary,cancel'`. These network commands are never part of CI.

## Browser audio through isolated network proxies

For the fixed browser buffer study, `scripts/poor-link/browser_buffer.py build
--output <ignored-study-directory>` snapshots browser sources once and builds
P100/P150/P200 in the pinned Linux image. Each candidate runs the web checks and
render-clock startup tests, retains its source, one-constant patch, image ID,
and exact static-file manifest. Candidates are generated independently of the
working default, which is now 150 ms. The grant cue's separate 100 ms capture
delay is unchanged.

After SSH authentication and the installed ZIP are verified, use the same
study directory and `--expected-bundle <installed-zip-sha256>` with the
`historical`, `pilot`, and `matrix` actions, in that order. Historical repeats
run the two original short scenarios ten times each. The pilot runs baseline
and unstable seed 1009 for all three buffers. The full matrix has 45 lightweight
runs (five profiles, three seeds, three buffers) and six separate PCM runs.
Matching saved pilot attempts are retained; changed methods or candidate
manifests cannot silently replace them. Use a new directory and the proxy
runner's `--retry-of` for an explicit retry.

The opt-in `--series` proxy scenario plans three 20-second captures per
direction. A browser timer releases PTT relative to the worklet capture-start
clock, independently of sent/received counts. Two seconds separate completed
sends; receiver playback is not a prerequisite for the next send. After the
third send the receiver has 30 seconds to finish. Browser and process limits
are 360 and 420 seconds. Application timeouts remain unchanged. The main
matrix sets `--lightweight-observer`; the six PCM runs use the same method
with the observer enabled. PCM capture has a separate bounded budget and
does not determine network frame completeness.
The scenario also has a 35-second sender-idle guard per press; a timeout ends
that directional series and preserves its reason before attempting the reverse
direction. Later end markers must not erase that timeout in offline analysis.
The timer rechecks the render-clock deadline and is canceled when its capture
ends, expires, is superseded, or the context closes. Early release must not
leave a timer that can release the next capture.
Journal checkpoints cross the automation boundary as packed JSON strings;
large structured event arrays can themselves delay the main thread. No journal
copy is awaited between sends. The pause deadline uses the preceding sender
terminal event, and analysis retains actual inter-send pauses.

Playwright tracing is disabled by default in all browser audio scenarios because
trace collection can delay main-thread audio callbacks. The normal web gate
retains lightweight journals under each test's `audio-diagnostics/` output path.
`scripts/test-web.ps1` preserves separate `browser`, `audio`, and `restart`
outputs under a unique ignored `acceptance/artifacts/web-gate/<run-id>/`.
Each stage has stdout/stderr logs; Compose journals and container state are
collected before cleanup. `result.json` retains the primary failure separately
from collection and cleanup errors. An occupied test project is rejected before
building. `-KeepRunning` retains only the stack started by that invocation.
Collection and cleanup commands have 45-second process budgets; browser stages
have a 900-second outer budget without changing individual test or app timeouts.
Run `pwsh -NoProfile -File scripts/test-web-gate.ps1` for isolated launcher
regressions with fake Docker/npm commands; no live containers are used.
Historical repeats enable tracing explicitly with `--trace-browser` to reproduce
the older observation conditions. For a direct Playwright diagnostic run, set
`ZENPTT_WEB_PROXY_TRACE=1`. Compare that effect separately from ScriptProcessor
PCM observation.

Per-page journals preserve request/burst IDs, transport and playback
generations, worklet commands/replies, render clocks, queue depths, context
and UI changes. Ten-second checkpoints, failure screenshots, streaming process
logs, and unique Playwright artifact directories retain partial evidence.
A forced process termination may lose events since its last checkpoint.
The proxy runner atomically saves `result.json` before container cleanup with
`cleanup_status: pending`, then updates it to `complete` or `failed` and records
`cleanup_errors`. All owned cleanup operations are attempted independently.
Pending/failed cleanup makes a new attempt unsuccessful; older evidence without
these additive fields retains its original interpretation. Experimental
`web/dist` switching validates candidate paths before replacement and retries
only confirmed Windows ReadOnly deletion failures. Restoration verifies the
complete file-hash set and writes a separate `*-restoration.json` record,
including both the original and restoration errors when applicable.
Worklet metadata is local to the browser and does not change the wire protocol.
`--web-manifest` verifies experimental static files separately from the server
ZIP and checks the assets actually fetched by Chrome. The diagnostic loader
uses exact fetched worklet bytes: it hashes the module,
loads those bytes through a temporary Blob URL, and revokes the URL afterward.
Analyze a saved attempt
with `python scripts/poor-link/analyze_browser_buffer.py <attempt-directory>`.
The analyzer preserves planned/created/delivered counts, padding, excess,
terminal evidence and playback generations. Render-clock gaps between frames
of one burst measure internal queue stalls. Cross-context delay estimates use
same-host performance origins and record clock-offset variation; these are not
mouth-to-ear or acoustic measurements.
`summarize_browser_buffer.py <study-directory>` produces the complete case list,
separate observer groups and matched differences. `analyze_browser_pcm.py
<attempt-directory>` performs supplementary sparse spectral confirmation;
it does not infer frame loss from PCM duration. See the
[October browser-buffer investigation](research/browser-buffer-2026-10-09.md)
for the completed comparison and its limitations.

The browser proxy experiment runs the real AudioWorklet and Opus path in two
Chromium clients. Each client connects through its own temporary Linux Caddy
container. Traffic control is confined to a proxy container; this changes the
TCP path, so its measurements are separate from the direct bot/server matrix.
The test host keeps its installed package. Run a baseline first, using the
SHA-256 of the package currently installed on the host:

```powershell
.venv\Scripts\python.exe scripts/poor-link/client_proxy.py `
  --expected-bundle <installed-zip-sha256> `
  --package-zip <saved-zenptt-server.zip> --profile baseline
```

Select `egress-32`, `egress-48`, `egress-64`, `ingress-police-48`, or `unstable`
with `--profile`; `--impaired A` or `--impaired B` chooses the constrained proxy.
The default addresses come from the ignored `private/poor-link-ssh.json`.
With `--package-zip`, the runner verifies that the local `web/dist` bytes match
the selected installed package before opening the browser.
The runner checks host health and the installed ZIP, captures both browser
directions, `tc` counters, proxy logs, and server logs, then removes its own
containers. Each invocation writes an ignored timestamped result directory
under `acceptance/artifacts/poor-link/`. A failed browser run or missing
measurement evidence returns a nonzero status while preserving the artifacts.
The proxy profiles are manual and never run in the ordinary CI gate.

For browser playback-stall diagnosis, use the same package with
`--target-frames 400 --diagnose-audio --seed 1009` (then seeds `2017` and
`3037`). Repeat `baseline` and `unstable` with `--prebuffer-ms 0`, `50`, and
`100`, always using a distinct ignored `--output` directory. This extra
prebuffer is a test-only delay before the first frame commands reach the
unchanged AudioWorklet; it is not a server or client setting. Each result saves
frame timing, queue-block events, raw float32 PCM, and observer callback gaps.
The 7 October study used a 100 ms worklet delay; current builds use 150 ms.
Keep the actual worklet build identical when comparing extra prebuffer values.
Use `--lightweight-observer` with `--prebuffer-ms 0` to repeat baseline and
unstable without the ScriptProcessorNode PCM tap. This mode retains worklet
queue events but does not save PCM. Analyze the complete matrix with:

```powershell
.venv\Scripts\python.exe scripts/poor-link/analyze_browser_stall.py `
  --runs <ignored-matrix-dir> --lightweight-runs <ignored-lightweight-dir> `
  --expected-bundle <installed-zip-sha256>
```

The analyzer requires all 18 full-observer cases and six lightweight cases,
checks individual frame identities, and lists incomplete attempts separately.
Browser PCM duration errors alone are not evidence of network frame loss.

## Full local gate

Run the normal bounded gate from the repository root:

```console
.\scripts\test-full.ps1
```

The default full gate runs host headless tests. Linux Opus/watchdog checks and
headless container fault scenarios require the separate commands in the
Headless checks section below; `-WithLiveStack` does not run those gates.

The full gate also verifies that identical versioned APK publication is reused
and conflicting bytes with the same `versionCode` are rejected. The application
and instrumentation APKs must each declare a distinct `versionCode`; every new
set of APK bytes receives a previously unused code before it is built.

It runs:

- server pytest suite with at least 90% line coverage;
- Ruff over server application and tests;
- headless client and bot tests plus Ruff;
- static Lucide/Yin-Yang icon asset checks;
- pinned Linux web build, lint, TypeScript, unit tests and native/WASM Opus interoperability;
- production Caddy/Chrome browser scenarios and a separate real AudioWorklet/WASM signal suite;
- Android JVM unit tests;
- Android lint;
- debug application APK assembly;
- instrumentation APK assembly.

Optional connected instrumentation:

```console
.\scripts\test-full.ps1 -WithInstrumentation
```

Optional live-stack checks after an APK is published and Compose is running:

```console
.\scripts\test-full.ps1 -WithLiveStack
```

Both switches may be combined. The script terminates and returns non-zero on
the first failed gate.

## Refactoring acceptance and CI

Every push and pull request runs server tests, the production web gate (including
restart recovery), app and headset JVM/lint checks, emulator instrumentation, and
a headless job with host tests, metadata validation, Linux Opus/watchdog checks,
and every live fault scenario. Failures are not optional. Repository administrators
must select these jobs as required checks in GitHub branch protection; workflow
configuration alone does not prevent merging.

Before a broad refactor, run these existing commands from the repository root:

```console
.\scripts\test-full.ps1 -WithInstrumentation
.\scripts\test-headless.ps1 -WithDocker
.\scripts\test-headless-live.ps1
```

The first command needs the documented local server and an API 31+ device/emulator.
The headless scripts also run in PowerShell on Linux using `.venv/bin/python`;
Windows uses `.venv/Scripts/python.exe`. Generated reports and PCM remain untracked.
The live headless gate uses the shared `run-bounded.py` process timeout helper;
Docker calls retain the scenario deadline without creating a PowerShell job per poll.
Run the heavy gates sequentially on memory-constrained workstations; audio timing
checks require an emulator that is not competing with a browser build or test run.
On Windows build-directory locking, stop Gradle and clean only the affected
generated directory. Repeated locking requires temporary build/cache paths under
`C:\tmp`, supplied by a local Gradle init script, never committed SDK paths.
The full gate can create that init script and use the matching APK/report paths:

```console
.\scripts\test-full.ps1 -WithInstrumentation -AndroidWorkDirectory C:\tmp\zenptt-checks
```

Use a directory dedicated to these generated checks. Omitting the option keeps the
normal Gradle build paths. Both JVM/lint/assembly and connected tests use the same
temporary build and project cache directories. The immutable publisher test reads
that build's APK and metadata using `-ApkDirectory` and writes only to its temporary
test release directory; it never publishes to the hosting directory.

The server coverage floor remains 90%. Behavioral evidence, not a higher percentage,
is the refactoring criterion. Independent literal binary vectors protect server and
Android framing from matching encoder/decoder mistakes. Admission tests synchronize
contenders for the last global/per-client slot and verify reuse after disconnect
or accept failure. ECHO ticket expiry uses the real cleanup loop and an injected
clock, checks rejection of the old ticket, and checks log redaction separately
from assignment failure.

`test_echo.py` also pauses bot admission while replacing the supervisor and checks
that the revoked ticket cannot attach a bot or publish a snapshot. Client departure
must revoke the assignment and close the bot. Capacity, ticket reuse, bot loss,
and per-user isolation remain covered through the normal ECHO paths.

Headless tests attach transport contexts through `_attach_transport`, including
replacement during replay. Stale writes, cancellation, `burst_end` ordering, and
the shared `PcmBudget` remain behavioral checks. The Linux gate additionally checks
real Opus, PCM memory limits, watchdog termination, and signal-driven shutdown.

`PttSessionTest` covers releasing state and stale terminal events. Icon checks
validate resources and the notification through instrumentation.
`ConnectionScreenTest` checks server-supplied ECHO counts, headset controls,
and snackbar expiry using the Compose clock. Diagnostic reporting
shares the PTT state lock so reading a report cannot race an uplink ACK; this is
covered by a synchronized JVM regression and foreground-service instrumentation.
The foreground-service test dispatches PTT on the main thread, like the UI, so
service callbacks cannot race the start from an instrumentation worker thread.
It waits for a grant before releasing its baseline PTT press, then for the actual
notification update after the session becomes ready.
The accepted press must retain its wake lock across settings persistence and
session startup; publishing the initial disconnected settings must not release it.
An early release legitimately cancels a queued press and shows a rejection notice;
Android also publishes notification updates asynchronously.

Android settings regressions exercise the public `ChannelViewModel` boundary after
delegation to `ChannelSettingsController`: invalid settings do not persist, only
server/frequency changes reconnect, and stale ping/upload results cannot replace
the latest UI state. Headset save failure and setup cancellation retain the accepted
settings. `PttButtonSetupUiTest` checks that Save alone shows no success notice and
the asynchronous completion event shows it once; `HeadsetAudioPolicyTest` covers
the phrase boundary. Audio metrics tests use `audioFrame`/`audioLoss` and verify
cumulative loss, burst changes, reconnect resets, quality thresholds, and strings.

If no device or emulator is available, JVM/lint and both APK assemblies still run;
the `instrumentation` CI job must pass before accepting the change. A skipped
connected or live-stack check is not a passing result. Bluetooth/BM008/SCO physical
checks remain separate from emulator evidence.

## Browser client checks

The browser client has a pinned Linux build and test gate,
native/WASM Opus cross-decoding, real-server connection tests, and installed-Chrome
tests using real AudioWorklet/WASM with a synthetic microphone and output analysis. See
[`WEB_CLIENT.md`](WEB_CLIENT.md#verification) for commands and local prerequisites.
`test-full.ps1` runs `test-web.ps1`: an isolated `zenptt-web-gate` Compose stack
serves the production build at loopback port 18081, then terminates. `-KeepRunning`
on `test-web.ps1` retains that stack for local acceptance. `build-web.ps1` exports
the checked Linux assets to `web/dist`; no generated assets are committed.
CI runs the same web gate in PowerShell on Ubuntu with installed Chrome.
Recovery cases cut real server connections before grants, during audio, and at
completion, check original packet replay and playback deduplication, and exercise
short/long outages and input cancellation. The listener regression compares the
complete rendered sequence through the final acknowledged watermark, including
the drained tail. `npm run test:restart` runs last in `test-web.ps1` and interrupts
only `zenptt-web-gate`. It requires `ZENPTT_WEB_TEST_PROJECT=zenptt-web-gate` and
`ZENPTT_WEB_URL=http://127.0.0.1:18081`; missing or different values fail closed.
Unit/worklet tests
also cover slow consumers, overflow, server gaps, and capture deadlines.
These tests do not prove physical microphone/speaker audibility.
The signal suite observes microphone input and actual worklet output using a
test-only PCM observer. It checks bounded duration error, envelope correlation
and spectral similarity above 0.9, with bounded codec delay. Real Opus negative
controls reject silence, noise, a wrong tone, reordered sections, and truncation.
BrowserAudio integration tests use the real adapter/client with browser API doubles
to verify resource cleanup, stopped transmission, and reconnect after worklet,
microphone, or AudioContext failure and late microphone selection.
Connect prepares audio before the handshake finishes; tests cover early permission
failure surviving admission, retry without a second join, pending preparation at
disconnect, and no queued transmission while permission is pending. Chrome checks
also verify listening without an initial PTT press and recovery from permission denial.
Halo contract tests cover state precedence, sixteen sectors, and audio quality
thresholds. Settings browser tests cover incoming playback across navigation,
microphone switching, persisted history, local diagnostic redaction, keyboard
activation, and reflow under increased page scale. Reports/screenshots from tests
remain ignored artifacts under `.cache` or `web/test-results`.
Hosting checks cover the root client, versioned Android footer download, redirect, CSP, MIME types, cache
revalidation, missing assets and local diagnostic download. `npm run test:browser`
and `npm run test:audio` are separate commands; the second runs the actual browser
audio path rather than substituting browser audio APIs with mocks.

## Server checks

Run independently:

```console
.venv\Scripts\python.exe -m pytest server\tests -q --cov=server\app --cov-report=term-missing --cov-fail-under=90
.venv\Scripts\python.exe -m ruff check server\app server\tests
```

On Linux or macOS use `.venv/bin/python` and `/` separators.

The suite covers:

- channel normalization, logical resume/generation fencing, capacity,
  reservations, atomic floor ownership, expiry, and race cleanup;
- strict JSON and binary protocol behavior;
- cumulative uplink ACK, out-of-order and duplicate media, authoritative gaps,
  mixed expired/recoverable envelopes, draining/sealed watermarks, exact-size
  media batching, ordered listen, and floor-lease behavior;
- Echo assignment, one-time tickets, isolation, capacity, bot replacement, resume, and cleanup;
- per-session outbound ordering, the fixed send watchdog, and slow-listener handling;
- global/per-client admission and diagnostic rate limits;
- settings ceilings and cross-setting invariants;
- safe HTTP errors, diagnostic persistence, and release endpoints;
- Uvicorn launcher arguments and transport limits.

The server supports Python 3.10+ and the container uses the pinned Python image
from `server/Dockerfile`.

After changing `server/pyproject.toml`, regenerate the hashed Linux production
lock with:

```console
.\scripts\update-server-lock.ps1
```

Review the resulting `server/requirements.lock`; do not hand-edit hashes.

## Headless checks

Install FFmpeg separately and ensure `ffmpeg -version` succeeds in the same shell.
Python development dependencies do not provide this executable. The headless gate
reports a missing FFmpeg dependency before starting tests; it does not skip those tests.
The QRZ developer example uses prepared PCM. The production headless image contains
neither QRZ assets nor FFmpeg.

Run the client and bot gate without Android:

```console
.\scripts\test-headless.ps1
```

It covers independent binary vectors, every server control shape, strict JSON values and
limits, malformed framing, burst assembly, explicit gaps,
decode failures, monotonic completion deduplication, partial PCM, MP3 conversion, handler
replacement, queue ownership, session fencing, bounded writes, fixed finalization deadlines,
transport-local write cancellation, connection-stage shutdown, grant resume,
ACK-independent pacing, immutable terminal confirmation, and strict terminal watermark
validation. Session-loss regressions require a canceled handler to finish within 0.5
seconds and verify that a possible transmitted PTT request fences the logical session.
PCM-budget regressions insert old lifecycle messages and media before and after
`listen_reset`; neither an empty nor nonempty rejected tail may reach a handler,
and the next burst or new session must still work. A virtual-clock sender test
records every encoder call across repeated failed resumes and requires production
to stop at `H`, with an immutable final watermark and finalization deadline.
It also checks a successful short resume, prefix sealing, and timeout. Disabling
the horizon or resetting its origin on a failed transport must break the test.
Canceled granted sends retain serialization through `draining` until `sealed`. The
shutdown regressions use the real client write path and verify
completed `burst_end`, entry into `HeadlessClient.stop()`, `disconnect`, and
socket-close ordering, including the race where the sender worker has already
cleared its current-task pointer. The portable watchdog test uses a controlled timer
without real delays or process termination. Each local
test or lint invocation has a 120-second process deadline.
Public `run_bot_session()` regressions cover configuration mismatch before
connection, equal-but-distinct configurations, completed-burst delivery, no
response, startup failure, cancellation during connection or handler execution,
and cleanup timeout without process exit. Concurrent sessions must keep host
signal handlers untouched and continue after a sibling fails or is canceled.
Public watchdog tests cover bounded timeout validation, signal reentry, and
permanent disarming before or after its first start.
Use `-WithDocker` to build the Linux image and verify real libopus encoding and decoding:

```console
.\scripts\test-headless.ps1 -WithDocker
```

The Linux check sends external SIGTERM through the production shared standalone lifecycle and public
`run_bot()` entry points. It verifies the five-second standalone watchdog with
cancellation-suppressing user code, observes the same process for the full interval with
only the watchdog disabled, checks a clean standalone exit, and checks a library
`TimeoutError` that does not terminate its host process. A separate no-signal scenario
verifies that an internal `handler_cancel_timeout` arms the same production watchdog.
Additional children block the event loop in user code and receive either one signal
or SIGTERM followed by SIGINT; both must exit against the first watchdog deadline.
Echo-specific children exercise the real supervisor entry point, assignment
handling, and public session runner with controlled control/media transports.
Two sessions must stop cleanly on SIGTERM. A blocked handler must trigger the
same five-second watchdog even after a second signal, and a handler suppressing
cancellation must not outlive that deadline during `asyncio.run()` cleanup.
The live Echo gate below supplies the separate real-network verification.
Library signal regressions run normal, startup-error, cancellation, and cleanup-timeout
paths repeatedly in one loop. They require restoration of custom Python handlers,
default/ignored dispositions, `asyncio.run()`'s SIGINT handler, and existing loop
callbacks, including actual signal delivery after the library returns.
Nonblocking binary reads keep
marker and cleanup deadlines effective even for partial lines. A startup failure routed
through the real standalone wrapper and additional child failures verify that the gate
reports the traceback, scenario, expected result, exit status, elapsed time,
stdout, stderr, and bounded cleanup outcome.
Each child first publishes readiness under a separate five-second startup budget;
delayed-start regressions ensure cold Python startup cannot consume shutdown deadlines.

The Docker gate also asserts that the production headless image has neither the
`zenptt_headless.qrz_bot` module nor `/audio/QRZ.pcm`.

The repository keeps the generated reference PCM under
`examples/qrz_bot`. Run the bounded server, Caddy, example bot, and
independent-client round trip without downloading assets:

```console
.\scripts\test-headless-live.ps1
```

The minimal headless runtime is included inside `zenptt-server.zip` for the required
Echo supervisor; QRZ code and assets are excluded, and the hosting directory still
contains exactly four files.

The independent checker compares QRZ against the prepared PCM; the developer example and metadata
handler read prepared PCM. Tests pin both asset hashes and reject
a subprocess invocation during the QRZ entry point's audio loading.
Real Opus checks compare a client resume after ten received frames with continuous
decoding, exclude the replayed duplicate, and verify decoder reset between independent
bursts. A deliberate mid-burst decoder reset must break the PCM comparison.
The QRZ criterion combines energy-envelope correlation and power-spectrum similarity,
both above 0.9. Wrong-tone, noise, reordered, silent, and truncated negative controls
are encoded and decoded with real Opus and must be rejected. Positive controls cover
normal QRZ and a gain/delay variation.
The live client contains its own framing and codec calls and does not import the server or
headless packages. Every fault scenario completes two source/QRZ exchanges with the same bot,
maps their source burst IDs to exactly two `sent/complete` runner results, validates frame
count and audio correlation, and rejects another response during a three-second
quiet window. A separate no-fault Echo scenario sends 700 Hz and 1100 Hz sources,
requires response frame counts to match their respective inputs, applies the same
correlation and spectral threshold after the second Opus encode, and rejects an extra
response. The history-loss scenario also checks the public handler's complete tail metadata.
The Echo scenario starts three clients concurrently on distinct hidden instances,
checks six correlated responses with no extra bursts, and includes one 55-second
source. Every snapshot must report two participants and every channel incarnation
must be distinct. It then routes bot sessions through the fault proxy, drops only one
ticket-authenticated bot, verifies replacement, restarts `echo-supervisor`, and checks
all three existing sessions recover. A server restart must produce three new RAM-only
incarnations that each complete another correlated response.
Both IDs and indices must equal the independent sender's grant/seal observations;
both PCM intervals must end at the independently confirmed watermark. Seal reasons,
empty loss/decode-error arrays, and the expected epoch relationship are checked for
both exchanges. The same PowerShell validator runs in the host gate against valid examples and deliberately corrupted, missing, reordered, or duplicate
observations. It can also run alone with
`scripts/test-headless-live.ps1 -CheckMetadataOnly` without Docker.
Decoded PCM must retain the
known 700 Hz source tone. An additional `contract` scenario tests competing senders,
the bot's participant count, late-join exclusion, and a real server restart. The same
bot must answer again with a newer epoch. Queue and PCM regressions exercise the shared
64 MiB budget, shared-buffer deduplication, active sends, assembly copies, and recovery
after rejected work. Grant tests cover server errors, missing grants, late grants,
fixed deadlines across transports, and terminal payload conflicts.
The proxy selects faults from decoded protocol events, including a server-accepted `burst_end`
whose terminal confirmation is dropped, and emits scenario markers required by the gate. Image
building has a separate 600-second deadline. The completed scenario run has 600 seconds; the
Echo scenario has 300 seconds and ordinary scenarios have 60 seconds each. Each ordinary
scenario shares one 60-second deadline across all of its commands, and cleanup has 60 seconds.

## Android JVM, lint, and assembly

From `android/`:

```console
.\gradlew.bat :headset:testDebugUnitTest :headset:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleAndroidTest
```

Use `./gradlew` on Linux or macOS.

JVM tests cover protocol parsing, cumulative sender ACK and the derived watchdog
boundary, bounded receive FIFO, bounded source/server/receive timing diagnostics,
fixed 100 ms playout and propagation of burst identity to frame and loss playback,
PTT transitions across the absolute deferred-request, offline recovery, and burst deadlines,
terminal payload-conflict handling, reconnect policy,
early WebSocket callback races, the ten-second application-pong watchdog,
queue and drain state, headset rule validation, framing, and held-input safety,
speech playback start-threshold and write-ahead calculations, queued-cue
boundaries and skipping, service-indicator PCM duration/frequency/amplitude/envelopes, indicator ordering
and suppression, settings persistence, input validation, update validation,
diagnostics, and view-model orchestration. Android lint and both APK assemblies
catch manifest, resource, SDK, and packaging regressions that JVM tests do not.

Detailed requirement-to-test traceability is maintained separately in
[`RELIABILITY_MATRIX.md`](RELIABILITY_MATRIX.md).

Headset coverage includes module `ButtonLearningTest`, `ButtonInputTest`, and
`HeadsetSettingsTest` for rule inference, independent validation, background
telemetry rejection, framing, limits, edge deduplication, terminal reset, and
configuration validation. App coverage includes persistence and failed-save
cases in `ChannelViewModelTest` and connected `HeadsetControllerTest`,
`PttButtonSetupUiTest`, and `SharedPreferencesConnectionPreferencesTest`.
These tests do not establish physical or locked-screen headset compatibility.

## Connected Android instrumentation

The emulator runs on the host, never inside Docker. Start local Compose and use
an API 31+ AVD. The emulator reaches Caddy at `ws://10.0.2.2:8080`.

```console
cd android
.\gradlew.bat :app:connectedDebugAndroidTest
```

Override the server address when required:

```console
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.serverAddress=ws://<host>:<port>
```

Instrumentation covers Compose behavior, pinned libopus JNI encode/decode,
PCM observed in the production playback queue, deterministic pre-consumption
queue barriers, fresh decoder state at consumed Opus burst boundaries including
leading loss and long gaps, retained PCM across reconnect,
deterministic ECHO and normal-channel round trips, foreground-service and
notification lifecycle, manifest requirements, SharedPreferences persistence,
foreground-service headset activation cleanup, absence of a service-sound setting, audio
pipeline cleanup and AudioRecord/AudioTrack release ordering, route behavior,
playback draining, explicit speech start
threshold, the service-indicator barrier before granted capture, and real `AudioTrack`
initialization, cancellation cleanup, and reuse for every service indicator.

The deterministic audio source exists only in `androidTest`. It is not exposed
by the production or debug UI. Instrumentation does not prove physical BM008
SPP behavior, SCO timing, audible tones, real microphone quality, or
manufacturer-specific background behavior.

`EchoEndToEndTest` checks each of two distinct source/response bursts separately,
including grant/seal identity, the final frame count, fresh Opus decoder state,
and PCM envelope/spectrum. `AudioSignalOracleTest` rejects silence, noise, a wrong
carrier, reordered sections and truncation after real Opus encoding/decoding.
`AudioRecoveryEndToEndTest` sends synthetic PCM through real Opus and WebSockets
into the production AudioPipeline/AudioTrack. It cancels the receiver socket
after playback starts, verifies same-session resume and exact PCM against continuous
decoding, then completes a second burst and checks drain/resource cleanup.
Microphone capture is synthetic in that scenario; capture lifecycle and physical
audio-route acceptance remain separate checks.

The playback and lifecycle observers and playback-start barrier are internal,
unset by default, and exercise the same decoder, queue, AudioTrack, AudioRecord,
and coroutine jobs used by the application. They do not replace physical audible
quality or route testing.

## Compose configuration check

Start with a static configuration validation:

```console
docker compose --env-file server.local.env config --quiet
```

Then start the bounded local stack and probe through Caddy:

```console
docker compose --env-file server.local.env up -d --build
curl http://127.0.0.1:8080/health
```

The expected result is `{"status":"ok"}`. `zenptt-server` must have no host
port mapping and its process must be `python -m app.run` with one worker.

## Local Caddy and live-stack gate

Publish an APK if needed, then run:

```console
.\scripts\test-caddy-stack.ps1
```

The gate validates:

- Compose service ports, resource ceilings, read-only roots, capabilities, and volumes;
- Caddy security headers, JSON access logging, and hidden OpenAPI endpoints;
- HTTP 206 partial APK behavior when a Range header is supplied;
- health and idle WebSocket behavior;
- oversized text/binary WebSocket close code 1009 and recovery;
- diagnostic upload, storage, body limits, and redaction;
- APK metadata, size, SHA-256, and Android signature;
- replacement of spoofed `X-Forwarded-For` before per-client limiting;
- Linux support-bundle contents and secret exclusions.

The script stops Compose unless `-KeepRunning` is supplied. `-IdleSeconds`
changes only the bounded idle WebSocket verification interval.

Run only the already-started live stack with:

```console
.\scripts\test-live-stack.ps1
```

`ANDROID_HOME` is required because the gate verifies the APK signature with
Android build tools.

## Installer and shell gates

Run the Ubuntu installer lifecycle independently:

```console
.\scripts\test-linux-installer.ps1
```

It uses isolated containers to verify clean Ubuntu 24.04 bootstrap, Docker
installation, initial activation, repeated update, APK-only update, rollback,
volume persistence, restart, rejection of QRZ-contaminated archives, and other
invalid delivery packages. Containers cannot
prove real `systemd`, public DNS, certificate issuance, or host reboot.

Run Bash validation and ShellCheck with:

```console
.\scripts\test-shell-scripts.ps1
```

Run the support collector check independently with:

```console
.\scripts\test-support-bundle.ps1
```

## Hosting package gate

Create the ignored `android/signing.properties` with the operator's key before
running the full gate or package builder. Android CI can compile debug APKs
without this file, but publication requires an explicit signing identity.

The production package builder runs the full gate, APK publication, Caddy gate,
installer lifecycle, and ShellCheck before writing delivery files:

```console
.\scripts\build-hosting-package.ps1
```

Use `-SkipChecks` only for a deliberately partial local packaging iteration,
never as release evidence. Successful output contains exactly four files under
`dist/hosting`:

- `install-update.sh`;
- `zenptt.apk`;
- `server.env`;
- `zenptt-server.zip`.

The package gate requires the Echo runtime and rejects QRZ example code, audio,
and Compose files in the ZIP.

## What automation cannot establish

Automated success does not establish:

- BM008 command behavior on physical firmware;
- actual SCO/BLE microphone and speaker routing;
- grant/service-tone audibility, timing, or subjective unobtrusiveness;
- confirmation that queued or server-state tones never enter captured speech;
- phone-speaker fallback after a physical Bluetooth loss;
- screen-off behavior under a device vendor's battery manager;
- subjective speech quality or mouth-to-ear latency;
- public DNS, firewall, certificate issuance, and reboot recovery.

Use [`MANUAL_TESTING.md`](MANUAL_TESTING.md) for those checks.

Headset-switch validation includes `HeadsetAudioPolicyTest` (explicit phone route,
queued-grant completion during a pending change, cancellation without persistence),
`AudioPipelineLifecycleTest` (capture/playback/indicator barriers), and
`PttButtonSetupUiTest` (short commands, Cancel/Back, empty connection list, pending
switch, and Save gating). `SetupCommandTest` checks Hold/Toggle command transitions.
`SetupSearchTest` checks SPP/BLE/Media priority, unavailable methods, HID binding,
failure fallback, and stopping at the first verified Hold or Toggle rule.
`HeadsetControllerTest` checks that delayed ACL/profile disconnect broadcasts do
not abort SPP/BLE setup, while adapter shutdown and media disconnect still reset
input. UI coverage includes the current connection name while probing and switching.
Physical connected-profile filtering and Bluetooth routing require the acceptance
steps in `MANUAL_TESTING.md`; emulator success cannot establish those properties.
