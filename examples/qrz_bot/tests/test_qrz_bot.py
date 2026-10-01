import asyncio
import hashlib
from pathlib import Path

import pytest

from examples.qrz_bot import qrz_bot
from examples.qrz_bot.generate_audio import render_qrz_pcm
from examples.qrz_bot.qrz_bot import make_qrz_handler
from zenptt_headless import PcmAudio, ReceivedBurst, load_pcm


@pytest.mark.asyncio
async def test_qrz_policy_returns_the_prepared_audio_by_reference() -> None:
    audio = PcmAudio(b"\1\0" * 320)
    burst = ReceivedBurst("source", 1, PcmAudio(b""), (), (), "complete")
    assert await make_qrz_handler(audio)(burst) is audio


def test_generated_pcm_is_bundled_and_ready_to_send() -> None:
    assets = Path(__file__).resolve().parents[1] / "assets"
    pcm = load_pcm(assets / "QRZ.pcm")
    assert pcm.data == render_qrz_pcm()
    assert len(pcm.data) == 88_800
    assert hashlib.sha256(pcm.data).hexdigest() == (
        "974dcf2024c7ca7e8609e428c4dd0aa7c2ecf73c9ae9aa5ef9aa481c370f287c"
    )
    assert (len(pcm.data) + 639) // 640 == 139


def test_qrz_main_uses_prepared_pcm_without_a_decoder(monkeypatch) -> None:
    assets = Path(__file__).resolve().parents[1] / "assets"
    monkeypatch.setenv("ZENPTT_PCM_PATH", str(assets / "QRZ.pcm"))
    monkeypatch.setenv("ZENPTT_SERVER_URL", "ws://test/ws")

    def unexpected_process(*args, **kwargs):
        raise AssertionError("Standalone startup must not run FFmpeg or a downloader")

    monkeypatch.setattr("subprocess.run", unexpected_process)
    monkeypatch.setattr("urllib.request.urlopen", unexpected_process)

    def verify(config, handler):
        async def assert_handler():
            result = await handler(
                ReceivedBurst("source", 0, PcmAudio(b""), (), (), "complete")
            )
            assert result == load_pcm(assets / "QRZ.pcm")

        asyncio.run(assert_handler())

    monkeypatch.setattr(qrz_bot, "run_standalone", verify)
    qrz_bot.main()
