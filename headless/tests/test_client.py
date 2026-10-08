import asyncio
import json
import time
from unittest.mock import patch

import pytest

from zenptt_headless.assembler import BurstAssembler
from zenptt_headless.client import ClientConfig, HeadlessClient, _Outgoing
from zenptt_headless.protocol import decode_media, parse_control
from zenptt_headless.types import PcmAudio, ReceivedBurst, SendResult


BURST_ID = "11111111-1111-4111-8111-111111111111"


@pytest.mark.asyncio
async def test_echo_client_uses_ticket_join_before_snapshot() -> None:
    sent = []

    class Socket:
        async def send(self, raw) -> None:
            sent.append(json.loads(raw))

        async def recv(self) -> str:
            return (
                '{"type":"snapshot","channel":"ECHO",'
                '"member_id":"22222222-2222-4222-8222-222222222222",'
                '"resume_token":"next","generation":1,'
                '"channel_incarnation_id":"33333333-3333-4333-8333-333333333333",'
                '"revision":2,"participant_count":2,"eligible_from_index":0,'
                '"next_burst_index":0,"audio_policy":{"recovery_horizon_ms":1000},'
                '"floor":null}'
            )

    client = HeadlessClient(ClientConfig("ws://test", "ECHO"), echo_ticket="secret")
    context = client._attach_transport(Socket())
    assert await client._handshake(context)
    assert sent[0] == {"type": "join_echo_bot", "ticket": "secret"}
    assert sent[1] == {"type": "listen", "burst_index": 0, "next_sequence": 0}


class FakeEncoder:
    def encode(self, pcm: bytes) -> bytes:
        return bytes([pcm[0]])

    def close(self) -> None:
        pass


class ManualClock:
    def __init__(self) -> None:
        self.value = 0.0

    def now(self) -> float:
        return self.value

    async def sleep(self, seconds: float) -> None:
        self.value += max(0, seconds)
        await asyncio.sleep(0)


@pytest.mark.asyncio
@pytest.mark.parametrize("ack_delay", [0.06, 0.2])
async def test_uplink_pacing_does_not_wait_for_ack(ack_delay: float) -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    client._ready.set()
    client._attach_transport(object())
    client._snapshot = {"audio_policy": {"recovery_horizon_ms": 1_000}}
    writes: list[tuple[float, int, int]] = []
    cumulative_ack = asyncio.Event()

    async def grant(outgoing):
        return {"type": "ptt_granted", "burst_id": BURST_ID}

    async def send_bytes(raw, **kwargs):
        _, first, packets = decode_media(raw, 1)
        writes.append((time.monotonic(), first, len(packets)))
        if first <= 4 < first + len(packets):
            async def deliver_ack() -> None:
                message = {
                    "type": "uplink_ack",
                    "burst_id": BURST_ID,
                    "next_sequence": 5,
                }
                await client._handle_control(message)
                await client._handle_control(message)
                assert all(sequence >= 5 for sequence in client._outgoing.retained)
                cumulative_ack.set()

            asyncio.get_running_loop().call_later(
                ack_delay, lambda: asyncio.create_task(deliver_ack())
            )
        return True

    async def send_json(message, **kwargs):
        if message["type"] == "burst_end":
            await asyncio.wait_for(cumulative_ack.wait(), 0.5)
            await client._handle_control(
                {
                    "type": "ptt_ended",
                    "burst_id": BURST_ID,
                    "state": "sealed",
                    "reason": "complete",
                    "final_next_sequence": 15,
                }
            )
        return True

    client._request_floor = grant
    client._send_bytes = send_bytes
    client._send_json = send_json
    with patch("zenptt_headless.client.OpusEncoder", FakeEncoder):
        result = await asyncio.wait_for(client.send_audio(PcmAudio(bytes(15 * 640))), 1)

    assert result.status == "sent"
    assert cumulative_ack.is_set()
    assert [sequence for _, first, count in writes for sequence in range(first, first + count)] == list(range(15))
    assert writes[-1][0] - writes[0][0] < 0.35


@pytest.mark.asyncio
async def test_frame_production_uses_the_injected_monotonic_clock() -> None:
    clock = ManualClock()
    encoded_at = []

    class RecordingEncoder(FakeEncoder):
        def encode(self, pcm: bytes) -> bytes:
            encoded_at.append(clock.now())
            return super().encode(pcm)

    client = HeadlessClient(ClientConfig("ws://test"))
    client._now = clock.now
    client._sleep = clock.sleep
    client._ready.set()
    client._attach_transport(object())
    client._snapshot = {"audio_policy": {"recovery_horizon_ms": 1_000}}

    async def grant(outgoing):
        return {"type": "ptt_granted", "burst_id": BURST_ID}

    async def send_bytes(raw, **kwargs):
        _, first, _ = decode_media(raw, 1)
        await client._handle_control(
            {"type": "uplink_ack", "burst_id": BURST_ID, "next_sequence": first + 1}
        )
        return True

    async def send_json(message, **kwargs):
        if message["type"] == "burst_end":
            await client._handle_control(
                {
                    "type": "ptt_ended",
                    "burst_id": BURST_ID,
                    "state": "sealed",
                    "reason": "complete",
                    "final_next_sequence": 4,
                }
            )
        return True

    client._request_floor = grant
    client._send_bytes = send_bytes
    client._send_json = send_json
    with patch("zenptt_headless.client.OpusEncoder", RecordingEncoder):
        result = await asyncio.wait_for(client.send_audio(PcmAudio(bytes(4 * 640))), 1)

    assert result.status == "sent"
    assert encoded_at == pytest.approx([0.0, 0.02, 0.04, 0.06])


@pytest.mark.asyncio
async def test_send_rejects_a_stale_expected_session_epoch() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    result = await client.send_audio(PcmAudio(bytes(640)), expected_session_epoch=1)
    assert result.status == "interrupted"
    assert result.reason == "session_lost"


@pytest.mark.asyncio
async def test_reconnect_replays_the_original_unacknowledged_packet() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    client._ready.set()
    client._attach_transport(object())
    outgoing = _Outgoing("request", 0, burst_id=BURST_ID, phase="transmitting")
    outgoing.retained[0] = b"original"
    client._outgoing = outgoing
    writes = []

    async def send_bytes(raw, **kwargs):
        writes.append(decode_media(raw, 1)[2][0])
        return True

    client._send_bytes = send_bytes
    await client._upload_retained(outgoing)
    client._attach_transport(object())
    await client._upload_retained(outgoing)
    assert writes == [b"original", b"original"]


@pytest.mark.asyncio
async def test_draining_does_not_complete_until_matching_sealed_event() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    outgoing = _Outgoing(
        "request",
        0,
        burst_id=BURST_ID,
        phase="finalizing",
        next_sequence=2,
        final_next_sequence=2,
        started_at=time.monotonic(),
    )
    client._outgoing = outgoing
    client._ready.set()
    client._attach_transport(object())
    writes = 0

    async def send_json(message, **kwargs):
        nonlocal writes
        writes += 1
        if writes == 1:
            await client._handle_control(
                {
                    "type": "ptt_ended",
                    "burst_id": BURST_ID,
                    "state": "draining",
                    "reason": "released",
                    "final_next_sequence": 2,
                }
            )
        else:
            await client._handle_control(
                {
                    "type": "ptt_ended",
                    "burst_id": BURST_ID,
                    "state": "sealed",
                    "reason": "complete",
                    "final_next_sequence": 2,
                }
            )
        return True

    client._send_json = send_json
    result = await asyncio.wait_for(client._finish_outgoing(outgoing, 2), 2)
    assert writes == 2
    assert result.status == "sent"


@pytest.mark.asyncio
async def test_terminal_for_another_burst_is_ignored() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    outgoing = _Outgoing("request", 0, burst_id=BURST_ID)
    client._outgoing = outgoing
    await client._handle_control(
        {
            "type": "ptt_ended",
            "burst_id": "22222222-2222-4222-8222-222222222222",
            "state": "sealed",
            "reason": "complete",
            "final_next_sequence": 1,
        }
    )
    assert outgoing.terminal is None


@pytest.mark.asyncio
async def test_floor_release_stops_new_frames_but_finishes_the_tail() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    client._ready.set()
    client._attach_transport(object())
    client._snapshot = {"audio_policy": {"recovery_horizon_ms": 1_000}}
    writes = []
    released_at = None

    async def grant(outgoing):
        return {"type": "ptt_granted", "burst_id": BURST_ID}

    async def send_bytes(raw, **kwargs):
        nonlocal released_at
        _, first, packets = decode_media(raw, 1)
        writes.extend(range(first, first + len(packets)))
        if first == 0:
            released_at = client._outgoing.next_sequence
            await client._handle_control(
                {
                    "type": "ptt_ended",
                    "burst_id": BURST_ID,
                    "state": "draining",
                    "reason": "lease_expired",
                    "final_next_sequence": None,
                }
            )
        return True

    async def send_json(message, **kwargs):
        if message["type"] == "burst_end":
            assert message["final_next_sequence"] == released_at
            await client._handle_control(
                {
                    "type": "ptt_ended",
                    "burst_id": BURST_ID,
                    "state": "sealed",
                    "reason": "complete",
                    "final_next_sequence": released_at,
                }
            )
        return True

    client._request_floor = grant
    client._send_bytes = send_bytes
    client._send_json = send_json
    with patch("zenptt_headless.client.OpusEncoder", FakeEncoder):
        result = await client.send_audio(PcmAudio(bytes(5 * 640)))

    assert released_at is not None
    assert client._outgoing is None
    assert writes == list(range(released_at))
    assert result.status == "interrupted"
    assert result.reason == "lease_expired"


@pytest.mark.asyncio
async def test_receive_discards_events_from_an_old_epoch() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    client._events.put_nowait(
        ReceivedBurst(BURST_ID, 1, PcmAudio(b""), (), (), "complete", session_epoch=0)
    )
    client._logical_epoch = 1
    current = ReceivedBurst(
        "22222222-2222-4222-8222-222222222222",
        2,
        PcmAudio(b""),
        (),
        (),
        "complete",
        session_epoch=1,
    )
    client._events.put_nowait(current)
    assert await client.receive() is current


@pytest.mark.asyncio
async def test_cumulative_ack_releases_only_the_resolved_prefix() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    outgoing = _Outgoing("request", 0, burst_id=BURST_ID)
    outgoing.retained = {0: b"zero", 1: b"one", 2: b"two"}
    client._outgoing = outgoing
    message = {"type": "uplink_ack", "burst_id": BURST_ID, "next_sequence": 2}
    await client._handle_control(message)
    await client._handle_control(message)
    assert outgoing.ack_next == 2
    assert outgoing.retained == {2: b"two"}


@pytest.mark.asyncio
async def test_session_loss_fences_waiters_before_publishing_the_event() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    outgoing = _Outgoing("request", 0)
    client._outgoing = outgoing
    waiter = asyncio.get_running_loop().create_future()
    client._control_waiters["request"] = waiter
    client._events.put_nowait(
        ReceivedBurst(BURST_ID, 1, PcmAudio(b""), (), (), "complete", session_epoch=0)
    )
    client._lose_session("resume_rejected")
    event = client._events.get_nowait()
    assert client.session_epoch == 1
    assert not outgoing.active
    assert waiter.result()["reason"] == "session_lost"
    assert event.session_epoch == 1


@pytest.mark.asyncio
async def test_server_cannot_confirm_a_shorter_response_as_sent() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    outgoing = _Outgoing(
        "request",
        0,
        burst_id=BURST_ID,
        phase="sealed",
        next_sequence=5,
        final_next_sequence=5,
        terminal={
            "state": "sealed",
            "reason": "complete",
            "final_next_sequence": 2,
        },
    )
    client._outgoing = outgoing
    result = await client._finish_outgoing(outgoing, 5)
    assert result.status == "interrupted"
    assert result.reason == "final_sequence_mismatch"


@pytest.mark.asyncio
async def test_implicit_final_after_lease_expiry_reports_interruption() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    outgoing = _Outgoing(
        "request", 0, burst_id=BURST_ID, phase="finalizing",
        next_sequence=5, final_next_sequence=5,
    )
    client._outgoing = outgoing
    await client._handle_control({
        "type": "ptt_ended", "burst_id": BURST_ID, "state": "draining",
        "reason": "lease_expired", "final_next_sequence": None,
    })
    assert outgoing.stop_reason == "lease_expired"
    assert outgoing.released_without_final
    await client._handle_control({
        "type": "ptt_ended", "burst_id": BURST_ID, "state": "sealed",
        "reason": "complete", "final_next_sequence": 2,
    })
    assert await client._finish_outgoing(outgoing, 5) == SendResult(
        "interrupted", "lease_expired", BURST_ID
    )


@pytest.mark.asyncio
async def test_finalization_deadline_is_fixed_when_the_tail_is_frozen() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    client._snapshot = {"audio_policy": {"recovery_horizon_ms": 1_000}}
    outgoing = _Outgoing("request", 0, burst_id=BURST_ID, next_sequence=3)
    client._outgoing = outgoing
    client._freeze_outgoing(outgoing)
    deadline = outgoing.finalization_deadline
    outgoing.next_sequence = 4
    client._freeze_outgoing(outgoing)
    assert outgoing.final_next_sequence == 3
    assert outgoing.finalization_deadline == deadline


@pytest.mark.asyncio
async def test_write_lock_timeout_detaches_and_aborts_only_its_transport(monkeypatch) -> None:
    class Transport:
        def __init__(self) -> None:
            self.aborted = False

        def abort(self) -> None:
            self.aborted = True

    class Socket:
        def __init__(self) -> None:
            self.transport = Transport()

        async def send(self, _message) -> None:
            pass

    monkeypatch.setattr("zenptt_headless.client.WRITE_TIMEOUT_SECONDS", 0.02)
    client = HeadlessClient(ClientConfig("ws://test"))
    socket = Socket()
    context = client._attach_transport(socket)
    client._ready.set()
    await context.write_lock.acquire()
    try:
        sent = await asyncio.wait_for(
            client._send_json({"type": "ping"}, epoch=0), 0.2
        )
    finally:
        context.write_lock.release()
    assert not sent
    assert client._transport is None
    assert socket.transport.aborted
    assert context.epoch == 0


@pytest.mark.asyncio
async def test_stalled_socket_write_is_aborted_without_waiting_for_cancellation(monkeypatch) -> None:
    class Transport:
        def __init__(self) -> None:
            self.aborted = asyncio.Event()

        def abort(self) -> None:
            self.aborted.set()

    class Socket:
        def __init__(self) -> None:
            self.transport = Transport()

        async def send(self, _message) -> None:
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                await self.transport.aborted.wait()

    monkeypatch.setattr("zenptt_headless.client.WRITE_TIMEOUT_SECONDS", 0.02)
    client = HeadlessClient(ClientConfig("ws://test"))
    socket = Socket()
    client._attach_transport(socket)
    client._ready.set()
    sent = await asyncio.wait_for(client._send_json({"type": "ping"}, epoch=0), 0.2)
    assert not sent
    assert socket.transport.aborted.is_set()
    assert client._transport is None


@pytest.mark.asyncio
async def test_cancelled_lock_wait_never_writes_and_does_not_abort_transport() -> None:
    class Transport:
        def __init__(self) -> None:
            self.aborted = False

        def abort(self) -> None:
            self.aborted = True

    class Socket:
        def __init__(self) -> None:
            self.transport = Transport()
            self.messages = []

        async def send(self, message) -> None:
            self.messages.append(message)

    client = HeadlessClient(ClientConfig("ws://test"))
    first_socket = Socket()
    first = client._attach_transport(first_socket)
    client._ready.set()
    await first.write_lock.acquire()
    pending = asyncio.create_task(client._send_json({"type": "old"}, epoch=0))
    await asyncio.sleep(0)
    pending.cancel()
    with pytest.raises(asyncio.CancelledError):
        await pending
    first.write_lock.release()
    await asyncio.sleep(0)

    assert first_socket.messages == []
    assert not first_socket.transport.aborted

    second_socket = Socket()
    client._attach_transport(second_socket)
    assert await client._send_json({"type": "new"}, epoch=0)
    assert second_socket.messages == ['{"type":"new"}']


@pytest.mark.asyncio
async def test_cancelled_active_write_aborts_old_transport_without_blocking_new_one() -> None:
    class Transport:
        def __init__(self) -> None:
            self.aborted = asyncio.Event()

        def abort(self) -> None:
            self.aborted.set()

    class StuckSocket:
        def __init__(self) -> None:
            self.transport = Transport()
            self.entered = asyncio.Event()
            self.completed = False

        async def send(self, _message) -> None:
            self.entered.set()
            try:
                await asyncio.Event().wait()
            except asyncio.CancelledError:
                await self.transport.aborted.wait()
            self.completed = True

    class Socket:
        def __init__(self) -> None:
            self.transport = Transport()
            self.messages = []

        async def send(self, message) -> None:
            self.messages.append(message)

    client = HeadlessClient(ClientConfig("ws://test"))
    old_socket = StuckSocket()
    client._attach_transport(old_socket)
    client._ready.set()
    pending = asyncio.create_task(client._send_json({"type": "old"}, epoch=0))
    await asyncio.wait_for(old_socket.entered.wait(), 1)
    pending.cancel()
    with pytest.raises(asyncio.CancelledError):
        await pending
    await asyncio.wait_for(old_socket.transport.aborted.wait(), 1)

    new_socket = Socket()
    client._attach_transport(new_socket)
    client._ready.set()
    assert await asyncio.wait_for(client._send_json({"type": "new"}, epoch=0), 0.2)
    assert new_socket.messages == ['{"type":"new"}']
    assert old_socket.completed


@pytest.mark.asyncio
async def test_ack_while_waiting_for_write_lock_skips_stale_packet() -> None:
    class Socket:
        transport = None

        def __init__(self) -> None:
            self.sequences = []

        async def send(self, message) -> None:
            if isinstance(message, bytes):
                self.sequences.append(decode_media(message, 1)[1])

    client = HeadlessClient(ClientConfig("ws://test"))
    socket = Socket()
    context = client._attach_transport(socket)
    client._ready.set()
    outgoing = _Outgoing(
        "request", 0, burst_id=BURST_ID, phase="finalizing", started_at=time.monotonic()
    )
    outgoing.retained = {0: b"zero", 1: b"one"}
    outgoing.next_sequence = 2
    client._outgoing = outgoing
    await context.write_lock.acquire()
    upload = asyncio.create_task(client._upload_retained(outgoing))
    await asyncio.sleep(0)
    await client._handle_control(
        {"type": "uplink_ack", "burst_id": BURST_ID, "next_sequence": 1}
    )
    context.write_lock.release()
    await asyncio.wait_for(upload, 1)

    assert socket.sequences == [1]


@pytest.mark.asyncio
async def test_retention_expiry_while_waiting_for_lock_skips_packet() -> None:
    class Socket:
        transport = None

        def __init__(self) -> None:
            self.sequences = []

        async def send(self, message) -> None:
            self.sequences.append(decode_media(message, 1)[1])

    clock = ManualClock()
    client = HeadlessClient(ClientConfig("ws://test"))
    client._now = clock.now
    client._snapshot = {"audio_policy": {"recovery_horizon_ms": 100}}
    socket = Socket()
    context = client._attach_transport(socket)
    client._ready.set()
    outgoing = _Outgoing(
        "request", 0, burst_id=BURST_ID, phase="finalizing", started_at=0
    )
    outgoing.retained = {0: b"expired", 1: b"current"}
    outgoing.next_sequence = 2
    client._outgoing = outgoing
    await context.write_lock.acquire()
    upload = asyncio.create_task(client._upload_retained(outgoing))
    await asyncio.sleep(0)
    clock.value = 0.11
    context.write_lock.release()
    await asyncio.wait_for(upload, 1)

    assert socket.sequences == [1]
    assert outgoing.retained == {1: b"current"}


@pytest.mark.asyncio
async def test_frame_production_continues_while_a_network_write_is_blocked() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    client._ready.set()
    client._attach_transport(object())
    client._snapshot = {"audio_policy": {"recovery_horizon_ms": 1_000}}
    write_started = asyncio.Event()
    release_write = asyncio.Event()

    async def grant(outgoing):
        return {"type": "ptt_granted", "burst_id": BURST_ID}

    async def send_bytes(raw, **kwargs):
        _, first, _ = decode_media(raw, 1)
        if first == 0 and not release_write.is_set():
            write_started.set()
            await release_write.wait()
        await client._handle_control(
            {"type": "uplink_ack", "burst_id": BURST_ID, "next_sequence": first + 1}
        )
        return True

    async def send_json(message, **kwargs):
        if message["type"] == "burst_end":
            await client._handle_control(
                {
                    "type": "ptt_ended",
                    "burst_id": BURST_ID,
                    "state": "sealed",
                    "reason": "complete",
                    "final_next_sequence": 5,
                }
            )
        return True

    client._request_floor = grant
    client._send_bytes = send_bytes
    client._send_json = send_json
    with patch("zenptt_headless.client.OpusEncoder", FakeEncoder):
        task = asyncio.create_task(client.send_audio(PcmAudio(bytes(5 * 640))))
        await asyncio.wait_for(write_started.wait(), 1)
        await asyncio.sleep(0.075)
        assert client._outgoing is not None
        assert client._outgoing.next_sequence >= 4
        release_write.set()
        result = await asyncio.wait_for(task, 1)
    assert result.status == "sent"


@pytest.mark.asyncio
async def test_successful_resume_clears_a_pending_grant_outage() -> None:
    class Socket:
        async def send(self, _message) -> None:
            pass

        async def recv(self) -> str:
            return (
                '{"type":"snapshot","channel":"TEST",'
                '"member_id":"22222222-2222-4222-8222-222222222222",'
                '"resume_token":"next","generation":2,'
                '"channel_incarnation_id":"33333333-3333-4333-8333-333333333333",'
                '"revision":1,"participant_count":1,"eligible_from_index":0,'
                '"next_burst_index":0,"audio_policy":{"recovery_horizon_ms":1000},'
                '"floor":null}'
            )

    client = HeadlessClient(ClientConfig("ws://test"))
    client._resume_token = "token"
    client._generation = 1
    client._incarnation = "33333333-3333-4333-8333-333333333333"
    outgoing = _Outgoing("request", 0, disconnected_at=time.monotonic() - 10)
    client._outgoing = outgoing
    context = client._attach_transport(Socket())
    assert await client._handshake(context)
    assert outgoing.disconnected_at is None
    assert not client._disconnect_expired(outgoing)


@pytest.mark.asyncio
async def test_waiting_for_grant_longer_than_h_does_not_shorten_the_response() -> None:
    clock = ManualClock()
    client = HeadlessClient(ClientConfig("ws://test"))
    client._now = clock.now
    client._sleep = clock.sleep
    client._ready.set()
    client._attach_transport(object())
    client._snapshot = {"audio_policy": {"recovery_horizon_ms": 1_000}}

    async def delayed_grant(outgoing):
        await clock.sleep(1.1)
        return {"type": "ptt_granted", "burst_id": BURST_ID}

    async def send_bytes(raw, **kwargs):
        _, first, _ = decode_media(raw, 1)
        await client._handle_control(
            {"type": "uplink_ack", "burst_id": BURST_ID, "next_sequence": first + 1}
        )
        return True

    async def send_json(message, **kwargs):
        if message["type"] == "burst_end":
            await client._handle_control(
                {
                    "type": "ptt_ended",
                    "burst_id": BURST_ID,
                    "state": "sealed",
                    "reason": "complete",
                    "final_next_sequence": 4,
                }
            )
        return True

    client._request_floor = delayed_grant
    client._send_bytes = send_bytes
    client._send_json = send_json
    with patch("zenptt_headless.client.OpusEncoder", FakeEncoder):
        result = await asyncio.wait_for(client.send_audio(PcmAudio(bytes(4 * 640))), 1)

    assert result.status == "sent"
    assert clock.now() >= 1.16


@pytest.mark.asyncio
async def test_stale_transport_messages_and_finally_do_not_touch_the_new_session() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    old_socket = object()
    old = client._attach_transport(old_socket)
    client._ready.set()
    client._lose_session("resume_rejected", old)
    new_socket = object()
    new = client._attach_transport(new_socket)
    client._ready.set()

    await client._handle_control(
        {"type": "burst_started", "burst_id": BURST_ID, "burst_index": 0},
        context=old,
    )
    assert client._events.qsize() == 1
    assert client._assembler.cursor == (0, 0)
    assert not client._detach_transport(old)
    assert client._transport is new
    assert client._ready.is_set()


@pytest.mark.asyncio
async def test_completed_index_boundary_blocks_old_lifecycle_and_resets_per_epoch() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))

    class Decoder:
        def decode(self, _packet):
            return bytes(640)

        def close(self):
            pass

    client._assembler = BurstAssembler(Decoder)
    ids = [
        "11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333",
    ]
    for index, burst_id in enumerate(ids):
        await client._handle_control(
            {"type": "burst_started", "burst_id": burst_id, "burst_index": index}
        )
        await client._handle_control(
            {
                "type": "burst_sealed",
                "burst_id": burst_id,
                "burst_index": index,
                "final_next_sequence": 0,
                "reason": "complete",
            }
        )
    while not client._events.empty():
        client._events.get_nowait()
    cursor = client._listen_cursor

    await client._handle_control(
        {"type": "burst_started", "burst_id": ids[1], "burst_index": 1}
    )
    await client._handle_control(
        {
            "type": "burst_gaps",
            "burst_id": ids[1],
            "burst_index": 1,
            "ranges": [{"first_sequence": 0, "count": 1}],
        }
    )
    await client._handle_control(
        {
            "type": "burst_sealed",
            "burst_id": ids[1],
            "burst_index": 1,
            "final_next_sequence": 1,
            "reason": "complete",
        }
    )
    assert client._listen_cursor == cursor
    assert client._events.empty()

    client._lose_session("resume_rejected")
    client._events.get_nowait()
    await client._handle_control(
        {"type": "burst_started", "burst_id": ids[0], "burst_index": 0}
    )
    await client._handle_control(
        {
            "type": "burst_sealed",
            "burst_id": ids[0],
            "burst_index": 0,
            "final_next_sequence": 0,
            "reason": "complete",
        }
    )
    event = client._events.get_nowait()
    assert event.burst_index == 0
    assert event.session_epoch == 1


@pytest.mark.asyncio
async def test_repeated_listen_reset_does_not_discard_a_progressed_tail() -> None:
    class Decoder:
        def decode(self, _packet):
            return bytes(640)

        def close(self):
            pass

    client = HeadlessClient(ClientConfig("ws://test"))
    client._assembler = BurstAssembler(Decoder)

    async def sent(_message, **_kwargs):
        return True

    client._send_json = sent
    reset = {
        "type": "listen_reset",
        "burst_index": 4,
        "next_sequence": 10,
        "reason": "expired",
    }
    await client._handle_control(reset)
    await client._handle_control(
        {"type": "burst_started", "burst_id": BURST_ID, "burst_index": 4}
    )
    client._assembler.media(BURST_ID, 10, (b"packet",))
    client._advance_listen_cursor(client._assembler.cursor)

    await client._handle_control(reset)
    assert client._assembler.cursor == (4, 11)
    await client._handle_control(
        {
            "type": "burst_sealed",
            "burst_id": BURST_ID,
            "burst_index": 4,
            "final_next_sequence": 11,
            "reason": "complete",
        }
    )
    event = client._events.get_nowait()
    assert event.first_sequence == 10
    assert len(event.audio.data) == 640


@pytest.mark.asyncio
async def test_finalization_refreshes_deadline_while_uploading_retained_packets() -> None:
    clock = ManualClock()
    client = HeadlessClient(ClientConfig("ws://test"))
    client._now = clock.now
    client._snapshot = {"audio_policy": {"recovery_horizon_ms": 1_000}}
    client._ready.set()
    client._attach_transport(object())
    outgoing = _Outgoing(
        "request", 0, burst_id=BURST_ID, phase="transmitting", started_at=0
    )
    outgoing.retained = {sequence: bytes([sequence]) for sequence in range(10)}
    outgoing.next_sequence = 10
    client._outgoing = outgoing
    writes: list[tuple[int, int, float]] = []

    async def send_bytes(raw, **_kwargs):
        _, sequence, packets = decode_media(raw, 1)
        writes.append((sequence, len(packets), clock.now()))
        clock.value += 0.9
        if sequence == 0:
            client._freeze_outgoing(outgoing)
        return True

    client._send_bytes = send_bytes
    await client._upload_retained(outgoing)

    assert outgoing.finalization_deadline == pytest.approx(6.9)
    assert all(started < outgoing.finalization_deadline for _, _, started in writes)
    assert [sequence for first, count, _ in writes for sequence in range(first, first + count)] == list(range(10))


@pytest.mark.asyncio
@pytest.mark.parametrize("frame_count", [1, 2, 3, 4])
async def test_fresh_audio_batches_and_flushes_short_tail(frame_count: int) -> None:
    class Socket:
        transport = None

        def __init__(self) -> None:
            self.messages: list[bytes] = []

        async def send(self, message: bytes) -> None:
            self.messages.append(message)

    clock = ManualClock()
    client = HeadlessClient(ClientConfig("ws://test"))
    client._now = clock.now
    socket = Socket()
    client._attach_transport(socket)
    client._ready.set()
    outgoing = _Outgoing("request", 0, burst_id=BURST_ID, phase="transmitting", started_at=0)
    outgoing.retained = {sequence: bytes([sequence]) for sequence in range(frame_count)}
    outgoing.next_sequence = frame_count
    client._outgoing = outgoing

    clock.value = (frame_count - 1) * 0.02
    await client._upload_retained(outgoing)
    assert [len(decode_media(raw, 1)[2]) for raw in socket.messages] == (
        [3] if frame_count >= 3 else []
    )

    client._freeze_outgoing(outgoing)
    await client._upload_retained(outgoing)
    frames = [
        (first + offset, packet)
        for raw in socket.messages
        for _, first, packets in [decode_media(raw, 1)]
        for offset, packet in enumerate(packets)
    ]
    assert frames == [(sequence, bytes([sequence])) for sequence in range(frame_count)]
    assert all(len(raw) <= 4096 for raw in socket.messages)


@pytest.mark.asyncio
async def test_fresh_audio_partial_batch_flushes_after_40_ms() -> None:
    class Socket:
        transport = None

        def __init__(self) -> None:
            self.messages: list[bytes] = []

        async def send(self, message: bytes) -> None:
            self.messages.append(message)

    clock = ManualClock()
    client = HeadlessClient(ClientConfig("ws://test"))
    client._now = clock.now
    socket = Socket()
    client._attach_transport(socket)
    client._ready.set()
    outgoing = _Outgoing("request", 0, burst_id=BURST_ID, phase="transmitting", started_at=0)
    outgoing.retained = {0: b"first", 1: b"second"}
    outgoing.ready_at = {0: 0.1, 1: 0.12}
    outgoing.next_sequence = 2
    client._outgoing = outgoing

    clock.value = 0.139
    await client._upload_retained(outgoing)
    assert socket.messages == []
    clock.value = 0.14
    await client._upload_retained(outgoing)
    assert decode_media(socket.messages[0], 1)[2] == (b"first", b"second")


@pytest.mark.asyncio
async def test_replay_batches_without_waiting_and_respects_message_limit() -> None:
    class Socket:
        transport = None

        def __init__(self) -> None:
            self.messages: list[bytes] = []

        async def send(self, message: bytes) -> None:
            self.messages.append(message)

    clock = ManualClock()
    client = HeadlessClient(ClientConfig("ws://test"))
    client._now = clock.now
    client._snapshot = {"audio_policy": {"recovery_horizon_ms": 5_000}}
    client._attach_transport(object())
    socket = Socket()
    client._attach_transport(socket)
    client._ready.set()
    outgoing = _Outgoing("request", 0, burst_id=BURST_ID, phase="transmitting", started_at=0)
    outgoing.retained = {sequence: bytes(1_275) for sequence in range(7)}
    outgoing.next_sequence = 7
    outgoing.sent_transport = 1
    client._outgoing = outgoing

    await client._upload_retained(outgoing)
    assert [len(decode_media(raw, 1)[2]) for raw in socket.messages] == [3, 3, 1]
    assert all(len(raw) <= 4096 for raw in socket.messages)
    assert outgoing.sent_sequences == set(range(7))


@pytest.mark.asyncio
async def test_new_speech_after_replay_uses_fresh_batch_deadline() -> None:
    class Socket:
        transport = None

        def __init__(self) -> None:
            self.messages: list[bytes] = []

        async def send(self, message: bytes) -> None:
            self.messages.append(message)

    clock = ManualClock()
    client = HeadlessClient(ClientConfig("ws://test"))
    client._now = clock.now
    client._snapshot = {"audio_policy": {"recovery_horizon_ms": 5_000}}
    client._attach_transport(object())
    socket = Socket()
    client._attach_transport(socket)
    client._ready.set()
    outgoing = _Outgoing("request", 0, burst_id=BURST_ID,
                         phase="transmitting", started_at=0)
    outgoing.retained = {sequence: b"old" for sequence in range(4)}
    outgoing.next_sequence = 4
    outgoing.sent_transport = 1
    client._outgoing = outgoing

    await client._upload_retained(outgoing)
    assert [len(decode_media(raw, 1)[2]) for raw in socket.messages] == [4]
    outgoing.retained[4] = b"new"
    outgoing.ready_at[4] = clock.now()
    outgoing.next_sequence = 5
    await client._upload_retained(outgoing)
    assert len(socket.messages) == 1
    clock.value = 0.04
    await client._upload_retained(outgoing)
    assert [len(decode_media(raw, 1)[2]) for raw in socket.messages] == [4, 1]


@pytest.mark.asyncio
async def test_batch_upload_stops_when_another_burst_replaces_it() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    client._ready.set()
    client._attach_transport(object())
    outgoing = _Outgoing(
        "request", 0, burst_id=BURST_ID, phase="finalizing", started_at=time.monotonic(),
    )
    outgoing.retained = {sequence: bytes([sequence]) for sequence in range(4)}
    outgoing.next_sequence = outgoing.final_next_sequence = 4
    client._outgoing = outgoing
    writes = []

    async def send_bytes(raw, **_kwargs):
        writes.append(decode_media(raw, 1))
        client._outgoing = _Outgoing(
            "next", 0, burst_id="22222222-2222-4222-8222-222222222222",
        )
        return True

    client._send_bytes = send_bytes
    await client._upload_retained(outgoing)

    assert [(burst, first, len(packets)) for burst, first, packets in writes] == [
        (BURST_ID, 0, 3),
    ]


@pytest.mark.asyncio
async def test_terminal_received_after_finalization_deadline_is_not_sent() -> None:
    clock = ManualClock()
    client = HeadlessClient(ClientConfig("ws://test"))
    client._now = clock.now
    outgoing = _Outgoing(
        "request",
        0,
        burst_id=BURST_ID,
        phase="sealed",
        next_sequence=1,
        final_next_sequence=1,
        finalization_deadline=1,
        terminal={
            "state": "sealed",
            "reason": "complete",
            "final_next_sequence": 1,
        },
        terminal_at=1.1,
    )
    client._outgoing = outgoing
    clock.value = 1.1
    result = await client._finish_outgoing(outgoing, 1)
    assert result.status == "interrupted"
    assert result.reason == "recovery_timeout"
    assert client.session_epoch == 1


@pytest.mark.asyncio
async def test_first_sealed_confirmation_is_immutable() -> None:
    clock = ManualClock()
    client = HeadlessClient(ClientConfig("ws://test"))
    client._now = clock.now
    outgoing = _Outgoing(
        "request",
        0,
        burst_id=BURST_ID,
        phase="finalizing",
        next_sequence=1,
        final_next_sequence=1,
        finalization_deadline=1,
    )
    client._outgoing = outgoing
    first = {
        "type": "ptt_ended",
        "burst_id": BURST_ID,
        "state": "sealed",
        "reason": "complete",
        "final_next_sequence": 1,
    }
    clock.value = 0.9
    await client._handle_control(first)
    clock.value = 2
    await client._handle_control({**first, "reason": "canceled"})
    await client._handle_control({**first, "state": "draining"})

    result = await client._finish_outgoing(outgoing, 1)
    assert result == SendResult("sent", "complete", BURST_ID)
    assert outgoing.terminal is first
    assert outgoing.terminal_at == 0.9
    assert client.session_epoch == 0


@pytest.mark.asyncio
async def test_send_audio_invalidates_session_when_full_operation_misses_deadline() -> None:
    class Transport:
        def __init__(self) -> None:
            self.aborted = False

        def abort(self) -> None:
            self.aborted = True

    class Socket:
        def __init__(self) -> None:
            self.transport = Transport()
            self.write_times = []
            self.deadline = None

        async def send(self, message) -> None:
            self.write_times.append(clock.now())
            if isinstance(message, str) and '"type":"burst_end"' in message:
                self.deadline = client._outgoing.finalization_deadline
                clock.value = self.deadline + 0.1

        async def close(self, code=1000) -> None:
            pass

    clock = ManualClock()
    client = HeadlessClient(ClientConfig("ws://test"))
    client._now = clock.now
    client._sleep = clock.sleep
    client._snapshot = {"audio_policy": {"recovery_horizon_ms": 10}}
    socket = Socket()
    client._attach_transport(socket)
    client._ready.set()

    async def grant(_outgoing):
        return {"type": "ptt_granted", "burst_id": BURST_ID}

    client._request_floor = grant
    with patch("zenptt_headless.client.OpusEncoder", FakeEncoder):
        result = await asyncio.wait_for(client.send_audio(PcmAudio(bytes(640))), 1)

    assert result == SendResult("interrupted", "recovery_timeout", BURST_ID)
    assert socket.deadline is not None
    assert all(write_time <= socket.deadline for write_time in socket.write_times)
    assert socket.transport.aborted
    assert client.session_epoch == 1


@pytest.mark.asyncio
async def test_encoder_creation_failure_does_not_request_ptt() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    requested = False

    async def request(_outgoing):
        nonlocal requested
        requested = True
        raise AssertionError("PTT must not be requested")

    client._request_floor = request
    with patch("zenptt_headless.client.OpusEncoder", side_effect=RuntimeError("missing")):
        result = await client.send_audio(PcmAudio(bytes(640)))
    assert result.status == "rejected"
    assert result.reason == "encoding_error"
    assert not requested


@pytest.mark.asyncio
async def test_encoder_failure_after_frames_finishes_as_interrupted() -> None:
    class FailingEncoder(FakeEncoder):
        def __init__(self) -> None:
            self.calls = 0
            self.closed = False

        def encode(self, pcm: bytes) -> bytes:
            self.calls += 1
            if self.calls == 3:
                raise RuntimeError("encode failed")
            return super().encode(pcm)

        def close(self) -> None:
            self.closed = True

    encoder = FailingEncoder()
    client = HeadlessClient(ClientConfig("ws://test"))
    client._ready.set()
    client._attach_transport(object())
    client._snapshot = {"audio_policy": {"recovery_horizon_ms": 1_000}}

    async def grant(_outgoing):
        return {"type": "ptt_granted", "burst_id": BURST_ID}

    async def send_bytes(raw, **_kwargs):
        _, first, _ = decode_media(raw, 1)
        await client._handle_control(
            {"type": "uplink_ack", "burst_id": BURST_ID, "next_sequence": first + 1}
        )
        return True

    async def send_json(message, **_kwargs):
        if message["type"] == "burst_end":
            await client._handle_control(
                {
                    "type": "ptt_ended",
                    "burst_id": BURST_ID,
                    "state": "sealed",
                    "reason": "complete",
                    "final_next_sequence": 2,
                }
            )
        return True

    client._request_floor = grant
    client._send_bytes = send_bytes
    client._send_json = send_json
    with patch("zenptt_headless.client.OpusEncoder", return_value=encoder):
        result = await asyncio.wait_for(client.send_audio(PcmAudio(bytes(5 * 640))), 1)

    assert result.status == "interrupted"
    assert result.reason == "encoding_error"
    assert encoder.closed


@pytest.mark.asyncio
async def test_grant_binds_burst_before_waking_waiter_for_terminal() -> None:
    clock = ManualClock()
    client = HeadlessClient(ClientConfig("ws://test"))
    client._now = clock.now
    outgoing = _Outgoing("request", 0)
    client._outgoing = outgoing
    waiter = asyncio.get_running_loop().create_future()
    client._control_waiters[outgoing.request_id] = waiter

    grant = {
        "type": "ptt_granted",
        "request_id": outgoing.request_id,
        "burst_id": BURST_ID,
    }
    await client._handle_control(grant)
    await client._handle_control(
        {
            "type": "ptt_ended",
            "burst_id": BURST_ID,
            "state": "sealed",
            "reason": "canceled",
            "final_next_sequence": 0,
        }
    )
    await client._handle_control(grant)

    assert waiter.result() is grant
    assert outgoing.burst_id == BURST_ID
    assert outgoing.started_at == 0
    assert outgoing.final_next_sequence == 0
    assert outgoing.terminal is not None
    assert outgoing.phase == "sealed"


@pytest.mark.asyncio
async def test_failed_listen_handshake_preserves_original_disconnect_time() -> None:
    class Socket:
        async def recv(self) -> str:
            return (
                '{"type":"snapshot","channel":"TEST",'
                '"member_id":"22222222-2222-4222-8222-222222222222",'
                '"resume_token":"next","generation":2,'
                '"channel_incarnation_id":"33333333-3333-4333-8333-333333333333",'
                '"revision":1,"participant_count":1,"eligible_from_index":0,'
                '"next_burst_index":0,"audio_policy":{"recovery_horizon_ms":5000},'
                f'"floor":{{"owned":true,"burst_id":"{BURST_ID}","burst_index":0}}}}'
            )

    clock = ManualClock()
    clock.value = 4.9
    client = HeadlessClient(ClientConfig("ws://test"))
    client._now = clock.now
    client._resume_token = "token"
    client._generation = 1
    client._incarnation = "33333333-3333-4333-8333-333333333333"
    outgoing = _Outgoing(
        "request",
        0,
        burst_id=BURST_ID,
        phase="transmitting",
        started_at=0,
        disconnected_at=0,
    )
    client._outgoing = outgoing
    context = client._attach_transport(Socket())
    writes = 0

    async def write(_message, **_kwargs):
        nonlocal writes
        writes += 1
        return writes == 1

    client._write_json = write
    assert not await client._handshake(context)
    client._detach_transport(context)
    assert outgoing.disconnected_at == 0


@pytest.mark.asyncio
async def test_cancelled_send_keeps_floor_serialized_until_sealed() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    client._snapshot = {"audio_policy": {"recovery_horizon_ms": 1_000}}
    first_audio = asyncio.Event()
    first_end_started = asyncio.Event()
    allow_first_seal = asyncio.Event()
    request_count = 0
    first_burst = BURST_ID
    second_burst = "22222222-2222-4222-8222-222222222222"

    class Socket:
        async def send(self, raw) -> None:
            nonlocal request_count
            if isinstance(raw, bytes):
                first_audio.set()
                return
            message = json.loads(raw)
            if message["type"] == "ptt_request":
                request_count += 1
                burst_id = first_burst if request_count == 1 else second_burst
                await client._handle_control(
                    {
                        "type": "ptt_granted",
                        "request_id": message["request_id"],
                        "burst_id": burst_id,
                        "burst_index": request_count - 1,
                        "lease_remaining_ms": 5_000,
                    }
                )
            elif message["type"] == "burst_end":
                if message["burst_id"] == first_burst:
                    first_end_started.set()
                    await allow_first_seal.wait()
                    await client._handle_control(
                        {
                            "type": "ptt_ended",
                            "burst_id": first_burst,
                            "burst_index": 0,
                            "state": "draining",
                            "reason": "released",
                            "final_next_sequence": message["final_next_sequence"],
                        }
                    )
                await client._handle_control(
                    {
                        "type": "ptt_ended",
                        "burst_id": message["burst_id"],
                        "burst_index": request_count - 1,
                        "state": "sealed",
                        "reason": "complete",
                        "final_next_sequence": message["final_next_sequence"],
                    }
                )

    client._attach_transport(Socket())
    client._ready.set()
    with patch("zenptt_headless.client.OpusEncoder", FakeEncoder):
        first = asyncio.create_task(client.send_audio(PcmAudio(bytes(5 * 640))))
        await asyncio.wait_for(first_audio.wait(), 1)
        while client._outgoing is None or 0 not in client._outgoing.sent_sequences:
            await asyncio.sleep(0)
        first.cancel()
        await asyncio.wait_for(first_end_started.wait(), 1)
        first.cancel()
        second = asyncio.create_task(client.send_audio(PcmAudio(bytes(640))))
        await asyncio.sleep(0.05)
        assert request_count == 1
        allow_first_seal.set()
        with pytest.raises(asyncio.CancelledError):
            await first
        result = await asyncio.wait_for(second, 1)

    assert request_count == 2, result
    assert result == SendResult("sent", "complete", second_burst)


@pytest.mark.asyncio
async def test_cancelled_possible_ptt_request_invalidates_session() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    send_started = asyncio.Event()

    class Transport:
        def abort(self) -> None:
            pass

    class Socket:
        transport = Transport()

        async def send(self, _raw) -> None:
            send_started.set()
            await asyncio.Event().wait()

        async def close(self, code=1000) -> None:
            pass

    client._attach_transport(Socket())
    client._ready.set()
    with patch("zenptt_headless.client.OpusEncoder", FakeEncoder):
        task = asyncio.create_task(client.send_audio(PcmAudio(bytes(640))))
        await asyncio.wait_for(send_started.wait(), 1)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(task, 1)

    assert client.session_epoch == 1
    assert client._outgoing is None


@pytest.mark.asyncio
async def test_cancelled_ptt_request_before_socket_write_keeps_session() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))

    class Socket:
        async def send(self, _raw) -> None:
            raise AssertionError("Canceled request must not reach the socket")

    context = client._attach_transport(Socket())
    client._ready.set()
    await context.write_lock.acquire()
    try:
        with patch("zenptt_headless.client.OpusEncoder", FakeEncoder):
            task = asyncio.create_task(client.send_audio(PcmAudio(bytes(640))))
            while client._outgoing is None:
                await asyncio.sleep(0)
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await asyncio.wait_for(task, 1)
    finally:
        context.write_lock.release()

    assert client.session_epoch == 0
    assert client._transport is context


@pytest.mark.asyncio
async def test_invalid_ack_is_rejected_before_outgoing_state_changes() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    outgoing = _Outgoing("request", 0, burst_id=BURST_ID, ack_next=2)
    client._outgoing = outgoing

    with pytest.raises(ValueError):
        message = parse_control(
            f'{{"type":"uplink_ack","burst_id":"{BURST_ID}",'
            '"next_sequence":null}'
        )
        await client._handle_control(message)

    assert outgoing.ack_next == 2


@pytest.mark.asyncio
@pytest.mark.parametrize("server_error", [None, "not_joined", "invalid_state"])
async def test_missing_grant_or_server_error_finishes_and_fences_late_grant(
    monkeypatch, server_error
) -> None:
    monkeypatch.setattr("zenptt_headless.client.PTT_REQUEST_TIMEOUT_SECONDS", 0.04, raising=False)
    client = HeadlessClient(ClientConfig("ws://test"))
    requests = []

    class Socket:
        async def send(self, raw):
            message = json.loads(raw)
            if message["type"] == "ptt_request":
                requests.append(message)
                await client._handle_control({"type": "pong", "id": 1, "sent_at_ms": 1})
                if server_error:
                    await client._handle_control(
                        {"type": "error", "code": server_error, "message": "Server error"}
                    )

        async def close(self, code=1000):
            pass

    context = client._attach_transport(Socket())
    client._ready.set()
    with patch("zenptt_headless.client.OpusEncoder", FakeEncoder):
        result = await asyncio.wait_for(client.send_audio(PcmAudio(bytes(640))), 0.5)
    assert result.status == "interrupted"
    assert result.reason == (f"server_error_{server_error}" if server_error else "grant_timeout")
    assert client.session_epoch == 1
    assert not client._audio_lock.locked()
    assert len(requests) == 1
    await client._handle_control(
        {"type": "ptt_granted", "request_id": requests[0]["request_id"], "burst_id": BURST_ID},
        context=context,
    )
    assert client._outgoing is None
    assert not client._ready.is_set()


@pytest.mark.asyncio
async def test_grant_deadline_includes_waiting_for_readiness(monkeypatch) -> None:
    monkeypatch.setattr("zenptt_headless.client.PTT_REQUEST_TIMEOUT_SECONDS", 0.03, raising=False)
    client = HeadlessClient(ClientConfig("ws://test"))
    with patch("zenptt_headless.client.OpusEncoder", FakeEncoder):
        result = await asyncio.wait_for(client.send_audio(PcmAudio(bytes(640))), 0.5)
    assert result == SendResult("interrupted", "grant_timeout")


@pytest.mark.asyncio
async def test_pending_request_reuses_id_after_transport_replacement() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    requests = []

    class Socket:
        async def send(self, raw):
            message = json.loads(raw)
            requests.append(message["request_id"])
            if len(requests) == 1:
                client._detach_transport(client._transport)
                client._attach_transport(Socket())
                client._ready.set()
            else:
                await client._handle_control(
                    {"type": "ptt_granted", "request_id": message["request_id"],
                     "burst_id": BURST_ID}
                )

    client._attach_transport(Socket())
    client._ready.set()
    outgoing = _Outgoing("same-request", 0)
    client._outgoing = outgoing
    grant = await asyncio.wait_for(client._request_floor(outgoing), 1)
    assert grant["type"] == "ptt_granted"
    assert requests == ["same-request", "same-request"]
    assert client.session_epoch == 0


@pytest.mark.asyncio
async def test_payload_conflict_stops_reconnect_and_preserves_failure_reason() -> None:
    client = HeadlessClient(ClientConfig("ws://test"))
    outgoing = _Outgoing("request", 0, burst_id=BURST_ID, phase="transmitting")
    outgoing.retained[0] = b"original"
    client._outgoing = outgoing
    await client._handle_control(
        {"type": "audio_rejected", "burst_id": BURST_ID, "first_sequence": 0,
         "next_sequence": 1, "reason": "payload_mismatch"}
    )
    with patch("zenptt_headless.client.connect") as connect_mock:
        await asyncio.wait_for(client._connection_loop(), 0.1)
        connect_mock.assert_not_called()
    assert outgoing.retained == {}
    assert await client._finish_outgoing(outgoing, 1) == SendResult(
        "interrupted", "payload_mismatch", BURST_ID
    )
    assert await client.send_audio(PcmAudio(bytes(640))) == SendResult(
        "rejected", "payload_mismatch"
    )
    event = await client.receive()
    assert event.reason == "payload_mismatch"
    assert event.session_epoch == 1
    with pytest.raises(RuntimeError, match="manual reconnect"):
        await client.start()


@pytest.mark.asyncio
async def test_repeated_transport_replacements_do_not_extend_grant_deadline(monkeypatch):
    monkeypatch.setattr("zenptt_headless.client.PTT_REQUEST_TIMEOUT_SECONDS", 0.04)
    client = HeadlessClient(ClientConfig("ws://test"))
    requests = []

    class Socket:
        async def send(self, raw):
            requests.append(json.loads(raw)["request_id"])
            client._detach_transport(client._transport)
            client._attach_transport(Socket())
            client._ready.set()

        async def close(self, code=1000):
            pass

    client._attach_transport(Socket())
    client._ready.set()
    with patch("zenptt_headless.client.OpusEncoder", FakeEncoder):
        result = await asyncio.wait_for(client.send_audio(PcmAudio(bytes(640))), 0.3)
    assert result == SendResult("interrupted", "grant_timeout")
    assert len(set(requests)) == 1
    assert client.session_epoch == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("session_loss", [False, True])
async def test_real_busy_retry_stops_without_a_second_request(session_loss):
    client = HeadlessClient(ClientConfig("ws://test"))
    waiting = asyncio.Event()
    requests = []

    class Socket:
        async def send(self, raw):
            message = json.loads(raw)
            if message["type"] == "ptt_request":
                requests.append(message["request_id"])
                await client._handle_control({"type": "ptt_denied", "request_id": message["request_id"],
                                              "reason": "channel_busy"})

    async def sleep(_seconds):
        waiting.set()
        await asyncio.Event().wait()

    client._attach_transport(Socket())
    client._ready.set()
    client._sleep = sleep
    with patch("zenptt_headless.client.OpusEncoder", FakeEncoder):
        task = asyncio.create_task(client.send_audio(PcmAudio(bytes(640))))
        await asyncio.wait_for(waiting.wait(), 1)
        if session_loss:
            client._lose_session("resume_rejected")
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
    assert len(requests) == 1
    assert client.session_epoch == int(session_loss)


@pytest.mark.asyncio
async def test_grant_arriving_after_deadline_cannot_bind_a_burst():
    client = HeadlessClient(ClientConfig("ws://test"))
    outgoing = _Outgoing("request", 0)
    outgoing.grant_deadline = 5
    client._now = lambda: 6
    client._outgoing = outgoing
    await client._handle_control({"type": "ptt_granted", "request_id": "request", "burst_id": BURST_ID})
    assert outgoing.burst_id is None
    assert outgoing.stop_reason == "grant_timeout"
    assert client.session_epoch == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("outcome", ["sealed", "timeout", "short_resume"])
async def test_audio_production_obeys_outage_horizon_across_resume_attempts(outcome):
    clock = ManualClock()
    client = HeadlessClient(ClientConfig("ws://test"))
    client._now = clock.now
    client._snapshot = {"audio_policy": {"recovery_horizon_ms": 1_000}}
    client._resume_token = "token"
    client._generation = 1
    client._incarnation = "33333333-3333-4333-8333-333333333333"
    outgoing = _Outgoing("request", 0, burst_id=BURST_ID, phase="transmitting", started_at=0)
    client._outgoing = outgoing
    encoded_at, attempts, ends = [], [], []

    class Socket:
        def __init__(self, fail_listen=False):
            self.fail_listen = fail_listen

        async def recv(self):
            return json.dumps({
                "type": "snapshot", "channel": "TEST",
                "member_id": "22222222-2222-4222-8222-222222222222",
                "resume_token": "token", "generation": client._generation,
                "channel_incarnation_id": client._incarnation, "revision": 1,
                "participant_count": 1, "eligible_from_index": 0, "next_burst_index": 1,
                "audio_policy": {"recovery_horizon_ms": 1000},
                "floor": {"burst_id": BURST_ID, "burst_index": 0, "owned": True},
            })

        async def send(self, raw):
            if isinstance(raw, bytes):
                return
            message = json.loads(raw)
            if message["type"] == "listen" and self.fail_listen:
                raise OSError("resume listen write failed")
            if message["type"] == "burst_end":
                ends.append(message["final_next_sequence"])
                if outcome == "timeout":
                    clock.value = deadline
                else:
                    await client._handle_control(parse_control(json.dumps({
                        "type": "ptt_ended", "burst_id": BURST_ID, "burst_index": 0,
                        "state": "sealed", "reason": "complete",
                        "final_next_sequence": message["final_next_sequence"],
                    })))

        async def close(self, code=1000):
            pass

    first = client._attach_transport(Socket())
    client._ready.set()

    class RecordingEncoder(FakeEncoder):
        def encode(self, pcm):
            encoded_at.append(clock.now())
            if len(encoded_at) == 1:
                client._detach_transport(first)
            return super().encode(pcm)

    resume_times = [0.5] if outcome == "short_resume" else [0.3, 0.6, 0.9]

    async def sleep(seconds):
        await clock.sleep(seconds)
        if resume_times and clock.now() >= resume_times[0]:
            attempts.append(resume_times.pop(0))
            context = client._attach_transport(Socket(fail_listen=outcome != "short_resume"))
            assert await client._handshake(context) == (outcome == "short_resume")
            if outcome != "short_resume":
                client._detach_transport(context)

    client._sleep = sleep
    await asyncio.wait_for(
        client._produce_audio(PcmAudio(bytes(100 * 640)), 100, outgoing, RecordingEncoder()), 2,
    )
    expected_frames = 100 if outcome == "short_resume" else 50
    assert encoded_at == pytest.approx([index * 0.02 for index in range(expected_frames)])
    assert attempts == ([0.5] if outcome == "short_resume" else [0.3, 0.6, 0.9])
    assert outgoing.final_next_sequence == expected_frames
    deadline = outgoing.finalization_deadline
    assert deadline == pytest.approx(clock.now() + 6)
    assert outgoing.stop_reason == (None if outcome == "short_resume" else "recovery_timeout")

    # Rejoining after capture stops cannot extend the frozen finalization budget.
    clock.value += 0.25
    client._freeze_outgoing(outgoing)
    resumed = client._attach_transport(Socket())
    assert await client._handshake(resumed)
    assert outgoing.finalization_deadline == deadline
    assert outgoing.final_next_sequence == expected_frames
    try:
        result = await asyncio.wait_for(client._finish_outgoing(outgoing, 100), 1)
        expected = SendResult("sent", "complete", BURST_ID) if outcome == "short_resume" else (
            SendResult("interrupted", "recovery_timeout", BURST_ID)
        )
        assert result == expected
        assert ends == [expected_frames]
        assert client.session_epoch == int(outcome == "timeout")
        assert outgoing.finalization_deadline == deadline
        assert len(encoded_at) == expected_frames
    finally:
        await client.stop()
