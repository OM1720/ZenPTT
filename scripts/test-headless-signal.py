"""Verify production standalone and library shutdown paths in the pinned Linux image."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import selectors
import signal
import subprocess
import sys
import time
import traceback
from collections.abc import Callable
from dataclasses import dataclass, field

import zenptt_headless.bot as bot_module
import zenptt_headless.echo_supervisor as echo_module
from zenptt_headless import BotConfig, ClientConfig, PcmAudio, ReceiveInterrupted, ReceivedBurst
import qrz_bot as qrz_module
import zenptt_headless.standalone as standalone_module

CHILD_STARTUP_SECONDS = 5.0

class FakeClient:
    emit_burst = False
    emit_session_loss = False
    handler_ready: asyncio.Event | None = None

    def __init__(self, _config=None) -> None:
        self.config = _config
        self.session_epoch = 0
        self.events = asyncio.Queue()
        self.loss_sent = False

    async def start(self) -> None:
        if self.emit_burst:
            await self.events.put(
                ReceivedBurst("source", 0, PcmAudio(b""), (), (), "complete")
            )
        else:
            print("RUNNER_READY", flush=True)

    async def receive(self):
        if self.emit_session_loss and self.events.empty() and not self.loss_sent:
            assert self.handler_ready is not None
            await self.handler_ready.wait()
            self.session_epoch = 1
            self.loss_sent = True
            return ReceiveInterrupted(None, None, "resume_rejected", 1)
        return await self.events.get()

    async def stop(self) -> None:
        pass


class FailingClient(FakeClient):
    async def start(self) -> None:
        raise RuntimeError("standalone start failure for diagnostics")


async def stuck_handler(_burst) -> None:
    if FakeClient.handler_ready is not None:
        FakeClient.handler_ready.set()
    print("HANDLER_READY", flush=True)
    while True:
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            continue


async def blocking_handler(_burst) -> None:
    print("HANDLER_BLOCKED", flush=True)
    while True:
        time.sleep(0.05)


def configure_standalone(
    stuck: bool,
    disable_watchdog: bool,
    *,
    fail_start: bool = False,
    session_loss: bool = False,
) -> None:
    FakeClient.emit_burst = stuck
    FakeClient.emit_session_loss = session_loss
    FakeClient.handler_ready = asyncio.Event() if session_loss else None
    bot_module.HeadlessClient = FailingClient if fail_start else FakeClient
    qrz_module.load_pcm = lambda _path: PcmAudio(bytes(640))
    qrz_module.make_qrz_handler = lambda _audio: stuck_handler
    qrz_module.BotConfig = lambda client: BotConfig(client, shutdown_seconds=0.05)
    if session_loss:
        bot_module.HANDLER_CANCEL_TIMEOUT_SECONDS = 0.05
    if disable_watchdog:
        standalone_module.ShutdownWatchdog.start = lambda _self: None
    os.environ["ZENPTT_SERVER_URL"] = "ws://test"


def run_standalone_child(
    stuck: bool,
    disable_watchdog: bool = False,
    *,
    fail_start: bool = False,
    session_loss: bool = False,
) -> None:
    configure_standalone(
        stuck,
        disable_watchdog,
        fail_start=fail_start,
        session_loss=session_loss,
    )
    try:
        qrz_module.main()
    except BaseException:
        traceback.print_exc()
        raise SystemExit(71) from None
    if stuck:
        raise SystemExit(70)


def run_echo_child(mode: str) -> None:
    """Exercise the real Echo entry point and sessions with a controlled transport."""
    ready = asyncio.Event()
    started = []

    class EchoClient(FakeClient):
        def __init__(self, config, *, echo_ticket):
            super().__init__(config)
            self.index = int(echo_ticket)

        async def start(self):
            started.append(self.index)
            if len(started) == 2:
                ready.set()
                print("ECHO_READY", flush=True)
            await ready.wait()
            if self.index == 1 and mode != "echo-clean":
                await self.events.put(
                    ReceivedBurst("source", 0, PcmAudio(bytes(640)), (), (), "complete")
                )

        async def stop(self):
            print(f"ECHO_STOPPED_{self.index}", flush=True)

    class ControlSocket:
        def __init__(self):
            self.assignments = iter((1, 2))

        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args):
            return False

        async def send(self, raw):
            assert json.loads(raw) == {"type": "hello", "capacity": 2}

        async def recv(self):
            index = next(self.assignments, None)
            if index is None:
                await asyncio.Event().wait()
            return json.dumps({
                "type": "assign",
                "assignment_id": f"00000000-0000-4000-8000-{index:012d}",
                "ticket": str(index),
            })

    echo_module.connect = lambda *_args, **_kwargs: ControlSocket()
    echo_module.HeadlessClient = EchoClient
    if mode == "echo-blocked":
        echo_module.make_echo_handler = lambda: blocking_handler
    elif mode == "echo-stuck":
        echo_module.make_echo_handler = lambda: stuck_handler
        echo_module.BotConfig = lambda **kwargs: BotConfig(**kwargs, shutdown_seconds=0.05)
    os.environ["ZENPTT_SERVER_URL"] = "ws://test"
    os.environ["ZENPTT_ECHO_CONTROL_URL"] = "ws://test/internal/echo/control"
    os.environ["ZENPTT_ECHO_MAX_SESSIONS"] = "2"
    echo_module.main()


async def run_library_child() -> None:
    FakeClient.emit_burst = True
    bot_module.HeadlessClient = FakeClient
    released = asyncio.Event()

    async def handler(_burst) -> None:
        print("HANDLER_READY", flush=True)
        while not released.is_set():
            try:
                await released.wait()
            except asyncio.CancelledError:
                continue

    config = BotConfig(ClientConfig("ws://test"), shutdown_seconds=0.05)
    try:
        await bot_module.run_bot(config, handler)
    except TimeoutError:
        print("LIBRARY_TIMEOUT", flush=True)
        released.set()
        await asyncio.sleep(0)
        return
    raise AssertionError("Public run_bot did not report its shutdown timeout")


async def run_library_signal_restore_child() -> None:
    loop = asyncio.get_running_loop()
    signals = (signal.SIGINT, signal.SIGTERM)
    original_signals = {signum: signal.getsignal(signum) for signum in signals}
    original_client = bot_module.HeadlessClient
    calls = []

    def previous_handler(signum, _frame):
        calls.append(signum)

    async def handler(_burst):
        return None

    try:
        for disposition in ("custom", "default", "ignored", "asyncio", "loop"):
            for outcome in ("normal", "start_error", "cancel", "timeout"):
                ready, release_cleanup, stopped = asyncio.Event(), asyncio.Event(), asyncio.Event()

                class Client(FakeClient):
                    async def start(self):
                        if outcome == "start_error":
                            raise RuntimeError("controlled startup failure")
                        ready.set()

                    async def stop(self):
                        if outcome == "timeout":
                            while not release_cleanup.is_set():
                                try:
                                    await release_cleanup.wait()
                                except asyncio.CancelledError:
                                    continue
                        stopped.set()

                bot_module.HeadlessClient = Client
                for signum in signals:
                    previous = {
                        "custom": previous_handler, "default": signal.SIG_DFL,
                        "ignored": signal.SIG_IGN, "asyncio": original_signals[signum],
                        "loop": previous_handler,
                    }[disposition]
                    signal.signal(signum, previous)
                    if disposition == "loop":
                        loop.add_signal_handler(signum, calls.append, signum)
                installed = {signum: signal.getsignal(signum) for signum in signals}
                task = asyncio.create_task(bot_module.run_bot(
                    BotConfig(ClientConfig("ws://test"), shutdown_seconds=0.03), handler,
                ))
                try:
                    if outcome != "start_error":
                        await asyncio.wait_for(ready.wait(), 1)
                        if outcome == "cancel":
                            task.cancel()
                        else:
                            os.kill(os.getpid(), signal.SIGTERM)
                    try:
                        await task
                    except TimeoutError:
                        assert outcome == "timeout"
                    except asyncio.CancelledError:
                        assert outcome == "cancel"
                    except RuntimeError as error:
                        assert outcome == "start_error" and str(error) == "controlled startup failure"
                    else:
                        assert outcome == "normal"
                    for signum in signals:
                        assert signal.getsignal(signum) == installed[signum], (
                            f"Signal handler lost: {disposition}/{outcome}/{signum.name}"
                        )
                    calls.clear()
                    if disposition in {"custom", "ignored", "loop"}:
                        for signum in signals:
                            os.kill(os.getpid(), signum)
                        await asyncio.sleep(0.02)
                        assert calls == ([] if disposition == "ignored" else list(signals))
                finally:
                    release_cleanup.set()
                    await asyncio.wait_for(stopped.wait(), 1)
                    if disposition == "loop":
                        for signum in signals:
                            loop.remove_signal_handler(signum)
        print("LIBRARY_SIGNALS_RESTORED", flush=True)
    finally:
        bot_module.HeadlessClient = original_client
        for signum, previous in original_signals.items():
            signal.signal(signum, previous)


async def run_library_session_child() -> None:
    FakeClient.emit_burst = True
    FakeClient.emit_session_loss = True
    FakeClient.handler_ready = asyncio.Event()
    bot_module.HeadlessClient = FakeClient
    bot_module.HANDLER_CANCEL_TIMEOUT_SECONDS = 0.05
    released = asyncio.Event()

    async def handler(_burst) -> None:
        assert FakeClient.handler_ready is not None
        FakeClient.handler_ready.set()
        print("HANDLER_READY", flush=True)
        while not released.is_set():
            try:
                await released.wait()
            except asyncio.CancelledError:
                continue

    config = BotConfig(ClientConfig("ws://test"), shutdown_seconds=0.05)
    try:
        await bot_module.run_bot(config, handler)
    except TimeoutError:
        print("LIBRARY_SESSION_TIMEOUT", flush=True)
        released.set()
        await asyncio.sleep(0)
        return
    raise AssertionError("Session-loss handler timeout did not fail public run_bot")


@dataclass
class Child:
    process: subprocess.Popen
    selector: selectors.BaseSelector
    output: dict[str, bytearray] = field(
        default_factory=lambda: {"stdout": bytearray(), "stderr": bytearray()}
    )
    eof: set[str] = field(default_factory=set)

    def read_ready(self, timeout: float) -> None:
        for key, _ in self.selector.select(max(0.0, timeout)):
            stream_name = key.data
            try:
                chunk = os.read(key.fd, 65_536)
            except BlockingIOError:
                continue
            if not chunk:
                self.eof.add(stream_name)
                try:
                    self.selector.unregister(key.fileobj)
                except KeyError:
                    pass
                continue
            self.output[stream_name].extend(chunk)

    def completed_stdout(self) -> bytes:
        output = bytes(self.output["stdout"])
        if "stdout" in self.eof or self.process.poll() is not None:
            return output
        newline = output.rfind(b"\n")
        return output[: newline + 1] if newline >= 0 else b""

    def decoded(self, stream_name: str) -> str:
        return bytes(self.output[stream_name]).decode("utf-8", errors="replace")

    def close(self) -> None:
        self.selector.close()
        for pipe in (self.process.stdout, self.process.stderr):
            if pipe is not None:
                pipe.close()


def start_child(mode: str, startup_delay: float = 0) -> Child:
    process = subprocess.Popen(
        [sys.executable, __file__, "--child", mode, "--startup-delay", str(startup_delay)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=0,
    )
    selector = selectors.DefaultSelector()
    assert process.stdout is not None and process.stderr is not None
    for stream_name, pipe in (("stdout", process.stdout), ("stderr", process.stderr)):
        os.set_blocking(pipe.fileno(), False)
        selector.register(pipe, selectors.EVENT_READ, stream_name)
    return Child(process, selector)


def wait_for_marker(child: Child, marker: str, timeout: float = 5) -> None:
    deadline = time.monotonic() + timeout
    encoded_marker = marker.encode()
    while True:
        if any(encoded_marker in line for line in child.completed_stdout().splitlines()):
            return
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        child.read_ready(min(remaining, 0.05))
        if child.process.poll() is not None and len(child.eof) == 2:
            break
    raise AssertionError(f"Child did not publish {marker}; exit={child.process.poll()}")


def wait_for_exit(child: Child, timeout: float) -> int:
    deadline = time.monotonic() + timeout
    while True:
        returncode = child.process.poll()
        if returncode is not None and len(child.eof) == 2:
            return returncode
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise subprocess.TimeoutExpired(child.process.args, timeout)
        child.read_ready(min(remaining, 0.05))


def terminate_child(child: Child) -> tuple[str, str, str]:
    cleanup_error = ""
    try:
        if child.process.poll() is None:
            child.process.kill()
        wait_for_exit(child, 1)
    except BaseException as error:
        cleanup_error = f"{type(error).__name__}: {error}"
        try:
            if child.process.poll() is None:
                child.process.kill()
            wait_for_exit(child, 1)
        except BaseException as final_error:
            cleanup_error += f"; final cleanup: {type(final_error).__name__}: {final_error}"
    stdout = child.decoded("stdout")
    stderr = child.decoded("stderr")
    child.close()
    return stdout, stderr, cleanup_error


def verify_child(
    mode: str,
    expected: str,
    action: Callable[[Child], None],
    startup_delay: float = 0,
) -> tuple[str, str]:
    child = start_child(mode, startup_delay)
    started = time.monotonic()
    try:
        wait_for_marker(child, "CHILD_READY", timeout=CHILD_STARTUP_SECONDS)
        action(child)
    except BaseException as error:
        observed_exit = child.process.poll()
        stdout, stderr, cleanup_error = terminate_child(child)
        elapsed = time.monotonic() - started
        raise AssertionError(
            f"mode={mode} expected={expected} actual_exit={observed_exit} "
            f"cleanup_exit={child.process.poll()} elapsed={elapsed:.3f}s "
            f"error={type(error).__name__}: {error}\n"
            f"stdout:\n{stdout}\nstderr:\n{stderr}\ncleanup_error={cleanup_error or 'none'}"
        ) from error

    stdout, stderr, cleanup_error = terminate_child(child)
    if cleanup_error:
        elapsed = time.monotonic() - started
        raise AssertionError(
            f"mode={mode} expected={expected} actual_exit={child.process.poll()} "
            f"elapsed={elapsed:.3f}s cleanup_error={cleanup_error}\n"
            f"stdout:\n{stdout}\nstderr:\n{stderr}"
        )
    return stdout, stderr


def signal_and_wait(child: Child, timeout: float) -> tuple[int, float]:
    started = time.monotonic()
    child.process.send_signal(signal.SIGTERM)
    return wait_for_exit(child, timeout), time.monotonic() - started


def verify_forced_exit() -> None:
    def action(child: Child) -> None:
        wait_for_marker(child, "HANDLER_READY")
        returncode, elapsed = signal_and_wait(child, 6.2)
        if returncode != 1:
            raise AssertionError(f"Standalone watchdog exit was {returncode}, expected 1")
        if not 4.5 <= elapsed <= 6:
            raise AssertionError(f"Standalone watchdog exited after {elapsed:.3f} seconds")

    verify_child("stuck", "watchdog exit=1 after 4.5-6s", action)


def verify_disabled_watchdog() -> None:
    def action(child: Child) -> None:
        wait_for_marker(child, "HANDLER_READY")
        child.process.send_signal(signal.SIGTERM)
        try:
            wait_for_exit(child, 6)
        except subprocess.TimeoutExpired:
            return
        raise AssertionError(
            f"Standalone exited without its watchdog: {child.process.returncode}"
        )

    verify_child("stuck-no-watchdog", "alive for 6s after SIGTERM", action)


def verify_blocked_event_loop() -> None:
    for repeat_signal in (False, True):
        def action(child: Child) -> None:
            wait_for_marker(child, "HANDLER_BLOCKED")
            started = time.monotonic()
            child.process.send_signal(signal.SIGTERM)
            if repeat_signal:
                child.read_ready(2)
                child.process.send_signal(signal.SIGINT)
            returncode = wait_for_exit(child, max(0, started + 6.2 - time.monotonic()))
            elapsed = time.monotonic() - started
            if returncode != 1 or not 4.5 <= elapsed <= 6:
                raise AssertionError(
                    f"Blocked-loop watchdog exit={returncode} elapsed={elapsed:.3f}s"
                )

        verify_child("blocked", f"watchdog exit=1; repeated signal={repeat_signal}", action)


def verify_echo_shutdown() -> None:
    def clean_action(child: Child) -> None:
        wait_for_marker(child, "ECHO_READY")
        returncode, elapsed = signal_and_wait(child, 2)
        if returncode != 0 or elapsed >= 1:
            raise AssertionError(f"Echo clean exit={returncode} elapsed={elapsed:.3f}s")
        for index in (1, 2):
            wait_for_marker(child, f"ECHO_STOPPED_{index}")

    verify_child("echo-clean", "two sessions stop; supervisor exit=0 within 1s", clean_action)

    for mode, marker, repeat_signal in (
        ("echo-blocked", "HANDLER_BLOCKED", False),
        ("echo-blocked", "HANDLER_BLOCKED", True),
        ("echo-stuck", "HANDLER_READY", False),
    ):
        def action(child: Child) -> None:
            wait_for_marker(child, "ECHO_READY")
            wait_for_marker(child, marker)
            started = time.monotonic()
            child.process.send_signal(signal.SIGTERM)
            if repeat_signal:
                repeat_at = started + 2
                while time.monotonic() < repeat_at:
                    child.read_ready(max(0, repeat_at - time.monotonic()))
                child.process.send_signal(signal.SIGINT)
            returncode = wait_for_exit(child, max(0, started + 6.2 - time.monotonic()))
            elapsed = time.monotonic() - started
            if returncode != 1 or not 4.5 <= elapsed <= 6:
                raise AssertionError(f"Echo watchdog exit={returncode} elapsed={elapsed:.3f}s")
            if mode == "echo-stuck":
                for index in (1, 2):
                    wait_for_marker(child, f"ECHO_STOPPED_{index}")

        verify_child(mode, f"watchdog exit=1; repeated signal={repeat_signal}", action)


def verify_internal_failure_starts_watchdog() -> None:
    def action(child: Child) -> None:
        wait_for_marker(child, "HANDLER_READY")
        started = time.monotonic()
        returncode = wait_for_exit(child, 6.2)
        elapsed = time.monotonic() - started
        if returncode != 1:
            raise AssertionError(f"Internal-failure watchdog exit was {returncode}")
        if not 4.5 <= elapsed <= 6:
            raise AssertionError(
                f"Internal-failure watchdog exited after {elapsed:.3f} seconds"
            )

    verify_child(
        "session-stuck",
        "internal handler timeout starts watchdog and exits 1 after 4.5-6s",
        action,
    )


def verify_clean_exit() -> None:
    def action(child: Child) -> None:
        wait_for_marker(child, "RUNNER_READY")
        returncode, elapsed = signal_and_wait(child, 2)
        if returncode != 0 or elapsed >= 1:
            raise AssertionError(
                f"Clean standalone exit was {returncode} after {elapsed:.3f} seconds"
            )

    verify_child("clean", "exit=0 within 1s", action)


def verify_terminal_conflict_remains_idle() -> None:
    def action(child: Child) -> None:
        wait_for_marker(child, "CONFLICT_READY")
        try:
            wait_for_exit(child, 0.3)
        except subprocess.TimeoutExpired:
            pass
        else:
            raise AssertionError("Terminal conflict exited and could trigger a Docker restart")
        returncode, elapsed = signal_and_wait(child, 2)
        if returncode != 0 or elapsed >= 1:
            raise AssertionError("Idle terminal conflict did not stop normally")

    verify_child("protocol-conflict", "idle until manual stop, then exit=0", action)


def verify_library_timeout() -> None:
    def action(child: Child) -> None:
        wait_for_marker(child, "HANDLER_READY")
        child.process.send_signal(signal.SIGTERM)
        wait_for_marker(child, "LIBRARY_TIMEOUT", timeout=1)
        returncode = wait_for_exit(child, 1)
        if returncode != 0:
            raise AssertionError(f"Library timeout check exited with {returncode}")

    verify_child("library", "public run_bot TimeoutError and exit=0", action)


def verify_library_signal_restoration() -> None:
    def action(child: Child) -> None:
        wait_for_marker(child, "LIBRARY_SIGNALS_RESTORED", timeout=5)
        if wait_for_exit(child, 1) != 0:
            raise AssertionError("Library signal restoration failed")

    verify_child("library-signals", "previous signal handlers survive repeated library runs", action)


def verify_library_session_timeout() -> None:
    def action(child: Child) -> None:
        wait_for_marker(child, "HANDLER_READY")
        wait_for_marker(child, "LIBRARY_SESSION_TIMEOUT", timeout=1)
        returncode = wait_for_exit(child, 1)
        if returncode != 0:
            raise AssertionError(f"Library session timeout exited with {returncode}")

    verify_child(
        "library-session",
        "internal handler timeout raises TimeoutError without process exit",
        action,
    )


def verify_harness_diagnostics() -> None:
    def assert_report(mode: str, expected: str, action) -> str:
        try:
            verify_child(mode, expected, action, startup_delay=2.2)
        except AssertionError as error:
            report = str(error)
            required = (
                f"mode={mode}",
                f"expected={expected}",
                "actual_exit=",
                "cleanup_exit=",
                "stdout:",
                "stderr:",
            )
            if any(value not in report for value in required):
                raise AssertionError(f"Incomplete child diagnostic:\n{report}") from error
            return report
        raise AssertionError(f"Synthetic {mode} scenario unexpectedly passed")

    def unexpected_action(child: Child) -> None:
        returncode = wait_for_exit(child, 2)
        if returncode != 0:
            raise AssertionError(f"Unexpected child exit was {returncode}, expected 0")

    unexpected_report = assert_report(
        "unexpected", "standalone exit=0", unexpected_action
    )
    for value in (
        "actual_exit=71",
        "Traceback",
        "RuntimeError: standalone start failure for diagnostics",
    ):
        if value not in unexpected_report:
            raise AssertionError(
                f"Unexpected-child diagnostic omitted {value}:\n{unexpected_report}"
            )

    def wait_for_missing_marker(child: Child) -> None:
        wait_for_marker(child, "OUTPUT_READY")
        started = time.monotonic()
        try:
            wait_for_marker(child, "NEVER", timeout=0.1)
        except AssertionError:
            elapsed = time.monotonic() - started
            if elapsed > 0.25:
                raise AssertionError(f"Marker timeout took {elapsed:.3f}s") from None
            raise

    silent_report = assert_report(
        "silent",
        "marker=NEVER",
        wait_for_missing_marker,
    )
    for value in ("Child did not publish NEVER", "partial output", "cleanup_exit=-9"):
        if value not in silent_report:
            raise AssertionError(f"Silent child diagnostic omitted {value}:\n{silent_report}")

    def expect_marker(mode: str, marker: str) -> None:
        def action(child: Child) -> None:
            wait_for_marker(child, marker, timeout=5)
            if wait_for_exit(child, 1) != 0:
                raise AssertionError(f"{mode} did not exit successfully")

        verify_child(mode, f"marker={marker} and exit=0", action)

    expect_marker("fragmented", "FRAGMENTED_MARKER")
    expect_marker("multiple-lines", "SECOND_MARKER")
    expect_marker("eof-line", "EOF_MARKER")

    def wrong_code(child: Child) -> None:
        returncode = wait_for_exit(child, 2)
        if returncode != 1:
            raise AssertionError(f"Synthetic exit was {returncode}, expected 1")

    wrong_report = assert_report("wrong-code", "exit=1", wrong_code)
    for value in ("actual_exit=72", "WRONG_CODE_OUTPUT", "expected 1"):
        if value not in wrong_report:
            raise AssertionError(f"Wrong-code diagnostic omitted {value}:\n{wrong_report}")


def run_parent() -> None:
    verify_echo_shutdown()
    verify_blocked_event_loop()
    verify_forced_exit()
    verify_disabled_watchdog()
    verify_internal_failure_starts_watchdog()
    verify_clean_exit()
    verify_terminal_conflict_remains_idle()
    verify_library_timeout()
    verify_library_signal_restoration()
    verify_library_session_timeout()
    verify_harness_diagnostics()
    print("Headless Linux shutdown checks passed")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--startup-delay", type=float, default=0)
    parser.add_argument(
        "--child",
        choices=(
            "echo-clean",
            "echo-blocked",
            "echo-stuck",
            "blocked",
            "stuck",
            "stuck-no-watchdog",
            "session-stuck",
            "clean",
            "protocol-conflict",
            "library",
            "library-signals",
            "library-session",
            "unexpected",
            "silent",
            "fragmented",
            "multiple-lines",
            "eof-line",
            "wrong-code",
        ),
    )
    args = parser.parse_args()
    if args.child:
        time.sleep(args.startup_delay)
        print("CHILD_READY", flush=True)
    if args.child in {"echo-clean", "echo-blocked", "echo-stuck"}:
        run_echo_child(args.child)
    elif args.child == "blocked":
        configure_standalone(True, False)
        qrz_module.make_qrz_handler = lambda _audio: blocking_handler
        qrz_module.main()
    elif args.child == "stuck":
        run_standalone_child(True)
    elif args.child == "stuck-no-watchdog":
        run_standalone_child(True, disable_watchdog=True)
    elif args.child == "session-stuck":
        run_standalone_child(True, session_loss=True)
    elif args.child == "clean":
        run_standalone_child(False)
    elif args.child == "protocol-conflict":
        configure_standalone(False, False)

        class ConflictClient(FakeClient):
            async def start(self):
                self.session_epoch = 1
                await self.events.put(ReceiveInterrupted(None, None, "payload_mismatch", 1))
                print("CONFLICT_READY", flush=True)

        bot_module.HeadlessClient = ConflictClient
        qrz_module.main()
    elif args.child == "library":
        asyncio.run(run_library_child())
    elif args.child == "library-signals":
        asyncio.run(run_library_signal_restore_child())
    elif args.child == "library-session":
        asyncio.run(run_library_session_child())
    elif args.child == "unexpected":
        run_standalone_child(False, fail_start=True)
    elif args.child == "silent":
        os.write(sys.stdout.fileno(), b"OUTPUT_READY\npartial output")
        time.sleep(30)
    elif args.child == "fragmented":
        os.write(sys.stdout.fileno(), b"FRAG")
        time.sleep(0.02)
        os.write(sys.stdout.fileno(), b"MENTED_MARKER\n")
    elif args.child == "multiple-lines":
        os.write(sys.stdout.fileno(), b"FIRST_MARKER\nSECOND_MARKER\n")
    elif args.child == "eof-line":
        os.write(sys.stdout.fileno(), b"EOF_MARKER")
        os.close(sys.stdout.fileno())
    elif args.child == "wrong-code":
        print("WRONG_CODE_OUTPUT", flush=True)
        raise SystemExit(72)
    else:
        run_parent()


if __name__ == "__main__":
    main()
