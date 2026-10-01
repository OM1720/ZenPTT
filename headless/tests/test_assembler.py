import pytest

from zenptt_headless.assembler import BurstAssembler
from zenptt_headless.types import FRAME_BYTES

BURST_ID = "11111111-1111-4111-8111-111111111111"


class FakeDecoder:
    def __init__(self) -> None:
        self.closed = False

    def decode(self, packet: bytes) -> bytes:
        if packet == b"bad":
            raise RuntimeError("bad packet")
        return (packet * FRAME_BYTES)[:FRAME_BYTES]

    def close(self) -> None:
        self.closed = True


def test_assembly_preserves_loss_duration_and_reports_decode_errors() -> None:
    assembler = BurstAssembler(FakeDecoder)
    assembler.start(BURST_ID, 4)
    assembler.media(BURST_ID, 0, (b"a",))
    assembler.gaps(BURST_ID, [{"first_sequence": 1, "count": 2}])
    assembler.media(BURST_ID, 3, (b"bad",))
    result = assembler.seal(BURST_ID, 4, "complete")
    assert len(result.audio.data) == 4 * FRAME_BYTES
    assert result.losses[0].first_sequence == 1
    assert result.losses[0].count == 2
    assert result.decode_errors == (3,)


def test_duplicate_media_does_not_duplicate_pcm() -> None:
    assembler = BurstAssembler(FakeDecoder)
    assembler.start(BURST_ID, 0)
    assembler.media(BURST_ID, 0, (b"a",))
    assembler.media(BURST_ID, 0, (b"a",))
    assert len(assembler.seal(BURST_ID, 1, "complete").audio.data) == FRAME_BYTES


def test_gap_cannot_allocate_beyond_the_protocol_burst_limit() -> None:
    assembler = BurstAssembler(FakeDecoder)
    assembler.start(BURST_ID, 0)
    with pytest.raises(ValueError):
        assembler.gaps(BURST_ID, [{"first_sequence": 0, "count": 3_001}])


def test_history_reset_returns_only_the_available_tail() -> None:
    assembler = BurstAssembler(FakeDecoder)
    assembler.reset_cursor(4, 10)
    assembler.start(BURST_ID, 4)
    assembler.media(BURST_ID, 10, (b"a", b"b"))
    result = assembler.seal(BURST_ID, 12, "complete")
    assert result is not None
    assert result.first_sequence == 10
    assert result.audio.data == b"a" * FRAME_BYTES + b"b" * FRAME_BYTES
    assert result.losses == ()


def test_history_reset_can_produce_an_empty_completed_tail() -> None:
    assembler = BurstAssembler(FakeDecoder)
    assembler.reset_cursor(4, 10)
    assembler.start(BURST_ID, 4)
    result = assembler.seal(BURST_ID, 10, "complete")
    assert result is not None
    assert result.first_sequence == 10
    assert result.audio.data == b""


def test_repeated_start_preserves_the_active_decoder_and_cursor() -> None:
    created = []

    class StatefulDecoder(FakeDecoder):
        def __init__(self):
            super().__init__()
            self.frames = 0
            created.append(self)

        def decode(self, packet):
            assert not self.closed
            self.frames += 1
            return bytes([self.frames]) * FRAME_BYTES

    assembler = BurstAssembler(StatefulDecoder)
    assembler.start(BURST_ID, 4)
    assembler.media(BURST_ID, 0, (b"a",))
    assembler.start(BURST_ID, 4)
    assembler.media(BURST_ID, 1, (b"b",))
    assert len(created) == 1
    result = assembler.seal(BURST_ID, 2, "complete")
    assert result is not None
    assert result.audio.data == b"\1" * FRAME_BYTES + b"\2" * FRAME_BYTES
    assert created[0].closed

    assembler.start("22222222-2222-4222-8222-222222222222", 5)
    assembler.media("22222222-2222-4222-8222-222222222222", 0, (b"a",))
    next_result = assembler.seal("22222222-2222-4222-8222-222222222222", 1, "complete")
    assert len(created) == 2
    assert next_result.audio.data == b"\1" * FRAME_BYTES
    assert created[1].closed


def test_duplicate_ranges_and_seal_are_ignored() -> None:
    assembler = BurstAssembler(FakeDecoder)
    assembler.start(BURST_ID, 4)
    assembler.media(BURST_ID, 0, (b"a", b"b"))
    assembler.media(BURST_ID, 1, (b"b", b"c"))
    assembler.gaps(BURST_ID, [{"first_sequence": 2, "count": 2}])
    assembler.gaps(BURST_ID, [{"first_sequence": 2, "count": 2}])
    assert assembler.seal(BURST_ID, 4, "complete") is not None
    assembler.start(BURST_ID, 4)
    assembler.media(BURST_ID, 0, (b"a",))
    assembler.gaps(BURST_ID, [{"first_sequence": 1, "count": 1}])
    assert assembler.seal(BURST_ID, 4, "complete") is None
