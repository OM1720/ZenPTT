# Manual testing ZenPTT

Status: current

Purpose: define the physical and environment-dependent checks that automated
tests cannot establish.

Audience: testers, release owners, and support engineers.

Authority: this document owns physical-device and environment-dependent acceptance
scenarios that automated tests cannot prove.

Code anchors: the production Android and browser clients, public Caddy deployment,
and the diagnostic evidence named by each scenario.

Update when: physical Bluetooth/audio, browser behavior, screen-off, degraded-network,
public deployment, or required evidence behavior changes.

Run the automated gate in [`TESTING.md`](TESTING.md) first. Use the same APK on
all phones and record the APK version, phone models, Android versions, server
environment, report codes, and observed failures with the release or issue being
validated. Do not store historical acceptance snapshots in this repository.
The mapping from reliability risks to these scenarios and executable regressions is in
[`RELIABILITY_MATRIX.md`](RELIABILITY_MATRIX.md).

## Headset discovery and PTT

BM008 is the first required physical test. Repeat the checks on other headsets as
they become available. Automated tests do not prove radio compatibility.

1. Pair BM008 in Android settings. In ZenPTT Settings, select **Set up headset**
   and select BM008 as a new device. Follow each prompt. Select Continue if the
   wizard offers another method. Confirm the indicator follows the button, then
   Save. **Connection: SPP** must be offered first. If SPP passes, BLE and Media
   must not be tested. On **Press other buttons.**, leave PTT released for at
   least five seconds. Delayed Bluetooth notifications must not cause an error.
2. Select ECHO. Verify one transmission per press and that release stops it.
   Repeat with the screen locked. Disconnect BM008 during transmission, reconnect
   it, and verify that only a new press starts transmission.
3. Start setup again and cancel from different steps, including Android Back and
   app backgrounding. The saved setup and switch position must remain unchanged.
4. While idle, disable known protocol hints with the command below. It stops the
   running app. Repeat step 1, then run the same command with `false` to restore
   hints for later setup runs.

   `adb shell am start -S -n app.zenptt/.MainActivity --ez headset_disable_protocol_hints true`

5. After a failure, send debug info and record the report code, failed step,
   screen-lock state, phone/headset models, Android version, and APK version.
   Expected metadata includes `headset.source=Spp` and a selected Hold rule.
   Setup must not create real PTT presses.

Check the two settings actions, every English instruction and Cancel/Back,
background/rotation cancellation, no audio or floor requests during learning,
independent verification, other-button rejection, and Save gating. A failed
attempt must retain the previous configuration and switch position. Off retains
the setup; Reset restores factory/off; Undo restores everything.

After saving, test ECHO with the screen on and locked, music-app competition,
disconnection while held, reconnection, denial, queue cancellation, and the
60-second limit. Repeats must not restart a terminated transmission. Confirm
that hardware release does not release an independently held screen button.

## Physical personal ECHO

1. Start the intended server and confirm `/health` through Caddy.
2. Install the APK on one Android 12+ phone.
3. Pair the UNIWA BM008 when Bluetooth behavior is in scope.
4. Open Settings, enter `ws://<LAN-IP>:8080` or `wss://<DNS-name>`, use Ping, and return Home.
5. Select `ECHO`, press keyboard **Done**, and grant microphone, Nearby devices, and notification permissions.
6. Confirm the participant count is two and wait for the static Ready ring. If the
   supervisor is restarting, confirm **Connecting** and that PTT produces the rejection cue.
7. Hold PTT, confirm the grant tone and **Transmitting**, speak for 3-5 seconds,
   and release.
8. Confirm the channel-free indicator plays after recording stops but before
   **Playing echo**, then hear the complete phrase and return to **Ready**.
9. During an active echo burst, press PTT and confirm the two-tone PTT-rejected
   indicator sounds without interrupting or losing the echoed phrase. Test the
   interruptible local `Draining` tail separately in the two-phone section below.
10. With BM008 disabled, record 45-55 seconds and release before the limit. Confirm
    the complete echo has no clicks, underruns, gaps, or reconnect.
11. Hold PTT to the 60-second limit and confirm exactly one descending interruption
    indicator replaces channel free before the complete echo playback.
12. Use **Share debug info** and record RTT, PTT grant time, sequence gaps, selected route,
    queue growth, underrun events, drain counters, and reconnect count.
13. Repeat concurrently on three phones with distinct phrases. Every phone must hear
    only its own phrase, and all three must continue after one supervisor restart.

Pass criteria: capture starts only after a grant, the complete phrase is heard,
received and played frame counts match, `underruns=0`, `Gaps 0`, reconnect is zero,
no audio is retained after playback, and another cycle works without reconnect.

## Two-phone normal channel

1. Install the same APK on two phones and connect both to the same server and
   normal channel code.
2. Hold PTT on phone A. Confirm A shows **Transmitting** and phone B shows
   **Receiving** and plays the complete phrase.
3. Release A. Phone B must hear the short dry channel-free indicator only after the
   final syllable; phone A must hear it only after the server confirms release.
   Repeat from B to A. For playback regression acceptance, alternate at least
   20 phrases of 3-5 seconds. Every phrase must be complete, and both clients
   must return to Ready without a stuck Receiving state.
4. Press both PTT controls almost together. Exactly one phone must transmit.
   The denied phone must hear exactly one two-tone PTT-rejected indicator, must
   not be queued, and must release/press again.
5. Press PTT on the receiving phone. It must play the PTT-rejected indicator
   immediately, continue incoming playback, and return to Receiving when released.
6. Disable the transmitter's network while speaking. The listener must leave
   **Receiving** after playback drains, hear one channel-free indicator, and be
   able to obtain PTT after the floor lease expires. Keep the sender offline
   beyond `H`: it must remain silent during recoverable transport loss and hear
   one interruption indicator only when its recovery deadline expires.
7. Hold one transmission past the configured maximum. The server must end it;
   the sender must hear one interruption indicator rather than channel free, and
   no further audio may be accepted until a new PTT request.
8. Restore network access after step 6 and confirm both clients rejoin without
   resuming the interrupted transmission or generating connection recovery
   sounds.

9. While Ready or Receiving, disable one phone's network for more than two
   seconds and restore it. Confirm that loss and recovery remain silent. Repeat
   across several reconnect attempts and after a fresh server incarnation.
10. Start PTT while a connection or channel switch is pending. Hear a quiet
    wooden tap at 0, 800, 1600, 2400, 3200, 4000, and 4800 ms while continuously
    holding. Confirm the indefinite queued Snackbar. A ready connection stops
    future taps; the later grant follows an already-started tap without overlap. With no connection, at
    exactly 5000 ms hear one rejected signal and see `Connection unavailable —
    release and press PTT again.` Confirm no eighth tap and no request after a
    late reconnect. Repeat release, Disconnect, and session-change cancellation;
    each must be silent. A tap already playing at release must finish, while
    Disconnect or session change may truncate it as part of immediate audio
    cleanup. Occupy the indicator player and confirm missed taps are
    skipped rather than played in a burst. No tap may be present in recorded
    speech.
11. End an ordinary incoming burst with enough local playback buffered to remain
    in `Draining`, then press PTT. Confirm the tail and channel-free sound are
    discarded, the request is sent immediately, and the grant tone precedes
    capture. Repeat in ECHO and with screen, BM008, and accessibility PTT. Press
    during a newly active burst and confirm a rejected signal and the instruction
    to release and press again.
12. Tap explicit **Disconnect** during an otherwise active session and confirm it
    produces no service indicator, stops local capture/playback
    immediately, and lets the second phone obtain the floor without waiting for
    the lease. Record the time from the tap to the second grant. Repeat with the
    sender network already fully unavailable: local cleanup must remain
    immediate, while the second grant occurs only at the current lease boundary.
13. Send debug info from both phones. The listener must report `playback prepared`
    with `capacity` of at least 4800 frames and `threshold` no greater than one
    20 ms frame, write-ahead no greater than 100 ms, an empty playback queue,
    balanced playback starts/stops, `underruns=0`, and `Gaps 0`.
    If `underruns` is non-zero, the first ten increases must have bounded
    `playback underrun` events with queue/write timing context, and `playback drain`
    must report the observed, before-drain, and after-drain counts.
    On a stable channel, both reports must contain no `Uplink ACK watchdog expired`
    event, no `release_failed` event, and a `release_fail:0` summary.

## UNIWA BM008 control and routing

1. Pair BM008 in Android Bluetooth settings before opening ZenPTT.
2. Allow Nearby devices and microphone permissions.
3. Enable **Headset on/off** with the factory or learned BM008 setup, select `ECHO`, and confirm **Ready**.
4. Turn **Headset on/off** off and confirm **Disabled**. Diagnostics must show no
   receiver or MediaSession; turn it on again.
5. Hold the hardware PTT button. Confirm one request and one transition to
   **Transmitting** after the grant tone.
6. Speak into BM008 and release. Confirm ECHO playback through BM008.
7. Repeat to verify that SPP and the audio route remain reusable.
8. Press/release rapidly several times. Duplicate edges must not create a stuck
   transmission or a permanent **Channel busy** state.
9. On a normal channel, hold PTT and power off BM008. The controller must
   synthesize exactly one release and the descending interruption indicator must
   play through the phone speaker, not the disconnected headset.
10. Confirm normal channel-free and PTT-rejected indicators use the active
   communication route while Bluetooth remains available.
11. Disconnect Bluetooth, trigger 20 consecutive channel-free indicators and
    five PTT-rejected indicators. Every signal must use the built-in phone
    speaker. Each completed signal must report `indicator prepared` with
    `threshold` no greater than `primed`, followed by `indicator drain` with
    `completed=true`; diagnostics must contain no drain timeout.
12. Use **Share debug info** and verify `headset.source=Spp`, `headset.behavior=Hold`,
   bounded `event source=Spp` entries, route selection, and actual
   `AudioRecord`/`AudioTrack` routed devices.

For failures, record the setup step and diagnostic report code. Raw learning payloads
are not uploaded. Accompanying media-key events must not control an SPP assignment.

## Audio power saving and wake

1. In Settings set **Power save** to 1 minute, return Home, and join ECHO or a normal frequency.
2. Leave capture and playback idle for at least 65 seconds.
3. Use **Share debug info** and confirm the route entered the sleeping state while the
   WebSocket and BM008 SPP remained connected.
4. Press PTT. Confirm route preparation, exactly two grant tones, and preservation
   of all speech after the second tone.
5. Press PTT again before the next timeout. Confirm one tone and normal low-latency start.
6. Let the route sleep again, then send speech from a second phone. Confirm the
   beginning is delayed if necessary but preserved while the route wakes, with no tone.
7. Disconnect and confirm Android audio mode returns to normal.

No timeout may clear the route during active capture or queued playback.

## Background and locked screen

1. Join ECHO or a normal channel and confirm the ongoing notification.
2. Lock the phone. After a meaningful idle interval, receive a complete phrase.
3. With the screen locked, transmit through BM008 and confirm release.
4. Remove ZenPTT from Recents and repeat one receive and transmit cycle.
5. Disable and restore Wi-Fi. Reconnect attempts must continue until rejoin or
   explicit Disconnect; the notification status must follow the connection.
6. Expand the notification and confirm **PTT** is left of **Disconnect**.
7. Tap **PTT** and confirm it opens the existing session rather than creating a
   new connection.
8. Return to the expanded notification, tap **Disconnect**, and confirm the WebSocket, BM008
   connection, wake lock, audio route, service, and notification all stop.
9. Reconnect, reboot without Disconnect, and confirm no session or notification
   is restored after boot.

For Android Doze, use:

```console
adb shell dumpsys deviceidle force-idle
adb shell dumpsys deviceidle unforce
```

If a vendor suspends the WebSocket, repeat after allowing unrestricted battery
use and record that device-specific requirement.

## Degraded-network buffering

Use a normal two-phone channel with `RECOVERY_HORIZON_MS=5000`. Apply shaping
outside ZenPTT and record direction, throughput, RTT, jitter, packet loss, and
outage duration. Repeat the boundary cases with 1,000 and 60,000 ms when changing
policy formulas.

Run at least these cases:

1. Establish a healthy baseline. Confirm 16 kbit/s Opus, playback near 100 ms,
   cumulative ACK progress, no gaps, no replay, and no reconnect.
2. Introduce frequent 100-500 ms full outages while PTT remains held. Confirm
   capture continues, reconnect resumes the same owned burst, retained frames
   replay idempotently, and no speech is lost. Audible delay may accumulate.
3. Delay cumulative ACK progress just below the advertised watchdog and confirm
   the socket remains. Cross the watchdog with unresolved audio and confirm one
   replacement, a higher generation, and replay from the cumulative cursor.
4. Keep the sender offline for less than `H` but beyond the floor lease. Confirm
   capture stops when resume shows the floor is gone, the old retained tail is
   queued before its watermark, and a still-held PTT gets a new burst only after
   the old burst completes and a new grant tone sounds.
5. Keep the sender offline beyond `H`. Confirm recording stops at the horizon,
   the interruption indicator sounds, gaps are declared only by the server, and
   another transmission requires release and a new press. Trigger several
   reconnect attempts before expiry and confirm none moves that original
   first-disconnect deadline.
6. Interrupt only downlink inside `max(15 seconds, 3 * H)`. Confirm fast ordered
   history delivery after resume, strict 1x playback, no FIFO eviction, and no
   later burst passing an unresolved earlier one. Resume after the earliest
   frames have expired but before the sealed burst metadata expires. Confirm an
   authoritative `listen_reset`, no local PLC/static cue for the skipped prefix,
   and no stuck Receiving state. Stay offline beyond all retained history and
   confirm the reset advances to the next available burst.
7. Create one-to-three missing frames and confirm Opus PLC. Create a gap of four
   or more frames and confirm exactly one 80 ms static cue regardless of length,
   then inspect the support report for
   `audio.playback_write_ahead=max=<value>ms,limit=100ms`; `<value>` must not
   exceed `100`.
8. Repeat equivalent loss and reconnect cases in ECHO. Confirm it first seals
   the source, replies as a separate ordered burst, supports a 60-second source,
   resumes through the normal token, and reports one participant.
9. Hold PTT beyond 60 seconds on a stable connection and while reconnecting.
   Confirm capture stops at the same absolute burst-duration boundary, an
   interruption indicator sounds, no position `>= 3000` is sent, and a new
   transmission requires release and a fresh press.
10. With an instrumented build, block playback and fill the derived receive FIFO.
    Confirm the first overflow reconnects from the unchanged playback cursor and
    discards nothing. Reproduce overflow at that same cursor and confirm automatic
    reconnect stops with an explicit error. The support report must contain both
    `receive_overflow` events and exact server `gap` ranges, if any.
11. Inject an uplink `audio_rejected` with reason `payload_mismatch`. Confirm the
    client stops capture, clears retained custody, closes the logical session,
    shows an explicit protocol-conflict error, and performs no automatic replay
    or reconnect until the user taps Reconnect.
12. Block one server WebSocket writer while another listener remains healthy.
    Confirm the blocked socket closes with code `1001` after ten seconds, the
    healthy listener remains live, and the slow listener resumes from its
    playback cursor.
13. With a controlled proxy, drop a granted PTT response before it reaches the
    phone. Confirm resume with an owned snapshot floor repeats the original
    request ID and accepts only its matching grant, with no microphone audio
    before that grant. Repeat reconnect while still awaiting the response, then
    repeat with a physical release during the outage: the recovered request must
    be canceled rather than becoming a phantom transmission.
14. Release PTT during an outage and separately drop `burst_end` or its terminal
    confirmation. Confirm capture cleanup finishes, the retained tail and original
    terminal control precede any replacement request, and replay uses the same
    final watermark. After completion, start an adjacent burst and confirm a
    duplicate old terminal cannot stop it.

Pass criteria:

- no microphone audio is sent before the matching grant;
- each cumulative ACK permanently resolves every lower sequence;
- `burst_end` releases the floor without waiting for final uplink resolution;
- unintentional transport loss preserves the floor until replayed `burst_end` or
  the current lease boundary;
- downlink frames and gaps play once in strict order;
- an interrupted outgoing tail remains attached only to its original burst;
- Receiving ends only after playback drains;
- sender, playback, server, and WebSocket queues remain bounded;
- a stable channel produces no ACK-watchdog reconnect and no failed PTT release;
- a recovered good network does not leave either client in a permanently stale
  transmitting, receiving, or busy state.

The repository does not claim a universal recovery-time guarantee without a
recorded network profile.

## Browser client

Run the browser preview as described in
[`WEB_CLIENT.md`](WEB_CLIENT.md#start-the-isolated-local-preview). Open
<http://127.0.0.1:5173/web/> in desktop Chrome. The browser must use the same
server as any Android phone in a cross-client check. Automated browser tests
cover membership, channel switching, controlled network faults, and codec
signals. The checks below confirm real audio, devices, and interaction.

### Audio and Android interoperability

1. Connect Chrome to ECHO and allow microphone access. Hold the Halo, speak a
   short phrase, and release. Hear the complete reply through the default
   output device. Confirm the grant cue, black transmit core, and Volume icon
   during the reply. Disconnect: Chrome must release the microphone. Reconnect
   and repeat the ECHO check.
2. Connect Chrome and Android to `ROOM1` on the same server. Wait until browser
   audio is ready. Speak while holding browser PTT: Android must start playback
   before release. Release and repeat from Android to Chrome. Each client must
   be able to take the floor next. Record ECHO and both directions as pass/fail.
3. While Chrome receives a longer Android phrase, open Settings and return Home.
   Speech must continue without a new channel session. Select another available
   microphone, switch to ECHO, and test again. The output remains the system default.

For Android over trusted local Wi-Fi, bind only the preview's Caddy port to the
PC's LAN address. Keep the browser development server on loopback:

```powershell
$env:ZENPTT_LAN_ADDRESS = '<PC-LAN-IP>'
docker compose -p zenptt-web --env-file server.local.env -f compose.yaml -f web/compose.yaml -f web/compose.lan.yaml up -d --no-deps caddy
```

Set Android Settings > Server to `ws://<PC-LAN-IP>:18080` and check Ping.
Keep Chrome on <http://127.0.0.1:5173/web/>. Allow TCP 18080 from the local
subnet on the Windows LAN interface; do not expose the preview through a
router. If Ping fails, open `http://<PC-LAN-IP>:18080/health` on the phone and
expect `{"status":"ok"}`. Guest Wi-Fi isolation can block the connection.
After the check, restore Android's previous server address and remove the LAN
binding:

```console
docker compose -p zenptt-web --env-file server.local.env -f compose.yaml -f web/compose.yaml up -d --no-deps caddy
```

Remove any temporary firewall rule created for this check.

For Android over USB, enable USB debugging, authorize the PC, and run:

```powershell
& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" reverse tcp:18080 tcp:18080
```

Set Android Settings > Server to `ws://127.0.0.1:18080`, check Ping, and use
`ROOM1` with Chrome. After the check, restore Android's previous address and
run `adb reverse --remove tcp:18080`. An emulator can use
`ws://10.0.2.2:18080` but cannot prove physical audio.

### Controls, permissions, and installed browsers

1. Connect Chrome to ECHO. Hold Space outside the channel field, speak, and
   release. The reply must play. Hold Space again, switch windows, then return:
   PTT must be idle and must not restart. A new press must work. Space inside
   the channel field must type normally.
2. Check Main at normal zoom and 200% zoom: power and Settings in the header,
   channel/count above the Halo, and no clipped controls. Open Settings during
   reception. Check Ping, the grouped Audio section, build footer, hover hints,
   and keyboard focus. On Settings, Space must not activate Home. After returning
   Home, Space must control PTT without activating navigation.
3. Download the local diagnostic JSON. It must contain no channel code or audio.
   Reload: recent channels and microphone choice must remain available, but
   connection requires Connect. Deny microphone access for a fresh connection:
   the channel must stay connected with PTT disabled and **Retry audio** visible.
   Allow access in Chrome site settings, select **Retry audio**, and verify ECHO
   without a second participant. Disconnect must release audio resources.
4. On each target Android Chrome or iPhone Safari device, follow the installation
   steps in [`USER_GUIDE.md`](USER_GUIDE.md#before-starting). Open the installed
   app, allow microphone access, and test ECHO capture and playback. Hide and
   reopen it while PTT is held: transmission must not restart on return. Record
   browser/device versions and whether audio or page state survived. Do not
   assume background reception or recovery.

### Hosted browser smoke check

Run `scripts/test-web.ps1 -KeepRunning` and open <http://127.0.0.1:18081/> in
Chrome. The hosted client must open at `/`. Connect to ECHO and hear a reply.
Hover over the Android download icon: it must show the hosted version. Click it
and confirm the APK download. Public HTTPS, certificate issuance, and installer
checks remain in the deployment and automated test runbooks.

For a failure, record the step, displayed error, browser and device versions,
and whether microphone and speaker audio were audible. Keep test reports outside
the repository. Automated signal checks cannot prove physical audibility.

## Public deployment

After installing the hosting package:

1. Confirm DNS resolves to the intended host.
2. Confirm HTTPS certificate validity and redirects/entry page behavior.
3. Confirm public `/health`, `/app/latest`, and the versioned APK download URL.
4. Confirm port 8000 is not reachable from the internet.
5. Run ECHO and two-phone PTT through `wss://<domain>`.
6. Check update discovery and the Android installer flow.
7. Reboot the host and confirm all containers return healthy.
8. Fetch a support bundle with `scripts/fetch-logs.ps1`.

## Evidence to record outside the repository

For each release or defect, retain only the evidence needed for the decision:

- APK version code/name and signature source;
- server environment and deployment identifier;
- phone model and Android/API version;
- BM008 identifying label or firmware when available;
- scenario and expected/actual result;
- diagnostic report code or redacted debug text;
- relevant server/Caddy logs;
- whether battery optimization was enabled.

Do not record conversation audio, clear normal-channel codes, secrets, or full
support bundles in public issue trackers.

## Connected headset and audio switch acceptance

Physical phone/BM008 checks remain pending when only an emulator is available.

1. Keep several devices paired but disconnected. Open **Set up headset**: only
   connected profiles/external inputs appear; connect/disconnect BM008 and check
   live addition/removal without duplicate addresses. No scan permission is asked.
2. On a fresh install or Reset, verify **Headset on/off** is Off. With BM008 still
   connected, use ECHO and speak near the phone, then near the headset: capture,
   returned speech, grant tones and service signals must use only the phone.
3. Enable the switch. Verify headset capture/playback, then restart and confirm the
   choice persists. Disable during transmission and during reception: **Wait.**
   remains until the phrase/closing signal ends. The next phrase uses the phone.
4. Reset and Undo while waiting. Confirm the final accepted setting and audio route
   agree. Cancel a Save waiting for reception; the previous setup must remain.
5. Disconnect during hardware transmission. Verify it ends, phone audio becomes
   available, and reconnect does not resume transmission. A fresh press works.
   Reconnect during phone reception and verify Bluetooth returns between phrases.
6. Repeat ECHO with the screen locked. Send diagnostic report code, failed step,
   and phone/Android model if microphone, speaker, timing or recovery differs.
