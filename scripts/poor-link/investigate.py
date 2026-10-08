"""Build protocol evidence from saved poor-link runs without contacting the host."""

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import statistics

from run import events, save_json


def burst_evidence(burst: dict, sender: list[dict], receiver: list[dict]) -> dict:
    burst_id = burst["burst_id"]
    sent = [event for event in sender if burst_id and event.get("burst_id") == burst_id]
    received = [event for event in receiver if burst_id and event.get("burst_id") == burst_id]
    ends = [event for event in sent if event.get("type") == "ptt_ended"]
    decisions = [event for event in sent if event.get("event") == "terminal_decision"]
    writes = [event for event in sent if event.get("event") == "socket_send" and "first_sequence" in event]
    sealed = next((event for event in ends if event["state"] == "sealed"), None)
    draining = next((event for event in ends if event["state"] == "draining"), None)
    produced = next((event for event in sent if event.get("event") == "produced"), None)
    acks = [event for event in sent if event.get("type") == "uplink_ack"]
    send_observation_available = any(event.get("event") == "socket_send" for event in sender)
    result = dict(burst)
    result.update(
        draining=draining, sealed=sealed,
        terminal_decision=decisions[-1] if decisions else None,
        produced_event=produced,
        ack_regressions=sum(right["next_sequence"] < left["next_sequence"]
                            for left, right in zip(acks, acks[1:])),
        rejected=dict(Counter(event["reason"] for event in sent if event.get("type") == "audio_rejected")),
        rejected_end_writes=sum(event.get("type") == "burst_end" for event in sent
                                if event.get("event") == "socket_send_failed"),
        local_final=decisions[-1]["local_final"] if decisions else None,
        send_observation_available=send_observation_available,
        locally_enqueued_unique_frames=len({sequence for event in writes
                                            for sequence in range(event["first_sequence"],
                                                                  event["first_sequence"] + event["count"])})
        if send_observation_available else None,
        locally_enqueued_media_bytes=sum(event["bytes"] for event in writes)
        if send_observation_available else None,
        end_writes=[event for event in sent if event.get("event") == "socket_send"
                    and event.get("type") == "burst_end"],
        expired_retained_frames=sum(len(event["sequences"]) for event in sent
                                    if event.get("event") == "retained_expired")
        if send_observation_available else None,
        server_sealed_repeats=sum(event.get("state") == "sealed" for event in ends),
        received_sealed=next((event for event in received if event.get("type") == "burst_sealed"), None),
    )
    rejected_ends = [event for event in sent if event.get("type") == "audio_rejected"
                     and event.get("reason") == "invalid_range" and event.get("first_sequence") == 0
                     and any(write.get("final_next_sequence") == event.get("next_sequence")
                             for write in result["end_writes"])]
    result["end_rejection_evidence"] = rejected_ends
    return result


def interface_rates(observed: list[dict]) -> dict:
    samples = [event for event in observed if event.get("event") == "network"]
    intervals = [{"at": right["at"], "duration_s": right["at"] - left["at"],
                  **{direction + "_kbit_s": round((right[direction + "_bytes"] - left[direction + "_bytes"])
                                                 * 8 / 1000 / (right["at"] - left["at"]), 3)
                     for direction in ("rx", "tx")}}
                 for left, right in zip(samples, samples[1:]) if right["at"] > left["at"]]
    active = [event for event in observed if event.get("event") == "grant"]
    rates = []
    for grant in active:
        window = [interval for interval in intervals if grant["at"] + 2 <= interval["at"] <= grant["at"] + 18]
        rates.append({"ordinal": grant["ordinal"],
                      **{direction + "_median_kbit_s": round(statistics.median(
                          interval[direction + "_kbit_s"] for interval in window), 3) if window else None
                         for direction in ("rx", "tx")}})
    return {"intervals": intervals, "active_burst_rates": rates}


def case_evidence(case: Path, record: dict) -> dict:
    sender = events(case / "sender/stdout.log")
    receiver = events(case / "receiver/stdout.log")
    backlog = []
    backpressure = []
    channel_hash = hashlib.sha256(record["channel"].encode("utf-8")).hexdigest()[:8]
    log = (case / "server.log").read_text(encoding="utf-8", errors="replace")
    initial_sessions = set(re.findall(r"join session=([0-9a-f-]{36}) channel=" + channel_hash, log))
    known_sessions = initial_sessions | {
        item["session"] for item in record["server_events"].get("resume_correlations", [])
    }
    for line in log.splitlines():
        match = re.search(r"outbound_backlog_limit session=(\S+) queued_bytes=(\d+) offered_bytes=(\d+)", line)
        pressure = re.search(
            r"outbound_backpressure session=(\S+) duration_ms=(\d+) blocked_offers=(\d+) "
            r"peak_queued_bytes=(\d+) recovered=(true|false)", line,
        )
        stamp = re.search(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)\.(\d+)Z", line)
        known = {item["session"]: item for item in record["server_events"].get("resume_correlations", [])}
        if match and stamp and match[1] in known:
            at = datetime.fromisoformat(stamp[1] + "." + stamp[2][:6] + "+00:00").timestamp()
            backlog.append({"at": at, "session": match[1], "queued_bytes": int(match[2]),
                            "offered_bytes": int(match[3]),
                            "after_resume_s": round(at - known[match[1]]["server_at"], 6)})
        if pressure and pressure[1] in known_sessions:
            backpressure.append({"session": pressure[1], "duration_ms": int(pressure[2]),
                                 "blocked_offers": int(pressure[3]),
                                 "peak_queued_bytes": int(pressure[4]),
                                 "recovered": pressure[5] == "true"})
    fault = json.loads((case / "network.json").read_text(encoding="utf-8")).get("fault")
    fault_ack = None
    if fault:
        grant = next((event for event in sender if event.get("event") == "grant" and event.get("ordinal") == 2), None)
        acknowledgements = [event for event in sender if grant and event.get("type") == "uplink_ack"
                            and event.get("burst_id") == grant["burst_id"]]
        before = [event for event in acknowledgements if event["at"] < fault["enabled_at"]]
        after = [event for event in acknowledgements if event["at"] > fault["disabled_at"]]
        if before and after:
            fault_ack = {"last_before": before[-1], "first_after": after[0],
                         "gap_s": round(after[0]["at"] - before[-1]["at"], 6),
                         "after_return_s": round(after[0]["at"] - fault["disabled_at"], 6)}
    return {key: record[key] for key in ("profile", "repeat", "direction", "classification", "network_model")} | {
        "case": str(case.resolve()), "source_run": case.parent.name,
        "bursts": [burst_evidence(burst, sender, receiver)
                                                 for burst in record["analysis"]["bursts"]],
        "backlog": backlog, "backpressure": backpressure,
        "server_events": record["server_events"],
        "fault": fault, "fault_ack": fault_ack,
        "snapshots": [event for event in sender + receiver if event.get("event") == "snapshot"],
        "sender_network": interface_rates(sender),
        "receiver_network": interface_rates(receiver + [event for event in sender if event.get("event") == "grant"]),
    }


def render(output: Path, cases: list[dict]) -> None:
    output.mkdir(parents=True, exist_ok=True)
    save_json(output / "evidence.json", {"cases": cases})
    lines = ["# ZenPTT protocol investigation", "",
             "Saved source, bot events, Linux interface counters, and server logs are the evidence. "
             "Socket-send completion means local enqueue, not server receipt. ACK next_sequence marks "
             "resolved source positions and can include expired gaps; it is not delivered-frame count.", "",
             "Inputs: " + ", ".join(dict.fromkeys(case["source_run"] for case in cases)) + ".", "",
             "## Final sequence disagreements", "",
             "| Case | Burst | Produced | Local final (observed) | Server final | ACK max | Received | Release / seal |",
             "|---|---:|---:|---:|---:|---:|---:|---|"]
    for case in cases:
        for burst in case["bursts"]:
            if burst["send_reason"] != "final_sequence_mismatch":
                continue
            seal = burst["sealed"] or {}
            drain = burst["draining"] or {}
            lines.append(f"| {case['source_run']}/{Path(case['case']).name} | {burst['ordinal']} | {burst['produced_frames']} | "
                         f"{burst['local_final']} | {seal.get('final_next_sequence')} | "
                         f"{burst['ack_max_next_sequence']} | {burst['delivered_frames']} | "
                         f"{drain.get('reason')}/{seal.get('reason')} |")
    lines += ["", "Local final is unavailable in older probes; produced count must not be substituted silently.",
              "", "## Profile comparison", "",
              "| Profile | Cases | Full delivery | Delivered/planned | Median full-burst end lag s | Legacy backlog | Backpressure intervals |",
              "|---|---:|---:|---:|---:|---:|---:|"]
    for profile in dict.fromkeys(case["profile"] for case in cases):
        group = [case for case in cases if case["profile"] == profile]
        bursts = [burst for case in group for burst in case["bursts"]]
        lag = [burst["last_frame_lag_s"] for burst in bursts
               if burst["missing_from_plan_frames"] == 0 and burst["last_frame_lag_s"] is not None]
        lines.append(f"| {profile} | {len(group)} | {sum(all(b['missing_from_plan_frames'] == 0 for b in c['bursts']) for c in group)} | "
                     f"{sum(b['delivered_frames'] for b in bursts)}/{sum(b['planned_frames'] for b in bursts)} | "
                     f"{round(statistics.median(lag), 3) if lag else None} | "
                     f"{sum(c['server_events']['outbound_backlog_limit'] for c in group)} | "
                     f"{sum(len(c.get('backpressure', [])) for c in group)} |")
    lines += ["", "## Backlog timing", "", "| Case | Events | Queue bytes min/max | After resume min/max s |",
              "|---|---:|---|---|"]
    for case in cases:
        observed = case["backlog"]
        if observed:
            queue = [event["queued_bytes"] for event in observed]
            delay = [event["after_resume_s"] for event in observed]
            lines.append(f"| {case['source_run']}/{Path(case['case']).name} | {len(observed)} | {min(queue)}/{max(queue)} | {min(delay)}/{max(delay)} |")
    lines += ["", "## Completed backpressure intervals", "",
              "| Case | Intervals | Blocked offers | Peak queued bytes | Longest interval ms | Unrecovered intervals |",
              "|---|---:|---:|---:|---:|---:|"]
    for case in cases:
        observed = case.get("backpressure", [])
        if observed:
            lines.append(f"| {case['source_run']}/{Path(case['case']).name} | {len(observed)} | "
                         f"{sum(item['blocked_offers'] for item in observed)} | "
                         f"{max(item['peak_queued_bytes'] for item in observed)} | "
                         f"{max(item['duration_ms'] for item in observed)} | "
                         f"{sum(not item['recovered'] for item in observed)} |")
    lines += ["", "## Outage recovery", "",
              "ACK timing is observed at the sender; it does not establish the exact server timer firing time.", "",
              "| Case | Actual outage s | ACK gap s | ACK after return s | Second-burst frames | Send reason |",
              "|---|---:|---:|---:|---:|---|"]
    for case in cases:
        if not case["fault"]:
            continue
        fault = case["fault"]
        ack = case["fault_ack"] or {}
        burst = case["bursts"][1]
        lines.append(f"| {case['source_run']}/{Path(case['case']).name} | {fault['actual_duration_s']} | {ack.get('gap_s')} | "
                     f"{ack.get('after_return_s')} | {burst['delivered_frames']} | {burst['send_reason']} |")
    lines += ["", "## Linux interface traffic", "",
              "Median one-second interface traffic during seconds 2-18 after each sender grant. "
              "Includes protocol overhead, control traffic, ACKs, and retransmissions. It is not codec bitrate.", "",
              "| Case | Burst | Sender TX kbit/s | Sender RX kbit/s | Listener RX kbit/s |",
              "|---|---:|---:|---:|---:|"]
    for case in cases:
        downlink = {rate["ordinal"]: rate for rate in case["receiver_network"]["active_burst_rates"]}
        for rate in case["sender_network"]["active_burst_rates"]:
            lines.append(f"| {case['source_run']}/{Path(case['case']).name} | {rate['ordinal']} | {rate['tx_median_kbit_s']} | "
                         f"{rate['rx_median_kbit_s']} | {downlink.get(rate['ordinal'], {}).get('rx_median_kbit_s')} |")
    (output / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", type=Path, nargs="+", help="Saved run results.json files")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    cases = []
    for path in args.inputs:
        for record in json.loads(path.read_text(encoding="utf-8"))["runs"]:
            case = path.parent / f"{record['profile']}-r{record['repeat']}-{record['direction']}"
            cases.append(case_evidence(case, record))
    render(args.output, cases)


if __name__ == "__main__":
    main()
