"""Reusable lifecycle, backpressure, and delivery loop for ZenPTT bots."""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from contextlib import suppress
from dataclasses import dataclass
import logging
import math
import signal

from .client import ClientConfig, HeadlessClient
from .memory import MAX_PCM_BYTES, PcmBudget
from .types import PcmAudio, ReceiveInterrupted, ReceivedBurst

logger = logging.getLogger("zenptt.headless")
BurstHandler = Callable[[ReceivedBurst], Awaitable[PcmAudio | None]]
HANDLER_CANCEL_TIMEOUT_SECONDS = 0.5
MAX_HANDLER_QUEUE_SIZE = 4
MAX_RESPONSE_QUEUE_SIZE = 32
MAX_SHUTDOWN_SECONDS = 5.0


@dataclass(frozen=True)
class BotConfig:
    client: ClientConfig
    receive_queue_size: int = MAX_HANDLER_QUEUE_SIZE
    send_queue_size: int = MAX_RESPONSE_QUEUE_SIZE
    send_queue_bytes: int = 64 * 1024 * 1024
    busy_retry_seconds: float = 1.0
    shutdown_seconds: float = MAX_SHUTDOWN_SECONDS

    def __post_init__(self) -> None:
        for size, maximum in (
            (self.receive_queue_size, MAX_HANDLER_QUEUE_SIZE),
            (self.send_queue_size, MAX_RESPONSE_QUEUE_SIZE),
        ):
            if type(size) is not int or not 0 < size <= maximum:
                raise ValueError(f"Bot queue size must be an integer from 1 through {maximum}")
        if not 0 < self.send_queue_bytes <= MAX_PCM_BYTES:
            raise ValueError("Bot PCM limit must be positive and at most 64 MiB")
        if not math.isfinite(self.busy_retry_seconds) or self.busy_retry_seconds < 1:
            raise ValueError("Busy retries must be at least one second apart")
        if not math.isfinite(self.shutdown_seconds) or not 0 < self.shutdown_seconds <= MAX_SHUTDOWN_SECONDS:
            raise ValueError("Shutdown timeout must be positive and at most five seconds")


class _BotRunner:
    def __init__(
        self,
        config: BotConfig,
        handler: BurstHandler,
        client: HeadlessClient,
        on_shutdown: Callable[[], None] | None = None,
    ) -> None:
        self.config = config
        self.handler = handler
        self.client = client
        self.client._busy_retry_seconds = config.busy_retry_seconds
        self._pcm_budget = getattr(client, "_pcm_budget", None) or PcmBudget()
        self._pcm_budget.set_limit(config.send_queue_bytes)
        self.receive_queue: asyncio.Queue[ReceivedBurst] = asyncio.Queue(config.receive_queue_size)
        self.send_queue: asyncio.Queue[tuple[str, PcmAudio, int]] = asyncio.Queue(
            config.send_queue_size
        )
        self.stop_event = asyncio.Event()
        self.current_handler: asyncio.Task[PcmAudio | None] | None = None
        self.current_send: asyncio.Task | None = None
        self._session_cancelled_handler: asyncio.Task | None = None
        self._session_cancelled_send: asyncio.Task | None = None
        self._send_cancel_reason: str | None = None
        self._shutdown_deadline: float | None = None
        self._shutdown_children: set[asyncio.Task] = set()
        self._shutdown_send: asyncio.Task | None = None
        self._on_shutdown = on_shutdown
        self._fatal_error: BaseException | None = None
        self._handler_cancel_timer: asyncio.TimerHandle | None = None

    def request_stop(self, error: BaseException | None = None) -> None:
        if error is not None and self._fatal_error is None:
            self._fatal_error = error
        if self._shutdown_deadline is not None:
            self.stop_event.set()
            return
        self._shutdown_deadline = (
            asyncio.get_running_loop().time() + self.config.shutdown_seconds
        )
        if self._on_shutdown is not None:
            with suppress(Exception):
                self._on_shutdown()
        self.stop_event.set()
        self._clear_queues()
        for task in (self.current_handler, self.current_send):
            if task is not None:
                self._shutdown_children.add(task)
                if task is self.current_send:
                    self._shutdown_send = task
                if task is not self._session_cancelled_handler:
                    task.cancel()

    async def run(self) -> None:
        workers: list[asyncio.Task] = []
        start_task = asyncio.create_task(self.client.start())
        stop_waiter = asyncio.create_task(self.stop_event.wait())
        try:
            ready, _ = await asyncio.wait(
                (start_task, stop_waiter), return_when=asyncio.FIRST_COMPLETED
            )
            if stop_waiter in ready:
                self.request_stop()
                if self._fatal_error is not None:
                    raise self._fatal_error
                return
            await start_task
            if self.stop_event.is_set():
                self.request_stop()
                return
            workers = [
                asyncio.create_task(self._collect()),
                asyncio.create_task(self._handle()),
                asyncio.create_task(self._send()),
            ]
            done, _ = await asyncio.wait(
                [*workers, stop_waiter], return_when=asyncio.FIRST_COMPLETED
            )
            if stop_waiter in done or self.stop_event.is_set():
                self.request_stop()
                if self._fatal_error is not None:
                    raise self._fatal_error
                return
            failed = next(task for task in workers if task in done)
            if failed.cancelled():
                raise RuntimeError("Bot worker stopped unexpectedly")
            error = failed.exception()
            if error is not None:
                raise error
            raise RuntimeError("Bot worker stopped unexpectedly")
        finally:
            self.request_stop()
            shutdown_deadline = self._shutdown_deadline
            assert shutdown_deadline is not None
            self._session_cancelled_handler = None
            self._session_cancelled_send = None
            self._send_cancel_reason = None
            stop_waiter.cancel()
            start_task.cancel()
            send_task = self._shutdown_send
            children = set(self._shutdown_children)
            children.update(
                task
                for task in (self.current_handler, self.current_send)
                if task is not None
            )
            for task in workers:
                task.cancel()
            client_stop = asyncio.create_task(
                self._stop_client_after_send(send_task, shutdown_deadline)
            )
            cleanup = {start_task, stop_waiter, *workers, *children, client_stop}
            remaining = max(0, shutdown_deadline - asyncio.get_running_loop().time())
            done, pending = await asyncio.wait(cleanup, timeout=remaining)
            if done:
                await asyncio.gather(*done, return_exceptions=True)
            self._shutdown_children.intersection_update(pending)
            self._shutdown_send = None
            for task in pending:
                task.cancel()
                task.add_done_callback(self._consume_task_result)
                task.add_done_callback(self._shutdown_children.discard)
            if pending:
                raise TimeoutError("Bot shutdown exceeded its deadline")

    async def _collect(self) -> None:
        while True:
            event = await self.client.receive()
            try:
                self._collect_event(event)
            finally:
                del event
            if self.stop_event.is_set():
                return

    def _collect_event(self, event: ReceivedBurst | ReceiveInterrupted) -> None:
        if self.stop_event.is_set() or event.session_epoch != self.client.session_epoch:
            return
        if isinstance(event, ReceiveInterrupted):
            logger.warning("receive_interrupted reason=%s", event.reason)
            if event.reason.startswith("server_error_") or event.reason in {
                "grant_timeout", "resume_rejected", "server_incarnation_changed",
                "recovery_timeout", "payload_mismatch", "ptt_cancelled",
                "send_finalization_failed",
            }:
                if self.current_handler is not None:
                    self._cancel_handler_for_session_loss(self.current_handler)
                if self.current_send is not None:
                    self._send_cancel_reason = event.reason
                    self._session_cancelled_send = self.current_send
                    self.current_send.cancel()
                self._clear_queues()
            return
        if not self._pcm_budget.admit(event.audio):
            logger.error("pcm_budget_exceeded burst=%s", event.burst_index)
            return
        try:
            self.receive_queue.put_nowait(event)
        except asyncio.QueueFull:
            logger.error("handler_queue_full burst=%s", event.burst_index)

    async def _handle(self) -> None:
        while True:
            burst = await self.receive_queue.get()
            try:
                await self._handle_burst(burst)
            finally:
                del burst
            if self.stop_event.is_set():
                return

    async def _handle_burst(self, burst: ReceivedBurst) -> None:
        epoch = burst.session_epoch
        if self.stop_event.is_set() or epoch != self.client.session_epoch:
            return
        if not self._pcm_budget.admit(burst.audio):
            logger.error("pcm_budget_exceeded burst=%s", burst.burst_index)
            return
        handler_task = None
        try:
            handler_task = asyncio.create_task(self.handler(burst))
            self.current_handler = handler_task
            response = await asyncio.shield(handler_task)
        except asyncio.CancelledError:
            if self.stop_event.is_set() or self._session_cancelled_handler is not handler_task:
                raise
            return
        except Exception as error:
            logger.error("handler_failed burst=%s error=%s", burst.burst_index, type(error).__name__)
            return
        finally:
            if self._session_cancelled_handler is handler_task:
                self._session_cancelled_handler = None
                if self._handler_cancel_timer is not None:
                    self._handler_cancel_timer.cancel()
                    self._handler_cancel_timer = None
            if self.current_handler is handler_task:
                self.current_handler = None
        if self.stop_event.is_set() or response is None or epoch != self.client.session_epoch:
            return
        if not isinstance(response, PcmAudio):
            logger.error("handler_failed burst=%s error=invalid_response", burst.burst_index)
            return
        if not self._pcm_budget.admit(response):
            logger.error("pcm_budget_exceeded burst=%s", burst.burst_index)
            return
        try:
            self.send_queue.put_nowait((burst.burst_id, response, epoch))
        except asyncio.QueueFull:
            logger.error("send_queue_full burst=%s", burst.burst_index)

    async def _send(self) -> None:
        while True:
            source_id, audio, epoch = await self.send_queue.get()
            try:
                await self._send_response(source_id, audio, epoch)
            finally:
                del audio
            if self.stop_event.is_set():
                return

    async def _send_response(self, source_id: str, audio: PcmAudio, epoch: int) -> None:
        if self.stop_event.is_set() or epoch != self.client.session_epoch:
            return
        if not self._pcm_budget.admit(audio):
            logger.error("pcm_budget_exceeded source=%s", source_id)
            return
        self.current_send = asyncio.create_task(
            self.client.send_audio(audio, expected_session_epoch=epoch)
        )
        try:
            result = await asyncio.shield(self.current_send)
        except asyncio.CancelledError:
            if self.stop_event.is_set() or self._session_cancelled_send is not self.current_send:
                raise
            self._session_cancelled_send = None
            reason = self._send_cancel_reason or "session_lost"
            self._send_cancel_reason = None
            logger.info(
                "response_finished source=%s status=interrupted reason=%s", source_id, reason
            )
            return
        finally:
            if self._session_cancelled_send is self.current_send:
                self._session_cancelled_send = None
                self._send_cancel_reason = None
            self.current_send = None
        if self.stop_event.is_set():
            return
        logger.info(
            "response_finished source=%s status=%s reason=%s",
            source_id, result.status, result.reason,
        )

    def _cancel_handler_for_session_loss(
        self, task: asyncio.Task[PcmAudio | None]
    ) -> None:
        if self._session_cancelled_handler is task:
            return
        self._session_cancelled_handler = task
        task.cancel()
        loop = asyncio.get_running_loop()

        def cancel_timed_out() -> None:
            if self._session_cancelled_handler is task and not task.done():
                logger.error("handler_cancel_timeout")
                self.request_stop(RuntimeError("handler_cancel_timeout"))

        self._handler_cancel_timer = loop.call_later(
            HANDLER_CANCEL_TIMEOUT_SECONDS, cancel_timed_out
        )

    def _clear_queues(self) -> None:
        while not self.receive_queue.empty():
            with suppress(asyncio.QueueEmpty):
                self.receive_queue.get_nowait()
        while not self.send_queue.empty():
            with suppress(asyncio.QueueEmpty):
                self.send_queue.get_nowait()

    @staticmethod
    def _consume_task_result(task: asyncio.Task) -> None:
        with suppress(asyncio.CancelledError, Exception):
            task.result()

    async def _stop_client_after_send(
        self, send_task: asyncio.Task | None, deadline: float
    ) -> None:
        if send_task is not None and not send_task.done():
            remaining = max(0, deadline - asyncio.get_running_loop().time())
            await asyncio.wait({send_task}, timeout=min(0.5, remaining))
        await self.client.stop()


async def run_bot(config: BotConfig, on_burst: BurstHandler) -> None:
    """Run a handler with the common ZenPTT bot lifecycle until SIGINT/SIGTERM."""
    await _run_bot(config, on_burst)


async def run_bot_session(
    config: BotConfig, on_burst: BurstHandler, client: HeadlessClient
) -> None:
    """Own a fresh, exclusive client until cancellation or failure, without signals.

    The caller must not start or share the client. Cancellation stops it within
    the configured shutdown budget, then propagates CancelledError. Cleanup
    exceeding that budget raises TimeoutError instead; this never exits the process.
    """

    if client.config != config.client:
        raise ValueError("Bot and client configurations must match")
    await _BotRunner(config, on_burst, client).run()


async def _run_bot(
    config: BotConfig,
    on_burst: BurstHandler,
    *,
    on_shutdown: Callable[[], None] | None = None,
    on_signal: Callable[[], None] | None = None,
) -> None:
    client = HeadlessClient(config.client)
    runner = _BotRunner(config, on_burst, client, on_shutdown)
    loop = asyncio.get_running_loop()
    process_signals: list[tuple[signal.Signals, signal.Handlers]] = []

    def request_shutdown() -> None:
        runner.request_stop()

    def receive_signal(_signum, _frame) -> None:
        # Standalone emergency shutdown must not wait for a blocked event loop.
        if on_signal is not None:
            on_signal()
        loop.call_soon_threadsafe(request_shutdown)

    try:
        # Temporarily replace Python handlers without removing any host asyncio
        # callbacks. Both those callbacks and asyncio.run's SIGINT handler must
        # remain usable after the library returns, including on startup failure.
        for signum in (signal.SIGINT, signal.SIGTERM):
            try:
                previous = signal.getsignal(signum)
                signal.signal(signum, receive_signal)
                process_signals.append((signum, previous))
            except ValueError:
                pass
        await runner.run()
    finally:
        for signum, previous in process_signals:
            signal.signal(signum, previous)
