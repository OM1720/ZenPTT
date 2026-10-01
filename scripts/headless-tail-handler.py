"""Integration-only bot handler that exposes received metadata through logs."""

from __future__ import annotations

import asyncio
from array import array
import json
import logging
import math
import os

from zenptt_headless import BotConfig, ClientConfig, ReceivedBurst, run_bot
from zenptt_headless.audio import load_pcm


async def main() -> None:
    logging.basicConfig(
        level=os.environ.get("ZENPTT_LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    response = load_pcm(os.environ["ZENPTT_PCM_PATH"])

    async def on_burst(burst: ReceivedBurst):
        samples = array("h", burst.audio.data)[-8000:]
        energy = sum(value * value for value in samples)
        sine = sum(value * math.sin(2 * math.pi * 700 * i / 16000)
                   for i, value in enumerate(samples))
        cosine = sum(value * math.cos(2 * math.pi * 700 * i / 16000)
                     for i, value in enumerate(samples))
        print(
            "tail_observer_result "
            + json.dumps(
                {
                    "burst_id": burst.burst_id,
                    "burst_index": burst.burst_index,
                    "first_sequence": burst.first_sequence,
                    "frames": len(burst.audio.data) // 640,
                    "losses": [
                        {
                            "first_sequence": item.first_sequence,
                            "count": item.count,
                        }
                        for item in burst.losses
                    ],
                    "decode_errors": list(burst.decode_errors),
                    "reason": burst.reason,
                    "session_epoch": burst.session_epoch,
                    "pcm_rms": math.sqrt(energy / len(samples)) if samples else 0,
                    "tone_fraction": 2 * (sine*sine + cosine*cosine) / (len(samples)*energy)
                    if energy else 0,
                },
                separators=(",", ":"),
            ),
            flush=True,
        )
        return response

    await run_bot(
        BotConfig(
            ClientConfig(
                os.environ["ZENPTT_SERVER_URL"],
                os.environ.get("ZENPTT_CHANNEL", "Test"),
            )
        ),
        on_burst,
    )


if __name__ == "__main__":
    asyncio.run(main())
