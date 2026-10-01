"""Owns one WebSocket transport, ordered byte-bounded output, and traffic limits."""

from __future__ import annotations

import asyncio
from collections import deque
from collections.abc import Callable
from contextlib import suppress
from dataclasses import dataclass, field
import json
import logging
import time
from typing import Any
import uuid

from fastapi import WebSocket, WebSocketDisconnect

from .domain import Channel, LogicalMember
from .media_timing import OutboundMediaContext, ServerMediaTimingDiagnostics
from .security_constants import (
    MAX_AUDIO_MESSAGES_PER_SECOND,
    MAX_CONTROL_MESSAGES_PER_SECOND,
)

logger = logging.getLogger("zenptt.server")

OutboundMessage = tuple[str, dict[str, Any] | bytes, int, OutboundMediaContext | None]
MAX_LISTENER_OUTBOUND_BYTES = 16 * 1024
OUTBOUND_SEND_TIMEOUT_SECONDS = 10


@dataclass
class MessageRateLimiter:
    control_limit: int = MAX_CONTROL_MESSAGES_PER_SECOND
    audio_message_limit: int = MAX_AUDIO_MESSAGES_PER_SECOND
    now: Callable[[], float] = time.monotonic
    window_started: float = field(init=False)
    control_count: int = 0
    audio_message_count: int = 0

    def __post_init__(self) -> None:
        self.window_started = self.now()

    def allow_control(self) -> bool:
        return self._allow("control")

    def allow_audio(self) -> bool:
        return self._allow("audio_message")

    def _allow(self, kind: str) -> bool:
        self._reset_window()
        attribute = f"{kind}_count"
        value = getattr(self, attribute) + 1
        setattr(self, attribute, value)
        return value <= getattr(self, f"{kind}_limit")

    def _reset_window(self) -> None:
        current = self.now()
        if current - self.window_started >= 1:
            self.window_started = current
            self.control_count = 0
            self.audio_message_count = 0


@dataclass
class ClientSession:
    websocket: WebSocket
    max_outbound_bytes: int = MAX_LISTENER_OUTBOUND_BYTES
    client_key: str = "unknown"
    rate_limiter: MessageRateLimiter = field(default_factory=MessageRateLimiter)
    session_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    now_ms: Callable[[], int] = field(default=lambda: int(time.monotonic() * 1000))
    channel: Channel | None = None
    member: LogicalMember | None = None
    resume_token: str | None = None
    outbound_task: asyncio.Task[None] | None = None
    audio_frames_received: int = 0
    closing: bool = False
    intentional_close: bool = False
    authoritative: bool = True
    last_activity_ms: int = 0
    presence_active: bool = True

    listen_burst_index: int | None = None
    listen_next_sequence: int = 0
    announced_bursts: set[str] = field(default_factory=set)
    downlink_next_sequences: dict[str, int] = field(default_factory=dict)
    released_bursts: set[str] = field(default_factory=set)
    sealed_bursts: set[str] = field(default_factory=set)

    outbound_queue: deque[OutboundMessage] = field(init=False, default_factory=deque)
    queued_bytes: int = 0
    outbound_ready: asyncio.Event = field(init=False, default_factory=asyncio.Event)
    close_task: asyncio.Task[None] | None = None
    outbound_capacity_callback: Callable[[], None] | None = None
    downlink_task: asyncio.Task[None] | None = None
    media_timing: ServerMediaTimingDiagnostics = field(init=False)

    def __post_init__(self) -> None:
        self.media_timing = ServerMediaTimingDiagnostics(self.session_id)

    def offer_json(self, message: dict[str, Any]) -> bool:
        encoded_size = len(json.dumps(message, separators=(",", ":")).encode("utf-8"))
        return self._offer("json", message, encoded_size)

    def offer_bytes(self, message: bytes) -> bool:
        return self._offer("bytes", message, len(message), media_context=None)

    def offer_resumable_json(self, message: dict[str, Any]) -> bool:
        encoded_size = len(json.dumps(message, separators=(",", ":")).encode("utf-8"))
        return self._offer("json", message, encoded_size, close_on_limit=False)

    def offer_resumable_bytes(
        self,
        message: bytes,
        media_context: OutboundMediaContext | None = None,
    ) -> bool:
        return self._offer(
            "bytes",
            message,
            len(message),
            close_on_limit=False,
            media_context=media_context,
        )

    def _offer(
        self,
        kind: str,
        message: dict[str, Any] | bytes,
        size: int,
        *,
        close_on_limit: bool = True,
        media_context: OutboundMediaContext | None = None,
    ) -> bool:
        if self.closing:
            return False
        if size > self.max_outbound_bytes or self.queued_bytes + size > self.max_outbound_bytes:
            logger.warning(
                "outbound_backlog_limit session=%s queued_bytes=%d offered_bytes=%d",
                self.session_id,
                self.queued_bytes,
                size,
            )
            if close_on_limit:
                self._schedule_close(1008)
            return False
        self.outbound_queue.append((kind, message, size, media_context))
        self.queued_bytes += size
        self.outbound_ready.set()
        return True

    def start_outbound(self) -> None:
        self.outbound_task = asyncio.create_task(self.run_outbound())

    async def close(self, code: int) -> None:
        self.closing = True
        with suppress(Exception):
            await self.websocket.close(code=code)

    async def stop_outbound(self) -> None:
        self.outbound_capacity_callback = None
        downlink_task = self.downlink_task
        self.downlink_task = None
        if downlink_task is not None and downlink_task is not asyncio.current_task():
            downlink_task.cancel()
            with suppress(asyncio.CancelledError):
                await downlink_task
        task = self.outbound_task
        self.outbound_task = None
        if task is not None and task is not asyncio.current_task():
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task
        self.outbound_queue.clear()
        self.queued_bytes = 0
        self.outbound_ready.clear()
        if self.close_task is not None and self.close_task is not asyncio.current_task():
            self.close_task.cancel()
        self.close_task = None

    async def run_outbound(self) -> None:
        try:
            while True:
                await self.outbound_ready.wait()
                while self.outbound_queue:
                    kind, message, size, media_context = self.outbound_queue.popleft()
                    if kind == "json":
                        assert isinstance(message, dict)
                        send = self.websocket.send_json(message)
                    else:
                        assert isinstance(message, bytes)
                        send = self.websocket.send_bytes(message)
                    send_started_at_ms = self.now_ms()
                    send_task = asyncio.create_task(send)
                    done, _ = await asyncio.wait(
                        {send_task},
                        timeout=OUTBOUND_SEND_TIMEOUT_SECONDS,
                    )
                    if not done:
                        send_task.cancel()
                        with suppress(asyncio.CancelledError):
                            await send_task
                        raise TimeoutError
                    await send_task
                    self.queued_bytes = max(0, self.queued_bytes - size)
                    if media_context is not None:
                        self.media_timing.downlink_sent(
                            media_context,
                            send_started_at_ms,
                            self.now_ms(),
                            self.queued_bytes,
                        )
                    callback = self.outbound_capacity_callback
                    if callback is not None:
                        callback()
                self.outbound_ready.clear()
                if self.outbound_queue:
                    self.outbound_ready.set()
        except asyncio.CancelledError:
            raise
        except WebSocketDisconnect:
            logger.info("outbound_disconnected session=%s", self.session_id)
        except TimeoutError:
            logger.warning("outbound_send_timeout session=%s", self.session_id)
            self._schedule_close(1001)
        except Exception:
            if self.closing:
                logger.info("outbound_closed session=%s", self.session_id)
            else:
                logger.exception("outbound_error session=%s", self.session_id)
                self._schedule_close(1001)

    def _schedule_close(self, code: int) -> None:
        if self.close_task is not None or self.closing:
            return
        self.closing = True
        self.close_task = asyncio.create_task(self.close(code))
