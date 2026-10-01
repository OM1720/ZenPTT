import pytest
from pydantic import ValidationError

from app.settings import Settings


def test_defaults_are_valid() -> None:
    assert Settings().max_channel_participants > 0


@pytest.mark.parametrize("value", [0, -1])
def test_session_limits_reject_unsafe_values(value: int) -> None:
    with pytest.raises(ValidationError):
        Settings(max_active_sessions=value)
