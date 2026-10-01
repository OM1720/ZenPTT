# ZenPTT Concentric Halo UI

Status: current and normative

Purpose: lock the visual structure and interaction rules of the two-screen Android UI.

Audience: Android developers, designers, testers, and AI coding assistants.

Authority: this document owns the current Android layout, visual state grammar,
interaction, and accessibility contract.

Code anchors: `ZenPttUi.kt`, `MainScreen.kt`, `SettingsScreen.kt`, `MainActivity.kt`,
and `UserIndicators.kt` under `android/app/src/main/java/app/zenptt`.

Update when: screen structure, controls, state presentation, accessibility behavior,
icons, or visual constraints change.

This document and the implemented Compose UI are the current visual reference.

## Main screen

The layout and Halo grammar below are the visual baseline. Channel examples use `ROOM1`; channel codes remain uppercase and contain `A-Z`/`0-9` segments separated by single periods.

The screen contains only:

- the adaptive Connect/Disconnect control at top left;
- the centered `ZenPTT` title matching the browser client's 24 px medium-weight wordmark;
- Settings at top right;
- one unlabeled frequency field with a live connection count at its right;
- the press-and-hold Concentric Halo PTT control.

The connection control is one 48 dp action containing a 30 dp Lucide Circle
Power icon. It mirrors the 48 dp Settings action and its 30 dp icon at the
opposite edge of the top bar. Offline and connection-error states show the black
outline icon rotated 180 degrees. Connecting and reconnecting switch that same
icon fully on/off every 600 ms. A stable active session shows the original
orientation as a solid black outer circle with a white Power mark. Its TalkBack
action remains Connect, Disconnect, or Reconnect as appropriate.

A saved valid frequency connects automatically once the foreground service binds. Explicit Disconnect suppresses that startup action until Connect is tapped or a new valid frequency is submitted. `Done` validates, uppercases, saves, and switches frequency exactly once. Entering `ECHO` uses the normal channel input to start the private ECHO flow.

The frequency row keeps the outlined field at no more than 520 dp, followed by a 12 dp gap and a stable 32 dp count column. The count includes the current WebSocket session and belongs only to the applied, connected frequency. It is `1` for connected private `ECHO` and `—` while offline, connecting, reconnecting, or editing a different frequency draft. Only protocol v4 servers are supported. TalkBack announces it as the active connection count.

The frequency history is one white outlined panel anchored to the measured field width. It uses the field's black 1 dp border and corner shape, zero elevation, bodyLarge black text, no dividers, 16 dp horizontal item padding, and 56 dp item height.

An invalid keyboard Done keeps the field focused, shows its inline error, and
opens the history. The user may dismiss that history without it reopening until
another validation error occurs. Selecting a valid history item clears the
error through the normal submit path.

### Halo grammar

| Geometry | Meaning |
|---|---|
| Microphone without a ring | Offline |
| Circle Alert without a ring | Connection error |
| Static ring | Connected and stable |
| Rotating ring, once per six seconds | Connecting, reconnecting, requesting, or releasing |
| Solid ring | Good active-path audio quality |
| Sixteen sectors with 25% white gaps | Fair active-path audio quality |
| Sixteen sectors with 50% white gaps | Poor active-path audio quality |
| Black core with white microphone and a 24 dp white ring gap | Transmitting |
| Volume icon on white | Receiving or ECHO playback |
| Microphone Off with the quality ring retained | Busy or PTT error |

If retained incoming audio continues to play while the connection is
reconnecting, the ring keeps its rotating reconnect geometry while the center
keeps the Volume icon. The center returns to Microphone only after playback
fully drains, or the rotating ring becomes the normal static receiving ring
after a successful resume.

The ring uses sixteen fixed 22.5-degree sectors. A rotating good-quality ring temporarily uses 10% white gaps so its motion remains visible; it becomes solid again when the transition finishes. Rounded stroke caps are optically compensated so the visible gaps remain 10%, 25%, or 50% of each sector.

Outgoing quality reflects the age of the oldest unacknowledged frame as a share
of the advertised recovery horizon: below 10% is good, 10-20% is fair, and 20%
or more is poor. It never changes the fixed 16 kbit/s codec. Incoming quality is
good without loss, fair for one to three PLC frames, and poor for a longer gap
or blocked playback. The playout target remains fixed at 100 ms. Ready retains
the most recently active audio path. RTT, PTT grant delay, and exact gaps remain
available to diagnostics and TalkBack but do not change Halo geometry.

Ordinary touch remains press-and-hold. With TalkBack, the PTT accessibility action toggles transmission: the first double-tap engages PTT and the next double-tap releases it. It is available during the interruptible `Draining` tail of ordinary or ECHO playback, but unavailable during a newly active ECHO burst or while releasing. The accessibility latch is released on Settings navigation, Activity background/recreation, connection loss, or service stop without releasing an independently held BM008 button. TalkBack announces queued/rejected PTT instructions, Offline correctly, and exact RTT/PTT/gap values, using unknown before measurement.

## Settings screen

Settings uses one headset card directly below the server row and one
shared diagnostics/debug row.

Top controls:

- Reset at left restores the draft server address and 10-minute power-save timeout
  and restores the disabled factory headset configuration. It
  never changes frequency or the active connection. Undo restores every changed
  setting.
- Home at right validates, saves, and returns in one action. Android Back behaves identically.

Rows are direct actions without nested menus:

- editable server address with Ping;
- headset: Lucide Headset + **Headset on/off** + monochrome Material 3 Switch;
  Lucide Settings 2 + **Set up headset** in the same card; off by default;
  the switch controls PTT and audio, with **Wait.** / Hourglass until a phrase ends;
- editable power-save timeout;
- diagnostics at left and share debug info at right in one row;
- battery optimization settings;
- check/install update.

Settings remain a rotation-safe draft until Home or Back. Invalid values stay inline under the owning field with an outline warning, TalkBack error semantics, and focused input. Network and service failures use a snackbar and the Halo error geometry. PTT queue state uses an indefinite Snackbar until grant or cancellation; PTT rejection uses a long Snackbar with a reason and an explicit release-and-press-again instruction. Neither adds Halo geometry. A power-save-only change applies live. A saved server change reconnects the active frequency once. Editing, resetting, or undoing the draft server cancels stale Ping/update results without applying the draft.

The left side of the shared support row stays dynamic: `Send debug info` -> `Sending diagnostics...` -> `Report sent + code + Copy`; failures become Retry, and Copy confirms with a brief snackbar. Its title aligns vertically with Share debug info. After an upload completes, tapping the left side again sends a fresh report and replaces the previous code; parallel uploads remain disabled. On narrow screens or with large text, the code and Copy control stack inside the left half. The right side opens a newly generated Share debug info intent on every tap. There is no standalone Copy row. Updates likewise stay in one row through check, available, install, and download states. During download the row itself fills black from left to right, with the icon and text turning white exactly across the filled area; it has no separate progress bar and cannot be tapped. A verified download returns the row to white and opens Android's installer immediately. Returning after cancelling the installer lets Install reopen the verified APK without downloading it again. There is no Test echo action.

The headset wizard temporarily replaces Settings content. A fixed header keeps
the step number and **Cancel** visible above the scrolling instruction and
controls. Android Back cancels. Device selection precedes sequential connection
checks, quiet sampling, three guided holds and two independent verification
cycles, an other-buttons check, behavior selection, and the simulated test.
Each step uses one short imperative and its approved Lucide icon; see `HEADSET_PTT.md`.
Device selection shows confirmed connections only. Use **Continue**, not Next.
All prompts and accessibility announcements are English. Changing instructions
and the simulated indicator use polite live regions. Save requires a complete
on/off cycle, an off indicator, and explicit confirmation that it matches the
physical button. Successful Save replaces the accepted setup and enables the
switch. Cancellation, failure, recreation, or backgrounding preserves the previous
setup and switch position. Reset/Undo includes the entire configuration. No model
name, setup subtitle, or device history is displayed in the settings card.

## Visual constraints

- fixed light theme with white system bars and dark system icons;
- safe-drawing insets on every screen;
- portrait and landscape layouts; landscape Main places frequency left and Halo right, while landscape Settings pairs compact rows;
- font scale 1.3 and above keeps the full-width Settings rows readable;
- white surfaces and black content only, with disabled/placeholder alpha as the sole gray;
- switch tracks are white when off and black when on;
- no state colors, gradients, glow, shadows, or bottom navigation;
- local 24×24 outline vector icons with consistent rounded strokes;
- UI actions use local Lucide vectors with 2 dp rounded strokes; custom artwork is limited to the Halo and Yin-Yang brand mark;
- adaptive, round, themed, and foreground-notification icons use the Yin-Yang brand mark;
- icon touch targets are at least 48 dp;
- the expanded foreground notification exposes PTT (open the current session) and Disconnect actions; the PTT state machine, WebSocket protocol, and server API remain unchanged.
