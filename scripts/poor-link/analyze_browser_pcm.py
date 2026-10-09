"""Supplementary signal check; PCM duration and silence do not establish frame loss."""

from array import array
import json
import math
from pathlib import Path
import sys


def inspect(samples: array, rate: int = 48000) -> dict:
    finite = all(math.isfinite(value) for value in samples)
    if not finite:
        return {"samples": len(samples), "finite": False}
    powers = [0.0] * 64
    stride = rate // 8000
    active = 0
    windows = 0
    # Sparse 16 ms windows avoid turning this optional check into another observer.
    for start in range(0, len(samples) - 128 * stride + 1, rate // 10):
        window = samples[start:start + 128 * stride:stride]
        windows += 1
        if sum(value * value for value in window) / len(window) < 0.000004:
            continue
        active += 1
        for band in range(1, 65):
            coefficient = 2 * math.cos(2 * math.pi * band / 128)
            previous = older = 0.0
            for value in window:
                current = value + coefficient * previous - older
                older, previous = previous, current
            powers[band - 1] += max(0, previous ** 2 + older ** 2 - coefficient * previous * older)
    total = sum(powers)
    # Neighbour bins include the finite-window spread of the 700/1100 Hz carriers.
    carrier = sum(power for index, power in enumerate(powers, 1)
                  if abs(index * 62.5 - 700) <= 100 or abs(index * 62.5 - 1100) <= 100)
    return {"samples": len(samples), "duration_ms": len(samples) * 1000 / rate,
            "finite": True, "sampled_windows": windows, "active_windows": active,
            "carrier_power_fraction": carrier / total if total else 0,
            "spectrum": powers}


def compare(source: array, output: array) -> dict:
    left, right = inspect(source), inspect(output)
    similarity = 0.0
    if left["finite"] and right["finite"]:
        x, y = left["spectrum"], right["spectrum"]
        scale = math.sqrt(sum(v * v for v in x) * sum(v * v for v in y))
        similarity = sum(a * b for a, b in zip(x, y)) / scale if scale else 0
    return {"source": left, "output": right, "spectrum_similarity": similarity,
            "signal_confirmed": left["finite"] and right["finite"] and similarity > 0.9
            and left["carrier_power_fraction"] > 0.8 and right["carrier_power_fraction"] > 0.8,
            "limitation": "Sparse spectral confirmation only; no claim about speech intelligibility, frame completeness, ordering or acoustic quality."}


def read_pcm(path: Path) -> array:
    values = array("f")
    values.frombytes(path.read_bytes())
    if sys.byteorder != "little":
        values.byteswap()
    return values


def analyze(directory: Path) -> dict:
    result = {}
    for direction in ("A-to-B", "B-to-A"):
        try:
            result[direction] = compare(read_pcm(directory / f"{direction}-input.f32le"),
                                        read_pcm(directory / f"{direction}-output.f32le"))
        except (OSError, ValueError) as error:
            result[direction] = {"signal_confirmed": False, "error": type(error).__name__}
    return result


if __name__ == "__main__":
    directory = Path(sys.argv[1])
    (directory / "pcm-analysis.json").write_text(json.dumps(analyze(directory), indent=2) + "\n")
