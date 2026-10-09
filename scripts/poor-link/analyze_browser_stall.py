"""Compare browser playback stalls across saved proxy runs."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from statistics import median

from client_proxy import successful


SEEDS = (1009, 2017, 3037)
PREBUFFERS_MS = (0, 50, 100)
PROFILES = ("baseline", "unstable")
DIRECTIONS = ("A-to-B", "B-to-A")


def sequences(events: list[dict]) -> list[int]:
    return [number for event in events
            for number in range(event["sequence"], event["sequence"] + event["count"])]


def blocked_durations(events: list[dict]) -> list[float]:
    started = None
    durations = []
    for event in events:
        if event["blocked"]:
            if started is not None:
                raise ValueError("Repeated queue-block start")
            started = event["atMs"]
        elif started is not None:
            durations.append(event["atMs"] - started)
            started = None
    if started is not None:
        raise ValueError("Queue-block period has no end")
    return durations


def analyze_direction(metric: dict, trace: dict) -> dict:
    sent = sequences(trace["sender"]["sent"])
    received = sequences(trace["receiver"]["received"])
    played = [event["sequence"] for event in trace["receiver"]["played"]]
    if (not sent or sent != received or received != played
            or len(sent) != len(set(sent))
            or len(sent) != metric["sentFrames"]
            or len(received) != metric["receivedFrames"]
            or len(played) != metric["playedFrames"]):
        raise ValueError("Sent, received, and played frame identities do not match")
    received_at = {number: event["atMs"] for event in trace["receiver"]["received"]
                   for number in range(event["sequence"], event["sequence"] + event["count"])}
    receive_to_play = [event["atMs"] - received_at[event["sequence"]]
                       for event in trace["receiver"]["played"]]
    if min(receive_to_play) < 0:
        raise ValueError("Playback precedes receipt")
    blocks = blocked_durations(trace["receiver"]["quality"])
    if len(blocks) != metric["queueBlockEvents"]:
        raise ValueError("Queue-block metric disagrees with the trace")
    prebuffer = trace["receiver"].get("prebuffer", {})
    return {
        "direction": metric["direction"], "frames": len(sent),
        "duration_delta_ms": round((metric["outputSamples"] - metric["inputSamples"]) / 48, 1),
        "input_internal_silence_ms": metric["inputInternalSilenceMs"],
        "output_internal_silence_ms": metric["outputInternalSilenceMs"],
        "queue_blocks": len(blocks), "queue_block_duration_ms": round(sum(blocks), 1),
        "first_receive_to_play_ms": round(receive_to_play[0], 1),
        "median_receive_to_play_ms": round(median(receive_to_play), 1),
        "max_receive_to_play_ms": round(max(receive_to_play), 1),
        "observer_wall_gap_max_ms": round(trace["receiver"]["observer"]["wallGapMaxMs"], 1),
        "prebuffer_actual_ms": (round(prebuffer["releasedAtMs"] - prebuffer["startedAtMs"], 1)
                                if prebuffer.get("releasedAtMs") is not None else 0),
        "signal_error": metric["signalError"],
    }


def analyze(root: Path, bundle: str) -> dict:
    cases: dict[tuple[str, int, int], dict] = {}
    excluded = []
    for directory in sorted(root.iterdir()):
        if not directory.is_dir() or not (directory / "result.json").exists():
            continue
        result = json.loads((directory / "result.json").read_text(encoding="utf-8"))
        if not successful(result) or not (directory / "browser-metrics.json").exists():
            browser_output = (directory / "browser.stdout.log").read_text(encoding="utf-8")
            if "Expected: > 400" in browser_output and "Received:" in browser_output:
                observed = browser_output.split("Received:", 1)[1].splitlines()[0].strip()
                reason = f"capture target >400 was not reached (observed {observed})"
            elif "locator.click: Test timeout" in browser_output:
                reason = "Disconnect control was unavailable at the browser test deadline"
            else:
                reason = result.get("system_error", result.get("test_error",
                                    f"browser exit {result.get('test_exit_code', 'missing')}"))
            metrics_path = directory / "browser-metrics.json"
            if metrics_path.exists():
                metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
                if any(row["sentFrames"] != row["receivedFrames"] for row in metrics):
                    reason += "; sent/received frame counts differ"
            excluded.append({"directory": directory.name,
                             "reason": reason})
            continue
        key = (result["profile"], result["seed"], result.get("prebuffer_ms", 0))
        if result["installed_bundle"] != bundle or key in cases:
            raise ValueError(f"Bundle mismatch or duplicate valid case: {directory.name}")
        metrics = json.loads((directory / "browser-metrics.json").read_text(encoding="utf-8"))
        if [metric["direction"] for metric in metrics] != list(DIRECTIONS):
            raise ValueError(f"Missing direction: {directory.name}")
        rows = []
        for metric in metrics:
            trace = json.loads((directory / f"{metric['direction']}-trace.json")
                               .read_text(encoding="utf-8"))
            rows.append(analyze_direction(metric, trace))
        cases[key] = {"directory": directory.name, "profile": key[0], "seed": key[1],
                      "prebuffer_ms": key[2], "directions": rows,
                      "test_source": result["source"],
                      "tc_confirmed": result["tc_confirmed"],
                      "server_logs": result["server_logs"]}
    expected = {(profile, seed, prebuffer) for profile in PROFILES
                for seed in SEEDS for prebuffer in PREBUFFERS_MS}
    if set(cases) != expected:
        raise ValueError(f"Missing cases: {sorted(expected - set(cases))}; extra: {sorted(set(cases) - expected)}")
    groups = []
    for profile in PROFILES:
        for prebuffer in PREBUFFERS_MS:
            rows = [direction for (name, _, delay), case in cases.items()
                    if name == profile and delay == prebuffer for direction in case["directions"]]
            groups.append({"profile": profile, "prebuffer_ms": prebuffer, "directions": len(rows),
                           "frames": sum(row["frames"] for row in rows),
                           "output_internal_silence_ms": sum(row["output_internal_silence_ms"] for row in rows),
                           "queue_blocks": sum(row["queue_blocks"] for row in rows),
                           "queue_block_duration_ms": round(sum(row["queue_block_duration_ms"] for row in rows), 1),
                           "median_first_receive_to_play_ms": round(median(
                               row["first_receive_to_play_ms"] for row in rows), 1),
                           "median_receive_to_play_ms": round(median(
                               row["median_receive_to_play_ms"] for row in rows), 1),
                           "signal_errors": sum(bool(row["signal_error"]) for row in rows)})
    return {"bundle_sha256": bundle, "groups": groups,
            "cases": [cases[key] for key in sorted(cases)], "excluded": excluded}


def analyze_lightweight(root: Path, bundle: str) -> list[dict]:
    groups = []
    for profile in PROFILES:
        rows = []
        for seed in SEEDS:
            directory = root / f"{profile}-{seed}"
            result = json.loads((directory / "result.json").read_text(encoding="utf-8"))
            if (not successful(result) or result["installed_bundle"] != bundle
                    or not result["lightweight_observer"] or result["prebuffer_ms"] != 0):
                raise ValueError(f"Incomplete lightweight case: {directory.name}")
            metrics = json.loads((directory / "browser-metrics.json").read_text(encoding="utf-8"))
            if [metric["direction"] for metric in metrics] != list(DIRECTIONS):
                raise ValueError(f"Missing lightweight direction: {directory.name}")
            for metric in metrics:
                trace = json.loads((directory / f"{metric['direction']}-trace.json")
                                   .read_text(encoding="utf-8"))
                rows.append(analyze_direction(metric, trace))
        groups.append({"profile": profile, "directions": len(rows),
                       "frames": sum(row["frames"] for row in rows),
                       "queue_blocks": sum(row["queue_blocks"] for row in rows),
                       "queue_block_duration_ms": round(sum(
                           row["queue_block_duration_ms"] for row in rows), 1),
                       "median_first_receive_to_play_ms": round(median(
                           row["first_receive_to_play_ms"] for row in rows), 1),
                       "median_receive_to_play_ms": round(median(
                           row["median_receive_to_play_ms"] for row in rows), 1)})
    return groups


def markdown(data: dict) -> str:
    lines = ["# Browser playback stall diagnosis", "",
             f"Installed server package SHA-256: `{data['bundle_sha256']}`.", "",
             "Each group contains three seeds and both speech directions. The test-only extra prebuffer "
             "holds first frame commands before the AudioWorklet's own startup delay.", "",
             "| Link | Extra prebuffer | Frames | Internal silence | Queue blocks / duration | "
             "Median first frame delay | Median receive-to-play | Signal oracle errors |",
             "|---|---:|---:|---:|---:|---:|---:|---:|"]
    for group in data["groups"]:
        lines.append(f"| {group['profile']} | {group['prebuffer_ms']} ms | {group['frames']} | "
                     f"{group['output_internal_silence_ms']} ms | {group['queue_blocks']} / "
                     f"{group['queue_block_duration_ms']} ms | "
                     f"{group['median_first_receive_to_play_ms']} ms | "
                     f"{group['median_receive_to_play_ms']} ms | {group['signal_errors']}/6 |")
    baseline = {group["prebuffer_ms"]: group for group in data["groups"]
                if group["profile"] == "baseline"}
    unstable = {group["prebuffer_ms"]: group for group in data["groups"]
                if group["profile"] == "unstable"}
    lines += ["", "## Findings", "",
              "The unstable link preserved the identity of every sent frame in the valid "
              "cases, but playback queue starvation inserted internal silence. Its total "
              f"measured silence was {unstable[0]['output_internal_silence_ms']} ms with "
              f"{unstable[0]['queue_blocks']} queue blocks at the original startup delay."]
    for delay in (50, 100):
        reduction = round(100 * (1 - unstable[delay]["output_internal_silence_ms"]
                                / unstable[0]["output_internal_silence_ms"]))
        startup_cost = round(baseline[delay]["median_first_receive_to_play_ms"]
                             - baseline[0]["median_first_receive_to_play_ms"], 1)
        lines.append(f"Test-only {delay} ms prebuffer reduced measured internal silence "
                     f"by {reduction}% across six unstable directions, while increasing "
                     f"baseline median first-frame delay by {startup_cost} ms. It did not "
                     "eliminate all queue blocks.")
    lines += ["", "All valid cases have matching sent, received, and played frame identities, "
              "confirmed network counters, server logs, and healthy host checks.", "",
              "The browser PCM observer uses ScriptProcessorNode. Its callback timing and "
              "the signal oracle's edge trimming can produce duration errors even in baseline "
              "runs without queue blocks. Queue-block events come from the AudioWorklet and "
              "are independent of that observer. The extra prebuffer is a test-only command "
              "delay, not a selected production parameter.", ""]
    if "lightweight" in data:
        lines += ["## Without the PCM observer", "",
                  "The six separate runs remove ScriptProcessorNode while retaining frame and "
                  "AudioWorklet queue telemetry. They do not record PCM samples.", "",
                  "| Link | Frames | Queue blocks / duration | Median first frame delay | "
                  "Median receive-to-play |",
                  "|---|---:|---:|---:|---:|"]
        for group in data["lightweight"]:
            lines.append(f"| {group['profile']} | {group['frames']} | "
                         f"{group['queue_blocks']} / {group['queue_block_duration_ms']} ms | "
                         f"{group['median_first_receive_to_play_ms']} ms | "
                         f"{group['median_receive_to_play_ms']} ms |")
        lines.append("")
    lines += ["## Individual full-observer runs", "",
              "| Run | Direction | Frames | Internal silence | Queue blocks / duration | "
              "First frame delay | Median receive-to-play |",
              "|---|---|---:|---:|---:|---:|---:|"]
    for case in data["cases"]:
        for row in case["directions"]:
            lines.append(f"| `{case['directory']}` | {row['direction']} | {row['frames']} | "
                         f"{row['output_internal_silence_ms']} ms | {row['queue_blocks']} / "
                         f"{row['queue_block_duration_ms']} ms | "
                         f"{row['first_receive_to_play_ms']} ms | "
                         f"{row['median_receive_to_play_ms']} ms |")
    lines.append("")
    if data["excluded"]:
        lines += ["## Incomplete attempts", ""]
        lines += [f"- `{item['directory']}`: {item['reason']}." for item in data["excluded"]]
        lines.append("")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runs", type=Path, required=True)
    parser.add_argument("--lightweight-runs", type=Path)
    parser.add_argument("--expected-bundle", required=True)
    args = parser.parse_args()
    data = analyze(args.runs, args.expected_bundle)
    if args.lightweight_runs:
        data["lightweight"] = analyze_lightweight(args.lightweight_runs, args.expected_bundle)
    (args.runs / "analysis.json").write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    (args.runs / "report.md").write_text(markdown(data), encoding="utf-8")
    print(args.runs / "report.md")


if __name__ == "__main__":
    main()
