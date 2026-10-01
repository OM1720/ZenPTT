"""Independent protocol client for containerized headless bot acceptance."""

from __future__ import annotations

import asyncio
from array import array
import ctypes
import ctypes.util
import json
import math
import os
from pathlib import Path
import struct
import sys
import time
import uuid

from websockets.asyncio.client import connect

HEADER = struct.Struct("!BB16sIH")
LENGTH = struct.Struct("!H")
RECEIVE_BACKLOG: dict[int, list[str | bytes]] = {}
WS_URL = os.environ.get("ZENPTT_LIVE_WS_URL", "ws://caddy/ws")
ACK_WINDOW_FRAMES = 25


class Codec:
    def __init__(self) -> None:
        self.lib = ctypes.CDLL(ctypes.util.find_library("opus"))
        self.lib.opus_encoder_create.restype = ctypes.c_void_p
        self.lib.opus_decoder_create.restype = ctypes.c_void_p
        self.lib.opus_encode.argtypes = [
            ctypes.c_void_p,
            ctypes.POINTER(ctypes.c_int16),
            ctypes.c_int,
            ctypes.POINTER(ctypes.c_ubyte),
            ctypes.c_int32,
        ]
        self.lib.opus_decode.argtypes = [
            ctypes.c_void_p,
            ctypes.POINTER(ctypes.c_ubyte),
            ctypes.c_int32,
            ctypes.POINTER(ctypes.c_int16),
            ctypes.c_int,
            ctypes.c_int,
        ]
        error = ctypes.c_int()
        self.encoder = self.lib.opus_encoder_create(16_000, 1, 2048, ctypes.byref(error))
        assert error.value == 0
        self.decoder = self.lib.opus_decoder_create(16_000, 1, ctypes.byref(error))
        assert error.value == 0

    def encode_silence(self) -> bytes:
        source = (ctypes.c_int16 * 320)()
        return self.encode(bytes(source))

    def encode(self, pcm: bytes) -> bytes:
        source = (ctypes.c_int16 * 320).from_buffer_copy(pcm)
        output = (ctypes.c_ubyte * 1275)()
        size = self.lib.opus_encode(self.encoder, source, 320, output, 1275)
        assert size > 0
        return bytes(output[:size])

    def decode(self, packet: bytes) -> bytes:
        source = (ctypes.c_ubyte * len(packet)).from_buffer_copy(packet)
        output = (ctypes.c_int16 * 320)()
        count = self.lib.opus_decode(self.decoder, source, len(packet), output, 320, 0)
        assert count == 320
        return bytes(output)


def media(burst_id: str, sequence: int, packet: bytes) -> bytes:
    return (
        HEADER.pack(4, 1, uuid.UUID(burst_id).bytes, sequence, 1)
        + LENGTH.pack(len(packet))
        + packet
    )


def decode_downlink(raw: bytes) -> tuple[str, int, tuple[bytes, ...]]:
    version, direction, burst, first, count = HEADER.unpack_from(raw)
    assert version == 4 and direction == 2 and count > 0
    offset = HEADER.size
    packets = []
    for _ in range(count):
        (size,) = LENGTH.unpack_from(raw, offset)
        offset += LENGTH.size
        packets.append(raw[offset : offset + size])
        offset += size
    assert offset == len(raw)
    return str(uuid.UUID(bytes=burst)), first, tuple(packets)


def energy_envelope(pcm: bytes) -> list[float]:
    samples = array("h", pcm)
    return [sum(abs(value) for value in samples[offset : offset + 320]) / 320 for offset in range(0, len(samples), 320)]


def correlation(left: list[float], right: list[float]) -> float:
    left_mean = sum(left) / len(left)
    right_mean = sum(right) / len(right)
    numerator = sum((a - left_mean) * (b - right_mean) for a, b in zip(left, right))
    left_size = sum((value - left_mean) ** 2 for value in left) ** 0.5
    right_size = sum((value - right_mean) ** 2 for value in right) ** 0.5
    return numerator / (left_size * right_size) if left_size and right_size else 0


def spectral_profile(pcm: bytes) -> list[float]:
    # Average short-window Goertzel spectra: magnitudes tolerate Opus phase and
    # delay, while the full Nyquist range distinguishes another carrier or noise.
    samples = array("h", pcm)
    windows = [samples[offset:offset+128] for offset in range(0, len(samples)-127, 640)]
    powers = []
    for band in range(1, 65):
        coefficient = 2 * math.cos(2 * math.pi * band / 128)
        power = 0.0
        for window in windows:
            previous, older = 0.0, 0.0
            for sample in window:
                current = sample + coefficient * previous - older
                older, previous = previous, current
            power += max(0, previous*previous + older*older - coefficient*previous*older)
        powers.append(power)
    return powers


def assert_audio(reference: bytes, received: bytes, label: str) -> float:
    reference += bytes((-len(reference)) % 640)
    assert len(received) == len(reference), f"{label} frame count mismatch"
    source_energy = energy_envelope(reference)
    response_energy = energy_envelope(received)
    correlations = []
    for lag in range(11):
        correlations.append(correlation(source_energy[:len(response_energy)-lag], response_energy[lag:]))
        correlations.append(correlation(source_energy[lag:], response_energy[:len(source_energy)-lag]))
    best = max(correlations)
    assert best > 0.9, f"{label} energy envelope mismatch: correlation={best:.6f}"
    source_spectrum, response_spectrum = spectral_profile(reference), spectral_profile(received)
    scale = math.sqrt(sum(value*value for value in source_spectrum)
                      * sum(value*value for value in response_spectrum))
    similarity = sum(a*b for a, b in zip(source_spectrum, response_spectrum)) / scale if scale else 0
    assert similarity > 0.9, f"{label} spectrum mismatch: similarity={similarity:.6f}"
    return best


def assert_qrz_audio(reference: bytes, received: bytes) -> float:
    """Keep the independent QRZ audio gate available to the container codec checks."""

    return assert_audio(reference, received, "QRZ")


async def control_until(socket, wanted: set[str], forbidden_burst: str | None = None) -> dict:
    backlog = RECEIVE_BACKLOG.setdefault(id(socket), [])

    def matching_control() -> dict | None:
        for index, raw in enumerate(backlog):
            if not isinstance(raw, str):
                continue
            message = json.loads(raw)
            if message["type"] in wanted:
                backlog.pop(index)
                return message
        return None

    matching = matching_control()
    if matching is not None:
        return matching
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        raw = await asyncio.wait_for(socket.recv(), deadline - time.monotonic())
        if isinstance(raw, bytes) and forbidden_burst is not None:
            assert decode_downlink(raw)[0] != forbidden_burst, "Own media reached the listener"
        if isinstance(raw, str):
            message = json.loads(raw)
            if message["type"] == "burst_started" and forbidden_burst is not None:
                assert message["burst_id"] != forbidden_burst, "Own burst reached the listener"
            if message["type"] in wanted:
                return message
        backlog.append(raw)
    raise AssertionError(f"Timed out waiting for {wanted}")


def tone_pcm(frame_count: int, *, frequency: int = 700, varied: bool = False) -> bytes:
    pcm = bytearray()
    amplitudes = (1_500, 7_000, 3_000, 8_000, 2_500, 6_500, 4_000, 7_500)
    for frame in range(frame_count):
        amplitude = 8_000
        if varied:
            amplitude = amplitudes[(frame // 4) % len(amplitudes)]
        pcm.extend(
            array(
                "h",
                (
                    round(
                        amplitude
                        * math.sin(2 * math.pi * frequency * (frame * 320 + sample) / 16000)
                    )
                    for sample in range(320)
                ),
            ).tobytes()
        )
    return bytes(pcm)


async def send_source(socket, codec: Codec, source: int | bytes) -> dict:
    source_pcm = tone_pcm(source) if isinstance(source, int) else source
    assert source_pcm and len(source_pcm) % 640 == 0
    source_frames = len(source_pcm) // 640
    request_id = str(uuid.uuid4())
    await socket.send(json.dumps({"type": "ptt_request", "request_id": request_id}))
    grant = await control_until(socket, {"ptt_granted"})
    assert grant["request_id"] == request_id
    source_started = time.monotonic()
    for sequence in range(source_frames):
        target = source_started + sequence * 0.02
        if target > time.monotonic():
            await asyncio.sleep(target - time.monotonic())
        frame = source_pcm[sequence * 640 : (sequence + 1) * 640]
        await socket.send(media(grant["burst_id"], sequence, codec.encode(frame)))
        expected_ack = sequence + 1
        if expected_ack % ACK_WINDOW_FRAMES == 0 or expected_ack == source_frames:
            while True:
                ack = await control_until(socket, {"uplink_ack"}, grant["burst_id"])
                if ack["next_sequence"] >= expected_ack:
                    break
    await socket.send(
        json.dumps(
            {
                "type": "burst_end",
                "burst_id": grant["burst_id"],
                "final_next_sequence": source_frames,
            }
        )
    )
    while True:
        ended = await control_until(socket, {"ptt_ended"}, grant["burst_id"])
        if ended["burst_id"] == grant["burst_id"] and ended["state"] == "sealed":
            assert ended["burst_index"] == grant["burst_index"]
            assert ended["final_next_sequence"] == source_frames
            assert ended["reason"] == "complete"
            return {key: ended[key] for key in (
                "burst_id", "burst_index", "final_next_sequence", "reason",
            )}


async def receive_response(
    socket, reference: bytes, expected_frames: int, source_id: str, label: str
) -> float:
    started = await control_until(socket, {"burst_started"})
    assert started["burst_id"] != source_id
    codec = Codec()
    received_frames = 0
    nonzero = False
    response_pcm = bytearray()
    response_started = time.monotonic()
    while True:
        backlog = RECEIVE_BACKLOG.setdefault(id(socket), [])
        raw = backlog.pop(0) if backlog else await asyncio.wait_for(socket.recv(), 15)
        if isinstance(raw, bytes):
            response_id, first, packets = decode_downlink(raw)
            assert response_id == started["burst_id"]
            assert first == received_frames
            for packet in packets:
                pcm = codec.decode(packet)
                nonzero = nonzero or any(pcm)
                response_pcm.extend(pcm)
                received_frames += 1
        else:
            message = json.loads(raw)
            if message["type"] == "burst_sealed" and message["burst_id"] == started["burst_id"]:
                assert message["final_next_sequence"] == received_frames
                break
    assert received_frames == expected_frames
    assert nonzero
    elapsed = time.monotonic() - response_started
    assert expected_frames * 0.018 <= elapsed <= expected_frames * 0.04 + 2
    return assert_audio(reference, bytes(response_pcm), label)


async def receive_qrz(socket, decoded: bytes, expected_frames: int, source_id: str) -> float:
    """Keep the QRZ contract helper stable for the independent restart scenario."""

    return await receive_response(socket, decoded, expected_frames, source_id, "QRZ")


async def assert_quiet(socket, seconds: float) -> None:
    deadline = time.monotonic() + seconds
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return
        backlog = RECEIVE_BACKLOG.setdefault(id(socket), [])
        if backlog:
            raw = backlog.pop(0)
        else:
            try:
                raw = await asyncio.wait_for(socket.recv(), remaining)
            except asyncio.TimeoutError:
                return
        if isinstance(raw, bytes):
            raise AssertionError("Unexpected extra bot audio")
        message = json.loads(raw)
        if message["type"] == "burst_started":
            raise AssertionError("Unexpected extra bot response")


async def run_echo_client(index: int) -> dict:
    codec = Codec()
    sources_pcm = (
        tone_pcm(2_750 if index == 0 else 20 + index * 4, frequency=500 + index * 300, varied=True),
        tone_pcm(12 + index * 3, frequency=650 + index * 300, varied=True),
    )
    async with connect(WS_URL, subprotocols=["zenptt.v4"], max_size=4096) as socket:
        await socket.send('{"type":"join_echo"}')
        snapshot = await control_until(socket, {"snapshot"})
        assert snapshot["channel"] == "ECHO"
        assert snapshot["participant_count"] == 2
        await socket.send(
            json.dumps(
                {"type": "listen", "burst_index": snapshot["eligible_from_index"], "next_sequence": 0},
                separators=(",", ":"),
            )
        )
        sources = []
        for source_pcm in sources_pcm:
            source = await send_source(socket, codec, source_pcm)
            await receive_response(
                socket,
                source_pcm,
                len(source_pcm) // 640,
                source["burst_id"],
                f"Echo client {index}",
            )
            sources.append(source)
        await assert_quiet(socket, 1)
        await socket.send('{"type":"disconnect"}')
        return {
            "member_id": snapshot["member_id"],
            "incarnation": snapshot["channel_incarnation_id"],
            "sources": sources,
        }


async def open_echo_client() -> tuple[object, dict]:
    deadline = time.monotonic() + 30
    while True:
        try:
            socket = await connect(
                WS_URL, subprotocols=["zenptt.v4"], max_size=4096
            )
            await socket.send('{"type":"join_echo"}')
            snapshot = await control_until(socket, {"snapshot"})
            assert snapshot["channel"] == "ECHO"
            assert snapshot["participant_count"] == 2
            await socket.send(
                json.dumps(
                    {
                        "type": "listen",
                        "burst_index": snapshot["eligible_from_index"],
                        "next_sequence": 0,
                    },
                    separators=(",", ":"),
                )
            )
            return socket, snapshot
        except Exception:
            if time.monotonic() >= deadline:
                raise
            await asyncio.sleep(0.5)


async def wait_for_echo_replacement(socket) -> None:
    lost = False
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        raw = await asyncio.wait_for(socket.recv(), deadline - time.monotonic())
        if isinstance(raw, bytes):
            continue
        message = json.loads(raw)
        if message["type"] in {"snapshot", "channel_state"}:
            count = message["participant_count"]
            lost = lost or count == 1
            if lost and count == 2:
                return
    raise AssertionError("Echo replacement did not restore two participants")


async def verify_echo_response(socket, index: int, frames: int) -> None:
    source_pcm = tone_pcm(frames, frequency=550 + index * 250, varied=True)
    codec = Codec()
    source = await send_source(socket, codec, source_pcm)
    await receive_response(
        socket,
        source_pcm,
        frames,
        source["burst_id"],
        f"Echo restart client {index}",
    )


async def keep_echo_active(socket) -> None:
    ping_id = 0
    while True:
        await socket.send(
            json.dumps(
                {"type": "ping", "id": ping_id, "sent_at_ms": int(time.monotonic() * 1000)},
                separators=(",", ":"),
            )
        )
        ping_id += 1
        await asyncio.sleep(1)


async def run_echo_restarts() -> None:
    clients = [await open_echo_client() for _ in range(3)]
    sockets = [entry[0] for entry in clients]
    keepalives = [asyncio.create_task(keep_echo_active(socket)) for socket in sockets]
    before = {entry[1]["channel_incarnation_id"] for entry in clients}
    assert len(before) == 3
    replacement_tasks = [asyncio.create_task(wait_for_echo_replacement(socket)) for socket in sockets]
    done, pending = await asyncio.wait(replacement_tasks, return_when=asyncio.FIRST_COMPLETED)
    for task in pending:
        task.cancel()
    await asyncio.gather(*done, *pending, return_exceptions=True)
    print("echo_bot_fault_recovered", flush=True)
    print("echo_supervisor_restart_ready", flush=True)
    await asyncio.gather(*(wait_for_echo_replacement(socket) for socket in sockets))
    print("echo_server_restart_ready", flush=True)
    await asyncio.sleep(15)
    for task in keepalives:
        task.cancel()
    await asyncio.gather(*keepalives, return_exceptions=True)
    await asyncio.gather(*(socket.close() for socket in sockets), return_exceptions=True)

    restarted = [await open_echo_client() for _ in range(3)]
    restarted_sockets = [entry[0] for entry in restarted]
    restarted_keepalives = [
        asyncio.create_task(keep_echo_active(socket)) for socket in restarted_sockets
    ]
    after = {entry[1]["channel_incarnation_id"] for entry in restarted}
    assert len(after) == 3
    assert before.isdisjoint(after)
    await asyncio.gather(
        *(
            verify_echo_response(socket, index, 40 + index * 4)
            for index, socket in enumerate(restarted_sockets)
        )
    )
    for task in restarted_keepalives:
        task.cancel()
    await asyncio.gather(*restarted_keepalives, return_exceptions=True)
    await asyncio.gather(*(socket.close() for socket in restarted_sockets))
    print("Echo restart recovery passed: clients=3 supervisor=ready server=new-incarnations")


async def main() -> None:
    echo = "--echo" in sys.argv
    echo_restarts = "--echo-restarts" in sys.argv
    long_source = "--long-source" in sys.argv
    if echo_restarts:
        await run_echo_restarts()
        return
    if echo:
        results = await asyncio.gather(*(run_echo_client(index) for index in range(3)))
        assert len({result["member_id"] for result in results}) == 3
        assert len({result["incarnation"] for result in results}) == 3
        assert len({source["burst_id"] for result in results for source in result["sources"]}) == 6
        print("Echo live isolation passed: clients=3 responses=6 long_seconds=55")
        print("echo_live_result " + json.dumps(results, separators=(",", ":")))
        return
    qrz_pcm = Path("/audio/QRZ.pcm").read_bytes()
    qrz_frames = (len(qrz_pcm) + 639) // 640
    channel = "TEST"
    sources_pcm = (
        tone_pcm(1_000 if long_source else 1),
        tone_pcm(1),
    )
    codec = Codec()
    async with connect(WS_URL, subprotocols=["zenptt.v4"], max_size=4096) as socket:
        await socket.send(json.dumps({"type": "join", "channel": channel}))
        snapshot = await control_until(socket, {"snapshot"})
        await socket.send(
            json.dumps(
                {"type": "listen", "burst_index": snapshot["eligible_from_index"], "next_sequence": 0},
                separators=(",", ":"),
            )
        )
        sources = []
        correlations = []
        for index, source_pcm in enumerate(sources_pcm):
            source = await send_source(socket, codec if index == 0 else Codec(), source_pcm)
            reference = qrz_pcm
            expected_frames = qrz_frames
            correlation = await receive_response(
                socket,
                reference,
                expected_frames,
                source["burst_id"],
                "QRZ",
            )
            sources.append(source)
            correlations.append(correlation)
        await assert_quiet(socket, 3)
        await socket.send('{"type":"disconnect"}')
    print(
        f"QRZ live round trips passed: "
        f"response_frames={[qrz_frames for value in sources_pcm]}, "
        f"correlations={correlations[0]:.3f},{correlations[1]:.3f}"
    )
    print(
        "headless_live_result "
        + json.dumps(
            {
                "sources": sources,
                "response_frames": [
                    qrz_frames for value in sources_pcm
                ],
            },
            separators=(",", ":"),
        )
    )


if __name__ == "__main__":
    asyncio.run(main())
