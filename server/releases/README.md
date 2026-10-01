# APK release directory

Status: current

Purpose: identify the generated files served by the ZenPTT server.

Audience: developers and release owners.

Authority: this file owns the contract of the generated release directory; publication
scripts and server validation remain authoritative for mechanics.

Code anchors: `scripts/publish-apk.ps1` and `server/app/releases.py`.

Update when: generated filenames, immutability, metadata, retention, or download routes
change.

Configure the ignored `android/signing.properties` with the operator's key, then
run `scripts/publish-apk.ps1` from the repository root. It copies a newly
assembled debug APK to `zenptt-<version_code>.apk` and updates `release.json`
with the current version, size, and SHA-256 metadata.

A published versioned APK is immutable. Publishing identical bytes with the
same `versionCode` reuses the file; different bytes are rejected and require a
higher `versionCode`. Old versioned APKs are retained until an administrator
explicitly removes them.

Generated release files are ignored by Git and mounted read-only at
`/data/releases` by Compose. FastAPI exposes the current release through
`/app/latest` and `/app/download`, and every retained APK through
`/app/releases/<version_code>/download`. Do not edit release metadata by hand.
