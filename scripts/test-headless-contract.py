"""Independent live acceptance for contention, late join, PCM, and server restart."""

import asyncio
from contextlib import suppress
import json
from pathlib import Path
import time

from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed, InvalidStatus

from test import Codec, assert_quiet, control_until, media, receive_qrz, send_source


async def join():
    deadline = time.monotonic() + 15
    while True:
        socket = None
        try:
            socket = await connect("ws://caddy/ws", subprotocols=["zenptt.v4"], open_timeout=2)
            await socket.send('{"type":"join","channel":"Test"}')
            snapshot = await control_until(socket, {"snapshot"})
            return socket, snapshot
        except (OSError, ConnectionClosed, InvalidStatus, asyncio.TimeoutError):
            if socket is not None:
                await socket.close()
            if time.monotonic() >= deadline:
                raise
            await asyncio.sleep(0.2)


async def ping(socket):
    try:
        while True:
            await socket.send('{"type":"ping","id":1,"sent_at_ms":1}')
            await asyncio.sleep(0.5)
    except ConnectionClosed:
        pass


async def main():
    sockets = []
    pingers = []
    decoded = Path("/audio/QRZ.pcm").read_bytes()
    frames = (len(decoded) + 639) // 640
    try:
        sender, initial = await join()
        competitor, _ = await join()
        sockets.extend((sender, competitor))
        pingers.extend(asyncio.create_task(ping(socket)) for socket in sockets)
        await sender.send('{"type":"listen","burst_index":0,"next_sequence":0}')
        await sender.send('{"type":"ptt_request","request_id":"late-source"}')
        grant = await control_until(sender, {"ptt_granted"})
        await competitor.send('{"type":"ptt_request","request_id":"contender"}')
        denied = await control_until(competitor, {"ptt_denied"})
        assert denied["request_id"] == "contender" and denied["reason"] == "channel_busy"
        print("contract_late_join_ready", flush=True)
        codec = Codec()
        bot_joined = False
        sequence = 0
        deadline = time.monotonic() + 20
        while not bot_joined:
            assert time.monotonic() < deadline, "Bot did not join the occupied channel"
            await sender.send(media(grant["burst_id"], sequence, codec.encode_silence()))
            sequence += 1
            while True:
                message = await control_until(sender, {"channel_state", "uplink_ack"}, grant["burst_id"])
                if message["type"] == "channel_state":
                    bot_joined = message["participant_count"] == 3
                else:
                    assert message["next_sequence"] == sequence
                    break
            await asyncio.sleep(0.02)
        await sender.send(json.dumps({"type": "burst_end", "burst_id": grant["burst_id"],
                                      "final_next_sequence": sequence}))
        while True:
            ended = await control_until(sender, {"ptt_ended"}, grant["burst_id"])
            if ended["state"] == "sealed":
                break
        await assert_quiet(sender, 3)
        print("contract_late_join_and_participant_count_passed", flush=True)
        await competitor.send(json.dumps({"type": "listen", "burst_index": grant["burst_index"]+1,
                                          "next_sequence": 0}))
        first = await send_source(competitor, Codec(), 32)
        await receive_qrz(competitor, decoded, frames, first["burst_id"])
        print("contract_restart_ready", flush=True)
        # The parent restarts the real server; transport loss alone is insufficient.
        with suppress(ConnectionClosed):
            while True:
                await asyncio.wait_for(competitor.recv(), 15)
        for task in pingers:
            task.cancel()
        await asyncio.gather(*pingers, return_exceptions=True)
        pingers.clear()
        sender, current = await join()
        competitor, _ = await join()
        sockets.extend((sender, competitor))
        pingers.extend(asyncio.create_task(ping(socket)) for socket in (sender, competitor))
        assert current["channel_incarnation_id"] != initial["channel_incarnation_id"]
        participants = current["participant_count"]
        while participants != 3:
            state = await control_until(sender, {"channel_state"})
            participants = state["participant_count"]
        await sender.send(json.dumps({"type": "listen", "burst_index": current["eligible_from_index"],
                                      "next_sequence": 0}))
        second = await send_source(sender, Codec(), 64)
        await receive_qrz(sender, decoded, frames, second["burst_id"])
        await assert_quiet(sender, 3)
        print("contract_contention_and_restart_passed", flush=True)
        print("headless_live_result " + json.dumps({"sources": [first, second],
                                                   "expected_frames": frames}), flush=True)
    finally:
        for task in pingers:
            task.cancel()
        await asyncio.gather(*pingers, return_exceptions=True)
        await asyncio.gather(*(socket.close() for socket in sockets), return_exceptions=True)


asyncio.run(main())
