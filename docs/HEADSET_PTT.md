# Headset PTT discovery and learning

Status: current and normative

Purpose: define headset discovery, button learning, saved rules, and runtime input.

Audience: Android developers, testers, and AI coding assistants.

Authority: this document owns the hardware PTT contract. `MANUAL_TESTING.md` owns
physical headset checks; `BLUETOOTH_PTT_AUDIO.md` owns audio routing.

Code anchors: `android/headset/src/main/java/app/zenptt/headset`,
`android/app/src/main/java/app/zenptt/PttButtonSettingsUi.kt`, and
`android/app/src/main/java/app/zenptt/HeadsetCommands.kt`.

Update when: headset sources, learning, saved rules, setup UI, or runtime input change.

The Android `:headset` library owns hardware PTT discovery, transport lifetimes,
learning, and application of one saved button rule. It has no microphone,
channel, server, or network-floor API. `PttForegroundService` passes its working
edges to `PttInputLatch`; the existing server grant remains the capture gate.

## Settings and persistence

Settings shows a Lucide Headset icon, the fixed label **Headset on/off**, and a
monochrome Material 3 switch. A Lucide Settings 2 action labelled **Set up headset**
opens setup. The ordinary card contains no model name or saved-setup subtitle.
The switch controls hardware PTT and the communication audio policy. Off explicitly
uses the built-in microphone and loudspeaker, including all app tones. On prefers
Bluetooth SCO/LE Audio and falls back to the phone if unavailable. User choice is
retained across launches and upgrades; fresh installation and Reset are Off.
Manual changes wait for the current phrase, PTT request, and closing tones. While
waiting, the switch shows the effective state and **Wait.** with Lucide Hourglass.
Switch, Save, Reset, and Undo share the service's deferred persistence path.
Cancelling a pending Save discards it. A disconnected capture cannot resume itself;
Bluetooth recovery is applied between phrases. See `BLUETOOTH_PTT_AUDIO.md`.

`ConnectionPreferences` stores one versioned `HeadsetSettings` JSON value at
`headset_settings_v1`. It includes enable state, an explicit `HeadsetSetup`, source,
device selection, event address, framing, recognition rule, and Hold/Toggle.
The initial value is disabled with an explicit BM008-compatible SPP Hold rule.
The factory selector prefers a paired name containing BM008, then a name containing
PTT with the SPP UUID. A learned SPP/BLE setup binds to the selected device address;
HID binds to its Android descriptor. Media keys remain global because Android
does not reliably expose the originating Bluetooth device.

Migration preserves both the old BM008 enable choice and existing media-key
assignments. Corrupt configurations remain disabled rather than silently activating
the factory setup. Off preserves the setup. Reset restores the explicit factory
setup and switches off; Undo restores the entire previous value.

Learning drafts are memory-only. Save replaces the configuration only after a
successful disk commit; failure restores the old in-memory preference and leaves
the draft available for retry. Successful Save enables the switch. Cancel, Back,
Activity stop/recreation, and service stop discard the draft. No setup edge reaches
the real PTT latch, including during the final simulated test.

## Discovery and transports

The device screen lists confirmed connections from Android HEADSET, A2DP, LE Audio
(API 33+), GATT, the module's own active connections, and external non-virtual input
devices. Bluetooth records are deduplicated by address. Profiles are polled every
second and rechecked at selection. Profile proxies are released when selection
ends. Names are display-only. SCO need not be active to list a connected profile.
Disconnected paired devices and BLE advertisements are never listed. There is no
nearby scan or global-media bypass; only Bluetooth Connect permission is requested.
An empty list says **Connect your headset.** Public Android APIs cannot expose
another app's private SPP-only connection unless a supported profile also reports it.

For a selected Bluetooth device, test SPP, BLE, then Android media events, skipping
transports unavailable for that device type. A HID selection tests that input descriptor.
This operational priority favors direct background connections over shared media
keys and foreground-only HID; it is not a universal hardware reliability guarantee.
Each new method starts only after the user taps Continue; no transport knowledge is
needed. The wizard displays **Connection: SPP**, **Connection: BLE**,
**Connection: Media**, or **Connection: HID**. When offering another method it shows
the next method's name. An unsuccessful method does not overwrite settings or stop
the remaining search. The first method with a verified rule stops the search;
lower-priority methods are not opened, even if that first rule is Toggle-only.
The chosen rule remains a draft until the final indicator test and Save succeed.

- Media: HeadsetHook, Play/Pause, Play, Pause; Activity and one MediaSession share
  the same input policy.
  Ready means the listener is active; it does not prove a Bluetooth connection.
- HID: Android events from the selected external non-virtual device, excluding
  system keys, modifiers, and volume. Delivery may require a foreground Activity.
  Setup warns about this before saving; there is no AccessibilityService.
- SPP: standard Serial Port Profile, with secure and insecure RFCOMM attempts.
  Unknown streams require CR/LF/NUL-delimited messages. Socket read boundaries
  never define message boundaries. Optional known-token framing is a hypothesis,
  not automatic approval.
- BLE: discover services and sequentially enable notifications/indications using
  standard CCCD writes. Up to 32 unambiguous notifying characteristics are observed.
  Persist the discovered service/characteristic with the learned rule. Duplicate
  characteristic UUIDs are excluded because they cannot be restored unambiguously.

Do not send unknown activation/configuration commands. Devices requiring proprietary
initialization, inaccessible events, encryption, or indeterminate stream framing
can remain unsupported even when the transport itself is available.

## Learning and verification

`ButtonLearner` considers key DOWN/UP, exact message pairs, single-byte states,
individual state bits, and isolated pulses. Rules are data, never executable code.
Unknown protocols do not require Internet access or a model database.

After readiness, observe three seconds with the button released. Request three
holds lasting 4, 6, and 4 seconds until the release prompt, with three-second
release windows. Allow up to two seconds of reaction time after each instruction.
Build hypotheses using these three cycles only. Verify them against two additional
cycles lasting 5 and 4 seconds. No event in the first completed cycle skips that
method; a connection attempt itself has a 15-second deadline.

Hold requires matching press/release edges, at least 1.5 seconds held, no false
edges in quiet intervals, and at least one second of variation in measured hold
durations. The latter rejects periodic telemetry bits that accidentally line up
with the reaction windows. Short complete clicks or isolated presses may offer
Toggle; inconsistent long holds must not be downgraded to Toggle.

Raw collections are limited to 120 seconds, 2,048 observations, and 256 KiB per
method. Individual framed messages are limited to 256 bytes. Hypothesis generation
is bounded to 64 distinct press/release values and 4,096 rules. Exceeding a limit
cannot produce an accepted rule.

Only hypotheses passing all five cycles are eligible. Equivalent encodings prefer
exact messages over byte and bit matches. Within the first successful method,
prefer verified Hold over Toggle. Check other buttons with the chosen rule before choosing
behavior. A false activation fails setup and offers Retry. The final independent
simulated on/off cycle and the user's confirmation are both required for Save.

## Runtime and diagnostics

One selected source and at most one MediaSession are active. SPP/BLE media-key
side effects never become PTT edges. Release only the hardware input on settings
or channel changes, selected-device disconnect, Bluetooth shutdown, and service
stop. Terminal rejection and the 60-second limit require a fresh physical press;
repeats cannot restart capture. Screen lock alone does not release a working hold.
Connection generations reject callbacks from closed transports.
SPP socket failures and GATT callbacks determine loss of their owned connections.
Generic ACL and HEADSET-profile disconnect broadcasts do not close an SPP/BLE
source: a delayed broadcast from a finished probe can refer to another connection
on the same headset. Bluetooth adapter shutdown still resets hardware input and
stops setup immediately. Diagnostics record which disconnect broadcasts wait for
the selected source's own callback.
Repeated foreground-service start requests preserve the current notification
state instead of leaving a temporary Connecting status on an established session.

Diagnostics retain at most 100 metadata entries: source attempts, candidate rule
kinds, setup steps, event sizes/key codes, and reset/failure reasons. They contain
no raw learning payloads or device addresses. Raw observations remain in memory
and are discarded on completion/cancellation. The existing diagnostic upload
format is unchanged.

## Setup commands

The library exposes semantic `SetupCommand` values. The app maps them to one
English imperative and a local monochrome Lucide icon. Step and attempt counts,
errors, and the simulated transmission indicator are separate from the command.

| Command | Lucide icon |
|---|---|
| Connect your headset. | Bluetooth Connected |
| Select your headset. | Headset |
| Wait. | Hourglass |
| Keep PTT released. | Pointer Off |
| Press and hold PTT. | Pointer |
| Release PTT. | Pointer Off |
| Press PTT. / Press PTT again. | Mouse Pointer Click |
| Press other buttons. | Mouse Pointer Click |
| Choose a PTT mode. | List Checks |
| Check the indicator. | Circle Check |
| Continue | Arrow Right |
| Retry | Rotate CCW |

The test says **No audio will be transmitted.** Hold uses hold/release prompts;
Toggle uses press/press-again prompts. A complete on/off cycle leads to **Check the
indicator.** Save and Cancel are app UI actions, not `SetupCommand` values. Save
uses the Check icon and requires confirmation. Cancel uses the X icon. Cancel and
Android Back stay available while Save waits for the end of incoming speech.

Physical headset checks, including BM008 setup without protocol hints, are in
[`MANUAL_TESTING.md`](MANUAL_TESTING.md#headset-discovery-and-ptt).
