import asyncio
import json
import logging
import time

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.client_session import ClientSession
from app.main import create_app
from app.echo import ECHO_CONTROL_SUBPROTOCOL
from app.session import SessionManager
from app.settings import Settings


def connect_control(client: TestClient):
    return client.websocket_connect(
        "/internal/echo/control", subprotocols=[ECHO_CONTROL_SUBPROTOCOL]
    )


def receive_type(socket, wanted: str) -> dict:
    while True:
        message = socket.receive_json()
        if message["type"] == wanted:
            return message


def test_echo_waits_for_an_assigned_bot_and_reports_two_participants() -> None:
    app = create_app()
    with TestClient(app) as client:
        with connect_control(client) as control:
            control.send_json({"type": "hello", "capacity": 16})
            with client.websocket_connect("/ws", subprotocols=["zenptt.v4"]) as user:
                user.send_json({"type": "join_echo"})
                assignment = control.receive_json()
                assert set(assignment) == {"type", "assignment_id", "ticket"}
                assert assignment["type"] == "assign"
                manager = app.state.session_manager
                user_session = next(
                    session
                    for session in manager.sessions.values()
                    if session.member is not None and session.member.role == "client"
                )
                assert not user_session.outbound_queue

                with client.websocket_connect("/ws", subprotocols=["zenptt.v4"]) as bot:
                    bot.send_json(
                        {"type": "join_echo_bot", "ticket": assignment["ticket"]}
                    )
                    bot_snapshot = bot.receive_json()
                    user_snapshot = user.receive_json()
                    assert bot_snapshot["channel"] == "ECHO"
                    assert user_snapshot["channel"] == "ECHO"
                    assert bot_snapshot["participant_count"] == 2
                    assert user_snapshot["participant_count"] == 2


def test_echo_creates_a_private_channel_for_each_user() -> None:
    app = create_app()
    with TestClient(app) as client:
        with connect_control(client) as control:
            control.send_json({"type": "hello", "capacity": 16})
            with (
                client.websocket_connect("/ws", subprotocols=["zenptt.v4"]) as first,
                client.websocket_connect("/ws", subprotocols=["zenptt.v4"]) as second,
            ):
                first.send_json({"type": "join_echo"})
                second.send_json({"type": "join_echo"})
                assignments = [control.receive_json(), control.receive_json()]
                assert all(message["type"] == "assign" for message in assignments)
                keys = {
                    session.member.channel_key
                    for session in app.state.session_manager.sessions.values()
                    if session.member is not None and session.member.role == "client"
                }
                assert len(keys) == 2
                assert all(key.startswith("ECHO:") for key in keys)


def test_echo_rejects_ordinary_join_and_reused_ticket() -> None:
    app = create_app()
    with TestClient(app) as client:
        with client.websocket_connect("/ws", subprotocols=["zenptt.v4"]) as socket:
            socket.send_json({"type": "join", "channel": "ECHO"})
            assert socket.receive_json()["code"] == "invalid_channel"

        with connect_control(client) as control:
            control.send_json({"type": "hello", "capacity": 16})
            with client.websocket_connect("/ws", subprotocols=["zenptt.v4"]) as user:
                user.send_json({"type": "join_echo"})
                assignment = control.receive_json()
                with client.websocket_connect("/ws", subprotocols=["zenptt.v4"]) as bot:
                    bot.send_json(
                        {"type": "join_echo_bot", "ticket": assignment["ticket"]}
                    )
                    assert bot.receive_json()["participant_count"] == 2
                    user.receive_json()
                with client.websocket_connect("/ws", subprotocols=["zenptt.v4"]) as duplicate:
                    duplicate.send_json(
                        {"type": "join_echo_bot", "ticket": assignment["ticket"]}
                    )
                    assert duplicate.receive_json()["code"] == "invalid_message"


def test_echo_failed_assignment_is_reassigned() -> None:
    app = create_app()
    with TestClient(app) as client:
        with connect_control(client) as control:
            control.send_json({"type": "hello", "capacity": 16})
            with client.websocket_connect("/ws", subprotocols=["zenptt.v4"]) as user:
                user.send_json({"type": "join_echo"})
                first = control.receive_json()
                control.send_json(
                    {"type": "assignment_failed", "assignment_id": first["assignment_id"]}
                )
                replacement = control.receive_json()
                assert replacement["assignment_id"] != first["assignment_id"]
                assert replacement["ticket"] != first["ticket"]


def test_echo_ticket_expires_through_cleanup_without_logging_secrets(caplog) -> None:
    app = create_app()
    manager = app.state.session_manager
    clock = [0]
    manager.now_ms = lambda: clock[0]
    with caplog.at_level(logging.INFO, logger="zenptt.server"), TestClient(app) as client:
        with connect_control(client) as control:
            control.send_json({"type": "hello", "capacity": 1})
            with client.websocket_connect("/ws", subprotocols=["zenptt.v4"]) as user:
                user.send_json({"type": "join_echo"})
                first = receive_type(control, "assign")
                # Run the production cleanup task against an injected clock; do not edit tickets.
                clock[0] = 5_000
                replacement = receive_type(control, "assign")
                assert replacement["assignment_id"] != first["assignment_id"]
                assert replacement["ticket"] != first["ticket"]
                user.send_json({"type": "ping", "id": 1, "sent_at_ms": 5_000})
                receive_type(user, "pong")
                with client.websocket_connect("/ws", subprotocols=["zenptt.v4"]) as old_bot:
                    old_bot.send_json({"type": "join_echo_bot", "ticket": first["ticket"]})
                    assert receive_type(old_bot, "error")["code"] == "invalid_message"
                with client.websocket_connect("/ws", subprotocols=["zenptt.v4"]) as bot:
                    bot.send_json({"type": "join_echo_bot", "ticket": replacement["ticket"]})
                    assert receive_type(bot, "snapshot")["participant_count"] == 2
                    assert receive_type(user, "snapshot")["participant_count"] == 2
    assert first["ticket"] not in caplog.text
    assert replacement["ticket"] not in caplog.text


def test_echo_status_tracks_controller() -> None:
    app = create_app()
    with TestClient(app) as client:
        assert client.get("/internal/echo/status").json()["status"] == "unavailable"
        with connect_control(client) as control:
            control.send_json({"type": "hello", "capacity": 3})
            deadline = time.monotonic() + 1
            while time.monotonic() < deadline:
                status = client.get("/internal/echo/status").json()
                if status["controller_connected"]:
                    break
            assert status == {
                "status": "ready",
                "controller_connected": True,
                "capacity": 3,
                "active": 0,
                "pending": 0,
            }


def test_echo_intentional_bot_loss_blocks_ptt_and_assigns_replacement() -> None:
    app = create_app()
    with TestClient(app) as client:
        with connect_control(client) as control:
            control.send_json({"type": "hello", "capacity": 16})
            with client.websocket_connect("/ws", subprotocols=["zenptt.v4"]) as user:
                user.send_json({"type": "join_echo"})
                first_assignment = control.receive_json()
                with client.websocket_connect("/ws", subprotocols=["zenptt.v4"]) as bot:
                    bot.send_json(
                        {"type": "join_echo_bot", "ticket": first_assignment["ticket"]}
                    )
                    receive_type(bot, "snapshot")
                    receive_type(user, "snapshot")
                    receive_type(user, "channel_state")
                    bot.send_json({"type": "disconnect"})

                unavailable = receive_type(user, "channel_state")
                assert unavailable["participant_count"] == 1
                user.send_json({"type": "ptt_request", "request_id": "blocked"})
                denied = receive_type(user, "ptt_denied")
                assert denied == {
                    "type": "ptt_denied",
                    "request_id": "blocked",
                    "reason": "invalid_state",
                }

                replacement = receive_type(control, "assign")
                assert replacement["assignment_id"] != first_assignment["assignment_id"]
                with client.websocket_connect("/ws", subprotocols=["zenptt.v4"]) as bot:
                    bot.send_json(
                        {"type": "join_echo_bot", "ticket": replacement["ticket"]}
                    )
                    assert receive_type(bot, "snapshot")["participant_count"] == 2
                    assert receive_type(user, "snapshot")["participant_count"] == 2


def test_echo_capacity_keeps_excess_users_pending() -> None:
    app = create_app()
    with TestClient(app) as client:
        with connect_control(client) as control:
            control.send_json({"type": "hello", "capacity": 1})
            with (
                client.websocket_connect("/ws", subprotocols=["zenptt.v4"]) as first,
                client.websocket_connect("/ws", subprotocols=["zenptt.v4"]) as second,
            ):
                first.send_json({"type": "join_echo"})
                assignment = receive_type(control, "assign")
                second.send_json({"type": "join_echo"})
                manager = app.state.session_manager
                assert len(manager.echo.assignments) == 1
                assert len(manager.echo.pending) == 1
                assert assignment["ticket"].encode() not in manager.echo.tickets


class ControlSocket:
    scope = {"subprotocols": [ECHO_CONTROL_SUBPROTOCOL]}

    def __init__(self) -> None:
        self.received = asyncio.Queue()
        self.sent = asyncio.Queue()
        self.close_codes: list[int] = []
        self.received.put_nowait({"type": "hello", "capacity": 1})

    async def accept(self, *, subprotocol: str) -> None:
        assert subprotocol == ECHO_CONTROL_SUBPROTOCOL

    async def receive_text(self) -> str:
        message = await self.received.get()
        if message is None:
            raise WebSocketDisconnect(1001)
        return json.dumps(message)

    async def send_json(self, message: dict) -> None:
        self.sent.put_nowait(message)

    async def close(self, *, code: int) -> None:
        self.close_codes.append(code)
        self.received.put_nowait(None)


@pytest.mark.asyncio
async def test_bot_admission_sends_snapshot_to_the_resumed_client_transport() -> None:
    manager = SessionManager(Settings())
    control = ControlSocket()
    task = asyncio.create_task(manager.echo.handle_control(control))
    user = ClientSession(ControlSocket())
    manager.sessions[user.session_id] = user
    try:
        await manager.echo.join(user)
        assignment = await asyncio.wait_for(control.sent.get(), 1)
        resumed = ClientSession(ControlSocket())
        manager.sessions[resumed.session_id] = resumed
        await manager.resume(resumed, user.resume_token, 2)
        assert not user.authoritative
        bot = ClientSession(ControlSocket())
        manager.sessions[bot.session_id] = bot
        await manager.echo.join_bot(bot, assignment["ticket"])
        assert not user.outbound_queue
        snapshots = [item[1] for item in resumed.outbound_queue if item[1]["type"] == "snapshot"]
        assert [message["participant_count"] for message in snapshots] == [1, 2]
        assert all(message["generation"] == 2 for message in snapshots)
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)


@pytest.mark.asyncio
async def test_supervisor_replacement_during_bot_join_revokes_old_ticket(monkeypatch) -> None:
    manager = SessionManager(Settings())
    old_control = ControlSocket()
    tasks = [asyncio.create_task(manager.echo.handle_control(old_control))]
    user = ClientSession(ControlSocket())
    bot = ClientSession(ControlSocket())
    manager.sessions.update({user.session_id: user, bot.session_id: bot})
    entered = asyncio.Event()
    release = asyncio.Event()
    join_existing = manager.registry.join_existing_echo

    async def paused_join(*args):
        entered.set()
        await release.wait()
        return await join_existing(*args)

    try:
        await manager.echo.join(user)
        first = await asyncio.wait_for(old_control.sent.get(), 1)
        monkeypatch.setattr(manager.registry, "join_existing_echo", paused_join)
        joining = asyncio.create_task(manager.echo.join_bot(bot, first["ticket"]))
        tasks.append(joining)
        await asyncio.wait_for(entered.wait(), 1)
        replacement = ControlSocket()
        tasks.append(asyncio.create_task(manager.echo.handle_control(replacement)))
        second = await asyncio.wait_for(replacement.sent.get(), 1)
        assert old_control.close_codes == [1001]
        assert second["assignment_id"] != first["assignment_id"]
        release.set()
        with pytest.raises(ValueError):
            await asyncio.wait_for(joining, 1)
        assert bot.member is None
        assert bot.channel is None
        assert bot.resume_token is None
        assert not bot.outbound_queue
        assert not user.outbound_queue
        assert len(user.channel.members) == 1
        await manager.echo.join_bot(bot, second["ticket"])
        assert [item[1]["type"] for item in bot.outbound_queue] == ["snapshot", "channel_state"]
        assert [item[1]["type"] for item in user.outbound_queue] == ["snapshot", "channel_state"]
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)


@pytest.mark.asyncio
async def test_client_departure_revokes_assignment_and_closes_bot() -> None:
    manager = SessionManager(Settings())
    control = ControlSocket()
    task = asyncio.create_task(manager.echo.handle_control(control))
    user = ClientSession(ControlSocket())
    bot = ClientSession(ControlSocket())
    manager.sessions.update({user.session_id: user, bot.session_id: bot})
    try:
        await manager.echo.join(user)
        assignment = await asyncio.wait_for(control.sent.get(), 1)
        await manager.echo.join_bot(bot, assignment["ticket"])
        user.intentional_close = True
        await manager.disconnect(user)
        assert await asyncio.wait_for(control.sent.get(), 1) == {
            "type": "revoke", "assignment_id": assignment["assignment_id"],
        }
        assert bot.websocket.close_codes == [1000]
        assert not manager.echo.assignments
        assert not manager.echo.tickets
        assert not manager.echo.pending
        assert all(not channel.members for channel in manager.registry.channels.values())
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
