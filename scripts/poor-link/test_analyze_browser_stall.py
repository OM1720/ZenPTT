"""Checks identity and queue-block evidence for browser stall analysis."""

import pytest

from analyze_browser_stall import analyze_direction, blocked_durations, sequences


def test_frame_ranges_and_queue_block_durations():
    assert sequences([{"sequence": 2, "count": 3}, {"sequence": 5, "count": 1}]) == [2, 3, 4, 5]
    assert blocked_durations([{"atMs": 10, "blocked": False},
                              {"atMs": 20, "blocked": True},
                              {"atMs": 75, "blocked": False}]) == [55]
    with pytest.raises(ValueError, match="no end"):
        blocked_durations([{"atMs": 20, "blocked": True}])


def test_rejects_missing_played_frame_despite_matching_aggregate_count():
    metric = {"direction": "A-to-B", "sentFrames": 2, "receivedFrames": 2,
              "playedFrames": 2, "queueBlockEvents": 0}
    trace = {"sender": {"sent": [{"sequence": 0, "count": 2}]},
             "receiver": {"received": [{"sequence": 0, "count": 2, "atMs": 10}],
                          "played": [{"sequence": 0, "atMs": 20},
                                     {"sequence": 0, "atMs": 40}],
                          "quality": []}}
    with pytest.raises(ValueError, match="identities"):
        analyze_direction(metric, trace)
