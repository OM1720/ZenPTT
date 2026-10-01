"""Owns logical membership, floor arbitration, and retained voice bursts."""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import re
import secrets
import uuid
from dataclasses import dataclass, field

from .protocol import AUDIO_FRAME_DURATION_MS, MAX_OPUS_PACKET_BYTES

ECHO_CHANNEL = "ECHO"
CHANNEL_CODE_PATTERN = re.compile(r"^[A-Z0-9]+(?:\.[A-Z0-9]+)*$")
FRAME_ACCOUNTING_BYTES = 32
MAX_CAPTURE_LEAD_MS = 500

MAX_BURST_MS = 60_000
MAX_FRAMES_PER_BURST = MAX_BURST_MS // AUDIO_FRAME_DURATION_MS
MAX_CHANNEL_AUDIO_BYTES = 1024 * 1024
MAX_GLOBAL_AUDIO_BYTES = 160 * 1024 * 1024
MIN_SERVER_HISTORY_MS = 15_000


@dataclass(frozen=True)
class RecoveryPolicy:
    recovery_horizon_ms: int

    def __post_init__(self) -> None:
        if not 1_000 <= self.recovery_horizon_ms <= 60_000:
            raise ValueError("invalid recovery horizon")
        if self.recovery_horizon_ms % AUDIO_FRAME_DURATION_MS:
            raise ValueError("recovery horizon must align to audio frames")

    @property
    def server_history_ms(self) -> int:
        return max(MIN_SERVER_HISTORY_MS, 3 * self.recovery_horizon_ms)


class DomainError(Exception):
    code = "invalid_state"


class InvalidChannel(DomainError):
    code = "invalid_channel"


class ChannelFull(DomainError):
    code = "channel_full"


class NotJoined(DomainError):
    code = "not_joined"


class ServerBusy(DomainError):
    code = "server_busy"


class AudioRejected(DomainError):
    """A public, operation-scoped media rejection."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def normalize_channel_code(value: str, max_length: int = 256) -> str:
    normalized = value.upper()
    if (
        not normalized
        or len(normalized) > max_length
        or not CHANNEL_CODE_PATTERN.fullmatch(normalized)
    ):
        raise InvalidChannel
    return normalized


@dataclass(frozen=True)
class StoredFrame:
    sequence: int
    payload: bytes
    received_at_ms: int = 0

    @property
    def accounted_bytes(self) -> int:
        return len(self.payload) + FRAME_ACCOUNTING_BYTES


@dataclass
class TalkBurst:
    burst_id: str
    index: int
    owner_member_id: str
    request_id: str
    grant_time_ms: int
    lease_expires_at_ms: int
    eligible_member_ids: frozenset[str]
    recovery_horizon_ms: int = 5_000
    server_history_ms: int = MIN_SERVER_HISTORY_MS
    state: str = "open"
    frames: dict[int, StoredFrame] = field(default_factory=dict)
    final_next_sequence: int | None = None
    release_reason: str | None = None
    drain_expires_at_ms: int | None = None
    seal_reason: str | None = None
    sealed_at_ms: int | None = None
    lost_sequences: set[int] = field(default_factory=set)
    resolved_next_sequence: int = 0
    history_floor_sequence: int = 0
    retained_byte_count: int = 0

    @property
    def retained_bytes(self) -> int:
        return self.retained_byte_count

    @property
    def highest_sequence(self) -> int | None:
        return max(self.frames, default=None)

    @property
    def released(self) -> bool:
        return self.state in {"draining", "sealed"}

    @property
    def sealed(self) -> bool:
        return self.state == "sealed"

    def frame_position_ms(self, sequence: int) -> int:
        return self.grant_time_ms + sequence * AUDIO_FRAME_DURATION_MS

    def append(
        self,
        *,
        first_sequence: int,
        packets: tuple[bytes, ...],
        member_id: str,
        now_ms: int,
        remaining_channel_bytes: int,
        remaining_global_bytes: int,
        renewal_lease_ms: int,
        received_at_ms: int | None = None,
    ) -> tuple[StoredFrame, ...]:
        if member_id != self.owner_member_id:
            raise AudioRejected("unknown_burst")
        if self.sealed:
            raise AudioRejected("expired")
        if not packets or first_sequence < 0:
            raise AudioRejected("invalid_range")
        next_sequence = first_sequence + len(packets)
        if next_sequence > MAX_FRAMES_PER_BURST:
            raise AudioRejected("invalid_range")
        if self.final_next_sequence is not None and next_sequence > self.final_next_sequence:
            raise AudioRejected("invalid_range")

        new_frames: list[StoredFrame] = []
        has_accepted_position = False
        for offset, packet in enumerate(packets):
            sequence = first_sequence + offset
            if not packet or len(packet) > MAX_OPUS_PACKET_BYTES:
                raise AudioRejected("invalid_range")
            position_ms = self.frame_position_ms(sequence)
            if position_ms > now_ms + MAX_CAPTURE_LEAD_MS:
                raise AudioRejected("invalid_range")
            existing = self.frames.get(sequence)
            if existing is not None:
                if existing.payload != packet:
                    raise AudioRejected("payload_mismatch")
                has_accepted_position = True
                continue
            if sequence in self.lost_sequences or now_ms - position_ms > self.recovery_horizon_ms:
                continue
            has_accepted_position = True
            new_frames.append(
                StoredFrame(
                    sequence,
                    bytes(packet),
                    now_ms if received_at_ms is None else received_at_ms,
                )
            )

        if not has_accepted_position:
            raise AudioRejected("expired")

        added_bytes = sum(frame.accounted_bytes for frame in new_frames)
        if added_bytes > remaining_channel_bytes or added_bytes > remaining_global_bytes:
            raise AudioRejected("server_busy")
        for frame in new_frames:
            self.frames[frame.sequence] = frame
        self.retained_byte_count += added_bytes
        if new_frames and self.state == "open":
            self.lease_expires_at_ms = now_ms + renewal_lease_ms
        self.advance_resolution(now_ms)
        self.maybe_seal(now_ms)
        return tuple(new_frames)

    def advance_resolution(self, now_ms: int) -> bool:
        previous = self.resolved_next_sequence
        expected_next = (
            self.final_next_sequence
            if self.final_next_sequence is not None
            else (self.highest_sequence + 1 if self.highest_sequence is not None else 0)
        )
        while self.resolved_next_sequence < expected_next:
            sequence = self.resolved_next_sequence
            if sequence in self.frames or sequence in self.lost_sequences:
                self.resolved_next_sequence += 1
                continue
            if now_ms - self.frame_position_ms(sequence) <= self.recovery_horizon_ms:
                break
            self.lost_sequences.add(sequence)
            self.resolved_next_sequence += 1
        return self.resolved_next_sequence != previous

    def end(self, final_next_sequence: int, now_ms: int) -> bool:
        if not 0 <= final_next_sequence <= MAX_FRAMES_PER_BURST:
            raise AudioRejected("invalid_range")
        highest = self.highest_sequence
        if highest is not None and highest >= final_next_sequence:
            raise AudioRejected("invalid_range")
        if self.final_next_sequence is not None:
            if self.final_next_sequence != final_next_sequence:
                raise AudioRejected("invalid_range")
            return False
        if self.sealed:
            raise AudioRejected("expired")
        self.final_next_sequence = final_next_sequence
        changed = self.release("released", now_ms)
        self.maybe_seal(now_ms)
        return changed

    def release(self, reason: str, now_ms: int) -> bool:
        if self.state != "open":
            return False
        self.state = "draining"
        self.release_reason = reason
        if self.final_next_sequence is None:
            self.drain_expires_at_ms = now_ms + self.recovery_horizon_ms
        return True

    def force_seal(self, reason: str, now_ms: int) -> bool:
        if self.sealed:
            return False
        self.release(reason, now_ms)
        if self.final_next_sequence is None:
            highest = self.highest_sequence
            self.final_next_sequence = 0 if highest is None else highest + 1
        self.lost_sequences.update(
            sequence
            for sequence in range(self.final_next_sequence)
            if sequence not in self.frames
        )
        self.resolved_next_sequence = self.final_next_sequence
        self._seal(reason, now_ms)
        return True

    def maybe_seal(self, now_ms: int) -> bool:
        if self.sealed or self.state != "draining":
            return False
        if self.final_next_sequence is None:
            if self.drain_expires_at_ms is None or now_ms < self.drain_expires_at_ms:
                return False
            highest = self.highest_sequence
            self.final_next_sequence = 0 if highest is None else highest + 1
        self.advance_resolution(now_ms)
        if self.resolved_next_sequence < self.final_next_sequence:
            return False
        self._seal("expired" if self.lost_sequences else "complete", now_ms)
        return True

    def evict_history(self, now_ms: int) -> int:
        expired = [
            frame
            for frame in self.frames.values()
            if now_ms - self.frame_position_ms(frame.sequence) > self.server_history_ms
        ]
        for frame in expired:
            self.frames.pop(frame.sequence, None)
        if expired:
            self.history_floor_sequence = max(
                self.history_floor_sequence,
                max(frame.sequence for frame in expired) + 1,
            )
        removed_bytes = sum(frame.accounted_bytes for frame in expired)
        self.retained_byte_count -= removed_bytes
        return removed_bytes

    def history_expired(self, now_ms: int) -> bool:
        if not self.sealed or self.frames:
            return False
        final = self.final_next_sequence or 0
        last_position = self.frame_position_ms(max(0, final - 1))
        anchor = max(last_position, self.sealed_at_ms or last_position)
        return now_ms - anchor > self.server_history_ms

    def _seal(self, reason: str, now_ms: int) -> None:
        self.state = "sealed"
        self.seal_reason = reason
        self.sealed_at_ms = now_ms
        self.drain_expires_at_ms = None


@dataclass
class LogicalMember:
    member_id: str
    channel_code: str
    channel_key: str
    token_digest: bytes
    eligible_from_index: int
    generation: int
    transport_id: str | None
    disconnected_expires_at_ms: int | None = None
    role: str = "client"


@dataclass
class Channel:
    code: str
    max_participants: int
    incarnation_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    revision: int = 0
    members: dict[str, LogicalMember] = field(default_factory=dict)
    bursts: dict[str, TalkBurst] = field(default_factory=dict)
    burst_ids_by_index: dict[int, str] = field(default_factory=dict)
    request_burst_ids: dict[tuple[str, str], str] = field(default_factory=dict)
    next_burst_index: int = 0
    floor_burst_id: str | None = None
    retained_byte_count: int = 0

    @property
    def current_burst(self) -> TalkBurst | None:
        return self.bursts.get(self.floor_burst_id) if self.floor_burst_id else None

    @property
    def retained_bytes(self) -> int:
        return self.retained_byte_count

    def burst_at(self, index: int) -> TalkBurst | None:
        burst_id = self.burst_ids_by_index.get(index)
        return self.bursts.get(burst_id) if burst_id else None

    def eligible(self, member: LogicalMember, burst: TalkBurst) -> bool:
        return member.member_id in burst.eligible_member_ids

    def touch(self) -> None:
        self.revision += 1


@dataclass(frozen=True)
class ResumeResult:
    accepted: bool
    member: LogicalMember | None = None
    channel: Channel | None = None
    fenced_transport_id: str | None = None
    reason: str = "invalid_token"


@dataclass(frozen=True)
class FloorResult:
    burst: TalkBurst | None
    created: bool = False
    denial_reason: str | None = None


@dataclass(frozen=True)
class ListenResult:
    status: str
    burst_index: int
    next_sequence: int
    reason: str | None = None


class ChannelRegistry:
    """Serializes logical membership, floor, retained store, and stream lookup."""

    def __init__(
        self,
        max_participants: int = 12,
        max_code_length: int = 256,
        channel_audio_bytes: int = MAX_CHANNEL_AUDIO_BYTES,
        global_audio_bytes: int = MAX_GLOBAL_AUDIO_BYTES,
        recovery_policy: RecoveryPolicy = RecoveryPolicy(5_000),
    ) -> None:
        self.max_participants = max_participants
        self.max_code_length = max_code_length
        self.channel_audio_limit = channel_audio_bytes
        self.global_audio_limit = global_audio_bytes
        self.recovery_policy = recovery_policy
        self.channels: dict[str, Channel] = {}
        self.members_by_token: dict[bytes, LogicalMember] = {}
        self._global_retained_bytes = 0
        self.lock = asyncio.Lock()

    @property
    def global_retained_bytes(self) -> int:
        return self._global_retained_bytes

    async def fresh_join(
        self, code: str, transport_id: str, now_ms: int
    ) -> tuple[Channel, LogicalMember, str]:
        normalized = normalize_channel_code(code, self.max_code_length)
        token = secrets.token_urlsafe(32)
        digest = self._token_digest(token)
        async with self.lock:
            channel_key = f"{normalized}:{uuid.uuid4()}" if normalized == ECHO_CHANNEL else normalized
            channel = self.channels.get(channel_key)
            if channel is None:
                channel = Channel(normalized, self.max_participants)
                self.channels[channel_key] = channel
            if len(channel.members) >= channel.max_participants:
                raise ChannelFull
            member = LogicalMember(
                member_id=str(uuid.uuid4()),
                channel_code=normalized,
                channel_key=channel_key,
                token_digest=digest,
                eligible_from_index=channel.next_burst_index,
                generation=1,
                transport_id=transport_id,
            )
            channel.members[member.member_id] = member
            self.members_by_token[digest] = member
            channel.touch()
            return channel, member, token

    async def join_existing_echo(
        self, channel_key: str, transport_id: str, now_ms: int
    ) -> tuple[Channel, LogicalMember, str]:
        token = secrets.token_urlsafe(32)
        digest = self._token_digest(token)
        async with self.lock:
            channel = self.channels.get(channel_key)
            if channel is None or channel.code != ECHO_CHANNEL:
                raise NotJoined
            if any(member.role == "echo_bot" for member in channel.members.values()):
                raise ChannelFull
            if len(channel.members) >= 2:
                raise ChannelFull
            member = LogicalMember(
                member_id=str(uuid.uuid4()),
                channel_code=ECHO_CHANNEL,
                channel_key=channel_key,
                token_digest=digest,
                eligible_from_index=channel.next_burst_index,
                generation=1,
                transport_id=transport_id,
                role="echo_bot",
            )
            channel.members[member.member_id] = member
            self.members_by_token[digest] = member
            channel.touch()
            return channel, member, token

    async def remove_member(
        self, member: LogicalMember, now_ms: int
    ) -> tuple[Channel | None, TalkBurst | None]:
        async with self.lock:
            channel = self.channels.get(member.channel_key)
            if channel is None:
                return None, None
            return channel, self._remove_member(member, now_ms)

    async def cancel_channel_floor(
        self, channel: Channel, now_ms: int
    ) -> TalkBurst | None:
        async with self.lock:
            current = channel.current_burst
            if current is None:
                return None
            current.force_seal("canceled", now_ms)
            channel.floor_burst_id = None
            channel.touch()
            return current

    async def resume(
        self, *, token: str, generation: int, transport_id: str, now_ms: int
    ) -> ResumeResult:
        digest = self._token_digest(token)
        async with self.lock:
            member = self.members_by_token.get(digest)
            if member is None or not hmac.compare_digest(member.token_digest, digest):
                return ResumeResult(False)
            channel = self.channels.get(member.channel_key)
            if channel is None or member.member_id not in channel.members:
                return ResumeResult(False)
            if (
                member.disconnected_expires_at_ms is not None
                and member.disconnected_expires_at_ms <= now_ms
            ):
                if not self._member_has_retained_audio(channel, member, now_ms):
                    return ResumeResult(False, reason="expired")
            if generation <= member.generation:
                return ResumeResult(False, reason="stale_generation")
            fenced = member.transport_id
            member.generation = generation
            member.transport_id = transport_id
            member.disconnected_expires_at_ms = None
            return ResumeResult(
                True,
                member=member,
                channel=channel,
                fenced_transport_id=fenced,
                reason="accepted",
            )

    async def transport_lost(
        self, member: LogicalMember, transport_id: str, now_ms: int
    ) -> None:
        async with self.lock:
            channel = self.channels.get(member.channel_key)
            if channel is None or member.transport_id != transport_id:
                return
            member.transport_id = None
            member.disconnected_expires_at_ms = now_ms + self.recovery_policy.server_history_ms

    async def intentional_leave(self, member: LogicalMember, now_ms: int) -> TalkBurst | None:
        async with self.lock:
            return self._remove_member(member, now_ms)

    async def request_floor(
        self,
        *,
        member: LogicalMember,
        request_id: str,
        now_ms: int,
        initial_lease_ms: int,
    ) -> FloorResult:
        async with self.lock:
            channel = self.channels.get(member.channel_key)
            if (
                channel is None
                or member.member_id not in channel.members
                or member.transport_id is None
            ):
                raise NotJoined
            request_key = (member.member_id, request_id)
            previous_id = channel.request_burst_ids.get(request_key)
            if previous_id is not None:
                previous = channel.bursts.get(previous_id)
                if (
                    previous is not None
                    and previous.owner_member_id == member.member_id
                    and previous.state == "open"
                    and channel.floor_burst_id == previous.burst_id
                ):
                    return FloorResult(previous)
                return FloorResult(None, denial_reason="invalid_state")
            if channel.current_burst is not None:
                return FloorResult(None, denial_reason="channel_busy")
            if (
                channel.retained_bytes >= self.channel_audio_limit
                or self.global_retained_bytes >= self.global_audio_limit
            ):
                raise ServerBusy
            burst = TalkBurst(
                burst_id=str(uuid.uuid4()),
                index=channel.next_burst_index,
                owner_member_id=member.member_id,
                request_id=request_id,
                grant_time_ms=now_ms,
                lease_expires_at_ms=now_ms + initial_lease_ms,
                eligible_member_ids=frozenset(
                    candidate.member_id
                    for candidate in channel.members.values()
                    if candidate.member_id != member.member_id
                    and (
                        candidate.transport_id is not None
                        or (
                            candidate.disconnected_expires_at_ms is not None
                            and candidate.disconnected_expires_at_ms > now_ms
                        )
                    )
                ),
                recovery_horizon_ms=self.recovery_policy.recovery_horizon_ms,
                server_history_ms=self.recovery_policy.server_history_ms,
            )
            channel.next_burst_index += 1
            channel.bursts[burst.burst_id] = burst
            channel.burst_ids_by_index[burst.index] = burst.burst_id
            channel.request_burst_ids[request_key] = burst.burst_id
            channel.floor_burst_id = burst.burst_id
            channel.touch()
            return FloorResult(burst, created=True)

    async def cancel_request(
        self, member: LogicalMember, request_id: str, now_ms: int
    ) -> tuple[Channel, TalkBurst] | None:
        async with self.lock:
            channel = self.channels.get(member.channel_key)
            if channel is None:
                raise NotJoined
            burst_id = channel.request_burst_ids.get((member.member_id, request_id))
            burst = channel.bursts.get(burst_id) if burst_id else None
            if burst is None or burst.owner_member_id != member.member_id:
                return None
            changed = burst.force_seal("canceled", now_ms)
            if channel.floor_burst_id == burst.burst_id:
                channel.floor_burst_id = None
                changed = True
            if changed:
                channel.touch()
            return channel, burst

    async def end_burst(
        self, member: LogicalMember, burst_id: str, final_next_sequence: int, now_ms: int
    ) -> tuple[Channel, TalkBurst, bool]:
        async with self.lock:
            channel = self.channels.get(member.channel_key)
            if channel is None:
                raise NotJoined
            burst = channel.bursts.get(burst_id)
            if burst is None or burst.owner_member_id != member.member_id:
                raise AudioRejected("unknown_burst")
            changed = burst.end(final_next_sequence, now_ms)
            if channel.floor_burst_id == burst_id:
                channel.floor_burst_id = None
                changed = True
            if changed:
                channel.touch()
            return channel, burst, changed

    async def owned_terminal_burst(
        self, member: LogicalMember, burst_id: str
    ) -> TalkBurst | None:
        async with self.lock:
            channel = self.channels.get(member.channel_key)
            if channel is None:
                raise NotJoined
            burst = channel.bursts.get(burst_id)
            if (
                burst is None
                or burst.owner_member_id != member.member_id
                or burst.state == "open"
            ):
                return None
            return burst

    async def append_audio(
        self,
        *,
        member: LogicalMember,
        burst_id: str,
        first_sequence: int,
        packets: tuple[bytes, ...],
        now_ms: int,
        renewal_lease_ms: int,
        received_at_ms: int | None = None,
    ) -> tuple[Channel, TalkBurst, tuple[StoredFrame, ...], bool]:
        async with self.lock:
            channel = self.channels.get(member.channel_key)
            if channel is None:
                raise NotJoined
            burst = channel.bursts.get(burst_id)
            if burst is None:
                raise AudioRejected("unknown_burst")
            previous_resolved = burst.resolved_next_sequence
            added = burst.append(
                first_sequence=first_sequence,
                packets=packets,
                member_id=member.member_id,
                now_ms=now_ms,
                remaining_channel_bytes=self.channel_audio_limit - channel.retained_bytes,
                remaining_global_bytes=self.global_audio_limit - self.global_retained_bytes,
                renewal_lease_ms=renewal_lease_ms,
                received_at_ms=received_at_ms,
            )
            added_bytes = sum(frame.accounted_bytes for frame in added)
            channel.retained_byte_count += added_bytes
            self._global_retained_bytes += added_bytes
            resolution_changed = burst.resolved_next_sequence != previous_resolved
            return channel, burst, added, resolution_changed

    async def expire_floor(self, channel: Channel, burst_id: str, now_ms: int) -> TalkBurst | None:
        async with self.lock:
            burst = channel.bursts.get(burst_id)
            if burst is None or channel.floor_burst_id != burst_id:
                return None
            duration_expired = now_ms >= burst.grant_time_ms + MAX_BURST_MS
            if not duration_expired and burst.lease_expires_at_ms > now_ms:
                return None
            reason = "duration_limit" if duration_expired else "lease_expired"
            burst.release(reason, now_ms)
            channel.floor_burst_id = None
            channel.touch()
            return burst

    async def resolve_listen(
        self, member: LogicalMember, burst_index: int, next_sequence: int, now_ms: int
    ) -> ListenResult:
        async with self.lock:
            channel = self.channels.get(member.channel_key)
            if channel is None:
                raise NotJoined
            if burst_index < member.eligible_from_index:
                return ListenResult("reset", member.eligible_from_index, 0, "not_eligible")
            if burst_index > channel.next_burst_index:
                return ListenResult("reset", channel.next_burst_index, 0, "invalid_cursor")
            index = burst_index
            while index < channel.next_burst_index:
                burst = channel.burst_at(index)
                if (
                    burst is None
                    or not channel.eligible(member, burst)
                    or burst.history_expired(now_ms)
                ):
                    index += 1
                    continue
                if index != burst_index:
                    return ListenResult("reset", index, 0, "expired")
                if next_sequence < burst.history_floor_sequence:
                    return ListenResult(
                        "reset", index, burst.history_floor_sequence, "expired"
                    )
                if next_sequence < 0 or next_sequence > MAX_FRAMES_PER_BURST:
                    return ListenResult(
                        "reset", index, burst.history_floor_sequence, "invalid_cursor"
                    )
                return ListenResult("ready", index, next_sequence)
            if index != burst_index:
                return ListenResult("reset", index, 0, "expired")
            return ListenResult("ready", index, next_sequence)

    async def cleanup(self, now_ms: int) -> tuple[tuple[Channel, TalkBurst], ...]:
        changed_bursts: list[tuple[Channel, TalkBurst]] = []
        async with self.lock:
            for channel_key, channel in list(self.channels.items()):
                for burst in list(channel.bursts.values()):
                    previous_resolved = burst.resolved_next_sequence
                    if not burst.sealed:
                        burst.advance_resolution(now_ms)
                    sealed = burst.maybe_seal(now_ms)
                    removed_bytes = burst.evict_history(now_ms)
                    channel.retained_byte_count -= removed_bytes
                    self._global_retained_bytes -= removed_bytes
                    if sealed or burst.resolved_next_sequence != previous_resolved:
                        changed_bursts.append((channel, burst))
                        channel.touch()
                    if not burst.history_expired(now_ms):
                        continue
                    channel.bursts.pop(burst.burst_id, None)
                    channel.burst_ids_by_index.pop(burst.index, None)
                    request_key = (burst.owner_member_id, burst.request_id)
                    if channel.request_burst_ids.get(request_key) == burst.burst_id:
                        channel.request_burst_ids.pop(request_key, None)
                for member in list(channel.members.values()):
                    if (
                        member.transport_id is None
                        and member.disconnected_expires_at_ms is not None
                        and member.disconnected_expires_at_ms <= now_ms
                        and not self._member_has_retained_audio(channel, member, now_ms)
                    ):
                        self._remove_member(member, now_ms)
                if not channel.members and not channel.bursts:
                    self.channels.pop(channel_key, None)
        return tuple(changed_bursts)

    def _release_owned_floor(
        self, channel: Channel, member: LogicalMember, reason: str, now_ms: int
    ) -> TalkBurst | None:
        burst = channel.current_burst
        if burst is None or burst.owner_member_id != member.member_id:
            return None
        burst.release(reason, now_ms)
        channel.floor_burst_id = None
        channel.touch()
        return burst

    def _remove_member(self, member: LogicalMember, now_ms: int) -> TalkBurst | None:
        channel = self.channels.get(member.channel_key)
        if channel is None or channel.members.pop(member.member_id, None) is None:
            return None
        self.members_by_token.pop(member.token_digest, None)
        released = self._release_owned_floor(channel, member, "member_left", now_ms)
        if released is not None:
            released.force_seal("member_left", now_ms)
        channel.touch()
        return released

    @staticmethod
    def _member_has_retained_audio(
        channel: Channel, member: LogicalMember, now_ms: int
    ) -> bool:
        return any(
            (
                member.member_id in burst.eligible_member_ids
                or (member.member_id == burst.owner_member_id and not burst.sealed)
            )
            and (
                not burst.sealed
                or any(
                    now_ms - burst.frame_position_ms(frame.sequence) <= burst.server_history_ms
                    for frame in burst.frames.values()
                )
            )
            for burst in channel.bursts.values()
        )

    @staticmethod
    def _token_digest(token: str) -> bytes:
        return hashlib.sha256(token.encode("utf-8")).digest()
