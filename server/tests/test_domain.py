from copy import deepcopy

import pytest

from app.domain import (
    AudioRejected,
    ChannelFull,
    ChannelRegistry,
    InvalidChannel,
    MAX_FRAMES_PER_BURST,
    NotJoined,
    RecoveryPolicy,
    ServerBusy,
    TalkBurst,
    normalize_channel_code,
)
from app.protocol import MAX_OPUS_PACKET_BYTES


def burst(**overrides) -> TalkBurst:
    values = {
        "burst_id": "11111111-1111-4111-8111-111111111111",
        "index": 0,
        "owner_member_id": "owner",
        "request_id": "request",
        "grant_time_ms": 0,
        "lease_expires_at_ms": 5_000,
        "eligible_member_ids": frozenset({"listener"}),
    }
    values.update(overrides)
    return TalkBurst(**values)


def append(item: TalkBurst, sequence: int, packets: tuple[bytes, ...], now_ms: int = 100):
    return item.append(
        first_sequence=sequence,
        packets=packets,
        member_id="owner",
        now_ms=now_ms,
        renewal_lease_ms=2_000,
        remaining_channel_bytes=100_000,
        remaining_global_bytes=100_000,
    )


def test_stored_frame_keeps_server_receive_timestamp() -> None:
    item = burst()

    added = item.append(
        first_sequence=0,
        packets=(b"one",),
        member_id="owner",
        now_ms=150,
        renewal_lease_ms=2_000,
        remaining_channel_bytes=100_000,
        remaining_global_bytes=100_000,
        received_at_ms=123,
    )

    assert added[0].received_at_ms == 123


@pytest.mark.parametrize(
    ("horizon_ms", "history_ms"),
    [
        (1_000, 15_000),
        (5_000, 15_000),
        (60_000, 180_000),
    ],
)
def test_recovery_policy_contract_vectors(
    horizon_ms: int,
    history_ms: int,
) -> None:
    policy = RecoveryPolicy(horizon_ms)

    assert policy.server_history_ms == history_ms


def test_open_burst_resolves_only_positions_older_than_horizon() -> None:
    item = burst()
    append(item, 2, (b"three",), now_ms=100)

    assert not item.advance_resolution(5_000)
    assert item.resolved_next_sequence == 0
    assert item.advance_resolution(5_021)
    assert item.lost_sequences == {0, 1}
    assert item.resolved_next_sequence == 3
    with pytest.raises(AudioRejected, match="expired"):
        append(item, 1, (b"late",), now_ms=5_022)


def test_channel_code_validation_is_strict() -> None:
    assert normalize_channel_code("room1") == "ROOM1"
    assert normalize_channel_code("446.00625") == "446.00625"
    assert normalize_channel_code("room.1") == "ROOM.1"
    assert normalize_channel_code("a.b.c") == "A.B.C"
    for invalid in (
        "",
        ".",
        "...",
        ".ROOM",
        "ROOM.",
        "ROOM..1",
        "WITH-DASH",
        "WITH SPACE",
        "WITH,COMMA",
    ):
        with pytest.raises(InvalidChannel):
            normalize_channel_code(invalid)
    with pytest.raises(InvalidChannel):
        normalize_channel_code("TOOLONG", max_length=4)


@pytest.mark.asyncio
async def test_dotted_channel_codes_share_one_channel() -> None:
    registry = ChannelRegistry()
    first, _, _ = await registry.fresh_join("room.1", "first", 0)
    second, _, _ = await registry.fresh_join("ROOM.1", "second", 0)

    assert first is second
    assert first.code == "ROOM.1"
    assert len(first.members) == 2


def test_sparse_append_and_exact_duplicate_are_idempotent() -> None:
    item = burst()
    assert len(append(item, 2, (b"three",))) == 1
    assert len(append(item, 0, (b"one", b"two"))) == 2
    assert append(item, 0, (b"one", b"two")) == ()
    assert sorted(item.frames) == [0, 1, 2]


def test_conflicting_duplicate_rejects_the_whole_envelope() -> None:
    item = burst()
    append(item, 0, (b"one",))
    with pytest.raises(AudioRejected, match="payload_mismatch"):
        append(item, 0, (b"other", b"two"))
    assert sorted(item.frames) == [0]


def test_release_is_immediate_and_late_tail_can_complete() -> None:
    item = burst()
    append(item, 0, (b"one",), now_ms=100)
    assert item.end(2, 100)
    assert item.state == "draining"
    append(item, 1, (b"two",), now_ms=200)
    assert item.state == "sealed"
    assert item.seal_reason == "complete"


def test_missing_tail_seals_with_resolved_losses_after_five_seconds() -> None:
    item = burst()
    append(item, 0, (b"one",), now_ms=100)
    item.end(4, 100)
    assert not item.maybe_seal(5_000)
    assert item.maybe_seal(5_101)
    assert item.lost_sequences == {1, 2, 3}
    assert item.resolved_next_sequence == 4
    assert item.seal_reason == "expired"


def test_payload_older_than_uplink_window_is_rejected() -> None:
    with pytest.raises(AudioRejected, match="expired"):
        append(burst(), 0, (b"old",), now_ms=5_001)


def test_mixed_expired_envelope_keeps_every_recoverable_frame() -> None:
    item = burst()

    added = append(item, 0, tuple(bytes([sequence]) for sequence in range(50)), now_ms=5_010)

    assert [frame.sequence for frame in added] == list(range(1, 50))
    assert sorted(item.frames) == list(range(1, 50))
    assert item.lost_sequences == {0}
    assert item.resolved_next_sequence == 50


def test_recovery_horizon_boundary_is_inclusive_per_frame() -> None:
    item = burst()

    added = append(item, 0, (b"boundary", b"fresh"), now_ms=5_000)

    assert [frame.sequence for frame in added] == [0, 1]
    with pytest.raises(AudioRejected, match="expired"):
        append(burst(), 0, (b"late",), now_ms=5_001)


def test_retained_exact_duplicate_remains_idempotent_after_its_recovery_age() -> None:
    item = burst()
    append(item, 0, (b"stored",), now_ms=100)

    assert append(item, 0, (b"stored",), now_ms=5_001) == ()
    with pytest.raises(AudioRejected, match="payload_mismatch"):
        append(item, 0, (b"different",), now_ms=5_001)


def test_mixed_expired_envelope_remains_atomic_on_payload_conflict() -> None:
    item = burst()
    append(item, 2, (b"original",), now_ms=100)
    before = deepcopy(item)

    with pytest.raises(AudioRejected, match="payload_mismatch"):
        append(item, 0, (b"expired", b"new", b"conflict"), now_ms=5_010)

    assert item == before


def test_mixed_expired_envelope_remains_atomic_when_server_is_busy() -> None:
    item = burst()
    before = deepcopy(item)

    with pytest.raises(AudioRejected, match="server_busy"):
        item.append(
            first_sequence=0,
            packets=(b"expired", b"fresh"),
            member_id="owner",
            now_ms=5_010,
            renewal_lease_ms=2_000,
            remaining_channel_bytes=1,
            remaining_global_bytes=100_000,
        )

    assert item == before


@pytest.mark.parametrize(
    ("remaining_channel_bytes", "remaining_global_bytes"),
    [(1, 100_000), (100_000, 1)],
)
def test_mixed_expired_envelope_checks_each_memory_limit_atomically(
    remaining_channel_bytes: int,
    remaining_global_bytes: int,
) -> None:
    item = burst()
    before = deepcopy(item)

    with pytest.raises(AudioRejected, match="server_busy"):
        item.append(
            first_sequence=0,
            packets=(b"expired", b"fresh"),
            member_id="owner",
            now_ms=5_010,
            renewal_lease_ms=2_000,
            remaining_channel_bytes=remaining_channel_bytes,
            remaining_global_bytes=remaining_global_bytes,
        )

    assert item == before


def test_final_boundary_rejects_mixed_envelope_without_partial_changes() -> None:
    item = burst()
    item.end(2, 100)
    before = deepcopy(item)

    with pytest.raises(AudioRejected, match="invalid_range"):
        append(item, 0, (b"expired", b"valid", b"past-final"), now_ms=5_010)

    assert item == before


def test_permanent_gap_is_not_restored_beside_recoverable_frames() -> None:
    item = burst()
    append(item, 1, (b"one",), now_ms=100)
    assert item.advance_resolution(5_001)
    lease_before = item.lease_expires_at_ms

    added = append(item, 0, (b"late", b"one", b"two"), now_ms=5_010)

    assert [frame.sequence for frame in added] == [2]
    assert item.lost_sequences == {0}
    assert sorted(item.frames) == [1, 2]
    assert item.resolved_next_sequence == 3
    assert item.lease_expires_at_ms > lease_before


def test_old_exact_duplicate_does_not_extend_floor_lease() -> None:
    item = burst()
    append(item, 0, (b"stored",), now_ms=100)
    lease_before = item.lease_expires_at_ms

    assert append(item, 0, (b"stored",), now_ms=5_001) == ()

    assert item.lease_expires_at_ms == lease_before


@pytest.mark.parametrize(
    ("changes", "reason"),
    [
        ({"member_id": "other"}, "unknown_burst"),
        ({"packets": ()}, "invalid_range"),
        ({"first_sequence": -1}, "invalid_range"),
        ({"first_sequence": MAX_FRAMES_PER_BURST}, "invalid_range"),
        ({"packets": (b"",)}, "invalid_range"),
        ({"packets": (b"x" * (MAX_OPUS_PACKET_BYTES + 1),)}, "invalid_range"),
        ({"first_sequence": 100, "now_ms": 0}, "invalid_range"),
        ({"remaining_channel_bytes": 1}, "server_busy"),
        ({"remaining_global_bytes": 1}, "server_busy"),
    ],
)
def test_append_rejects_invalid_envelopes_atomically(changes, reason: str) -> None:
    item = burst()
    arguments = {
        "first_sequence": 0,
        "packets": (b"valid",),
        "member_id": "owner",
        "now_ms": 100,
        "renewal_lease_ms": 2_000,
        "remaining_channel_bytes": 100_000,
        "remaining_global_bytes": 100_000,
    }
    with pytest.raises(AudioRejected, match=reason):
        item.append(**(arguments | changes))
    assert not item.frames


def test_terminal_operations_are_idempotent_and_bounded() -> None:
    item = burst()
    assert item.end(0, 100)
    assert item.sealed and item.released
    assert not item.end(0, 200)
    with pytest.raises(AudioRejected, match="invalid_range"):
        item.end(1, 200)
    with pytest.raises(AudioRejected, match="invalid_range"):
        burst().end(MAX_FRAMES_PER_BURST + 1, 0)
    with pytest.raises(AudioRejected, match="expired"):
        append(item, 0, (b"late",), 200)
    assert not item.force_seal("again", 300)

    watermark = burst()
    append(watermark, 2, (b"three",), 100)
    with pytest.raises(AudioRejected, match="invalid_range"):
        watermark.end(2, 100)


def test_interrupted_burst_derives_watermark_and_history_expires_per_frame() -> None:
    item = burst()
    append(item, 2, (b"three",), 100)
    assert item.release("transport_lost", 100)
    assert not item.release("again", 101)
    assert not item.maybe_seal(5_099)
    assert item.maybe_seal(5_100)
    assert item.final_next_sequence == 3
    assert item.lost_sequences == {0, 1}
    assert item.resolved_next_sequence == 3
    assert not item.history_expired(15_000)
    assert item.evict_history(15_041) > 0
    assert item.history_expired(20_101)


@pytest.mark.asyncio
async def test_listen_resets_to_history_floor_while_burst_metadata_remains() -> None:
    registry = ChannelRegistry()
    channel, owner, _ = await registry.fresh_join("ROOM", "owner", 0)
    _, listener, _ = await registry.fresh_join("ROOM", "listener", 0)
    grant = await registry.request_floor(
        member=owner, request_id="one", now_ms=0, initial_lease_ms=5_000
    )
    assert grant.burst is not None
    burst_id = grant.burst.burst_id
    await registry.append_audio(
        member=owner,
        burst_id=burst_id,
        first_sequence=0,
        packets=(b"one",),
        now_ms=0,
        renewal_lease_ms=2_000,
    )
    await registry.end_burst(owner, burst_id, 2, 0)
    await registry.cleanup(5_001)
    await registry.cleanup(15_001)

    burst = channel.bursts[burst_id]
    result = await registry.resolve_listen(listener, 0, 0, 15_001)

    assert not burst.frames
    assert not burst.history_expired(15_001)
    assert burst.history_floor_sequence == 1
    assert (result.status, result.burst_index, result.next_sequence, result.reason) == (
        "reset",
        0,
        1,
        "expired",
    )


@pytest.mark.asyncio
async def test_cleanup_advances_known_gaps_in_open_burst() -> None:
    registry = ChannelRegistry()
    channel, owner, _ = await registry.fresh_join("ROOM", "owner", 0)
    grant = await registry.request_floor(
        member=owner, request_id="one", now_ms=0, initial_lease_ms=5_000
    )
    assert grant.burst is not None
    await registry.append_audio(
        member=owner,
        burst_id=grant.burst.burst_id,
        first_sequence=2,
        packets=(b"three",),
        now_ms=100,
        renewal_lease_ms=5_000,
    )

    assert await registry.cleanup(5_000) == ()
    changed = await registry.cleanup(5_021)

    assert changed == ((channel, grant.burst),)
    assert grant.burst.state == "open"
    assert grant.burst.resolved_next_sequence == 3
    assert grant.burst.lost_sequences == {0, 1}


def test_force_seal_empty_burst_is_terminal() -> None:
    item = burst()
    assert item.force_seal("canceled", 10)
    assert item.final_next_sequence == 0
    assert item.seal_reason == "canceled"


@pytest.mark.asyncio
async def test_floor_releases_before_late_tail_arrives() -> None:
    registry = ChannelRegistry()
    channel, owner, _ = await registry.fresh_join("ROOM", "owner", 0)
    _, listener, _ = await registry.fresh_join("ROOM", "listener", 0)
    first = await registry.request_floor(
        member=owner, request_id="one", now_ms=0, initial_lease_ms=5_000
    )
    assert first.burst is not None
    await registry.end_burst(owner, first.burst.burst_id, 1, 100)
    assert channel.current_burst is None
    second = await registry.request_floor(
        member=listener, request_id="two", now_ms=101, initial_lease_ms=5_000
    )
    assert second.created
    _, old, added, _ = await registry.append_audio(
        member=owner,
        burst_id=first.burst.burst_id,
        first_sequence=0,
        packets=(b"tail",),
        now_ms=200,
        renewal_lease_ms=2_000,
    )
    assert added and old.sealed


@pytest.mark.asyncio
async def test_late_joiner_and_owner_are_not_eligible_for_old_burst() -> None:
    registry = ChannelRegistry()
    channel, owner, _ = await registry.fresh_join("ROOM", "owner", 0)
    _, listener, _ = await registry.fresh_join("ROOM", "listener", 0)
    result = await registry.request_floor(
        member=owner, request_id="one", now_ms=0, initial_lease_ms=5_000
    )
    assert result.burst is not None
    _, late, _ = await registry.fresh_join("ROOM", "late", 1)
    assert channel.eligible(listener, result.burst)
    assert not channel.eligible(owner, result.burst)
    assert not channel.eligible(late, result.burst)


@pytest.mark.asyncio
async def test_disconnected_member_eligibility_expires_for_new_bursts() -> None:
    registry = ChannelRegistry()
    channel, owner, _ = await registry.fresh_join("ROOM", "owner", 0)
    _, listener, token = await registry.fresh_join("ROOM", "listener", 0)
    old = await registry.request_floor(
        member=owner, request_id="old", now_ms=0, initial_lease_ms=5_000
    )
    assert old.burst is not None
    await registry.append_audio(
        member=owner,
        burst_id=old.burst.burst_id,
        first_sequence=0,
        packets=(b"old",),
        now_ms=0,
        renewal_lease_ms=2_000,
    )
    await registry.end_burst(owner, old.burst.burst_id, 1, 0)
    await registry.transport_lost(listener, "listener", 0)

    before = await registry.request_floor(
        member=owner, request_id="before", now_ms=14_999, initial_lease_ms=5_000
    )
    assert before.burst is not None and channel.eligible(listener, before.burst)
    await registry.end_burst(owner, before.burst.burst_id, 0, 14_999)
    at_boundary = await registry.request_floor(
        member=owner, request_id="boundary", now_ms=15_000, initial_lease_ms=5_000
    )
    assert at_boundary.burst is not None
    assert not channel.eligible(listener, at_boundary.burst)
    assert listener.member_id in channel.members
    await registry.end_burst(owner, at_boundary.burst.burst_id, 0, 15_000)

    resumed = await registry.resume(
        token=token, generation=2, transport_id="resumed", now_ms=15_000
    )
    assert resumed.accepted
    after_resume = await registry.request_floor(
        member=owner, request_id="after", now_ms=15_000, initial_lease_ms=5_000
    )
    assert after_resume.burst is not None and channel.eligible(listener, after_resume.burst)


@pytest.mark.asyncio
async def test_retained_byte_counters_follow_append_duplicate_and_eviction() -> None:
    registry = ChannelRegistry()
    first_channel, first_owner, _ = await registry.fresh_join("ONE", "one", 0)
    second_channel, second_owner, _ = await registry.fresh_join("TWO", "two", 0)
    first = await registry.request_floor(
        member=first_owner, request_id="first", now_ms=0, initial_lease_ms=5_000
    )
    second = await registry.request_floor(
        member=second_owner, request_id="second", now_ms=0, initial_lease_ms=5_000
    )
    assert first.burst is not None and second.burst is not None
    _, _, first_added, _ = await registry.append_audio(
        member=first_owner,
        burst_id=first.burst.burst_id,
        first_sequence=0,
        packets=(b"one",),
        now_ms=0,
        renewal_lease_ms=2_000,
    )
    first_bytes = first_added[0].accounted_bytes
    assert first_channel.retained_bytes == first_bytes
    assert registry.global_retained_bytes == first_bytes
    await registry.append_audio(
        member=first_owner,
        burst_id=first.burst.burst_id,
        first_sequence=0,
        packets=(b"one",),
        now_ms=1,
        renewal_lease_ms=2_000,
    )
    assert registry.global_retained_bytes == first_bytes

    _, _, second_added, _ = await registry.append_audio(
        member=second_owner,
        burst_id=second.burst.burst_id,
        first_sequence=0,
        packets=(b"two",),
        now_ms=0,
        renewal_lease_ms=2_000,
    )
    second_bytes = second_added[0].accounted_bytes
    assert second_channel.retained_bytes == second_bytes
    assert registry.global_retained_bytes == first_bytes + second_bytes

    await registry.cleanup(15_001)
    assert first_channel.retained_bytes == 0
    assert second_channel.retained_bytes == 0
    assert registry.global_retained_bytes == 0


@pytest.mark.asyncio
async def test_transport_loss_keeps_floor_until_lease_boundary() -> None:
    registry = ChannelRegistry()
    channel, owner, _ = await registry.fresh_join("ROOM", "owner", 0)
    _, listener, _ = await registry.fresh_join("ROOM", "listener", 0)
    result = await registry.request_floor(
        member=owner, request_id="one", now_ms=0, initial_lease_ms=5_000
    )
    assert result.burst is not None

    await registry.transport_lost(owner, "owner", 100)

    assert owner.transport_id is None
    assert channel.current_burst is result.burst and result.burst.state == "open"
    denied = await registry.request_floor(
        member=listener, request_id="two", now_ms=4_999, initial_lease_ms=5_000
    )
    assert denied.burst is None
    assert await registry.expire_floor(channel, result.burst.burst_id, 4_999) is None
    expired = await registry.expire_floor(channel, result.burst.burst_id, 5_000)
    assert expired is result.burst and expired.release_reason == "lease_expired"
    granted = await registry.request_floor(
        member=listener, request_id="two", now_ms=5_000, initial_lease_ms=5_000
    )
    assert granted.created


@pytest.mark.asyncio
async def test_resume_fences_transport_then_replayed_end_releases_floor() -> None:
    registry = ChannelRegistry()
    channel, owner, token = await registry.fresh_join("ROOM", "old", 0)
    _, listener, _ = await registry.fresh_join("ROOM", "listener", 0)
    result = await registry.request_floor(
        member=owner, request_id="one", now_ms=0, initial_lease_ms=5_000
    )
    stale = await registry.resume(token=token, generation=1, transport_id="stale", now_ms=1)
    assert not stale.accepted and stale.reason == "stale_generation"
    resumed = await registry.resume(token=token, generation=2, transport_id="new", now_ms=2)
    assert resumed.accepted and resumed.fenced_transport_id == "old"
    assert channel.current_burst is result.burst and result.burst.state == "open"
    await registry.end_burst(owner, result.burst.burst_id, 1, 3)
    assert channel.current_burst is None and result.burst.state == "draining"
    next_floor = await registry.request_floor(
        member=listener, request_id="two", now_ms=4, initial_lease_ms=5_000
    )
    assert next_floor.created
    _, old, added, _ = await registry.append_audio(
        member=owner,
        burst_id=result.burst.burst_id,
        first_sequence=0,
        packets=(b"tail",),
        now_ms=5,
        renewal_lease_ms=2_000,
    )
    assert added and old.sealed
    assert not (
        await registry.resume(token="bad", generation=2, transport_id="x", now_ms=2)
    ).accepted


@pytest.mark.asyncio
async def test_intentional_leave_immediately_releases_and_seals_owned_floor() -> None:
    registry = ChannelRegistry()
    channel, owner, _ = await registry.fresh_join("ROOM", "owner", 0)
    result = await registry.request_floor(
        member=owner, request_id="one", now_ms=0, initial_lease_ms=5_000
    )
    assert result.burst is not None

    released = await registry.intentional_leave(owner, 100)

    assert released is result.burst and released.sealed
    assert channel.current_burst is None


@pytest.mark.asyncio
async def test_registry_busy_capacity_cancel_expiry_and_leave_paths() -> None:
    full = ChannelRegistry(max_participants=1)
    _, only, _ = await full.fresh_join("ROOM", "one", 0)
    with pytest.raises(ChannelFull):
        await full.fresh_join("ROOM", "two", 0)
    echo_channel, echo_member, echo_token = await full.fresh_join("ECHO", "echo", 0)
    assert echo_channel.code == "ECHO" and echo_member.channel_key != "ECHO" and echo_token

    blocked = ChannelRegistry(channel_audio_bytes=0)
    _, blocked_member, _ = await blocked.fresh_join("ROOM", "one", 0)
    with pytest.raises(ServerBusy):
        await blocked.request_floor(
            member=blocked_member, request_id="one", now_ms=0, initial_lease_ms=5_000
        )

    registry = ChannelRegistry()
    channel, owner, _ = await registry.fresh_join("ROOM", "owner", 0)
    _, listener, _ = await registry.fresh_join("ROOM", "listener", 0)
    first = await registry.request_floor(
        member=owner, request_id="one", now_ms=0, initial_lease_ms=5_000
    )
    duplicate = await registry.request_floor(
        member=owner, request_id="one", now_ms=1, initial_lease_ms=5_000
    )
    assert duplicate.burst is first.burst and not duplicate.created
    denied = await registry.request_floor(
        member=listener, request_id="one", now_ms=1, initial_lease_ms=5_000
    )
    assert denied.burst is None and denied.denial_reason == "channel_busy"
    assert await registry.expire_floor(channel, first.burst.burst_id, 1) is None
    expired = await registry.expire_floor(channel, first.burst.burst_id, 5_000)
    assert expired is first.burst and expired.release_reason == "lease_expired"
    assert await registry.expire_floor(channel, first.burst.burst_id, 5_001) is None
    assert await registry.cancel_request(owner, "missing", 5_002) is None
    terminal_replay = await registry.request_floor(
        member=owner, request_id="one", now_ms=5_002, initial_lease_ms=5_000
    )
    assert (
        terminal_replay.burst is None
        and terminal_replay.denial_reason == "invalid_state"
    )

    second = await registry.request_floor(
        member=listener, request_id="one", now_ms=5_003, initial_lease_ms=5_000
    )
    assert second.created
    canceled = await registry.cancel_request(listener, "one", 5_004)
    assert canceled is not None and second.burst.sealed
    assert await registry.intentional_leave(listener, 5_005) is None
    with pytest.raises(NotJoined):
        await registry.request_floor(
            member=listener, request_id="gone", now_ms=5_006, initial_lease_ms=5_000
        )


@pytest.mark.asyncio
async def test_listen_reset_and_cleanup_remove_expired_state() -> None:
    registry = ChannelRegistry()
    channel, owner, _ = await registry.fresh_join("ROOM", "owner", 0)
    _, listener, _ = await registry.fresh_join("ROOM", "listener", 0)
    created = await registry.request_floor(
        member=owner, request_id="one", now_ms=0, initial_lease_ms=5_000
    )
    item = created.burst
    assert item is not None
    await registry.append_audio(
        member=owner,
        burst_id=item.burst_id,
        first_sequence=1,
        packets=(b"two",),
        now_ms=100,
        renewal_lease_ms=2_000,
    )
    await registry.end_burst(owner, item.burst_id, 2, 100)
    assert (await registry.resolve_listen(listener, 0, 0, 100)).status == "ready"
    assert (await registry.resolve_listen(listener, 2, 0, 100)).reason == "invalid_cursor"
    assert (await registry.resolve_listen(listener, 0, 1, 100)).status == "ready"
    sealed = await registry.cleanup(5_001)
    assert sealed and sealed[0][1] is item
    await registry.cleanup(20_102)
    assert item.burst_id not in channel.bursts
    reset = await registry.resolve_listen(listener, 0, 0, 20_102)
    assert reset.status == "reset" and reset.burst_index == 1
