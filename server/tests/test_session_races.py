import asyncio
import json

import pytest

from app.client_session import ClientSession
from app.domain import StoredFrame, TalkBurst
from app.media_timing import ServerMediaTimingDiagnostics
from app.protocol import DOWNLINK_MEDIA_TYPE, decode_media
from app.session import SessionManager
from app.settings import Settings
from tests.helpers import audio_message


class DisconnectingSocket:
    def __init__(self, code: int) -> None:
        self.code = code

    async def receive(self) -> dict:
        return {"type": "websocket.disconnect", "code": self.code}


class AppDisconnectingSocket:
    def __init__(self) -> None:
        self.close_codes: list[int] = []

    async def receive(self) -> dict:
        return {
            "type": "websocket.receive",
            "text": json.dumps({"type": "disconnect"}),
        }

    async def close(self, *, code: int) -> None:
        self.close_codes.append(code)


class DrainingSocket:
    def __init__(self) -> None:
        self.messages: list[tuple[str, object]] = []
        self.close_codes: list[int] = []

    async def send_json(self, message: dict) -> None:
        self.messages.append(("json", message))

    async def send_bytes(self, message: bytes) -> None:
        self.messages.append(("bytes", message))

    async def close(self, code: int) -> None:
        self.close_codes.append(code)


class CursorBoundedFrames(dict):
    def values(self):
        raise AssertionError("listener pump scanned frames before its cursor")


def queued_json(session: ClientSession) -> list[dict]:
    messages = [message for kind, message, _, _ in session.outbound_queue if kind == "json"]
    session.outbound_queue.clear()
    session.queued_bytes = 0
    return messages


async def joined_session(manager: SessionManager, channel: str) -> ClientSession:
    session = ClientSession(object())
    manager.sessions[session.session_id] = session
    await manager.join(session, channel)
    queued_json(session)
    return session


@pytest.mark.asyncio
async def test_live_media_hooks_record_receive_gap_and_listener_handoff_delay() -> None:
    clock = [0]
    manager = SessionManager(Settings(), now_ms=lambda: clock[0])
    owner = await joined_session(manager, "ROOM")
    listener = await joined_session(manager, "ROOM")
    assert owner.member is not None
    created = await manager.registry.request_floor(
        member=owner.member,
        request_id="one",
        now_ms=0,
        initial_lease_ms=5_000,
    )
    burst = created.burst
    assert burst is not None
    owner_events: list[str] = []
    listener_events: list[str] = []
    owner.media_timing = ServerMediaTimingDiagnostics(owner.session_id, emit=owner_events.append)
    listener.media_timing = ServerMediaTimingDiagnostics(
        listener.session_id,
        emit=listener_events.append,
    )

    await manager.handle_audio(owner, audio_message(0, payload=b"one", burst_id=burst.burst_id))
    clock[0] = 150
    await manager.handle_audio(owner, audio_message(1, payload=b"two", burst_id=burst.burst_id))
    await manager.listen(listener, burst.index, 0)

    assert any("type=server_receive_gap" in event and "gap=150ms" in event for event in owner_events)
    assert any(
        "type=server_handoff_delay" in event
        and "oldest_age=150ms" in event
        and "newest_age=0ms" in event
        for event in listener_events
    )


@pytest.mark.asyncio
async def test_participant_presence_expires_and_recovers_without_disconnect() -> None:
    clock = [0]
    manager = SessionManager(Settings(), now_ms=lambda: clock[0])
    first = await joined_session(manager, "ROOM")
    second = await joined_session(manager, "ROOM")
    channel = first.channel
    assert channel is not None
    queued_json(first)

    clock[0] = 2_999
    manager.record_activity(first)
    assert manager.expire_stale_presence(clock[0]) == ()
    assert manager.participant_count(channel) == 2

    clock[0] = 3_000
    assert manager.expire_stale_presence(clock[0]) == (channel,)
    assert queued_json(first)[-1]["participant_count"] == 1
    assert queued_json(second)[-1]["participant_count"] == 1
    assert not second.presence_active
    assert not second.closing

    clock[0] = 3_001
    await manager.handle_text(
        second,
        json.dumps({"type": "ping", "id": 1, "sent_at_ms": clock[0]}),
    )
    assert queued_json(first)[-1]["participant_count"] == 2
    recovered_messages = queued_json(second)
    assert [message["type"] for message in recovered_messages] == ["channel_state", "pong"]
    assert recovered_messages[0]["participant_count"] == 2
    assert second.presence_active
    assert not second.closing

    clock[0] = 6_001
    manager.record_activity(first)
    manager.expire_stale_presence(clock[0])
    queued_json(first)
    queued_json(second)

    clock[0] = 6_002
    await manager.handle_audio(second, b"invalid")
    assert queued_json(first)[-1]["participant_count"] == 2
    audio_messages = queued_json(second)
    assert [message["type"] for message in audio_messages] == ["channel_state", "error"]
    assert second.presence_active
    assert not second.closing


@pytest.mark.asyncio
async def test_presence_count_never_broadcasts_zero() -> None:
    clock = [0]
    manager = SessionManager(Settings(), now_ms=lambda: clock[0])
    first = await joined_session(manager, "ROOM")
    second = await joined_session(manager, "ROOM")
    channel = first.channel
    assert channel is not None
    queued_json(first)

    clock[0] = 3_000
    manager.expire_stale_presence(clock[0])

    assert manager.participant_count(channel) == 1
    assert queued_json(first)[-1]["participant_count"] == 1
    assert queued_json(second)[-1]["participant_count"] == 1


@pytest.mark.asyncio
async def test_transport_loss_updates_presence_but_keeps_resume_membership() -> None:
    clock = [0]
    manager = SessionManager(Settings(), now_ms=lambda: clock[0])
    first = await joined_session(manager, "ROOM")
    second = await joined_session(manager, "ROOM")
    channel = first.channel
    token = second.resume_token
    assert channel is not None and second.member is not None and token is not None
    member_id = second.member.member_id
    queued_json(first)

    clock[0] = 100
    await manager.disconnect(second)

    assert member_id in channel.members
    assert queued_json(first)[-1]["participant_count"] == 1

    resumed = ClientSession(object(), last_activity_ms=clock[0])
    manager.sessions[resumed.session_id] = resumed
    await manager.resume(resumed, token, 2)

    snapshot = next(message for message in queued_json(resumed) if message["type"] == "snapshot")
    assert snapshot["participant_count"] == 2


@pytest.mark.asyncio
async def test_finish_racing_floor_timeout_terminalizes_exactly_once() -> None:
    manager = SessionManager(Settings())
    owner = await joined_session(manager, "ROOM")
    listener = await joined_session(manager, "ROOM")
    queued_json(owner)
    await manager.listen(listener, 0, 0)
    await manager.request_floor(owner, "one")
    grant = next(message for message in queued_json(owner) if message["type"] == "ptt_granted")
    queued_json(listener)

    await asyncio.gather(
        manager.end_burst(owner, grant["burst_id"], 0),
        manager.registry.expire_floor(owner.channel, grant["burst_id"], manager.now_ms()),
    )

    owner_messages = queued_json(owner)
    listener_messages = queued_json(listener)
    assert sum(message["type"] == "ptt_ended" for message in owner_messages) == 1
    assert sum(message["type"] == "burst_sealed" for message in listener_messages) == 1
    assert owner.channel is not None and owner.channel.current_burst is None


@pytest.mark.asyncio
async def test_floor_timer_survives_disconnect_and_notifies_resumed_owner() -> None:
    clock = [0]
    blocked = asyncio.Event()

    async def controlled_wait(delay: float) -> None:
        if delay > 0:
            await blocked.wait()

    manager = SessionManager(Settings(), wait=controlled_wait, now_ms=lambda: clock[0])
    owner = await joined_session(manager, "ROOM")
    token = owner.resume_token
    assert token is not None
    await manager.request_floor(owner, "one")
    grant = next(message for message in queued_json(owner) if message["type"] == "ptt_granted")

    clock[0] = 100
    await manager.disconnect(owner)
    assert grant["burst_id"] in manager.floor_tasks
    assert owner.channel is not None and owner.channel.current_burst is not None

    resumed = ClientSession(object())
    manager.sessions[resumed.session_id] = resumed
    clock[0] = 200
    await manager.resume(resumed, token, 2)
    queued_json(resumed)

    clock[0] = 5_000
    await manager.enforce_floor_timeout(owner.channel, grant["burst_id"], 0)

    terminal = next(message for message in queued_json(resumed) if message["type"] == "ptt_ended")
    assert terminal["burst_id"] == grant["burst_id"]
    assert terminal["reason"] == "lease_expired"
    assert owner.channel.current_burst is None
    manager.cancel_floor_timer(grant["burst_id"])
    await asyncio.sleep(0)


@pytest.mark.asyncio
@pytest.mark.parametrize(("code", "released"), [(1000, True), (1001, False)])
async def test_websocket_close_code_controls_intentional_floor_release(
    monkeypatch: pytest.MonkeyPatch, code: int, released: bool
) -> None:
    manager = SessionManager(Settings(), now_ms=lambda: 0)
    owner = await joined_session(manager, "ROOM")
    await manager.request_floor(owner, "one")
    grant = next(message for message in queued_json(owner) if message["type"] == "ptt_granted")

    async def existing_session(_socket: object) -> ClientSession:
        return owner

    monkeypatch.setattr(manager, "connect", existing_session)
    await manager.handle(DisconnectingSocket(code))  # type: ignore[arg-type]

    assert owner.channel is not None
    assert (owner.channel.current_burst is None) is released
    if not released:
        manager.cancel_floor_timer(grant["burst_id"])
    await asyncio.sleep(0)


@pytest.mark.asyncio
async def test_app_disconnect_control_closes_and_seals_owned_floor(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    manager = SessionManager(Settings(), now_ms=lambda: 0)
    owner = await joined_session(manager, "ROOM")
    member = owner.member
    assert member is not None
    await manager.request_floor(owner, "one")
    grant = next(message for message in queued_json(owner) if message["type"] == "ptt_granted")
    channel = owner.channel
    assert channel is not None
    burst = channel.bursts[grant["burst_id"]]
    socket = AppDisconnectingSocket()
    owner.websocket = socket  # type: ignore[assignment]

    async def existing_session(_socket: object) -> ClientSession:
        return owner

    monkeypatch.setattr(manager, "connect", existing_session)
    await manager.handle(socket)  # type: ignore[arg-type]

    assert socket.close_codes == [1000]
    assert channel.current_burst is None
    assert burst.sealed and burst.release_reason == "member_left"
    assert member.member_id not in channel.members
    assert owner.session_id not in manager.sessions
    await asyncio.sleep(0)


@pytest.mark.asyncio
async def test_request_ids_are_idempotent_per_member() -> None:
    manager = SessionManager(Settings(), now_ms=lambda: 0)
    owner = await joined_session(manager, "ROOM")
    contender = await joined_session(manager, "ROOM")
    queued_json(owner)
    await manager.request_floor(owner, "one")
    first_grant = next(
        message for message in queued_json(owner) if message["type"] == "ptt_granted"
    )
    channel = owner.channel
    assert channel is not None
    next_index = channel.next_burst_index

    await manager.request_floor(owner, "one")
    duplicate_grant = next(
        message for message in queued_json(owner) if message["type"] == "ptt_granted"
    )
    assert duplicate_grant["burst_id"] == first_grant["burst_id"]
    assert channel.next_burst_index == next_index

    queued_json(contender)
    await manager.request_floor(contender, "one")
    assert queued_json(contender) == [
        {"type": "ptt_denied", "request_id": "one", "reason": "channel_busy"}
    ]

    await manager.end_burst(owner, first_grant["burst_id"], 0)
    queued_json(owner)
    queued_json(contender)

    await manager.request_floor(owner, "one")

    assert queued_json(owner) == [
        {"type": "ptt_denied", "request_id": "one", "reason": "invalid_state"}
    ]
    assert channel.current_burst is None
    assert channel.next_burst_index == next_index

    await manager.request_floor(contender, "one")
    second_grant = next(
        message for message in queued_json(contender) if message["type"] == "ptt_granted"
    )
    assert second_grant["burst_index"] == next_index
    await manager.end_burst(contender, second_grant["burst_id"], 0)
    queued_json(contender)


@pytest.mark.asyncio
async def test_conflicting_terminal_watermark_repeats_authoritative_end() -> None:
    manager = SessionManager(Settings(), now_ms=lambda: 0)
    owner = await joined_session(manager, "ROOM")
    await manager.request_floor(owner, "one")
    grant = next(message for message in queued_json(owner) if message["type"] == "ptt_granted")
    await manager.end_burst(owner, grant["burst_id"], 0)
    queued_json(owner)

    await manager.handle_text(
        owner,
        json.dumps(
            {
                "type": "burst_end",
                "burst_id": grant["burst_id"],
                "final_next_sequence": 1,
            }
        ),
    )

    messages = queued_json(owner)
    assert [message["type"] for message in messages] == ["audio_rejected", "ptt_ended"]
    assert messages[0]["reason"] == "invalid_range"
    assert messages[1]["burst_id"] == grant["burst_id"]


@pytest.mark.asyncio
async def test_invalid_open_watermark_does_not_release_floor() -> None:
    manager = SessionManager(Settings(), now_ms=lambda: 0)
    owner = await joined_session(manager, "ROOM")
    await manager.request_floor(owner, "one")
    grant = next(message for message in queued_json(owner) if message["type"] == "ptt_granted")
    assert owner.member is not None
    await manager.registry.append_audio(
        member=owner.member,
        burst_id=grant["burst_id"],
        first_sequence=0,
        packets=(b"frame",),
        now_ms=0,
        renewal_lease_ms=5_000,
    )

    await manager.handle_text(
        owner,
        json.dumps(
            {
                "type": "burst_end",
                "burst_id": grant["burst_id"],
                "final_next_sequence": 0,
            }
        ),
    )

    messages = queued_json(owner)
    assert [message["type"] for message in messages] == ["audio_rejected"]
    assert owner.channel is not None
    assert owner.channel.current_burst is not None
    manager.cancel_floor_timer(grant["burst_id"])
    await asyncio.sleep(0)


@pytest.mark.asyncio
async def test_duplicate_audio_that_resolves_gaps_pumps_listener() -> None:
    clock = [100]
    manager = SessionManager(Settings(), now_ms=lambda: clock[0])
    owner = await joined_session(manager, "ROOM")
    listener = await joined_session(manager, "ROOM")
    await manager.listen(listener, 0, 0)
    await manager.request_floor(owner, "one")
    grant = next(message for message in queued_json(owner) if message["type"] == "ptt_granted")
    queued_json(listener)

    packet = audio_message(2, burst_id=grant["burst_id"])
    await manager.handle_audio(owner, packet)
    queued_json(owner)
    queued_json(listener)

    clock[0] = 5_121
    await manager.handle_audio(owner, packet)

    owner_messages = queued_json(owner)
    listener_messages = queued_json(listener)
    assert next(message for message in owner_messages if message["type"] == "uplink_ack")[
        "next_sequence"
    ] == 3
    assert next(message for message in listener_messages if message["type"] == "burst_gaps")[
        "ranges"
    ] == [{"first_sequence": 0, "count": 2}]
    manager.cancel_floor_timer(grant["burst_id"])
    await asyncio.sleep(0)


@pytest.mark.asyncio
async def test_mixed_expired_audio_acks_and_delivers_recoverable_suffix() -> None:
    clock = [0]
    manager = SessionManager(Settings(), now_ms=lambda: clock[0])
    owner = await joined_session(manager, "ROOM")
    listener = await joined_session(manager, "ROOM")
    second_listener = await joined_session(manager, "ROOM")
    await manager.listen(listener, 0, 0)
    await manager.listen(second_listener, 0, 0)
    await manager.request_floor(owner, "one")
    grant = next(message for message in queued_json(owner) if message["type"] == "ptt_granted")
    resume_token = owner.resume_token
    assert resume_token is not None
    queued_json(listener)

    clock[0] = 5_010
    packets = tuple(bytes([sequence]) for sequence in range(50))
    await manager.handle_audio(
        owner,
        audio_message(0, burst_id=grant["burst_id"], packets=packets),
    )

    owner_messages = queued_json(owner)
    assert next(message for message in owner_messages if message["type"] == "uplink_ack")[
        "next_sequence"
    ] == 50
    assert not any(message["type"] == "audio_rejected" for message in owner_messages)

    for recipient in (listener, second_listener):
        recipient_messages = list(recipient.outbound_queue)
        gap_index = next(
            index
            for index, (kind, message, _, _) in enumerate(recipient_messages)
            if kind == "json" and message["type"] == "burst_gaps"
        )
        first_media_index = next(
            index
            for index, (kind, _, _, _) in enumerate(recipient_messages)
            if kind == "bytes"
        )
        assert gap_index < first_media_index
        gap = next(
            message
            for kind, message, _, _ in recipient_messages
            if kind == "json" and message["type"] == "burst_gaps"
        )
        assert gap["ranges"] == [{"first_sequence": 0, "count": 1}]
        media = [
            decode_media(message, expected_type=DOWNLINK_MEDIA_TYPE)
            for kind, message, _, _ in recipient_messages
            if kind == "bytes"
        ]
        coordinates = [
            (envelope.burst_id, envelope.first_sequence + offset, packet)
            for envelope in media
            for offset, packet in enumerate(envelope.packets)
        ]
        assert coordinates == [
            (grant["burst_id"], sequence, packets[sequence]) for sequence in range(1, 50)
        ]

    listener.outbound_queue.clear()
    listener.queued_bytes = 0
    second_listener.outbound_queue.clear()
    second_listener.queued_bytes = 0
    await manager.disconnect(owner)
    resumed = ClientSession(object(), last_activity_ms=clock[0])
    manager.sessions[resumed.session_id] = resumed
    await manager.resume(resumed, resume_token, 2)
    queued_json(resumed)
    listener.outbound_queue.clear()
    listener.queued_bytes = 0
    second_listener.outbound_queue.clear()
    second_listener.queued_bytes = 0

    await manager.handle_audio(
        resumed,
        audio_message(0, burst_id=grant["burst_id"], packets=packets),
    )

    replay_messages = queued_json(resumed)
    assert next(message for message in replay_messages if message["type"] == "uplink_ack")[
        "next_sequence"
    ] == 50
    assert not any(message["type"] == "audio_rejected" for message in replay_messages)
    assert list(listener.outbound_queue) == []
    assert list(second_listener.outbound_queue) == []

    await manager.end_burst(resumed, grant["burst_id"], 50)

    terminal = queued_json(resumed)
    assert next(message for message in terminal if message["type"] == "ptt_ended")[
        "burst_id"
    ] == grant["burst_id"]
    for recipient in (listener, second_listener):
        messages = queued_json(recipient)
        assert [message["type"] for message in messages[:2]] == [
            "burst_released",
            "burst_sealed",
        ]
        assert messages[1]["final_next_sequence"] == 50
        assert messages[1]["reason"] == "expired"
    await asyncio.sleep(0)


@pytest.mark.asyncio
async def test_listener_pump_defensively_resets_evicted_cursor() -> None:
    clock = [0]
    manager = SessionManager(Settings(), now_ms=lambda: clock[0])
    owner = await joined_session(manager, "ROOM")
    listener = await joined_session(manager, "ROOM")
    await manager.request_floor(owner, "one")
    grant = next(message for message in queued_json(owner) if message["type"] == "ptt_granted")
    assert owner.member is not None
    await manager.registry.append_audio(
        member=owner.member,
        burst_id=grant["burst_id"],
        first_sequence=0,
        packets=(b"one",),
        now_ms=0,
        renewal_lease_ms=2_000,
    )
    await manager.registry.end_burst(owner.member, grant["burst_id"], 2, 0)
    await manager.registry.cleanup(5_001)
    await manager.registry.cleanup(15_001)
    queued_json(listener)
    listener.listen_burst_index = 0
    listener.listen_next_sequence = 0

    await manager.pump_listener(listener)

    reset = next(message for message in queued_json(listener) if message["type"] == "listen_reset")
    assert reset == {
        "type": "listen_reset",
        "burst_index": 0,
        "next_sequence": 1,
        "reason": "expired",
    }
    manager.cancel_floor_timer(grant["burst_id"])
    await asyncio.sleep(0)


def test_downlink_messages_use_the_largest_prefix_that_fits_the_wire_envelope() -> None:
    manager = SessionManager(Settings())
    burst = TalkBurst(
        burst_id="11111111-1111-4111-8111-111111111111",
        index=0,
        owner_member_id="owner",
        request_id="request",
        grant_time_ms=0,
        lease_expires_at_ms=5_000,
        eligible_member_ids=frozenset({"listener"}),
    )
    packets = tuple(
        bytes([index]) * size
        for index, size in enumerate((1_275, 1_275, 1_275, 239, 1))
    )
    frames = tuple(
        StoredFrame(sequence, packet, sequence * 20)
        for sequence, packet in enumerate(packets)
    )

    messages = manager.downlink_messages(burst, frames)

    assert len(messages) == 2
    assert len(messages[0][0]) == 4_096
    decoded = [
        decode_media(message, expected_type=DOWNLINK_MEDIA_TYPE)
        for message, _, _, _ in messages
    ]
    coordinates = tuple(
        (envelope.burst_id, envelope.first_sequence + offset, packet)
        for envelope in decoded
        for offset, packet in enumerate(envelope.packets)
    )
    assert coordinates == tuple(
        (burst.burst_id, sequence, packet) for sequence, packet in enumerate(packets)
    )


def test_downlink_messages_preserve_nonzero_sequences_gaps_and_count_limit() -> None:
    manager = SessionManager(Settings())
    burst = TalkBurst(
        burst_id="11111111-1111-4111-8111-111111111111",
        index=0,
        owner_member_id="owner",
        request_id="request",
        grant_time_ms=0,
        lease_expires_at_ms=5_000,
        eligible_member_ids=frozenset({"listener"}),
    )
    frames = tuple(
        StoredFrame(sequence, bytes([sequence % 256]))
        for sequence in (*range(10, 61), 62)
    )

    messages = manager.downlink_messages(burst, frames)
    decoded = [
        decode_media(message, expected_type=DOWNLINK_MEDIA_TYPE)
        for message, _, _, _ in messages
    ]
    coordinates = tuple(
        (envelope.first_sequence + offset, packet)
        for envelope in decoded
        for offset, packet in enumerate(envelope.packets)
    )

    assert [len(envelope.packets) for envelope in decoded] == [50, 1, 1]
    assert coordinates == tuple((frame.sequence, frame.payload) for frame in frames)


@pytest.mark.asyncio
async def test_fast_history_dump_respects_budget_without_closing_healthy_socket() -> None:
    clock = [0]
    manager = SessionManager(Settings(recovery_horizon_ms=60_000), now_ms=lambda: clock[0])
    owner = await joined_session(manager, "ROOM")
    listener = await joined_session(manager, "ROOM")
    await manager.listen(listener, 0, 0)
    await manager.request_floor(owner, "one")
    grant = next(message for message in queued_json(owner) if message["type"] == "ptt_granted")
    queued_json(listener)
    assert owner.member is not None
    clock[0] = 15_000
    await manager.registry.append_audio(
        member=owner.member,
        burst_id=grant["burst_id"],
        first_sequence=0,
        packets=tuple(b"x" * 40 for _ in range(750)),
        now_ms=clock[0],
        renewal_lease_ms=2_000,
    )
    await manager.registry.end_burst(owner.member, grant["burst_id"], 750, clock[0])

    socket = DrainingSocket()
    listener.websocket = socket  # type: ignore[assignment]
    listener.max_outbound_bytes = 5_000
    listener.start_outbound()
    try:
        await manager.pump_listener(listener)
        for _ in range(2_000):
            sealed = any(
                kind == "json" and isinstance(message, dict) and message.get("type") == "burst_sealed"
                for kind, message in socket.messages
            )
            if sealed:
                break
            await asyncio.sleep(0)
        media = [
            decode_media(message, expected_type=DOWNLINK_MEDIA_TYPE)
            for kind, message in socket.messages
            if kind == "bytes" and isinstance(message, bytes)
        ]
        assert sum(len(envelope.packets) for envelope in media) == 750
        assert not listener.closing
        assert socket.close_codes == []
    finally:
        await listener.stop_outbound()
        manager.cancel_floor_timer(grant["burst_id"])


@pytest.mark.asyncio
async def test_listener_pump_reads_only_frames_at_or_after_its_cursor() -> None:
    manager = SessionManager(Settings(), now_ms=lambda: 100)
    owner = await joined_session(manager, "ROOM")
    listener = await joined_session(manager, "ROOM")
    await manager.listen(listener, 0, 0)
    assert owner.member is not None
    created = await manager.registry.request_floor(
        member=owner.member,
        request_id="one",
        now_ms=0,
        initial_lease_ms=5_000,
    )
    burst = created.burst
    assert burst is not None
    await manager.registry.append_audio(
        member=owner.member,
        burst_id=burst.burst_id,
        first_sequence=0,
        packets=(b"one", b"two", b"three"),
        now_ms=100,
        renewal_lease_ms=2_000,
    )
    await manager.registry.end_burst(owner.member, burst.burst_id, 3, 100)
    listener.downlink_next_sequences[burst.burst_id] = 2
    burst.frames = CursorBoundedFrames(burst.frames)

    await manager.pump_listener(listener)

    media = [
        decode_media(message, expected_type=DOWNLINK_MEDIA_TYPE)
        for kind, message, _, _ in listener.outbound_queue
        if kind == "bytes" and isinstance(message, bytes)
    ]
    assert [packet for envelope in media for packet in envelope.packets] == [b"three"]
