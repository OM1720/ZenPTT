# ZenPTT documentation

Status: current

Purpose: route readers to the authoritative document for each part of ZenPTT.

Audience: users, developers, operators, testers, and AI coding assistants.

Authority: this index defines documentation ownership and routing, not product or
implementation behavior.

Code anchors: `README.md`, `ARCHITECTURE.md`, `AGENTS.md`, and the subsystem paths
listed in the change-impact table below.

Update when: a maintained document is added, removed, renamed, or changes authority.

## Choose by task

| Goal | Start here | Continue with |
|---|---|---|
| Understand the product | [`PRODUCT.md`](PRODUCT.md) | [`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md), [`USER_GUIDE.md`](USER_GUIDE.md) |
| Change system ownership or state | [`ARCHITECTURE.md`](../ARCHITECTURE.md) | The affected normative subsystem document |
| Change client/server messages or HTTP routes | [`protocol.md`](protocol.md) | [`RELIABILITY_MATRIX.md`](RELIABILITY_MATRIX.md) |
| Change Android UI | [`UI_DESIGN.md`](UI_DESIGN.md) | [`USER_GUIDE.md`](USER_GUIDE.md) |
| Change audio | [`BLUETOOTH_PTT_AUDIO.md`](BLUETOOTH_PTT_AUDIO.md) | [`MANUAL_TESTING.md`](MANUAL_TESTING.md) |
| Change headset discovery or learning | [`HEADSET_PTT.md`](HEADSET_PTT.md) | [`MANUAL_TESTING.md`](MANUAL_TESTING.md) |
| Build or release Android | [`ANDROID_BUILD.md`](ANDROID_BUILD.md) | [`TESTING.md`](TESTING.md) |
| Configure or deploy the server | [`CONFIGURATION.md`](CONFIGURATION.md) | [`DEPLOYMENT.md`](DEPLOYMENT.md) |
| Develop a bot | [`BOT_DEVELOPMENT.md`](BOT_DEVELOPMENT.md) | [`HEADLESS_CLIENT.md`](HEADLESS_CLIENT.md), [`TESTING.md`](TESTING.md) |
| Develop the browser client | [`WEB_CLIENT.md`](WEB_CLIENT.md) | [`protocol.md`](protocol.md), [`TESTING.md`](TESTING.md) |
| Check browser audio on real devices | [`MANUAL_TESTING.md`](MANUAL_TESTING.md#browser-client) | [`WEB_CLIENT.md`](WEB_CLIENT.md) |
| Diagnose a problem | [`SUPPORT.md`](SUPPORT.md) | [`MANUAL_TESTING.md`](MANUAL_TESTING.md) |

## Document catalog

The catalog covers maintained text documentation, the interactive sequence
diagram, and packaged third-party icon notices. Generated build, release, and
support output is not part of the maintained documentation.

| Document | Purpose | Audience |
|---|---|---|
| [`README.md`](../README.md) | Quick start, common commands, and repository layout. | Users, developers, operators |
| [`docs/README.md`](README.md) | This catalog, document authority, and writing conventions. | All contributors and readers |
| [`LICENSE`](../LICENSE) | MIT terms for ZenPTT source. | Users, developers, distributors |
| [`SECURITY.md`](../SECURITY.md) | Private vulnerability reporting and supported-version policy. | Users, security researchers, maintainers |
| [`PRODUCT.md`](PRODUCT.md) | Supported product, user-visible outcomes, and intentional scope. | Product owners, developers, testers |
| [`ARCHITECTURE.md`](../ARCHITECTURE.md) | Component ownership, data flow, state, concurrency, and persistence. | Developers, architecture reviewers |
| [`WEB_CLIENT.md`](WEB_CLIENT.md) | Browser behavior, local development, and automated verification. | Developers, testers, operators |
| [`AGENTS.md`](../AGENTS.md) | Project rules, invariants, and verification requirements for coding assistants. | AI coding assistants and their operators |
| [`USER_GUIDE.md`](USER_GUIDE.md) | Browser and Android setup, PTT, BM008, updates, and common failures. | Users, support operators |
| [`UI_DESIGN.md`](UI_DESIGN.md) | Main/Settings layout, Halo states, accessibility, and visual rules. | Android developers, designers, testers |
| [`ANDROID_BUILD.md`](ANDROID_BUILD.md) | SDK setup, APK builds, signing identity, installation, and versioning. | Android developers, testers, release owners |
| [`protocol.md`](protocol.md) | Normative WebSocket v4 framing, sessions, audio recovery, and HTTP endpoints. | Client/server developers, protocol testers |
| [`client-server-client.html`](diagrams/client-server-client.html) | Interactive sequence of one successful Android-to-Android v4 exchange, with abbreviated fields. | Developers, reviewers, technical readers |
| [`HEADLESS_CLIENT.md`](HEADLESS_CLIENT.md) | Python API, handler lifecycle, PCM ownership, recovery, shutdown, and Echo runtime. | Bot developers, operators, testers |
| [`BOT_DEVELOPMENT.md`](BOT_DEVELOPMENT.md) | QRZ example, public bot API, prepared audio, custom handlers, and separate startup. | Bot developers, testers |
| [`CONFIGURATION.md`](CONFIGURATION.md) | Runtime/build settings, defaults, ceilings, and configuration ownership. | Developers, operators |
| [`HEADSET_PTT.md`](HEADSET_PTT.md) | Headset sources, saved configuration, guided learning, and runtime safety. | Android developers, testers |
| [`BLUETOOTH_PTT_AUDIO.md`](BLUETOOTH_PTT_AUDIO.md) | Hardware/audio separation, communication routing, tones, playback, and power-saving contract. | Android developers, audio testers, support engineers |
| [`TESTING.md`](TESTING.md) | Automated gates, prerequisites, fault coverage, and verification boundaries. | Developers, testers, release owners |
| [`RELIABILITY_MATRIX.md`](RELIABILITY_MATRIX.md) | Reliability risks mapped to executable and physical evidence. | Developers, testers, release owners |
| [`MANUAL_TESTING.md`](MANUAL_TESTING.md) | Physical Android, headset, browser, degraded-network, and deployment checks. | Testers, release owners, support engineers |
| [`SUPPORT.md`](SUPPORT.md) | Diagnostic contents, privacy, retention, collection commands, and triage. | Users, support engineers, operators |
| [`DEPLOYMENT.md`](DEPLOYMENT.md) | Four-file server delivery, Ubuntu installation, rollback, and operations. | Release owners, server operators |
| [`KNOWN_LIMITATIONS.md`](KNOWN_LIMITATIONS.md) | Intentional product, recovery, hardware, and bot limitations. | Users, product owners, developers, operators |
| [`server/releases/README.md`](../server/releases/README.md) | Generated APK metadata, immutable artifacts, and download routes. | Developers, release owners |
| [`examples/qrz_bot/assets/README.md`](../examples/qrz_bot/assets/README.md) | QRZ audio generation, pinned hash, PCM format, and offline preparation. | Bot developers, asset maintainers |
| [`THIRD_PARTY_NOTICES.txt`](../android/app/src/main/assets/THIRD_PARTY_NOTICES.txt) | Attribution and license texts for the bundled UI icons. | Distributors, release owners |

The sequence diagram's editable source is
[`client-server-client.sequence.json`](diagrams/client-server-client.sequence.json).
It illustrates a successful ordinary-channel exchange; keepalive, repeated media
and ACKs, channel-state updates, Caddy, recovery, ECHO, and headless behavior are
omitted. Its abbreviated labels are not complete wire schemas: use `protocol.md`.

## Authority rules

- `PRODUCT.md` owns intended product outcomes and scope. An implementation conflict
  is a defect to resolve explicitly, not a reason to copy the mismatch elsewhere.
- Source code and executable tests are authoritative for currently implemented
  mechanics and verification evidence.
- `protocol.md` is the normative network contract for compatible clients and
  servers.
- `CONFIGURATION.md` explains settings, while defaults and hard ceilings remain
  authoritative in `server/app/settings.py`, `server/app/security_constants.py`,
  `server.env.example`, and `server.local.env`.
- `BLUETOOTH_PTT_AUDIO.md` is the normative contract for BM008, audio routing,
  grant tones, buffering, and power saving.
- `UI_DESIGN.md` is the normative visual and interaction contract for the
  Android Main and Settings screens.
- `TESTING.md` describes automated evidence. `MANUAL_TESTING.md` describes
  device-dependent evidence that automation cannot provide. `RELIABILITY_MATRIX.md`
  maps important risks to both kinds of evidence.
- Product and runbook documents describe only the current supported system.

## Change impact map

| Changed area | Documentation to review |
|---|---|
| `server/app/protocol.py`, `server/app/session.py`, Android/headless protocol code | `protocol.md`, `ARCHITECTURE.md`, `RELIABILITY_MATRIX.md` |
| `server/app/domain.py`, reconnect, recovery, or ordered playback state | `ARCHITECTURE.md`, `protocol.md`, `KNOWN_LIMITATIONS.md` |
| `web/src`, `web/codec`, or browser hosting | `WEB_CLIENT.md`, `CONFIGURATION.md` for build settings, `MANUAL_TESTING.md` for physical behavior, `TESTING.md` for gates |
| Compose, Caddy, settings, build constants, or capacity ceilings | `CONFIGURATION.md`, and `DEPLOYMENT.md` when deployment changes |
| Main/Settings Compose UI or accessibility | `UI_DESIGN.md`, `USER_GUIDE.md` |
| BM008, route selection, capture, playback, tones, or power saving | `BLUETOOTH_PTT_AUDIO.md`, `MANUAL_TESTING.md`, `SUPPORT.md` |
| Diagnostics schema, collection, retention, or redaction | `SUPPORT.md`, `protocol.md`, `CONFIGURATION.md` |
| APK metadata, download, signing, or installer behavior | `ANDROID_BUILD.md`, `protocol.md`, `USER_GUIDE.md` |
| Headless API, runner lifecycle, or production bot image | `HEADLESS_CLIENT.md`, `TESTING.md` |
| QRZ example, custom-bot workflow, or example audio | `BOT_DEVELOPMENT.md`, `TESTING.md`, `examples/qrz_bot/assets/README.md` |
| Product scope or supported use case | `PRODUCT.md`, `KNOWN_LIMITATIONS.md` |

## Documentation conventions

- Use English for all repository content, including source code, comments,
  logs, API/UI messages, tests, fixtures, and documentation, as required by `AGENTS.md`.
- Prefer stable file paths, class names, and function names over line numbers.
- State invariants and failure behavior explicitly.
- Avoid volatile test counts, copied dependency versions, and release-specific
  evidence in living documents.
- Link to one authoritative explanation instead of repeating it.
- Begin maintained technical and operational documents with `Status`, `Purpose`,
  `Audience`, `Authority`, `Code anchors`, and `Update when`. The root `README.md`,
  `SECURITY.md`, and `LICENSE` use their GitHub-facing formats instead.
