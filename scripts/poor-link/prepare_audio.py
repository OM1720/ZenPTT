"""Prepare pinned LibriSpeech audio with Linux FFmpeg into ignored local storage."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import urllib.request


HERE = Path(__file__).resolve().parent
MANIFEST = json.loads((HERE / "audio-source.json").read_text(encoding="utf-8"))
MAX_ARCHIVE_BYTES = 20 * 1024 * 1024
FRAME_BYTES = 640
SPEECH_SECONDS = 60


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare(cache: Path, image: str) -> dict:
    cache.mkdir(parents=True, exist_ok=True)
    archive_path = cache / "dev_clean.tar.gz"
    expected = MANIFEST["archive_sha256"]
    if not archive_path.exists() or sha256(archive_path) != expected:
        with urllib.request.urlopen(MANIFEST["archive_url"], timeout=30) as response:
            data = response.read(MAX_ARCHIVE_BYTES + 1)
        if len(data) > MAX_ARCHIVE_BYTES or hashlib.sha256(data).hexdigest() != expected:
            raise ValueError("Speech archive does not match the pinned size and SHA-256")
        archive_path.write_bytes(data)

    paths = []
    with tarfile.open(archive_path, "r:gz") as archive:
        for member_name in MANIFEST["files"]:
            member = archive.getmember(member_name)
            if not member.isfile() or member.size > 5 * 1024 * 1024:
                raise ValueError(f"Unexpected speech archive member: {member_name}")
            destination = cache / Path(member_name).name
            source = archive.extractfile(member)
            if source is None:
                raise ValueError(f"Missing speech archive member: {member_name}")
            payload = source.read()
            if len(payload) != member.size or not payload.startswith(b"fLaC"):
                raise ValueError(f"Invalid FLAC member: {member_name}")
            destination.write_bytes(payload)
            paths.append(destination)

    concat = "".join(f"file '/audio/{path.name}'\n" for path in paths)
    (cache / "concat.txt").write_bytes(concat.encode("ascii"))
    command = [
        "docker", "run", "--rm", "-v", f"{cache.resolve()}:/audio", image,
        "ffmpeg", "-nostdin", "-y", "-loglevel", "error", "-f", "concat",
        "-safe", "0", "-i", "/audio/concat.txt", "-t", str(SPEECH_SECONDS),
        "-ac", "1", "-ar", "16000", "-f", "s16le", "/audio/speech-60s.pcm",
    ]
    completed = subprocess.run(command, capture_output=True, text=True, timeout=90)
    if completed.returncode:
        raise RuntimeError(f"Linux FFmpeg failed: {completed.stderr.strip()[:400]}")
    pcm = (cache / "speech-60s.pcm").read_bytes()
    if len(pcm) != SPEECH_SECONDS * 16000 * 2:
        raise ValueError(f"Prepared speech length is wrong: {len(pcm)} bytes")
    chunk_bytes = 20 * 16000 * 2
    for index in range(3):
        part = pcm[index * chunk_bytes:(index + 1) * chunk_bytes]
        if len(part) != chunk_bytes or len(part) % FRAME_BYTES:
            raise ValueError("Speech chunk does not align to 20 ms frames")
        (cache / f"speech-{index + 1}.pcm").write_bytes(part)
    return {
        "source": MANIFEST,
        "archive_sha256": sha256(archive_path),
        "pcm_sha256": sha256(cache / "speech-60s.pcm"),
        "chunks": [sha256(cache / f"speech-{index}.pcm") for index in range(1, 4)],
        "pcm_format": "signed 16-bit little-endian mono 16000 Hz",
    }
