"""Synthetic journals must never turn missing speech into a complete burst."""

import copy
import json

import pytest

import analyze_browser_buffer
from analyze_browser_buffer import analyze, analyze_burst, playback_for_burst


def fixture(count=1000):
    def event(kind, **fields):
        return {"kind": kind, "atMs": 0, "timeOrigin": 100000,
                "audioNowMs": 0, "renderFrame": 0, **fields}
    grant = event("control_received", type="ptt_granted", burst_id="first",
                  request_id="request", burst_index=0)
    sender = [grant, event("reply", type="capture_started", requestId="request")]
    sender += [event("reply", type="packet", requestId="request", captureSequence=i, renderFrame=i * 960)
               for i in range(count)]
    sender += [event("reply", type="stopped", requestId="request",
                     renderFrame=count * 960, paddedSamples=0, captureFrames=count),
               event("sent", burstId="first", sequence=0, count=count),
               event("control_received", type="ptt_ended", burst_id="first",
                     state="sealed", reason="complete", final_next_sequence=count)]
    receiver = [event("received", burstId="first", sequence=0, count=count),
                event("command", type="start", burstId="first")]
    receiver += [event("command", type="frame", epoch=0,
                      cursor={"burstIndex": 0, "nextSequence": i + 1}) for i in range(count)]
    receiver += [event("command", type="end", epoch=0,
                      cursor={"burstIndex": 1, "nextSequence": 0})]
    receiver += [event("reply", type="played", frame=True, epoch=0,
                       cursor={"burstIndex": 0, "nextSequence": i + 1},
                       renderFrame=4800 + (i + 1) * 960) for i in range(count)]
    receiver += [event("reply", type="played", frame=False, epoch=0,
                       cursor={"burstIndex": 1, "nextSequence": 0}, renderFrame=4800 + count * 960)]
    return sender, receiver, grant


def test_complete_and_capture_shortfall_have_separate_denominators():
    result = analyze_burst(*fixture())
    assert result["complete"]
    assert result["created"] == result["planned"] == 1000
    short = analyze_burst(*fixture(999))
    assert not short["complete"]
    assert short["capture_shortfall"] == 1
    assert short["planned"] == 1000


@pytest.mark.parametrize("change", ["missing", "duplicate", "other_burst", "epoch", "order", "end"])
def test_frame_identity_and_end_evidence(change):
    sender, receiver, grant = fixture()
    first = next(i for i, event in enumerate(receiver) if event.get("type") == "played")
    if change == "missing":
        receiver.pop(first + 1)
    elif change == "duplicate":
        receiver[first + 1] = copy.deepcopy(receiver[first])
    elif change == "other_burst":
        receiver[first + 1]["cursor"]["burstIndex"] = 5
    elif change == "epoch":
        receiver[first + 1]["epoch"] = 1
    elif change == "order":
        receiver[first + 1], receiver[first + 2] = receiver[first + 2], receiver[first + 1]
    else:
        receiver.pop()
    assert not analyze_burst(sender, receiver, grant)["complete"]


def test_queue_pause_uses_render_clock_not_message_receipt_time():
    sender, receiver, grant = fixture()
    first = next(i for i, event in enumerate(receiver) if event.get("type") == "played")
    for event in receiver[first + 500:]:
        event["renderFrame"] += 4800
        event["atMs"] += 900
    result = analyze_burst(sender, receiver, grant)
    assert result["complete"]
    assert result["queue_stalls"] == 1
    assert result["queue_stall_total_ms"] == 100


def test_received_duplicates_are_counted_without_hiding_unique_delivery():
    sender, receiver, grant = fixture()
    receiver.insert(1, copy.deepcopy(receiver[0]))
    result = analyze_burst(sender, receiver, grant)
    assert result["received"] == 2000
    assert result["received_unique"] == 1000
    assert result["received_duplicates"] == 1000


def test_missing_created_sequence_cannot_be_hidden_by_packet_count():
    sender, receiver, grant = fixture()
    packet = next(event for event in sender if event.get("type") == "packet")
    packet["captureSequence"] = 1
    result = analyze_burst(sender, receiver, grant)
    assert not result["complete"]
    assert "capture_sequence_journal_incomplete_or_unordered" in result["issues"]


def test_old_worklet_reply_cannot_be_attributed_to_new_worklet_with_same_cursor():
    cursor = {"burstIndex": 0, "nextSequence": 1}
    events = [
        {"kind": "command", "type": "start", "burstId": "old", "audioGeneration": 1},
        {"kind": "command", "type": "frame", "cursor": cursor, "epoch": 0, "audioGeneration": 1},
        {"kind": "command", "type": "start", "burstId": "new", "audioGeneration": 2},
        {"kind": "command", "type": "frame", "cursor": cursor, "epoch": 0, "audioGeneration": 2},
        {"kind": "reply", "type": "played", "frame": True, "cursor": cursor, "epoch": 0, "audioGeneration": 1},
        {"kind": "reply", "type": "played", "frame": True, "cursor": cursor, "epoch": 0, "audioGeneration": 2},
    ]
    assert playback_for_burst(events, "old")[0] == [events[-2]]
    assert playback_for_burst(events, "new")[0] == [events[-1]]


def test_capture_expiry_precedes_later_stop_acknowledgement():
    sender, receiver, grant = fixture()
    stop = next(event for event in sender if event.get("type") == "stopped")
    expired = {**stop, "type": "capture_expired", "renderFrame": 48000 * 19, "paddedSamples": 80}
    sender.insert(sender.index(stop), expired)
    result = analyze_burst(sender, receiver, grant)
    assert result["capture_duration_ms"] == 19000
    assert result["padded_samples_16khz"] == 80
    assert result["capture_end_reason"] == "capture_expired"
    assert not result["complete"]


def test_interleaved_old_start_does_not_change_new_worklet_owner():
    cursor = {"burstIndex": 0, "nextSequence": 1}
    events = [
        {"kind": "command", "type": "start", "burstId": "new", "audioGeneration": 2},
        {"kind": "command", "type": "start", "burstId": "old", "audioGeneration": 1},
        {"kind": "command", "type": "frame", "cursor": cursor, "epoch": 0, "audioGeneration": 2},
        {"kind": "reply", "type": "played", "frame": True, "cursor": cursor, "epoch": 0, "audioGeneration": 2},
    ]
    assert playback_for_burst(events, "new")[0] == [events[-1]]
    assert playback_for_burst(events, "old")[0] == []


@pytest.mark.parametrize("exit_code", [1, None, 124])
def test_process_failure_or_missing_journals_is_not_complete(tmp_path, exit_code):
    (tmp_path / "result.json").write_text(json.dumps({"test_exit_code": exit_code}))
    result = analyze(tmp_path)
    assert not result["complete"]
    assert "browser_process_failed_or_timed_out" in result["issues"]
    assert "missing_or_invalid_series_outcome_journal" in result["issues"]


def test_explicit_series_timeout_survives_late_complete_events(tmp_path, monkeypatch):
    (tmp_path / "result.json").write_text(json.dumps({"test_exit_code": 0, "tc_confirmed": True,
                                                   "server_logs": {"exit_code": 0}}))
    for index in range(2):
        (tmp_path / f"page-{index}.json").write_text(json.dumps({"context": "closed",
            "microphone": {"state": "ended"}, "events": [
                {"kind": "control_received", "type": "ptt_granted", "burst_id": str(i)} for i in range(3)]}))
    (tmp_path / "series.json").write_text(json.dumps([
        {"direction": "A-to-B", "error": "Drain timed out"}, {"direction": "B-to-A", "error": None}]))
    monkeypatch.setattr(analyze_browser_buffer, "analyze_burst", lambda *_: {
        "complete": True, "end_delay_ms": 100, "burst_id": "test", "request_id": "request"})
    result = analyze(tmp_path)
    assert not result["complete"]
    assert "series_timeout_or_error" in result["directions"][0]["issues"]
    assert result["directions"][1]["complete"]


def test_inter_send_pause_starts_at_final_terminal_not_earlier_draining(tmp_path, monkeypatch):
    (tmp_path / "result.json").write_text(json.dumps({"test_exit_code": 0, "tc_confirmed": True,
                                                   "server_logs": {"exit_code": 0}}))
    events = [{"kind": "control_received", "type": "ptt_granted", "burst_id": str(i),
               "request_id": str(i)} for i in range(3)]
    for burst, at, state in (("0", 20000, "draining"), ("0", 30000, "sealed"), ("1", 52000, "sealed")):
        events.append({"kind": "control_received", "type": "ptt_ended", "burst_id": burst,
                       "state": state, "timeOrigin": 100000, "atMs": at})
    for request, at in (("1", 32000), ("2", 54000)):
        events.append({"kind": "control_sent", "type": "ptt_request", "request_id": request,
                       "timeOrigin": 100000, "atMs": at})
    for index in range(2):
        (tmp_path / f"page-{index}.json").write_text(json.dumps({"context": "closed",
            "microphone": {"state": "ended"}, "events": events}))
    (tmp_path / "series.json").write_text(json.dumps([
        {"direction": "A-to-B", "error": None}, {"direction": "B-to-A", "error": None}]))
    monkeypatch.setattr(analyze_browser_buffer, "analyze_burst", lambda _s, _r, grant: {
        "complete": True, "end_delay_ms": 100, "burst_id": grant["burst_id"], "request_id": grant["request_id"]})
    result = analyze(tmp_path)
    assert result["complete"]
    assert result["directions"][0]["inter_send_pauses_ms"] == [2000, 2000]


@pytest.mark.parametrize("status", ["pending", "failed"])
def test_runner_cleanup_failure_is_explicit(tmp_path, status):
    (tmp_path / "result.json").write_text(json.dumps({"test_exit_code": 0,
        "cleanup_status": status, "cleanup_errors": ["fixture"]}))
    result = analyze(tmp_path)
    assert "runner_cleanup_incomplete" in result["issues"]
    assert not result["complete"]
