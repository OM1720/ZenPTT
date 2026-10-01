"""Shared bounded process lifecycle for standalone ZenPTT bots."""

from __future__ import annotations

import asyncio
import math
import os
import threading

from .bot import MAX_SHUTDOWN_SECONDS, BotConfig, BurstHandler, _run_bot


class ShutdownWatchdog:
    """One-shot process shutdown deadline; expiry forces exit with status 1.

    Start synchronously when shutdown begins and cancel only after process-level
    cleanup. Repeated starts cannot extend the deadline, even during signal reentry.
    """

    def __init__(self, seconds: float = MAX_SHUTDOWN_SECONDS) -> None:
        if (
            type(seconds) not in (int, float)
            or not math.isfinite(seconds)
            or not 0 < seconds <= MAX_SHUTDOWN_SECONDS
        ):
            raise ValueError("Watchdog timeout must be positive and at most five seconds")
        self.seconds = seconds
        self._lock = threading.RLock()
        self._started = False
        self._cancelled = False
        self._timer: threading.Timer | None = None

    @property
    def started(self) -> bool:
        return self._started

    def start(self) -> None:
        with self._lock:
            if self._started or self._cancelled:
                return
            # A Python signal can reenter start() during Timer construction.
            self._started = True
            self._timer = threading.Timer(self.seconds, os._exit, (1,))
            self._timer.daemon = True
            self._timer.start()

    def cancel(self) -> None:
        with self._lock:
            self._cancelled = True
            if self._timer is not None:
                self._timer.cancel()


async def _run_standalone(
    config: BotConfig, handler: BurstHandler, watchdog: ShutdownWatchdog
) -> None:
    await _run_bot(config, handler, on_shutdown=watchdog.start, on_signal=watchdog.start)


def run_standalone(config: BotConfig, handler: BurstHandler) -> None:
    """Run one standalone bot with the process watchdog armed during shutdown."""

    watchdog = ShutdownWatchdog()
    try:
        asyncio.run(_run_standalone(config, handler, watchdog))
    finally:
        watchdog.cancel()
