"""Checks that lease reports require comparable complete evidence."""

import json

import pytest

from compare_lease import OUTAGE_MODES, compare, render


def test_lease_comparison_counts_frames_and_contender_wait(tmp_path) -> None:
    roots = {}
    for lease in (2, 3, 4):
        root = tmp_path / str(lease)
        root.mkdir()
        source = {"lease_seconds": lease, "modes": list(OUTAGE_MODES),
                  "repeats": 3, "seeds": [1009, 2017, 3037],
                  "audio": {"pcm_sha256": "same"},
                  "installed_bundle_sha256": str(lease), "image": str(lease),
                  "checkout": {"files": {"server/app/session.py": str(lease),
                                         "headless/src/zenptt_headless/client.py": "same"}}}
        runs = [{"mode": mode, "repeat": repeat, "direction": direction,
                 "seed": source["seeds"][repeat - 1],
                 "classification": "measured_degradation", "server_logs": {"exit_code": 0},
                 "analysis": {"planned_frames": {"2": 1000},
                              "delivered_frames": {"2": 500 + lease},
                              "contender_wait_s": float(lease),
                              "server_contender_wait_s": float(lease),
                              "server_release_to_grant_s": 0.5,
                              "server_evidence_complete": True,
                              "server_no_overlap": True,
                              "server_lease_expired_at_ms": 1000,
                              "fault": {"counter_confirmed": True}}}
                for mode in OUTAGE_MODES for repeat in (1, 2, 3)
                for direction in ("A-to-B", "B-to-A")]
        (root / "source.json").write_text(json.dumps(source), encoding="utf-8")
        (root / "results.json").write_text(json.dumps({
            "health": {"before": {"status": "ok"}, "after": {"status": "ok"}},
            "runs": runs,
        }), encoding="utf-8")
        roots[lease] = root

    data = compare(roots)
    assert len(data["cases"]) == 72
    assert data["summary"][0]["second_delivered_frames"] == 6 * 502
    assert data["summary"][0]["contender_wait_s_median"] == 2.0
    assert data["summary"][0]["server_release_to_grant_s_median"] == 0.5
    assert data["probe_images_differ"] is True
    assert "502/1000" in render(data)

    results = json.loads((roots[3] / "results.json").read_text(encoding="utf-8"))
    results["runs"].pop()
    (roots[3] / "results.json").write_text(json.dumps(results), encoding="utf-8")
    with pytest.raises(ValueError, match="missing cases"):
        compare(roots)
