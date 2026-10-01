"""Independent ZenPTT v4 framing and strict server-control validation."""

from __future__ import annotations

import json
import re
import struct
import uuid
from typing import Any

VERSION = 4
SUBPROTOCOL = "zenptt.v4"
UPLINK = 1
DOWNLINK = 2
MAX_MESSAGE_BYTES = 4_096
MAX_PACKET_BYTES = 1_275
MAX_PACKETS = 50
HEADER = struct.Struct("!BB16sIH")
LENGTH = struct.Struct("!H")
MAX_BURST_INDEX = 0x7FFFFFFF
MAX_SEQUENCE = 0xFFFFFFFF
MAX_PING_ID = 0x7FFFFFFF
MAX_TIMESTAMP = 0x7FFFFFFFFFFFFFFF
CHANNEL_PATTERN = re.compile(r"[A-Z0-9]+(?:\.[A-Z0-9]+)*")

SERVER_FIELDS = {
    "snapshot": {"type", "channel", "member_id", "resume_token", "generation", "channel_incarnation_id", "revision", "participant_count", "eligible_from_index", "next_burst_index", "audio_policy", "floor"},
    "resume_rejected": {"type", "reason"},
    "channel_state": {"type", "revision", "participant_count", "next_burst_index", "floor"},
    "ptt_granted": {"type", "request_id", "burst_id", "burst_index", "lease_remaining_ms"},
    "ptt_denied": {"type", "request_id", "reason"},
    "uplink_ack": {"type", "burst_id", "next_sequence"},
    "audio_rejected": {"type", "burst_id", "first_sequence", "next_sequence", "reason"},
    "burst_started": {"type", "burst_id", "burst_index"},
    "burst_released": {"type", "burst_id", "burst_index", "reason"},
    "burst_gaps": {"type", "burst_id", "burst_index", "ranges"},
    "burst_sealed": {"type", "burst_id", "burst_index", "final_next_sequence", "reason"},
    "listen_reset": {"type", "burst_index", "next_sequence", "reason"},
    "ptt_ended": {"type", "burst_id", "burst_index", "state", "reason", "final_next_sequence"},
    "pong": {"type", "id", "sent_at_ms"},
    "error": {"type", "code", "message"},
}


def parse_control(raw: str) -> dict[str, Any]:
    if not isinstance(raw, str):
        raise ValueError("Control message must be text")
    if len(raw.encode("utf-8")) > MAX_MESSAGE_BYTES:
        raise ValueError("Control message is too large")

    def object_without_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate control field")
            result[key] = value
        return result

    def reject_constant(_value: str) -> None:
        raise ValueError("Invalid JSON number")

    try:
        value = json.loads(
            raw,
            object_pairs_hook=object_without_duplicates,
            parse_constant=reject_constant,
        )
    except (json.JSONDecodeError, ValueError) as error:
        raise ValueError("Invalid control JSON") from error
    if not isinstance(value, dict) or not isinstance(value.get("type"), str):
        raise ValueError("Invalid control message")
    expected = SERVER_FIELDS.get(value["type"])
    if expected is None or set(value) != expected:
        raise ValueError("Invalid control fields")
    _validate_control(value)
    return value


def _integer(value: Any, minimum: int = 0) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= minimum


def _uuid(value: Any) -> bool:
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except (ValueError, AttributeError, TypeError):
        return False


def _floor(value: Any) -> bool:
    return value is None or (
        isinstance(value, dict)
        and set(value) == {"burst_id", "burst_index", "owned"}
        and _uuid(value["burst_id"])
        and _integer(value["burst_index"])
        and value["burst_index"] <= MAX_BURST_INDEX
        and isinstance(value["owned"], bool)
    )


def _validate_control(message: dict[str, Any]) -> None:
    kind = message["type"]
    uuid_fields = ({"burst_id"} & set(message))
    if any(not _uuid(message[field]) for field in uuid_fields):
        raise ValueError("Invalid control UUID")
    limits: dict[str, tuple[int, int | None]] = {
        "revision": (0, None),
        "eligible_from_index": (0, None),
        "next_burst_index": (0, None),
        "burst_index": (0, MAX_BURST_INDEX),
        "lease_remaining_ms": (0, None),
        "next_sequence": (0, MAX_SEQUENCE),
        "first_sequence": (0, MAX_SEQUENCE),
        "id": (0, MAX_PING_ID),
        "sent_at_ms": (0, MAX_TIMESTAMP),
    }
    for field, (minimum, maximum) in limits.items():
        if field in message and not (
            _integer(message[field], minimum)
            and (maximum is None or message[field] <= maximum)
        ):
            raise ValueError("Invalid control integer")
    if "final_next_sequence" in message:
        value = message["final_next_sequence"]
        if not (
            kind == "ptt_ended" and value is None
        ) and not (_integer(value) and value <= MAX_SEQUENCE):
            raise ValueError("Invalid final sequence")
    if "request_id" in message and (
        not isinstance(message["request_id"], str)
        or not message["request_id"]
        or len(message["request_id"]) > 128
    ):
        raise ValueError("Invalid request ID")
    if "floor" in message and not _floor(message["floor"]):
        raise ValueError("Invalid floor")
    if kind == "snapshot":
        channel = message["channel"]
        token = message["resume_token"]
        if not (
            isinstance(channel, str)
            and len(channel) <= 256
            and CHANNEL_PATTERN.fullmatch(channel)
            and isinstance(token, str)
            and 0 < len(token) <= 256
        ):
            raise ValueError("Invalid snapshot strings")
        if not (
            _integer(message["generation"], 1)
            and message["generation"] <= MAX_BURST_INDEX
            and _integer(message["participant_count"], 1)
        ):
            raise ValueError("Invalid snapshot counters")
        policy = message["audio_policy"]
        if not (
            isinstance(policy, dict)
            and set(policy) == {"recovery_horizon_ms"}
            and _integer(policy["recovery_horizon_ms"], 1_000)
            and policy["recovery_horizon_ms"] <= 60_000
            and policy["recovery_horizon_ms"] % 20 == 0
        ):
            raise ValueError("Invalid audio policy")
        if not _uuid(message["member_id"]) or not _uuid(message["channel_incarnation_id"]):
            raise ValueError("Invalid snapshot UUID")
    if kind == "burst_gaps":
        ranges = message["ranges"]
        if not isinstance(ranges, list) or any(
            not isinstance(item, dict)
            or set(item) != {"first_sequence", "count"}
            or not _integer(item["first_sequence"])
            or not _integer(item["count"], 1)
            or item["first_sequence"] > MAX_SEQUENCE
            or item["first_sequence"] + item["count"] > MAX_SEQUENCE + 1
            for item in ranges
        ):
            raise ValueError("Invalid gap ranges")
        previous_end = 0
        for index, item in enumerate(ranges):
            if index and item["first_sequence"] < previous_end:
                raise ValueError("Invalid gap ordering")
            previous_end = item["first_sequence"] + item["count"]
    if kind == "audio_rejected" and message["next_sequence"] < message["first_sequence"]:
        raise ValueError("Invalid rejected range")
    if kind == "channel_state" and not _integer(message["participant_count"], 1):
        raise ValueError("Invalid participant count")
    string_fields = {"reason", "state", "code", "message"}
    if any(not isinstance(message[field], str) for field in string_fields & set(message)):
        raise ValueError("Invalid control string")
    enums = {
        "resume_rejected": {"invalid_token", "expired", "stale_generation"},
        "ptt_denied": {"channel_busy", "invalid_state", "server_busy"},
        "audio_rejected": {
            "unknown_burst",
            "expired",
            "invalid_range",
            "payload_mismatch",
            "server_busy",
        },
        "burst_released": {
            "released",
            "canceled",
            "lease_expired",
            "duration_limit",
            "member_left",
            "echo",
        },
        "burst_sealed": {"complete", "expired", "canceled", "member_left"},
        "listen_reset": {"expired", "not_eligible", "invalid_cursor"},
    }
    if kind in enums and message["reason"] not in enums[kind]:
        raise ValueError("Invalid control reason")
    if kind == "ptt_ended" and message["state"] not in {"draining", "sealed"}:
        raise ValueError("Invalid terminal state")
    if kind == "ptt_ended" and message["reason"] not in {
        "released",
        "complete",
        "expired",
        "canceled",
        "lease_expired",
        "duration_limit",
    }:
        raise ValueError("Invalid terminal reason")
    if kind == "error" and message["code"] not in {
        "invalid_message",
        "invalid_channel",
        "channel_full",
        "not_joined",
        "invalid_state",
        "server_busy",
        "internal_error",
    }:
        raise ValueError("Invalid error code")


def encode_media(media_type: int, burst_id: str, first_sequence: int, packets: tuple[bytes, ...]) -> bytes:
    if media_type not in {UPLINK, DOWNLINK} or not 1 <= len(packets) <= MAX_PACKETS:
        raise ValueError("Invalid media envelope")
    if not 0 <= first_sequence <= 0xFFFFFFFF or first_sequence + len(packets) - 1 > 0xFFFFFFFF:
        raise ValueError("Invalid media sequence")
    encoded = bytearray(HEADER.pack(VERSION, media_type, uuid.UUID(burst_id).bytes, first_sequence, len(packets)))
    for packet in packets:
        if not 1 <= len(packet) <= MAX_PACKET_BYTES:
            raise ValueError("Invalid Opus packet")
        encoded.extend(LENGTH.pack(len(packet)))
        encoded.extend(packet)
    if len(encoded) > MAX_MESSAGE_BYTES:
        raise ValueError("Media message is too large")
    return bytes(encoded)


def decode_media(raw: bytes, expected_type: int = DOWNLINK) -> tuple[str, int, tuple[bytes, ...]]:
    if not HEADER.size < len(raw) <= MAX_MESSAGE_BYTES:
        raise ValueError("Invalid media size")
    version, media_type, burst_bytes, first, count = HEADER.unpack_from(raw)
    if version != VERSION or media_type != expected_type or not 1 <= count <= MAX_PACKETS:
        raise ValueError("Invalid media header")
    if first + count - 1 > 0xFFFFFFFF:
        raise ValueError("Media sequence overflow")
    offset = HEADER.size
    packets = []
    for _ in range(count):
        if offset + LENGTH.size > len(raw):
            raise ValueError("Truncated media length")
        (size,) = LENGTH.unpack_from(raw, offset)
        offset += LENGTH.size
        if not 1 <= size <= MAX_PACKET_BYTES or offset + size > len(raw):
            raise ValueError("Invalid media packet")
        packets.append(raw[offset : offset + size])
        offset += size
    if offset != len(raw):
        raise ValueError("Trailing media bytes")
    return str(uuid.UUID(bytes=burst_bytes)), first, tuple(packets)
