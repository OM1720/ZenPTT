"""Reusable asynchronous ZenPTT v4 headless client."""

from __future__ import annotations

import asyncio
from collections.abc import Callable
from contextlib import suppress
from dataclasses import dataclass, field, replace
import json
import logging
import time
import uuid

from websockets.asyncio.client import ClientConnection, connect

from .assembler import BurstAssembler
from .codec import OpusEncoder
from .memory import PcmBudget, PcmBudgetExceeded
from .protocol import DOWNLINK, MAX_MESSAGE_BYTES, SUBPROTOCOL, UPLINK, decode_media, encode_media, parse_control
from .types import FRAME_BYTES, FRAME_DURATION_MS, MAX_FRAMES, PcmAudio, ReceiveInterrupted, ReceivedBurst, SendResult

logger = logging.getLogger("zenptt.headless")
FRAME_SECONDS = FRAME_DURATION_MS / 1_000
WRITE_TIMEOUT_SECONDS = 1.0
PTT_REQUEST_TIMEOUT_SECONDS = 5.0


@dataclass(frozen=True)
class ClientConfig:
    server_url: str
    channel: str = "Test"
    event_queue_size: int = 8

    def __post_init__(self) -> None:
        if not self.server_url.startswith(("ws://", "wss://")):
            raise ValueError("Server URL must use ws:// or wss://")
        if not self.channel:
            raise ValueError("Channel must not be empty")
        if self.event_queue_size <= 0:
            raise ValueError("Event queue size must be positive")


@dataclass(frozen=True)
class _TransportContext:
    socket: ClientConnection
    generation: int
    epoch: int
    write_lock: asyncio.Lock = field(default_factory=asyncio.Lock)


@dataclass
class _Outgoing:
    request_id: str
    epoch: int
    request_started: bool = False
    grant_deadline: float | None = None
    burst_id: str | None = None
    phase: str = "waiting_grant"
    ack_next: int = 0
    next_sequence: int = 0
    final_next_sequence: int | None = None
    finalization_deadline: float | None = None
    started_at: float | None = None
    disconnected_at: float | None = None
    stop_reason: str | None = None
    terminal: dict | None = None
    terminal_at: float | None = None
    active: bool = True
    retained: dict[int, bytes] = field(default_factory=dict)
    sent_transport: int = -1
    sent_sequences: set[int] = field(default_factory=set)
    changed: asyncio.Event = field(default_factory=asyncio.Event)


class HeadlessClient:
    """Own one resumable session, ordered receiver, and serialized sender."""

    def __init__(self, config: ClientConfig, *, echo_ticket: str | None = None) -> None:
        self.config = config
        if echo_ticket is not None and not 1 <= len(echo_ticket) <= 256:
            raise ValueError("Invalid Echo ticket")
        self._echo_ticket = echo_ticket
        self._events: asyncio.Queue[ReceivedBurst | ReceiveInterrupted] = asyncio.Queue(
            config.event_queue_size
        )
        self._ready = asyncio.Event()
        self._stopping = asyncio.Event()
        self._terminal_reason: str | None = None
        self._busy_retry_seconds = 1.0
        self._audio_lock = asyncio.Lock()
        self._connection_task: asyncio.Task[None] | None = None
        self._transport: _TransportContext | None = None
        self._pcm_budget = PcmBudget()
        self._assembler = BurstAssembler(pcm_budget=self._pcm_budget)
        self._resume_token: str | None = None
        self._generation = 0
        self._transport_generation = 0
        self._incarnation: str | None = None
        self._listen_cursor = (0, 0)
        self._delivered_next_index = 0
        self._ignored_burst_id: str | None = None
        self._stale_burst_id: str | None = None
        self._last_listen_reset: tuple[int, int] | None = None
        self._logical_epoch = 0
        self._snapshot: dict | None = None
        self._control_waiters: dict[str, asyncio.Future[dict]] = {}
        self._outgoing: _Outgoing | None = None
        self._ping_id = 0
        self._now = time.monotonic
        self._sleep = asyncio.sleep
        self._last_pong = self._now()
        self._send_finalizers: set[asyncio.Task[SendResult]] = set()

    @property
    def session_epoch(self) -> int:
        return self._logical_epoch

    async def start(self) -> None:
        if self._terminal_reason is not None:
            raise RuntimeError(f"{self._terminal_reason}: manual reconnect requires a new client")
        if self._connection_task is None:
            self._connection_task = asyncio.create_task(self._connection_loop())
        await asyncio.wait_for(self._ready.wait(), 15)

    async def stop(self) -> None:
        self._stopping.set()
        outgoing = self._outgoing
        if outgoing is not None:
            outgoing.active = False
            outgoing.changed.set()
        context = self._transport
        if context is not None:
            with suppress(Exception):
                await self._write_json(
                    {"type": "disconnect"}, context=context, require_ready=False
                )
            self._detach_transport(context)
            await self._close_socket(context.socket)
        if self._connection_task is not None:
            self._connection_task.cancel()
            with suppress(asyncio.CancelledError):
                await self._connection_task
        self._connection_task = None
        self._ready.clear()
        self._assembler.reset_session()
        while not self._events.empty():
            self._events.get_nowait()

    async def receive(self) -> ReceivedBurst | ReceiveInterrupted:
        while True:
            event = await self._events.get()
            if event.session_epoch == self._logical_epoch:
                return event
            del event

    async def send_audio(
        self, audio: PcmAudio, *, expected_session_epoch: int | None = None
    ) -> SendResult:
        if self._terminal_reason is not None:
            return SendResult("rejected", self._terminal_reason)
        epoch = self._logical_epoch if expected_session_epoch is None else expected_session_epoch
        if epoch != self._logical_epoch:
            return SendResult("interrupted", "session_lost")
        if not audio.data:
            return SendResult("rejected", "empty_audio")
        if not self._pcm_budget.admit(audio):
            return SendResult("rejected", "pcm_budget_exceeded")
        async with self._audio_lock:
            if epoch != self._logical_epoch:
                return SendResult("interrupted", "session_lost")
            return await self._send_audio_locked(audio, epoch)

    async def _send_audio_locked(self, audio: PcmAudio, epoch: int) -> SendResult:
        frame_count = (len(audio.data) + FRAME_BYTES - 1) // FRAME_BYTES
        if frame_count > MAX_FRAMES:
            return SendResult("rejected", "duration_limit")

        outgoing = _Outgoing(str(uuid.uuid4()), epoch)
        self._outgoing = outgoing
        producer: asyncio.Task[None] | None = None
        encoder: OpusEncoder | None = None
        try:
            try:
                encoder = OpusEncoder()
            except Exception as error:
                logger.error("encoder_failed error=%s", type(error).__name__)
                return SendResult("rejected", "encoding_error")

            grant = await self._request_floor(outgoing)
            while grant["type"] == "ptt_denied" and grant["reason"] == "channel_busy":
                await self._sleep(self._busy_retry_seconds)
                if not self._outgoing_current(outgoing):
                    return SendResult("interrupted", "session_lost")
                grant = await self._request_floor(outgoing)
            if grant["type"] == "ptt_denied":
                reason = grant["reason"]
                status = "denied" if self._outgoing_current(outgoing) else "interrupted"
                return SendResult(status, reason)

            self._accept_grant(outgoing, grant)
            if outgoing.phase == "transmitting":
                producer = asyncio.create_task(
                    self._produce_audio(audio, frame_count, outgoing, encoder)
                )
            return await self._finish_outgoing(outgoing, frame_count)
        except asyncio.CancelledError:
            if producer is not None and not producer.done():
                producer.cancel()
                with suppress(asyncio.CancelledError):
                    await producer
            producer = None
            if outgoing.burst_id is not None and self._outgoing_current(outgoing):
                self._freeze_outgoing(outgoing)
                await self._finish_cancelled_outgoing(outgoing, frame_count)
            elif outgoing.request_started and self._outgoing_current(outgoing):
                await self._abandon_session("ptt_cancelled")
            raise
        finally:
            if producer is not None and not producer.done():
                producer.cancel()
            if producer is not None:
                with suppress(asyncio.CancelledError):
                    await producer
            if encoder is not None:
                encoder.close()
            outgoing.active = False
            if self._outgoing is outgoing:
                self._outgoing = None

    async def _finish_cancelled_outgoing(
        self, outgoing: _Outgoing, requested_frames: int
    ) -> None:
        finalizer = asyncio.create_task(
            self._finish_outgoing(outgoing, requested_frames)
        )
        self._send_finalizers.add(finalizer)
        finalizer.add_done_callback(self._send_finalizers.discard)
        while not finalizer.done():
            try:
                await asyncio.shield(finalizer)
            except asyncio.CancelledError:
                continue
            except Exception as error:
                logger.error("send_finalization_failed error=%s", type(error).__name__)
                await self._abandon_session("send_finalization_failed")
                return
        with suppress(asyncio.CancelledError, Exception):
            finalizer.result()

    async def _produce_audio(
        self,
        audio: PcmAudio,
        frame_count: int,
        outgoing: _Outgoing,
        encoder: OpusEncoder,
    ) -> None:
        try:
            for sequence in range(frame_count):
                start = outgoing.started_at if outgoing.started_at is not None else self._now()
                target = start + sequence * FRAME_SECONDS
                delay = target - self._now()
                if delay > 0:
                    await self._sleep(delay)
                if not self._outgoing_current(outgoing) or outgoing.phase != "transmitting":
                    break
                if self._disconnect_expired(outgoing):
                    outgoing.stop_reason = outgoing.stop_reason or "recovery_timeout"
                    break
                offset = sequence * FRAME_BYTES
                frame = audio.data[offset : offset + FRAME_BYTES]
                if len(frame) < FRAME_BYTES:
                    frame += bytes(FRAME_BYTES - len(frame))
                outgoing.retained[sequence] = encoder.encode(frame)
                outgoing.next_sequence = sequence + 1
                self._prune_retained(outgoing)
                outgoing.changed.set()
        except Exception as error:
            logger.error("encoder_failed error=%s", type(error).__name__)
            outgoing.stop_reason = outgoing.stop_reason or "encoding_error"
        finally:
            if self._outgoing_current(outgoing):
                self._freeze_outgoing(outgoing)

    def _freeze_outgoing(self, outgoing: _Outgoing, final: int | None = None) -> None:
        if outgoing.final_next_sequence is None:
            outgoing.final_next_sequence = outgoing.next_sequence if final is None else final
            for sequence in tuple(outgoing.retained):
                if sequence >= outgoing.final_next_sequence:
                    outgoing.retained.pop(sequence, None)
                    outgoing.sent_sequences.discard(sequence)
            outgoing.finalization_deadline = (
                self._now() + self._recovery_horizon_seconds() + 5
            )
        if outgoing.phase != "sealed":
            outgoing.phase = "finalizing"
        outgoing.changed.set()

    async def _finish_outgoing(self, outgoing: _Outgoing, requested_frames: int) -> SendResult:
        next_end_write = 0.0
        while self._outgoing_current(outgoing):
            deadline = outgoing.finalization_deadline
            terminal = outgoing.terminal
            if (
                terminal is not None
                and terminal["state"] == "sealed"
                and (
                    deadline is None
                    or outgoing.terminal_at is None
                    or outgoing.terminal_at <= deadline
                )
            ):
                return self._terminal_result(outgoing, requested_frames, terminal)
            if deadline is not None and self._now() >= deadline:
                await self._abandon_session("recovery_timeout")
                return SendResult("interrupted", "recovery_timeout", outgoing.burst_id)

            outgoing.changed.clear()
            self._prune_retained(outgoing)
            await self._upload_retained(outgoing)
            if not self._outgoing_current(outgoing):
                break

            now = self._now()
            if (
                outgoing.final_next_sequence is not None
                and now >= next_end_write
                and self._ready.is_set()
            ):
                await self._send_json(
                    {
                        "type": "burst_end",
                        "burst_id": outgoing.burst_id,
                        "final_next_sequence": outgoing.final_next_sequence,
                    },
                    epoch=outgoing.epoch,
                    deadline=outgoing.finalization_deadline,
                )
                next_end_write = now + 1

            wait_seconds = 0.05
            if deadline is not None:
                wait_seconds = max(0, min(wait_seconds, deadline - self._now()))
            change_waiter = asyncio.create_task(outgoing.changed.wait())
            try:
                await asyncio.wait({change_waiter}, timeout=wait_seconds)
            finally:
                if not change_waiter.done():
                    change_waiter.cancel()
                with suppress(asyncio.CancelledError):
                    await change_waiter

        return SendResult("interrupted", outgoing.stop_reason or "session_lost", outgoing.burst_id)

    def _terminal_result(
        self, outgoing: _Outgoing, requested_frames: int, terminal: dict
    ) -> SendResult:
        local_final = outgoing.final_next_sequence
        if terminal["final_next_sequence"] != local_final:
            return SendResult("interrupted", "final_sequence_mismatch", outgoing.burst_id)
        if outgoing.stop_reason is not None:
            return SendResult("interrupted", outgoing.stop_reason, outgoing.burst_id)
        complete = (
            terminal["reason"] == "complete"
            and local_final == requested_frames
            and outgoing.next_sequence == requested_frames
        )
        if complete:
            return SendResult("sent", "complete", outgoing.burst_id)
        return SendResult("interrupted", terminal["reason"], outgoing.burst_id)

    async def _upload_retained(self, outgoing: _Outgoing) -> None:
        if not self._ready.is_set() or not self._outgoing_current(outgoing):
            return
        context = self._transport
        if context is None:
            return
        transport = context.generation
        if outgoing.sent_transport != transport:
            outgoing.sent_transport = transport
            outgoing.sent_sequences.clear()
        for sequence in tuple(outgoing.retained):
            deadline = outgoing.finalization_deadline
            if deadline is not None and self._now() >= deadline:
                return
            packet = outgoing.retained.get(sequence)
            if packet is None:
                continue
            if (
                outgoing.final_next_sequence is not None
                and sequence >= outgoing.final_next_sequence
            ):
                continue
            if sequence < outgoing.ack_next or sequence in outgoing.sent_sequences:
                continue
            sent = await self._send_bytes(
                encode_media(UPLINK, outgoing.burst_id, sequence, (packet,)),
                epoch=outgoing.epoch,
                transport=transport,
                deadline=deadline,
                audio=(outgoing, sequence, packet),
            )
            if not sent or not self._outgoing_current(outgoing):
                return
            deadline = outgoing.finalization_deadline
            if deadline is not None and self._now() >= deadline:
                return
            if outgoing.retained.get(sequence) == packet:
                outgoing.sent_sequences.add(sequence)

    def _accept_grant(self, outgoing: _Outgoing, message: dict) -> bool:
        burst_id = message["burst_id"]
        if outgoing.burst_id is not None:
            return outgoing.burst_id == burst_id
        outgoing.burst_id = burst_id
        outgoing.started_at = self._now()
        outgoing.disconnected_at = None
        if outgoing.phase == "waiting_grant":
            outgoing.phase = "transmitting"
        outgoing.changed.set()
        return True

    def _prune_retained(self, outgoing: _Outgoing) -> None:
        if outgoing.started_at is None:
            return
        oldest_time = self._now() - self._recovery_horizon_seconds()
        expired = [
            sequence
            for sequence in outgoing.retained
            if outgoing.started_at + sequence * FRAME_SECONDS < oldest_time
        ]
        for sequence in expired:
            outgoing.retained.pop(sequence, None)
            outgoing.sent_sequences.discard(sequence)

    def _disconnect_expired(self, outgoing: _Outgoing) -> bool:
        return bool(
            outgoing.disconnected_at is not None
            and self._now() - outgoing.disconnected_at >= self._recovery_horizon_seconds()
        )

    def _recovery_horizon_seconds(self) -> float:
        if self._snapshot is None:
            return 5.0
        return self._snapshot["audio_policy"]["recovery_horizon_ms"] / 1_000

    def _outgoing_current(self, outgoing: _Outgoing) -> bool:
        return outgoing.active and outgoing.epoch == self._logical_epoch and self._outgoing is outgoing

    async def _request_floor(self, outgoing: _Outgoing) -> dict:
        last_transport = -1
        deadline = self._now() + PTT_REQUEST_TIMEOUT_SECONDS
        outgoing.grant_deadline = deadline
        loop = asyncio.get_running_loop()
        waiter = loop.create_future()
        self._control_waiters[outgoing.request_id] = waiter
        try:
            while self._outgoing_current(outgoing):
                if self._now() >= deadline:
                    await self._abandon_session("grant_timeout")
                    break
                ready = await self._wait_ready(outgoing.epoch, deadline=deadline)
                if not ready or not self._outgoing_current(outgoing):
                    if self._outgoing_current(outgoing) and self._now() >= deadline:
                        await self._abandon_session("grant_timeout")
                    break
                context = self._transport
                transport = (
                    context.generation if context is not None else self._transport_generation
                )
                if transport != last_transport:
                    sent = await self._send_json(
                        {"type": "ptt_request", "request_id": outgoing.request_id},
                        epoch=outgoing.epoch,
                        transport=transport,
                        deadline=deadline,
                        on_send_start=lambda: setattr(
                            outgoing, "request_started", True
                        ),
                    )
                    if sent:
                        last_transport = transport
                try:
                    result = await asyncio.wait_for(
                        asyncio.shield(waiter), max(0, min(0.25, deadline - self._now()))
                    )
                    if not self._outgoing_current(outgoing):
                        break
                    if result["type"] == "ptt_denied":
                        outgoing.request_started = False
                    return result
                except asyncio.TimeoutError:
                    continue
            return {"type": "ptt_denied", "reason": outgoing.stop_reason or "session_lost"}
        finally:
            self._control_waiters.pop(outgoing.request_id, None)

    async def _connection_loop(self) -> None:
        delays = (0, 0.25, 0.5, 1, 2)
        attempt = 0
        while not self._stopping.is_set() and self._terminal_reason is None:
            if attempt:
                await self._sleep(delays[attempt] if attempt < len(delays) else 5)
            if self._stopping.is_set() or self._terminal_reason is not None:
                return
            context: _TransportContext | None = None
            try:
                async with connect(
                    self.config.server_url,
                    subprotocols=[SUBPROTOCOL],
                    max_size=MAX_MESSAGE_BYTES,
                    ping_interval=20,
                    ping_timeout=60,
                    open_timeout=5,
                ) as socket:
                    context = self._attach_transport(socket)
                    accepted = await self._handshake(context)
                    if not accepted:
                        attempt += 1
                        continue
                    attempt = 0
                    receiver = asyncio.create_task(self._receive_loop(context))
                    pinger = asyncio.create_task(self._ping_loop(context))
                    try:
                        await receiver
                    finally:
                        pinger.cancel()
                        with suppress(asyncio.CancelledError):
                            await pinger
            except asyncio.CancelledError:
                raise
            except Exception as error:
                logger.warning("connection_lost error=%s", type(error).__name__)
                attempt += 1
            finally:
                if context is not None:
                    self._detach_transport(context)

    def _attach_transport(self, socket: ClientConnection) -> _TransportContext:
        self._transport_generation += 1
        context = _TransportContext(socket, self._transport_generation, self._logical_epoch)
        self._transport = context
        return context

    def _transport_current(self, context: _TransportContext) -> bool:
        return self._transport is context and context.epoch == self._logical_epoch

    def _detach_transport(self, context: _TransportContext) -> bool:
        if self._transport is not context:
            return False
        self._transport = None
        self._ready.clear()
        outgoing = self._outgoing
        if (
            outgoing is not None
            and outgoing.burst_id is not None
            and outgoing.disconnected_at is None
        ):
            outgoing.disconnected_at = self._now()
            outgoing.changed.set()
        return True

    async def _handshake(self, context: _TransportContext) -> bool:
        resuming = self._resume_token is not None
        if resuming:
            self._generation += 1
            command = {
                "type": "resume",
                "resume_token": self._resume_token,
                "generation": self._generation,
            }
        elif self._echo_ticket is not None:
            command = {"type": "join_echo_bot", "ticket": self._echo_ticket}
        else:
            command = {"type": "join", "channel": self.config.channel}
        if not await self._write_json(command, context=context, require_ready=False):
            return False
        raw = await asyncio.wait_for(context.socket.recv(), 5)
        if not self._transport_current(context):
            return False
        if not isinstance(raw, str):
            raise ValueError("Snapshot must be a control message")
        message = parse_control(raw)
        if message["type"] == "resume_rejected":
            socket = self._lose_session("resume_rejected", context)
            self._abort_socket(socket)
            return False
        if message["type"] != "snapshot":
            raise ValueError("Expected snapshot")
        incarnation = message["channel_incarnation_id"]
        if self._incarnation is not None and incarnation != self._incarnation:
            socket = self._lose_session("server_incarnation_changed", context)
            self._abort_socket(socket)
            return False

        self._resume_token = message["resume_token"]
        self._generation = message["generation"]
        self._incarnation = incarnation
        self._snapshot = message
        if not resuming or self._listen_cursor == (0, 0):
            eligible = message["eligible_from_index"]
            self._listen_cursor = (eligible, 0)
            self._delivered_next_index = max(self._delivered_next_index, eligible)
            self._assembler.reset_cursor(*self._listen_cursor)

        outgoing = self._outgoing
        if outgoing is not None and outgoing.burst_id is not None:
            floor = message["floor"]
            owns_floor = bool(
                floor and floor.get("owned") and floor.get("burst_id") == outgoing.burst_id
            )
            if not owns_floor and outgoing.phase == "transmitting":
                outgoing.stop_reason = outgoing.stop_reason or "released"
                self._freeze_outgoing(outgoing)

        if not await self._write_json(
            {
                "type": "listen",
                "burst_index": self._listen_cursor[0],
                "next_sequence": self._listen_cursor[1],
            },
            context=context,
            require_ready=False,
        ):
            return False
        if not self._transport_current(context):
            return False
        if outgoing is not None:
            if outgoing.phase == "transmitting" and self._disconnect_expired(outgoing):
                outgoing.stop_reason = outgoing.stop_reason or "recovery_timeout"
                self._freeze_outgoing(outgoing)
            elif outgoing.phase in {"waiting_grant", "transmitting"}:
                outgoing.disconnected_at = None
            outgoing.changed.set()
        self._last_pong = self._now()
        self._ready.set()
        logger.info("session_ready resumed=%s", resuming)
        return True

    async def _receive_loop(self, context: _TransportContext) -> None:
        async for raw in context.socket:
            if not self._transport_current(context):
                return
            if isinstance(raw, bytes):
                burst_id, first, packets = decode_media(raw, DOWNLINK)
                if not self._transport_current(context):
                    return
                if burst_id in {self._ignored_burst_id, self._stale_burst_id}:
                    continue
                try:
                    self._assembler.media(burst_id, first, packets)
                except PcmBudgetExceeded:
                    self._discard_receive(burst_id)
                    continue
                self._advance_listen_cursor(self._assembler.cursor)
            else:
                await self._handle_control(parse_control(raw), context=context)

    async def _handle_control(
        self, message: dict, *, context: _TransportContext | None = None
    ) -> None:
        if context is not None and not self._transport_current(context):
            return
        epoch = self._logical_epoch if context is None else context.epoch
        kind = message["type"]
        if kind == "burst_started":
            index = message["burst_index"]
            if message["burst_id"] == self._ignored_burst_id:
                return
            if index < self._delivered_next_index:
                # Replayed history must not replace the current PCM-budget rejection.
                self._stale_burst_id = message["burst_id"]
                return
            self._ignored_burst_id = None
            self._assembler.start(message["burst_id"], index)
            self._advance_listen_cursor(self._assembler.cursor)
        elif kind == "burst_gaps":
            if (
                message["burst_index"] < self._delivered_next_index
                or message["burst_id"] == self._ignored_burst_id
            ):
                return
            try:
                self._assembler.gaps(message["burst_id"], message["ranges"])
            except PcmBudgetExceeded:
                self._discard_receive(message["burst_id"])
                return
            self._advance_listen_cursor(self._assembler.cursor)
        elif kind == "burst_sealed":
            index = message["burst_index"]
            if index < self._delivered_next_index:
                return
            result = None
            if message["burst_id"] != self._ignored_burst_id:
                try:
                    result = self._assembler.seal(
                        message["burst_id"], message["final_next_sequence"], message["reason"]
                    )
                except PcmBudgetExceeded:
                    self._discard_receive(message["burst_id"])
            if message["burst_id"] == self._ignored_burst_id:
                self._delivered_next_index = max(self._delivered_next_index, index + 1)
                self._advance_listen_cursor((index + 1, 0))
            if result is not None:
                self._delivered_next_index = max(self._delivered_next_index, index + 1)
                self._advance_listen_cursor((index + 1, 0))
                if context is None or self._transport_current(context):
                    self._offer_event(replace(result, session_epoch=epoch))
        elif kind == "listen_reset":
            requested_cursor = (message["burst_index"], message["next_sequence"])
            if (
                requested_cursor == self._last_listen_reset
                or requested_cursor < self._listen_cursor
            ):
                return
            interrupted = self._assembler.interrupt("listen_reset")
            if interrupted and (context is None or self._transport_current(context)):
                self._offer_event(replace(interrupted, session_epoch=epoch))
            index = message["burst_index"]
            sequence = message["next_sequence"]
            if index < self._delivered_next_index:
                index, sequence = self._delivered_next_index, 0
            self._listen_cursor = (index, sequence)
            self._last_listen_reset = self._listen_cursor
            self._delivered_next_index = max(self._delivered_next_index, index)
            # A history reset must not revive a burst rejected by the PCM budget.
            # A new burst_started or logical session will replace the ignored ID.
            self._assembler.reset_cursor(index, sequence)
            await self._send_json(
                {"type": "listen", "burst_index": index, "next_sequence": sequence},
                epoch=epoch,
                transport=None if context is None else context.generation,
            )
        elif kind in {"ptt_granted", "ptt_denied"}:
            waiter = self._control_waiters.get(message["request_id"])
            outgoing = self._outgoing
            accepted = True
            if kind == "ptt_granted":
                if (
                    outgoing is not None and outgoing.request_id == message["request_id"]
                    and outgoing.phase == "waiting_grant" and outgoing.grant_deadline is not None
                    and self._now() > outgoing.grant_deadline
                ):
                    await self._abandon_session("grant_timeout")
                    return
                accepted = bool(
                    outgoing is not None
                    and outgoing.request_id == message["request_id"]
                    and self._outgoing_current(outgoing)
                    and self._accept_grant(outgoing, message)
                )
            if accepted and waiter is not None and not waiter.done():
                waiter.set_result(message)
        elif kind == "uplink_ack":
            outgoing = self._matching_outgoing(message)
            if outgoing is not None:
                outgoing.ack_next = max(outgoing.ack_next, message["next_sequence"])
                for sequence in tuple(outgoing.retained):
                    if sequence < outgoing.ack_next:
                        outgoing.retained.pop(sequence, None)
                        outgoing.sent_sequences.discard(sequence)
                outgoing.changed.set()
        elif kind == "audio_rejected":
            outgoing = self._matching_outgoing(message)
            if outgoing is not None:
                reason = message["reason"]
                if reason not in {"expired", "server_busy"}:
                    outgoing.stop_reason = reason
                    self._freeze_outgoing(outgoing)
                if reason == "server_busy":
                    outgoing.sent_sequences.difference_update(
                        range(message["first_sequence"], message["next_sequence"])
                    )
                outgoing.changed.set()
                if reason == "payload_mismatch":
                    self._terminal_reason = reason
                    logger.error("manual_reconnect_required reason=%s", reason)
                    await self._abandon_session("payload_mismatch")
        elif kind == "ptt_ended":
            outgoing = self._matching_outgoing(message)
            if outgoing is not None:
                previous = outgoing.terminal
                if previous is not None and previous["state"] == "sealed":
                    if message["state"] == "sealed" and message != previous:
                        logger.warning("conflicting_terminal burst=%s", outgoing.burst_id)
                    return
                outgoing.terminal = message
                if message["state"] == "sealed":
                    outgoing.terminal_at = self._now()
                if message["state"] == "draining" and outgoing.phase == "transmitting":
                    outgoing.stop_reason = message["reason"]
                    self._freeze_outgoing(outgoing, message["final_next_sequence"])
                elif message["state"] == "sealed":
                    if outgoing.phase == "transmitting":
                        outgoing.stop_reason = outgoing.stop_reason or message["reason"]
                        self._freeze_outgoing(outgoing, message["final_next_sequence"])
                    outgoing.phase = "sealed"
                outgoing.changed.set()
        elif kind == "pong":
            self._last_pong = self._now()
        elif kind == "error":
            # Errors carry no request ID, so continuing could leave an unknown floor.
            logger.error("server_error code=%s", message["code"])
            await self._abandon_session(f"server_error_{message['code']}")

    def _advance_listen_cursor(self, cursor: tuple[int, int]) -> None:
        if cursor >= self._listen_cursor:
            self._listen_cursor = cursor

    def _matching_outgoing(self, message: dict) -> _Outgoing | None:
        outgoing = self._outgoing
        if (
            outgoing is not None
            and outgoing.epoch == self._logical_epoch
            and message.get("burst_id") == outgoing.burst_id
        ):
            return outgoing
        return None

    async def _ping_loop(self, context: _TransportContext) -> None:
        while self._transport_current(context):
            await self._sleep(1)
            if not self._transport_current(context):
                return
            if self._now() - self._last_pong >= 10:
                self._detach_transport(context)
                self._abort_socket(context.socket)
                return
            self._ping_id = (self._ping_id + 1) & 0x7FFFFFFF
            await self._write_json(
                {
                    "type": "ping",
                    "id": self._ping_id,
                    "sent_at_ms": int(self._now() * 1_000),
                },
                context=context,
            )

    def _lose_session(
        self, reason: str, context: _TransportContext | None = None
    ) -> ClientConnection | None:
        if context is not None and not self._transport_current(context):
            return None
        old_context = self._transport
        self._ready.clear()
        self._transport = None
        interrupted = self._assembler.interrupt(reason)
        self._logical_epoch += 1
        self._resume_token = None
        self._generation = 0
        self._incarnation = None
        self._listen_cursor = (0, 0)
        self._delivered_next_index = 0
        self._ignored_burst_id = None
        self._stale_burst_id = None
        self._last_listen_reset = None
        self._assembler.reset_session()
        while not self._events.empty():
            with suppress(asyncio.QueueEmpty):
                self._events.get_nowait()
        if self._outgoing is not None:
            self._outgoing.active = False
            self._outgoing.stop_reason = reason
            self._outgoing.retained.clear()
            self._outgoing.sent_sequences.clear()
            self._outgoing.changed.set()
        for waiter in self._control_waiters.values():
            if not waiter.done():
                waiter.set_result({"type": "ptt_denied", "reason": "session_lost"})
        event = interrupted or ReceiveInterrupted(None, None, reason)
        self._offer_event(replace(event, session_epoch=self._logical_epoch))
        return None if old_context is None else old_context.socket

    async def _abandon_session(self, reason: str) -> None:
        socket = self._lose_session(reason)
        self._abort_socket(socket)
        if socket is not None:
            closing = asyncio.create_task(self._close_socket(socket))
            closing.add_done_callback(self._consume_task_result)

    def _offer_event(self, event: ReceivedBurst | ReceiveInterrupted) -> None:
        if isinstance(event, ReceivedBurst) and not self._pcm_budget.admit(event.audio):
            logger.error("pcm_budget_exceeded burst=%s", event.burst_index)
            return
        try:
            self._events.put_nowait(event)
        except asyncio.QueueFull:
            logger.error("receive_queue_full burst=%s", event.burst_index)

    def _discard_receive(self, burst_id: str) -> None:
        event = self._assembler.interrupt("pcm_budget_exceeded")
        self._ignored_burst_id = burst_id
        logger.error("pcm_budget_exceeded burst=%s", burst_id)
        if event is not None:
            self._offer_event(replace(event, session_epoch=self._logical_epoch))

    async def _wait_ready(self, epoch: int, *, deadline: float | None = None) -> bool:
        while not self._stopping.is_set() and epoch == self._logical_epoch:
            remaining = 0.1 if deadline is None else min(0.1, deadline - self._now())
            if remaining <= 0:
                return False
            if self._ready.is_set() and self._transport is not None:
                return True
            with suppress(asyncio.TimeoutError):
                await asyncio.wait_for(self._ready.wait(), remaining)
        return False

    async def _send_json(
        self,
        message: dict,
        *,
        epoch: int | None = None,
        transport: int | None = None,
        deadline: float | None = None,
        on_send_start: Callable[[], None] | None = None,
    ) -> bool:
        encoded = json.dumps(message, separators=(",", ":"))
        return await self._send_current(
            encoded,
            epoch=epoch,
            transport=transport,
            deadline=deadline,
            on_send_start=on_send_start,
        )

    async def _send_bytes(
        self,
        message: bytes,
        *,
        epoch: int | None = None,
        transport: int | None = None,
        deadline: float | None = None,
        audio: tuple[_Outgoing, int, bytes] | None = None,
        on_send_start: Callable[[], None] | None = None,
    ) -> bool:
        return await self._send_current(
            message,
            epoch=epoch,
            transport=transport,
            deadline=deadline,
            audio=audio,
            on_send_start=on_send_start,
        )

    async def _send_current(
        self,
        message: str | bytes,
        *,
        epoch: int | None,
        transport: int | None,
        deadline: float | None = None,
        audio: tuple[_Outgoing, int, bytes] | None = None,
        on_send_start: Callable[[], None] | None = None,
    ) -> bool:
        context = self._transport
        if context is None:
            return False
        if epoch is not None and epoch != context.epoch:
            return False
        if transport is not None and transport != context.generation:
            return False
        return await self._write(
            context,
            message,
            deadline=deadline,
            audio=audio,
            on_send_start=on_send_start,
        )

    async def _write_json(
        self,
        message: dict,
        *,
        context: _TransportContext,
        deadline: float | None = None,
        require_ready: bool = True,
    ) -> bool:
        return await self._write(
            context,
            json.dumps(message, separators=(",", ":")),
            deadline=deadline,
            require_ready=require_ready,
        )

    async def _write(
        self,
        context: _TransportContext,
        message: str | bytes,
        *,
        deadline: float | None = None,
        require_ready: bool = True,
        audio: tuple[_Outgoing, int, bytes] | None = None,
        on_send_start: Callable[[], None] | None = None,
    ) -> bool:
        if not self._transport_current(context):
            return False
        if require_ready and not self._ready.is_set():
            return False
        timeout = WRITE_TIMEOUT_SECONDS
        if deadline is not None:
            timeout = min(timeout, max(0, deadline - self._now()))
        if timeout <= 0:
            return False

        send_started = False

        async def locked_write() -> bool:
            nonlocal send_started
            async with context.write_lock:
                if not self._transport_current(context):
                    return False
                if require_ready and not self._ready.is_set():
                    return False
                if audio is not None:
                    outgoing, sequence, packet = audio
                    self._prune_retained(outgoing)
                    current_deadline = outgoing.finalization_deadline
                    if current_deadline is not None and self._now() >= current_deadline:
                        return False
                    if (
                        not self._outgoing_current(outgoing)
                        or sequence < outgoing.ack_next
                        or sequence in outgoing.sent_sequences
                        or outgoing.retained.get(sequence) != packet
                        or (
                            outgoing.final_next_sequence is not None
                            and sequence >= outgoing.final_next_sequence
                        )
                    ):
                        return True
                if deadline is not None and self._now() >= deadline:
                    return False
                if on_send_start is not None:
                    on_send_start()
                send_started = True
                await context.socket.send(message)
                return self._transport_current(context) and (
                    deadline is None or self._now() <= deadline
                )

        operation = asyncio.create_task(locked_write())
        timer = asyncio.create_task(asyncio.sleep(timeout))
        try:
            done, _ = await asyncio.wait({operation, timer}, return_when=asyncio.FIRST_COMPLETED)
        except asyncio.CancelledError:
            operation.cancel()
            timer.cancel()
            operation.add_done_callback(self._consume_task_result)
            timer.add_done_callback(self._consume_task_result)
            if send_started and not operation.done():
                self._detach_transport(context)
                self._abort_socket(context.socket)
            raise
        if operation in done:
            timer.cancel()
            with suppress(asyncio.CancelledError):
                await timer
            try:
                return operation.result()
            except Exception:
                self._detach_transport(context)
                self._abort_socket(context.socket)
                return False

        operation.cancel()
        operation.add_done_callback(self._consume_task_result)
        self._detach_transport(context)
        self._abort_socket(context.socket)
        return False

    @staticmethod
    def _consume_task_result(task: asyncio.Task) -> None:
        with suppress(asyncio.CancelledError, Exception):
            task.result()

    @staticmethod
    def _abort_socket(socket: ClientConnection | None) -> None:
        if socket is None:
            return
        transport = getattr(socket, "transport", None)
        if transport is not None:
            with suppress(Exception):
                transport.abort()

    @staticmethod
    async def _close_socket(socket: ClientConnection) -> None:
        with suppress(Exception):
            await asyncio.wait_for(socket.close(code=1000), 1)
