"""Bounded timing diagnostics for live media envelopes."""

from __future__ import annotations

from collections import OrderedDict
from collections.abc import Callable
from dataclasses import dataclass, field
import logging

logger = logging.getLogger("zenptt.server")

MEDIA_TIMING_THRESHOLD_MS = 100
MAX_MEDIA_TIMING_EVENTS_PER_TYPE = 10
MAX_TRACKED_MEDIA_BURSTS = 30


@dataclass(frozen=True)
class OutboundMediaContext:
    burst_id: str
    first_sequence: int
    frame_count: int
    oldest_received_at_ms: int
    newest_received_at_ms: int
    queued_at_ms: int


@dataclass
class _BurstTiming:
    previous_received_at_ms: int | None = None
    previous_sent_at_ms: int | None = None
    event_counts: dict[str, int] = field(default_factory=dict)


class ServerMediaTimingDiagnostics:
    def __init__(
        self,
        session_id: str,
        emit: Callable[[str], None] | None = None,
        threshold_ms: int = MEDIA_TIMING_THRESHOLD_MS,
        max_events_per_type: int = MAX_MEDIA_TIMING_EVENTS_PER_TYPE,
    ) -> None:
        self.session_id = session_id[-6:]
        self.emit = emit or (lambda event: logger.info("%s", event))
        self.threshold_ms = threshold_ms
        self.max_events_per_type = max_events_per_type
        self.bursts: OrderedDict[str, _BurstTiming] = OrderedDict()

    def uplink_received(
        self,
        burst_id: str,
        first_sequence: int,
        frame_count: int,
        message_bytes: int,
        received_at_ms: int,
    ) -> None:
        state = self._state(burst_id)
        previous = state.previous_received_at_ms
        if previous is not None:
            gap_ms = self._elapsed(received_at_ms, previous)
            if gap_ms > self.threshold_ms:
                self._record(
                    state,
                    "server_receive_gap",
                    burst_id,
                    f"first={first_sequence} frames={frame_count} gap={gap_ms}ms "
                    f"bytes={message_bytes}",
                )
        state.previous_received_at_ms = received_at_ms

    def downlink_queued(
        self,
        context: OutboundMediaContext,
        queued_bytes: int,
    ) -> None:
        oldest_age_ms = self._elapsed(context.queued_at_ms, context.oldest_received_at_ms)
        newest_age_ms = self._elapsed(context.queued_at_ms, context.newest_received_at_ms)
        if oldest_age_ms <= self.threshold_ms:
            return
        self._record(
            self._state(context.burst_id),
            "server_handoff_delay",
            context.burst_id,
            f"first={context.first_sequence} frames={context.frame_count} "
            f"oldest_age={oldest_age_ms}ms newest_age={newest_age_ms}ms "
            f"queue={queued_bytes}B",
        )

    def downlink_sent(
        self,
        context: OutboundMediaContext,
        send_started_at_ms: int,
        sent_at_ms: int,
        queued_bytes: int,
    ) -> None:
        state = self._state(context.burst_id)
        previous = state.previous_sent_at_ms
        if previous is not None:
            gap_ms = self._elapsed(sent_at_ms, previous)
            if gap_ms > self.threshold_ms:
                self._record(
                    state,
                    "server_send_gap",
                    context.burst_id,
                    f"first={context.first_sequence} frames={context.frame_count} "
                    f"gap={gap_ms}ms queue={queued_bytes}B",
                )
        queue_wait_ms = self._elapsed(send_started_at_ms, context.queued_at_ms)
        send_time_ms = self._elapsed(sent_at_ms, send_started_at_ms)
        if max(queue_wait_ms, send_time_ms) > self.threshold_ms:
            self._record(
                state,
                "server_send_delay",
                context.burst_id,
                f"first={context.first_sequence} frames={context.frame_count} "
                f"queue_wait={queue_wait_ms}ms send_time={send_time_ms}ms "
                f"queue={queued_bytes}B",
            )
        state.previous_sent_at_ms = sent_at_ms

    def _state(self, burst_id: str) -> _BurstTiming:
        state = self.bursts.get(burst_id)
        if state is not None:
            self.bursts.move_to_end(burst_id)
            return state
        while len(self.bursts) >= MAX_TRACKED_MEDIA_BURSTS:
            self.bursts.popitem(last=False)
        state = _BurstTiming()
        self.bursts[burst_id] = state
        return state

    def _record(
        self,
        state: _BurstTiming,
        event_type: str,
        burst_id: str,
        fields: str,
    ) -> None:
        count = state.event_counts.get(event_type, 0)
        if count >= self.max_events_per_type:
            return
        state.event_counts[event_type] = count + 1
        event = (
            f"media_timing type={event_type} session={self.session_id} "
            f"burst={burst_id[-6:]} {fields}"
        )
        try:
            self.emit(event)
        except Exception:
            logger.exception("media_timing_emit_failed session=%s", self.session_id)

    @staticmethod
    def _elapsed(later_ms: int, earlier_ms: int) -> int:
        return max(0, later_ms - earlier_ms)
