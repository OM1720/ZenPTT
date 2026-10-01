import pytest

from zenptt_headless.echo_handler import make_echo_handler
from zenptt_headless.types import PcmAudio, ReceivedBurst


def burst(audio: PcmAudio) -> ReceivedBurst:
    return ReceivedBurst("source", 1, audio, (), (), "complete", session_epoch=3)


@pytest.mark.asyncio
async def test_echo_policy_returns_non_empty_received_audio_by_reference() -> None:
    audio = PcmAudio(b"\1\0" * 320)
    assert await make_echo_handler()(burst(audio)) is audio


@pytest.mark.asyncio
async def test_echo_policy_ignores_empty_received_audio() -> None:
    assert await make_echo_handler()(burst(PcmAudio(b""))) is None
