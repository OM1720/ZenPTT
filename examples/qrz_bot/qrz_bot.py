"""Reference bot that responds to every completed burst with QRZ Morse audio."""

from __future__ import annotations

import logging
import os

from zenptt_headless import (
    BotConfig,
    BurstHandler,
    ClientConfig,
    PcmAudio,
    ReceivedBurst,
    load_pcm,
    run_standalone,
)


def make_qrz_handler(audio: PcmAudio) -> BurstHandler:
    """Return the reference policy while keeping startup dependencies outside it."""

    async def answer(_: ReceivedBurst) -> PcmAudio:
        return audio

    return answer


def main() -> None:
    level = os.environ.get("ZENPTT_LOG_LEVEL", "INFO").upper()
    logging.basicConfig(level=level, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    audio = load_pcm(os.environ.get("ZENPTT_PCM_PATH", "/audio/QRZ.pcm"))

    config = BotConfig(
        ClientConfig(
            server_url=os.environ["ZENPTT_SERVER_URL"],
            channel=os.environ.get("ZENPTT_CHANNEL", "Test"),
        )
    )
    run_standalone(config, make_qrz_handler(audio))


if __name__ == "__main__":
    main()
