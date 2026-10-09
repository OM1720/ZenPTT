"""Analyze timed browser bursts without confusing PCM silence with missing frames."""

from __future__ import annotations

from collections import Counter
import json
from pathlib import Path
import statistics


def distribution(values: list[float]) -> dict:
    quantiles = statistics.quantiles(values, n=100, method="inclusive") if len(values) > 1 else values * 99
    return {"count": len(values), "median": statistics.median(values) if values else None,
            "min": min(values) if values else None, "max": max(values) if values else None,
            "p05": quantiles[4] if quantiles else None,
            "p95": quantiles[94] if quantiles else None, "p99": quantiles[98] if quantiles else None}


def wall(event: dict, *, render: bool = False) -> float:
    value = event["timeOrigin"] + event["atMs"]
    if render:
        value -= event["audioNowMs"] - event["renderFrame"] / 48
    return value


def frames(events: list[dict], kind: str, burst_id: str) -> list[tuple[int, dict]]:
    return [(sequence, event) for event in events
            if event.get("kind") == kind and event.get("burstId") == burst_id
            for sequence in range(event["sequence"], event["sequence"] + event["count"])]


def playback_for_burst(events: list[dict], burst_id: str) -> tuple[list[dict], list[dict]]:
    active = {}
    owners = {}
    played, ends = [], []
    for event in events:
        kind, message = event.get("kind"), event.get("type")
        if kind == "command" and message == "start":
            active[event.get("audioGeneration", 0)] = event["burstId"]
        if message not in ("frame", "end", "played") or "cursor" not in event:
            continue
        cursor = event["cursor"]
        is_frame = message == "frame" if kind == "command" else event.get("frame", False)
        key = (event.get("audioGeneration", 0), cursor["burstIndex"], cursor["nextSequence"], event["epoch"], is_frame)
        if kind == "command":
            owners[key] = active.get(event.get("audioGeneration", 0))
        elif kind == "reply" and message == "played" and owners.get(key) == burst_id:
            (played if is_frame else ends).append(event)
    return played, ends


def analyze_burst(sender: list[dict], receiver: list[dict], grant: dict) -> dict:
    burst_id, request_id, index = grant["burst_id"], grant["request_id"], grant["burst_index"]
    capture = [event for event in sender if event.get("kind") == "reply"
               and event.get("requestId") == request_id]
    created = [event for event in capture if event.get("type") == "packet"]
    created_by_sequence = {event["captureSequence"]: event for event in created if "captureSequence" in event}
    starts = [event for event in capture if event.get("type") == "capture_started"]
    stops = [event for event in capture if event.get("type") == "stopped"]
    boundaries = [event for event in capture if event.get("type") in ("stopped", "capture_expired")]
    sent = frames(sender, "sent", burst_id)
    received = frames(receiver, "received", burst_id)
    played, ends = playback_for_burst(receiver, burst_id)
    terminals = [event for event in sender if event.get("kind") == "control_received"
                 and event.get("type") == "ptt_ended" and event.get("burst_id") == burst_id]
    sequence = [event["cursor"]["nextSequence"] - 1 for event in played]
    identities = [(burst_id, seq, event.get("audioGeneration", 0), event["epoch"]) for seq, event in zip(sequence, played)]
    received_counts = Counter(seq for seq, _ in received)
    first_sequences = list(dict.fromkeys(seq for seq, _ in received))
    gaps = [max(0, (right["renderFrame"] - left["renderFrame"]) / 48 - 20)
            for left, right in zip(played, played[1:]) if left["epoch"] == right["epoch"]
            and left.get("audioGeneration", 0) == right.get("audioGeneration", 0)]
    gaps = [gap for gap in gaps if gap > 0.001]
    first_received = {}
    for seq, event in received:
        first_received.setdefault(seq, event)
    delays = [wall(event, render=True) - wall(first_received[seq])
              for seq, event in zip(sequence, played) if seq in first_received]
    capture_delays = [wall(event, render=True) - wall(created_by_sequence[seq], render=True)
                      for seq, event in zip(sequence, played) if seq in created_by_sequence]
    issues = []
    if [event.get("captureSequence") for event in created] != list(range(len(created))):
        issues.append("capture_sequence_journal_incomplete_or_unordered")
    if not starts or not stops:
        issues.append("missing_capture_boundary")
    elif stops[0].get("captureFrames") != len(created):
        issues.append("incomplete_capture_packet_journal")
    if not ends:
        issues.append("missing_playback_end")
    elif len(ends) != 1 or {(event.get("audioGeneration", 0), event["epoch"]) for event in ends} != {
            (event.get("audioGeneration", 0), event["epoch"]) for event in played}:
        issues.append("playback_end_generation_mismatch")
    elif ends[0]["cursor"] != {"burstIndex": index + 1, "nextSequence": 0}:
        issues.append("invalid_playback_end_cursor")
    if not terminals:
        issues.append("missing_sender_terminal")
    elif not any(event.get("state") == "sealed" and event.get("reason") == "complete"
                 and event.get("final_next_sequence") == len(created) for event in terminals):
        issues.append("sender_not_complete")
    if len(set(identities)) != len(identities):
        issues.append("duplicate_playback_identity")
    if sequence != sorted(set(sequence)):
        issues.append("playback_order_or_replay")
    if first_sequences != sorted(first_sequences):
        issues.append("receive_order_changed")
    if len({(event.get("audioGeneration", 0), event["epoch"]) for event in played}) > 1:
        issues.append("playback_generation_changed")
    if any("renderFrame" not in event or "audioNowMs" not in event for event in played):
        issues.append("missing_render_clock")
    planned = 1000
    expected = set(range(len(created)))
    boundary = boundaries[0] if boundaries else None
    duration = ((boundary["renderFrame"] - starts[0]["renderFrame"]) / 48
                if starts and boundary else None)
    if len(created) < planned:
        issues.append("capture_shortfall")
    if duration is not None and duration < 20000:
        issues.append("capture_duration_short")
    if expected - set(received_counts):
        issues.append("missing_received_frames")
    if expected - set(sequence):
        issues.append("missing_played_frames")
    if set(sequence) - expected or set(received_counts) - expected:
        issues.append("unexpected_frame_identity")
    complete = (not issues and len(created) >= planned and set(received_counts) == expected
                and set(sequence) == expected and {seq for seq, _ in sent} == expected
                and duration is not None and duration >= 20000)
    return {"burst_id": burst_id, "request_id": request_id, "burst_index": index,
            "planned": planned, "created": len(created), "sent": len(sent),
            "sent_unique": len({seq for seq, _ in sent}), "received": len(received),
            "received_unique": len(received_counts), "played": len(played),
            "played_unique": len(set(identities)), "playback_epochs": sorted({event["epoch"] for event in played}),
            "received_duplicates": sum(count - 1 for count in received_counts.values()),
            "concealed_frames": len(set(sequence) - set(received_counts)),
            "missing_received": sorted(expected - set(received_counts)),
            "missing_played": sorted(expected - set(sequence)),
            "capture_shortfall": max(0, planned - len(created)),
            "capture_excess": max(0, len(created) - planned),
            "padded_samples_16khz": boundary.get("paddedSamples") if boundary else None,
            "capture_end_reason": boundary["type"] if boundary else None,
            "capture_reported_frames": boundary.get("captureFrames") if boundary else None,
            "capture_duration_ms": duration,
            "stop_deviation_ms": duration - 20000 if duration is not None else None,
            "queue_stalls": len(gaps), "queue_stall_total_ms": sum(gaps),
            "queue_stall_max_ms": max(gaps, default=0),
            "receipt_to_frame_end_ms": distribution(delays),
            "capture_to_frame_end_ms": distribution(capture_delays),
            "clock_offset_span_ms": distribution([
                wall(event) - event["audioNowMs"] for event in played]),
            "start_delay_ms": wall(played[0], render=True) - 20 - wall(starts[0], render=True)
            if played and starts else None,
            "receive_start_delay_ms": wall(played[0], render=True) - 20 - wall(first_received[sequence[0]])
            if played and sequence[0] in first_received else None,
            "end_delay_ms": wall(played[-1], render=True) - wall(boundary, render=True)
            if played and boundary else None,
            "end_marker_delay_ms": wall(ends[0], render=True) - wall(boundary, render=True)
            if ends and boundary else None,
            "empty_tail_wait_ms": max(0, (ends[0]["renderFrame"] - played[-1]["renderFrame"]) / 48)
            if ends and played else None,
            "terminal": [{key: event.get(key) for key in ("state", "reason", "final_next_sequence")}
                         for event in terminals],
            "complete": complete, "issues": issues}


def analyze(directory: Path) -> dict:
    result = json.loads((directory / "result.json").read_text())
    report = {"directory": str(directory), "profile": result.get("profile"),
              "seed": result.get("seed"), "variant": result.get("web_manifest", {}).get("variant"),
              "observer": "lightweight" if result.get("lightweight_observer") else "pcm",
              "clock_method": "Same-host performance time origins; each render event mapped using AudioContext.currentTime at receipt. Nominal uncertainty >= one 128-sample block (2.667 ms), scheduling may add uncertainty. Server clock is not used for these delays.",
              "directions": [], "issues": []}
    if result.get("test_exit_code") != 0:
        report["issues"].append("browser_process_failed_or_timed_out")
    if result.get("system_error"):
        report["issues"].append("runner_system_error")
    if result.get("collection_errors"):
        report["issues"].append("evidence_collection_failed")
    if result.get("cleanup_status", "complete") != "complete" or result.get("cleanup_errors"):
        report["issues"].append("runner_cleanup_incomplete")
    if not result.get("tc_confirmed") or result.get("server_logs", {}).get("exit_code") != 0:
        report["issues"].append("missing_external_evidence")
    try:
        series = json.loads((directory / "series.json").read_text())
        series_errors = {item["direction"]: item.get("error") for item in series}
        if set(series_errors) != {"A-to-B", "B-to-A"} or len(series) != 2:
            report["issues"].append("incomplete_series_outcome_journal")
    except (OSError, KeyError, TypeError, ValueError):
        series_errors = {}
        report["issues"].append("missing_or_invalid_series_outcome_journal")
    for sender_index, receiver_index, name in [(0, 1, "A-to-B"), (1, 0, "B-to-A")]:
        try:
            sender_state = json.loads((directory / f"page-{sender_index}.json").read_text())
            sender = sender_state["events"]
            receiver = json.loads((directory / f"page-{receiver_index}.json").read_text())["events"]
            if sender_state.get("context") != "closed" or (sender_state.get("microphone") or {}).get("state") != "ended":
                report["issues"].append(f"page-{sender_index}: resource_cleanup_unconfirmed")
            grants = [event for event in sender if event.get("kind") == "control_received"
                      and event.get("type") == "ptt_granted"]
            unique = {event["burst_id"]: event for event in grants}
            bursts = [analyze_burst(sender, receiver, event) for event in unique.values()]
            delays = [burst["end_delay_ms"] for burst in bursts]
            direction_issues = ["burst_count_mismatch"] if len(bursts) != 3 else []
            if series_errors.get(name):
                direction_issues.append("series_timeout_or_error")
            pauses = []
            for previous, following in zip(bursts, bursts[1:]):
                requested = next((event for event in sender if event.get("type") == "ptt_request"
                                  and event.get("request_id") == following["request_id"]), None)
                ended = [event for event in sender if event.get("type") == "ptt_ended"
                         and event.get("burst_id") == previous["burst_id"] and requested
                         and wall(event) <= wall(requested)]
                if ended and requested:
                    pauses.append(wall(requested) - wall(ended[-1]))
            report["directions"].append({"direction": name, "bursts": bursts,
                "planned_bursts": 3, "missing_bursts": max(0, 3 - len(bursts)),
                "issues": direction_issues, "series_error": series_errors.get(name),
                "complete": not direction_issues and all(burst["complete"] for burst in bursts),
                "inter_send_pauses_ms": pauses,
                "first_to_third_end_delay_change_ms": delays[2] - delays[0]
                if len(delays) == 3 and delays[0] is not None and delays[2] is not None else None,
                "sender_reconnections": sum(event.get("kind") == "socket_created" for event in sender) - 1})
        except (OSError, KeyError, TypeError, ValueError) as error:
            report["issues"].append(f"{name}: invalid_or_missing_events: {type(error).__name__}")
    report["complete"] = (not report["issues"] and len(report["directions"]) == 2
                          and all(item["complete"] for item in report["directions"]))
    return report


if __name__ == "__main__":
    import sys
    path = Path(sys.argv[1])
    (path / "buffer-analysis.json").write_text(json.dumps(analyze(path), indent=2) + "\n")
