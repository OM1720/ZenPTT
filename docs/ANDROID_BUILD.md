# Android build

Status: current

Purpose: build, install, version, and package the current Android client.

Audience: Android developers, testers, and release owners.

Authority: this document owns the supported Android build, signing, versioning,
installation, and APK publication procedure.

Code anchors: `android/app/build.gradle.kts`, `android/signing.properties.example`,
Gradle wrapper files, and `scripts/publish-apk.ps1`.

Update when: SDK/toolchain requirements, variants, permissions, signing, versioning,
publication, or installation changes.

## Requirements

- JDK 17; the Android Studio bundled JBR is supported;
- Android SDK Platform 35 and Build Tools 35.0.0;
- NDK 27.0.12077973 and CMake 3.22.1 for the pinned libopus JNI build;
- `ANDROID_HOME` pointing to the SDK for command-line builds;
- a first-line `ZENPTT_DOMAIN` value in local `server.env`, copied from
  `server.env.example` and set to the operator's DNS name.

The project compiles and targets API 35 and supports Android 12/API 31 and
newer.

## Android Studio

1. Open the `android` directory as the project.
2. Select JDK 17.
3. Install the requested SDK components.
4. Run the `app` debug configuration or `:app:assembleDebug`.

The APK is written to
`android/app/build/outputs/apk/debug/app-debug.apk`.

## Command line

Windows:

```console
cd android
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleAndroidTest
```

Linux or macOS:

```console
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleAndroidTest
```

Install with Android platform tools:

```console
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Connected instrumentation requires an API 31+ emulator or phone:

```console
.\gradlew.bat :app:connectedDebugAndroidTest
```

See [`TESTING.md`](TESTING.md) for topology and server-address overrides.

## Build variants and network policy

The main manifest disables cleartext traffic. `src/debug/AndroidManifest.xml`
enables it only for debug builds so trusted local testing may use `ws://`.
Public clients use `wss://`.

`android/app/build.gradle.kts` reads the first `ZENPTT_DOMAIN` line from the
ignored `server.env` and generates the default address as `wss://<domain>`.
The domain must contain only letters, digits, dots, and hyphens. Rebuild the APK
after changing it. New installations leave the two optional historical Android
addresses in that file empty. Operators updating older clients can set
`ZENPTT_ANDROID_LEGACY_SERVER_ADDRESS` and
`ZENPTT_ANDROID_PRE_SNAPSHOT_SERVER_ADDRESS` to their previous defaults so
stored defaults migrate without replacing custom addresses.

## Permissions and foreground service

The manifest declares Internet, wake lock, package installation, microphone,
Bluetooth connection, notifications, and the required foreground-service types.
At session start the activity requests microphone, Nearby devices, and Android
13+ notification access. The session does not start when a required runtime
permission is denied.

The special package-install permission is requested only through Android system
settings when the user chooses to install an update. The application does not
scan or pair Bluetooth devices.

## Versioning and APK updates

`versionCode` and `versionName` live in `android/app/build.gradle.kts`.
Increment `versionCode` for every distinct APK. Once published, a version code is
immutable and cannot be reused for different bytes. Keep `versionName` within
the server protocol's release metadata format.

The instrumentation APK has its own `versionCode` in
`android/app/src/androidTest/AndroidManifest.xml`; the full gate requires it to
differ from the application code.

From the repository root, publish the assembled APK and generated metadata with:

```console
.\scripts\publish-apk.ps1
```

Use `-SkipBuild` only when `app-debug.apk` already matches the intended source
and version. The update client validates metadata, maximum size, final size,
and SHA-256 before Android verifies package signature and version.

## Signing and reproducibility

- Gradle Wrapper fixes the Gradle version.
- Plugin and library versions are fixed in Gradle files.
- Without `android/signing.properties`, a debug build uses the local Android SDK
  debug key. CI does not need a private key.
- For updates, copy `android/signing.properties.example` to the ignored
  `android/signing.properties` and set `storeFile`, `storePassword`, `keyAlias`,
  and `keyPassword`. The key file stays local; `storeFile` is relative to the
  `android` directory or an absolute path.
- A new operator must generate a new keystore and retain it for every update.
  For example, use `keytool -genkeypair -keystore <path> -alias <alias>
  -keyalg RSA -keysize 3072 -validity 10000`, then enter the chosen passwords
  in the local properties file.
- The APK publication script requires explicit signing settings so a missing
  local file cannot silently change an operator's update identity.
- Build directories, caches, downloaded updates, and APK outputs are not committed.

Never delete, replace, or regenerate a keystore used for installed clients.
Android rejects an update signed with a different key; affected devices would
have to uninstall the existing client before installing the replacement, losing
the application's private local data. Before publishing, compare the APK signer
shown by `apksigner verify --print-certs` with the operator's local key record.

The hosting builder copies the already signed APK. Signing keys are never
installed on the server.
