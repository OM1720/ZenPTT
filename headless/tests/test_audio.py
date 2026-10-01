from pathlib import Path
import subprocess

import pytest

from zenptt_headless import audio as audio_module
from zenptt_headless.audio import MAX_PCM_BYTES, load_mp3, load_pcm


@pytest.mark.parametrize(
    "data", [b"", b"x", bytes(MAX_PCM_BYTES + 2)], ids=["empty", "partial-sample", "oversized"]
)
def test_prepared_pcm_rejects_invalid_size(tmp_path: Path, data: bytes) -> None:
    source = tmp_path / "audio.pcm"
    source.write_bytes(data)
    with pytest.raises(ValueError):
        load_pcm(source)


def test_ffmpeg_decodes_stereo_mp3_to_16khz_mono(tmp_path: Path) -> None:
    target = tmp_path / "tone.mp3"
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=700:duration=0.1:sample_rate=44100",
            "-ac",
            "2",
            str(target),
        ],
        check=True,
    )
    audio = load_mp3(target)
    assert audio.sample_rate == 16_000
    assert 3_000 <= len(audio.data) <= 3_600


def test_missing_and_corrupt_mp3_are_rejected(tmp_path: Path) -> None:
    with pytest.raises(ValueError):
        load_mp3(tmp_path / "missing.mp3")
    corrupt = tmp_path / "bad.mp3"
    corrupt.write_bytes(b"not audio")
    with pytest.raises(ValueError):
        load_mp3(corrupt)


@pytest.mark.parametrize("size", [0, MAX_PCM_BYTES + 2], ids=["empty", "too-long"])
def test_empty_and_too_long_decoded_audio_are_rejected(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, size: int
) -> None:
    source = tmp_path / "source.mp3"
    source.write_bytes(b"placeholder")
    result = subprocess.CompletedProcess([], 0, bytes(size), b"")
    monkeypatch.setattr(audio_module.subprocess, "run", lambda *args, **kwargs: result)
    with pytest.raises(ValueError):
        load_mp3(source)
