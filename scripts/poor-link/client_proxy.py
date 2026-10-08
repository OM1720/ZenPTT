"""Run the real browser audio path through two isolated Linux network proxies."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
from urllib.parse import urlsplit
from zipfile import ZipFile

from prepare_audio import sha256
from run import IMAGE as TC_IMAGE, ROOT, build, docker, installed_bundle, ssh_logs, url_health


CADDY_IMAGE = (
    "caddy:2.11.4-alpine@sha256:"
    "5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648"
)
COMPOSE = ["compose", "-p", "zenptt-web-gate", "--env-file", "server.local.env",
           "-f", "compose.yaml", "-f", "web/compose.test.yaml"]
PROFILES = ("baseline", "egress-32", "egress-48", "egress-64",
            "ingress-police-48", "unstable")


def upstream_caddyfile(server_url: str) -> str:
    parsed = urlsplit(server_url)
    if parsed.scheme not in {"ws", "wss"} or parsed.path != "/ws" or not parsed.hostname:
        raise ValueError("Server URL must be ws(s)://host[:port]/ws")
    host = parsed.hostname
    if not re.fullmatch(r"[A-Za-z0-9.-]+", host):
        raise ValueError("Server hostname is invalid")
    authority = host + (f":{parsed.port}" if parsed.port else "")
    scheme = "https" if parsed.scheme == "wss" else "http"
    return (f":8080 {{\n"
            f"    reverse_proxy {scheme}://{authority} {{\n"
            f"        header_up Host {authority}\n"
            f"    }}\n"
            f"}}\n")


def qdisc(profile: str, seed: int = 1009) -> str | None:
    if profile.startswith("egress-"):
        rate = profile.removeprefix("egress-")
        return f"tc qdisc add dev eth0 root netem rate {rate}kbit delay 200ms 50ms seed {seed}"
    if profile == "ingress-police-48":
        return ("tc qdisc add dev eth0 handle ffff: ingress && "
                "tc filter add dev eth0 parent ffff: protocol ip u32 match u32 0 0 "
                "police rate 48kbit burst 1500 drop flowid :1")
    if profile == "unstable":
        return f"tc qdisc add dev eth0 root netem delay 200ms 100ms loss 1% seed {seed}"
    return None


def network_helper(name: str, script: str, *, check: bool = True) -> subprocess.CompletedProcess[str]:
    return docker("run", "--rm", "--network", f"container:{name}", "--cap-add", "NET_ADMIN",
                  "--entrypoint", "sh", TC_IMAGE, "-c", script, check=check)


def tc_stats(name: str) -> str:
    result = network_helper(name, "tc -s qdisc show dev eth0; "
                            "tc -s filter show dev eth0 ingress; cat /proc/net/dev", check=False)
    return result.stdout + result.stderr


def sent_packets(stats: str) -> int:
    return max((int(count) for count in re.findall(r"Sent \d+ bytes (\d+) pkt", stats)), default=0)


def successful(result: dict) -> bool:
    return (result.get("test_exit_code") == 0 and result.get("tc_confirmed") is True
            and result.get("browser_direction_count") == 2
            and result.get("health_after", {}).get("status") == "ok"
            and result.get("server_logs", {}).get("exit_code") == 0
            and "system_error" not in result)


def verify_web_dist(package_zip: Path) -> int:
    with ZipFile(package_zip) as package:
        names = {name for name in package.namelist()
                 if name.startswith("web/dist/") and not name.endswith("/")}
        if not names or any(".." in Path(name).parts for name in names):
            raise ValueError("Package has no valid web/dist files")
        local = {path.relative_to(ROOT).as_posix() for path in (ROOT / "web/dist").rglob("*")
                 if path.is_file()}
        if names != local or any((ROOT / name).read_bytes() != package.read(name) for name in names):
            raise RuntimeError("Local web/dist differs from the selected package")
        return len(names)


def run_case(args: argparse.Namespace) -> Path:
    allowed = (ROOT / "acceptance/artifacts/poor-link").resolve()
    output = Path(args.output).resolve() if args.output else (
        allowed /
        ("client-proxy-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")))
    if not output.is_relative_to(allowed):
        raise ValueError("Client proxy artifacts must stay under ignored acceptance/artifacts/poor-link")
    if output.exists():
        raise FileExistsError(f"Result directory already exists: {output}")
    output.mkdir(parents=True)
    config = json.loads(Path(args.config).read_text(encoding="utf-8"))
    server_url = args.server_url or config["server_url"]
    caddyfile = output / "Caddyfile"
    caddyfile.write_text(upstream_caddyfile(server_url), encoding="utf-8")
    names = [f"zenptt-client-proxy-{output.name[-12:]}-{role.lower()}" for role in ("A", "B")]
    ports = (args.port_a, args.port_b)
    result: dict = {"profile": args.profile, "impaired": args.impaired,
                    "seed": args.seed, "target_frames": args.target_frames,
                    "audio_diagnostics": args.diagnose_audio,
                    "prebuffer_ms": args.prebuffer_ms,
                    "lightweight_observer": args.lightweight_observer,
                    "ports": ports, "started_at": datetime.now(timezone.utc).isoformat(),
                    "source": {str(path.relative_to(ROOT)): sha256(path) for path in (
                        ROOT / "web/src/ZenPttClient.ts", ROOT / "web/src/recovery.ts",
                        ROOT / "web/tests/browser/audio.spec.ts", ROOT / "scripts/poor-link/client_proxy.py")}}
    created: list[str] = []
    stack_started = False
    try:
        docker("info", "--format", "{{.ServerVersion}}")
        if docker(*COMPOSE, "ps", "-q").stdout.strip():
            raise RuntimeError("The local web test stack is already in use")
        result["health_before"] = url_health(server_url)
        result["installed_bundle"] = installed_bundle(config)
        if result["installed_bundle"] != args.expected_bundle:
            raise RuntimeError("Installed test-host package differs from --expected-bundle")
        if args.package_zip:
            package_zip = Path(args.package_zip)
            if sha256(package_zip) != args.expected_bundle:
                raise RuntimeError("Selected package ZIP differs from --expected-bundle")
            result["local_web_files_verified"] = verify_web_dist(package_zip)
        if docker("image", "inspect", TC_IMAGE, check=False).returncode:
            build()
        for name, port in zip(names, ports):
            docker("run", "-d", "--name", name, "-p", f"127.0.0.1:{port}:8080",
                   "--mount", f"type=bind,src={caddyfile},dst=/etc/caddy/Caddyfile,readonly",
                   CADDY_IMAGE)
            created.append(name)
        impaired = names[0 if args.impaired == "A" else 1]
        setup = qdisc(args.profile, args.seed)
        if setup:
            network_helper(impaired, setup)
        for port in ports:
            from urllib.request import urlopen
            with urlopen(f"http://127.0.0.1:{port}/health", timeout=15) as response:
                if response.status != 200 or json.load(response).get("status") != "ok":
                    raise RuntimeError(f"Proxy on port {port} did not reach the test host")
        before_stats = {role: tc_stats(name) for name, role in zip(names, ("A", "B"))}
        docker(*COMPOSE, "up", "-d", "--build", "--force-recreate", timeout=600)
        stack_started = True
        for _ in range(30):
            try:
                from urllib.request import urlopen
                with urlopen("http://127.0.0.1:18081/health", timeout=2) as response:
                    if json.load(response).get("status") == "ok":
                        break
            except Exception:
                time.sleep(1)
        else:
            raise RuntimeError("Local static web stack was not healthy")
        environment = os.environ.copy()
        environment.update({"ZENPTT_WEB_URL": "http://127.0.0.1:18081",
                            "ZENPTT_WEB_PROXY_URLS": ",".join(
                                f"ws://127.0.0.1:{port}/ws" for port in ports),
                            "ZENPTT_WEB_PROXY_METRICS": str(output / "browser-metrics.json"),
                            "ZENPTT_WEB_PROXY_TARGET_FRAMES": str(args.target_frames),
                            "ZENPTT_WEB_PROXY_PREBUFFER_MS": str(args.prebuffer_ms)})
        if args.diagnose_audio:
            environment["ZENPTT_WEB_PROXY_DIAGNOSTICS"] = str(output)
        if args.lightweight_observer:
            environment["ZENPTT_WEB_PROXY_LIGHTWEIGHT"] = "1"
        npx = shutil.which("npx.cmd" if os.name == "nt" else "npx")
        if npx is None:
            raise FileNotFoundError("npx is not installed")
        command = [npx, "playwright", "test", "--project=audio",
                   "tests/browser/audio.spec.ts", "-g",
                   "real AudioWorklet/WASM path streams before release in both directions"]
        result["test_started_at"] = datetime.now(timezone.utc).isoformat()
        try:
            test = subprocess.run(command, cwd=ROOT / "web", env=environment,
                                  capture_output=True, text=True, timeout=180, check=False)
            result["test_exit_code"] = test.returncode
            (output / "browser.stdout.log").write_text(test.stdout, encoding="utf-8")
            (output / "browser.stderr.log").write_text(test.stderr, encoding="utf-8")
        except subprocess.TimeoutExpired as error:
            result["test_error"] = "browser_timeout"
            (output / "browser.stdout.log").write_bytes(error.stdout or b"")
            (output / "browser.stderr.log").write_bytes(error.stderr or b"")
        result["test_ended_at"] = datetime.now(timezone.utc).isoformat()
        metrics_path = output / "browser-metrics.json"
        if metrics_path.exists():
            browser_metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
            result["browser_direction_count"] = len(browser_metrics)
            result["signal_degradation"] = any(item["signalError"] for item in browser_metrics)
        for name, role in zip(names, ("A", "B")):
            after_stats = tc_stats(name)
            (output / f"tc-{role}-before.log").write_text(before_stats[role], encoding="utf-8")
            (output / f"tc-{role}.log").write_text(after_stats, encoding="utf-8")
            result[f"tc_{role}_sent_packet_delta"] = sent_packets(after_stats) - sent_packets(before_stats[role])
            logs = docker("logs", name, check=False)
            (output / f"proxy-{role}.log").write_text(logs.stdout + logs.stderr, encoding="utf-8")
        result["health_after"] = url_health(server_url)
        result["server_logs"] = ssh_logs(config, result["test_started_at"],
                                          result["test_ended_at"], output / "server.log")
        result["tc_confirmed"] = setup is None or result[f"tc_{args.impaired}_sent_packet_delta"] > 0
    except Exception as error:
        result["system_error"] = f"{type(error).__name__}: {error}"
    finally:
        if stack_started:
            docker(*COMPOSE, "down", check=False)
        for name in created:
            docker("rm", "-f", name, check=False)
        result["finished_at"] = datetime.now(timezone.utc).isoformat()
        (output / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        metrics = output / "browser-metrics.json"
        lines = ["# Browser client proxy experiment", "",
                 f"Profile: `{args.profile}` on client {args.impaired}.",
                 f"Test-only extra prebuffer: `{args.prebuffer_ms}` ms.",
                 f"Browser exit: `{result.get('test_exit_code', result.get('test_error', 'not_started'))}`.",
                 f"Measured signal degradation: `{result.get('signal_degradation', 'unknown')}`.",
                 f"Test system error: `{result.get('system_error', 'none')}`.",
                 f"`tc` packet counter confirmed: `{result.get('tc_confirmed', 'unknown')}`.", "",
                 "The proxy limits its container interface in both directions between the "
                 "proxy and its two TCP peers. These results are separate from direct bot "
                 "shaping and policing measurements.", ""]
        if metrics.exists():
            for item in json.loads(metrics.read_text(encoding="utf-8")):
                lines.append(f"- {item['direction']}: {item['mediaMessages']} sent media messages, "
                             f"{item['sentFrames']} sent frames, "
                             f"{item['receivedMessages']} received messages, "
                             f"{item['receivedFrames']} received frames, "
                             f"{item['playedFrames']} played frames; "
                             f"signal error: {item['signalError'] or 'none'}.")
                if args.diagnose_audio:
                    lines.append(f"  Internal silence: input {item['inputInternalSilenceMs']} ms, "
                                 f"output {item['outputInternalSilenceMs']} ms; "
                                 f"receiver queue-block events: {item['queueBlockEvents']}; "
                                 f"observer wall-gap maximum: {item['observerWallGapMaxMs']} ms.")
        (output / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default=str(ROOT / "private/poor-link-ssh.json"))
    parser.add_argument("--server-url")
    parser.add_argument("--expected-bundle", required=True)
    parser.add_argument("--package-zip")
    parser.add_argument("--profile", choices=PROFILES, default="baseline")
    parser.add_argument("--impaired", choices=("A", "B"), default="A")
    parser.add_argument("--seed", type=int, choices=(1009, 2017, 3037), default=1009)
    parser.add_argument("--target-frames", type=int, choices=(100, 400), default=100)
    parser.add_argument("--diagnose-audio", action="store_true")
    parser.add_argument("--prebuffer-ms", type=int, choices=(0, 50, 100), default=0)
    parser.add_argument("--lightweight-observer", action="store_true")
    parser.add_argument("--port-a", type=int, default=18091)
    parser.add_argument("--port-b", type=int, default=18092)
    parser.add_argument("--output")
    args = parser.parse_args()
    output = run_case(args)
    print(output)
    if not successful(json.loads((output / "result.json").read_text(encoding="utf-8"))):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
