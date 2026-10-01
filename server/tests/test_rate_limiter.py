from app.client_session import MessageRateLimiter


def test_audio_message_limit_bounds_envelopes_without_counting_frames() -> None:
    limiter = MessageRateLimiter(audio_message_limit=3, now=lambda: 0.0)

    assert limiter.allow_audio()
    assert limiter.allow_audio()
    assert limiter.allow_audio()
    assert not limiter.allow_audio()


def test_limits_reset_after_one_second() -> None:
    current = [0.0]
    limiter = MessageRateLimiter(control_limit=1, audio_message_limit=1, now=lambda: current[0])

    assert limiter.allow_control()
    assert not limiter.allow_control()
    assert limiter.allow_audio()
    assert not limiter.allow_audio()
    current[0] = 1.0
    assert limiter.allow_control()
    assert limiter.allow_audio()
