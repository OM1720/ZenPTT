"""Shared builders for ZenPTT v4 server protocol test messages."""

from app.protocol import MediaEnvelope, UPLINK_MEDIA_TYPE, encode_media

TEST_TRANSMISSION_ID = "11111111-1111-4111-8111-111111111111"


def audio_message(
    sequence: int,
    payload: bytes = b"opus",
    *,
    burst_id: str = TEST_TRANSMISSION_ID,
    packets: tuple[bytes, ...] | None = None,
    direction: int = UPLINK_MEDIA_TYPE,
) -> bytes:
    return encode_media(
        MediaEnvelope(
            media_type=direction,
            burst_id=burst_id,
            first_sequence=sequence,
            packets=packets or (payload,),
        )
    )
