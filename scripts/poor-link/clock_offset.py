"""Estimate test-host HTTP clock offset without recording its address."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import json
from pathlib import Path
import time
from urllib.parse import urlsplit
from urllib.request import urlopen


def estimate(server_url: str, samples: int = 10) -> dict:
    parsed = urlsplit(server_url)
    scheme = "https" if parsed.scheme == "wss" else "http"
    health_url = f"{scheme}://{parsed.netloc}/health"
    results = []
    for _ in range(samples):
        start = time.time()
        with urlopen(health_url, timeout=10) as response:
            date = response.headers.get("Date")
            response.read(1_000)
        end = time.time()
        if date is None:
            raise RuntimeError("Test host did not provide an HTTP Date header")
        server_time = parsedate_to_datetime(date).timestamp()
        results.append({"offset_lower_s": server_time - end,
                        "offset_upper_s": server_time + 1 - start,
                        "rtt_s": end - start})
    lower = max(item["offset_lower_s"] for item in results)
    upper = min(item["offset_upper_s"] for item in results)
    if lower > upper:
        raise RuntimeError("HTTP Date samples imply inconsistent host clock offsets")
    return {"measured_at_utc": datetime.now(timezone.utc).isoformat(),
            "method": "Intersection of HTTP Date one-second intervals and request RTT",
            "samples": results,
            "estimated_offset_s": (lower + upper) / 2,
            "uncertainty_s": (upper - lower) / 2}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ssh-config", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    config = json.loads(args.ssh_config.read_text(encoding="utf-8"))
    result = estimate(config["server_url"])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"Estimated offset {result['estimated_offset_s']:.3f} s; "
          f"uncertainty +/-{result['uncertainty_s']:.3f} s")


if __name__ == "__main__":
    main()
