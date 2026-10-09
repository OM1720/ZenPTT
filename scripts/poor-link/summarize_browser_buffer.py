"""Summarize every planned browser case, including missing and incomplete attempts."""

from __future__ import annotations

import argparse
from collections import defaultdict
import json
from pathlib import Path

from analyze_browser_buffer import analyze, distribution
from browser_buffer import cases


def summarize(root: Path, unrun_reason: str) -> dict:
    attempts = []
    grouped = defaultdict(list)
    grouped_directions = defaultdict(list)
    planned_bursts = defaultdict(int)
    paired = {}
    for case in cases():
        name = f"{case['profile']}-{case['seed']}-{case['variant']}-{case['observer']}"
        directory = root / name
        item = {**case, "attempt": name}
        group_key = (case["observer"], case["profile"], case["variant"])
        planned_bursts[group_key] += 6
        grouped[group_key]
        if not (directory / "result.json").exists():
            item.update(status="not_run", reason=unrun_reason)
        else:
            analysis = analyze(directory)
            item.update(status="complete" if analysis["complete"] else "incomplete", analysis=analysis)
            for direction in analysis["directions"]:
                grouped_directions[group_key].append({**direction, "measurement_valid": not analysis["issues"]})
                for ordinal, burst in enumerate(direction["bursts"]):
                    measured = {**burst, "measurement_valid": not analysis["issues"] and not direction.get("issues")}
                    grouped[(case["observer"], case["profile"], case["variant"])].append(measured)
                    paired[(case["observer"], case["profile"], case["seed"], direction["direction"], ordinal, case["variant"])] = measured
        attempts.append(item)
    groups = []
    for (observer, profile, variant), bursts in grouped.items():
        complete = [burst for burst in bursts if burst["complete"] and burst["measurement_valid"]]
        complete_directions = [item for item in grouped_directions[(observer, profile, variant)]
                               if item.get("complete") and item["measurement_valid"]]
        groups.append({"observer": observer, "profile": profile, "variant": variant,
            "observed_bursts": len(bursts), "complete_bursts": len(complete),
            "planned_bursts": planned_bursts[(observer, profile, variant)],
            "planned_frames": planned_bursts[(observer, profile, variant)] * 1000,
            "created_frames": sum(burst["created"] for burst in bursts),
            "received_unique": sum(burst["received_unique"] for burst in bursts),
            "played_frames": sum(burst["played"] for burst in bursts),
            "complete_directions": len(complete_directions),
            "first_to_third_end_delay_change_ms": distribution([
                item["first_to_third_end_delay_change_ms"] for item in complete_directions]),
            "inter_send_pauses_ms": distribution([
                pause for item in grouped_directions[(observer, profile, variant)]
                for pause in item.get("inter_send_pauses_ms", [])]),
            "complete_bursts_only": {
                "queue_stalls": sum(burst["queue_stalls"] for burst in complete),
                "queue_stall_total_ms": sum(burst["queue_stall_total_ms"] for burst in complete),
                "queue_stall_max_ms": max((burst["queue_stall_max_ms"] for burst in complete), default=None),
                "start_delay_ms": distribution([burst["start_delay_ms"] for burst in complete]),
                "receive_start_delay_ms": distribution([burst["receive_start_delay_ms"] for burst in complete]),
                "end_delay_ms": distribution([burst["end_delay_ms"] for burst in complete]),
                "capture_duration_ms": distribution([burst["capture_duration_ms"] for burst in complete]),
                "per_burst_median_frame_delay_ms": distribution([
                    burst["capture_to_frame_end_ms"]["median"] for burst in complete]),
                "render_clock_offset_range_ms": distribution([
                    burst["clock_offset_span_ms"]["max"] - burst["clock_offset_span_ms"]["min"]
                    for burst in complete]),
            }})
    differences = []
    for key, candidate in paired.items():
        if key[-1] == "P100":
            continue
        control = paired.get((*key[:-1], "P100"))
        comparable = (control is not None and control["complete"] and candidate["complete"]
                      and control["measurement_valid"] and candidate["measurement_valid"])
        item = {"observer": key[0], "profile": key[1], "seed": key[2], "direction": key[3],
                "burst_ordinal": key[4] + 1, "variant": key[5], "comparable": comparable}
        if comparable:
            item.update({f"{metric}_difference": candidate[metric] - control[metric]
                         for metric in ("queue_stall_total_ms", "start_delay_ms", "end_delay_ms")})
            item.update(control_stall_ms=control["queue_stall_total_ms"],
                        candidate_stall_ms=candidate["queue_stall_total_ms"])
        differences.append(item)
    paired_groups = []
    for observer, profile, variant in sorted({(item["observer"], item["profile"], item["variant"])
                                             for item in differences}):
        matched = [item for item in differences if (item["observer"], item["profile"], item["variant"])
                   == (observer, profile, variant) and item["comparable"]]
        control = sum(item["control_stall_ms"] for item in matched)
        candidate = sum(item["candidate_stall_ms"] for item in matched)
        paired_groups.append({"observer": observer, "profile": profile, "variant": variant,
            "comparable_burst_pairs": len(matched), "control_stall_ms": control,
            "candidate_stall_ms": candidate,
            "stall_reduction_percent": 100 * (control - candidate) / control if control else None,
            "start_delay_difference_ms": distribution([item["start_delay_ms_difference"] for item in matched]),
            "end_delay_difference_ms": distribution([item["end_delay_ms_difference"] for item in matched])})
    retries = []
    for path in sorted(root.glob("*/result.json")):
        result = json.loads(path.read_text())
        if result.get("retry_of") and result.get("series"):
            retries.append({"attempt": path.parent.name, "retry_of": result["retry_of"], "analysis": analyze(path.parent)})
    return {"attempts": attempts, "retries": retries, "groups": groups, "paired_differences": differences,
            "paired_groups": paired_groups,
            "decision": "Buffer selection requires a human decision based on measured pauses and delay. Acoustic quality is unverified."}


def write_report(root: Path, report: dict) -> None:
    (root / "comparison.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    lines = ["# Browser buffer comparison", "", report["decision"], "",
             "The synthetic microphone signal is separate from the earlier LibriSpeech matrix.",
             "Proxy A shapes a TCP proxy path, not a direct bot uplink. Three seeds cannot establish a universal effect.", "",
             "| Attempt | Status | Reason |", "|---|---|---|"]
    for item in report["attempts"]:
        analysis = item.get("analysis", {})
        issues = set(analysis.get("issues", []))
        for direction in analysis.get("directions", []):
            issues.update(direction.get("issues", []))
            for burst in direction["bursts"]:
                issues.update(burst["issues"])
        reason = item.get("reason", ", ".join(sorted(issues)))
        lines.append(f"| {item['attempt']} | {item['status']} | {reason} |")
    lines += ["", "Only complete bursts enter delay/stall aggregates. Raw partial bursts remain in comparison.json.", "",
              "| Observer | Profile | Variant | Complete / observed / planned bursts | Stalls | Total stall ms | Max stall ms | Median start ms | Median end ms |",
              "|---|---|---|---:|---:|---:|---:|---:|---:|"]
    for group in report["groups"]:
        full = group["complete_bursts_only"]
        lines.append(f"| {group['observer']} | {group['profile']} | {group['variant']} | "
                     f"{group['complete_bursts']} / {group['observed_bursts']} / {group['planned_bursts']} | {full['queue_stalls']} | "
                     f"{full['queue_stall_total_ms']} | {full['queue_stall_max_ms']} | "
                     f"{full['start_delay_ms']['median']} | {full['end_delay_ms']['median']} |")
    if report["retries"]:
        lines += ["", "Explicit retries are retained separately and do not silently replace original attempts:"]
        lines += [f"- {item['attempt']} retries {item['retry_of']}; complete={item['analysis']['complete']}"
                  for item in report["retries"]]
    (root / "comparison.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--unrun-reason", default="No saved attempt evidence")
    args = parser.parse_args()
    write_report(args.directory, summarize(args.directory, args.unrun_reason))
