# ZenPTT

ZenPTT is an Android Push-to-Talk application with half-duplex channels,
personal headless ECHO tests, learned headset hardware PTT, Bluetooth communication audio,
background operation, automatic reconnect, support diagnostics, and
server-hosted APK updates.

The Python headless runtime provides the required multi-session Echo supervisor.
A separate QRZ developer example demonstrates the public bot API without entering
the production image or server delivery. Neither requires a physical audio device;
see [`docs/HEADLESS_CLIENT.md`](docs/HEADLESS_CLIENT.md) and
[`docs/BOT_DEVELOPMENT.md`](docs/BOT_DEVELOPMENT.md).

A browser client at `/` (also `/web/`) supports the Halo interface, local settings, live audio,
push-to-talk, and bounded connection recovery in Chrome on Windows. See
[`docs/WEB_CLIENT.md`](docs/WEB_CLIENT.md) for isolated local startup and build checks.

The Android client connects through one WebSocket. A single-process FastAPI
server arbitrates the right to transmit and forwards Opus frames. Caddy is the
only externally exposed service and provides the local HTTP entry point or
public TLS termination.

## Requirements

- Docker Engine with Docker Compose for the server;
- Android 12/API 31 or newer for the client;
- Android Studio or JDK 17 plus Android SDK 35 to build the APK;
- a paired headset when testing hardware PTT and Bluetooth audio; BM008 uses SPP,
  while the common wizard checks media/HID/SPP/BLE rules; see
  [`docs/HEADSET_PTT.md`](docs/HEADSET_PTT.md).

## Start the local server

From the repository root:

```console
docker compose --env-file server.local.env up -d --build
curl http://127.0.0.1:8080/health
```

The expected response is `{"status":"ok"}`. FastAPI port 8000 remains private
inside the Docker network. A debug APK on the same LAN connects to
`ws://<computer-LAN-IP>:8080`; an Android Emulator uses
`ws://10.0.2.2:8080`.

Stop the stack with:

```console
docker compose --env-file server.local.env down
```

## Build and install Android

Copy `server.env.example` to `server.env` and replace the example DNS name with
your own before building. The latter is local and is not committed. For a normal
debug build, Android uses the developer machine's debug key automatically. See
[`docs/ANDROID_BUILD.md`](docs/ANDROID_BUILD.md) for signing and updates.

On Windows:

```console
cd android
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

On Linux or macOS use `./gradlew` and `/` path separators. See
[`docs/ANDROID_BUILD.md`](docs/ANDROID_BUILD.md) for SDK, signing, versioning,
and build-variant details.

## Publish the APK locally

Before publishing, copy `android/signing.properties.example` to the ignored
`android/signing.properties` and configure a signing key. The publisher requires
this file even when a normal debug build uses the local Android debug key. See
[`docs/ANDROID_BUILD.md`](docs/ANDROID_BUILD.md#signing-and-reproducibility).

With the local stack running on Windows:

```console
.\scripts\publish-apk.ps1
curl http://127.0.0.1:8080/app/latest
```

The server root page exposes `/app/download` through an accessible icon-only
Lucide download control. Installed clients can use
**Check for updates** and **Install**. Downloads use a versioned URL, resume
after temporary network failures, show progress with ETA, and open only after
their size and SHA-256 match the published metadata.

## Use the application

Follow [`docs/USER_GUIDE.md`](docs/USER_GUIDE.md) for permissions, connection,
ECHO, normal channels, BM008, power saving, background behavior, updates, and
diagnostics.

## Run the development gate

Create `.venv`, install both `server[dev]` and `headless[dev]`, and install FFmpeg
on PATH for offline audio tests. The full gate also needs the Android SDK/NDK
components listed in [`docs/TESTING.md`](docs/TESTING.md). Run on Windows:

```console
.\scripts\test-full.ps1
```

Use `-WithInstrumentation` with a connected API 31+ emulator or phone and
`-WithLiveStack` after publishing an APK and starting Compose. The individual
commands and expected evidence are in [`docs/TESTING.md`](docs/TESTING.md).
Physical checks are in [`docs/MANUAL_TESTING.md`](docs/MANUAL_TESTING.md).

## Deploy the public server

Set the real DNS name in the first line of `server.env`, increment Android
`versionCode`, and build the four-file delivery package:

```console
.\scripts\build-hosting-package.ps1
```

Deploy or update Ubuntu 24.04 with `sudo bash install-update.sh`. See
[`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) for DNS, firewall, validation,
rollback, and operation.

## Documentation

[`docs/README.md`](docs/README.md) routes each task to its authoritative document.
Start with [`docs/PRODUCT.md`](docs/PRODUCT.md) for product requirements or
[`ARCHITECTURE.md`](ARCHITECTURE.md) for implementation boundaries.

## License and security

ZenPTT source is licensed under the [MIT License](LICENSE). Bundled third-party
materials retain their own license notices in the Android and web clients.
External issues and pull requests are not currently reviewed. Report security
vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## Source distribution

This GitHub repository distributes source code only. Android APK downloads and
in-app updates use the server-hosted path described above. GitHub Releases
provide source archives without attached APKs.

Source release tags use `vX.Y.Z` and match the Android `versionName` in
`android/app/build.gradle.kts` at that snapshot. Increment Android `versionCode`
for every distinct APK, including a rebuild that changes its bytes. The server,
headless, and web package versions are independent of the source release tag.

## Repository layout

- `android/` — Kotlin and Jetpack Compose client;
- `server/` — FastAPI server and its test suite;
- `headless/` — reusable Python protocol client and production Echo runtime;
- `examples/qrz_bot/` — runnable, tested QRZ developer example excluded from server delivery;
- `scripts/` — bounded build, test, deployment, update, and support commands;
- `docs/` — current product, developer, operator, and support documentation;
- `compose.yaml` and `Caddyfile` — local and public container runtime;
- `server.env.example` and `server.local.env` — public deployment template and local runtime configuration;
- ignored `server.env` and `android/signing.properties` — operator-specific deployment and signing settings.
