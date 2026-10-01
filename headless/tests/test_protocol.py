import json
import struct
import uuid

import pytest

from zenptt_headless.protocol import DOWNLINK, HEADER, decode_media, encode_media, parse_control

BURST_ID = "11111111-1111-4111-8111-111111111111"


def test_media_vector_is_independent_and_network_ordered() -> None:
    encoded = encode_media(DOWNLINK, BURST_ID, 7, (b"one", b"two"))
    expected = struct.pack("!BB16sIH", 4, 2, uuid.UUID(BURST_ID).bytes, 7, 2)
    expected += struct.pack("!H3sH3s", 3, b"one", 3, b"two")
    assert encoded == expected
    assert decode_media(expected) == (BURST_ID, 7, (b"one", b"two"))


@pytest.mark.parametrize(
    "raw",
    [
        b"",
        HEADER.pack(3, 2, uuid.UUID(BURST_ID).bytes, 0, 1) + b"\x00\x01x",
        HEADER.pack(4, 1, uuid.UUID(BURST_ID).bytes, 0, 1) + b"\x00\x01x",
        HEADER.pack(4, 2, uuid.UUID(BURST_ID).bytes, 0, 1) + b"\x00\x02x",
        HEADER.pack(4, 2, uuid.UUID(BURST_ID).bytes, 0, 1) + b"\x00\x01xx",
    ],
)
def test_media_rejects_malformed_messages(raw: bytes) -> None:
    with pytest.raises(ValueError):
        decode_media(raw)


def test_control_requires_exact_catalog_fields() -> None:
    assert parse_control('{"type":"pong","id":1,"sent_at_ms":2}')["id"] == 1
    with pytest.raises(ValueError):
        parse_control('{"type":"pong","id":1,"sent_at_ms":2,"extra":true}')
    with pytest.raises(ValueError):
        parse_control('{"type":"unknown"}')
    with pytest.raises(ValueError):
        parse_control('{"type":"pong","id":true,"sent_at_ms":2}')


@pytest.mark.parametrize(
    "message",
    [
        {
            "type": "snapshot",
            "channel": "TEST",
            "member_id": "22222222-2222-4222-8222-222222222222",
            "resume_token": "token",
            "generation": 1,
            "channel_incarnation_id": "33333333-3333-4333-8333-333333333333",
            "revision": 0,
            "participant_count": 1,
            "eligible_from_index": 0,
            "next_burst_index": 0,
            "audio_policy": {"recovery_horizon_ms": 5_000},
            "floor": None,
        },
        {"type": "resume_rejected", "reason": "expired"},
        {
            "type": "channel_state",
            "revision": 1,
            "participant_count": 1,
            "next_burst_index": 2,
            "floor": {"burst_id": BURST_ID, "burst_index": 1, "owned": False},
        },
        {
            "type": "ptt_granted",
            "request_id": "request",
            "burst_id": BURST_ID,
            "burst_index": 1,
            "lease_remaining_ms": 5_000,
        },
        {"type": "ptt_denied", "request_id": "request", "reason": "channel_busy"},
        {"type": "uplink_ack", "burst_id": BURST_ID, "next_sequence": 2},
        {
            "type": "audio_rejected",
            "burst_id": BURST_ID,
            "first_sequence": 1,
            "next_sequence": 2,
            "reason": "expired",
        },
        {"type": "burst_started", "burst_id": BURST_ID, "burst_index": 1},
        {
            "type": "burst_released",
            "burst_id": BURST_ID,
            "burst_index": 1,
            "reason": "released",
        },
        {
            "type": "burst_gaps",
            "burst_id": BURST_ID,
            "burst_index": 1,
            "ranges": [{"first_sequence": 2, "count": 3}],
        },
        {
            "type": "burst_sealed",
            "burst_id": BURST_ID,
            "burst_index": 1,
            "final_next_sequence": 5,
            "reason": "complete",
        },
        {
            "type": "listen_reset",
            "burst_index": 1,
            "next_sequence": 5,
            "reason": "expired",
        },
        {
            "type": "ptt_ended",
            "burst_id": BURST_ID,
            "burst_index": 1,
            "state": "draining",
            "reason": "released",
            "final_next_sequence": None,
        },
        {"type": "pong", "id": 7, "sent_at_ms": 9},
        {"type": "error", "code": "invalid_message", "message": "Invalid message"},
    ],
)
def test_control_accepts_every_server_message_shape(message: dict) -> None:
    assert parse_control(json.dumps(message)) == message


@pytest.mark.parametrize(
    "raw",
    [
        '{"type":"pong","id":1,"id":2,"sent_at_ms":3}',
        '{"type":"pong","id":NaN,"sent_at_ms":3}',
        f'{{"type":"uplink_ack","burst_id":"{BURST_ID}","next_sequence":null}}',
        f'{{"type":"ptt_denied","request_id":"{"x" * 129}","reason":"channel_busy"}}',
        f'{{"type":"ptt_ended","burst_id":"{BURST_ID}","burst_index":0,'
        '"state":"sealed","reason":"invalid","final_next_sequence":1}',
        f'{{"type":"audio_rejected","burst_id":"{BURST_ID}","first_sequence":2,'
        '"next_sequence":1,"reason":"expired"}',
    ],
)
def test_control_rejects_invalid_json_values_and_constraints(raw: str) -> None:
    with pytest.raises(ValueError):
        parse_control(raw)
