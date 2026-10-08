"""Synthetic evidence tests for the poor-link measurement analyzer."""

from __future__ import annotations

from copy import deepcopy
import hashlib

from analyze import analyze_pair
from run import classify, server_event_counts


def evidence() -> tuple[list[dict], list[dict]]:
    sender = []
    receiver = []
    for ordinal in range(1, 4):
        burst = f"burst-{ordinal}"
        at = ordinal * 30.0
        sender.extend((
            {"event": "grant", "ordinal": ordinal, "burst_id": burst, "at": at},
            {"event": "produced", "ordinal": ordinal, "burst_id": burst, "frames": 1000},
            {"event": "send_result", "ordinal": ordinal, "burst_id": burst,
             "status": "complete", "reason": None},
        ))
        receiver.extend((
            {"event": "media", "burst_id": burst, "first_sequence": sequence,
             "count": 1, "at": at + sequence * 0.02 + 0.2}
            for sequence in range(1000)
        ))
        receiver.append({"event": "received", "burst_id": burst, "first_sequence": 0,
                         "frames": 1000, "loss_ranges": [], "decode_errors": [], "reason": "complete"})
    return sender, receiver


def test_complete_plan() -> None:
    sender, receiver = evidence()
    result = analyze_pair(sender, receiver)
    assert result["problems"] == []
    assert all(row["missing_from_plan_frames"] == 0 for row in result["bursts"])


def test_gap_and_duplicate() -> None:
    sender, receiver = evidence()
    receiver.append(deepcopy(next(item for item in receiver if item["event"] == "media")))
    observed = next(item for item in receiver if item["event"] == "received")
    observed["loss_ranges"] = [[300, 1]]
    result = analyze_pair(sender, receiver)["bursts"][0]
    assert result["duplicate_media_frames"] == 1
    assert result["authoritative_loss_frames"] == 1
    assert result["missing_from_plan_frames"] == 1


def test_complete_reason_does_not_hide_truncated_tail() -> None:
    sender, receiver = evidence()
    observed = next(item for item in receiver if item["event"] == "received")
    observed["frames"] = 800
    assert observed["reason"] == "complete"
    result = analyze_pair(sender, receiver)["bursts"][0]
    assert result["truncated_tail_frames"] == 200
    assert result["missing_from_plan_frames"] == 200


def test_short_final_without_release_evidence_is_uncertain() -> None:
    sender, receiver = evidence()
    sender[2]["reason"] = "final_sequence_mismatch"
    observed = next(item for item in receiver if item["event"] == "received")
    observed["frames"] = 500
    analysis = analyze_pair(sender, receiver)
    assert classify(analysis, {"sender": 0, "receiver": 0}, {"exit_code": 0}, [], None) == "diagnostic_uncertainty"


def test_reconnect_and_missing_observation() -> None:
    sender, receiver = evidence()
    sender.extend((
        {"event": "transport", "count": 1, "session_epoch": 0},
        {"event": "transport", "count": 2, "session_epoch": 1},
    ))
    receiver[:] = [event for event in receiver if event.get("burst_id") != "burst-2"]
    result = analyze_pair(sender, receiver)
    assert "missing_receive:2" in result["problems"]
    assert result["bursts"][2]["delivered_frames"] == 1000
    assert result["sender_transports"] == 2
    assert result["sender_session_epochs"] == [0, 1]


def test_process_error_and_mismatched_burst_id() -> None:
    sender, receiver = evidence()
    sender.append({"event": "probe_error", "error": "RuntimeError"})
    sender[-2]["burst_id"] = "other"
    result = analyze_pair(sender, receiver)
    assert "process_event:probe_error" in result["problems"]
    assert "sender_burst_mismatch:3" in result["problems"]


def test_unexpected_receiver_burst() -> None:
    sender, receiver = evidence()
    receiver.append({"event": "received", "burst_id": "unrelated", "frames": 1})
    assert "unexpected_burst:unrelated" in analyze_pair(sender, receiver)["problems"]


def test_missing_burst_after_receiver_deadline_is_degradation() -> None:
    sender, receiver = evidence()
    receiver[:] = [event for event in receiver if event.get("burst_id") != "burst-3"]
    receiver.append({"event": "receiver_finished", "completed_bursts": 2})
    result = analyze_pair(sender, receiver)
    assert result["bursts"][2]["receive_reason"] == "not_received_by_deadline"
    assert classify(result, {"sender": 0, "receiver": 0}, {"exit_code": 0}, [], None) == "measured_degradation"


def test_server_events_only_count_sessions_on_test_channel() -> None:
    channel = "TEST.POOR.SAMPLE"
    channel_hash = hashlib.sha256(channel.encode()).hexdigest()[:8]
    sender = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    receiver = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    foreign = "cccccccc-cccc-cccc-cccc-cccccccccccc"
    lines = [
        f"zenptt-server-1 | join session={sender} channel={channel_hash}",
        f"zenptt-server-1 | join session={receiver} channel={channel_hash}",
        f"zenptt-server-1 | outbound_backlog_limit session={receiver}",
        f"zenptt-server-1 | outbound_backlog_limit session={foreign}",
        f"zenptt-server-1 | resume session={sender} generation=2",
        f"zenptt-server-1 | media_timing type=server_receive_gap session={sender[-6:]}",
    ]
    counts = server_event_counts("\n".join(lines), channel)
    assert counts["matched_sessions"] == 2
    assert counts["outbound_backlog_limit"] == 1
    assert counts["resumes"] == 1
    assert counts["server_receive_gaps"] == 1


def test_resumed_session_counts_backlog_with_unique_transport_evidence() -> None:
    channel = "TEST.POOR.SAMPLE"
    channel_hash = hashlib.sha256(channel.encode()).hexdigest()[:8]
    initial = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    resumed = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    foreign = "cccccccc-cccc-cccc-cccc-cccccccccccc"
    log = "\n".join((
        f"zenptt-server-1 | join session={initial} channel={channel_hash}",
        f"zenptt-server-1 | 2026-10-03T12:00:00.100000001Z INFO: resume session={resumed} generation=2",
        f"zenptt-server-1 | outbound_backlog_limit session={resumed}",
        f"zenptt-server-1 | media_timing type=server_send_gap session={resumed[-6:]}",
        f"zenptt-server-1 | 2026-10-03T12:00:05.000Z INFO: resume session={foreign} generation=2",
        f"zenptt-server-1 | outbound_backlog_limit session={foreign}",
    ))
    transport = {"event": "transport", "at": 1791028800.3, "count": 2, "role": "receiver"}
    counts = server_event_counts(log, channel, [transport])
    assert counts["matched_sessions"] == 2
    assert counts["resumes"] == 1
    assert counts["outbound_backlog_limit"] == 1
    assert counts["server_send_gaps"] == 1
    assert counts["resume_correlations"][0]["delta_s"] == 0.2
    assert counts["resume_correlations"][0]["role"] == "receiver"
    assert server_event_counts(log, channel)["outbound_backlog_limit"] == 0
    assert server_event_counts(log, "OTHER", [transport])["resumes"] == 0
    assert server_event_counts(log, channel, [transport, transport])["resumes"] == 0
    duplicate = log + f"\nzenptt-server-1 | 2026-10-03T12:00:00.200Z INFO: resume session={foreign} generation=2"
    assert server_event_counts(duplicate, channel, [transport])["resumes"] == 0
