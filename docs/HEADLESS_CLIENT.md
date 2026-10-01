# ZenPTT headless client and bot handlers

Status: current

Purpose: document the reusable Python v4 client and production Echo runtime.

Audience: bot developers, operators, and testers.

Authority: this document owns the public headless API, handler lifecycle, PCM ownership,
recovery, bounded shutdown, and Echo operation.

Code anchors: `headless/src/zenptt_headless`, `headless/Dockerfile`, and
`compose.yaml`.

Update when: public types, client/runner lifecycle, queue or PCM ownership, shutdown,
bot configuration, container behavior, or Echo startup changes.

## Public API

The `zenptt_headless` package is independent of the server implementation. Its public API is:

- `PcmAudio`: signed 16-bit little-endian mono PCM at 16 kHz;
- `ReceivedBurst`: a completed burst with PCM, its `first_sequence`, authoritative loss ranges, decode errors, the server seal reason, and its local `session_epoch`;
- `ReceiveInterrupted`: an incomplete receive operation that must not be treated as a sealed burst;
- `SendResult`: local delivery outcome; it is not proof that another participant heard the audio;
- `HeadlessClient`: connection, receive, resume, and serialized send ownership. Its read-only `session_epoch` fences work belonging to a lost logical session;
- `load_pcm(path)`: bounded loading and validation of prepared PCM audio;
- `run_bot(BotConfig, handler)`: common queues, sequential handler invocation, retries, and shutdown; owns SIGINT/SIGTERM for one runner;
- `run_standalone(BotConfig, handler)`: synchronous process wrapper with signal handling and a five-second watchdog;
- `run_bot_session(BotConfig, handler, client)`: the same runner with a caller-provided client, without taking ownership of process signals;
- `ShutdownWatchdog(seconds=5.0)`: an explicit, one-shot process shutdown deadline;
- `MAX_MESSAGE_BYTES`: the shared protocol message limit, currently 4096 bytes.

Import supported interfaces from the `zenptt_headless` package root.

### Application-managed sessions

Use `run_bot_session(config, on_burst, client)` when an application owns signals
or runs multiple bots concurrently. Supply a fresh `HeadlessClient` that has not
been started and is not shared with another task. Its `config` must equal
`config.client`; a mismatch raises `ValueError` before starting or stopping the
client. The function owns client startup and cleanup for the duration of the
session. Do not independently receive, send, start, or stop that client while
the session is running, and create a new client for a later session.

Cancel the session's asyncio task and await it to stop the bot. Successful
cleanup propagates `asyncio.CancelledError`; exceeding
`BotConfig.shutdown_seconds` raises `TimeoutError` instead. Startup and worker
failures also stop the client before propagating. Queues, PCM budgets, handler
isolation, session fencing, and transmission finalization use the same runner
as `run_bot()`. The session never installs signal handlers, arms a process
watchdog, or exits the host process. Its failure does not cancel sibling
sessions; the application chooses how to observe and recover failed tasks.

`ShutdownWatchdog` is a separate process-level tool for such applications.
Its timeout must be a finite positive number no greater than five seconds.
Call `start()` synchronously when process shutdown begins, before scheduling
async cleanup. It starts a daemon timer that calls `os._exit(1)` at the deadline,
including when the event loop is blocked. Repeated calls and signal reentry
do not extend the first deadline. `started` reports whether it was armed.
`cancel()` permanently disarms this one-shot object, even before its first
start; it cannot be restarted. Cancel it only after process-level cleanup,
including `asyncio.run()` cleanup, has finished. Never arm a shared process
watchdog merely because one independent bot is being revoked.

`run_bot()` and `run_standalone()` retain their existing signal behavior;
use `run_bot_session()` for multiple runners in one process.

The client follows [`protocol.md`](protocol.md). It does not import `server/app`, and its framing tests use literal independent vectors.
Server controls are validated completely before they can mutate client state,
including exact fields, JSON types and limits, nested structures, enumerated
reasons, duplicate keys, and non-standard numeric literals.

Pass `expected_session_epoch=burst.session_epoch` to `send_audio()` when a
response is derived from a received burst. The client rejects the call if that
logical session has already been replaced. The argument is optional for callers
that intentionally send work created in the current session.

For local development, install `headless[dev]` into the project virtual environment.
Runtime transmission requires a discoverable libopus shared library. Local tests
also require the FFmpeg executable on PATH for offline MP3 preparation checks;
the test gate fails before pytest if it is missing. FFmpeg is not installed in
the runtime image, and installing `headless[dev]` does not install FFmpeg.

```console
.venv\Scripts\python.exe -m pip install -e ".\headless[dev]"
```

## Lifecycle and ownership

`HeadlessClient.start()` joins or resumes one logical session and registers the receive cursor. A transport replacement retains the current receive decoder and response when the server accepts resume. A rejected resume or changed server incarnation increments `session_epoch`, emits `ReceiveInterrupted`, drops old logical-session work, and joins afresh.

The receive path accepts the server's strict ordered stream. Each authoritative gap contributes the matching duration of silence and remains listed in `ReceivedBurst.losses`. A decode failure also contributes one silent frame and its sequence appears in `decode_errors`. Only `burst_sealed` creates a `ReceivedBurst`. When a `listen_reset` discards the start of a burst, `first_sequence` identifies the first frame represented by PCM; the unavailable prefix is neither synthesized as silence nor reported as an authoritative loss.

The send path creates a fresh Opus encoder per response, pads the final PCM frame with silence, requests PTT, and produces frames on a monotonic 20 ms timeline independent of cumulative ACK latency or a blocked network write. It retains the original unacknowledged Opus packets for the server recovery horizon and replays those exact bytes after a resumed transport. A released floor stops new audio capture but permits the retained tail and immutable final watermark to drain before the next response starts. The client alone retries `channel_busy`, using the same request ID and `BotConfig.busy_retry_seconds` (default one second, minimum one second). Other failures complete that response explicitly.

Each grant attempt has a fixed five-second deadline including readiness and writes;
transport replacement does not extend it. A busy denial permits a new attempt after
the retry interval. Missing grants return `interrupted/grant_timeout`. Generic server
errors return `interrupted/server_error_<code>`. Both invalidate the session because
the protocol's generic error has no request ID and an unknown floor cannot be retained.

`payload_mismatch` is terminal: retained packets and old work are discarded, automatic
reconnect stops, and subsequent sends return `rejected/payload_mismatch`. The runner
remains idle and logs `manual_reconnect_required`; SIGINT/SIGTERM still performs normal
bounded shutdown. Keeping the standalone process alive prevents `restart: unless-stopped`
from silently reconnecting. An operator must restart the bot container explicitly, or
a library caller must stop the old client and create a new one.

The encoder is created before requesting PTT. Failure to create it returns
`rejected/encoding_error` without occupying the channel. A failure while encoding
an active response freezes and drains the available prefix, then returns
`interrupted/encoding_error` even if the server seals that shorter prefix as complete.

Canceling `send_audio()` after a grant freezes and drains that response through
the normal recovery path. The audio lock remains held until the server seals the
burst or the logical session is invalidated, so `draining` never permits a second
PTT request. The canceled call ultimately raises `CancelledError`. If cancellation
occurs while a PTT request may already have reached the socket but before its grant
is known, the client invalidates the session rather than risk an untracked floor.

Every write, including waiting for its transport's write lock, is limited to one
second and is also constrained by the response finalization deadline. Audio is
rechecked after taking that lock, so an acknowledged or retention-expired packet
is skipped without being written. Canceling a write that has entered the socket
aborts that transport; its lock and unfinished operation cannot block the next
transport. Once production
stops, the final sequence and its `H + 5 seconds` deadline are fixed; reconnects
and repeated `burst_end` messages do not extend that deadline. Expiry invalidates
the logical session and returns `interrupted/recovery_timeout`.

After transport loss during audio production, encoding stops at `H` from that
loss. Failed resume attempts do not restart the outage clock. A successful short
resume can continue the response; waiting for the initial grant does not consume
the audio recovery horizon. Reaching the outage limit freezes the available
prefix and drains it, but even a complete server seal of that prefix returns
`interrupted/recovery_timeout`. The separate finalization deadline bounds cleanup.

`SendResult(status="sent", reason="complete")` requires a matching `ptt_ended`
with `state="sealed"`, `reason="complete"`, and the same final sequence as both
the immutable local watermark and the complete requested PCM. A conflicting
server watermark returns `interrupted/final_sequence_mismatch`. An earlier local
stop reason remains authoritative even if the shorter server burst seals as
`complete`. The first matching `sealed` message and its arrival time are final;
late duplicates or `draining` messages cannot replace a timely confirmation.
As with every `SendResult`, this confirms server acceptance rather
than listener playback.

The runner invokes one handler at a time. Its handler queue holds four completed bursts.
Its response queue holds at most 32 entries. Both queue sizes must be positive integers;
configuration may lower these limits but cannot exceed them. `BotConfig.send_queue_bytes` bounds the
combined admitted PCM across assembly, event and handler queues, responses, and active
operations (default and maximum 64 MiB). Assembly reserves space before growing PCM and
before its immutable copy. An overflow interrupts that receive with `pcm_budget_exceeded`
and discards the remaining burst through its seal, including across resume and
`listen_reset` of the same burst. That reset cannot turn rejected work into an
empty or partial completed event. Replayed lifecycle events and frames from an
older completed burst do not replace that rejection, either before or after the
reset. A new burst or logical session can be admitted normally.
Queue overflow rejects new work with a diagnostic. Handler exceptions affect one burst.
Session loss cancels active processing and the old response, clears queued work, and
rejects a late handler result even when that handler suppresses cancellation. The sender
worker remains available for a new session after recoverable failures. An unexpected
exit of a worker fails the runner instead of leaving an inactive pipeline.

PCM data must be immutable `bytes`. Reused response buffers count once.
Budget tracking follows the lifetime of admitted `PcmAudio` objects using weak references;
queues and completed workers do not retain finished inputs. If a handler or caller keeps
an admitted object, it continues to count until released. This is a PCM payload budget,
not a process RSS limit: arbitrary handler allocations, Python object overhead, encoded
Opus packets, and native codec state remain subject to the container's memory ceiling.
Handler outputs are admitted when returned; rejected output is not queued.

A handler canceled by session loss has 0.5 seconds to finish. Its result is always
discarded. If it keeps running, the runner reports `handler_cancel_timeout`, stops
accepting work, and enters the same bounded shutdown used for worker failures. The
standalone watchdog is armed for this internal shutdown as well as for signals;
library `run_bot()` reports `TimeoutError` instead of terminating its host process.

Shutdown is observed while the initial connection and handshake are still in
progress. `BotConfig.shutdown_seconds` must be positive and at most five seconds.
The first request fixes that one budget across
connection cancellation, worker cleanup, transmission finalization, and client
disconnect. It also clears both queues and cancels active handler and send tasks
synchronously exactly once. Receive, handler, busy-retry, and send boundaries
check the stop state before starting more work. Canceled child tasks, including
the saved active send after its worker clears the current-task pointer, remain
tracked for bounded cleanup. A second cancellation is issued only when that
budget expires. The library raises `TimeoutError` if user code still remains and
never terminates its host process.
Signal handling is temporary: public `run_bot()` restores the preceding Python
SIGINT/SIGTERM handlers after normal shutdown, startup failure, cancellation, or
cleanup timeout. Existing event-loop signal callbacks remain registered; host
applications may also respond to signals while the bot is running. The Linux
gate checks repeated library runs, actual delivery to restored callbacks,
default/ignored dispositions, and the SIGINT handler installed by `asyncio.run()`.
The runner regressions use the real client write path to verify that a completed
`burst_end` precedes entry into `HeadlessClient.stop()`, `disconnect`, and socket
closure during that cleanup. A separate controlled race verifies that the saved
send remains authoritative after the sender worker clears its current-task pointer.
The public standalone wrapper and Echo supervisor entry point also start a five-second process watchdog on the
first SIGINT or SIGTERM. A synchronous Python signal handler arms it before
queuing asynchronous shutdown, including when user code blocks the event loop.
Timer creation is idempotent under signal reentry. It remains armed through `asyncio.run()` cleanup and
exits with a failure code if Python cannot finish; repeated signals do not extend
the deadline. The Linux gate exercises a blocked event loop and a second signal
two seconds after the first. It sends SIGTERM from a parent process, distinguishes
watchdog exit from ordinary exceptions, and verifies the shared standalone lifecycle and public
`run_bot()` directly. Its negative control observes cancellation-suppressing user
code for the full watchdog interval with only the watchdog disabled. A portable
unit test controls the timer without sleeping or terminating its test process;
the Linux gate provides the separate proof of the real emergency exit. The gate
collects stdout and stderr with nonblocking binary reads under fixed monotonic
deadlines. Its diagnostic self-check routes a startup failure through the real
standalone wrapper and requires that the resulting report retain the traceback,
exit status, partial output, elapsed time, and bounded-cleanup result.
Child startup has a separate five-second readiness budget. The diagnostic regressions
deliberately delay startup beyond two seconds without relaxing shutdown measurements.

## Develop a bot

[`BOT_DEVELOPMENT.md`](BOT_DEVELOPMENT.md) provides the runnable QRZ example,
public imports, prepared-audio requirements, custom handler workflow, separate
Compose startup, and development checks. QRZ is developer-only and is not part
of the production image or server delivery.

[`echo_handler.py`](../headless/src/zenptt_headless/echo_handler.py) is the
production personal Echo response policy; it returns each completed non-empty
burst's immutable PCM and ignores empty bursts.

## Run the Echo supervisor

Production Compose starts `echo-supervisor` automatically after the server healthcheck:

```console
docker compose --env-file server.env up -d --build
```

The `zenptt-echo-supervisor` CLI (or `python -m
zenptt_headless.echo_supervisor`) maintains the private control WebSocket and creates
one independent client/runner pair per assignment through public `run_bot_session()`.
The supervisor and its sessions run in one Python process in the production
`echo-supervisor` container. Only the supervisor handles process signals; its
public `ShutdownWatchdog` remains armed through `asyncio.run()` cleanup.
Capacity is 16. Every session uses
two receive and send queue entries and a 4 MiB PCM budget. A session failure or fencing
does not stop its siblings; reconnecting control cancels stale sessions before accepting
new assignments. Logs contain assignment UUIDs and state counters, never tickets,
hidden channel keys, or audio.

The `HeadlessClient(..., echo_ticket=...)` option is reserved for the system
Echo supervisor. Its assignment tickets and private control channel are not
required for ordinary-channel bots and are not public server-management APIs.

## Verification

Run host tests without Android:

```console
.\scripts\test-headless.ps1
```

Add `-WithDocker` to build the Linux image and run real Opus and process-watchdog
checks. Run `scripts/test-headless-live.ps1` for isolated server/Caddy/bot fault
scenarios and the no-fault Echo round trip, or add `-CheckMetadataOnly` to validate
recorded scenario metadata without starting containers.

The authoritative command list, coverage boundaries, scenario deadlines, and live
fault descriptions are maintained in [`TESTING.md`](TESTING.md). Relevant reliability
risks and their regression anchors are indexed in
[`RELIABILITY_MATRIX.md`](RELIABILITY_MATRIX.md).
