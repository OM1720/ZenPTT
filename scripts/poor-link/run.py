"""Run isolated, sequential poor-link measurements against an existing test host."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shlex
import statistics
import subprocess
import time
from urllib.parse import urlsplit
import urllib.request
import uuid

from analyze import analyze_pair, summarize_runs
from clock_offset import estimate as estimate_clock_offset
from prepare_audio import prepare, sha256


ROOT = Path(__file__).resolve().parents[2]
SEEDS = (1009, 2017, 3037)
PROFILES = (
    "baseline", "sender-24", "sender-32", "sender-48",
    "listener-24", "listener-32", "listener-48", "unstable",
    "sender-blackout-1", "sender-blackout-2", "sender-blackout-3",
    "listener-blackout-3",
)
EXTRA_PROFILES = (
    "sender-64", "sender-80", "sender-96", "listener-64", "listener-80", "listener-96",
    "listener-shaped-48", "listener-shaped-64", "listener-shaped-80", "listener-shaped-96",
    "sender-blackout-1.25", "sender-blackout-1.5", "sender-blackout-1.75",
)
IMAGE = "zenptt-poor-link:local"


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def save_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def command(args: list[str], timeout: int = 45, *, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {args[0]} {args[1]}: {result.stderr[:300]}")
    return result


def docker(*args: str, timeout: int = 45, check: bool = True) -> subprocess.CompletedProcess[str]:
    return command(["docker", *args], timeout, check=check)


def events(path: Path) -> list[dict]:
    result = []
    if path.exists():
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            try:
                result.append(json.loads(line))
            except json.JSONDecodeError:
                result.append({"event": "invalid_json", "raw": line[:200]})
    return result


def url_health(server_url: str) -> dict:
    parsed = urlsplit(server_url)
    url = ("https" if parsed.scheme == "wss" else "http") + "://" + parsed.netloc + "/health"
    with urllib.request.urlopen(url, timeout=10) as response:
        payload = response.read(2000).decode("utf-8")
        if response.status != 200 or json.loads(payload).get("status") != "ok":
            raise RuntimeError("Test host health response is not OK")
    return {"at": now(), "status": "ok"}


def source_state() -> dict:
    result = command(["git", "rev-parse", "HEAD"])
    files = [*sorted((ROOT / "headless/src/zenptt_headless").glob("*.py")),
             ROOT / "headless/Dockerfile", ROOT / "headless/requirements.lock",
             *sorted((ROOT / "server/app").glob("*.py")),
             ROOT / "server/Dockerfile", ROOT / "server/requirements.lock",
             *sorted((ROOT / "scripts/poor-link").glob("*.py")),
             ROOT / "scripts/poor-link/Dockerfile", ROOT / "scripts/poor-link/audio-source.json"]
    return {"commit": result.stdout.strip(),
            "files": {str(path.relative_to(ROOT)): sha256(path) for path in files}}


def installed_bundle(ssh: dict) -> str:
    package = PurePosixPath(ssh["runtime_path"]).parent / "zenptt-server.zip"
    result = command(["plink", "-batch", "-agent", "-l", ssh["user"],
                      "-hostkey", ssh["host_key"], ssh["host"],
                      "sha256sum " + shlex.quote(str(package))])
    digest = result.stdout.split(maxsplit=1)[0]
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise RuntimeError("Could not verify installed bundle SHA-256")
    return digest


def build() -> str:
    docker("build", "--pull=false", "-t", "zenptt-headless-test", str(ROOT / "headless"), timeout=600)
    docker("build", "--pull=false", "-t", IMAGE, "-f", str(ROOT / "scripts/poor-link/Dockerfile"),
           str(ROOT / "scripts/poor-link"), timeout=600)
    return docker("image", "inspect", "--format", "{{.Id}}", IMAGE).stdout.strip()


def qdisc_setup(profile: str, role: str, seed: int) -> list[str]:
    if re.fullmatch(r"sender-(24|32|48|64|80|96)", profile) and role == "sender":
        rate = profile.split("-")[-1]
        return ["tc", "qdisc", "add", "dev", "eth0", "root", "netem", "rate", f"{rate}kbit",
                "delay", "200ms", "50ms", "distribution", "normal", "seed", str(seed)]
    if re.fullmatch(r"listener-(24|32|48|64|80|96)", profile) and role == "receiver":
        rate = profile.split("-")[-1]
        return ["tc qdisc add dev eth0 handle ffff: ingress && "
                f"tc filter add dev eth0 parent ffff: protocol ip u32 match u32 0 0 police rate {rate}kbit burst 1500 drop flowid :1"]
    if profile.startswith("listener-shaped-") and role == "receiver":
        rate = profile.split("-")[-1]
        return ["ip link add ifb0 type ifb && ip link set ifb0 up && "
                "tc qdisc add dev eth0 handle ffff: ingress && "
                "tc filter add dev eth0 parent ffff: protocol ip u32 match u32 0 0 "
                "action mirred egress redirect dev ifb0 && "
                f"tc qdisc add dev ifb0 root netem rate {rate}kbit"]
    if profile == "unstable" and role == "sender":
        return ["tc", "qdisc", "add", "dev", "eth0", "root", "netem", "delay", "200ms",
                "100ms", "distribution", "normal", "loss", "1%", "seed", str(seed)]
    return []


def start_bot(name: str, role: str, case: Path, audio: Path, server_url: str,
              channel: str, profile: str, seed: int) -> None:
    out = case / role
    out.mkdir()
    args = ["run", "-d", "--name", name, "--cap-add", "NET_ADMIN",
            "--mount", f"type=bind,src={out.resolve()},dst=/out",
            "--mount", f"type=bind,src={audio.resolve()},dst=/audio,readonly",
            "--mount", f"type=bind,src={(ROOT / 'scripts/poor-link/bot.py').resolve()},dst=/probe.py,readonly",
            "-e", f"ZENPTT_ROLE={role}", "-e", f"ZENPTT_URL={server_url}",
            "-e", f"ZENPTT_CHANNEL={channel}", IMAGE]
    args += ["python", "/probe.py"]
    docker(*args)


def capture(name: str, out: Path) -> list[dict]:
    out.mkdir(exist_ok=True)
    result = docker("logs", name, check=False)
    (out / "stdout.log").write_text(result.stdout, encoding="utf-8")
    (out / "stderr.log").write_text(result.stderr, encoding="utf-8")
    return events(out / "stdout.log")


def tc_stats(name: str) -> str:
    qdisc = docker("exec", name, "tc", "-s", "qdisc", "show", "dev", "eth0", check=False)
    filters = docker("exec", name, "tc", "-s", "filter", "show", "dev", "eth0", "ingress", check=False)
    details = docker("exec", name, "sh", "-c",
                     "if ip link show ifb0 >/dev/null 2>&1; then tc -s qdisc show dev ifb0; fi; "
                     "cat /proc/net/dev; ss -tin", check=False)
    return qdisc.stdout + qdisc.stderr + "\n" + filters.stdout + filters.stderr + "\n" + details.stdout


def wait_event(name: str, kind: str, ordinal: int | None, deadline: float) -> dict:
    while time.monotonic() < deadline:
        result = docker("logs", name, check=False, timeout=10)
        for event in (json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")):
            if event.get("event") == kind and (ordinal is None or event.get("ordinal") == ordinal):
                return event
        if docker("inspect", "--format", "{{.State.Running}}", name, check=False).stdout.strip() != "true":
            raise RuntimeError(f"{name} exited before {kind} {ordinal}")
        time.sleep(0.3)
    raise TimeoutError(f"Timed out waiting for {kind} {ordinal}")


def ssh_logs(config: dict, since: str, until: str, path: Path) -> dict:
    args = ["plink", "-batch", "-agent", "-l", config["user"], "-hostkey", config["host_key"],
            config["host"], "cd " + config["runtime_path"] + " && docker compose logs --no-color "
            + "--timestamps --since=" + since + " --until=" + until]
    result = command(args, timeout=45, check=False)
    path.write_text(result.stdout + "\n" + result.stderr, encoding="utf-8")
    return {"exit_code": result.returncode, "bytes": path.stat().st_size}


def server_event_counts(log: str, channel: str, bot_events: list[dict] | None = None) -> dict:
    """Match joins directly and resumes by unique bot transport timing."""
    channel_hash = hashlib.sha256(channel.encode("utf-8")).hexdigest()[:8]
    sessions = set(re.findall(r"join session=([0-9a-f-]{36}) channel=" + channel_hash, log))
    transports = [event for event in bot_events or []
                  if event.get("event") == "transport" and event.get("count", 0) > 1]
    candidates = []
    for line in log.splitlines():
        match = re.search(r"INFO:\s+resume session=([0-9a-f-]{36})", line)
        stamp = re.search(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)\.(\d+)Z", line)
        if not line.startswith("zenptt-server-") or not match or not stamp:
            continue
        at = datetime.fromisoformat(stamp[1] + "." + stamp[2][:6] + "+00:00").timestamp()
        nearby = [index for index, event in enumerate(transports) if abs(event["at"] - at) <= 1]
        if len(nearby) == 1:
            candidates.append((match[1], at, nearby[0]))
    correlations = []
    for session, at, index in candidates:
        if sessions and sum(candidate[2] == index for candidate in candidates) == 1:
            sessions.add(session)
            correlations.append({"session": session, "role": transports[index].get("role"),
                                 "transport_count": transports[index]["count"],
                                 "server_at": at, "bot_at": transports[index]["at"],
                                 "delta_s": round(transports[index]["at"] - at, 6),
                                 "method": "unique_transport_within_1s"})
    patterns = {"outbound_backlog_limit": "outbound_backlog_limit",
                "outbound_backpressure": "outbound_backpressure session=", "resumes": "resume session=",
                "protocol_errors": "protocol_error", "server_receive_gaps": "server_receive_gap",
                "server_send_gaps": "server_send_gap", "timeouts": "timeout",
                "outbound_disconnects": "outbound_disconnected"}
    result = {"matched_sessions": len(sessions), "resume_correlations": correlations,
              **{key: 0 for key in patterns}}
    for line in log.splitlines():
        if not line.startswith("zenptt-server-"):
            continue
        if not any("session=" + session in line or "session=" + session[-6:] in line
                   for session in sessions):
            continue
        for key, pattern in patterns.items():
            result[key] += pattern in line
    return result


def classify(analysis: dict, exits: dict, server: dict, tc: list[dict], fault: dict | None) -> str:
    if any(exits.get(role) != 0 for role in ("sender", "receiver")) or server.get("exit_code"):
        return "test_system_error"
    if any(problem.startswith(("process_event:", "missing_send_result:", "invalid_json"))
           for problem in analysis["problems"]):
        return "test_system_error"
    if fault and not fault.get("counter_confirmed"):
        return "test_system_error"
    if any(problem.startswith(("unexpected_burst:", "sender_burst_mismatch:",
                               "duplicate_completed_burst:")) for problem in analysis["problems"]):
        return "protocol_error"
    if any(burst["send_reason"] in {"protocol_error", "invalid_ack"}
           for burst in analysis["bursts"]):
        return "protocol_error"
    if any(burst["send_reason"] == "final_sequence_mismatch"
           for burst in analysis["bursts"]):
        return "diagnostic_uncertainty"
    if any(burst["missing_from_plan_frames"] > 0 for burst in analysis["bursts"]):
        return "measured_degradation"
    return "complete"


def run_case(profile: str, repeat: int, direction: str, seed: int, root: Path,
             audio: Path, server_url: str, ssh: dict) -> dict:
    case = root / f"{profile}-r{repeat}-{direction}"
    case.mkdir()
    names = {role: "zpt-" + uuid.uuid4().hex[:12] for role in ("sender", "receiver")}
    channel = "TEST.POOR." + uuid.uuid4().hex[:12].upper()
    started = now()
    record: dict = {"profile": profile, "repeat": repeat, "direction": direction,
                    "seed": seed, "channel": channel, "started": started, "containers": names,
                    "network_model": "ingress shaping via IFB" if profile.startswith("listener-shaped-")
                    else "ingress policing" if re.fullmatch(r"listener-\d+", profile)
                    else "egress shaping" if re.fullmatch(r"sender-\d+", profile)
                    else "netem fault" if "blackout" in profile or profile == "unstable" else "none"}
    save_json(case / "settings.json", record)
    fault = None
    samples: list[dict] = []
    exits = {}
    fatal: BaseException | None = None
    try:
        start_bot(names["receiver"], "receiver", case, audio, server_url, channel, profile, seed)
        wait_event(names["receiver"], "ready", None, time.monotonic() + 20)
        start_bot(names["sender"], "sender", case, audio, server_url, channel, profile, seed)
        wait_event(names["sender"], "ready", None, time.monotonic() + 20)
        for role, name in names.items():
            setup = qdisc_setup(profile, role, seed)
            if setup:
                if len(setup) == 1:
                    docker("exec", name, "sh", "-c", setup[0])
                else:
                    docker("exec", name, *setup)
                samples.append({"at": now(), "role": role, "phase": "configured", "raw": tc_stats(name)})
        (case / "sender/start").touch()
        if "blackout" in profile:
            grant = wait_event(names["sender"], "grant", 2, time.monotonic() + 90)
            target = grant["at"] + 5
            time.sleep(max(0, target - time.time() - 0.1))
            victim = names["receiver" if profile.startswith("listener") else "sender"]
            if profile.startswith("listener"):
                on = ("tc qdisc add dev eth0 handle ffff: ingress && "
                      "tc filter add dev eth0 parent ffff: protocol ip u32 match u32 0 0 action drop")
                stats = "tc -s filter show dev eth0 ingress"
                off = "tc qdisc del dev eth0 ingress"
            else:
                on = "tc qdisc add dev eth0 root netem loss 100%"
                stats = "tc -s qdisc show dev eth0"
                off = "tc qdisc del dev eth0 root"
            duration = float(profile.rsplit("-", 1)[1])
            script = ("set -e; " + on + "; printf 'ENABLED '; date +%s.%N; "
                      + f"sleep {duration}; printf 'STATS_START\\n'; {stats}; "
                      + "printf 'STATS_END\\n'; " + off
                      + "; printf 'DISABLED '; date +%s.%N")
            observed = docker("exec", victim, "sh", "-c", script, timeout=duration + 20).stdout
            enabled = re.search(r"^ENABLED ([0-9.]+)$", observed, re.MULTILINE)
            disabled = re.search(r"^DISABLED ([0-9.]+)$", observed, re.MULTILINE)
            if not enabled or not disabled or "STATS_START\n" not in observed or "STATS_END\n" not in observed:
                raise RuntimeError("Blackout command did not return timestamps and counters")
            before = observed.split("STATS_START\n", 1)[1].split("STATS_END\n", 1)[0]
            fault = {"requested_at": target, "enabled_at": float(enabled[1]),
                     "duration_s": duration, "disabled_at": float(disabled[1])}
            fault["actual_duration_s"] = round(fault["disabled_at"] - fault["enabled_at"], 3)
            samples.append({"at": now(), "role": "receiver" if profile.startswith("listener") else "sender",
                            "phase": "fault_active", "raw": before})
            fault["counter_confirmed"] = bool(re.search(r"dropped [1-9][0-9]*", before))
        else:
            time.sleep(25)
        for role, name in names.items():
            samples.append({"at": now(), "role": role, "phase": "post_fault", "raw": tc_stats(name)})
        for role, name in names.items():
            result = docker("wait", name, timeout=180, check=False)
            exits[role] = int(result.stdout.strip()) if result.stdout.strip().isdigit() else None
            if role == "sender":
                (case / "receiver/sender_done").touch()
    except BaseException as error:
        record["error"] = f"{type(error).__name__}: {error}"
        if not isinstance(error, Exception):
            fatal = error
        (case / "receiver").mkdir(exist_ok=True)
        (case / "receiver/sender_done").touch()
    finally:
        record["finished"] = now()
        for role, name in names.items():
            result = docker("inspect", "--format", "{{.State.ExitCode}}", name, check=False)
            exits.setdefault(role, int(result.stdout.strip()) if result.stdout.strip().isdigit() else None)
            try:
                capture(name, case / role)
                samples.append({"at": now(), "role": role, "phase": "final", "raw": tc_stats(name)})
            except Exception as error:
                record.setdefault("collection_errors", []).append(f"{role}: {type(error).__name__}: {error}")
            docker("rm", "-f", name, check=False)
        save_json(case / "network.json", {"samples": samples, "fault": fault})
        record["exits"] = exits
        sender_events = events(case / "sender/stdout.log")
        receiver_events = events(case / "receiver/stdout.log")
        record["analysis"] = analyze_pair(sender_events, receiver_events)
        for event in sender_events + receiver_events:
            if event.get("event") == "invalid_json":
                record["analysis"]["problems"].append("invalid_json")
        try:
            record["server_logs"] = ssh_logs(ssh, started, record["finished"], case / "server.log")
        except Exception as error:
            record["server_logs"] = {"error": f"{type(error).__name__}: {error}"}
        log = (case / "server.log").read_text(encoding="utf-8", errors="replace") if (case / "server.log").exists() else ""
        transport_events = [{**event, "role": role}
                            for role, observed in (("sender", sender_events), ("receiver", receiver_events))
                            for event in observed if event.get("event") == "transport"]
        record["server_events"] = server_event_counts(log, channel, transport_events)
        record["classification"] = classify(record["analysis"], exits, record["server_logs"], samples, fault)
        if (record.get("error") or record.get("collection_errors") or record["server_logs"].get("error")
                or record["server_events"]["matched_sessions"] < 2):
            record["classification"] = "test_system_error"
        save_json(case / "result.json", record)
    if fatal is not None:
        raise fatal
    return record


def render_report(root: Path, runs: list[dict], health: dict) -> None:
    summary = summarize_runs(runs)
    save_json(root / "results.json", {"health": health, "runs": runs, "summary": summary})
    before = health.get("before", {}).get("status", "unknown")
    after = health.get("after", {}).get("status", "pending")
    lines = ["# ZenPTT poor-link measurements", "", "These are measurements, not quality pass thresholds.", "",
             f"Cases: {len(runs)}. Host health before: {before}; after: {after}.", "",
             "## All runs", "", "| Profile | Repeat | Direction | Class | Delivered frames (bursts 1/2/3) | Last-frame lag s (1/2/3) | Max arrival gap ms (1/2/3) | WebSocket transports S/R | Server events |",
             "|---|---:|---|---|---|---|---|---:|---|"]
    for run in runs:
        bursts = run["analysis"]["bursts"]
        delivered = "/".join(str(item["delivered_frames"]) for item in bursts)
        lag = "/".join(str(item["last_frame_lag_s"]) for item in bursts)
        gaps = "/".join(str(item["max_arrival_gap_ms"]) for item in bursts)
        transports = f"{run['analysis']['sender_transports']}/{run['analysis']['receiver_transports']}"
        server = ", ".join(f"{key}:{value}" for key, value in run["server_events"].items()
                           if isinstance(value, int) and value) or "none"
        lines.append(f"| {run['profile']} | {run['repeat']} | {run['direction']} | {run['classification']} | {delivered} | {lag} | {gaps} | {transports} | {server} |")
    lines += ["", "Initial sessions are matched by the case channel. Resumed sessions are correlated with a unique bot reconnection within one second, with evidence saved in resume_correlations. This is time correlation, since server resume lines do not carry a channel identifier. Ambiguous matches are excluded. Server media-gap diagnostics are capped per burst; bot events contain the full frame timing evidence."]
    lines += ["", "## Server recovery and outbound queues", "",
              "| Profile | Resumes | Legacy backlog events | Backpressure intervals | Runs with queue pressure |",
              "|---|---:|---:|---:|---:|"]
    for profile in dict.fromkeys(run["profile"] for run in runs):
        group = [run["server_events"] for run in runs if run["profile"] == profile]
        lines.append(f"| {profile} | {sum(item['resumes'] for item in group)} | "
                     f"{sum(item['outbound_backlog_limit'] for item in group)} | "
                     f"{sum(item['outbound_backpressure'] for item in group)} | "
                     f"{sum(item['outbound_backlog_limit'] > 0 or item['outbound_backpressure'] > 0 for item in group)} |")
    lines += ["", "## Variation by burst", "", "| Profile | Direction | Burst | Delivered min/median/max | Last lag min/median/max s |",
              "|---|---|---:|---|---|"]
    for group in summary["groups"]:
        delivery = "/".join(str(group[f"delivered_{part}"]) for part in ("min", "median", "max"))
        lag = "/".join(str(group[f"last_lag_{part}_s"]) for part in ("min", "median", "max"))
        lines.append(f"| {group['profile']} | {group['direction']} | {group['ordinal']} | {delivery} | {lag} |")
    lines += ["", "## Lag during each burst", "", "Lag is measured against the scheduled 20 ms source frame time at source positions 0/5/10/15 seconds.", "",
              "| Profile | Repeat | Direction | Burst | Frame lag at 0/5/10/15 s | Peak frame lag s |",
              "|---|---:|---|---:|---|---:|"]
    for run in runs:
        for burst in run["analysis"]["bursts"]:
            trace = "/".join(str(value) for value in burst["lag_trace_5s"])
            lines.append(f"| {run['profile']} | {run['repeat']} | {run['direction']} | {burst['ordinal']} | {trace} | {burst['max_frame_lag_s']} |")
    lines += ["", "## Evidence and next experiments", ""]
    recommendations = []
    bad = [item for item in runs if item["classification"] == "test_system_error"]
    if bad:
        recommendations.append(f"Repair measurement errors in {len(bad)} runs and rerun those cases before protocol conclusions.")
    protocol = [item for item in runs if item["classification"] == "protocol_error"]
    if protocol:
        reasons = sorted({burst["send_reason"] for item in protocol for burst in item["analysis"]["bursts"]
                          if burst["send_reason"] in {"protocol_error", "invalid_ack"}})
        recommendations.append(f"Investigate protocol outcomes in {len(protocol)} runs ({', '.join(reasons) or 'identity/order error'}); compare ACK and final sequence evidence.")
    backlog = sum(item["server_events"]["outbound_backlog_limit"] for item in runs)
    if backlog:
        recommendations.append(f"Review {backlog} legacy queue-limit encounters against frame delivery and timeouts. An encounter alone is not frame loss.")
    pressure = sum(item["server_events"]["outbound_backpressure"] for item in runs)
    if pressure:
        recommendations.append(f"Review {pressure} completed backpressure intervals against frame delivery and send timeouts.")
    listener_48 = [item for item in runs if item["profile"] == "listener-48"]
    if listener_48:
        medians = []
        for ordinal in (1, 3):
            values = [burst["last_frame_lag_s"] for item in listener_48
                      for burst in item["analysis"]["bursts"]
                      if burst["ordinal"] == ordinal and burst["last_frame_lag_s"] is not None]
            medians.append(round(statistics.median(values), 2) if values else None)
        if all(value is not None for value in medians):
            recommendations.append(f"Investigate accumulated listener delay: at 48 kbit/s ingress policing, median end lag grows from {medians[0]} s on burst 1 to {medians[1]} s on burst 3. Test downlink pacing separately.")
    loss = [item for item in runs if item["classification"] == "measured_degradation"]
    if loss:
        names = ", ".join(sorted({item["profile"] for item in loss}))
        recommendations.append(f"Investigate source-plan truncation and undelivered frames in {len(loss)} runs: {names}. Compare send reasons and network counters.")
    reconnects = sum(max(0, item["analysis"]["sender_transports"] - 1)
                     + max(0, item["analysis"]["receiver_transports"] - 1) for item in runs)
    if reconnects:
        recommendations.append(f"Inspect resume continuity across {reconnects} additional WebSocket transports, especially under listener ingress policing. Correlate bot epochs with receive gaps before changing retry behavior.")
    lease = sum(burst["send_reason"] == "lease_expired" for item in runs
                for burst in item["analysis"]["bursts"])
    if lease:
        recommendations.append(f"Compare {lease} client-observed floor lease expirations with sender outages, then test lease tolerance in a separate protocol experiment.")
    if not recommendations:
        recommendations.append("Extend the measured limits with a finer bandwidth sweep or longer outages before tuning protocol parameters.")
    lines.extend(f"{index}. {item}" for index, item in enumerate(recommendations, 1))
    lines += ["", "Each case directory has settings, bot stdout/stderr, receiver PCM, tc statistics, server logs, and raw JSON.", ""]
    (root / "report.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profiles", default=",".join(PROFILES))
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--server-url")
    parser.add_argument("--ssh-config", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "acceptance/artifacts/poor-link")
    parser.add_argument("--run-id", help="Unique output directory name assigned by the wrapper")
    args = parser.parse_args()
    profiles = args.profiles.split(",")
    if any(profile not in PROFILES + EXTRA_PROFILES for profile in profiles) or not 1 <= args.repeats <= 3:
        parser.error("Choose known profiles and 1-3 repeats")
    ssh = json.loads(args.ssh_config.read_text(encoding="utf-8"))
    server_url = args.server_url or ssh.get("server_url")
    if not server_url:
        parser.error("Supply --server-url or server_url in the ignored SSH config")
    run_id = args.run_id or datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    if not re.fullmatch(r"[A-Za-z0-9-]{1,64}", run_id):
        parser.error("Invalid run ID")
    root = args.output_dir / run_id
    root.mkdir(parents=True)
    runs: list[dict] = []
    health: dict = {}
    try:
        health["before"] = url_health(server_url)
        image_id = build()
        audio_dir = args.output_dir / "audio-cache"
        audio = prepare(audio_dir, IMAGE)
        save_json(root / "source.json", {"checkout": source_state(), "image": image_id,
                                           "installed_bundle_sha256": installed_bundle(ssh),
                                           "server_clock_offset": estimate_clock_offset(server_url),
                                           "audio": audio,
                                           "profiles": profiles, "repeats": args.repeats, "seeds": SEEDS})
        for profile in profiles:
            for repeat in range(1, args.repeats + 1):
                for direction in ("A-to-B", "B-to-A"):
                    print(f"{now()} {profile} repeat={repeat} {direction}", flush=True)
                    run = run_case(profile, repeat, direction, SEEDS[repeat - 1], root,
                                   audio_dir, server_url, ssh)
                    runs.append(run)
                    render_report(root, runs, health)
                    print(f"  {run['classification']}", flush=True)
    finally:
        try:
            health["after"] = url_health(server_url)
        except Exception as error:
            health["after"] = {"error": f"{type(error).__name__}: {error}"}
        render_report(root, runs, health)
        print(f"Report: {root / 'report.md'}", flush=True)
    return 1 if any(run["classification"] == "test_system_error" for run in runs) else 0


if __name__ == "__main__":
    raise SystemExit(main())
