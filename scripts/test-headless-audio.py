"""Real Opus resume and independent QRZ-oracle checks in the pinned Linux image."""

import asyncio
from array import array
import json
import math
from pathlib import Path
import random

from zenptt_headless.client import ClientConfig, HeadlessClient
from zenptt_headless.codec import OpusDecoder, OpusEncoder
from zenptt_headless.protocol import DOWNLINK, encode_media

from test import assert_qrz_audio, energy_envelope

BURST_ID = "11111111-1111-4111-8111-111111111111"
NEXT_ID = "22222222-2222-4222-8222-222222222222"
INCARNATION = "33333333-3333-4333-8333-333333333333"


def encode(pcm):
    encoder = OpusEncoder()
    try:
        pcm += bytes((-len(pcm)) % 640)
        return [encoder.encode(pcm[offset:offset+640]) for offset in range(0, len(pcm), 640)]
    finally:
        encoder.close()


def decode(packets):
    decoder = OpusDecoder()
    try:
        return b"".join(decoder.decode(packet) for packet in packets)
    finally:
        decoder.close()


class Socket:
    def __init__(self, messages=()):
        self.messages = messages
        self.writes = []

    async def send(self, message):
        self.writes.append(json.loads(message))

    async def recv(self):
        return json.dumps({
            "type": "snapshot", "channel": "TEST", "member_id": NEXT_ID,
            "resume_token": "token", "generation": 2, "channel_incarnation_id": INCARNATION,
            "revision": 1, "participant_count": 2, "eligible_from_index": 0,
            "next_burst_index": 1, "audio_policy": {"recovery_horizon_ms": 5000}, "floor": None,
        })

    async def __aiter__(self):
        for message in self.messages:
            yield message if isinstance(message, bytes) else json.dumps(message)


async def verify_resume_decoder(reset_decoder=False):
    pcm = array("h", (round(6000 * math.sin(i * (0.08 + i / 500000)))
                      for i in range(30 * 320))).tobytes()
    packets = encode(pcm)
    expected = decode(packets)
    client = HeadlessClient(ClientConfig("ws://test"))
    client._resume_token, client._generation, client._incarnation = "token", 1, INCARNATION
    start = {"type": "burst_started", "burst_id": BURST_ID, "burst_index": 0}
    first = client._attach_transport(Socket([start, encode_media(DOWNLINK, BURST_ID, 0, tuple(packets[:10]))]))
    await client._receive_loop(first)
    assert client._listen_cursor == (0, 10)
    original_decoder = client._assembler._burst.decoder
    client._detach_transport(first)
    socket = Socket()
    resumed = client._attach_transport(socket)
    assert await client._handshake(resumed)
    assert client.session_epoch == 0
    assert socket.writes[-1] == {"type": "listen", "burst_index": 0, "next_sequence": 10}
    assert client._assembler._burst.decoder is original_decoder
    if reset_decoder:
        original_decoder.close()
        client._assembler._burst.decoder = OpusDecoder()
    seal = {"type": "burst_sealed", "burst_id": BURST_ID, "burst_index": 0,
            "final_next_sequence": len(packets), "reason": "complete"}
    try:
        socket.messages = [start, encode_media(DOWNLINK, BURST_ID, 9, tuple(packets[9:])), seal]
        await client._receive_loop(resumed)
        result = await client.receive()
        assert result.audio.data == expected, "Resumed PCM differs from continuous decoding"
        assert (result.first_sequence, result.session_epoch, result.losses, result.decode_errors) == (0, 0, (), ())
        assert client._events.empty()
        assert original_decoder._state is None

        socket.messages = [
            {**start, "burst_id": NEXT_ID, "burst_index": 1},
            encode_media(DOWNLINK, NEXT_ID, 0, tuple(packets)),
            {**seal, "burst_id": NEXT_ID, "burst_index": 1},
        ]
        await client._receive_loop(resumed)
        independent = await client.receive()
        assert independent.audio.data == expected, "Independent burst reused the old decoder state"
    finally:
        client._detach_transport(resumed)
        await client.stop()


def verify_qrz_oracle():
    reference = Path("/audio/QRZ.pcm").read_bytes()
    padded = reference + bytes((-len(reference)) % 640)
    positive = assert_qrz_audio(reference, decode(encode(reference)))
    print(f"QRZ positive control passed: correlation={positive:.6f}")
    quieter = array("h", (value // 2 for value in array("h", padded))).tobytes()
    shifted = bytes(160) + decode(encode(quieter))[:-160]
    assert_qrz_audio(reference, shifted)
    print("QRZ gain and delay positive control passed")
    wrong_tone, noise = array("h"), array("h")
    rng = random.Random(172)
    for mean in energy_envelope(padded):
        amplitude = round(mean)
        wrong_tone.extend(amplitude if i % 4 < 2 else -amplitude for i in range(320))
        noise.extend(rng.randint(-min(32767, 2*amplitude), min(32767, 2*amplitude)) for _ in range(320))
    fragments = [padded[offset:offset+640] for offset in range(0, len(padded), 640)]
    rng.shuffle(fragments)
    controls = {
        "wrong-tone": wrong_tone.tobytes(), "noise": noise.tobytes(),
        "reordered": b"".join(fragments), "silence": bytes(len(padded)),
        "truncated": padded[:-640],
    }
    for name, pcm in controls.items():
        try:
            assert_qrz_audio(reference, decode(encode(pcm)))
        except AssertionError:
            print(f"QRZ negative control rejected: {name}")
        else:
            raise AssertionError(f"QRZ oracle accepted {name}")


async def main():
    await verify_resume_decoder()
    try:
        await verify_resume_decoder(reset_decoder=True)
    except AssertionError as error:
        assert str(error) == "Resumed PCM differs from continuous decoding"
    else:
        raise AssertionError("Decoder reset negative control was not detected")
    print("Real Opus resume, deduplication, and independent-burst checks passed")
    verify_qrz_oracle()


if __name__ == "__main__":
    asyncio.run(main())
