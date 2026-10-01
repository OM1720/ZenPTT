import struct

import pytest

from app.protocol import (
    DOWNLINK_MEDIA_TYPE,
    MAX_FRAMES_PER_MESSAGE,
    MAX_MESSAGE_BYTES,
    MAX_OPUS_PACKET_BYTES,
    MEDIA_HEADER,
    MediaEnvelope,
    decode_media,
    encode_media,
)
from tests.helpers import TEST_TRANSMISSION_ID, audio_message


# Literal wire vectors, independent of MEDIA_HEADER and the production encoder.
VECTOR_ID = "01234567-89ab-cdef-0123-456789abcdef"
VECTORS = [
    ("04020123456789abcdef0123456789abcdef01020304000200030180ff0002007f",
     0x01020304, (b"\x01\x80\xff", b"\x00\x7f")),
    ("04020123456789abcdef0123456789abcdefffffffff00010001ff",
     0xFFFFFFFF, (b"\xff",)),
]


@pytest.mark.parametrize(("hex_packet", "sequence", "packets"), VECTORS)
def test_fixed_wire_vectors(hex_packet, sequence, packets) -> None:
    wire = bytes.fromhex(hex_packet)
    expected = MediaEnvelope(DOWNLINK_MEDIA_TYPE, VECTOR_ID, sequence, packets)
    assert encode_media(expected) == wire
    assert decode_media(wire, expected_type=DOWNLINK_MEDIA_TYPE) == expected
    for length in range(len(wire)):
        with pytest.raises(ValueError):
            decode_media(wire[:length], expected_type=DOWNLINK_MEDIA_TYPE)
    with pytest.raises(ValueError):
        decode_media(wire + b"\x00", expected_type=DOWNLINK_MEDIA_TYPE)


def test_media_round_trip_preserves_identity_order_and_packets() -> None:
    encoded = encode_media(
        MediaEnvelope(
            DOWNLINK_MEDIA_TYPE,
            TEST_TRANSMISSION_ID,
            42,
            (b"one", b"two"),
        )
    )

    decoded = decode_media(encoded, expected_type=DOWNLINK_MEDIA_TYPE)

    assert decoded.burst_id == TEST_TRANSMISSION_ID
    assert decoded.first_sequence == 42
    assert decoded.packets == (b"one", b"two")


@pytest.mark.parametrize(
    "envelope",
    [
        MediaEnvelope(9, TEST_TRANSMISSION_ID, 0, (b"x",)),
        MediaEnvelope(DOWNLINK_MEDIA_TYPE, TEST_TRANSMISSION_ID, -1, (b"x",)),
        MediaEnvelope(DOWNLINK_MEDIA_TYPE, TEST_TRANSMISSION_ID, 0, ()),
        MediaEnvelope(DOWNLINK_MEDIA_TYPE, TEST_TRANSMISSION_ID, 0xFFFFFFFF, (b"x", b"y")),
        MediaEnvelope(DOWNLINK_MEDIA_TYPE, "not-a-uuid", 0, (b"x",)),
        MediaEnvelope(DOWNLINK_MEDIA_TYPE, TEST_TRANSMISSION_ID, 0, (b"",)),
        MediaEnvelope(
            DOWNLINK_MEDIA_TYPE,
            TEST_TRANSMISSION_ID,
            0,
            (b"x" * (MAX_OPUS_PACKET_BYTES + 1),),
        ),
        MediaEnvelope(
            DOWNLINK_MEDIA_TYPE,
            TEST_TRANSMISSION_ID,
            0,
            (b"x" * MAX_OPUS_PACKET_BYTES,) * 4,
        ),
    ],
)
def test_media_encoder_rejects_every_bounded_field_violation(envelope: MediaEnvelope) -> None:
    with pytest.raises(ValueError):
        encode_media(envelope)


def test_media_decoder_rejects_direction_count_overflow_and_truncated_lengths() -> None:
    valid = audio_message(0)
    with pytest.raises(ValueError):
        decode_media(valid, expected_type=DOWNLINK_MEDIA_TYPE)
    invalid = [
        MEDIA_HEADER.pack(
            4,
            DOWNLINK_MEDIA_TYPE,
            bytes(16),
            0,
            MAX_FRAMES_PER_MESSAGE + 1,
        )
        + b"xx",
        MEDIA_HEADER.pack(4, DOWNLINK_MEDIA_TYPE, bytes(16), 0xFFFFFFFF, 2) + b"xx",
        MEDIA_HEADER.pack(4, DOWNLINK_MEDIA_TYPE, bytes(16), 0, 2)
        + struct.pack("!H", 1)
        + b"x",
    ]
    for message in invalid:
        with pytest.raises(ValueError):
            decode_media(message)


@pytest.mark.parametrize(
    "message",
    [
        b"",
        b"short",
        audio_message(1)[:-1],
        bytes([1]) + audio_message(1)[1:],
        bytes([4, 9]) + audio_message(1)[2:],
        audio_message(1) + b"trailing",
        b"x" * (MAX_MESSAGE_BYTES + 1),
        MEDIA_HEADER.pack(4, 1, b"\0" * 16, 0, 0),
    ],
)
def test_media_rejects_invalid_message(message: bytes) -> None:
    with pytest.raises(ValueError):
        decode_media(message)
