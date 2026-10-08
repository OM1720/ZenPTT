"""Checks lease variant identity and three-party evidence."""

import hashlib
import zipfile

import pytest

from lease import summarize, verify_package


def test_lease_package_must_match_installed_hash_and_duration(tmp_path) -> None:
    archive = tmp_path / "zenptt-server.zip"
    with zipfile.ZipFile(archive, "w") as bundle:
        bundle.writestr("server/app/session.py", "FLOOR_LEASE_SECONDS = 3.0\n")
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()

    verify_package(tmp_path, 3, digest)
    with pytest.raises(RuntimeError, match="different floor lease"):
        verify_package(tmp_path, 2, digest)
    with pytest.raises(RuntimeError, match="differs from the selected"):
        verify_package(tmp_path, 3, "0" * 64)


def test_lease_summary_maps_competition_to_the_second_sender_burst() -> None:
    sender = [
        {"event": "send_start", "ordinal": 1, "planned_frames": 1000},
        {"event": "grant", "ordinal": 1, "burst_id": "sender-first", "at": 1.0},
        {"event": "send_start", "ordinal": 2, "planned_frames": 1000},
        {"event": "grant", "ordinal": 2, "burst_id": "sender-second", "at": 25.0},
        {"event": "control", "type": "ptt_ended", "state": "draining",
         "burst_id": "sender-second", "at": 32.1},
    ]
    receiver = [
        {"event": "received", "burst_id": "sender-first", "frames": 1000},
        {"event": "received", "burst_id": "contender", "frames": 1},
        {"event": "received", "burst_id": "sender-second", "frames": 405,
         "loss_ranges": [[403, 2]]},
    ]
    contender = [
        {"event": "contender_request_start", "at": 30.0},
        {"event": "socket_send", "type": "ptt_request", "request_id": "request-one",
         "at": 30.1},
        {"event": "socket_send", "type": "ptt_request", "request_id": "request-one",
         "at": 31.1},
        {"event": "grant", "ordinal": 1, "burst_id": "contender", "at": 32.2},
    ]
    log = "\n".join([
        "grant session=one burst=0 burst_id=sender-first at_ms=1000 at_unix_ms=1000",
        "floor_released burst_id=sender-first reason=released at_ms=21000 at_unix_ms=21000",
        "grant session=one burst=1 burst_id=sender-second at_ms=25000 at_unix_ms=25000",
        "floor_request session=two channel=ROOM request_id=request-one "
        "at_ms=30000 at_unix_ms=30000",
        "floor_request session=two channel=ROOM request_id=request-one "
        "at_ms=31000 at_unix_ms=31000",
        "floor_expired burst_id=sender-second reason=lease_expired "
        "at_ms=32000 at_unix_ms=32000",
        "grant session=two burst=2 burst_id=contender at_ms=32200 at_unix_ms=32200",
        "floor_released burst_id=contender reason=released at_ms=32300 at_unix_ms=32300",
    ])

    result = summarize(sender, receiver, contender, log, None)

    assert result["delivered_frames"] == {1: 1000, 2: 403}
    assert result["contender_request_times"] == [30.1, 31.1]
    assert result["contender_wait_s"] == 2.2
    assert result["sender_release_observed_at"] == 32.1
    assert result["server_lease_expired_at_ms"] == 32000
    assert len(result["server_contender_requests"]) == 2
    assert result["server_evidence_complete"]
    assert result["server_no_overlap"]
    assert result["server_contender_wait_s"] == 2.2
    assert result["server_release_to_grant_s"] == 0.2

    overlap = summarize(sender, receiver, contender,
                        log.replace("at_ms=32000 at_unix_ms=32000",
                                    "at_ms=32400 at_unix_ms=32400"), None)
    assert overlap["server_no_overlap"] is False
