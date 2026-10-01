# Developing ZenPTT bots

Status: current

Purpose: provide the shortest supported path from the QRZ example to a custom
ZenPTT bot without adding that bot to the server delivery.

Audience: bot developers and maintainers of the QRZ example.

Authority: this document owns the QRZ example workflow, example configuration,
prepared-audio requirements, and the supported custom-bot development path.

Code anchors: `examples/qrz_bot`, `headless/src/zenptt_headless`, and
`scripts/test-headless.ps1`.

Update when: the public bot API, QRZ example, example Compose file, audio format,
or custom-bot development workflow changes.

## Boundary

The reusable `zenptt_headless` package owns WebSocket framing, reconnect and
resume, PTT requests, Opus encoding and decoding, queues, retries, and bounded
shutdown. A bot supplies one async handler that receives a completed
`ReceivedBurst` and returns either a non-empty `PcmAudio` response or `None`.

Only sealed bursts reach the handler. `None` means that the bot sends no reply.
The handler must not attempt to manage the transport or send PTT messages itself.
Move blocking work to `asyncio.to_thread()` and prepare reusable dependencies
before defining the handler.

The official four-file server delivery contains the shared SDK and required Echo
Supervisor only. QRZ and custom bots run as separate source-checkout or operator
services and are never installed by `install-update.sh`.

## Public API used by bots

Import supported bot primitives from the package root:

- `BotConfig` and `ClientConfig` configure one standalone channel participant;
- `ReceivedBurst` contains completed PCM and delivery metadata;
- `PcmAudio` wraps immutable s16le mono 16 kHz response bytes;
- `load_pcm(path)` loads and validates a prepared PCM response;
- `run_standalone(config, handler)` owns signals and the five-second process watchdog;
- `run_bot(config, handler)` runs one async bot and owns SIGINT/SIGTERM;
- `HeadlessClient` and `run_bot_session(config, handler, client)` let an application
  run independent sessions while retaining ownership of signals;
- `ShutdownWatchdog(seconds=5.0)` bounds process shutdown for such applications.

[`HEADLESS_CLIENT.md`](HEADLESS_CLIENT.md) defines the complete lifecycle, limits,
recovery semantics, and PCM ownership rules.

## QRZ example

[`qrz_bot.py`](../examples/qrz_bot/qrz_bot.py) loads the prepared QRZ PCM once,
captures the immutable `PcmAudio`, and returns the same object for every completed
burst. It is intentionally small so the handler policy is separate from the SDK.
Startup performs no network download and no MP3 conversion.

Run the local server, Caddy, and example from the repository root:

```console
docker compose --env-file server.local.env -f compose.yaml -f examples/qrz_bot/compose.yaml --profile qrz-bot up -d --build
```

Stop the same stack with:

```console
docker compose --env-file server.local.env -f compose.yaml -f examples/qrz_bot/compose.yaml --profile qrz-bot down
```

The Compose example builds the production headless image, then mounts only the
example script and `QRZ.pcm` read-only. It does not modify the production image.
Its supported settings are:

| Variable | Default | Meaning |
|---|---|---|
| `ZENPTT_BOT_SERVER_URL` | `ws://caddy/ws` | WebSocket endpoint, including `/ws`. |
| `ZENPTT_BOT_CHANNEL` | `Test` | Ordinary channel joined by the bot. |
| `ZENPTT_BOT_LOG_LEVEL` | `INFO` | Python logging threshold. |
| `ZENPTT_BOT_MEMORY_LIMIT` | `128m` | Container memory ceiling. |
| `ZENPTT_BOT_PIDS_LIMIT` | `32` | Container PID ceiling. |

The direct script reads `ZENPTT_SERVER_URL`, `ZENPTT_CHANNEL`,
`ZENPTT_PCM_PATH`, and `ZENPTT_LOG_LEVEL`. Android server addresses omit `/ws`,
but bot URLs require it.

## Create a custom bot

1. Copy `examples/qrz_bot` to a new source-checkout directory.
2. Replace the QRZ handler with an async function that returns `PcmAudio` or
   `None`; keep transport and lifecycle work in the SDK.
3. Update the separate Compose file to mount the new script and any required
   read-only data. Do not add it to the root production `compose.yaml`.
4. Configure a dedicated ordinary channel and run the Compose profile locally.
5. Add handler unit tests and an end-to-end exchange before operating the bot.

Prepared audio must be signed 16-bit little-endian PCM, mono, 16 kHz, and no
longer than the server's 60-second burst limit. The example includes its own
generated Morse PCM for deterministic tests; generation details and the hash
are in [`assets/README.md`](../examples/qrz_bot/assets/README.md).
FFmpeg is an offline preparation/test dependency for other MP3 input and is not
installed in the runtime image.

One ordinary-channel bot response is heard by all eligible listeners. Two
automatic responders can trigger an indefinite exchange; there is no loop
detection. Use a dedicated channel and do not run multiple automatic responders
there unless that behavior is intentional.

## Remote hosts and multiple bots

A custom bot runs on the machine where you start its Python process or container.
Set its server URL to the reachable public endpoint, such as
`wss://ptt.example.org/ws`, and choose the same ordinary channel as its users.
The bot opens the outgoing connection itself. The QRZ Compose file above is a
local-stack override; a remote bot needs its own service definition without
the local `zenptt-server` dependency and with paths relative to that definition.
The Docker-local address `ws://caddy/ws` is not a remote server address.

An application can run several bots as separate asyncio tasks in the same
process. Each task needs its own fresh client. The following helper runs two
independent echo handlers in different ordinary channels until its caller sets
the stop event or cancels the helper. Its caller owns process signals and any
process watchdog. Session failures are logged independently; this example does
not automatically restart failed sessions.

```python
import asyncio
import logging

from zenptt_headless import (
    BotConfig, ClientConfig, HeadlessClient, PcmAudio, ReceivedBurst, run_bot_session,
)


async def answer(burst: ReceivedBurst) -> PcmAudio | None:
    return burst.audio if burst.audio.data else None


async def run_one(config: BotConfig) -> None:
    try:
        await run_bot_session(config, answer, HeadlessClient(config.client))
    except Exception:
        logging.getLogger("my_bots").exception("Bot session failed")


async def run_two_bots(server_url: str, stop: asyncio.Event) -> None:
    configs = [BotConfig(ClientConfig(server_url, channel)) for channel in ("BOT.ONE", "BOT.TWO")]
    tasks = [asyncio.create_task(run_one(config)) for config in configs]
    try:
        await stop.wait()
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
```

The caller should cancel and await this helper before the event loop closes.
Use `run_standalone()` for a single standalone bot. For a custom process
controller, synchronously start `ShutdownWatchdog` in the first shutdown signal
callback and cancel it only after `asyncio.run()` has finished. The full contract
and timeout behavior are in [`HEADLESS_CLIENT.md`](HEADLESS_CLIENT.md).

The official Echo supervisor runs alongside ZenPTT on the server and manages
only personal `ECHO` sessions. Its bots use the same public session lifecycle,
but their Echo tickets and private control connection belong to that system
service. Your ordinary-channel bots need neither tickets nor access to that
supervisor. `ECHO` is reserved and cannot be joined as an ordinary channel.

## Verify a bot

Run the host and container checks:

```console
.\scripts\test-headless.ps1 -WithDocker
.\scripts\test-headless-live.ps1
```

The first command includes the QRZ handler, generated asset, codec, and process
lifecycle checks. The live gate starts the example through its separate Compose
file and verifies real server/Caddy/bot exchanges and recovery scenarios.
