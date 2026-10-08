"""Check interpretation of protocol evidence rather than equating ACK with delivery."""

import hashlib
import json

from investigate import burst_evidence, case_evidence, interface_rates
from run import qdisc_setup


def test_terminal_keeps_generated_acknowledged_and_delivered_counts_separate() -> None:
    burst = {"burst_id": "sample", "delivered_frames": 280}
    sender = [
        {"event": "control", "type": "ptt_ended", "burst_id": "sample", "state": "draining"},
        {"event": "control", "type": "ptt_ended", "burst_id": "sample", "state": "sealed",
         "final_next_sequence": 295},
        {"event": "terminal_decision", "burst_id": "sample", "local_final": 645},
        {"event": "control", "type": "uplink_ack", "burst_id": "sample", "next_sequence": 295},
        {"event": "socket_send", "burst_id": "sample", "first_sequence": 0, "count": 2, "bytes": 100},
        {"event": "socket_send", "burst_id": "sample", "first_sequence": 0, "count": 2, "bytes": 100},
    ]
    result = burst_evidence(burst, sender, [])
    assert result["local_final"] == 645
    assert result["sealed"]["final_next_sequence"] == 295
    assert result["delivered_frames"] == 280
    assert result["locally_enqueued_unique_frames"] == 2
    assert result["locally_enqueued_media_bytes"] == 200
    unavailable = burst_evidence(burst, [], [])
    assert unavailable["local_final"] is None
    assert unavailable["locally_enqueued_unique_frames"] is None


def test_network_rates_use_elapsed_time_and_distinguish_shaping_from_policing() -> None:
    rates = interface_rates([
        {"event": "grant", "at": 0, "ordinal": 1},
        {"event": "network", "at": 2, "tx_bytes": 1000, "rx_bytes": 1000},
        {"event": "network", "at": 4, "tx_bytes": 3000, "rx_bytes": 2000},
    ])
    assert rates["active_burst_rates"][0]["tx_median_kbit_s"] == 8
    assert rates["active_burst_rates"][0]["rx_median_kbit_s"] == 4
    assert "ifb0" in qdisc_setup("listener-shaped-64", "receiver", 1009)[0]
    assert "police rate 64kbit" in qdisc_setup("listener-64", "receiver", 1009)[0]
    assert qdisc_setup("listener-shaped-64", "sender", 1009) == []


def test_backpressure_interval_is_matched_to_case_channel(tmp_path) -> None:
    channel = "TEST.POOR.SAMPLE"
    channel_hash = hashlib.sha256(channel.encode()).hexdigest()[:8]
    session = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    other = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    (tmp_path / "sender").mkdir()
    (tmp_path / "receiver").mkdir()
    (tmp_path / "sender/stdout.log").write_text("")
    (tmp_path / "receiver/stdout.log").write_text("")
    (tmp_path / "network.json").write_text(json.dumps({"fault": None}))
    (tmp_path / "server.log").write_text("\n".join((
        f"zenptt-server-1 | join session={session} channel={channel_hash}",
        f"zenptt-server-1 | outbound_backpressure session={session} duration_ms=42 blocked_offers=3 peak_queued_bytes=16000 recovered=true",
        f"zenptt-server-1 | outbound_backpressure session={other} duration_ms=99 blocked_offers=9 peak_queued_bytes=16000 recovered=false",
    )))
    record = {"channel": channel, "profile": "baseline", "repeat": 1,
              "direction": "A-to-B", "classification": "complete", "network_model": "none",
              "server_events": {"resume_correlations": []}, "analysis": {"bursts": []}}
    observed = case_evidence(tmp_path, record)["backpressure"]
    assert observed == [{"session": session, "duration_ms": 42,
                         "blocked_offers": 3, "peak_queued_bytes": 16000,
                         "recovered": True}]
