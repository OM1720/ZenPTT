"""Generate the original QRZ Morse response used by the example bot."""

from __future__ import annotations

import math
from pathlib import Path
import struct


SAMPLE_RATE = 16_000
TONE_HZ = 700
DOT_SAMPLES = 1_200
RAMP_SAMPLES = 80
AMPLITUDE = 10_000
QRZ_MORSE = ("--.-", ".-.", "--..")


def render_qrz_pcm() -> bytes:
    pcm = bytearray()
    for letter_index, letter in enumerate(QRZ_MORSE):
        if letter_index:
            pcm.extend(bytes(3 * DOT_SAMPLES * 2))
        for symbol_index, symbol in enumerate(letter):
            if symbol_index:
                pcm.extend(bytes(DOT_SAMPLES * 2))
            length = DOT_SAMPLES * (3 if symbol == "-" else 1)
            for sample_index in range(length):
                fade = min(sample_index, length - 1 - sample_index, RAMP_SAMPLES)
                value = round(
                    AMPLITUDE
                    * fade
                    / RAMP_SAMPLES
                    * math.sin(2 * math.pi * TONE_HZ * sample_index / SAMPLE_RATE)
                )
                pcm.extend(struct.pack("<h", value))
    return bytes(pcm)


if __name__ == "__main__":
    target = Path(__file__).resolve().parent / "assets" / "QRZ.pcm"
    target.write_bytes(render_qrz_pcm())
    print(f"Generated {target}")
