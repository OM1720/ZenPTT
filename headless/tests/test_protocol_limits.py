"""Negative vectors derived from the normative v4 wire limits."""

import copy
import json
import struct
import uuid

import pytest

from zenptt_headless.protocol import decode_media, parse_control

ID = "11111111-1111-4111-8111-111111111111"
SNAPSHOT = {
    "type": "snapshot", "channel": "TEST", "member_id": ID, "resume_token": "token",
    "generation": 1, "channel_incarnation_id": ID, "revision": 0,
    "participant_count": 1, "eligible_from_index": 0, "next_burst_index": 0,
    "audio_policy": {"recovery_horizon_ms": 5000},
    "floor": {"burst_id": ID, "burst_index": 0, "owned": True},
}


@pytest.mark.parametrize("path,bad", [
    *((field, value) for field in ("member_id", "channel_incarnation_id", "floor.burst_id")
      for value in (None, 1, "bad", ID.upper().replace("1111", "ABCD", 1), ID.replace("-", ""))),
    *(("channel", value) for value in (None, 1, "", "Test", " TEST", "A..B", "A" * 257)),
    *(("resume_token", value) for value in (None, 1, "", "t" * 257)),
    *((field, value) for field in ("generation", "participant_count")
      for value in (None, True, 0, -1, 1.5, "1")),
    ("generation", 2**31),
    *((field, value) for field in ("revision", "eligible_from_index", "next_burst_index",
                                  "floor.burst_index")
      for value in (None, True, -1, 1.5, "1")),
    ("floor.burst_index", 2**31),
    *(("floor.owned", value) for value in (None, 0, 1, "true")),
    *(("floor", value) for value in ({}, [], True, {"burst_id": ID, "burst_index": 0})),
    *(("audio_policy", value) for value in (None, {}, [], {"recovery_horizon_ms": 5000, "x": 1})),
    *(("audio_policy.recovery_horizon_ms", value)
      for value in (None, True, 999, 1001, 60001, 5000.0, "5000")),
])
def test_snapshot_rejects_invalid_nested_contract_values(path, bad):
    message = copy.deepcopy(SNAPSHOT)
    fields = path.split(".")
    target = message
    for field in fields[:-1]:
        target = target[field]
    target[fields[-1]] = bad
    with pytest.raises(ValueError):
        parse_control(json.dumps(message))


@pytest.mark.parametrize("message,field,bad", [
    *(({"type": "pong", "id": 0, "sent_at_ms": 0}, field, bad)
      for field, maximum in (("id", 2**31-1), ("sent_at_ms", 2**63-1))
      for bad in (-1, maximum+1, None, True, "0", 0.5)),
    *(({"type": "uplink_ack", "burst_id": ID, "next_sequence": 0}, "next_sequence", bad)
      for bad in (-1, 2**32, None, True, "0", 0.5)),
    *(({"type": "ptt_granted", "request_id": "r", "burst_id": ID,
        "burst_index": 0, "lease_remaining_ms": 0}, "request_id", bad)
      for bad in (None, True, "", "r"*129)),
    *(({"type": "burst_sealed", "burst_id": ID, "burst_index": 0,
        "final_next_sequence": 1, "reason": "complete"}, "final_next_sequence", bad)
      for bad in (None, True, -1, 2**32, "1", 1.5)),
    *(({"type": "ptt_ended", "burst_id": ID, "burst_index": 0,
        "final_next_sequence": None, "reason": "released", "state": "draining"}, field, bad)
      for field in ("state", "reason") for bad in (None, 1, "unknown")),
])
def test_control_rejects_numeric_string_and_nullable_violations(message, field, bad):
    message = {**message, field: bad}
    with pytest.raises(ValueError):
        parse_control(json.dumps(message))


@pytest.mark.parametrize("kind,fields", [
    ("resume_rejected", {}), ("ptt_denied", {"request_id": "r"}),
    ("audio_rejected", {"burst_id": ID, "first_sequence": 0, "next_sequence": 1}),
    ("burst_released", {"burst_id": ID, "burst_index": 0}),
    ("burst_sealed", {"burst_id": ID, "burst_index": 0, "final_next_sequence": 1}),
    ("listen_reset", {"burst_index": 0, "next_sequence": 0}),
])
def test_unknown_reasons_are_rejected(kind, fields):
    with pytest.raises(ValueError):
        parse_control(json.dumps({"type": kind, "reason": "unknown", **fields}))


@pytest.mark.parametrize("ranges", [
    None, {}, [None], [{"first_sequence": 0, "count": 0}],
    [{"first_sequence": -1, "count": 1}], [{"first_sequence": True, "count": 1}],
    [{"first_sequence": 0, "count": True}], [{"first_sequence": 0, "count": 1, "x": 1}],
    [{"first_sequence": 2**32, "count": 1}], [{"first_sequence": 2**32-1, "count": 2}],
    [{"first_sequence": 1, "count": 2}, {"first_sequence": 2, "count": 1}],
    [{"first_sequence": 2, "count": 1}, {"first_sequence": 0, "count": 1}],
])
def test_invalid_authoritative_ranges_are_rejected(ranges):
    with pytest.raises(ValueError):
        parse_control(json.dumps({"type": "burst_gaps", "burst_id": ID,
                                  "burst_index": 0, "ranges": ranges}))


@pytest.mark.parametrize("literal", ["NaN", "Infinity", "-Infinity"])
def test_non_json_numeric_literals_are_rejected(literal):
    with pytest.raises(ValueError):
        parse_control('{"type":"pong","id":0,"sent_at_ms":' + literal + '}')


def test_duplicate_nested_keys_and_missing_snapshot_fields_are_rejected():
    raw = json.dumps(SNAPSHOT).replace('"recovery_horizon_ms": 5000',
                                      '"recovery_horizon_ms": 5000, "recovery_horizon_ms": 1000')
    with pytest.raises(ValueError):
        parse_control(raw)
    for field in SNAPSHOT:
        with pytest.raises(ValueError):
            parse_control(json.dumps({key: value for key, value in SNAPSHOT.items() if key != field}))


def envelope(count, payload, first=0):
    return struct.pack("!BB16sIH", 4, 2, uuid.UUID(ID).bytes, first, count) + payload


@pytest.mark.parametrize("raw", [
    envelope(0, b"\0\1x"), envelope(51, b"\0\1x" * 51),
    envelope(1, b"\0\0"), envelope(1, struct.pack("!H", 1276) + bytes(1276)),
    envelope(1, b"\0"), envelope(2, b"\0\1x"),
    envelope(2, b"\0\1x" * 2, first=2**32-1),
    envelope(4, (struct.pack("!H", 1275) + bytes(1275))*4),
])
def test_binary_wire_limits_are_enforced(raw):
    with pytest.raises(ValueError):
        decode_media(raw)


def test_valid_boundaries_and_nullable_terminal_are_accepted():
    for horizon in (1000, 60000):
        message = {**SNAPSHOT, "resume_token": "t"*256, "channel": "A"*256,
                   "generation": 2**31-1, "audio_policy": {"recovery_horizon_ms": horizon}}
        assert parse_control(json.dumps(message)) == message
    terminal = {"type": "ptt_ended", "burst_id": ID, "burst_index": 0,
                "state": "draining", "reason": "released", "final_next_sequence": None}
    assert parse_control(json.dumps(terminal)) == terminal
    assert len(decode_media(envelope(50, b"\0\1x" * 50))[2]) == 50
    assert decode_media(envelope(1, b"\0\1x", first=2**32-1))[1] == 2**32-1
