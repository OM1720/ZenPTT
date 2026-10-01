# ZenPTT user guide

Status: current

Purpose: explain how to install, connect, communicate, update, and obtain
support information from the Android application and browser client.

Audience: ZenPTT users and support operators.

Authority: this document owns user procedures and user-facing explanations, not
technical protocol or implementation detail.

Code anchors: the Android Main/Settings UI, `PttForegroundService`, and the public
server routes exposed through Caddy.

Update when: installation, permissions, navigation, user-visible PTT behavior,
settings, updates, diagnostics actions, or common recovery steps change.

## Before starting

Open the server's home page directly, or open `/web/` on the same server. Public
use requires HTTPS. Installation is optional; the browser client works from a normal
browser tab and can also be installed as a standalone web app:

- on Android, open the site in Chrome and use **Install app** or **Add to Home screen**
  from the browser menu;
- on iPhone, open the site in Safari, select **Share**, then **Add to Home Screen**;
  keep **Open as Web App** enabled when Safari offers it;
- on Windows, use Chrome's install icon or **Install ZenPTT** menu action. The installed
  app opens in its own window and can be launched from Start or pinned to the taskbar.

The bottom-right download icon offers the native Android app; hover over it to see
the hosted version. Enter a channel and select the power icon (**Connect**); allow
microphone access when the browser asks. Incoming speech can play as soon as connection
and audio preparation finish, without an initial PTT press.

Hold the Halo, or hold Space on a physical keyboard, to talk and release to listen.
Space typed in the channel field does not transmit. Switching tabs or windows and
hiding an installed web app release PTT; returning does not restart it. Connect to
`ECHO` to hear your own phrase repeated after release.

If audio preparation fails, correct the displayed permission/device issue and
select **Retry audio**. In the browser's site settings, allow microphone access if it
was blocked. Settings lets you choose the microphone, check Ping, or download a local
diagnostic report. Playback uses the system default output. Navigating between Main
and Settings keeps the session and incoming audio active. Select **Disconnect** to
leave and release the microphone. History and microphone choice persist locally;
after a reload, select Connect again.

Installation does not add background execution, Bluetooth PTT, notifications,
screen wake, or offline operation. While the page is active, ZenPTT retries a lost
connection for a limited time. After that limit, select Connect again. A closed
or suspended browser may not recover in the background. Mobile microphone,
speaker, and lifecycle behavior depend on the browser and device. Android Chrome
and iPhone Safari installation still require physical acceptance on the target devices.

The remaining sections describe the Android application.

ZenPTT requires Android 12/API 31 or newer. Obtain the APK from the server root
page or from the local build described in [`ANDROID_BUILD.md`](ANDROID_BUILD.md).
Android may warn that the APK is installed outside Google Play; confirm only
when the APK came from the intended ZenPTT server or build.

For UNIWA BM008 use, pair the headset in Android Bluetooth settings before
starting a ZenPTT session. The application connects to an already bonded
device and does not scan or perform pairing.

## Permissions

Starting a session requires:

- microphone access;
- Nearby devices/Bluetooth connection access;
- notification access on Android 13 and newer.

These permissions allow the foreground service to record granted PTT audio,
connect to the paired BM008, and show the active-session notification. If a
required permission is denied, the session does not start.

Installing an update requires Android's separate **Install unknown apps**
permission for ZenPTT. The application opens the appropriate system settings
when that permission is missing.

## Server address

Use one of these forms:

- local trusted network: `ws://<server-LAN-IP>:8080`;
- public deployment: `wss://<DNS-name>`;
- Android Emulator with local Compose: `ws://10.0.2.2:8080`.

The address must contain only the `ws` or `wss` scheme, host, and optional port.
Paths, query strings, fragments, and embedded credentials are rejected.

The APK is built with a default public `wss://` address derived from the first
line of the local `server.env`. A previously saved default is replaced by the
build default when its historical address is configured by the build operator.

Open **Settings** and use the Ping icon before applying a new address. A
successful check calls the server `/health` endpoint without opening a channel
session.

## Main and settings screens

The main screen intentionally contains only the Connect/Disconnect icon,
Settings icon, frequency field, and press-and-hold Concentric Halo PTT control.
The frequency field opens up to five choices on focus: four recent ordinary
frequencies with the newest first, followed by fixed `ECHO`.
Selecting an item applies it immediately. Manual input is normalized, saved,
and applied by keyboard **Done**; unchanged input does not reconnect. Holding PTT
also applies the visible frequency: ZenPTT switches rooms when needed and sends
the PTT request only after the new connection joins. Releasing before that point
cancels the pending transmission.

Frequency codes are converted to uppercase and contain `A-Z`/`0-9` segments
separated by single periods, for example `ROOM.1` or `446.00625`. A period
cannot be first, last, or repeated. If keyboard **Done** reports an invalid
frequency, the history remains available over the inline error.
A clean installation selects and connects `ECHO` after permissions are granted.
Later launches restore and connect the last selected frequency.

Settings contains the server, Ping, **Headset on/off** and **Set up headset**, power-save timeout,
combined diagnostics/debug sharing, battery optimization, and update controls.
Home and Android Back validate, save, and return to the main screen. Reset
changes the settings draft to the build server, selects the 10-minute timeout,
and restores the disabled factory headset configuration; Undo is
available, and the frequency never changes.

The power-save timeout accepts 1 through 1440 minutes. It controls when the
Bluetooth communication audio route may sleep; it does not disconnect the
WebSocket or BM008 SPP control connection.

## Echo test

1. Open Settings, confirm the server address, and use Ping if needed.
2. Return to the main screen.
3. Select `ECHO`.
4. Grant the required permissions and wait for the static Ready halo.
5. Hold the on-screen or BM008 PTT button and wait for the grant tone.
6. Speak after the tone, then release PTT.
7. Confirm the speaker playback icon, audible playback, and a return to Ready.

ECHO is a private resumable channel and becomes Ready only when its participant
count is two. While its bot is reconnecting the status is Connecting and PTT is
rejected. The bot first receives the complete phrase and then responds through
the same v4 media path as any other headless participant. Audio remains
memory-only and is not durable across a server process restart.

An awake route emits one grant tone. The first PTT that wakes a route after the
idle timeout emits two tones; speak after the second tone. Incoming audio may
also wake the route but does not emit a tone.

## Sound indicators

Service indicators are always enabled in ZenPTT and follow Android communication
volume and Do Not Disturb behavior:

- a short dry tone means the channel has become free; listeners hear it only
  after the last queued syllable, and the sender hears it after server confirmation;
- a quiet wooden tap repeated every 800 ms means a held PTT is waiting for the
  connection; keep holding and wait for the grant tone, for at most five seconds;
- two low tones mean the PTT attempt was rejected, including pressing while
  receiving or failure before transmission starts;
- a descending tone means an active transmission was finally interrupted after
  recovery failed, the 60-second limit was reached, or capture/transport failed.

The interruption tone replaces the channel-free tone for the affected sender.
If Bluetooth disappears during that failure, it plays through the phone
speaker. Connection loss, reconnect, recovery, initial connect, and explicit
Disconnect make no sound. Grant tones and loss cues retain their separate
meanings. A queued PTT shows `PTT queued — keep holding and wait for the grant
tone.` If no connection is available at five seconds, two low tones and a
message require releasing and pressing PTT again.

PTT can discard speech that the server has already finished sending but the
phone is still draining locally. The new request is sent immediately; speak only
after the grant tone. PTT cannot interrupt a newly active incoming burst.

## Normal channel

1. Configure the same server address on all participating phones.
2. Enter the same frequency code and press keyboard **Done** on each phone.
3. Wait for the static Ready ring or the Receiving speaker icon.
4. Hold PTT, confirm the black transmitting core, and speak after the grant tone.
5. Release PTT when finished.

Only one participant may transmit. If two clients request PTT together, one is
granted and the other shows **Channel busy**. There is no queue; the denied
user must release and press again after the channel becomes free.

Listeners automatically enter **Receiving** and play the transmitted Opus
audio. The status returns to **Ready** only after queued playback has drained.
A transmission ends on release, explicit Disconnect, or a server timeout. After
an unintentional connection loss, the client replays the saved end control on
resume; otherwise the current floor lease expires before another speaker can
transmit.

## Set up a headset

1. Connect the headset in Android Bluetooth settings, then open Settings and
   **Set up headset**. Select a connected device. If the list is empty,
   connect the headset in Android settings. Nearby and disconnected devices are hidden.
2. Follow the current instruction: keep the button released, hold until prompted,
   then release. The app checks available methods in order: **SPP**, **BLE**, then
   **Media**. A selected external input uses **HID**. The current short name appears
   next to **Connection**. Each
   method uses three training cycles and two independent verification cycles.
   The first successful method stops the search; remaining methods are skipped.
3. If the headset has other buttons, test them during the negative check. They
   must not activate the learned rule. Choose **Hold to talk** when available or
   **Press to toggle**. Toggle starts and stops transmission on successive presses.
4. Complete the simulated on/off test, confirm that its indicator matches your
   actions, and tap **Save**. No audio is recorded or transmitted during setup.
5. Select **ECHO** yourself and test real audio with the screen on and locked,
   including after another media app has played audio.

**Cancel**, Android Back, backgrounding, or rotation discards the draft and
restores the last accepted setup and switch position. Failed learning or saving
also preserves them. Setup is unavailable while PTT is active or pending.

**Headset on/off** enables the last saved setup and Bluetooth communication audio.
Off retains the setup and uses the phone microphone and loudspeaker, including tones.
The choice is remembered. A change during speech waits until the phrase ends;
**Wait.** indicates a pending change. On falls back to the phone if Bluetooth is
unavailable and returns to Bluetooth between phrases when it reconnects.
**Reset settings** restores the disabled factory configuration; **Undo** restores
the entire previous configuration. Only one setup is stored. Global media rules
can respond to another device using the same key. Ordinary HID keys may work
only with the app on screen. Unsupported proprietary protocols may not be learned.
See [supported rules and physical checks](HEADSET_PTT.md).

## UNIWA BM008

Hardware PTT is off by default with a complete BM008 factory configuration
saved. Enable **Headset on/off** to use it. BM008 can also be configured as a
new device using **Set up headset**. Disabling the switch stops control
connections and releases the associated media-button session.

When an active session finds the paired headset, the hardware status changes to
**Ready**. BM008 ASCII commands received over Bluetooth SPP are the
authoritative hardware PTT source.

- press and hold the hardware button to request PTT;
- speak only after the grant tone;
- release the hardware button to release PTT;
- powering off or disconnecting the headset while held synthesizes a release.

Observed Android media keys are consumed and included in diagnostics but do
not start or stop PTT. This prevents device-specific key ordering from leaving
PTT stuck.

If BM008 remains disconnected, verify that it is paired, powered on, not owned
by another application, and that Nearby devices permission is granted.

## Background operation

An active normal or ECHO session runs in `PttForegroundService`. The ongoing
notification shows the channel and connection status. Its expanded actions include
**PTT**, which opens the current session for the normal press-and-hold control, and
**Disconnect**, which stops the session. Tapping the notification body also opens
the session.

Locking the screen, switching applications, or removing ZenPTT from Recents
does not intentionally disconnect the session. A bounded partial wake lock is
held only while PTT is pressed. Force stop, Android's Active apps stop control,
or a phone reboot ends the session and does not restart it automatically.

After a network loss the client retries indefinitely: five fast attempts are
followed by attempts approximately every 5 seconds. While PTT remains held, it
continues recording for the server-advertised recovery horizon (normally five
seconds). If reconnect confirms the old floor, that burst continues; otherwise
the retained old tail is uploaded before a new floor is requested. A single
transmission is always limited to 60 seconds, including offline time. When the
recovery horizon or that duration limit expires, recording stops, the
interruption indicator sounds, and the user must release and press PTT again.
Accepted audio remains in server RAM for at least 15 seconds and longer when a
larger horizon requires it. If fast recovery data repeatedly exceeds the receive
FIFO at the same playback position, ZenPTT stops automatic reconnection and asks
for a manual reconnect instead of discarding speech. Tap Disconnect
in the expanded notification or the top-left Disconnect icon to stop reconnecting
and release all network, Bluetooth, audio, wake-lock, and notification resources.

If the device vendor suspends networking while the screen is off, use the
**Battery optimization** row in Settings and allow unrestricted battery use for
ZenPTT.

## Updates

Use **Check for updates** in Settings. When a release with a higher `versionCode`
is available, the same row changes to **Install**.

The client downloads at most the size declared by the server, verifies the
complete file size and SHA-256 digest, then opens Android's package installer.
Android performs its normal package signature and version checks. ZenPTT does
not silently install an APK.

## Diagnostics and support

- **Share debug info** creates a local redacted text report for another app.
- **Send debug info** uploads a structured report and displays its short code
  with an inline Copy icon in the same row.

Reports contain bounded connection, PTT, queue, Bluetooth, and audio-route evidence.
They exclude audio payloads and clear normal-channel codes. The exact report contents,
retention, retrieval, privacy rules, and triage procedure are owned by
[`SUPPORT.md`](SUPPORT.md).

## Common failures

| Symptom | Action |
|---|---|
| **Server unavailable** | Verify the address, Caddy health, phone network, DNS, and TLS certificate. |
| **Connection error** | Use Share debug info, then reconnect. Invalid server messages and server control errors are not silently ignored. |
| **PTT request timed out** | Release PTT, wait for reconnect/readiness, and press again. Capture and the grant tone do not start; the always-on PTT-rejected indicator sounds. |
| **Channel busy** | Release the button and retry after the channel-free indicator or active transmission end. |
| No BM008 connection | Check pairing, power, Nearby devices permission, and competing Bluetooth applications. |
| No Bluetooth audio | Use Share debug info and inspect selected/routed devices; SPP connection alone does not prove that the communication audio route is ready. |
| Screen-off disconnect | Allow unrestricted battery use and repeat the locked-screen check in `MANUAL_TESTING.md`. |
| Update cannot open | Allow ZenPTT under Android **Install unknown apps**, then tap Install again. |
