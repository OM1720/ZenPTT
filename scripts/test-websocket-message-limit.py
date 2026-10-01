import asyncio
import json
import sys
from urllib.parse import urlsplit, urlunsplit

import websockets
from websockets.exceptions import ConnectionClosed

from app.protocol import MAX_MESSAGE_BYTES


def websocket_url(server_url: str) -> str:
    parsed = urlsplit(server_url)
    scheme = "wss" if parsed.scheme == "https" else "ws"
    return urlunsplit((scheme, parsed.netloc, "/ws", "", ""))


async def expect_too_large(url: str, payload: str | bytes) -> None:
    async with websockets.connect(
        url,
        max_size=None,
        subprotocols=["zenptt.v4"],
    ) as socket:
        await socket.send(payload)
        try:
            await asyncio.wait_for(socket.recv(), timeout=5)
        except ConnectionClosed as error:
            if error.code != 1009:
                raise RuntimeError(f"Oversized WebSocket message closed with {error.code}") from error
            return
    raise RuntimeError("Oversized WebSocket message was accepted")


async def verify(server_url: str) -> None:
    url = websocket_url(server_url)
    await expect_too_large(url, "x" * (MAX_MESSAGE_BYTES + 1))
    await expect_too_large(url, b"x" * (MAX_MESSAGE_BYTES + 1))

    async with websockets.connect(url, subprotocols=["zenptt.v4"]) as socket:
        await socket.send(json.dumps({"type": "ping", "id": 1, "sent_at_ms": 1}))
        response = json.loads(await asyncio.wait_for(socket.recv(), timeout=5))
        if response != {"type": "pong", "id": 1, "sent_at_ms": 1}:
            raise RuntimeError(f"Server did not recover after oversized messages: {response}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: test-websocket-message-limit.py SERVER_URL")
    asyncio.run(verify(sys.argv[1]))
    print("WebSocket text and binary message limits: passed")
