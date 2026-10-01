"""Protocol-triggered one-shot WebSocket fault proxy for headless integration tests."""

from __future__ import annotations

import asyncio
import json
import os
import struct
import time

from websockets.asyncio.client import connect
from websockets.asyncio.server import ServerConnection, serve

LISTEN_PORT = 8080
UPSTREAM_URL = "ws://caddy/ws"
SUBPROTOCOL = "zenptt.v4"
HEADER = struct.Struct("!BB16sIH")
FAULT = os.environ.get("ZENPTT_TEST_FAULT", "uplink:20")


def media_range(message: bytes, direction: int) -> range | None:
    if len(message) < HEADER.size:
        return None
    version, actual_direction, _, first, count = HEADER.unpack_from(message)
    if version != 4 or actual_direction != direction:
        return None
    return range(first, first + count)


class Fault:
    def __init__(self, spec: str) -> None:
        name, _, value = spec.partition(":")
        self.name = name
        self.sequence = int(value) if value else None
        self.triggered = False
        self.block_until = 0.0
        self.terminal_burst_id: str | None = None
        self.echo_bot_selected = False
        self.echo_resume_token: str | None = None
        self.lock = asyncio.Lock()

    async def matches(self, direction: str, message: str | bytes) -> bool:
        async with self.lock:
            if self.triggered:
                return False
            matched = False
            if isinstance(message, str):
                control = json.loads(message)
                kind = control.get("type")
                if (
                    self.name == "terminal"
                    and direction == "up"
                    and kind == "burst_end"
                ):
                    self.terminal_burst_id = control.get("burst_id")
                    print(
                        f"fault_proxy_upstream_end_forwarded scenario={FAULT}",
                        flush=True,
                    )
                matched = (
                    self.name == "before_grant"
                    and direction == "down"
                    and kind == "ptt_granted"
                ) or (
                    self.name == "burst_end"
                    and direction == "up"
                    and kind == "burst_end"
                ) or (
                    self.name == "terminal"
                    and direction == "down"
                    and kind == "ptt_ended"
                    and control.get("state") == "sealed"
                    and control.get("burst_id") == self.terminal_burst_id
                )
            elif self.name in {"uplink", "downlink"} and self.sequence is not None:
                expected_direction = 1 if self.name == "uplink" else 2
                expected_flow = "up" if expected_direction == 1 else "down"
                if direction == expected_flow:
                    sequences = media_range(message, expected_direction)
                    matched = sequences is not None and self.sequence in sequences
            if matched:
                self.triggered = True
                if self.name == "downlink":
                    self.block_until = time.monotonic() + 16.2
                print(f"fault_proxy_triggered scenario={FAULT}", flush=True)
            return matched

    def blocks_reconnect(self) -> bool:
        return self.name == "downlink" and time.monotonic() < self.block_until

    async def select_echo_bot(self, message: str | bytes) -> bool:
        if self.name != "echo_bot" or not isinstance(message, str):
            return False
        control = json.loads(message)
        async with self.lock:
            if control.get("type") == "join_echo_bot" and not self.echo_bot_selected:
                self.echo_bot_selected = True
                return True
            return False

    async def blocks_echo_resume(self, message: str | bytes) -> bool:
        if self.name != "echo_bot" or not isinstance(message, str):
            return False
        control = json.loads(message)
        async with self.lock:
            return bool(
                control.get("type") == "resume"
                and control.get("resume_token") == self.echo_resume_token
                and time.monotonic() < self.block_until
            )

    async def capture_echo_snapshot(self, message: str | bytes) -> bool:
        if self.name != "echo_bot" or not isinstance(message, str):
            return False
        control = json.loads(message)
        async with self.lock:
            if control.get("type") != "snapshot" or self.echo_resume_token is not None:
                return False
            self.echo_resume_token = control.get("resume_token")
            self.block_until = time.monotonic() + 16.2
            self.triggered = True
            print(f"fault_proxy_triggered scenario={FAULT}", flush=True)
            return True


fault = Fault(FAULT)


def abort(connection) -> None:
    transport = getattr(connection, "transport", None)
    if transport is not None:
        transport.abort()


async def handler(client: ServerConnection) -> None:
    async with connect(UPSTREAM_URL, subprotocols=[SUBPROTOCOL], max_size=4096) as upstream:
        if fault.blocks_reconnect():
            abort(client)
            abort(upstream)
            return
        target_echo_bot = False

        async def forward(source, target, direction: str) -> None:
            nonlocal target_echo_bot
            async for message in source:
                if direction == "up":
                    target_echo_bot = target_echo_bot or await fault.select_echo_bot(message)
                    if await fault.blocks_echo_resume(message):
                        abort(client)
                        abort(upstream)
                        return
                if direction == "down" and target_echo_bot:
                    await target.send(message)
                    if await fault.capture_echo_snapshot(message):
                        abort(client)
                        abort(upstream)
                        return
                    continue
                if await fault.matches(direction, message):
                    abort(client)
                    abort(upstream)
                    return
                await target.send(message)

        tasks = {
            asyncio.create_task(forward(client, upstream, "up")),
            asyncio.create_task(forward(upstream, client, "down")),
        }
        done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in pending:
            task.cancel()
        await asyncio.gather(*done, *pending, return_exceptions=True)


async def main() -> None:
    async with serve(
        handler,
        "0.0.0.0",
        LISTEN_PORT,
        subprotocols=[SUBPROTOCOL],
        max_size=4096,
    ):
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
