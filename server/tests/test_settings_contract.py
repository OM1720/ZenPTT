from pathlib import Path

import pytest
from pydantic import ValidationError

from app.security_constants import (
    DIAGNOSTICS_MAX_BODY_BYTES,
    MAX_ACTIVE_SESSIONS,
    MAX_AUDIO_MESSAGES_PER_SECOND,
    MAX_CHANNEL_CODE_LENGTH,
    MAX_CHANNEL_PARTICIPANTS,
)
from app.settings import Settings


CEILING_CASES = [
    ("max_channel_code_length", MAX_CHANNEL_CODE_LENGTH + 1),
    ("max_channel_participants", MAX_CHANNEL_PARTICIPANTS + 1),
    ("max_active_sessions", MAX_ACTIVE_SESSIONS + 1),
    ("max_audio_messages_per_second", MAX_AUDIO_MESSAGES_PER_SECOND + 1),
    ("diagnostics_max_body_bytes", DIAGNOSTICS_MAX_BODY_BYTES + 1),
]


@pytest.mark.parametrize(("field", "value"), CEILING_CASES)
def test_rejects_security_settings_above_compiled_ceiling(field: str, value) -> None:
    with pytest.raises(ValidationError):
        Settings(**{field: value})


def test_public_env_template_satisfies_settings_contract(monkeypatch) -> None:
    for field in Settings.model_fields:
        monkeypatch.delenv(field.upper(), raising=False)
    settings = Settings(_env_file=Path(__file__).resolve().parents[2] / "server.env.example")
    assert settings.max_channel_code_length == MAX_CHANNEL_CODE_LENGTH
    assert settings.max_channel_participants == MAX_CHANNEL_PARTICIPANTS
    assert settings.recovery_horizon_ms == 5_000
    assert settings.max_audio_messages_per_second == MAX_AUDIO_MESSAGES_PER_SECOND


def test_rejects_per_client_session_limit_above_global_limit() -> None:
    with pytest.raises(ValidationError):
        Settings(max_active_sessions=5, max_active_sessions_per_client_ip=6)


def test_reads_environment_style_names(monkeypatch) -> None:
    monkeypatch.setenv("MAX_CHANNEL_PARTICIPANTS", "7")
    assert Settings().max_channel_participants == 7


@pytest.mark.parametrize("value", [999, 60_001, 1_010])
def test_recovery_horizon_is_bounded_and_frame_aligned(value: int) -> None:
    with pytest.raises(ValidationError):
        Settings(recovery_horizon_ms=value)
