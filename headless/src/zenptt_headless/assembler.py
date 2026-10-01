"""Ordered receive-side Opus burst assembly."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable

from .codec import OpusDecoder
from .memory import PcmBudget, PcmBudgetExceeded
from .types import FRAME_BYTES, MAX_FRAMES, LossRange, PcmAudio, ReceiveInterrupted, ReceivedBurst


@dataclass
class _Burst:
    burst_id: str
    burst_index: int
    decoder: OpusDecoder
    first_sequence: int = 0
    next_sequence: int = 0
    pcm: bytearray = field(default_factory=bytearray)
    losses: list[LossRange] = field(default_factory=list)
    decode_errors: list[int] = field(default_factory=list)


class BurstAssembler:
    def __init__(
        self, decoder_factory: Callable[[], OpusDecoder] = OpusDecoder,
        *, pcm_budget: PcmBudget | None = None,
    ) -> None:
        self._decoder_factory = decoder_factory
        self._pcm_budget = pcm_budget or PcmBudget()
        self._burst: _Burst | None = None
        self._next_start: tuple[int, int] | None = None
        self._last_sealed_id: str | None = None

    @property
    def cursor(self) -> tuple[int, int]:
        if self._burst is None:
            return (0, 0)
        return (self._burst.burst_index, self._burst.next_sequence)

    def start(self, burst_id: str, burst_index: int) -> None:
        if burst_id == self._last_sealed_id:
            return
        if self._burst is not None and self._burst.burst_id != burst_id:
            raise ValueError("A second burst started before the active burst sealed")
        if self._burst is None:
            first = 0
            if self._next_start is not None and self._next_start[0] == burst_index:
                first = self._next_start[1]
            self._next_start = None
            self._burst = _Burst(
                burst_id,
                burst_index,
                self._decoder_factory(),
                first_sequence=first,
                next_sequence=first,
            )

    def media(self, burst_id: str, first_sequence: int, packets: tuple[bytes, ...]) -> None:
        if burst_id == self._last_sealed_id:
            return
        burst = self._require(burst_id)
        for offset, packet in enumerate(packets):
            sequence = first_sequence + offset
            if sequence < burst.next_sequence:
                continue
            if sequence != burst.next_sequence:
                raise ValueError("Non-contiguous downlink media")
            if sequence >= MAX_FRAMES:
                raise ValueError("Downlink exceeds the burst limit")
            self._pcm_budget.reserve(2 * FRAME_BYTES)
            try:
                try:
                    frame = burst.decoder.decode(packet)
                except RuntimeError:
                    burst.decode_errors.append(sequence)
                    frame = bytes(FRAME_BYTES)
                burst.pcm.extend(frame)
                del frame
            finally:
                self._pcm_budget.release(FRAME_BYTES)
            burst.next_sequence += 1

    def gaps(self, burst_id: str, ranges: list[dict[str, int]]) -> None:
        if burst_id == self._last_sealed_id:
            return
        burst = self._require(burst_id)
        for item in ranges:
            first = item["first_sequence"]
            count = item["count"]
            end = first + count
            if count <= 0 or end > MAX_FRAMES or first > burst.next_sequence:
                raise ValueError("Invalid authoritative gap range")
            if end <= burst.next_sequence:
                continue
            accepted_first = burst.next_sequence
            accepted_count = end - accepted_first
            size = FRAME_BYTES * accepted_count
            self._pcm_budget.reserve(2 * size)
            burst.losses.append(LossRange(accepted_first, accepted_count))
            burst.pcm.extend(bytes(size))
            self._pcm_budget.release(size)
            burst.next_sequence = end

    def seal(self, burst_id: str, final_next_sequence: int, reason: str) -> ReceivedBurst | None:
        if burst_id == self._last_sealed_id:
            return None
        burst = self._require(burst_id)
        if final_next_sequence != burst.next_sequence or final_next_sequence > MAX_FRAMES:
            raise ValueError("Sealed burst watermark does not match assembled audio")
        size = len(burst.pcm)
        self._pcm_budget.reserve(size)
        try:
            audio = PcmAudio(bytes(burst.pcm))
        finally:
            self._pcm_budget.release(size)
        if not self._pcm_budget.admit(audio):
            raise PcmBudgetExceeded("pcm_budget_exceeded")
        result = ReceivedBurst(
            burst.burst_id,
            burst.burst_index,
            audio,
            tuple(burst.losses),
            tuple(burst.decode_errors),
            reason,
            burst.first_sequence,
        )
        self.interrupt("sealed")
        self._last_sealed_id = burst_id
        return result

    def interrupt(self, reason: str) -> ReceiveInterrupted | None:
        burst = self._burst
        if burst is None:
            return None
        burst.decoder.close()
        self._pcm_budget.release(len(burst.pcm))
        self._burst = None
        return ReceiveInterrupted(burst.burst_id, burst.burst_index, reason)

    def reset_cursor(self, burst_index: int, next_sequence: int) -> None:
        if not 0 <= next_sequence <= MAX_FRAMES:
            raise ValueError("Invalid receive cursor")
        self._next_start = (burst_index, next_sequence)

    def reset_session(self) -> None:
        self.interrupt("session_reset")
        self._next_start = None
        self._last_sealed_id = None

    def _require(self, burst_id: str) -> _Burst:
        if self._burst is None or self._burst.burst_id != burst_id:
            raise ValueError("Media does not belong to the active burst")
        return self._burst
