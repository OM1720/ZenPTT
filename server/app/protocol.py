"""Validates control values and the ZenPTT v4 binary media envelope."""

from __future__ import annotations

import struct
import uuid
from dataclasses import dataclass
from typing import Any

PROTOCOL_VERSION = 4
WEBSOCKET_SUBPROTOCOL = "zenptt.v4"

UPLINK_MEDIA_TYPE = 1
DOWNLINK_MEDIA_TYPE = 2

# version, type, burst UUID, first sequence, frame count
MEDIA_HEADER = struct.Struct("!BB16sIH")
PACKET_LENGTH = struct.Struct("!H")

MAX_MESSAGE_BYTES = 4096
MAX_OPUS_PACKET_BYTES = 1275
MAX_FRAMES_PER_MESSAGE = 50
AUDIO_FRAME_DURATION_MS = 20


@dataclass(frozen=True)
class MediaEnvelope:
    media_type: int
    burst_id: str
    first_sequence: int
    packets: tuple[bytes, ...]


def encode_media(envelope: MediaEnvelope) -> bytes:
    """Encode one bounded consecutive run of Opus packets."""
    if envelope.media_type not in {UPLINK_MEDIA_TYPE, DOWNLINK_MEDIA_TYPE}:
        raise ValueError("Invalid media type")
    if not 0 <= envelope.first_sequence <= 0xFFFFFFFF:
        raise ValueError("Invalid media sequence")
    if not 1 <= len(envelope.packets) <= MAX_FRAMES_PER_MESSAGE:
        raise ValueError("Invalid media frame count")
    if envelope.first_sequence + len(envelope.packets) - 1 > 0xFFFFFFFF:
        raise ValueError("Media sequence overflow")
    try:
        burst_bytes = uuid.UUID(envelope.burst_id).bytes
    except (ValueError, AttributeError) as error:
        raise ValueError("Invalid transmission ID") from error

    encoded = bytearray(
        MEDIA_HEADER.pack(
            PROTOCOL_VERSION,
            envelope.media_type,
            burst_bytes,
            envelope.first_sequence,
            len(envelope.packets),
        )
    )
    for packet in envelope.packets:
        if not packet or len(packet) > MAX_OPUS_PACKET_BYTES:
            raise ValueError("Invalid Opus packet size")
        encoded.extend(PACKET_LENGTH.pack(len(packet)))
        encoded.extend(packet)
    if len(encoded) > MAX_MESSAGE_BYTES:
        raise ValueError("Media message is too large")
    return bytes(encoded)


def decode_media(message: bytes, *, expected_type: int | None = None) -> MediaEnvelope:
    """Decode a complete v4 media message and reject trailing or malformed data."""
    if len(message) <= MEDIA_HEADER.size or len(message) > MAX_MESSAGE_BYTES:
        raise ValueError("Invalid media message size")
    version, media_type, burst_bytes, first_sequence, frame_count = (
        MEDIA_HEADER.unpack_from(message)
    )
    if version != PROTOCOL_VERSION or media_type not in {UPLINK_MEDIA_TYPE, DOWNLINK_MEDIA_TYPE}:
        raise ValueError("Unsupported media message")
    if expected_type is not None and media_type != expected_type:
        raise ValueError("Unexpected media direction")
    if not 1 <= frame_count <= MAX_FRAMES_PER_MESSAGE:
        raise ValueError("Invalid media frame count")
    if first_sequence + frame_count - 1 > 0xFFFFFFFF:
        raise ValueError("Media sequence overflow")
    offset = MEDIA_HEADER.size
    packets: list[bytes] = []
    for _ in range(frame_count):
        if offset + PACKET_LENGTH.size > len(message):
            raise ValueError("Truncated media packet length")
        (packet_size,) = PACKET_LENGTH.unpack_from(message, offset)
        offset += PACKET_LENGTH.size
        if not 1 <= packet_size <= MAX_OPUS_PACKET_BYTES or offset + packet_size > len(message):
            raise ValueError("Invalid Opus packet size")
        packets.append(message[offset : offset + packet_size])
        offset += packet_size
    if offset != len(message):
        raise ValueError("Trailing media bytes")
    return MediaEnvelope(
        media_type=media_type,
        burst_id=str(uuid.UUID(bytes=burst_bytes)),
        first_sequence=first_sequence,
        packets=tuple(packets),
    )


def string(value: Any, max_length: int) -> str:
    if not isinstance(value, str) or not value or len(value) > max_length:
        raise ValueError
    return value


def integer(value: Any, minimum: int, maximum: int) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError
    return value
