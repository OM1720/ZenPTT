"""Run sequential three-party lease experiments against one installed AB bundle."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import time
import uuid
import zipfile

from clock_offset import estimate as estimate_clock_offset
from prepare_audio import prepare, sha256
from run import (IMAGE, ROOT, SEEDS, build, capture, docker, events, installed_bundle,
                 now, save_json, server_event_counts, source_state, ssh_logs, tc_stats,
                 url_health, wait_event)


MODES = ("blackout-1.25", "blackout-1.5", "blackout-2", "blackout-3",
         "disappear", "voluntary", "cancel")


def verify_package(package_dir: Path, lease_seconds: int, installed_sha256: str) -> None:
    archive = package_dir / "zenptt-server.zip"
    if sha256(archive) != installed_sha256:
        raise RuntimeError("Installed stand bundle differs from the selected lease package")
    with zipfile.ZipFile(archive) as bundle:
        source = bundle.read("server/app/session.py").decode("utf-8")
    match = re.search(r"^FLOOR_LEASE_SECONDS = ([0-9.]+)$", source, re.MULTILINE)
    if match is None or float(match[1]) != lease_seconds:
        raise RuntimeError("Selected lease package has a different floor lease")


def start_bot(name: str, role: str, mode: str, case: Path, audio: Path,
              server_url: str, channel: str) -> None:
    out = case / role
    out.mkdir()
    docker("run", "-d", "--name", name, "--cap-add", "NET_ADMIN",
           "--mount", f"type=bind,src={out.resolve()},dst=/out",
           "--mount", f"type=bind,src={audio.resolve()},dst=/audio,readonly",
           "--mount", f"type=bind,src={(ROOT / 'scripts/poor-link/bot.py').resolve()},dst=/probe_base.py,readonly",
           "--mount", f"type=bind,src={(ROOT / 'scripts/poor-link/lease_bot.py').resolve()},dst=/lease_probe.py,readonly",
           "-e", f"ZENPTT_ROLE={role}", "-e", f"ZENPTT_LEASE_MODE={mode}",
           "-e", f"ZENPTT_URL={server_url}", "-e", f"ZENPTT_CHANNEL={channel}",
           IMAGE, "python", "/lease_probe.py")


def summarize(sender: list[dict], receiver: list[dict], contender: list[dict],
              server_log: str, fault: dict | None) -> dict:
    sender_grants = {item["ordinal"]: item for item in sender if item.get("event") == "grant"}
    received = {item["burst_id"]: item for item in receiver if item.get("event") == "received"}
    requests = [item["at"] for item in contender
                if item.get("event") == "socket_send" and item.get("type") == "ptt_request"]
    grant = next((item for item in contender if item.get("event") == "grant"), None)
    request_start = next((item["at"] for item in contender
                          if item.get("event") == "contender_request_start"), None)
    second = sender_grants.get(2)
    release = next((item for item in sender if item.get("event") == "control"
                    and item.get("type") == "ptt_ended" and second is not None
                    and item.get("burst_id") == second["burst_id"]
                    and item.get("state") == "draining"), None)
    server_requests = []
    server_grants = {}
    server_releases = {}
    contender_request_ids = {item["request_id"] for item in contender
                             if item.get("event") == "socket_send"
                             and item.get("type") == "ptt_request"}
    for line in server_log.splitlines():
        request = re.search(
            r"floor_request session=(\S+) channel=(\S+) request_id=(\S+) "
            r"at_ms=(\d+) at_unix_ms=(\d+)", line)
        if request and request[3] in contender_request_ids:
            server_requests.append({"session": request[1], "channel": request[2],
                                    "request_id": request[3], "at_ms": int(request[4]),
                                    "at_unix_ms": int(request[5])})
        issued = re.search(
            r"grant session=(\S+) burst=\d+ burst_id=(\S+) "
            r"at_ms=(\d+) at_unix_ms=(\d+)", line)
        if issued:
            server_grants[issued[2]] = {"session": issued[1],
                                        "at_ms": int(issued[3]),
                                        "at_unix_ms": int(issued[4])}
        released = re.search(
            r"floor_(?:released|expired) burst_id=(\S+) reason=(\S+) "
            r"at_ms=(\d+) at_unix_ms=(\d+)", line)
        if released:
            server_releases.setdefault(released[1], {
                "reason": released[2], "at_ms": int(released[3]),
                "at_unix_ms": int(released[4])})
    contender_burst_id = grant["burst_id"] if grant else None
    second_burst_id = second["burst_id"] if second else None
    known_bursts = {item["burst_id"] for item in sender_grants.values()}
    if contender_burst_id is not None:
        known_bursts.add(contender_burst_id)
    server_evidence_complete = (
        bool(server_requests) and known_bursts <= server_grants.keys()
        and known_bursts <= server_releases.keys()
    )
    server_no_overlap = None
    if server_evidence_complete:
        ordered = sorted(known_bursts, key=lambda burst_id: server_grants[burst_id]["at_ms"])
        server_no_overlap = all(
            server_releases[earlier]["at_ms"] <= server_grants[later]["at_ms"]
            for earlier, later in zip(ordered, ordered[1:])
        )
    server_wait_s = None
    if server_requests and contender_burst_id in server_grants:
        server_wait_s = round((server_grants[contender_burst_id]["at_ms"]
                               - server_requests[0]["at_ms"]) / 1000, 3)
    release_to_grant_s = None
    if second_burst_id in server_releases and contender_burst_id in server_grants:
        release_to_grant_s = round((server_grants[contender_burst_id]["at_ms"]
                                    - server_releases[second_burst_id]["at_ms"]) / 1000, 3)
    planned = {item["ordinal"]: item["planned_frames"] for item in sender
               if item.get("event") == "send_start"}
    delivered = {}
    for ordinal, item in sender_grants.items():
        arrival = received.get(item["burst_id"])
        delivered[ordinal] = (arrival["frames"] - sum(count for _, count in
                              arrival.get("loss_ranges", []))) if arrival else 0
    return {"planned_frames": planned, "delivered_frames": delivered,
            "sender_grants": sender_grants,
            "sender_results": [item for item in sender if item.get("event") in
                               {"send_result", "sender_canceled"}],
            "contender_request_start": request_start,
            "contender_request_times": requests,
            "contender_grant": grant,
            "contender_wait_s": round(grant["at"] - request_start, 3)
            if grant is not None and request_start is not None else None,
            "sender_release_observed_at": release["at"] if release else None,
            "server_contender_requests": server_requests,
            "server_grants": {burst_id: server_grants[burst_id] for burst_id in known_bursts
                              if burst_id in server_grants},
            "server_releases": {burst_id: server_releases[burst_id] for burst_id in known_bursts
                                if burst_id in server_releases},
            "server_evidence_complete": server_evidence_complete,
            "server_no_overlap": server_no_overlap,
            "server_contender_wait_s": server_wait_s,
            "server_release_to_grant_s": release_to_grant_s,
            "server_lease_expired_at_ms": server_releases.get(second_burst_id, {}).get("at_ms")
            if server_releases.get(second_burst_id, {}).get("reason") == "lease_expired" else None,
            "fault": fault}


def run_case(mode: str, repeat: int, direction: str, lease_seconds: int, root: Path,
             audio: Path, server_url: str, ssh: dict) -> dict:
    case = root / f"{mode}-r{repeat}-{direction}"
    case.mkdir()
    names = {role: "zpt-" + uuid.uuid4().hex[:12]
             for role in ("sender", "receiver", "contender")}
    channel = "TEST.LEASE." + uuid.uuid4().hex[:12].upper()
    started = now()
    record = {"mode": mode, "repeat": repeat, "direction": direction,
              "lease_seconds": lease_seconds,
              "seed": SEEDS[repeat - 1], "channel": channel, "started": started,
              "containers": names}
    save_json(case / "settings.json", record)
    samples = []
    fault = None
    exits = {}
    try:
        for role in ("receiver", "contender", "sender"):
            start_bot(names[role], role, mode, case, audio, server_url, channel)
            wait_event(names[role], "ready", None, time.monotonic() + 25)
        (case / "sender/start").touch()
        second = wait_event(names["sender"], "grant", 2, time.monotonic() + 90)
        target = second["at"] + 5
        time.sleep(max(0, target - time.time() - 0.1))
        if mode.startswith("blackout-"):
            duration = float(mode.split("-", 1)[1])
            observed = docker("exec", names["sender"], "sh", "-c",
                              "tc qdisc add dev eth0 root netem loss 100% && date +%s.%N").stdout
            enabled = re.search(r"([0-9]+\.[0-9]+)\s*$", observed)
            try:
                (case / "contender/start").touch()
                time.sleep(duration)
                observed = docker("exec", names["sender"], "sh", "-c",
                                  "tc -s qdisc show dev eth0 && "
                                  "tc qdisc del dev eth0 root && date +%s.%N").stdout
            finally:
                docker("exec", names["sender"], "tc", "qdisc", "del",
                       "dev", "eth0", "root", check=False)
            disabled = re.search(r"([0-9]+\.[0-9]+)\s*$", observed)
            if not enabled or not disabled:
                raise RuntimeError("Lease blackout timestamps are missing")
            counters = observed[:disabled.start()]
            fault = {"enabled_at": float(enabled[1]), "disabled_at": float(disabled[1]),
                     "requested_duration_s": duration,
                     "counter_confirmed": bool(re.search(r"dropped [1-9][0-9]*", counters))}
            samples.append({"at": now(), "role": "sender", "phase": "fault_active",
                            "raw": counters})
        elif mode == "disappear":
            fault = {"killed_at": now()}
            docker("kill", names["sender"])
            (case / "contender/start").touch()
        elif mode == "cancel":
            (case / "contender/start").touch()
            (case / "sender/cancel").touch()
        else:
            (case / "contender/start").touch()
        for role in ("sender", "contender"):
            waited = docker("wait", names[role], timeout=180, check=False)
            exits[role] = int(waited.stdout.strip()) if waited.stdout.strip().isdigit() else None
            (case / f"receiver/{role}_done").touch()
        waited = docker("wait", names["receiver"], timeout=35, check=False)
        exits["receiver"] = int(waited.stdout.strip()) if waited.stdout.strip().isdigit() else None
    except Exception as error:
        record["error"] = f"{type(error).__name__}: {error}"
        (case / "receiver").mkdir(exist_ok=True)
        (case / "receiver/sender_done").touch()
        (case / "receiver/contender_done").touch()
    finally:
        record["finished"] = now()
        for role, name in names.items():
            inspected = docker("inspect", "--format", "{{.State.ExitCode}}", name, check=False)
            exits.setdefault(role, int(inspected.stdout.strip())
                             if inspected.stdout.strip().isdigit() else None)
            try:
                capture(name, case / role)
                samples.append({"at": now(), "role": role, "phase": "final",
                                "raw": tc_stats(name)})
            except Exception as error:
                record.setdefault("collection_errors", []).append(
                    f"{role}: {type(error).__name__}: {error}")
            docker("rm", "-f", name, check=False)
        save_json(case / "network.json", {"samples": samples, "fault": fault})
        record["exits"] = exits
        observed = {role: events(case / role / "stdout.log") for role in names}
        try:
            record["server_logs"] = ssh_logs(ssh, started, record["finished"], case / "server.log")
        except Exception as error:
            record["server_logs"] = {"error": f"{type(error).__name__}: {error}"}
        log = (case / "server.log").read_text(encoding="utf-8", errors="replace") \
            if (case / "server.log").exists() else ""
        transport_events = [{**item, "role": role} for role, rows in observed.items()
                            for item in rows if item.get("event") == "transport"]
        record["server_events"] = server_event_counts(log, channel, transport_events)
        record["analysis"] = summarize(observed["sender"], observed["receiver"],
                                       observed["contender"], log, fault)
        expected_sender_exit = 137 if mode == "disappear" else 0
        record["classification"] = "complete"
        if (record.get("error") or record.get("collection_errors")
                or record["server_logs"].get("exit_code") != 0
                or exits.get("sender") != expected_sender_exit
                or exits.get("receiver") != 0 or exits.get("contender") != 0
                or 2 not in record["analysis"]["sender_grants"]
                or record["analysis"]["contender_request_start"] is None
                or not record["analysis"]["contender_request_times"]
                or record["analysis"]["contender_grant"] is None
                or not record["analysis"]["server_evidence_complete"]
                or (fault is not None and fault.get("counter_confirmed") is False)):
            record["classification"] = "test_system_error"
        elif record["analysis"]["server_no_overlap"] is False:
            record["classification"] = "protocol_violation"
        elif any(record["analysis"]["delivered_frames"].get(ordinal, 0)
                 < planned for ordinal, planned in record["analysis"]["planned_frames"].items()
                 if ordinal in record["analysis"]["sender_grants"]):
            record["classification"] = "measured_degradation"
        save_json(case / "result.json", record)
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--modes", default=",".join(MODES[:4]))
    parser.add_argument("--lease-seconds", type=int, choices=(2, 3, 4), required=True)
    parser.add_argument("--package-dir", type=Path, required=True)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--server-url")
    parser.add_argument("--ssh-config", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "acceptance/artifacts/poor-link")
    parser.add_argument("--run-id")
    args = parser.parse_args()
    modes = args.modes.split(",")
    if any(mode not in MODES for mode in modes) or not 1 <= args.repeats <= 3:
        parser.error("Choose known lease modes and 1-3 repeats")
    ssh = json.loads(args.ssh_config.read_text(encoding="utf-8"))
    server_url = args.server_url or ssh.get("server_url")
    if not server_url:
        parser.error("Supply --server-url or server_url in the ignored SSH config")
    run_id = args.run_id or datetime.now(timezone.utc).strftime("lease-%Y%m%dT%H%M%SZ")
    if not re.fullmatch(r"[A-Za-z0-9-]{1,64}", run_id):
        parser.error("Invalid run ID")
    root = args.output_dir / run_id
    root.mkdir(parents=True)
    runs = []
    health = {}
    try:
        health["before"] = url_health(server_url)
        installed_sha256 = installed_bundle(ssh)
        verify_package(args.package_dir, args.lease_seconds, installed_sha256)
        image = build()
        audio = prepare(args.output_dir / "audio-cache", IMAGE)
        save_json(root / "source.json", {
            "checkout": source_state(), "image": image,
            "installed_bundle_sha256": installed_sha256,
            "server_clock_offset": estimate_clock_offset(server_url),
            "audio": audio, "modes": modes, "repeats": args.repeats,
            "lease_seconds": args.lease_seconds,
            "seeds": SEEDS,
        })
        for mode in modes:
            for repeat in range(1, args.repeats + 1):
                for direction in ("A-to-B", "B-to-A"):
                    print(f"{now()} {mode} repeat={repeat} {direction}", flush=True)
                    run = run_case(mode, repeat, direction, args.lease_seconds, root,
                                   args.output_dir / "audio-cache", server_url, ssh)
                    runs.append(run)
                    save_json(root / "results.json", {"health": health, "runs": runs})
                    print("  " + run["classification"], flush=True)
    finally:
        try:
            health["after"] = url_health(server_url)
        except Exception as error:
            health["after"] = {"error": f"{type(error).__name__}: {error}"}
        save_json(root / "results.json", {"health": health, "runs": runs})
        print(f"Results: {root / 'results.json'}", flush=True)
    return 1 if any(run["classification"] in {"test_system_error", "protocol_violation"}
                    for run in runs) else 0


if __name__ == "__main__":
    raise SystemExit(main())
