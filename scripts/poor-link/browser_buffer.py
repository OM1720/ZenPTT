"""Build immutable browser buffer candidates and run the sequential manual matrix."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import difflib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import uuid

from analyze_browser_buffer import analyze
from client_proxy import method_hashes, verify_web_manifest
from prepare_audio import sha256
from run import ROOT, command, docker, installed_bundle, url_health


def save(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def file_hashes(root: Path) -> dict:
    return {path.relative_to(root).as_posix(): sha256(path)
            for path in sorted(root.rglob("*")) if path.is_file()}


def build_variants(output: Path) -> None:
    docker("info")
    sources = command(["git", "ls-files", "web"], check=True).stdout.splitlines()
    common = output / "source-common"
    common.mkdir()
    for name in sources:
        target = common / Path(name).relative_to("web")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / name, target)
    for delay in (100, 150, 200):
        candidate = output / f"P{delay}"
        candidate.mkdir()
        source = candidate / "source"
        shutil.copytree(common, source)
        processor = source / "src/audio/processor.ts"
        original = processor.read_text(encoding="utf-8")
        patched, count = re.subn(r"(?m)^export const PLAYBACK_START_DELAY_MS = \d+$",
                                 f"export const PLAYBACK_START_DELAY_MS = {delay}", original)
        if count != 1:
            raise ValueError("Expected one numeric worklet startup-delay constant")
        processor.write_text(patched, encoding="utf-8", newline="\n")
        (candidate / "buffer.patch").write_text("".join(difflib.unified_diff(
            original.splitlines(keepends=True), patched.splitlines(keepends=True),
            fromfile="a/web/src/audio/processor.ts", tofile="b/web/src/audio/processor.ts")), encoding="utf-8")
        manifest = {"variant": f"P{delay}", "delay_ms": delay,
                    "source": file_hashes(source), "commit": command(["git", "rev-parse", "HEAD"]).stdout.strip()}
        tag = f"zenptt-browser-buffer:{output.name.lower()}-p{delay}"
        with (candidate / "build.log").open("w", encoding="utf-8") as log:
            subprocess.run(["docker", "build", "--pull=false", "-t", tag, str(source)],
                           stdout=log, stderr=subprocess.STDOUT, check=True, timeout=1200)
        manifest["image"] = docker("image", "inspect", "--format", "{{.Id}}", tag).stdout.strip()
        container = f"zenptt-buffer-export-{uuid.uuid4().hex}"
        try:
            docker("create", "--name", container, tag)
            (candidate / "dist").mkdir()
            docker("cp", f"{container}:/app/dist/.", str(candidate / "dist"))
        finally:
            docker("rm", container, check=False)
        manifest["dist"] = file_hashes(candidate / "dist")
        save(candidate / "manifest.json", manifest)
        print(f"Built P{delay}: {manifest['image']}", flush=True)


def cases() -> list[dict]:
    result = []
    for profile in ("baseline", "unstable", "egress-32", "egress-48", "egress-64"):
        for seed, order in ((1009, (100, 150, 200)), (2017, (150, 200, 100)), (3037, (200, 100, 150))):
            for delay in order:
                result.append({"profile": profile, "seed": seed, "variant": f"P{delay}",
                               "observer": "lightweight"})
    for profile in ("baseline", "unstable"):
        for delay in (100, 150, 200):
            result.append({"profile": profile, "seed": 1009, "variant": f"P{delay}", "observer": "pcm"})
    return result


def select_dist(candidate: Path) -> None:
    destination = (ROOT / "web/dist").resolve()
    if destination != (ROOT / "web").resolve() / "dist":
        raise ValueError("Unexpected web/dist location")
    candidate = candidate.resolve()
    if not candidate.is_relative_to((ROOT / "acceptance/artifacts/poor-link").resolve()):
        raise ValueError("Candidate must stay under ignored poor-link artifacts")
    if not candidate.is_dir():
        raise FileNotFoundError(f"Missing web candidate: {candidate}")
    expected = file_hashes(candidate)
    if not expected:
        raise ValueError("Empty web candidate")

    def retry_readonly(function, path, error):
        target = Path(path).resolve()
        if (not isinstance(error[1], PermissionError) or not target.is_relative_to(destination)
                or not getattr(target.stat(), "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_READONLY):
            raise error[1]
        os.chmod(target, stat.S_IWRITE)
        function(target)

    if destination.exists():
        shutil.rmtree(destination, onerror=retry_readonly)
    shutil.copytree(candidate, destination)
    if file_hashes(destination) != expected:
        raise RuntimeError("Selected web/dist differs from its candidate")


def restore_dist(backup: Path) -> None:
    primary = sys.exc_info()[1]
    record = {"backup": str(backup.resolve()), "restored": False,
              "primary_error": f"{type(primary).__name__}: {primary}" if primary else None}
    try:
        select_dist(backup)
        record["dist"] = file_hashes(ROOT / "web/dist")
        record["restored"] = record["dist"] == file_hashes(backup)
        if not record["restored"]:
            raise RuntimeError("Restored web/dist differs from its backup")
    except Exception as error:
        record["restored"] = False
        record["restoration_error"] = f"{type(error).__name__}: {error}"
        raise RuntimeError(f"Web restoration failed: {record['restoration_error']}; "
                           f"original error: {record['primary_error']}; backup: {backup}") from error
    finally:
        save(backup.parent / (backup.name + "-restoration.json"), record)


def run_matrix(output: Path, expected_bundle: str, *, pilot: bool) -> None:
    planned = cases()
    save(output / "matrix-plan.json", planned)
    backup = output / ("matrix-dist-backup-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
    shutil.copytree(ROOT / "web/dist", backup)
    try:
        for case in planned:
            is_pilot = case["profile"] in ("baseline", "unstable") and case["seed"] == 1009 and case["observer"] == "lightweight"
            if pilot and not is_pilot:
                continue
            name = f"{case['profile']}-{case['seed']}-{case['variant']}-{case['observer']}"
            destination = output / name
            if destination.exists():
                previous = json.loads((destination / "result.json").read_text())
                candidate_manifest = json.loads((output / case["variant"] / "manifest.json").read_text())
                if previous.get("source") == method_hashes() and previous.get("web_manifest") == candidate_manifest:
                    print(f"Preserving recorded attempt {name}", flush=True)
                    continue
                raise FileExistsError(f"Existing attempt used different sources: {destination}")
            candidate = output / case["variant"]
            select_dist(candidate / "dist")
            verify_web_manifest(candidate / "manifest.json")
            args = [sys.executable, str(ROOT / "scripts/poor-link/client_proxy.py"),
                    "--expected-bundle", expected_bundle, "--web-manifest", str(candidate / "manifest.json"),
                    "--series", "--profile", case["profile"], "--seed", str(case["seed"]),
                    "--impaired", "A", "--output", str(destination)]
            if case["observer"] == "lightweight":
                args.append("--lightweight-observer")
            subprocess.run(args, check=False)
            report = analyze(destination)
            save(destination / "buffer-analysis.json", report)
            print(f"{name}: complete={report['complete']}", flush=True)
            if pilot and (report["issues"] or case["profile"] == "baseline" and not report["complete"]):
                raise RuntimeError("Pilot has invalid measurement evidence; inspect before continuing")
    finally:
        restore_dist(backup)


def preflight(output: Path, expected_bundle: str) -> None:
    config = json.loads((ROOT / "private/poor-link-ssh.json").read_text())
    evidence = {"at": datetime.now(timezone.utc).isoformat()}
    try:
        docker("info")
        evidence["health"] = url_health(config["server_url"])
        evidence["installed_bundle"] = installed_bundle(config)
        if evidence["installed_bundle"] != expected_bundle:
            raise RuntimeError("Installed ZIP differs from the requested package")
    except Exception as error:
        evidence["error"] = str(error)
        raise
    finally:
        save(output / ("preflight-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + ".json"), evidence)


def repeat_historical(output: Path, expected_bundle: str) -> None:
    backup = output / ("historical-dist-backup-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
    shutil.copytree(ROOT / "web/dist", backup)
    try:
        candidate = output / "P100"
        select_dist(candidate / "dist")
        for seed, prebuffer in ((2017, 0), (3037, 100)):
            for repeat in range(1, 11):
                name = f"historical-unstable-{seed}-pre{prebuffer}-repeat{repeat:02}"
                destination = output / name
                if destination.exists():
                    raise FileExistsError(f"Historical attempt already exists: {destination}")
                subprocess.run([sys.executable, str(ROOT / "scripts/poor-link/client_proxy.py"),
                    "--expected-bundle", expected_bundle, "--web-manifest", str(candidate / "manifest.json"),
                    "--profile", "unstable", "--seed", str(seed), "--prebuffer-ms", str(prebuffer),
                    "--target-frames", "400", "--diagnose-audio", "--trace-browser", "--output", str(destination)], check=False)
                result = json.loads((destination / "result.json").read_text())
                if result.get("system_error"):
                    raise RuntimeError(f"Historical repeat has a measurement-system error: {destination}")
    finally:
        restore_dist(backup)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("build", "plan", "historical", "pilot", "matrix"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expected-bundle")
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to((ROOT / "acceptance/artifacts/poor-link").resolve()):
        raise ValueError("Keep experimental sources and builds under ignored acceptance artifacts")
    output.mkdir(parents=True, exist_ok=True)
    if args.action == "build":
        build_variants(output)
    elif args.action == "plan":
        save(output / "matrix-plan.json", cases())
    elif not args.expected_bundle:
        parser.error("--expected-bundle is required for external measurements")
    elif args.action == "historical":
        preflight(output, args.expected_bundle)
        repeat_historical(output, args.expected_bundle)
    else:
        preflight(output, args.expected_bundle)
        run_matrix(output, args.expected_bundle, pilot=args.action == "pilot")


if __name__ == "__main__":
    main()
