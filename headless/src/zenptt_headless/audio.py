"""Load prepared PCM; decode MP3 only for offline asset preparation."""

from __future__ import annotations

from pathlib import Path
import subprocess

from .types import FRAME_BYTES, MAX_FRAMES, PcmAudio

MAX_PCM_BYTES = FRAME_BYTES * MAX_FRAMES


def load_pcm(path: str | Path) -> PcmAudio:
    """Read at most one supported burst from a prepared s16le mono 16 kHz file."""
    with Path(path).open("rb") as source:
        data = source.read(MAX_PCM_BYTES + 1)
    if not data:
        raise ValueError("PCM file contains no audio")
    return PcmAudio(data)


def load_mp3(path: str | Path) -> PcmAudio:
    source = Path(path)
    if not source.is_file():
        raise ValueError(f"MP3 file does not exist: {source}")
    command = [
        "ffmpeg",
        "-v",
        "error",
        "-nostdin",
        "-i",
        str(source),
        "-t",
        "60.02",
        "-f",
        "s16le",
        "-acodec",
        "pcm_s16le",
        "-ac",
        "1",
        "-ar",
        "16000",
        "pipe:1",
    ]
    try:
        result = subprocess.run(command, capture_output=True, timeout=10, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ValueError("FFmpeg could not decode the MP3 within 10 seconds") from error
    if result.returncode != 0:
        raise ValueError("FFmpeg rejected the MP3")
    if not result.stdout:
        raise ValueError("MP3 contains no audio")
    if len(result.stdout) > MAX_PCM_BYTES:
        raise ValueError("MP3 exceeds the 60-second ZenPTT burst limit")
    return PcmAudio(result.stdout)
