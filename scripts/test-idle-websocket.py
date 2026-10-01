import asyncio
import json
import secrets
import sys
from urllib.parse import urlsplit, urlunsplit

import websockets


def websocket_url(server_url: str) -> str:
    parsed = urlsplit(server_url)
    scheme = "wss" if parsed.scheme == "https" else "ws"
    return urlunsplit((scheme, parsed.netloc, "/ws", "", ""))


async def receive_type(socket, expected: str) -> dict:
    while True:
        message = json.loads(await asyncio.wait_for(socket.recv(), timeout=5))
        if message.get("type") == expected:
            return message


async def verify(server_url: str, idle_seconds: float) -> None:
    url = websocket_url(server_url)
    run_id = secrets.token_hex(8)
    channel = f"IDLE{run_id[:12]}".upper()
    request_id = f"idle-{run_id}"
    async with (
        websockets.connect(url, subprotocols=["zenptt.v4"]) as sender,
        websockets.connect(url, subprotocols=["zenptt.v4"]) as listener,
    ):
        await sender.send(json.dumps({"type": "join", "channel": channel}))
        await listener.send(json.dumps({"type": "join", "channel": channel}))
        await receive_type(sender, "snapshot")
        await receive_type(listener, "snapshot")
        await listener.send(json.dumps({"type": "listen", "burst_index": 0, "next_sequence": 0}))

        await asyncio.sleep(idle_seconds)

        await sender.send(
            json.dumps(
                {
                    "type": "ptt_request",
                    "request_id": request_id,
                }
            )
        )
        granted = await receive_type(sender, "ptt_granted")
        if granted.get("request_id") != request_id:
            raise RuntimeError("Server returned the wrong PTT request id")
        started = await receive_type(listener, "burst_started")

        burst_id = granted["burst_id"]
        if started.get("burst_id") != burst_id:
            raise RuntimeError("Listener started the wrong burst")
        await sender.send(
            json.dumps(
                {
                    "type": "burst_end",
                    "burst_id": burst_id,
                    "final_next_sequence": 0,
                }
            )
        )
        await receive_type(listener, "burst_released")
        ended = await receive_type(listener, "burst_sealed")
        if ended.get("reason") != "complete" or ended.get("final_next_sequence") != 0:
            raise RuntimeError(f"Unexpected listener terminal state: {ended}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: test-idle-websocket.py SERVER_URL IDLE_SECONDS")
    asyncio.run(verify(sys.argv[1], float(sys.argv[2])))
    print(f"Idle WebSocket: passed after {sys.argv[2]}s")
