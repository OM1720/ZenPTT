import asyncio
import logging

import pytest

from zenptt_headless.echo_supervisor import EchoSupervisor, SESSION_PCM_BYTES


def test_supervisor_validates_capacity_and_urls() -> None:
    EchoSupervisor("ws://server/ws", "ws://server/internal", 16)
    with pytest.raises(ValueError, match="capacity"):
        EchoSupervisor("ws://server/ws", "ws://server/internal", 17)
    with pytest.raises(ValueError, match="Control URL"):
        EchoSupervisor("ws://server/ws", "http://server/internal", 1)


@pytest.mark.asyncio
async def test_assign_and_revoke_manage_independent_sessions(monkeypatch) -> None:
    supervisor = EchoSupervisor("ws://server/ws", "ws://server/internal", 2)
    started = asyncio.Queue()
    blockers = {}

    async def run_session(ticket):
        blocker = asyncio.Event()
        blockers[ticket] = blocker
        await started.put(ticket)
        await blocker.wait()

    monkeypatch.setattr(supervisor, "_run_session", run_session)
    first = "11111111-1111-4111-8111-111111111111"
    second = "22222222-2222-4222-8222-222222222222"
    supervisor._handle_message(
        '{"type":"assign","assignment_id":"%s","ticket":"first-secret"}' % first
    )
    supervisor._handle_message(
        '{"type":"assign","assignment_id":"%s","ticket":"second-secret"}' % second
    )
    assert {await started.get(), await started.get()} == {"first-secret", "second-secret"}
    supervisor._handle_message(
        '{"type":"revoke","assignment_id":"%s"}' % first
    )
    await asyncio.sleep(0)
    assert first not in supervisor.sessions
    assert second in supervisor.sessions
    await supervisor._stop_sessions()


@pytest.mark.asyncio
async def test_failed_session_is_reported_without_ticket_in_logs(monkeypatch, caplog) -> None:
    supervisor = EchoSupervisor("ws://server/ws", "ws://server/internal", 1)

    async def fail(_ticket):
        raise RuntimeError("failed")

    monkeypatch.setattr(supervisor, "_run_session", fail)
    assignment = "11111111-1111-4111-8111-111111111111"
    with caplog.at_level(logging.INFO):
        supervisor._handle_message(
            '{"type":"assign","assignment_id":"%s","ticket":"never-log-this"}'
            % assignment
        )
        assert await asyncio.wait_for(supervisor.failures.get(), 1) == assignment
    assert "never-log-this" not in caplog.text


@pytest.mark.asyncio
async def test_session_uses_bounded_bot_configuration(monkeypatch) -> None:
    observed = {}

    async def verify(config, handler, client):
        observed["config"] = config
        observed["ticket"] = client._echo_ticket

    monkeypatch.setattr("zenptt_headless.echo_supervisor.run_bot_session", verify)
    supervisor = EchoSupervisor("ws://server/ws", "ws://server/internal", 1)
    await supervisor._run_session("ticket")
    assert observed["ticket"] == "ticket"
    assert observed["config"].receive_queue_size == 2
    assert observed["config"].send_queue_size == 2
    assert observed["config"].send_queue_bytes == SESSION_PCM_BYTES
