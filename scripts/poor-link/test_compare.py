"""Checks for traffic accounting and incomplete-case comparison."""

from __future__ import annotations

import json

import pytest

from compare import bot_traffic, summarize, validate_matrix


def test_bot_traffic_keeps_media_messages_distinct_from_frames(tmp_path) -> None:
    path = tmp_path / "events.log"
    path.write_text("\n".join(json.dumps(event) for event in (
        {"event": "network", "rx_bytes": 100, "tx_bytes": 200,
         "rx_packets": 2, "tx_packets": 3},
        {"event": "socket_send", "count": 3, "bytes": 90},
        {"event": "socket_send", "type": "burst_end", "bytes": 40},
        {"event": "socket_send_failed", "count": 2},
        {"event": "network", "rx_bytes": 500, "tx_bytes": 900,
         "rx_packets": 8, "tx_packets": 12},
    )) + "\n", encoding="utf-8")

    result = bot_traffic(path)

    assert result["media_messages"] == 1
    assert result["media_bytes"] == 90
    assert result["control_messages"] == 1
    assert result["send_failures"] == 1
    assert result["sampled_network"] == {
        "rx_bytes": 400, "tx_bytes": 700, "rx_packets": 6, "tx_packets": 9,
    }


def test_summary_does_not_call_a_truncated_case_complete() -> None:
    def case(classification: str, delivered: int, lag: float) -> dict:
        return {"classification": classification, "planned_frames": 3000,
                "produced_frames": 3000, "delivered_frames": delivered,
                "ack_count": 600, "last_frame_lag_s": [lag],
                "sender": {"media_messages": 1000,
                           "sampled_network": {"tx_bytes": 10_000}}}

    summary = summarize({
        ("sender-32", 1, "A-to-B"): case("complete", 3000, 2.0),
        ("sender-32", 1, "B-to-A"): case("measured_degradation", 1500, -8.0),
    })["sender-32"]

    assert summary["classes"] == {"complete": 1, "measured_degradation": 1}
    assert summary["delivered_frames"] == 4500
    assert summary["median_last_frame_lag_s_complete"] == 2.0
    assert summary["median_burst_lag_s_complete"] == [2.0, None, None]


def test_comparison_rejects_missing_cases_and_unreliable_logs() -> None:
    profiles = [f"profile-{number}" for number in range(17)]
    source = {"profiles": profiles, "repeats": 3, "seeds": [1009, 2017, 3037],
              "audio": {"pcm_sha256": "same"}}
    cases = {(profile, repeat, direction): {
        "seed": source["seeds"][repeat - 1], "classification": "complete",
        "server_logs": {"exit_code": 0},
    } for profile in profiles for repeat in (1, 2, 3)
        for direction in ("A-to-B", "B-to-A")}
    loaded = {name: {"source": source, "cases": cases.copy(),
                     "health": {"before": {"status": "ok"}, "after": {"status": "ok"}}}
              for name in ("R", "A", "B", "AB")}
    validate_matrix(loaded)
    loaded["A"]["cases"].pop((profiles[0], 1, "A-to-B"))
    with pytest.raises(ValueError, match="missing"):
        validate_matrix(loaded)
    loaded["A"]["cases"] = cases.copy()
    loaded["AB"]["cases"][(profiles[0], 1, "A-to-B")] = {
        **cases[(profiles[0], 1, "A-to-B")], "server_logs": {"exit_code": 1}}
    with pytest.raises(ValueError, match="reliable test evidence"):
        validate_matrix(loaded)
