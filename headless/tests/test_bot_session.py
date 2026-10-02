import asyncio
from contextlib import suppress
import signal

import pytest

from zenptt_headless import (
    BotConfig,
    ClientConfig,
    PcmAudio,
    ReceivedBurst,
    ReceiveInterrupted,
    SendResult,
    ShutdownWatchdog,
    run_bot_session,
)


class SessionClient:
    def __init__(self, config: ClientConfig) -> None:
        self.config = config
        self.session_epoch = 0
        self.events = asyncio.Queue()
        self.sent = asyncio.Queue()
        self.starting = asyncio.Event()
        self.stopped = asyncio.Event()
        self.release_stop = asyncio.Event()
        self.block_start = False
        self.block_stop = False
        self.start_error = False

    async def start(self) -> None:
        self.starting.set()
        if self.start_error:
            raise RuntimeError("Connection failed")
        if self.block_start:
            await asyncio.Event().wait()

    async def receive(self):
        event = await self.events.get()
        if isinstance(event, Exception):
            raise event
        return event

    async def send_audio(self, audio, *, expected_session_epoch=None):
        await self.sent.put(audio)
        return SendResult("sent", "complete", "response")

    async def stop(self) -> None:
        if self.block_stop:
            while not self.release_stop.is_set():
                with suppress(asyncio.CancelledError):
                    await self.release_stop.wait()
        self.stopped.set()


def burst(index: int = 0) -> ReceivedBurst:
    return ReceivedBurst("source", index, PcmAudio(bytes(640)), (), (), "complete")


async def echo(received: ReceivedBurst) -> PcmAudio:
    return received.audio


@pytest.mark.asyncio
async def test_session_rejects_mismatched_config_before_client_start() -> None:
    client = SessionClient(ClientConfig("ws://test", "FIRST"))
    with pytest.raises(ValueError, match="configurations must match"):
        await run_bot_session(BotConfig(ClientConfig("ws://test", "SECOND")), echo, client)
    assert not client.starting.is_set()
    assert not client.stopped.is_set()


@pytest.mark.asyncio
async def test_session_delivers_bursts_and_allows_no_response() -> None:
    client = SessionClient(ClientConfig("ws://test"))
    inspected = asyncio.Event()
    received = []

    async def answer(event):
        received.append(event)
        if event.burst_index == 1:
            inspected.set()
            return None
        return event.audio

    # Equal configurations need not be the same Python object.
    task = asyncio.create_task(run_bot_session(
        BotConfig(ClientConfig("ws://test")), answer, client,
    ))
    try:
        first, second = burst(), burst(1)
        await client.events.put(ReceiveInterrupted(None, None, "pcm_budget_exceeded"))
        await client.events.put(first)
        assert await asyncio.wait_for(client.sent.get(), 1) is first.audio
        await client.events.put(second)
        await asyncio.wait_for(inspected.wait(), 1)
        assert received == [first, second]
        assert client.sent.empty()
    finally:
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
    assert client.stopped.is_set()


@pytest.mark.asyncio
async def test_session_stops_client_after_startup_failure() -> None:
    client = SessionClient(ClientConfig("ws://test"))
    client.start_error = True
    with pytest.raises(RuntimeError, match="Connection failed"):
        await run_bot_session(BotConfig(client.config), echo, client)
    assert client.stopped.is_set()


@pytest.mark.asyncio
@pytest.mark.parametrize("phase", ["connect", "handler"])
async def test_session_cancellation_stops_connecting_or_processing_client(phase) -> None:
    client = SessionClient(ClientConfig("ws://test"))
    client.block_start = phase == "connect"
    handler_started, handler_stopped = asyncio.Event(), asyncio.Event()

    async def wait_for_cancel(_burst):
        handler_started.set()
        try:
            await asyncio.Event().wait()
        finally:
            handler_stopped.set()

    task = asyncio.create_task(run_bot_session(BotConfig(client.config), wait_for_cancel, client))
    try:
        await asyncio.wait_for(client.starting.wait(), 1)
        if phase == "handler":
            await client.events.put(burst())
            await asyncio.wait_for(handler_started.wait(), 1)
    finally:
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(task, 1)
    assert client.stopped.is_set()
    assert handler_stopped.is_set() == (phase == "handler")


@pytest.mark.asyncio
async def test_session_cleanup_timeout_replaces_cancellation_without_process_exit() -> None:
    client = SessionClient(ClientConfig("ws://test"))
    client.block_stop = True
    task = asyncio.create_task(run_bot_session(
        BotConfig(client.config, shutdown_seconds=0.03), echo, client,
    ))
    try:
        await asyncio.wait_for(client.starting.wait(), 1)
        task.cancel()
        with pytest.raises(TimeoutError, match="shutdown exceeded"):
            await asyncio.wait_for(task, 1)
        assert not client.stopped.is_set()
    finally:
        client.release_stop.set()
        await asyncio.wait_for(client.stopped.wait(), 1)
        if not task.done():
            task.cancel()
        await asyncio.gather(task, return_exceptions=True)


@pytest.mark.asyncio
@pytest.mark.parametrize("outcome", ["cancel", "failure"])
async def test_concurrent_sessions_preserve_siblings_and_host_signals(monkeypatch, outcome) -> None:
    previous = {signum: signal.getsignal(signum) for signum in (signal.SIGINT, signal.SIGTERM)}

    def reject_signal_change(*_args):
        pytest.fail("A managed session must not install process signal handlers")

    monkeypatch.setattr(signal, "signal", reject_signal_change)
    clients = [SessionClient(ClientConfig("ws://test", channel)) for channel in ("ONE", "TWO")]
    tasks = [asyncio.create_task(run_bot_session(BotConfig(c.config), echo, c)) for c in clients]
    try:
        for client in clients:
            await asyncio.wait_for(client.starting.wait(), 1)
            first = burst()
            await client.events.put(first)
            assert await asyncio.wait_for(client.sent.get(), 1) is first.audio
        if outcome == "cancel":
            tasks[0].cancel()
            with pytest.raises(asyncio.CancelledError):
                await tasks[0]
        else:
            await clients[0].events.put(RuntimeError("Session failed"))
            with pytest.raises(RuntimeError, match="Session failed"):
                await tasks[0]
        assert clients[0].stopped.is_set()
        assert not clients[1].stopped.is_set()
        second = burst(1)
        await clients[1].events.put(second)
        assert await asyncio.wait_for(clients[1].sent.get(), 1) is second.audio
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        # Restore before pytest-asyncio's runner resets its own SIGINT handler.
        monkeypatch.undo()
    assert all(client.stopped.is_set() for client in clients)
    assert all(signal.getsignal(signum) == value for signum, value in previous.items())


@pytest.mark.parametrize("seconds", [0, -1, 5.01, float("inf"), float("nan"), True, None, "1"])
def test_watchdog_rejects_invalid_timeout(seconds) -> None:
    with pytest.raises(ValueError, match="Watchdog timeout"):
        ShutdownWatchdog(seconds)


@pytest.mark.parametrize("seconds", [0.01, 1, 5.0])
def test_watchdog_accepts_bounded_timeout(seconds) -> None:
    watchdog = ShutdownWatchdog(seconds)
    assert watchdog.seconds == seconds
    assert not watchdog.started


def test_watchdog_cancel_before_start_permanently_disarms_it(monkeypatch) -> None:
    def unexpected_timer(*_args):
        pytest.fail("A cancelled watchdog must not create a timer")

    monkeypatch.setattr("zenptt_headless.standalone.threading.Timer", unexpected_timer)
    watchdog = ShutdownWatchdog()
    watchdog.cancel()
    watchdog.cancel()
    watchdog.start()
    assert not watchdog.started
