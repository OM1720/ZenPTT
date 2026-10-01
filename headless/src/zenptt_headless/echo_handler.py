"""Response policy for personal Echo sessions."""

from __future__ import annotations

from zenptt_headless import BurstHandler, PcmAudio, ReceivedBurst


def make_echo_handler() -> BurstHandler:
    """Return received non-empty PCM by reference after its burst is complete."""

    async def echo(burst: ReceivedBurst) -> PcmAudio | None:
        return burst.audio if burst.audio.data else None

    return echo
