import uuid

from fastapi.testclient import TestClient

from app.main import create_app
from app.protocol import DOWNLINK_MEDIA_TYPE, decode_media
from tests.helpers import audio_message

SUBPROTOCOLS = ["zenptt.v4"]


def connect(client: TestClient):
    return client.websocket_connect("/ws", subprotocols=SUBPROTOCOLS)


def join(socket, channel: str = "ROOM") -> dict:
    socket.send_json({"type": "join", "channel": channel})
    snapshot = socket.receive_json()
    assert snapshot["type"] == "snapshot"
    assert snapshot["audio_policy"] == {"recovery_horizon_ms": 5_000}
    assert socket.receive_json()["type"] == "channel_state"
    return snapshot


def request_floor(socket, request_id: str = "request") -> dict:
    socket.send_json({"type": "ptt_request", "request_id": request_id})
    result = socket.receive_json()
    assert result["type"] in {"ptt_granted", "ptt_denied"}
    return result


def end_burst(socket, burst_id: str, final_next_sequence: int) -> None:
    socket.send_json(
        {"type": "burst_end", "burst_id": burst_id, "final_next_sequence": final_next_sequence}
    )


def test_two_clients_receive_live_audio_and_release_before_channel_state() -> None:
    with TestClient(create_app()) as client:
        with connect(client) as sender, connect(client) as listener:
            join(sender)
            join(listener)
            assert sender.receive_json()["type"] == "channel_state"
            listener.send_json({"type": "listen", "burst_index": 0, "next_sequence": 0})
            grant = request_floor(sender)
            burst_id = grant["burst_id"]
            assert listener.receive_json()["type"] == "burst_started"
            assert sender.receive_json()["type"] == "channel_state"
            assert listener.receive_json()["type"] == "channel_state"

            sender.send_bytes(audio_message(0, payload=b"SECRET_AUDIO", burst_id=burst_id))
            ack = sender.receive_json()
            assert ack == {
                "type": "uplink_ack",
                "burst_id": burst_id,
                "next_sequence": 1,
            }
            media = decode_media(listener.receive_bytes(), expected_type=DOWNLINK_MEDIA_TYPE)
            assert media.packets == (b"SECRET_AUDIO",)

            end_burst(sender, burst_id, 1)
            assert sender.receive_json()["type"] == "ptt_ended"
            assert listener.receive_json()["type"] == "burst_released"
            assert listener.receive_json()["type"] == "burst_sealed"
            assert sender.receive_json()["floor"] is None
            assert listener.receive_json()["floor"] is None


def test_sparse_ranges_are_accepted_and_acknowledged_exactly() -> None:
    with TestClient(create_app()) as client:
        with connect(client) as sender:
            join(sender)
            grant = request_floor(sender)
            sender.receive_json()
            sender.send_bytes(audio_message(2, payload=b"three", burst_id=grant["burst_id"]))
            ack = sender.receive_json()
            assert ack["next_sequence"] == 0
            sender.send_bytes(
                audio_message(0, burst_id=grant["burst_id"], packets=(b"one", b"two"))
            )
            ack = sender.receive_json()
            assert ack["next_sequence"] == 3


def test_conflicting_duplicate_payload_is_rejected() -> None:
    with TestClient(create_app()) as client:
        with connect(client) as sender:
            join(sender)
            grant = request_floor(sender)
            sender.receive_json()
            sender.send_bytes(audio_message(0, payload=b"one", burst_id=grant["burst_id"]))
            sender.receive_json()
            sender.send_bytes(audio_message(0, payload=b"other", burst_id=grant["burst_id"]))
            rejected = sender.receive_json()
            assert rejected["reason"] == "payload_mismatch"
            assert (rejected["first_sequence"], rejected["next_sequence"]) == (0, 1)


def test_late_joiner_is_reset_past_an_ineligible_burst() -> None:
    with TestClient(create_app()) as client:
        with connect(client) as sender:
            join(sender)
            grant = request_floor(sender)
            sender.receive_json()
            with connect(client) as late:
                snapshot = join(late)
                assert snapshot["floor"] is not None
                late.send_json({"type": "listen", "burst_index": 0, "next_sequence": 0})
                reset = late.receive_json()
                assert reset["type"] == "listen_reset"
                assert reset["burst_index"] == 1
            end_burst(sender, grant["burst_id"], 0)


def test_higher_resume_generation_fences_old_transport() -> None:
    with TestClient(create_app()) as client:
        with connect(client) as original:
            snapshot = join(original)
            with connect(client) as replacement:
                replacement.send_json(
                    {"type": "resume", "resume_token": snapshot["resume_token"], "generation": 2}
                )
                assert replacement.receive_json()["generation"] == 2
                replacement.receive_json()
                with connect(client) as stale:
                    stale.send_json(
                        {
                            "type": "resume",
                            "resume_token": snapshot["resume_token"],
                            "generation": 2,
                        }
                    )
                    assert stale.receive_json() == {
                        "type": "resume_rejected",
                        "reason": "stale_generation",
                    }


def test_audio_payload_and_resume_token_are_never_logged(caplog) -> None:
    with TestClient(create_app()) as client:
        with connect(client) as sender:
            snapshot = join(sender)
            grant = request_floor(sender, str(uuid.uuid4()))
            sender.receive_json()
            with caplog.at_level("INFO", logger="zenptt.server"):
                sender.send_bytes(
                    audio_message(0, payload=b"SECRET_AUDIO", burst_id=grant["burst_id"])
                )
                sender.receive_json()
    logs = "\n".join(record.getMessage() for record in caplog.records)
    assert "SECRET_AUDIO" not in logs
    assert snapshot["resume_token"] not in logs
