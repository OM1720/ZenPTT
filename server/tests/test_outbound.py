import asyncio
import logging
from typing import Any

import pytest

import app.client_session as client_session_module
from app.client_session import ClientSession
from app.media_timing import OutboundMediaContext, ServerMediaTimingDiagnostics


class FakeWebSocket:
    def __init__(self, block: bool = False) -> None:
        self.block = block
        self.release = asyncio.Event()
        self.sent = asyncio.Event()
        self.messages: list[tuple[str, Any]] = []
        self.close_code: int | None = None

    async def send_json(self, message: dict[str, Any]) -> None:
        self.messages.append(("json", message))
        self.sent.set()

    async def send_bytes(self, message: bytes) -> None:
        if self.block:
            await self.release.wait()
        self.messages.append(("bytes", message))
        self.sent.set()

    async def close(self, code: int) -> None:
        self.close_code = code


@pytest.mark.asyncio
async def test_outbound_preserves_enqueue_order() -> None:
    socket = FakeWebSocket()
    session = ClientSession(socket, max_outbound_bytes=100)
    assert session.offer_bytes(b"one")
    assert session.offer_json({"type": "state"})
    assert session.offer_bytes(b"two")
    session.start_outbound()
    try:
        for _ in range(100):
            if len(socket.messages) == 3 and session.queued_bytes == 0:
                break
            await asyncio.sleep(0.01)
        assert socket.messages == [
            ("bytes", b"one"),
            ("json", {"type": "state"}),
            ("bytes", b"two"),
        ]
        assert session.queued_bytes == 0
    finally:
        await session.stop_outbound()


@pytest.mark.asyncio
async def test_limit_uses_actual_serialized_bytes() -> None:
    socket = FakeWebSocket()
    session = ClientSession(socket, max_outbound_bytes=12)
    assert session.offer_bytes(b"123456")
    assert session.offer_bytes(b"123456")
    assert not session.offer_bytes(b"x")
    await asyncio.sleep(0)
    assert session.queued_bytes == 12
    assert socket.close_code == 1008


@pytest.mark.asyncio
async def test_slow_listener_does_not_block_another_socket() -> None:
    slow_socket = FakeWebSocket(block=True)
    fast_socket = FakeWebSocket()
    slow = ClientSession(slow_socket)
    fast = ClientSession(fast_socket)
    slow.start_outbound()
    fast.start_outbound()
    try:
        slow.offer_bytes(b"audio")
        fast.offer_bytes(b"audio")
        await asyncio.wait_for(fast_socket.sent.wait(), 1)
        assert fast_socket.messages == [("bytes", b"audio")]
        assert slow_socket.messages == []
    finally:
        slow_socket.release.set()
        await slow.stop_outbound()
        await fast.stop_outbound()


@pytest.mark.asyncio
async def test_in_flight_bytes_remain_inside_the_socket_budget() -> None:
    socket = FakeWebSocket(block=True)
    session = ClientSession(socket, max_outbound_bytes=5)
    session.start_outbound()
    try:
        assert session.offer_bytes(b"12345")
        await asyncio.sleep(0)
        assert session.queued_bytes == 5
        assert not session.offer_bytes(b"x")
        await asyncio.sleep(0)
        assert socket.close_code == 1008
    finally:
        socket.release.set()
        await session.stop_outbound()


def test_resumable_backpressure_is_one_completed_interval(caplog) -> None:
    caplog.set_level(logging.INFO, logger="zenptt.server")
    clock = [100]
    session = ClientSession(FakeWebSocket(), max_outbound_bytes=5, now_ms=lambda: clock[0])
    assert session.offer_resumable_bytes(b"12345")
    assert not session.offer_resumable_bytes(b"x")
    clock[0] = 120
    assert not session.offer_resumable_bytes(b"x")
    session.outbound_queue.clear()
    session.queued_bytes = 0
    clock[0] = 150
    assert session.offer_resumable_bytes(b"x")
    assert "outbound_backpressure" in caplog.text
    assert "duration_ms=50 blocked_offers=2 peak_queued_bytes=5 recovered=true" in caplog.text
    assert "outbound_backlog_limit" not in caplog.text


@pytest.mark.asyncio
async def test_blocked_send_closes_socket_for_resume(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(client_session_module, "OUTBOUND_SEND_TIMEOUT_SECONDS", 0.01)
    socket = FakeWebSocket(block=True)
    session = ClientSession(socket)
    events: list[str] = []
    session.media_timing = ServerMediaTimingDiagnostics(session.session_id, emit=events.append)
    session.start_outbound()
    try:
        context = OutboundMediaContext("burst-111111", 0, 1, 0, 0, 0)
        assert session.offer_resumable_bytes(b"audio", context)
        while socket.close_code is None:
            await asyncio.sleep(0)

        assert socket.close_code == 1001
        assert session.closing
        assert session.queued_bytes == len(b"audio")
        assert events == []
    finally:
        socket.release.set()
        await session.stop_outbound()


@pytest.mark.asyncio
async def test_completed_slow_send_records_queue_and_send_delay() -> None:
    clock = [0]
    socket = FakeWebSocket(block=True)
    session = ClientSession(socket, now_ms=lambda: clock[0])
    events: list[str] = []
    session.media_timing = ServerMediaTimingDiagnostics(session.session_id, emit=events.append)
    context = OutboundMediaContext("burst-111111", 7, 1, 0, 0, 0)
    session.start_outbound()
    try:
        assert session.offer_resumable_bytes(b"audio", context)
        await asyncio.sleep(0)
        clock[0] = 250
        socket.release.set()
        await asyncio.wait_for(socket.sent.wait(), 1)
        while session.queued_bytes:
            await asyncio.sleep(0)

        assert events == [
            "media_timing type=server_send_delay "
            f"session={session.session_id[-6:]} burst=111111 first=7 frames=1 "
            "queue_wait=0ms send_time=250ms queue=0B",
        ]
    finally:
        await session.stop_outbound()
