"""Runs logically isolated Echo bots assigned by the ZenPTT server."""

from __future__ import annotations

import asyncio
from contextlib import suppress
import json
import logging
import os
import signal
import uuid

from websockets.asyncio.client import connect

from zenptt_headless import (
    MAX_MESSAGE_BYTES,
    BotConfig,
    ClientConfig,
    HeadlessClient,
    ShutdownWatchdog,
    run_bot_session,
)

from .echo_handler import make_echo_handler

logger = logging.getLogger("zenptt.echo")
CONTROL_SUBPROTOCOL = "zenptt.echo-control.v1"
MAX_CAPACITY = 16
SESSION_PCM_BYTES = 4 * 1024 * 1024


class EchoSupervisor:
    def __init__(self, server_url: str, control_url: str, capacity: int = MAX_CAPACITY) -> None:
        if not server_url.startswith(("ws://", "wss://")):
            raise ValueError("Server URL must use ws:// or wss://")
        if not control_url.startswith(("ws://", "wss://")):
            raise ValueError("Control URL must use ws:// or wss://")
        if type(capacity) is not int or not 1 <= capacity <= MAX_CAPACITY:
            raise ValueError("Echo capacity must be from 1 through 16")
        self.server_url = server_url
        self.control_url = control_url
        self.capacity = capacity
        self.stop_event = asyncio.Event()
        self.sessions: dict[str, asyncio.Task[None]] = {}
        self.failures: asyncio.Queue[str] = asyncio.Queue()

    def request_stop(self) -> None:
        self.stop_event.set()

    async def run(self) -> None:
        delay = 0.0
        while not self.stop_event.is_set():
            if delay:
                try:
                    await asyncio.wait_for(self.stop_event.wait(), delay)
                    break
                except asyncio.TimeoutError:
                    pass
            try:
                async with connect(
                    self.control_url,
                    subprotocols=[CONTROL_SUBPROTOCOL],
                    max_size=MAX_MESSAGE_BYTES,
                    open_timeout=5,
                    ping_interval=20,
                    ping_timeout=60,
                ) as socket:
                    await socket.send(json.dumps({"type": "hello", "capacity": self.capacity}))
                    logger.info("controller_ready capacity=%d", self.capacity)
                    delay = 0.0
                    await self._serve(socket)
            except asyncio.CancelledError:
                raise
            except Exception as error:
                logger.warning("controller_lost error=%s", type(error).__name__)
                delay = min(5.0, 0.25 if not delay else delay * 2)
            finally:
                await self._stop_sessions()

    async def _serve(self, socket) -> None:
        while not self.stop_event.is_set():
            receive_task = asyncio.create_task(socket.recv())
            failure_task = asyncio.create_task(self.failures.get())
            stop_task = asyncio.create_task(self.stop_event.wait())
            done, pending = await asyncio.wait(
                {receive_task, failure_task, stop_task},
                return_when=asyncio.FIRST_COMPLETED,
            )
            for task in pending:
                task.cancel()
            await asyncio.gather(*pending, return_exceptions=True)
            if stop_task in done:
                return
            if failure_task in done:
                assignment_id = failure_task.result()
                await socket.send(
                    json.dumps(
                        {"type": "assignment_failed", "assignment_id": assignment_id}
                    )
                )
                continue
            self._handle_message(receive_task.result())

    def _handle_message(self, raw: str | bytes) -> None:
        if not isinstance(raw, str) or len(raw.encode("utf-8")) > MAX_MESSAGE_BYTES:
            raise ValueError("Invalid Echo control message")
        message = json.loads(raw)
        if not isinstance(message, dict) or not isinstance(message.get("type"), str):
            raise ValueError("Invalid Echo control message")
        kind = message["type"]
        if kind == "assign" and set(message) == {"type", "assignment_id", "ticket"}:
            assignment_id = self._uuid(message["assignment_id"])
            ticket = message["ticket"]
            if not isinstance(ticket, str) or not 1 <= len(ticket) <= 256:
                raise ValueError("Invalid Echo ticket")
            if assignment_id in self.sessions or len(self.sessions) >= self.capacity:
                raise ValueError("Invalid Echo assignment")
            task = asyncio.create_task(self._run_session(ticket))
            self.sessions[assignment_id] = task
            task.add_done_callback(
                lambda completed, key=assignment_id: self._session_finished(key, completed)
            )
            logger.info("assignment_started id=%s active=%d", assignment_id, len(self.sessions))
            return
        if kind == "revoke" and set(message) == {"type", "assignment_id"}:
            assignment_id = self._uuid(message["assignment_id"])
            task = self.sessions.pop(assignment_id, None)
            if task is not None:
                task.cancel()
            logger.info("assignment_revoked id=%s active=%d", assignment_id, len(self.sessions))
            return
        raise ValueError("Invalid Echo control message")

    async def _run_session(self, ticket: str) -> None:
        client_config = ClientConfig(server_url=self.server_url, channel="ECHO")
        config = BotConfig(
            client=client_config,
            receive_queue_size=2,
            send_queue_size=2,
            send_queue_bytes=SESSION_PCM_BYTES,
        )
        client = HeadlessClient(client_config, echo_ticket=ticket)
        await run_bot_session(config, make_echo_handler(), client)

    def _session_finished(self, assignment_id: str, task: asyncio.Task[None]) -> None:
        if self.sessions.get(assignment_id) is not task:
            return
        self.sessions.pop(assignment_id, None)
        if task.cancelled() or self.stop_event.is_set():
            return
        with suppress(Exception):
            task.result()
        self.failures.put_nowait(assignment_id)
        logger.warning("assignment_failed id=%s active=%d", assignment_id, len(self.sessions))

    async def _stop_sessions(self) -> None:
        tasks = list(self.sessions.values())
        self.sessions.clear()
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        while not self.failures.empty():
            with suppress(asyncio.QueueEmpty):
                self.failures.get_nowait()

    @staticmethod
    def _uuid(value: object) -> str:
        if not isinstance(value, str) or str(uuid.UUID(value)) != value:
            raise ValueError("Invalid assignment ID")
        return value


async def _run_supervisor(supervisor: EchoSupervisor, watchdog: ShutdownWatchdog) -> None:
    loop = asyncio.get_running_loop()
    previous: list[tuple[signal.Signals, signal.Handlers]] = []

    def receive_signal(_signum, _frame) -> None:
        watchdog.start()
        loop.call_soon_threadsafe(supervisor.request_stop)

    try:
        for signum in (signal.SIGINT, signal.SIGTERM):
            try:
                handler = signal.getsignal(signum)
                signal.signal(signum, receive_signal)
                previous.append((signum, handler))
            except ValueError:
                pass
        await supervisor.run()
    finally:
        for signum, handler in previous:
            signal.signal(signum, handler)


def main() -> None:
    level = os.environ.get("ZENPTT_ECHO_LOG_LEVEL", "INFO").upper()
    logging.basicConfig(level=level, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    supervisor = EchoSupervisor(
        server_url=os.environ["ZENPTT_SERVER_URL"],
        control_url=os.environ["ZENPTT_ECHO_CONTROL_URL"],
        capacity=int(os.environ.get("ZENPTT_ECHO_MAX_SESSIONS", str(MAX_CAPACITY))),
    )
    watchdog = ShutdownWatchdog()
    try:
        asyncio.run(_run_supervisor(supervisor, watchdog))
    finally:
        watchdog.cancel()


if __name__ == "__main__":
    main()
