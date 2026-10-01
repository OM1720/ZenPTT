# Bluetooth PTT and audio routing decisions

Status: current and normative

Purpose: define the BM008 control, Android communication-audio, capture,
playback, buffering, and power-saving invariants that implementation changes
must preserve.

Audience: Android developers, testers, support engineers, and AI coding assistants.

Authority: this document owns the Android communication-audio,
capture, playback, service-indicator, and power-saving contract.

Code anchors: `android/headset`, `Audio*.kt`, `Playback*.kt`, `OpusCodec.kt`,
`VoiceAudioTrack.kt`, and `ChannelViewModel.kt` under
`android/app/src/main/java/app/zenptt`.

Update when: hardware selection or parsing, route ownership, capture/playback timing,
audio indicators, buffering, diagnostics, or power saving changes.

Class names may change, but the behavior in this document remains required
unless a deliberate product decision updates the contract and its tests.

## Responsibilities

- `HeadsetController` in `:headset` owns discovery, control connections, learned
  rules, reconnect, and hardware-button diagnostics.
- `AudioPipeline` coordinates audio jobs, route wake/cleanup and the audio
  power-save state. It also serializes locally generated service indicators
  and releases their `AudioTrack` resources.
- `AudioRouteController` owns Android communication-device selection.
- `AudioCapture` owns one granted AudioRecord/Opus transmission lifecycle.
- `AudioPlayback` owns the bounded Opus queue, AudioTrack and playback drain.
- `ChannelViewModel` connects network/PTT state to audio lifecycle events. It
  must remain the only place that decides when server grant permits capture.
- SPP and SCO solve different problems. SPP carries PTT commands; SCO carries
  microphone and speaker audio. Do not merge their lifecycle or assume that
  one connected profile proves the other is ready.

## Hardware control channel

[`HEADSET_PTT.md`](HEADSET_PTT.md) owns discovery, guided learning, uniform
configuration, and runtime input. The `:headset` library has no network or audio
dependency. `PttForegroundService` forwards its Pressed/Released callbacks to
`PttInputLatch`, independently of the on-screen button.

The initial, disabled factory configuration uses the observed BM008 SPP commands
`+PTT=P` and `+PTT=R`. Known-token framing accepts fragmented undelimited commands.
BM008 can also be learned through the common wizard, including without protocol
hints when the observed stream has a confirmed CR/LF/NUL delimiter. Media events
accompanying an SPP setup never request transmission.

Only one selected hardware source is active. SPP supports secure and insecure
RFCOMM; reconnect uses 1, 3, 7, then 10 seconds. Disconnection, configuration or
channel change, and shutdown release hardware input without releasing an
independently held screen button. Terminal rejection and the 60-second limit
require a fresh physical press. Screen lock alone does not release hardware PTT.

Learning and the final simulated test cannot request a floor or start capture.
Bluetooth control readiness does not establish communication-audio readiness.

## Server grant and tone semantics

Pressing hardware or on-screen PTT only sends a PTT request. Microphone capture
must not start until the matching `ptt_granted` message arrives. A denied,
cancelled or stale request must never produce a grant tone or audio.

The grant tone means both conditions are true:

1. the server granted the channel;
2. the selected communication route and recorder are ready for speech.

An awake transmission has one grant tone. The first granted transmission after
a PTT-triggered wake has two grant tones separated by a short gap. The double
tone is cleared if the user releases before capture, the request is denied, or
incoming audio becomes the wake source.
The wake signal is two tones in total, not two wake tones followed by a third
regular tone. Each tone is 100 ms and the gap between them is 100 ms.

## Service indicator semantics

Four additional mono PCM16/48 kHz indicators are generated locally without
audio assets or new dependencies:

| Indicator | Signal | Audience and trigger |
|---|---|---|
| Channel free | Damped 680 Hz burst, 40 ms, 22% amplitude | Every participant after a normal or abnormal floor release, except that the interrupted sender hears only the interruption indicator. |
| PTT queued | Dry 32 ms wooden tap: 1.2/1.9 kHz inharmonic resonances at 70/30, deterministic 2 ms noise transient, 1 ms attack, natural decay, 14% peak | Only the requester while a physical hold is deferred. It starts immediately and repeats every 800 ms through 4800 ms. |
| PTT rejected | Two 360 Hz tones of 70 ms with a 50 ms gap, 35% amplitude | Only the requester when PTT is pressed during reception or an attempt fails before capture starts. |
| Transmission interrupted | 700-to-350 Hz sweep, 220 ms, 45% amplitude | The sender only after an active transmission ends finally because the recovery horizon or 60-second limit expires, or capture/transport fails. |

Every signal has a short fade-in and fade-out and must remain below PCM
clipping. Service indicators are always enabled; the existing grant tone and
static loss cue keep their independent semantics.

The channel-free indicator is an ordering barrier. A listener plays it only
after all ordered speech has drained. A sender plays it only after the server
confirms the matching burst's floor release with `ptt_ended`. Its state may
still be `draining`; that is separate from final server sealing. On an abnormal local end,
the interruption indicator replaces the channel-free indicator so that the
sender never hears both.
Connection loss, reconnect, and recovery do not generate service indicators.
A held, recoverable transmission remains silent until its existing recovery
result is final, so indicator state never changes capture, playback, routing, or
recovery.

Deferred PTT has an absolute five-second deadline from the original press.
Reconnect does not extend it. The queued cue uses absolute 800 ms start
boundaries; if the indicator player is occupied, expired cycles are skipped
instead of accumulated. A ready connection, rejection, release, Disconnect, or
session change cancels the deadline and future taps. An already-started tap
finishes normally after release, and the grant or rejected sound follows it
without overlap. Explicit Disconnect and session replacement instead stop all
audio immediately, so they may truncate the current 32 ms tap. The indefinite
Snackbar says `PTT queued — keep holding and wait for the grant tone.` At exactly
five seconds the hold is canceled once, the rejection indicator plays, and the
Snackbar says `Connection unavailable — release and press PTT again.` A late
reconnect cannot send that expired request.

The end of a server burst moves local playback to `Draining`. PTT may stop and
discard only this local `AudioTrack` tail, invalidate its playback token, and
suppress the stale drain callback and channel-free cue. The network request is
sent immediately; capture and the grant tone wait for playback shutdown and any
already-started queued cue. A new active burst is not interruptible and rejects
PTT. Ordinary and ECHO playback use the same rule.


In ECHO, capture is stopped and its recorder is released before the normal or
interruption indicator. Returned ECHO frames may arrive during the indicator
but remain buffered until it completes. Playback then starts with no lost
frames.

Every service indicator uses the current phrase's communication route and
`USAGE_VOICE_COMMUNICATION`. It does not switch to newly available Bluetooth in the
middle of a phrase. If that route disappears, the phone speaker is selected.
Off always selects the built-in speaker; On prefers Bluetooth SCO/BLE at the next
phrase boundary. Every
indicator owns one short-lived `AudioTrack`; jobs are serialized and the track is
stopped and released in `finally` cleanup. The track uses `MODE_STREAM` with
`CONTENT_TYPE_SPEECH` and matches the proven speech playback path. Before
`play()`, the complete indicator PCM is written and the streaming start threshold
is limited to the prepared frame count. If Android returns a larger threshold,
the remaining priming frames are silence. `MODE_STATIC` with voice-communication
attributes is not portable and produces an uninitialized track on some Android
versions.

Cancelling queued PTT by release, explicit Disconnect, ordinary connection or
reconnection, recovery, and incoming speech do not generate rejection or
connection indicators.

## Capture path invariant
An awake PTT request has a two-second response timeout. The first request that
wakes an expired idle route has a five-second timeout because Android may also
need to wake the network path. Both cases still fail closed without a grant tone
or capture when no matching grant arrives; the PTT-rejected service indicator
reports the failed attempt.

The capture path uses `MODE_IN_COMMUNICATION` and `VOICE_COMMUNICATION` as the
`AudioRecord` source. The service initializes routing from saved `HeadsetSettings`
before session preparation. Off selects the built-in speaker and explicitly sets
the recorder's preferred built-in microphone. On prefers SCO/LE Audio, with explicit
phone fallback. Capture verifies its actual input before the grant tone and while
sending frames; a mismatch fails capture instead of silently sending another input.
Selecting the route is not considered complete merely because
`setCommunicationDevice` returned true: the code waits until
`communicationDevice` reports the selected device, with a bounded three-second
timeout.

`AudioRecord` is started once per granted transmission. The same recorder must
remain running through all of these phases:

1. 100 ms input warm-up;
2. grant tone or double grant tone;
3. encoded network transmission.

PCM is read and discarded during warm-up and tones. This prevents the input
stream from stalling while ensuring that speech after the final audible tone is
read from the already-warmed recorder. Do not stop, release or recreate the
recorder between the tone and transmission. Reusing the warmed recorder is required
to preserve the first media frame after the grant tone.

Grant tones use the voice-call stream so they follow the communication route.
They must be generated only after route preparation and recorder warm-up and
are not service indicators.
There is deliberately no always-on or pre-PTT outgoing microphone buffer:
capture remains server-grant-gated for privacy, battery use and protocol
correctness. Continuity comes from keeping one recorder alive after grant, not
from recording speech before grant.

## Communication route and power saving

Joining a channel prepares the saved communication route immediately. The
microphone is not kept recording while idle. Keeping only the route ready
removes the normal SCO startup delay from active use while avoiding continuous
microphone capture.

The configurable idle timeout is stored in shared preferences:

- default: 10 minutes;
- minimum: 1 minute;
- maximum: 1440 minutes;
- current UI location: Settings.

PTT press and incoming audio are both activity. Either event updates one
monotonic last-activity timestamp. Incoming packets must not create or restart
a coroutine per packet; one idle monitor observes the timestamp.
Because Android may delay that monitor while the screen is locked, every new
activity also compares the monotonic timestamp with the configured deadline.
An expired deadline is treated as a wake even when the monitor did not get CPU
time to record sleep first.

| State/event | Required behavior |
|---|---|
| Channel joined | Prepare SCO, mark route awake, start idle monitor |
| PTT while awake | Reset idle time; retain low-latency route |
| Incoming audio while awake | Reset idle time and play normally |
| Timeout with no active capture/playback | Clear communication device, restore `MODE_NORMAL`, keep WebSocket and SPP connected |
| PTT while sleeping | Start SCO preparation immediately in parallel with the network request; use double grant tone after grant |
| Incoming audio while sleeping | Prepare SCO without tones, buffer packets until playback can start |
| Disconnect | Stop audio immediately, including any active queued tap; cancel idle work and clear the communication route |
| Transport loss | Continue granted capture for at most `H` from the first loss and within the original 60-second limit; preserve retained outgoing packets and the ordered receive cursor. An owned resumed floor continues the burst; a lost floor drains the old tail before a held PTT requests another grant. A fresh logical session requires release and a new press. |
| App backgrounding or screen lock | Keep WebSocket, SPP, audio reception and the active route policy running; hold a bounded partial wake lock only while PTT is pressed |

The route must never be cleared in the middle of capture or playback. Route
preparation/cleanup jobs and capture/playback cleanup are ordered under the
existing audio job lock. Capture and playback wait for an in-progress wake job
before creating their Android audio objects.

## Incoming audio buffer

Waking SCO or a temporarily slow network may delay delivery by several seconds.
Incoming Opus packets therefore enter a bounded ordered FIFO. The client plays
only a contiguous frame or a finalized missing range and re-registers its
recovery position after reconnect. Playout starts from five contiguous frames
and remains fixed at 100 ms after an underrun. `AudioTrack` is fed at most 100 ms
ahead. Before `play()`, speech playback explicitly lowers the streaming start
threshold to one decoded 20 ms frame. The returned threshold must remain within
the 100 ms write-ahead bound; capacity, buffer size, requested and actual
thresholds, write-ahead, and final underruns remain available in bounded
diagnostics. The first ten observed underrun increases also record the written
and playback-head positions, write-ahead, outstanding frames, input wait, and
write gap. Drain diagnostics separate streaming underruns from any count added
while the final PCM tail is draining.

Live receive-path diagnostics retain at most 30 timing events and never add a
timer or file write. Intervals strictly greater than 100 ms are recorded, with
at most ten `ws_gap`, `fifo_delay`, and `playback_gap` events per burst. The
`receive_path` summary reports the maximum interval at each stage plus recorded,
retained, and suppressed event counts. `receive_path_events` distinguishes a
late WebSocket callback from lock/FIFO delay and a delayed handoff to the
playback queue. Frame age is measured from the original binary callback; gaps
and duplicate envelopes do not replace that timestamp.

Source diagnostics retain at most 30 `source_send_gap` events, with no more
than ten per burst. Each event records the successful WebSocket enqueue
interval, frame range age, socket generation, queue size, and whether the range
is a retransmission. Failed enqueue attempts do not advance the interval.
Server logs add bounded `media_timing` events for uplink receive, listener handoff,
outbound queue wait, and completed binary send, including personal ECHO sessions.

This is not microphone recording or a PCM history buffer: only compressed Opus
packets for eligible talk bursts are kept temporarily. Cumulative uplink ACK, the
`recovery_horizon_ms` retention policy, and the receive FIFO bound of
`max(60 seconds, 3 * recovery horizon)` are defined in `protocol.md`. An
authoritative `listen_reset` skips expired history without creating PLC or a
static loss cue; those sounds are reserved for server `burst_gaps`.
The Echo bot sends its response on a normal 20 ms media timeline; AudioTrack remains
the final 1x playout clock.

## Device routing details

Android commonly exposes separate SCO sink and source device IDs. The
communication-device API selects the sink; the actual microphone source is
verified through `AudioRecord.routedDevice`. Playback uses
`USAGE_VOICE_COMMUNICATION` and verifies `AudioTrack.routedDevice`.

Bluetooth SCO is preferred, followed by a BLE headset. Speaker fallback is
allowed for playback but not forced for capture. A2DP is not the PTT voice
route because it does not provide the bidirectional low-latency communication
path required by this feature.

## Diagnostics contract

**Share debug info** and **Send debug info** are part of feature support, not
temporary logging. Keep these
signals available without audio payloads, secrets or clear normal-channel
codes:

- Bluetooth permission, adapter, bond, profile and advertised UUID state;
- secure/insecure SPP attempts, raw RX hex/ASCII and parsed commands;
- every observed media key, PTT transition and synthetic release reason;
- all Android audio devices, available communication devices and the current
  communication device;
- route request result, readiness, elapsed time and selected device;
- recorder warm-up, grant-tone count, actual capture/playback routed devices;
- power-save timeout/state plus `power_save entered` and `power_save wake`
  events, including whether wake came from recorded sleep or an expired idle
  deadline;
- bounded PTT wake-lock acquisition and failure counters;
- bounded live receive-path maxima and `ws_gap`, `fifo_delay`, and
  `playback_gap` timing context without audio data or normal-channel codes.

Wall-clock timestamps intentionally allow SPP press, network grant, route wake
and grant tone to be correlated in one copied report. Diagnostic collection
must remain bounded and must never crash the application.

## Verification

Automated coverage is defined in [`TESTING.md`](TESTING.md). Physical BM008,
SCO, tone, sleep/wake, locked-screen, and routed-device checks are defined in
[`MANUAL_TESTING.md`](MANUAL_TESTING.md). Diagnostic data and privacy handling
are defined in [`SUPPORT.md`](SUPPORT.md).

Any change in this subsystem must run JVM tests, Android lint, debug and
instrumentation APK assembly, relevant connected instrumentation, and the
physical scenarios affected by the change. An emulator cannot validate BM008
SPP behavior, SCO timing, audible tones, or physical microphone/speaker routing.

## Deferred headset changes

`PttForegroundService` owns the pending setting in memory. `AudioPipeline` captures
barriers for the current capture/playback/cleanup jobs. New speech waits for the
route-change job, preserving the incoming queue. Each playback job ends at a drained
burst boundary; it does not wait for future queued bursts. The service also waits
for held/queued PTT and releasing state, then closing indicators, before committing
through `ConnectionPreferences`. Save failure leaves the previous policy intact.
Cancelling setup cancels an unapplied Save. Repeated switch input is disabled while
pending; Reset/Undo can replace the pending value through the same path.

Communication-route preparation is serialized. Playback and indicators explicitly
prefer the selected output and verify actual routing. Missing or rejected phone
selection fails closed; system-default Bluetooth is never used as an Off fallback.
Device removal ends a capture whose selected input disappeared and releases hardware
PTT. It does not release an independent screen hold or resume a failed transmission.
Connected Bluetooth becomes eligible again at a phrase boundary, without changing
the persisted enabled flag. Route clearing remains forbidden during active audio.
