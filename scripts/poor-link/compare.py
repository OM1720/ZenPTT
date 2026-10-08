"""Compare the four fixed poor-link variants using their saved raw evidence."""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
from statistics import median


VARIANTS = ("R", "A", "B", "AB")


def read_events(path: Path):
    with path.open(encoding="utf-8", errors="replace") as stream:
        for line in stream:
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                yield {"event": "invalid_json"}


def bot_traffic(path: Path) -> dict:
    media_messages = media_bytes = control_messages = 0
    first_network = last_network = None
    send_failures = 0
    for event in read_events(path):
        if event.get("event") == "socket_send":
            if "count" in event:
                media_messages += 1
                media_bytes += event["bytes"]
            else:
                control_messages += 1
        elif event.get("event") == "socket_send_failed":
            send_failures += 1
        elif event.get("event") == "network":
            if first_network is None:
                first_network = event
            last_network = event
    sampled = None
    if first_network is not None and last_network is not None:
        sampled = {name: last_network[name] - first_network[name]
                   for name in ("rx_bytes", "tx_bytes", "rx_packets", "tx_packets")}
    return {"media_messages": media_messages, "media_bytes": media_bytes,
            "control_messages": control_messages, "send_failures": send_failures,
            "sampled_network": sampled}


def case_key(run: dict) -> tuple[str, int, str]:
    return run["profile"], run["repeat"], run["direction"]


def case_metrics(root: Path, run: dict) -> dict:
    profile, repeat, direction = case_key(run)
    case = root / f"{profile}-r{repeat}-{direction}"
    sender = bot_traffic(case / "sender/stdout.log")
    receiver = bot_traffic(case / "receiver/stdout.log")
    bursts = run["analysis"]["bursts"]
    return {
        "classification": run["classification"], "seed": run["seed"],
        "planned_frames": sum(burst["planned_frames"] for burst in bursts),
        "produced_frames": sum(burst["produced_frames"] or 0 for burst in bursts),
        "delivered_frames": sum(burst["delivered_frames"] for burst in bursts),
        "ack_count": sum(burst["ack_count"] for burst in bursts),
        "last_frame_lag_s": [burst["last_frame_lag_s"] for burst in bursts],
        "lag_trace_5s": [burst["lag_trace_5s"] for burst in bursts],
        "bursts": bursts, "sender": sender, "receiver": receiver,
        "server_events": run["server_events"], "server_logs": run["server_logs"],
        "sender_transports": run["analysis"]["sender_transports"],
        "receiver_transports": run["analysis"]["receiver_transports"],
    }


def load_variant(root: Path) -> dict:
    results = json.loads((root / "results.json").read_text(encoding="utf-8"))
    source = json.loads((root / "source.json").read_text(encoding="utf-8"))
    cases = {}
    for run in results["runs"]:
        key = case_key(run)
        if key in cases:
            raise ValueError(f"Duplicate case {key} in {root}")
        cases[key] = case_metrics(root, run)
    return {"source": source, "health": results["health"], "cases": cases,
            "run_count": len(results["runs"])}


def validate_matrix(loaded: dict) -> None:
    reference = loaded["R"]["source"]
    profiles = reference["profiles"]
    repeats = reference["repeats"]
    expected = {(profile, repeat, direction)
                for profile in profiles for repeat in range(1, repeats + 1)
                for direction in ("A-to-B", "B-to-A")}
    if len(expected) != 102:
        raise ValueError(f"Expected the agreed 102-case matrix, found {len(expected)} cases")
    for name, item in loaded.items():
        if item["source"]["audio"]["pcm_sha256"] != reference["audio"]["pcm_sha256"]:
            raise ValueError(f"Audio source differs for {name}")
        if (item["source"]["seeds"] != reference["seeds"]
                or item["source"]["profiles"] != profiles
                or item["source"]["repeats"] != repeats):
            raise ValueError(f"Profiles, repeats, or seeds differ for {name}")
        missing = expected - item["cases"].keys()
        extra = item["cases"].keys() - expected
        if missing or extra:
            raise ValueError(f"{name} has {len(missing)} missing and {len(extra)} extra cases")
        if item["health"].get("before", {}).get("status") != "ok" or item["health"].get(
                "after", {}).get("status") != "ok":
            raise ValueError(f"{name} has incomplete stand health checks")
        for key, case in item["cases"].items():
            if case["seed"] != reference["seeds"][key[1] - 1]:
                raise ValueError(f"Case seed differs for {name} {key}")
            if (case["classification"] == "test_system_error"
                    or case["server_logs"].get("exit_code") != 0):
                raise ValueError(f"{name} {key} lacks reliable test evidence")


def summarize(cases: dict) -> dict:
    grouped = defaultdict(list)
    for key, case in cases.items():
        grouped[key[0]].append(case)
    result = {}
    for profile, values in grouped.items():
        lags = [lag for case in values for lag in case["last_frame_lag_s"]
                if lag is not None and case["classification"] == "complete"]
        burst_lags = [[case["last_frame_lag_s"][index] for case in values
                       if case["classification"] == "complete"
                       and len(case["last_frame_lag_s"]) > index
                       and case["last_frame_lag_s"][index] is not None]
                      for index in range(3)]
        result[profile] = {
            "cases": len(values), "classes": dict(Counter(case["classification"] for case in values)),
            "planned_frames": sum(case["planned_frames"] for case in values),
            "produced_frames": sum(case["produced_frames"] for case in values),
            "delivered_frames": sum(case["delivered_frames"] for case in values),
            "median_last_frame_lag_s_complete": median(lags) if lags else None,
            "median_burst_lag_s_complete": [median(group) if group else None
                                              for group in burst_lags],
            "server_events": {kind: sum(case.get("server_events", {}).get(kind, 0)
                                        for case in values)
                              for kind in ("resumes", "outbound_backpressure",
                                           "outbound_backlog_limit", "timeouts",
                                           "protocol_errors")},
            "median_ack_count": median(case["ack_count"] for case in values),
            "median_media_messages": median(case["sender"]["media_messages"] for case in values),
            "median_sender_tx_bytes_sampled": median(
                case["sender"]["sampled_network"]["tx_bytes"] for case in values
                if case["sender"]["sampled_network"] is not None
            ) if any(case["sender"]["sampled_network"] is not None for case in values) else None,
        }
    return result


def render(data: dict) -> str:
    lines = ["# ZenPTT R/A/B/AB comparison", "",
             "Measurements use the saved per-case bot and server evidence."
             " The two bots share one host clock. Sampled network bytes cover the bot"
             " sampling interval and include TCP overhead.", ""]
    for name in VARIANTS:
        item = data["variants"][name]
        lines.append(f"- **{name}**: {item['run_count']} cases; bundle SHA-256 "
                     f"`{item['source'].get('installed_bundle_sha256', 'see stage manifest')}`; "
                     f"host health {item['health'].get('before', {}).get('status')} / "
                     f"{item['health'].get('after', {}).get('status')}.")
        offset = item["source"].get("server_clock_offset")
        if offset is not None:
            lines.append(f"  - Server clock offset estimate: "
                         f"{offset['estimated_offset_s']:.3f} +/-"
                         f" {offset['uncertainty_s']:.3f} s (HTTP Date).")
    lines += ["", "## Profile summary", "",
              "Each cell is complete cases / profile cases; produced / planned frames; "
              "delivered / planned frames; "
              "median ACKs, media messages, and sampled sender TX KiB per case; "
              "median last-frame lag in seconds for complete cases.", "",
              "| Profile | R | A | B | AB |", "|---|---|---|---|---|"]
    profiles = sorted({key[0] for item in data["variants"].values() for key in item["cases"]})
    for profile in profiles:
        cells = []
        for name in VARIANTS:
            group = data["summary"][name].get(profile)
            if group is None:
                cells.append("missing")
                continue
            lag = group["median_last_frame_lag_s_complete"]
            lag_text = f"{lag:.2f}s" if lag is not None else "n/a"
            sampled_tx = group["median_sender_tx_bytes_sampled"]
            tx_text = f"{sampled_tx / 1024:.1f}KiB" if sampled_tx is not None else "n/a"
            cells.append(f"{group['classes'].get('complete', 0)}/{group['cases']}; "
                         f"made {group['produced_frames']}/{group['planned_frames']}; "
                         f"{group['delivered_frames']}/{group['planned_frames']}; "
                         f"ACK {group['median_ack_count']:g}; "
                         f"media {group['median_media_messages']:g}; "
                         f"TX {tx_text}; {lag_text}")
        lines.append("| " + " | ".join((profile, *cells)) + " |")
    lines += ["", "## Lag across three consecutive bursts", "",
              "Median last-frame lag in seconds for complete cases only; each cell "
              "lists bursts 1/2/3. Missing or truncated bursts are excluded.", "",
              "| Profile | R | A | B | AB |", "|---|---|---|---|---|"]
    for profile in profiles:
        cells = []
        for name in VARIANTS:
            group = data["summary"][name].get(profile)
            if group is None:
                cells.append("missing")
            else:
                cells.append("/".join(f"{lag:.2f}" if lag is not None else "n/a"
                                      for lag in group["median_burst_lag_s_complete"]))
        lines.append("| " + " | ".join((profile, *cells)) + " |")
    lines += ["", "## Server events", "",
              "Each cell lists resumes / backpressure intervals / backlog limits / "
              "timeouts / protocol errors across six cases per profile.", "",
              "| Profile | R | A | B | AB |", "|---|---|---|---|---|"]
    for profile in profiles:
        cells = []
        for name in VARIANTS:
            group = data["summary"][name].get(profile)
            if group is None:
                cells.append("missing")
            else:
                events = group["server_events"]
                cells.append("/".join(str(events[kind]) for kind in
                                      ("resumes", "outbound_backpressure",
                                       "outbound_backlog_limit", "timeouts",
                                       "protocol_errors")))
        lines.append("| " + " | ".join((profile, *cells)) + " |")
    lines += ["", "## Individual cases", "",
              "Each cell is produced / planned frames, delivered / planned frames, "
              "ACK count, media message count. "
              "The JSON includes all three burst traces and server event counts.", "",
              "| Profile | Repeat | Direction | R | A | B | AB |",
              "|---|---:|---|---|---|---|---|"]
    keys = sorted({key for item in data["variants"].values() for key in item["cases"]})
    for key in keys:
        cells = []
        for name in VARIANTS:
            case = data["variants"][name]["cases"].get(key)
            if case is None:
                cells.append("missing")
            else:
                cells.append(f"made {case['produced_frames']}/{case['planned_frames']}, "
                             f"delivered {case['delivered_frames']}/{case['planned_frames']}, "
                             f"ACK {case['ack_count']}, media {case['sender']['media_messages']} "
                             f"({case['classification']})")
        lines.append("| " + " | ".join((key[0], str(key[1]), key[2], *cells)) + " |")
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in VARIANTS:
        parser.add_argument(f"--{name.lower()}", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    loaded = {name: load_variant(getattr(args, name.lower())) for name in VARIANTS}
    validate_matrix(loaded)
    data = {"variants": loaded, "summary": {name: summarize(item["cases"])
                                           for name, item in loaded.items()}}
    serializable = {"variants": {name: {**item, "cases": {
        "|".join(map(str, key)): value for key, value in item["cases"].items()}}
        for name, item in loaded.items()}, "summary": data["summary"]}
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "comparison.json").write_text(
        json.dumps(serializable, indent=2) + "\n", encoding="utf-8")
    (args.output_dir / "comparison.md").write_text(render(data), encoding="utf-8")


if __name__ == "__main__":
    main()
