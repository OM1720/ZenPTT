import asyncio
from contextlib import suppress
import json
from unittest.mock import patch

import pytest

from zenptt_headless.bot import BotConfig, _BotRunner
from zenptt_headless.client import ClientConfig, HeadlessClient
from zenptt_headless.protocol import UPLINK, decode_media
from zenptt_headless import ShutdownWatchdog
from zenptt_headless.types import PcmAudio, ReceiveInterrupted, ReceivedBurst, SendResult


class FakeClient:
    def __init__(self) -> None:
        self.events = asyncio.Queue()
        self.sent = []
        self.session_epoch = 0

    async def start(self):
        pass

    async def receive(self):
        return await self.events.get()

    async def send_audio(self, audio, *, expected_session_epoch=None):
        self.sent.append(audio)
        return SendResult("sent", "complete", "response")

    async def stop(self):
        pass


def burst(index: int = 1) -> ReceivedBurst:
    return ReceivedBurst(str(index), index, PcmAudio(b"\0\0"), (), (), "complete")


@pytest.mark.asyncio
async def test_handler_can_return_none_without_changing_runner() -> None:
    client = FakeClient()

    async def inspect_only(received: ReceivedBurst):
        assert received.reason == "complete"
        return None

    runner = _BotRunner(BotConfig(ClientConfig("ws://test")), inspect_only, client)
    task = asyncio.create_task(runner._handle())
    await runner.receive_queue.put(burst())
    await asyncio.sleep(0)
    await asyncio.sleep(0)
    assert runner.send_queue.empty()
    task.cancel()


@pytest.mark.asyncio
async def test_handler_response_is_queued_by_reference() -> None:
    client = FakeClient()
    response = PcmAudio(b"\1\0" * 320)

    async def answer(_: ReceivedBurst):
        return response

    runner = _BotRunner(BotConfig(ClientConfig("ws://test")), answer, client)
    task = asyncio.create_task(runner._handle())
    source = burst()
    await runner.receive_queue.put(source)
    queued_source, queued_audio, queued_epoch = await asyncio.wait_for(runner.send_queue.get(), 1)
    assert queued_source == source.burst_id
    assert queued_audio is response
    assert queued_epoch == source.session_epoch
    task.cancel()


@pytest.mark.asyncio
async def test_receive_queue_rejects_new_work_at_four_items() -> None:
    client = FakeClient()

    async def answer(_: ReceivedBurst):
        return None

    runner = _BotRunner(BotConfig(ClientConfig("ws://test")), answer, client)
    task = asyncio.create_task(runner._collect())
    for index in range(6):
        await client.events.put(burst(index))
    await asyncio.sleep(0)
    assert runner.receive_queue.qsize() == 4
    task.cancel()


@pytest.mark.asyncio
async def test_session_loss_cancels_active_handler_and_clears_queues() -> None:
    client = FakeClient()
    started = asyncio.Event()
    canceled = asyncio.Event()

    async def slow(_: ReceivedBurst):
        started.set()
        try:
            await asyncio.Event().wait()
        finally:
            canceled.set()

    runner = _BotRunner(BotConfig(ClientConfig("ws://test")), slow, client)
    collect = asyncio.create_task(runner._collect())
    handle = asyncio.create_task(runner._handle())
    await client.events.put(burst())
    await asyncio.wait_for(started.wait(), 1)
    await client.events.put(ReceiveInterrupted(None, None, "resume_rejected"))
    await asyncio.wait_for(canceled.wait(), 1)
    assert runner.receive_queue.empty()
    assert runner.send_queue.empty()
    collect.cancel()
    handle.cancel()


@pytest.mark.asyncio
async def test_suppressed_handler_cancellation_cannot_queue_a_stale_response() -> None:
    client = FakeClient()
    started = asyncio.Event()
    next_started = asyncio.Event()

    async def ignores_cancellation(received: ReceivedBurst):
        if received.session_epoch == 1:
            next_started.set()
            return None
        started.set()
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            return PcmAudio(bytes(640))

    runner = _BotRunner(BotConfig(ClientConfig("ws://test")), ignores_cancellation, client)
    collect = asyncio.create_task(runner._collect())
    handle = asyncio.create_task(runner._handle())
    await client.events.put(burst())
    await asyncio.wait_for(started.wait(), 1)
    client.session_epoch = 1
    await client.events.put(ReceiveInterrupted(None, None, "resume_rejected", 1))
    await asyncio.sleep(0)
    await asyncio.sleep(0)
    assert runner.send_queue.empty()
    await client.events.put(
        ReceivedBurst("new", 0, PcmAudio(b""), (), (), "complete", session_epoch=1)
    )
    await asyncio.wait_for(next_started.wait(), 1)
    runner.stop_event.set()
    collect.cancel()
    handle.cancel()
    await asyncio.gather(collect, handle, return_exceptions=True)


@pytest.mark.asyncio
async def test_stuck_session_handler_fails_runner_and_starts_shutdown_once() -> None:
    client = FakeClient()
    started = asyncio.Event()
    release = asyncio.Event()
    shutdowns = 0

    async def refuses_cancellation(_: ReceivedBurst):
        started.set()
        while not release.is_set():
            try:
                await release.wait()
            except asyncio.CancelledError:
                continue

    def on_shutdown() -> None:
        nonlocal shutdowns
        shutdowns += 1

    runner = _BotRunner(
        BotConfig(ClientConfig("ws://test"), shutdown_seconds=0.03),
        refuses_cancellation,
        client,
        on_shutdown,
    )
    with patch("zenptt_headless.bot.HANDLER_CANCEL_TIMEOUT_SECONDS", 0.02):
        task = asyncio.create_task(runner.run())
        try:
            await client.events.put(burst())
            await asyncio.wait_for(started.wait(), 1)
            client.session_epoch = 1
            await client.events.put(
                ReceiveInterrupted(None, None, "resume_rejected", 1)
            )
            while runner._session_cancelled_handler is None:
                await asyncio.sleep(0)
            await client.events.put(
                ReceiveInterrupted(None, None, "resume_rejected", 1)
            )
            with pytest.raises(TimeoutError, match="shutdown exceeded"):
                await asyncio.wait_for(task, 0.5)
            assert shutdowns == 1
            assert runner._fatal_error is not None
            assert str(runner._fatal_error) == "handler_cancel_timeout"
            assert runner._shutdown_children
        finally:
            release.set()
            if not task.done():
                task.cancel()
            await asyncio.gather(task, return_exceptions=True)


@pytest.mark.asyncio
async def test_session_cancelled_send_does_not_stop_the_sender_worker() -> None:
    class BlockingClient(FakeClient):
        def __init__(self) -> None:
            super().__init__()
            self.first_started = asyncio.Event()
            self.second_finished = asyncio.Event()

        async def send_audio(self, audio, *, expected_session_epoch=None):
            if expected_session_epoch == 0:
                self.first_started.set()
                await asyncio.Event().wait()
            self.sent.append((audio, expected_session_epoch))
            self.second_finished.set()
            return SendResult("sent", "complete", "response")

    client = BlockingClient()
    runner = _BotRunner(BotConfig(ClientConfig("ws://test")), lambda _: None, client)
    collect = asyncio.create_task(runner._collect())
    sender = asyncio.create_task(runner._send())
    audio = PcmAudio(bytes(640))
    await runner.send_queue.put(("old", audio, 0))
    await asyncio.wait_for(client.first_started.wait(), 1)

    client.session_epoch = 1
    await client.events.put(ReceiveInterrupted(None, None, "resume_rejected", 1))
    while runner.current_send is not None:
        await asyncio.sleep(0)
    await runner.send_queue.put(("new", audio, 1))
    await asyncio.wait_for(client.second_finished.wait(), 1)

    assert client.sent == [(audio, 1)]
    runner.stop_event.set()
    collect.cancel()
    sender.cancel()
    await asyncio.gather(collect, sender, return_exceptions=True)


@pytest.mark.asyncio
async def test_runner_fails_when_a_worker_stops_unexpectedly() -> None:
    class BrokenClient(FakeClient):
        async def receive(self):
            raise RuntimeError("receive failed")

    runner = _BotRunner(
        BotConfig(ClientConfig("ws://test")), lambda _: None, BrokenClient()
    )
    with pytest.raises(RuntimeError, match="receive failed"):
        await asyncio.wait_for(runner.run(), 1)


@pytest.mark.asyncio
async def test_runner_stops_client_when_start_fails() -> None:
    class BrokenStartClient(FakeClient):
        def __init__(self) -> None:
            super().__init__()
            self.stopped = False

        async def start(self):
            raise RuntimeError("start failed")

        async def stop(self):
            self.stopped = True

    client = BrokenStartClient()
    runner = _BotRunner(BotConfig(ClientConfig("ws://test")), lambda _: None, client)
    with pytest.raises(RuntimeError, match="start failed"):
        await runner.run()
    assert client.stopped


@pytest.mark.asyncio
async def test_runner_stop_interrupts_a_blocked_start_within_shared_budget() -> None:
    class BlockingStartClient(FakeClient):
        def __init__(self) -> None:
            super().__init__()
            self.start_entered = asyncio.Event()
            self.stop_called = asyncio.Event()
            self.start_cancelled = False

        async def start(self):
            self.start_entered.set()
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                self.start_cancelled = True
                await self.stop_called.wait()

        async def stop(self):
            self.stop_called.set()

    client = BlockingStartClient()
    runner = _BotRunner(
        BotConfig(ClientConfig("ws://test"), shutdown_seconds=0.1),
        lambda _: None,
        client,
    )
    task = asyncio.create_task(runner.run())
    await asyncio.wait_for(client.start_entered.wait(), 1)
    runner.request_stop()
    await asyncio.wait_for(task, 0.3)

    assert client.start_cancelled
    assert client.stop_called.is_set()


@pytest.mark.asyncio
async def test_runner_stop_wins_a_race_with_session_readiness() -> None:
    class RacingClient(FakeClient):
        def __init__(self) -> None:
            super().__init__()
            self.release_start = asyncio.Event()
            self.stopped = False
            self.receive_called = False

        async def start(self):
            await self.release_start.wait()

        async def receive(self):
            self.receive_called = True
            return await super().receive()

        async def stop(self):
            self.stopped = True

    client = RacingClient()
    runner = _BotRunner(BotConfig(ClientConfig("ws://test")), lambda _: None, client)
    task = asyncio.create_task(runner.run())
    await asyncio.sleep(0)
    runner.request_stop()
    client.release_start.set()
    await asyncio.wait_for(task, 0.3)

    assert client.stopped
    assert not client.receive_called


@pytest.mark.asyncio
async def test_sender_does_not_start_work_returned_as_stop_is_requested() -> None:
    client = FakeClient()
    audio = PcmAudio(bytes(640))
    runner = _BotRunner(BotConfig(ClientConfig("ws://test")), lambda _: None, client)

    class StopOnGetQueue:
        async def get(self):
            runner.request_stop()
            return "source", audio, 0

        def empty(self):
            return True

    runner.send_queue = StopOnGetQueue()
    await asyncio.wait_for(runner._send(), 0.2)
    assert client.sent == []


@pytest.mark.asyncio
async def test_collector_drops_event_returned_as_stop_is_requested() -> None:
    client = FakeClient()
    runner = _BotRunner(BotConfig(ClientConfig("ws://test")), lambda _: None, client)

    async def receive_and_stop():
        runner.request_stop()
        return burst()

    client.receive = receive_and_stop
    await asyncio.wait_for(runner._collect(), 0.2)
    assert runner.receive_queue.empty()


@pytest.mark.asyncio
async def test_handler_result_completed_with_stop_is_discarded() -> None:
    client = FakeClient()
    response = PcmAudio(bytes(640))

    async def answer_and_stop(_burst):
        runner.request_stop()
        return response

    runner = _BotRunner(
        BotConfig(ClientConfig("ws://test")), answer_and_stop, client
    )
    runner.receive_queue.put_nowait(burst())
    with suppress(asyncio.CancelledError):
        await asyncio.wait_for(runner._handle(), 0.2)
    assert runner.send_queue.empty()


@pytest.mark.asyncio
async def test_stop_during_busy_retry_prevents_another_send() -> None:
    class BusyClient(FakeClient):
        def __init__(self) -> None:
            super().__init__()
            self.calls = 0
            self.first_finished = asyncio.Event()

        async def send_audio(self, audio, *, expected_session_epoch=None):
            self.calls += 1
            self.first_finished.set()
            return SendResult("denied", "channel_busy")

    client = BusyClient()
    runner = _BotRunner(
        BotConfig(ClientConfig("ws://test"), busy_retry_seconds=10),
        lambda _: None,
        client,
    )
    task = asyncio.create_task(runner.run())
    runner.send_queue.put_nowait(("source", PcmAudio(bytes(640)), 0))
    await asyncio.wait_for(client.first_finished.wait(), 1)
    runner.request_stop()
    await asyncio.wait_for(task, 0.3)
    assert client.calls == 1


@pytest.mark.asyncio
async def test_stop_deadline_is_fixed_and_worker_stop_race_is_normal() -> None:
    class StopOnReceiveClient(FakeClient):
        async def receive(self):
            runner.request_stop()
            return burst()

    client = StopOnReceiveClient()
    runner = _BotRunner(BotConfig(ClientConfig("ws://test")), lambda _: None, client)
    task = asyncio.create_task(runner.run())
    await asyncio.wait_for(runner.stop_event.wait(), 1)
    deadline = runner._shutdown_deadline
    runner.request_stop()
    assert runner._shutdown_deadline == deadline
    await asyncio.wait_for(task, 0.3)


@pytest.mark.asyncio
async def test_repeated_stop_does_not_cancel_child_cleanup_again() -> None:
    class FinalizingClient(FakeClient):
        def __init__(self) -> None:
            super().__init__()
            self.started = asyncio.Event()
            self.cleanup_started = asyncio.Event()
            self.cleanup_finished = asyncio.Event()
            self.cancelled_again = False

        async def send_audio(self, audio, *, expected_session_epoch=None):
            self.started.set()
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                self.cleanup_started.set()
                try:
                    await release.wait()
                except asyncio.CancelledError:
                    self.cancelled_again = True
                    raise
                self.cleanup_finished.set()
                raise

    client = FinalizingClient()
    release = asyncio.Event()
    runner = _BotRunner(
        BotConfig(ClientConfig("ws://test"), shutdown_seconds=0.2), lambda _: None, client
    )
    audio = PcmAudio(bytes(640))
    runner.send_queue.put_nowait(("source", audio, 0))
    task = asyncio.create_task(runner.run())
    await asyncio.wait_for(client.started.wait(), 1)
    runner.request_stop()
    await asyncio.wait_for(client.cleanup_started.wait(), 1)
    runner.request_stop()
    await asyncio.sleep(0)
    assert not client.cancelled_again
    release.set()
    await asyncio.wait_for(task, 1)

    assert client.cleanup_finished.is_set()
    assert not client.cancelled_again


@pytest.mark.asyncio
async def test_runner_allows_real_client_to_send_burst_end_during_shutdown() -> None:
    class Encoder:
        def encode(self, _pcm):
            return b"encoded"

        def close(self):
            pass

    burst_id = "11111111-1111-4111-8111-111111111111"
    client = HeadlessClient(ClientConfig("ws://test"))
    first_audio_finished = asyncio.Event()
    producer_waiting = asyncio.Event()
    end_requested = asyncio.Event()
    allow_end_start = asyncio.Event()
    ending_started = asyncio.Event()
    allow_end_finish = asyncio.Event()
    ending_finished = asyncio.Event()
    ending_cancelled = asyncio.Event()
    stop_entered = asyncio.Event()
    closed = asyncio.Event()
    order = []
    end_calls = 0
    end_request_cancelled = asyncio.Event()

    class Transport:
        def abort(self):
            order.append(("abort", None))

    class Socket:
        def __init__(self):
            self.transport = Transport()

        async def send(self, message):
            if isinstance(message, bytes):
                media_burst_id, first, packets = decode_media(message, UPLINK)
                order.append(("audio", first))
                await client._handle_control(
                    {
                        "type": "uplink_ack",
                        "burst_id": media_burst_id,
                        "next_sequence": first + len(packets),
                    }
                )
                first_audio_finished.set()
                return

            control = json.loads(message)
            if control["type"] == "ptt_request":
                order.append(("ptt_request", control))
                await client._handle_control(
                    {
                        "type": "ptt_granted",
                        "request_id": control["request_id"],
                        "burst_id": burst_id,
                        "burst_index": 0,
                        "lease_remaining_ms": 60_000,
                    }
                )
            elif control["type"] == "burst_end":
                ending_started.set()
                try:
                    await allow_end_finish.wait()
                except asyncio.CancelledError:
                    ending_cancelled.set()
                    raise
                order.append(("burst_end", control))
                ending_finished.set()
            else:
                order.append((control["type"], control))

        async def close(self, code=1000):
            order.append(("close", code))
            closed.set()

    socket = Socket()
    original_send_json = client._send_json
    original_stop = client.stop

    async def hold_producer(_seconds):
        producer_waiting.set()
        await asyncio.Event().wait()

    async def start():
        client._attach_transport(socket)
        client._snapshot = {"audio_policy": {"recovery_horizon_ms": 1_000}}
        client._ready.set()

    async def send_json(message, **_kwargs):
        nonlocal end_calls
        if message["type"] == "burst_end":
            end_calls += 1
            end_requested.set()
            try:
                await allow_end_start.wait()
            except asyncio.CancelledError:
                end_request_cancelled.set()
                raise
        return await original_send_json(message, **_kwargs)

    async def stop():
        order.append(("client_stop", None))
        stop_entered.set()
        await original_stop()

    client.start = start
    client._sleep = hold_producer
    client._send_json = send_json
    client.stop = stop

    runner = _BotRunner(
        BotConfig(ClientConfig("ws://test"), shutdown_seconds=0.8), lambda _: None, client
    )
    audio = PcmAudio(bytes(10 * 640))
    runner.send_queue.put_nowait(("source", audio, 0))
    task = None
    try:
        with patch("zenptt_headless.client.OpusEncoder", Encoder):
            task = asyncio.create_task(runner.run())
            await asyncio.wait_for(first_audio_finished.wait(), 1)
            await asyncio.wait_for(producer_waiting.wait(), 1)
            assert runner.current_send is not None
            runner.request_stop()
            assert runner._shutdown_send is not None
            assert not runner._shutdown_send.done()
            await asyncio.wait_for(end_requested.wait(), 1)
            runner.request_stop()
            await asyncio.sleep(0)
            assert not end_request_cancelled.is_set()
            assert not ending_cancelled.is_set()
            assert not stop_entered.is_set()
            assert not closed.is_set()

            allow_end_start.set()
            await asyncio.wait_for(ending_started.wait(), 1)
            runner.request_stop()
            await asyncio.sleep(0)
            assert not end_request_cancelled.is_set()
            assert not ending_cancelled.is_set()
            assert not stop_entered.is_set()
            assert not closed.is_set()

            allow_end_finish.set()
            await asyncio.wait_for(ending_finished.wait(), 1)
            await asyncio.wait_for(task, 2)
    finally:
        allow_end_start.set()
        allow_end_finish.set()
        if task is not None and not task.done():
            task.cancel()
            with suppress(asyncio.CancelledError, TimeoutError):
                await asyncio.wait_for(task, 1)

    assert ending_finished.is_set()
    assert end_calls == 1
    assert not end_request_cancelled.is_set()
    assert not ending_cancelled.is_set()
    assert stop_entered.is_set()
    assert closed.is_set()
    kinds = [kind for kind, _ in order]
    assert kinds == [
        "ptt_request",
        "audio",
        "burst_end",
        "client_stop",
        "disconnect",
        "close",
    ]
    end = next(message for kind, message in order if kind == "burst_end")
    assert end == {"type": "burst_end", "burst_id": burst_id, "final_next_sequence": 1}
    writes_after_shutdown = len(order)
    await asyncio.sleep(0.05)
    assert len(order) == writes_after_shutdown


@pytest.mark.asyncio
async def test_runner_uses_saved_send_after_worker_clears_current_pointer() -> None:
    class Encoder:
        def encode(self, _pcm):
            return b"encoded"

        def close(self):
            pass

    supervisor_waiting = asyncio.Event()
    allow_supervisor = asyncio.Event()

    burst_id = "22222222-2222-4222-8222-222222222222"
    client = HeadlessClient(ClientConfig("ws://test"))
    first_audio = asyncio.Event()
    producer_waiting = asyncio.Event()
    end_started = asyncio.Event()
    allow_end = asyncio.Event()
    end_finished = asyncio.Event()
    stop_entered = asyncio.Event()
    order = []

    class Transport:
        def abort(self):
            order.append("abort")

    class Socket:
        def __init__(self):
            self.transport = Transport()

        async def send(self, message):
            if isinstance(message, bytes):
                media_burst_id, first, packets = decode_media(message, UPLINK)
                order.append("audio")
                await client._handle_control(
                    {
                        "type": "uplink_ack",
                        "burst_id": media_burst_id,
                        "next_sequence": first + len(packets),
                    }
                )
                first_audio.set()
                return
            control = json.loads(message)
            if control["type"] == "ptt_request":
                order.append("ptt_request")
                await client._handle_control(
                    {
                        "type": "ptt_granted",
                        "request_id": control["request_id"],
                        "burst_id": burst_id,
                        "burst_index": 0,
                        "lease_remaining_ms": 60_000,
                    }
                )
            elif control["type"] == "burst_end":
                end_started.set()
                await allow_end.wait()
                order.append("burst_end")
                end_finished.set()
            else:
                order.append(control["type"])

        async def close(self, code=1000):
            order.append("close")

    socket = Socket()

    async def start():
        client._attach_transport(socket)
        client._snapshot = {"audio_policy": {"recovery_horizon_ms": 1_000}}
        client._ready.set()

    async def hold_producer(_seconds):
        producer_waiting.set()
        await asyncio.Event().wait()

    original_stop = client.stop

    async def stop():
        order.append("client_stop")
        stop_entered.set()
        await original_stop()

    client.start = start
    client._sleep = hold_producer
    client.stop = stop
    runner = _BotRunner(
        BotConfig(ClientConfig("ws://test"), shutdown_seconds=0.8), lambda _: None, client
    )
    audio = PcmAudio(bytes(10 * 640))
    runner.send_queue.put_nowait(("source", audio, 0))
    task = None
    original_wait = asyncio.wait

    async def gate_supervisor_wait(tasks, *args, **kwargs):
        result = await original_wait(tasks, *args, **kwargs)
        if any(
            getattr(candidate.get_coro(), "__qualname__", "").endswith("_BotRunner._send")
            for candidate in tasks
        ):
            supervisor_waiting.set()
            await allow_supervisor.wait()
        return result

    try:
        with (
            patch("zenptt_headless.client.OpusEncoder", Encoder),
            patch("zenptt_headless.bot.asyncio.wait", new=gate_supervisor_wait),
        ):
            task = asyncio.create_task(runner.run())
            await asyncio.wait_for(first_audio.wait(), 1)
            await asyncio.wait_for(producer_waiting.wait(), 1)
            send_worker = next(
                candidate
                for candidate in asyncio.all_tasks()
                if getattr(candidate.get_coro(), "__qualname__", "").endswith(
                    "_BotRunner._send"
                )
            )
            runner.request_stop()
            await asyncio.wait_for(supervisor_waiting.wait(), 1)
            await asyncio.wait_for(end_started.wait(), 1)
            send_worker.cancel()
            with suppress(asyncio.CancelledError):
                await send_worker

            async def wait_for_pointer_clear():
                while runner.current_send is not None:
                    await asyncio.sleep(0)

            await asyncio.wait_for(wait_for_pointer_clear(), 1)
            saved_send = runner._shutdown_send
            assert saved_send is not None and not saved_send.done()
            assert not stop_entered.is_set()

            allow_supervisor.set()
            await asyncio.sleep(0)
            assert not stop_entered.is_set()
            allow_end.set()
            await asyncio.wait_for(end_finished.wait(), 1)
            await asyncio.wait_for(task, 2)
    finally:
        allow_supervisor.set()
        allow_end.set()
        if task is not None and not task.done():
            task.cancel()
            with suppress(asyncio.CancelledError, TimeoutError):
                await asyncio.wait_for(task, 1)

    assert order == [
        "ptt_request",
        "audio",
        "burst_end",
        "client_stop",
        "disconnect",
        "close",
    ]


@pytest.mark.asyncio
@pytest.mark.parametrize("response", [None, PcmAudio(bytes(640))])
async def test_runner_shutdown_is_not_swallowed_by_a_handler(
    response: PcmAudio | None,
) -> None:
    client = FakeClient()
    started = asyncio.Event()

    async def suppresses_cancellation(_: ReceivedBurst):
        started.set()
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            return response

    runner = _BotRunner(
        BotConfig(ClientConfig("ws://test"), shutdown_seconds=0.1),
        suppresses_cancellation,
        client,
    )
    task = asyncio.create_task(runner.run())
    await client.events.put(burst())
    await asyncio.wait_for(started.wait(), 1)
    runner.stop_event.set()
    await asyncio.wait_for(task, 0.5)
    assert runner.send_queue.empty()


@pytest.mark.asyncio
async def test_runner_reports_a_handler_that_never_finishes_after_cancellation() -> None:
    client = FakeClient()
    started = asyncio.Event()
    release = asyncio.Event()

    async def refuses_first_cancellation(_: ReceivedBurst):
        started.set()
        while not release.is_set():
            try:
                await release.wait()
            except asyncio.CancelledError:
                continue

    runner = _BotRunner(
        BotConfig(ClientConfig("ws://test"), shutdown_seconds=0.03),
        refuses_first_cancellation,
        client,
    )
    task = asyncio.create_task(runner.run())
    await client.events.put(burst())
    await asyncio.wait_for(started.wait(), 1)
    runner.stop_event.set()
    with pytest.raises(TimeoutError, match="shutdown exceeded"):
        await asyncio.wait_for(task, 0.5)
    assert runner._shutdown_children
    assert any(not child.done() for child in runner._shutdown_children)
    release.set()
    await asyncio.sleep(0)


def test_process_watchdog_timer_is_started_once_and_can_be_cancelled(monkeypatch) -> None:
    timers = []
    exits = []

    class Timer:
        def __init__(self, seconds, callback, args):
            self.seconds = seconds
            self.callback = callback
            self.args = args
            self.daemon = False
            self.started = 0
            self.cancelled = 0
            timers.append(self)

        def start(self):
            self.started += 1

        def cancel(self):
            self.cancelled += 1

    monkeypatch.setattr("zenptt_headless.standalone.threading.Timer", Timer)
    monkeypatch.setattr("zenptt_headless.standalone.os._exit", exits.append)
    watchdog = ShutdownWatchdog()

    watchdog.start()
    watchdog.start()

    assert watchdog.started
    assert len(timers) == 1
    assert timers[0].seconds == 5.0
    assert timers[0].daemon
    assert timers[0].started == 1
    timers[0].callback(*timers[0].args)
    assert exits == [1]

    watchdog.cancel()
    assert timers[0].cancelled == 1
    watchdog.start()
    assert watchdog.started
    assert len(timers) == 1


def test_watchdog_signal_reentry_during_timer_creation_does_not_rearm(monkeypatch):
    timers = []
    watchdog = ShutdownWatchdog()

    class Timer:
        def __init__(self, seconds, callback, args):
            timers.append(self)
            watchdog.start()

        def start(self):
            watchdog.start()

    monkeypatch.setattr("zenptt_headless.standalone.threading.Timer", Timer)
    watchdog.start()
    assert watchdog.started
    assert len(timers) == 1


@pytest.mark.parametrize("field,value", [
    ("receive_queue_size", 5), ("send_queue_size", 33), ("shutdown_seconds", 5.01),
    ("receive_queue_size", 0), ("send_queue_size", 0), ("shutdown_seconds", 0),
    ("receive_queue_size", 1.5), ("send_queue_size", True),
    ("shutdown_seconds", float("inf")), ("shutdown_seconds", float("nan")),
])
def test_bot_config_rejects_limits_outside_the_contract(field, value):
    with pytest.raises(ValueError):
        BotConfig(ClientConfig("ws://test"), **{field: value})


def test_bot_config_accepts_smaller_and_maximum_limits():
    small = BotConfig(ClientConfig("ws://test"), receive_queue_size=1,
                      send_queue_size=1, shutdown_seconds=0.05)
    assert small.shutdown_seconds == 0.05
    maximum = BotConfig(ClientConfig("ws://test"))
    assert (maximum.receive_queue_size, maximum.send_queue_size, maximum.shutdown_seconds) == (4, 32, 5)


@pytest.mark.asyncio
async def test_real_client_owns_correlated_busy_retries_at_configured_interval():
    client = HeadlessClient(ClientConfig("ws://test"))
    requests = []
    delays = []

    class Encoder:
        def close(self):
            pass

    class Socket:
        async def send(self, raw):
            message = json.loads(raw)
            requests.append(message["request_id"])
            await client._handle_control({
                "type": "ptt_denied", "request_id": message["request_id"],
                "reason": "channel_busy" if len(requests) < 3 else "server_busy",
            })

    async def sleep(seconds):
        delays.append(seconds)
        await asyncio.sleep(0)

    client._sleep = sleep
    client._attach_transport(Socket())
    client._ready.set()
    runner = _BotRunner(BotConfig(client.config, busy_retry_seconds=2), lambda _: None, client)
    with patch("zenptt_headless.client.OpusEncoder", Encoder):
        await runner._send_response("source", PcmAudio(bytes(640)), 0)
    assert len(requests) == 3
    assert len(set(requests)) == 1
    assert delays == [2, 2]


@pytest.mark.asyncio
async def test_handler_failure_is_isolated_and_responses_remain_ordered(caplog):
    client = FakeClient()
    calls = []

    async def handler(received):
        calls.append(received.burst_index)
        if received.burst_index == 0:
            raise ValueError("test failure")
        if received.burst_index == 2:
            return None
        return PcmAudio(bytes([received.burst_index]) * 640)

    runner = _BotRunner(BotConfig(ClientConfig("ws://test")), handler, client)
    for index in range(4):
        await runner._handle_burst(burst(index))
    assert calls == [0, 1, 2, 3]
    assert [runner.send_queue.get_nowait()[0] for _ in range(2)] == ["1", "3"]
    assert "handler_failed burst=0" in caplog.text


@pytest.mark.asyncio
async def test_response_queue_rejects_the_33rd_item_without_copying_shared_audio(caplog):
    client = FakeClient()
    audio = PcmAudio(bytes(640))

    async def handler(_):
        return audio

    runner = _BotRunner(BotConfig(ClientConfig("ws://test")), handler, client)
    for index in range(33):
        await runner._handle_burst(burst(index))
    assert runner.send_queue.qsize() == 32
    assert runner._pcm_budget.used_bytes == 640
    assert "send_queue_full burst=32" in caplog.text
    assert all(item[1] is audio for item in runner.send_queue._queue)
    runner._clear_queues()
    assert runner.send_queue.empty()
