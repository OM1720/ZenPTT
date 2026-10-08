"""Disposable two-party speech probe using the current headless client."""

from __future__ import annotations

import asyncio
import json
import logging
import os
from pathlib import Path
import time

from zenptt_headless import ClientConfig, HeadlessClient, PcmAudio, ReceivedBurst
from zenptt_headless.protocol import UPLINK, decode_media


OUTPUT = Path("/out")
FRAME_BYTES = 640


def emit(kind: str, **fields: object) -> None:
    print(json.dumps({"event": kind, "at": time.time(), **fields}), flush=True)


async def network_samples() -> None:
    while True:
        fields = {name: int((Path("/sys/class/net/eth0/statistics") / name).read_text())
                  for name in ("rx_bytes", "tx_bytes", "rx_packets", "tx_packets", "rx_dropped", "tx_dropped")}
        emit("network", **fields)
        await asyncio.sleep(1)


class ObservedClient(HeadlessClient):
    def __init__(self, config: ClientConfig) -> None:
        super().__init__(config)
        self.transport_count = 0
        self.ordinal = 0
        original_media = self._assembler.media

        def observed_media(burst_id: str, first: int, packets: tuple[bytes, ...]) -> object:
            result = original_media(burst_id, first, packets)
            emit("media", burst_id=burst_id, first_sequence=first, count=len(packets))
            return result

        self._assembler.media = observed_media

    def _attach_transport(self, socket: object) -> object:
        self.transport_count += 1
        emit("transport", count=self.transport_count, session_epoch=self.session_epoch)
        original_send = socket.send

        async def observed_send(message: str | bytes, **kwargs: object) -> None:
            fields: dict = {"transport": self.transport_count, "bytes": len(message),
                            "started_at": time.time()}
            if isinstance(message, bytes):
                burst, first, packets = decode_media(message, UPLINK)
                fields.update(burst_id=burst, first_sequence=first, count=len(packets))
            else:
                control = json.loads(message)
                fields.update({key: value for key, value in control.items()
                               if key not in {"resume_token", "ticket", "channel"}})
            try:
                await original_send(message, **kwargs)
                emit("socket_send", **fields)
            except BaseException as error:
                emit("socket_send_failed", error=type(error).__name__, **fields)
                raise

        socket.send = observed_send
        return super()._attach_transport(socket)

    async def _handshake(self, context: object) -> bool:
        result = await super()._handshake(context)
        if result:
            emit("snapshot", transport=self.transport_count, generation=self._generation,
                 session_epoch=self.session_epoch, audio_policy=self._snapshot["audio_policy"],
                 floor=self._snapshot["floor"], listen_cursor=self._listen_cursor)
        return result

    def _prune_retained(self, outgoing: object) -> None:
        before = set(outgoing.retained)
        super()._prune_retained(outgoing)
        removed = sorted(before - outgoing.retained.keys())
        if removed:
            emit("retained_expired", burst_id=outgoing.burst_id, sequences=removed,
                 ack_next=outgoing.ack_next, next_sequence=outgoing.next_sequence)

    def _terminal_result(self, outgoing: object, requested_frames: int, terminal: dict) -> object:
        emit("terminal_decision", burst_id=outgoing.burst_id, requested_frames=requested_frames,
             produced_frames=outgoing.next_sequence, local_final=outgoing.final_next_sequence,
             ack_next=outgoing.ack_next, stop_reason=outgoing.stop_reason,
             release_reason=outgoing.release_reason,
             released_without_final=outgoing.released_without_final, terminal=terminal)
        return super()._terminal_result(outgoing, requested_frames, terminal)

    def _accept_grant(self, outgoing: object, message: dict) -> bool:
        first = outgoing.burst_id is None
        accepted = super()._accept_grant(outgoing, message)
        if first and accepted:
            emit("grant", ordinal=self.ordinal, burst_id=message["burst_id"],
                 lease_remaining_ms=message["lease_remaining_ms"])
        return accepted

    async def _produce_audio(self, audio: PcmAudio, frame_count: int,
                             outgoing: object, encoder: object) -> None:
        try:
            await super()._produce_audio(audio, frame_count, outgoing, encoder)
        finally:
            emit("produced", ordinal=self.ordinal, burst_id=outgoing.burst_id,
                 frames=outgoing.next_sequence)

    async def _handle_control(self, message: dict, *, context: object = None) -> None:
        kind = message["type"]
        if kind in {"uplink_ack", "ptt_ended", "burst_started", "burst_released", "burst_sealed",
                    "burst_gaps", "listen_reset", "audio_rejected", "channel_state"}:
            emit("control", type=kind, **{key: value for key, value in message.items()
                                           if key != "type"})
        await super()._handle_control(message, context=context)


async def send_three(client: ObservedClient) -> None:
    for ordinal in range(1, 4):
        client.ordinal = ordinal
        data = (Path("/audio") / f"speech-{ordinal}.pcm").read_bytes()
        audio = PcmAudio(data)
        emit("send_start", ordinal=ordinal, planned_frames=len(data) // FRAME_BYTES)
        try:
            result = await asyncio.wait_for(client.send_audio(audio), timeout=60)
            emit("send_result", ordinal=ordinal, status=result.status,
                 reason=result.reason, burst_id=result.burst_id,
                 session_epoch=client.session_epoch, transports=client.transport_count)
        except Exception as error:
            emit("send_error", ordinal=ordinal, error=type(error).__name__)
            raise
        if ordinal < 3:
            await asyncio.sleep(2)
    emit("sender_finished")


async def receive_three(client: ObservedClient) -> None:
    seen: set[str] = set()
    deadline = time.monotonic() + 240
    done_at: float | None = None
    while time.monotonic() < deadline and len(seen) < 3:
        if (OUTPUT / "sender_done").exists() and done_at is None:
            done_at = time.monotonic()
        if done_at is not None and time.monotonic() - done_at >= 30:
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
    emit("receiver_finished", completed_bursts=len(seen), expected_bursts=3,
         sender_finished=(OUTPUT / "sender_done").exists())


async def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    role = os.environ["ZENPTT_ROLE"]
    client = ObservedClient(ClientConfig(os.environ["ZENPTT_URL"],
                                         os.environ["ZENPTT_CHANNEL"]))
    network = asyncio.create_task(network_samples())
    try:
        await client.start()
        emit("ready", role=role, session_epoch=client.session_epoch)
        if role == "sender":
            deadline = time.monotonic() + 30
            while not (OUTPUT / "start").exists():
                if time.monotonic() >= deadline:
                    raise TimeoutError("Sender start barrier was not released")
                await asyncio.sleep(0.1)
            await send_three(client)
        else:
            await receive_three(client)
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
