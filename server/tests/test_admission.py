import asyncio
from types import SimpleNamespace

import pytest

from app.session import SessionManager
from app.settings import Settings


class Socket:
    def __init__(self, host="one", *, blocked=False, fail=False):
        self.scope = {"subprotocols": ["zenptt.v4"]}
        self.client = SimpleNamespace(host=host)
        self.entered = asyncio.Event()
        self.release = asyncio.Event()
        if not blocked:
            self.release.set()
        self.fail = fail
        self.close_codes = []

    async def accept(self, *, subprotocol):
        assert subprotocol == "zenptt.v4"
        self.entered.set()
        await self.release.wait()
        if self.fail:
            raise OSError("accept failed")

    async def close(self, *, code):
        self.close_codes.append(code)


async def cleanup(manager):
    for session in list(manager.sessions.values()):
        await manager.disconnect(session)
    if manager.cleanup_task is not None:
        manager.cleanup_task.cancel()
        await asyncio.gather(manager.cleanup_task, return_exceptions=True)


@pytest.mark.asyncio
@pytest.mark.parametrize("per_client", [False, True])
async def test_last_admission_slot_is_atomic_and_reusable(per_client):
    manager = SessionManager(Settings(
        max_active_sessions=2 if per_client else 1,
        max_active_sessions_per_client_ip=1,
    ))
    first = Socket(blocked=True)
    second = Socket("one" if per_client else "two")
    contender_started = asyncio.Event()

    async def contend():
        contender_started.set()
        return await manager.connect(second)

    tasks = []
    try:
        tasks.append(asyncio.create_task(manager.connect(first)))
        await asyncio.wait_for(first.entered.wait(), 1)
        tasks.append(asyncio.create_task(contend()))
        await asyncio.wait_for(contender_started.wait(), 1)
        assert not second.entered.is_set()
        first.release.set()
        accepted, rejected = await asyncio.wait_for(asyncio.gather(*tasks), 1)
        assert accepted is not None and rejected is None
        assert second.close_codes == [1013]
        assert len(manager.sessions) == 1
        if per_client:
            unrelated = await manager.connect(Socket("two"))
            assert unrelated is not None
            await manager.disconnect(unrelated)
        await manager.disconnect(accepted)
        assert manager.sessions_per_client == {}
        replacement = await manager.connect(Socket(second.client.host))
        assert replacement is not None
        assert len(manager.sessions) == 1
    finally:
        first.release.set()
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        await cleanup(manager)


@pytest.mark.asyncio
async def test_accept_failure_does_not_consume_admission_capacity():
    manager = SessionManager(Settings(
        max_active_sessions=1, max_active_sessions_per_client_ip=1,
    ))
    try:
        with pytest.raises(OSError, match="accept failed"):
            await manager.connect(Socket(fail=True))
        assert manager.sessions == {}
        assert manager.sessions_per_client == {}
        assert await manager.connect(Socket()) is not None
    finally:
        await cleanup(manager)
