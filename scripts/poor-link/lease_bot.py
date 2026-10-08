"""Three-party lease probe using the regular observed headless client."""

from __future__ import annotations

import asyncio
import logging
import os
from pathlib import Path
import time

from zenptt_headless import ClientConfig, PcmAudio, ReceivedBurst

from probe_base import FRAME_BYTES, OUTPUT, ObservedClient, emit, network_samples


async def wait_marker(name: str, seconds: float) -> None:
    deadline = time.monotonic() + seconds
    while not (OUTPUT / name).exists():
        if time.monotonic() >= deadline:
            raise TimeoutError(f"Timed out waiting for {name}")
        await asyncio.sleep(0.05)


async def sender(client: ObservedClient, mode: str) -> None:
    await wait_marker("start", 30)
    for ordinal in range(1, 4):
        client.ordinal = ordinal
        data = (Path("/audio") / f"speech-{ordinal}.pcm").read_bytes()
        if ordinal == 2 and mode == "voluntary":
            data = data[:250 * FRAME_BYTES]
        emit("send_start", ordinal=ordinal, planned_frames=len(data) // FRAME_BYTES)
        task = asyncio.create_task(client.send_audio(PcmAudio(data)))
        if ordinal == 2 and mode == "cancel":
            await wait_marker("cancel", 30)
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                emit("sender_canceled", ordinal=ordinal)
        else:
            result = await asyncio.wait_for(task, 60)
            emit("send_result", ordinal=ordinal, status=result.status,
                 reason=result.reason, burst_id=result.burst_id,
                 session_epoch=client.session_epoch, transports=client.transport_count)
        if ordinal < 3:
            await asyncio.sleep(2)
    emit("sender_finished")


async def receiver(client: ObservedClient) -> None:
    seen: set[str] = set()
    expected = 3 if os.environ["ZENPTT_LEASE_MODE"] == "disappear" else 4
    deadline = time.monotonic() + 240
    done_at: float | None = None
    while time.monotonic() < deadline:
        if (OUTPUT / "sender_done").exists() and (OUTPUT / "contender_done").exists():
            if done_at is None:
                done_at = time.monotonic()
            if time.monotonic() - done_at >= (1 if len(seen) >= expected else 30):
                break
        try:
            received = await asyncio.wait_for(client.receive(), timeout=0.5)
        except TimeoutError:
            continue
        if isinstance(received, ReceivedBurst):
            path = OUTPUT / f"received-{received.burst_index}-{received.burst_id}.pcm"
            path.write_bytes(received.audio.data)
            emit("received", burst_id=received.burst_id, burst_index=received.burst_index,
                 first_sequence=received.first_sequence,
                 frames=len(received.audio.data) // FRAME_BYTES,
                 loss_ranges=[[part.first_sequence, part.count] for part in received.losses],
                 decode_errors=list(received.decode_errors), reason=received.reason,
                 session_epoch=received.session_epoch, pcm_file=path.name)
            seen.add(received.burst_id)
        else:
            emit("receive_interrupted", burst_id=received.burst_id,
                 reason=received.reason, session_epoch=received.session_epoch)
    emit("receiver_finished", completed_bursts=len(seen))


async def contender(client: ObservedClient) -> None:
    await wait_marker("start", 90)
    client.ordinal = 1
    emit("contender_request_start")
    result = await asyncio.wait_for(client.send_audio(PcmAudio(bytes(FRAME_BYTES))), 90)
    emit("contender_result", status=result.status, reason=result.reason,
         burst_id=result.burst_id, transports=client.transport_count)


async def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    role = os.environ["ZENPTT_ROLE"]
    mode = os.environ["ZENPTT_LEASE_MODE"]
    client = ObservedClient(ClientConfig(os.environ["ZENPTT_URL"],
                                         os.environ["ZENPTT_CHANNEL"]))
    network = asyncio.create_task(network_samples())
    try:
        await client.start()
        emit("ready", role=role, session_epoch=client.session_epoch)
        if role == "sender":
            await sender(client, mode)
        elif role == "receiver":
            await receiver(client)
        elif role == "contender":
            await contender(client)
        else:
            raise ValueError("Invalid lease probe role")
    except Exception as error:
        emit("probe_error", role=role, error=type(error).__name__, detail=str(error)[:160])
        raise
    finally:
        await client.stop()
        network.cancel()
        try:
            await network
        except asyncio.CancelledError:
            pass


if __name__ == "__main__":
    asyncio.run(main())
