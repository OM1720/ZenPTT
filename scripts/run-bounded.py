"""Run one verification command with a hard wall-clock limit."""

from __future__ import annotations

import subprocess
import sys


def main() -> int:
    if len(sys.argv) < 3:
        raise SystemExit("usage: run-bounded.py SECONDS COMMAND [ARG ...]")
    timeout = float(sys.argv[1])
    try:
        completed = subprocess.run(sys.argv[2:], timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        print(f"Command exceeded {timeout:g} seconds: {' '.join(sys.argv[2:])}", file=sys.stderr)
        return 124
    return completed.returncode


if __name__ == "__main__":
    raise SystemExit(main())
