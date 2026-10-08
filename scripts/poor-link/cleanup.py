"""Preserve evidence and remove only containers named in one poor-link run."""

from __future__ import annotations

import json
from pathlib import Path
import re
import subprocess
import sys


NAME = re.compile(r"zpt-[0-9a-f]{12}\Z")


def cases(run_dir: Path) -> list[tuple[Path, str, str]]:
    result = []
    for settings in run_dir.glob("*/settings.json"):
        if (settings.parent / "result.json").exists():
            continue
        record = json.loads(settings.read_text(encoding="utf-8"))
        for role, name in record.get("containers", {}).items():
            if role in {"sender", "receiver", "contender"} and isinstance(name, str) and NAME.fullmatch(name):
                result.append((settings.parent, role, name))
    return result


def docker(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["docker", *args], capture_output=True, text=True, timeout=20)


def cleanup(run_dir: Path) -> list[str]:
    errors = []
    interrupted: dict[Path, list[dict]] = {}
    for case, role, name in cases(run_dir):
        exists = docker("inspect", "--format", "{{.State.Status}}", name)
        if exists.returncode:
            continue
        output = case / role
        output.mkdir(exist_ok=True)
        logs = docker("logs", name)
        (output / "stdout.log").write_text(logs.stdout, encoding="utf-8")
        (output / "stderr.log").write_text(logs.stderr, encoding="utf-8")
        stats = docker("exec", name, "tc", "-s", "qdisc", "show", "dev", "eth0")
        (output / "cleanup-tc.log").write_text(stats.stdout + stats.stderr, encoding="utf-8")
        removed = docker("rm", "-f", name)
        if removed.returncode:
            errors.append(f"{name}: {removed.stderr[:160]}")
        interrupted.setdefault(case, []).append({"container": name, "role": role})
    for case, containers in interrupted.items():
        (case / "aborted.json").write_text(
            json.dumps({"reason": "runner_interrupted", "containers": containers}) + "\n", encoding="utf-8")
    return errors


if __name__ == "__main__":
    for error in cleanup(Path(sys.argv[1])):
        print(error, file=sys.stderr)
