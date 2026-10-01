import json

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.protocol import MAX_MESSAGE_BYTES
from tests.helpers import audio_message

SUBPROTOCOLS = ["zenptt.v4"]


@pytest.mark.parametrize(
    "message",
    [
        {},
        [],
        {"type": "unknown"},
        {"type": "join"},
        {"type": "join", "channel": "ROOM", "extra": True},
        {"type": "ptt_request", "request_id": "",},
        {"type": "ptt_request", "request_id": "x" * 129},
        {"type": "ptt_request", "request_id": 1},
        {"type": "ptt_request", "request_id": "one", "audio_retention_ms": 30_000},
        {"type": "listen", "burst_index": -1, "next_sequence": 0},
        {"type": "burst_end", "burst_id": "bad", "outcome": "complete"},
        {"type": "ping", "id": "1", "sent_at_ms": 1},
        {"type": "disconnect", "extra": True},
    ],
)
def test_invalid_control_returns_stable_error_and_connection_survives(message) -> None:
    with TestClient(create_app()) as client:
        with client.websocket_connect("/ws", subprotocols=SUBPROTOCOLS) as socket:
            socket.send_text(json.dumps(message))
            assert socket.receive_json() == {
                "type": "error",
                "code": "invalid_message",
                "message": "Invalid message",
            }
            socket.send_json({"type": "ping", "id": 7, "sent_at_ms": 9})
            assert socket.receive_json() == {"type": "pong", "id": 7, "sent_at_ms": 9}


def test_oversized_json_and_invalid_binary_are_rejected_without_disconnect() -> None:
    oversized = json.dumps({"type": "join", "channel": "A" * MAX_MESSAGE_BYTES})
    with TestClient(create_app()) as client:
        with client.websocket_connect("/ws", subprotocols=SUBPROTOCOLS) as socket:
            socket.send_text(oversized)
            assert socket.receive_json()["code"] == "invalid_message"
            socket.send_bytes(b"not-an-audio-frame")
            assert socket.receive_json()["code"] == "invalid_message"
            socket.send_json({"type": "ping", "id": 1, "sent_at_ms": 2})
            assert socket.receive_json()["type"] == "pong"


def test_actions_before_join_and_duplicate_join_have_stable_errors() -> None:
    with TestClient(create_app()) as client:
        with client.websocket_connect("/ws", subprotocols=SUBPROTOCOLS) as socket:
            socket.send_json({"type": "ptt_request", "request_id": "one"})
            assert socket.receive_json()["code"] == "not_joined"
            socket.send_json({"type": "join", "channel": "ROOM"})
            assert socket.receive_json()["type"] == "snapshot"
            socket.receive_json()  # channel_state
            socket.send_json({"type": "join", "channel": "OTHER"})
            assert socket.receive_json()["code"] == "invalid_message"


def test_audio_before_join_has_stable_error_and_connection_survives() -> None:
    with TestClient(create_app()) as client:
        with client.websocket_connect("/ws", subprotocols=SUBPROTOCOLS) as socket:
            socket.send_bytes(audio_message(0))
            assert socket.receive_json()["code"] == "not_joined"
            socket.send_json({"type": "ping", "id": 7, "sent_at_ms": 9})
            assert socket.receive_json()["type"] == "pong"


def test_v3_subprotocol_is_rejected() -> None:
    with TestClient(create_app()) as client:
        with pytest.raises(Exception):
            with client.websocket_connect("/ws", subprotocols=["zenptt.v3"]):
                pass
