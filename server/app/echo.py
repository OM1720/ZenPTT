"""Owns private ECHO assignments, admission tickets, and supervisor lifecycle."""

from __future__ import annotations

import asyncio
from collections.abc import Callable, Mapping
import hashlib
import json
import logging
import secrets
from typing import Any
import uuid

from fastapi import WebSocket, WebSocketDisconnect

from .client_session import ClientSession
from .domain import ECHO_CHANNEL, Channel, ChannelRegistry, LogicalMember
from .protocol import integer, string

logger = logging.getLogger("zenptt.server")

ECHO_CONTROL_SUBPROTOCOL = "zenptt.echo-control.v1"
ECHO_TICKET_TTL_MS = 5_000
ECHO_MAX_CAPACITY = 16


class EchoAssignment:
    def __init__(
        self,
        assignment_id: str,
        channel_key: str,
        client_member_id: str,
        ticket_digest: bytes,
        expires_at_ms: int,
        controller_id: str,
    ) -> None:
        self.assignment_id = assignment_id
        self.channel_key = channel_key
        self.client_member_id = client_member_id
        self.ticket_digest = ticket_digest
        self.expires_at_ms = expires_at_ms
        self.controller_id = controller_id
        self.bot_member_id: str | None = None


class EchoCoordinator:
    def __init__(
        self,
        registry: ChannelRegistry,
        sessions: Mapping[str, ClientSession],
        now_ms: Callable[[], int],
        snapshot: Callable[[ClientSession], dict[str, Any]],
        broadcast_channel_state: Callable[[Channel], None],
    ) -> None:
        self.registry = registry
        self.sessions = sessions
        self.now_ms = now_ms
        self.snapshot = snapshot
        self.broadcast_channel_state = broadcast_channel_state
        self.controller: WebSocket | None = None
        self.controller_id: str | None = None
        self.capacity = 0
        self.pending: dict[str, str] = {}
        self.assignments: dict[str, EchoAssignment] = {}
        self.tickets: dict[bytes, str] = {}
        self.lock = asyncio.Lock()
        self.send_lock = asyncio.Lock()

    async def join(self, session: ClientSession) -> None:
        if session.channel is not None or session.member is not None:
            raise ValueError
        channel, member, token = await self.registry.fresh_join(
            ECHO_CHANNEL, session.session_id, self.now_ms()
        )
        session.channel = channel
        session.member = member
        session.resume_token = token
        async with self.lock:
            self.pending[member.channel_key] = member.member_id
        await self.offer_assignments()
        logger.info("join_echo session=%s", session.session_id)

    async def join_bot(self, session: ClientSession, ticket: str) -> None:
        if session.channel is not None or session.member is not None:
            raise ValueError
        digest = hashlib.sha256(ticket.encode("utf-8")).digest()
        async with self.lock:
            assignment_id = self.tickets.pop(digest, None)
            assignment = self.assignments.get(assignment_id or "")
            if (
                assignment is None
                or assignment.ticket_digest != digest
                or assignment.expires_at_ms <= self.now_ms()
                or assignment.controller_id != self.controller_id
                or assignment.bot_member_id is not None
            ):
                raise ValueError
        channel, member, token = await self.registry.join_existing_echo(
            assignment.channel_key, session.session_id, self.now_ms()
        )
        session.channel = channel
        session.member = member
        session.resume_token = token
        async with self.lock:
            current = self.assignments.get(assignment.assignment_id)
            if (
                current is not assignment
                or assignment.controller_id != self.controller_id
                or assignment.bot_member_id is not None
            ):
                await self.registry.remove_member(member, self.now_ms())
                session.channel = None
                session.member = None
                session.resume_token = None
                raise ValueError
            assignment.bot_member_id = member.member_id
        client = self.session_for_member(channel, assignment.client_member_id)
        if client is None:
            await self.registry.remove_member(member, self.now_ms())
            raise ValueError
        session.offer_json(self.snapshot(session))
        client.offer_json(self.snapshot(client))
        self.broadcast_channel_state(channel)
        logger.info("join_echo_bot assignment=%s", assignment.assignment_id)

    async def handle_control(self, websocket: WebSocket) -> None:
        if ECHO_CONTROL_SUBPROTOCOL not in websocket.scope.get("subprotocols", []):
            await websocket.close(code=1002)
            return
        await websocket.accept(subprotocol=ECHO_CONTROL_SUBPROTOCOL)
        controller_id = str(uuid.uuid4())
        try:
            raw = await asyncio.wait_for(websocket.receive_text(), 5)
            message = json.loads(raw)
            if set(message) != {"type", "capacity"} or message.get("type") != "hello":
                raise ValueError
            capacity = integer(message["capacity"], 1, ECHO_MAX_CAPACITY)
            previous: WebSocket | None
            previous_id: str | None
            async with self.lock:
                previous = self.controller
                previous_id = self.controller_id
                self.controller = websocket
                self.controller_id = controller_id
                self.capacity = capacity
            if previous is not None and previous is not websocket:
                await previous.close(code=1001)
            if previous_id is not None:
                await self.reset_assignments(previous_id)
            logger.info("echo_controller_ready capacity=%d", capacity)
            await self.offer_assignments()
            while True:
                raw = await websocket.receive_text()
                message = json.loads(raw)
                if (
                    set(message) != {"type", "assignment_id"}
                    or message.get("type") != "assignment_failed"
                ):
                    raise ValueError
                assignment_id = string(message["assignment_id"], 64)
                await self.fail_assignment(assignment_id, controller_id)
        except (WebSocketDisconnect, asyncio.TimeoutError, ValueError, TypeError, json.JSONDecodeError):
            pass
        finally:
            async with self.lock:
                current = self.controller_id == controller_id
                if current:
                    self.controller = None
                    self.controller_id = None
                    self.capacity = 0
            if current:
                await self.reset_assignments(controller_id)
                logger.info("echo_controller_lost")

    async def offer_assignments(self) -> None:
        offered: list[tuple[str, str]] = []
        async with self.lock:
            controller = self.controller
            controller_id = self.controller_id
            available = self.capacity - len(self.assignments)
            if controller is None or controller_id is None or available <= 0:
                return
            for channel_key, member_id in list(self.pending.items())[:available]:
                self.pending.pop(channel_key, None)
                assignment_id = str(uuid.uuid4())
                ticket = secrets.token_urlsafe(32)
                digest = hashlib.sha256(ticket.encode("utf-8")).digest()
                assignment = EchoAssignment(
                    assignment_id,
                    channel_key,
                    member_id,
                    digest,
                    self.now_ms() + ECHO_TICKET_TTL_MS,
                    controller_id,
                )
                self.assignments[assignment_id] = assignment
                self.tickets[digest] = assignment_id
                offered.append((assignment_id, ticket))
        try:
            async with self.send_lock:
                for assignment_id, ticket in offered:
                    await controller.send_json(
                        {"type": "assign", "assignment_id": assignment_id, "ticket": ticket}
                    )
        except Exception:
            await controller.close(code=1011)

    async def fail_assignment(self, assignment_id: str, controller_id: str) -> None:
        bot_session: ClientSession | None = None
        bot_member: LogicalMember | None = None
        async with self.lock:
            assignment = self.assignments.get(assignment_id)
            if assignment is None or assignment.controller_id != controller_id:
                return
            self.assignments.pop(assignment_id, None)
            self.tickets.pop(assignment.ticket_digest, None)
            self.pending[assignment.channel_key] = assignment.client_member_id
            if assignment.bot_member_id is not None:
                bot_session = self.session_for_member_id(assignment.bot_member_id)
                bot_member = bot_session.member if bot_session is not None else None
        if bot_member is not None:
            await self.registry.remove_member(bot_member, self.now_ms())
        if bot_session is not None:
            await bot_session.close(code=1011)
        await self.offer_assignments()

    async def reset_assignments(self, controller_id: str) -> None:
        sessions: list[ClientSession] = []
        members: list[LogicalMember] = []
        async with self.lock:
            assignments = [
                assignment
                for assignment in self.assignments.values()
                if assignment.controller_id == controller_id
            ]
            for assignment in assignments:
                self.assignments.pop(assignment.assignment_id, None)
                self.tickets.pop(assignment.ticket_digest, None)
                self.pending[assignment.channel_key] = assignment.client_member_id
                if assignment.bot_member_id is not None:
                    session = self.session_for_member_id(assignment.bot_member_id)
                    if session is not None and session.member is not None:
                        sessions.append(session)
                        members.append(session.member)
        for member in members:
            await self.registry.remove_member(member, self.now_ms())
        for session in sessions:
            await session.close(code=1001)

    async def expire_assignments(self, now_ms: int) -> None:
        expired: list[str] = []
        async with self.lock:
            expired = [
                assignment.assignment_id
                for assignment in self.assignments.values()
                if assignment.bot_member_id is None and assignment.expires_at_ms <= now_ms
            ]
        for assignment_id in expired:
            controller_id = self.controller_id
            if controller_id is not None:
                await self.fail_assignment(assignment_id, controller_id)

    async def reconcile(self) -> None:
        async with self.registry.lock:
            member_ids = {
                member.member_id
                for channel in self.registry.channels.values()
                for member in channel.members.values()
            }
        revocations: list[tuple[str, ClientSession | None, LogicalMember | None]] = []
        requeued = False
        async with self.lock:
            for channel_key, member_id in list(self.pending.items()):
                if member_id not in member_ids:
                    self.pending.pop(channel_key, None)
            for assignment_id, assignment in list(self.assignments.items()):
                if assignment.client_member_id not in member_ids:
                    self.assignments.pop(assignment_id, None)
                    self.tickets.pop(assignment.ticket_digest, None)
                    session = self.session_for_member_id(assignment.bot_member_id)
                    revocations.append(
                        (assignment_id, session, session.member if session is not None else None)
                    )
                elif assignment.bot_member_id is not None and assignment.bot_member_id not in member_ids:
                    self.assignments.pop(assignment_id, None)
                    self.tickets.pop(assignment.ticket_digest, None)
                    self.pending[assignment.channel_key] = assignment.client_member_id
                    requeued = True
        for assignment_id, session, member in revocations:
            await self.send_revoke(assignment_id)
            if member is not None:
                await self.registry.remove_member(member, self.now_ms())
            if session is not None:
                await session.close(code=1000)
        if revocations or requeued:
            await self.offer_assignments()

    async def send_revoke(self, assignment_id: str) -> None:
        controller = self.controller
        if controller is None:
            return
        try:
            async with self.send_lock:
                await controller.send_json({"type": "revoke", "assignment_id": assignment_id})
        except Exception:
            await controller.close(code=1011)

    def status(self) -> dict[str, int | bool | str]:
        connected = self.controller is not None
        return {
            "status": "ready" if connected else "unavailable",
            "controller_connected": connected,
            "capacity": self.capacity,
            "active": sum(
                assignment.bot_member_id is not None
                for assignment in self.assignments.values()
            ),
            "pending": len(self.pending),
        }

    def session_for_member(self, channel: Channel, member_id: str) -> ClientSession | None:
        member = channel.members.get(member_id)
        return self.sessions.get(member.transport_id or "") if member is not None else None

    def session_for_member_id(self, member_id: str | None) -> ClientSession | None:
        if member_id is None:
            return None
        return next(
            (
                session
                for session in self.sessions.values()
                if session.member is not None and session.member.member_id == member_id
            ),
            None,
        )
