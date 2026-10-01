"""Coordinates WebSocket admission, floor state, recovery, and ordered listen."""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable, Iterable
import hashlib
import json
import logging
import time
from typing import Any
from types import MappingProxyType
import uuid

from fastapi import WebSocket, WebSocketDisconnect

from .client_session import ClientSession, MessageRateLimiter
from .media_timing import OutboundMediaContext
from .domain import (
    ECHO_CHANNEL,
    MAX_BURST_MS,
    AudioRejected,
    Channel,
    ChannelRegistry,
    DomainError,
    InvalidChannel,
    LogicalMember,
    RecoveryPolicy,
    ServerBusy,
    StoredFrame,
    TalkBurst,
    normalize_channel_code,
)
from .protocol import (
    DOWNLINK_MEDIA_TYPE,
    MAX_FRAMES_PER_MESSAGE,
    MAX_MESSAGE_BYTES,
    MEDIA_HEADER,
    MediaEnvelope,
    PACKET_LENGTH,
    UPLINK_MEDIA_TYPE,
    WEBSOCKET_SUBPROTOCOL,
    decode_media,
    encode_media,
    integer,
    string,
)
from .echo import EchoCoordinator
from .settings import Settings

logger = logging.getLogger("zenptt.server")

INITIAL_FLOOR_LEASE_SECONDS = 5.0
FLOOR_LEASE_SECONDS = 2.0
PARTICIPANT_PRESENCE_TIMEOUT_MS = 3_000
RECOVERY_CLEANUP_INTERVAL_SECONDS = 0.1
PUBLIC_MESSAGES = {
    "invalid_message": "Invalid message",
    "invalid_channel": "Invalid channel",
    "channel_full": "Channel is full",
    "not_joined": "Join a channel first",
    "invalid_state": "Action is not available",
    "server_busy": "Server is busy",
    "internal_error": "Server error",
}


class ClientRequestedDisconnect(Exception):
    pass


class SessionManager:
    def __init__(
        self,
        settings: Settings,
        wait: Callable[[float], Awaitable[None]] = asyncio.sleep,
        now_ms: Callable[[], int] = lambda: int(time.monotonic() * 1000),
    ) -> None:
        self.settings = settings
        self.wait = wait
        self.now_ms = now_ms
        self.recovery_policy = RecoveryPolicy(settings.recovery_horizon_ms)
        self.registry = ChannelRegistry(
            max_participants=settings.max_channel_participants,
            max_code_length=settings.max_channel_code_length,
            recovery_policy=self.recovery_policy,
        )
        self.sessions: dict[str, ClientSession] = {}
        self.sessions_per_client: dict[str, int] = {}
        self.connect_lock = asyncio.Lock()
        self.floor_tasks: dict[str, asyncio.Task[None]] = {}
        self.cleanup_task: asyncio.Task[None] | None = None
        self.echo = EchoCoordinator(
            registry=self.registry,
            sessions=MappingProxyType(self.sessions),
            now_ms=lambda: self.now_ms(),
            snapshot=self.snapshot,
            broadcast_channel_state=self.broadcast_channel_state,
        )

    async def connect(self, websocket: WebSocket) -> ClientSession | None:
        if WEBSOCKET_SUBPROTOCOL not in websocket.scope.get("subprotocols", []):
            logger.info("unsupported_websocket_subprotocol")
            await websocket.close(code=1002)
            return None
        client_key = websocket.client.host if websocket.client else "unknown"
        async with self.connect_lock:
            if len(self.sessions) >= self.settings.max_active_sessions:
                await websocket.close(code=1013)
                return None
            client_active = self.sessions_per_client.get(client_key, 0)
            if client_active >= self.settings.max_active_sessions_per_client_ip:
                await websocket.close(code=1013)
                return None
            await websocket.accept(subprotocol=WEBSOCKET_SUBPROTOCOL)
            session = ClientSession(
                websocket=websocket,
                client_key=client_key,
                last_activity_ms=self.now_ms(),
                now_ms=self.now_ms,
                rate_limiter=MessageRateLimiter(
                    control_limit=self.settings.max_control_messages_per_second,
                    audio_message_limit=self.settings.max_audio_messages_per_second,
                ),
            )
            session.start_outbound()
            self.sessions[session.session_id] = session
            self.sessions_per_client[client_key] = client_active + 1
            if self.cleanup_task is None or self.cleanup_task.done():
                self.cleanup_task = asyncio.create_task(self.run_cleanup())
        logger.info("connect session=%s protocol=v4", session.session_id)
        return session

    async def disconnect(self, session: ClientSession) -> None:
        session.closing = True
        channel = session.channel
        released: TalkBurst | None = None
        if session.member is not None:
            if session.intentional_close:
                released = await self.registry.intentional_leave(session.member, self.now_ms())
            else:
                await self.registry.transport_lost(
                    session.member, session.session_id, self.now_ms()
                )
        if released is not None and channel is not None:
            self.cancel_floor_timer(released.burst_id)
            self.send_ptt_ended(session, released)
            await self.pump_channel_listeners(channel)
        if (
            channel is not None
            and channel.code == ECHO_CHANNEL
            and session.member is not None
            and session.member.role == "echo_bot"
        ):
            interrupted = await self.registry.cancel_channel_floor(channel, self.now_ms())
            if interrupted is not None:
                self.cancel_floor_timer(interrupted.burst_id)
                owner = self.session_for_member(channel, interrupted.owner_member_id)
                if owner is not None:
                    self.send_ptt_ended(owner, interrupted)
                await self.pump_channel_listeners(channel)
        if channel is not None:
            self.broadcast_channel_state(channel)
        await session.stop_outbound()
        removed = self.sessions.pop(session.session_id, None)
        if removed is not None:
            remaining = self.sessions_per_client.get(session.client_key, 1) - 1
            if remaining:
                self.sessions_per_client[session.client_key] = remaining
            else:
                self.sessions_per_client.pop(session.client_key, None)
        logger.info(
            "disconnect session=%s audio_frames=%d intentional=%s",
            session.session_id,
            session.audio_frames_received,
            session.intentional_close,
        )
        if channel is not None and channel.code == ECHO_CHANNEL:
            await self.echo.reconcile()

    async def handle(self, websocket: WebSocket) -> None:
        session = await self.connect(websocket)
        if session is None:
            return
        try:
            while True:
                event = await websocket.receive()
                if event["type"] == "websocket.disconnect":
                    if event.get("code") == 1000:
                        session.intentional_close = True
                    break
                if event.get("text") is not None:
                    await self.handle_text(session, event["text"])
                elif event.get("bytes") is not None:
                    await self.handle_audio(session, event["bytes"])
                else:
                    self.send_error(session, "invalid_message")
        except WebSocketDisconnect as error:
            if error.code == 1000:
                session.intentional_close = True
        except ClientRequestedDisconnect:
            pass
        except Exception:
            logger.exception("connection_error session=%s", session.session_id)
            self.send_error(session, "internal_error")
        finally:
            await self.disconnect(session)

    async def handle_text(self, session: ClientSession, text: str) -> None:
        self.record_activity(session)
        if not session.rate_limiter.allow_control():
            await session.close(code=1008)
            raise ClientRequestedDisconnect
        if len(text.encode("utf-8")) > MAX_MESSAGE_BYTES:
            self.send_error(session, "invalid_message")
            return
        message: dict[str, Any] = {}
        try:
            parsed = json.loads(text)
            if not isinstance(parsed, dict) or not isinstance(parsed.get("type"), str):
                raise ValueError
            message = parsed
            await self.dispatch_control(session, message)
        except (json.JSONDecodeError, ValueError, TypeError):
            self.send_error(session, "invalid_message")
        except AudioRejected as error:
            self.send_audio_rejected(
                session,
                self.optional_burst_id(message.get("burst_id")),
                0,
                self.optional_sequence(message.get("final_next_sequence")),
                error.reason,
            )
        except DomainError as error:
            self.send_error(session, error.code, message.get("type", "unknown"))

    async def dispatch_control(self, session: ClientSession, message: dict[str, Any]) -> None:
        message_type = message["type"]
        if message_type == "join":
            self.require_keys(message, {"type", "channel"})
            await self.join(session, string(message["channel"], 256))
        elif message_type == "join_echo":
            self.require_keys(message, {"type"})
            await self.echo.join(session)
        elif message_type == "join_echo_bot":
            self.require_keys(message, {"type", "ticket"})
            await self.echo.join_bot(session, string(message["ticket"], 256))
        elif message_type == "resume":
            self.require_keys(message, {"type", "resume_token", "generation"})
            await self.resume(
                session,
                string(message["resume_token"], 256),
                integer(message["generation"], 2, 0x7FFFFFFF),
            )
        elif message_type == "listen":
            self.require_keys(message, {"type", "burst_index", "next_sequence"})
            await self.listen(
                session,
                integer(message["burst_index"], 0, 0x7FFFFFFF),
                integer(message["next_sequence"], 0, 0xFFFFFFFF),
            )
        elif message_type == "ptt_request":
            self.require_keys(message, {"type", "request_id"})
            await self.request_floor(session, self.request_id(message["request_id"]))
        elif message_type == "ptt_cancel":
            self.require_keys(message, {"type", "request_id"})
            await self.cancel_request(session, self.request_id(message["request_id"]))
        elif message_type == "burst_end":
            self.require_keys(message, {"type", "burst_id", "final_next_sequence"})
            await self.end_burst(
                session,
                self.burst_id(message["burst_id"]),
                integer(message["final_next_sequence"], 0, 0xFFFFFFFF),
            )
        elif message_type == "ping":
            self.require_keys(message, {"type", "id", "sent_at_ms"})
            self.ping(session, message["id"], message["sent_at_ms"])
        elif message_type == "disconnect":
            self.require_keys(message, {"type"})
            session.intentional_close = True
            await session.close(code=1000)
            raise ClientRequestedDisconnect
        else:
            raise ValueError

    async def join(self, session: ClientSession, raw_code: str) -> None:
        if session.channel is not None or session.member is not None:
            raise ValueError
        code = normalize_channel_code(raw_code, self.settings.max_channel_code_length)
        if code == ECHO_CHANNEL:
            raise InvalidChannel
        channel, member, token = await self.registry.fresh_join(
            code, session.session_id, self.now_ms()
        )
        session.channel = channel
        session.member = member
        session.resume_token = token
        session.offer_json(self.snapshot(session))
        self.broadcast_channel_state(channel)
        logger.info("join session=%s channel=%s", session.session_id, self.channel_reference(code))

    async def resume(self, session: ClientSession, token: str, generation: int) -> None:
        if session.channel is not None or session.member is not None:
            raise ValueError
        result = await self.registry.resume(
            token=token,
            generation=generation,
            transport_id=session.session_id,
            now_ms=self.now_ms(),
        )
        if not result.accepted or result.member is None or result.channel is None:
            session.offer_json({"type": "resume_rejected", "reason": result.reason})
            return
        session.member = result.member
        session.channel = result.channel
        session.resume_token = token
        fenced = self.sessions.get(result.fenced_transport_id or "")
        if fenced is not None and fenced is not session:
            fenced.authoritative = False
            fenced._schedule_close(1001)
        session.offer_json(self.snapshot(session))
        self.broadcast_channel_state(result.channel)
        logger.info("resume session=%s generation=%d", session.session_id, generation)

    def snapshot(self, session: ClientSession) -> dict[str, Any]:
        member = self.require_member(session)
        channel = self.require_channel(session)
        return {
            "type": "snapshot",
            "channel": channel.code,
            "member_id": member.member_id,
            "resume_token": session.resume_token or "",
            "generation": member.generation,
            "channel_incarnation_id": channel.incarnation_id,
            "revision": channel.revision,
            "participant_count": self.participant_count(channel),
            "eligible_from_index": member.eligible_from_index,
            "next_burst_index": channel.next_burst_index,
            "audio_policy": self.audio_policy,
            "floor": self.floor_payload(channel, member),
        }

    @property
    def audio_policy(self) -> dict[str, int]:
        return {"recovery_horizon_ms": self.recovery_policy.recovery_horizon_ms}

    async def request_floor(self, session: ClientSession, request_id: str) -> None:
        member = self.require_member(session)
        channel = self.require_channel(session)
        if channel.code == ECHO_CHANNEL and self.participant_count(channel) < 2:
            session.offer_json(
                {"type": "ptt_denied", "request_id": request_id, "reason": "invalid_state"}
            )
            return
        try:
            result = await self.registry.request_floor(
                member=member,
                request_id=request_id,
                now_ms=self.now_ms(),
                initial_lease_ms=self.initial_floor_lease_ms,
            )
        except ServerBusy:
            session.offer_json(
                {"type": "ptt_denied", "request_id": request_id, "reason": "server_busy"}
            )
            return
        burst = result.burst
        if burst is None:
            session.offer_json(
                {
                    "type": "ptt_denied",
                    "request_id": request_id,
                    "reason": result.denial_reason or "channel_busy",
                }
            )
            return
        session.offer_json(
            {
                "type": "ptt_granted",
                "request_id": request_id,
                "burst_id": burst.burst_id,
                "burst_index": burst.index,
                "lease_remaining_ms": max(0, burst.lease_expires_at_ms - self.now_ms()),
            }
        )
        if result.created:
            logger.info("grant session=%s burst=%s", session.session_id, burst.index)
            self.start_floor_timer(channel, burst)
            await self.pump_channel_listeners(channel)
            self.broadcast_channel_state(channel)

    async def cancel_request(self, session: ClientSession, request_id: str) -> None:
        result = await self.registry.cancel_request(
            self.require_member(session), request_id, self.now_ms()
        )
        if result is None:
            return
        channel, burst = result
        self.cancel_floor_timer(burst.burst_id)
        self.send_ptt_ended(session, burst)
        await self.pump_channel_listeners(channel)
        self.broadcast_channel_state(channel)

    async def end_burst(
        self, session: ClientSession, burst_id: str, final_next_sequence: int
    ) -> None:
        member = self.require_member(session)
        try:
            channel, burst, changed = await self.registry.end_burst(
                member, burst_id, final_next_sequence, self.now_ms()
            )
        except AudioRejected as error:
            terminal = await self.registry.owned_terminal_burst(member, burst_id)
            if error.reason != "invalid_range" or terminal is None:
                raise
            self.send_audio_rejected(
                session, burst_id, 0, final_next_sequence, error.reason
            )
            self.send_ptt_ended(session, terminal)
            return
        self.cancel_floor_timer(burst.burst_id)
        self.send_ptt_ended(session, burst)
        if changed:
            await self.pump_channel_listeners(channel)
            self.broadcast_channel_state(channel)

    async def listen(self, session: ClientSession, burst_index: int, next_sequence: int) -> None:
        result = await self.registry.resolve_listen(
            self.require_member(session), burst_index, next_sequence, self.now_ms()
        )
        session.listen_burst_index = result.burst_index
        session.listen_next_sequence = result.next_sequence
        session.announced_bursts.clear()
        session.downlink_next_sequences.clear()
        session.released_bursts.clear()
        session.sealed_bursts.clear()
        if result.status == "reset":
            session.offer_json(
                {
                    "type": "listen_reset",
                    "burst_index": result.burst_index,
                    "next_sequence": result.next_sequence,
                    "reason": result.reason,
                }
            )
            return
        await self.pump_listener(session)

    async def pump_listener(self, session: ClientSession) -> None:
        session.outbound_capacity_callback = lambda: self.schedule_listener_pump(session)
        member = session.member
        channel = session.channel
        start_index = session.listen_burst_index
        if member is None or channel is None or start_index is None or session.closing:
            return
        async with self.registry.lock:
            bursts = [
                burst
                for burst in sorted(channel.bursts.values(), key=lambda item: item.index)
                if burst.index >= start_index and channel.eligible(member, burst)
            ]
            snapshots = []
            for burst in bursts:
                minimum = session.listen_next_sequence if burst.index == start_index else 0
                cursor = session.downlink_next_sequences.get(burst.burst_id, minimum)
                first_available = max(cursor, burst.history_floor_sequence)
                snapshots.append(
                    (
                        burst,
                        cursor,
                        tuple(
                            burst.frames[sequence]
                            for sequence in range(first_available, burst.resolved_next_sequence)
                            if sequence in burst.frames
                        ),
                        burst.released,
                        burst.sealed,
                        burst.final_next_sequence,
                        burst.release_reason,
                        burst.seal_reason,
                        frozenset(
                            sequence
                            for sequence in burst.lost_sequences
                            if first_available <= sequence < burst.resolved_next_sequence
                        ),
                        burst.resolved_next_sequence,
                        burst.history_floor_sequence,
                    )
                )
        for (
            burst,
            cursor,
            frames,
            released,
            sealed,
            final_next,
            release_reason,
            seal_reason,
            lost_sequences,
            resolved_next,
            history_floor,
        ) in snapshots:
            if cursor < history_floor:
                if not session.offer_resumable_json(
                    {
                        "type": "listen_reset",
                        "burst_index": burst.index,
                        "next_sequence": history_floor,
                        "reason": "expired",
                    }
                ):
                    return
                session.listen_burst_index = burst.index
                session.listen_next_sequence = history_floor
                session.downlink_next_sequences.clear()
                return
            if burst.burst_id not in session.announced_bursts:
                if not session.offer_resumable_json(
                    {
                        "type": "burst_started",
                        "burst_id": burst.burst_id,
                        "burst_index": burst.index,
                    }
                ):
                    return
                session.announced_bursts.add(burst.burst_id)
            frames_by_sequence = {frame.sequence: frame for frame in frames}
            while cursor < resolved_next:
                if cursor in lost_sequences:
                    first = cursor
                    gap_end = cursor
                    while gap_end < resolved_next and gap_end in lost_sequences:
                        gap_end += 1
                    if not session.offer_resumable_json(
                        {
                            "type": "burst_gaps",
                            "burst_id": burst.burst_id,
                            "burst_index": burst.index,
                            "ranges": [{"first_sequence": first, "count": gap_end - first}],
                        }
                    ):
                        session.downlink_next_sequences[burst.burst_id] = cursor
                        return
                    cursor = gap_end
                    continue
                frame = frames_by_sequence.get(cursor)
                if frame is None:
                    break
                contiguous = []
                scan_cursor = cursor
                while scan_cursor < resolved_next:
                    frame = frames_by_sequence.get(scan_cursor)
                    if frame is None or scan_cursor in lost_sequences:
                        break
                    contiguous.append(frame)
                    scan_cursor += 1
                for message, sequences, oldest_received, newest_received in self.downlink_messages(
                    burst,
                    contiguous,
                ):
                    queued_at_ms = self.now_ms()
                    media_context = OutboundMediaContext(
                        burst.burst_id,
                        sequences[0],
                        len(sequences),
                        oldest_received,
                        newest_received,
                        queued_at_ms,
                    )
                    if not session.offer_resumable_bytes(message, media_context):
                        session.downlink_next_sequences[burst.burst_id] = cursor
                        return
                    if media_context is not None:
                        session.media_timing.downlink_queued(
                            media_context,
                            session.queued_bytes,
                        )
                    cursor = sequences[-1] + 1
            session.downlink_next_sequences[burst.burst_id] = cursor
            if released and burst.burst_id not in session.released_bursts:
                if not session.offer_resumable_json(
                    {
                        "type": "burst_released",
                        "burst_id": burst.burst_id,
                        "burst_index": burst.index,
                        "reason": release_reason or "released",
                    }
                ):
                    return
                session.released_bursts.add(burst.burst_id)
            if sealed and burst.burst_id not in session.sealed_bursts:
                if not session.offer_resumable_json(
                    {
                        "type": "burst_sealed",
                        "burst_id": burst.burst_id,
                        "burst_index": burst.index,
                        "final_next_sequence": final_next or 0,
                        "reason": seal_reason or "interrupted",
                    }
                ):
                    return
                session.sealed_bursts.add(burst.burst_id)
            if not sealed or cursor < (final_next or 0):
                return

    def schedule_listener_pump(self, session: ClientSession) -> None:
        if session.closing or session.listen_burst_index is None:
            return
        task = session.downlink_task
        if task is not None and not task.done():
            return
        session.downlink_task = asyncio.create_task(self.pump_listener(session))

    async def pump_channel_listeners(self, channel: Channel) -> None:
        for recipient in self.channel_sessions(channel):
            if recipient.listen_burst_index is not None:
                await self.pump_listener(recipient)

    async def handle_audio(self, session: ClientSession, message: bytes) -> None:
        received_at_ms = self.now_ms()
        self.record_activity(session)
        try:
            envelope = decode_media(message, expected_type=UPLINK_MEDIA_TYPE)
        except ValueError:
            self.send_error(session, "invalid_message", "audio")
            return
        if not session.rate_limiter.allow_audio():
            await session.close(code=1008)
            raise ClientRequestedDisconnect
        try:
            channel, burst, added, resolution_changed = await self.registry.append_audio(
                member=self.require_member(session),
                burst_id=envelope.burst_id,
                first_sequence=envelope.first_sequence,
                packets=envelope.packets,
                now_ms=self.now_ms(),
                renewal_lease_ms=self.floor_lease_ms,
                received_at_ms=received_at_ms,
            )
        except AudioRejected as error:
            self.send_audio_rejected(
                session,
                envelope.burst_id,
                envelope.first_sequence,
                envelope.first_sequence + len(envelope.packets),
                error.reason,
            )
            return
        except DomainError as error:
            self.send_error(session, error.code, "audio")
            return
        session.audio_frames_received += len(envelope.packets)
        session.media_timing.uplink_received(
            envelope.burst_id,
            envelope.first_sequence,
            len(envelope.packets),
            len(message),
            received_at_ms,
        )
        self.send_uplink_ack(session, burst)
        if added:
            if burst.state == "open":
                self.start_floor_timer(channel, burst)
        if added or resolution_changed:
            await self.pump_channel_listeners(channel)

    def send_audio_rejected(
        self,
        session: ClientSession,
        burst_id: str,
        first_sequence: int,
        next_sequence: int,
        reason: str,
    ) -> None:
        session.offer_json(
            {
                "type": "audio_rejected",
                "burst_id": burst_id,
                "first_sequence": first_sequence,
                "next_sequence": next_sequence,
                "reason": reason,
            }
        )

    def ping(self, session: ClientSession, ping_id: Any, sent_at_ms: Any) -> None:
        ping_id = integer(ping_id, 0, 0x7FFFFFFF)
        sent_at_ms = integer(sent_at_ms, 0, 0x7FFFFFFFFFFFFFFF)
        session.offer_json({"type": "pong", "id": ping_id, "sent_at_ms": sent_at_ms})

    def start_floor_timer(self, channel: Channel, burst: TalkBurst) -> None:
        if burst.state != "open":
            return
        self.cancel_floor_timer(burst.burst_id)
        deadline = min(burst.lease_expires_at_ms, burst.grant_time_ms + MAX_BURST_MS)
        delay = max(0, deadline - self.now_ms()) / 1000
        self.floor_tasks[burst.burst_id] = asyncio.create_task(
            self.enforce_floor_timeout(channel, burst.burst_id, delay)
        )

    async def enforce_floor_timeout(
        self, channel: Channel, burst_id: str, delay_seconds: float
    ) -> None:
        try:
            await self.wait(delay_seconds)
            burst = await self.registry.expire_floor(channel, burst_id, self.now_ms())
            if burst is not None:
                owner = self.session_for_member(channel, burst.owner_member_id)
                if owner is not None:
                    self.send_ptt_ended(owner, burst)
                await self.pump_channel_listeners(channel)
                self.broadcast_channel_state(channel)
            else:
                current = channel.current_burst
                if current is not None and current.burst_id == burst_id:
                    self.start_floor_timer(channel, current)
        except asyncio.CancelledError:
            pass
        finally:
            if self.floor_tasks.get(burst_id) is asyncio.current_task():
                self.floor_tasks.pop(burst_id, None)

    def cancel_floor_timer(self, burst_id: str) -> None:
        task = self.floor_tasks.pop(burst_id, None)
        if task is not None and task is not asyncio.current_task():
            task.cancel()

    async def run_cleanup(self) -> None:
        try:
            while True:
                await self.wait(RECOVERY_CLEANUP_INTERVAL_SECONDS)
                now_ms = self.now_ms()
                self.expire_stale_presence(now_ms)
                changed_bursts = await self.registry.cleanup(now_ms)
                for channel, burst in changed_bursts:
                    owner = self.session_for_member(channel, burst.owner_member_id)
                    if owner is not None:
                        self.send_uplink_ack(owner, burst)
                    await self.pump_channel_listeners(channel)
                await self.echo.reconcile()
                await self.echo.expire_assignments(now_ms)
                if not self.sessions and not self.registry.channels:
                    return
        except asyncio.CancelledError:
            pass
        finally:
            if self.cleanup_task is asyncio.current_task():
                self.cleanup_task = None

    def downlink_messages(
        self, burst: TalkBurst, frames: Iterable[StoredFrame]
    ) -> list[tuple[bytes, tuple[int, ...], int, int]]:
        ordered = sorted(frames, key=lambda frame: frame.sequence)
        messages: list[tuple[bytes, tuple[int, ...], int, int]] = []
        offset = 0
        while offset < len(ordered):
            chunk = [ordered[offset]]
            message_bytes = MEDIA_HEADER.size + PACKET_LENGTH.size + len(chunk[0].payload)
            while offset + len(chunk) < len(ordered):
                candidate_frame = ordered[offset + len(chunk)]
                if candidate_frame.sequence != chunk[-1].sequence + 1:
                    break
                candidate_bytes = PACKET_LENGTH.size + len(candidate_frame.payload)
                if (
                    len(chunk) == MAX_FRAMES_PER_MESSAGE
                    or message_bytes + candidate_bytes > MAX_MESSAGE_BYTES
                ):
                    break
                chunk.append(candidate_frame)
                message_bytes += candidate_bytes
            payload = encode_media(
                MediaEnvelope(
                    DOWNLINK_MEDIA_TYPE,
                    burst.burst_id,
                    chunk[0].sequence,
                    tuple(frame.payload for frame in chunk),
                )
            )
            messages.append(
                (
                    payload,
                    tuple(frame.sequence for frame in chunk),
                    min(frame.received_at_ms for frame in chunk),
                    max(frame.received_at_ms for frame in chunk),
                )
            )
            offset += len(chunk)
        return messages

    def broadcast_channel_state(self, channel: Channel) -> None:
        participant_count = self.participant_count(channel)
        for recipient in self.channel_sessions(channel):
            member = recipient.member
            if member is not None:
                recipient.offer_json(
                    {
                        "type": "channel_state",
                        "revision": channel.revision,
                        "participant_count": participant_count,
                        "next_burst_index": channel.next_burst_index,
                        "floor": self.floor_payload(channel, member),
                    }
                )

    def record_activity(self, session: ClientSession) -> None:
        session.last_activity_ms = self.now_ms()
        if session.presence_active:
            return
        session.presence_active = True
        if session.channel is not None and session.authoritative and not session.closing:
            self.broadcast_channel_state(session.channel)

    def expire_stale_presence(self, now_ms: int) -> tuple[Channel, ...]:
        changed_channels: dict[str, Channel] = {}
        for session in self.sessions.values():
            if (
                session.presence_active
                and session.authoritative
                and not session.closing
                and now_ms - session.last_activity_ms >= PARTICIPANT_PRESENCE_TIMEOUT_MS
            ):
                session.presence_active = False
                if session.channel is not None:
                    changed_channels[session.channel.code] = session.channel
        for channel in changed_channels.values():
            self.broadcast_channel_state(channel)
        return tuple(changed_channels.values())

    def participant_count(self, channel: Channel) -> int:
        active_count = sum(
            session.presence_active for session in self.channel_sessions(channel)
        )
        return max(1, active_count)

    @staticmethod
    def floor_payload(channel: Channel, member: LogicalMember) -> dict[str, Any] | None:
        burst = channel.current_burst
        if burst is None:
            return None
        return {
            "burst_id": burst.burst_id,
            "burst_index": burst.index,
            "owned": burst.owner_member_id == member.member_id,
        }

    def send_ptt_ended(self, session: ClientSession, burst: TalkBurst) -> None:
        session.offer_json(
            {
                "type": "ptt_ended",
                "burst_id": burst.burst_id,
                "burst_index": burst.index,
                "state": burst.state,
                "reason": burst.seal_reason or burst.release_reason or "released",
                "final_next_sequence": burst.final_next_sequence,
            }
        )

    @staticmethod
    def send_uplink_ack(session: ClientSession, burst: TalkBurst) -> None:
        session.offer_json(
            {
                "type": "uplink_ack",
                "burst_id": burst.burst_id,
                "next_sequence": burst.resolved_next_sequence,
            }
        )

    def channel_sessions(self, channel: Channel) -> list[ClientSession]:
        recipients = []
        for member in channel.members.values():
            recipient = self.sessions.get(member.transport_id or "")
            if recipient is not None and recipient.authoritative and not recipient.closing:
                recipients.append(recipient)
        return recipients

    def session_for_member(self, channel: Channel, member_id: str) -> ClientSession | None:
        member = channel.members.get(member_id)
        return self.sessions.get(member.transport_id or "") if member is not None else None

    @staticmethod
    def require_keys(message: dict[str, Any], keys: set[str]) -> None:
        if set(message) != keys:
            raise ValueError

    @staticmethod
    def request_id(value: Any) -> str:
        return string(value, 128)

    @staticmethod
    def burst_id(value: Any) -> str:
        if not isinstance(value, str):
            raise ValueError
        normalized = str(uuid.UUID(value))
        if normalized != value:
            raise ValueError
        return normalized

    @classmethod
    def optional_burst_id(cls, value: Any) -> str:
        try:
            return cls.burst_id(value)
        except ValueError:
            return "00000000-0000-0000-0000-000000000000"

    @staticmethod
    def optional_sequence(value: Any) -> int:
        return value if type(value) is int and 0 <= value <= 0xFFFFFFFF else 0

    @staticmethod
    def require_channel(session: ClientSession) -> Channel:
        if session.channel is None:
            from .domain import NotJoined

            raise NotJoined
        return session.channel

    @staticmethod
    def require_member(session: ClientSession) -> LogicalMember:
        if session.member is None or not session.authoritative:
            from .domain import NotJoined

            raise NotJoined
        return session.member

    @staticmethod
    def send_error(session: ClientSession, code: str, operation: str = "unknown") -> None:
        logger.info(
            "protocol_error session=%s operation=%s code=%s",
            session.session_id,
            operation,
            code,
        )
        session.offer_json(
            {"type": "error", "code": code, "message": PUBLIC_MESSAGES.get(code, "Server error")}
        )

    @staticmethod
    def channel_reference(code: str) -> str:
        return hashlib.sha256(code.encode("utf-8")).hexdigest()[:8]

    @property
    def initial_floor_lease_ms(self) -> int:
        return round(INITIAL_FLOOR_LEASE_SECONDS * 1000)

    @property
    def floor_lease_ms(self) -> int:
        return round(FLOOR_LEASE_SECONDS * 1000)
