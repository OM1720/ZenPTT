"""Small value types shared by clients and bot handlers."""

from __future__ import annotations

from dataclasses import dataclass

SAMPLE_RATE = 16_000
CHANNELS = 1
SAMPLE_WIDTH_BYTES = 2
FRAME_DURATION_MS = 20
FRAME_SAMPLES = SAMPLE_RATE * FRAME_DURATION_MS // 1_000
FRAME_BYTES = FRAME_SAMPLES * SAMPLE_WIDTH_BYTES
MAX_FRAMES = 3_000


@dataclass(frozen=True)
class PcmAudio:
    """Signed 16-bit little-endian mono PCM sampled at 16 kHz."""

    data: bytes
    sample_rate: int = SAMPLE_RATE

    def __post_init__(self) -> None:
        if not isinstance(self.data, bytes):
            raise ValueError("PCM data must be immutable bytes")
        if self.sample_rate != SAMPLE_RATE:
            raise ValueError("PCM sample rate must be 16000 Hz")
        if len(self.data) % SAMPLE_WIDTH_BYTES:
            raise ValueError("PCM must contain complete 16-bit samples")
        frames = (len(self.data) + FRAME_BYTES - 1) // FRAME_BYTES
        if frames > MAX_FRAMES:
            raise ValueError("PCM exceeds the 60-second ZenPTT burst limit")


@dataclass(frozen=True)
class LossRange:
    first_sequence: int
    count: int


@dataclass(frozen=True)
class ReceivedBurst:
    burst_id: str
    burst_index: int
    audio: PcmAudio
    losses: tuple[LossRange, ...]
    decode_errors: tuple[int, ...]
    reason: str
    first_sequence: int = 0
    session_epoch: int = 0


@dataclass(frozen=True)
class ReceiveInterrupted:
    burst_id: str | None
    burst_index: int | None
    reason: str
    session_epoch: int = 0


@dataclass(frozen=True)
class SendResult:
    status: str
    reason: str | None = None
    burst_id: str | None = None
