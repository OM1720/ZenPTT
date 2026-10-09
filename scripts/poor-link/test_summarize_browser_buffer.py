import json

import summarize_browser_buffer
from summarize_browser_buffer import summarize, write_report


def test_missing_cases_preserve_all_planned_speech_and_observer_groups(tmp_path):
    result = summarize(tmp_path, "SSH unavailable")
    assert len(result["attempts"]) == 51
    assert all(case["status"] == "not_run" for case in result["attempts"])
    assert len(result["groups"]) == 21
    assert sum(group["planned_frames"] for group in result["groups"]
               if group["observer"] == "lightweight") == 270000
    assert sum(group["planned_frames"] for group in result["groups"]
               if group["observer"] == "pcm") == 36000
    assert all(group["complete_bursts"] == 0 for group in result["groups"])
    write_report(tmp_path, result)
    assert "SSH unavailable" in (tmp_path / "comparison.md").read_text()


def test_process_failure_excludes_otherwise_complete_bursts_from_aggregates(tmp_path, monkeypatch):
    directory = tmp_path / "baseline-1009-P100-lightweight"
    directory.mkdir()
    (directory / "result.json").write_text(json.dumps({}))
    monkeypatch.setattr(summarize_browser_buffer, "analyze", lambda _: {
        "complete": False, "issues": ["browser_process_failed_or_timed_out"],
        "directions": [{"direction": "A-to-B", "bursts": [{"complete": True,
            "created": 1000, "received_unique": 1000, "played": 1000}]}]})
    result = summarize(tmp_path, "not run")
    group = next(item for item in result["groups"] if item["profile"] == "baseline"
                 and item["variant"] == "P100" and item["observer"] == "lightweight")
    assert group["observed_bursts"] == 1
    assert group["created_frames"] == 1000
    assert group["complete_bursts"] == 0


def test_paired_reduction_keeps_raw_totals_and_matching_denominator(tmp_path, monkeypatch):
    for variant in ("P100", "P150"):
        directory = tmp_path / f"baseline-1009-{variant}-lightweight"
        directory.mkdir()
        (directory / "result.json").write_text("{}")

    def analysis(directory):
        delay = 100 if "P100" in directory.name else 150
        burst = {"complete": True, "created": 1000, "received_unique": 1000, "played": 1000,
                 "queue_stalls": 1, "queue_stall_total_ms": 200 - delay, "queue_stall_max_ms": 200 - delay,
                 "start_delay_ms": delay, "receive_start_delay_ms": delay, "end_delay_ms": delay,
                 "capture_duration_ms": 20000, "capture_to_frame_end_ms": {"median": delay},
                 "clock_offset_span_ms": {"min": 100000, "max": 100012}}
        return {"complete": True, "issues": [], "directions": [{"direction": "A-to-B",
            "complete": True, "bursts": [burst], "first_to_third_end_delay_change_ms": 5,
            "inter_send_pauses_ms": [2000, 2010]}]}

    monkeypatch.setattr(summarize_browser_buffer, "analyze", analysis)
    result = summarize(tmp_path, "not run")
    pair = result["paired_groups"][0]
    assert pair["comparable_burst_pairs"] == 1
    assert pair["control_stall_ms"] == 100
    assert pair["candidate_stall_ms"] == 50
    assert pair["stall_reduction_percent"] == 50
    assert pair["start_delay_difference_ms"]["median"] == 50
