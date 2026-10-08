"""Compare saved two-, three-, and four-second lease competition runs."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from statistics import median


OUTAGE_MODES = ("blackout-1.25", "blackout-1.5", "blackout-2", "blackout-3")
DIRECTIONS = ("A-to-B", "B-to-A")


def load_run(path: Path, lease: int) -> dict:
    source = json.loads((path / "source.json").read_text(encoding="utf-8"))
    results = json.loads((path / "results.json").read_text(encoding="utf-8"))
    if source["lease_seconds"] != lease or source["modes"] != list(OUTAGE_MODES):
        raise ValueError(f"Wrong lease or modes in {path}")
    if source["repeats"] != 3 or len(source["seeds"]) < 3:
        raise ValueError(f"Wrong repeats or seeds in {path}")
    if (results["health"].get("before", {}).get("status") != "ok"
            or results["health"].get("after", {}).get("status") != "ok"):
        raise ValueError(f"Incomplete stand health checks in {path}")
    cases = {}
    for run in results["runs"]:
        key = run["mode"], run["repeat"], run["direction"]
        if key in cases:
            raise ValueError(f"Duplicate lease case {key} in {path}")
        if (run["classification"] in {"test_system_error", "protocol_violation"}
                or run["server_logs"].get("exit_code") != 0):
            raise ValueError(f"Unreliable lease case {key} in {path}")
        if (not run["analysis"].get("server_evidence_complete")
                or run["analysis"].get("server_no_overlap") is not True):
            raise ValueError(f"Missing server floor evidence for {key} in {path}")
        if run["seed"] != source["seeds"][run["repeat"] - 1]:
            raise ValueError(f"Wrong seed for {key} in {path}")
        cases[key] = run
    expected = {(mode, repeat, direction) for mode in OUTAGE_MODES
                for repeat in (1, 2, 3) for direction in DIRECTIONS}
    if cases.keys() != expected:
        raise ValueError(f"Lease {lease} has {len(expected - cases.keys())} missing cases")
    return {"source": source, "health": results["health"], "cases": cases}


def compare(roots: dict[int, Path]) -> dict:
    loaded = {lease: load_run(roots[lease], lease) for lease in (2, 3, 4)}
    reference = loaded[2]["source"]
    for lease, item in loaded.items():
        source = item["source"]
        if (source["audio"]["pcm_sha256"] != reference["audio"]["pcm_sha256"]
                or source["seeds"] != reference["seeds"]):
            raise ValueError(f"Audio or seeds differ for lease {lease}")
        files = {path.replace("\\", "/"): digest for path, digest
                 in source["checkout"]["files"].items()
                 if path.replace("\\", "/") != "server/app/session.py"}
        reference_files = {path.replace("\\", "/"): digest for path, digest
                           in reference["checkout"]["files"].items()
                           if path.replace("\\", "/") != "server/app/session.py"}
        if files != reference_files:
            raise ValueError(f"Non-lease source differs for lease {lease}")
    rows = []
    for mode in OUTAGE_MODES:
        for repeat in (1, 2, 3):
            for direction in DIRECTIONS:
                for lease, item in loaded.items():
                    run = item["cases"][(mode, repeat, direction)]
                    analysis = run["analysis"]
                    planned = analysis["planned_frames"].get("2")
                    delivered = analysis["delivered_frames"].get("2")
                    if (planned is None or delivered is None
                            or analysis["contender_wait_s"] is None
                            or analysis["server_contender_wait_s"] is None
                            or analysis["server_release_to_grant_s"] is None):
                        raise ValueError(f"Missing second burst for lease {lease} {mode} {repeat}")
                    rows.append({
                        "mode": mode, "repeat": repeat, "direction": direction,
                        "lease_seconds": lease, "planned_second_frames": planned,
                        "delivered_second_frames": delivered,
                        "contender_wait_s": analysis["contender_wait_s"],
                        "server_contender_wait_s": analysis["server_contender_wait_s"],
                        "server_release_to_grant_s": analysis["server_release_to_grant_s"],
                        "server_lease_expired_at_ms": analysis["server_lease_expired_at_ms"],
                        "fault": analysis["fault"], "classification": run["classification"],
                    })
    summary = []
    for mode in OUTAGE_MODES:
        for lease in (2, 3, 4):
            subset = [row for row in rows if row["mode"] == mode
                      and row["lease_seconds"] == lease]
            waits = [row["contender_wait_s"] for row in subset
                     if row["contender_wait_s"] is not None]
            summary.append({
                "mode": mode, "lease_seconds": lease, "cases": len(subset),
                "second_delivered_frames": sum(row["delivered_second_frames"] for row in subset),
                "second_planned_frames": sum(row["planned_second_frames"] for row in subset),
                "contender_wait_s_min": min(waits) if waits else None,
                "contender_wait_s_median": median(waits) if waits else None,
                "contender_wait_s_max": max(waits) if waits else None,
                "server_contender_wait_s_median": median(
                    row["server_contender_wait_s"] for row in subset),
                "server_release_to_grant_s_median": median(
                    row["server_release_to_grant_s"] for row in subset),
                "lease_expirations": sum(row["server_lease_expired_at_ms"] is not None
                                         for row in subset),
            })
    return {"probe_images_differ": len({item["source"]["image"]
                                      for item in loaded.values()}) != 1,
            "sources": {str(lease): {"bundle_sha256": item["source"][
                "installed_bundle_sha256"], "image": item["source"]["image"]}
                        for lease, item in loaded.items()},
            "summary": summary, "cases": rows}


def render(data: dict) -> str:
    lines = ["# ZenPTT lease competition measurements", "",
             "The sender's second burst is interrupted on its fifth second. "
             "The third bot requests the floor during the fault. "
             "Wait includes its one-second retry interval.", "",
             "The probe images have different IDs after separate builds; "
             "the recorded probe sources and pinned dependencies match."
             if data["probe_images_differ"] else
             "The probe image ID matches across all runs.", "",
             "| Outage | Lease s | Second burst delivered/planned frames | "
             "Contender wait min/median/max s | Server wait median s | "
             "Release-to-grant median s | Server lease expirations |",
             "|---|---:|---:|---|---:|---:|---:|"]
    for row in data["summary"]:
        waits = "/".join(f"{row[f'contender_wait_s_{kind}']:.2f}"
                         for kind in ("min", "median", "max"))
        lines.append(f"| {row['mode']} | {row['lease_seconds']} | "
                     f"{row['second_delivered_frames']}/{row['second_planned_frames']} | "
                     f"{waits} | {row['server_contender_wait_s_median']:.2f} | "
                     f"{row['server_release_to_grant_s_median']:.2f} | "
                     f"{row['lease_expirations']}/{row['cases']} |")
    lines += ["", "## Individual cases", "",
              "| Outage | Repeat | Direction | Lease s | Second burst frames | "
              "Contender wait s | Classification |",
              "|---|---:|---|---:|---:|---:|---|"]
    for row in data["cases"]:
        wait = row["contender_wait_s"]
        lines.append(f"| {row['mode']} | {row['repeat']} | {row['direction']} | "
                     f"{row['lease_seconds']} | {row['delivered_second_frames']}/"
                     f"{row['planned_second_frames']} | "
                     f"{wait:.2f} | {row['classification']} |")
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for lease in (2, 3, 4):
        parser.add_argument(f"--lease-{lease}", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    data = compare({lease: getattr(args, f"lease_{lease}") for lease in (2, 3, 4)})
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "lease-comparison.json").write_text(
        json.dumps(data, indent=2) + "\n", encoding="utf-8")
    (args.output_dir / "lease-comparison.md").write_text(render(data), encoding="utf-8")


if __name__ == "__main__":
    main()
