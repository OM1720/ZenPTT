"""Loads environment settings and enforces reviewed ceilings and cross-setting invariants."""

from pathlib import Path

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from .security_constants import (
    DIAGNOSTICS_MAX_BODY_BYTES,
    DIAGNOSTICS_MAX_CONCURRENT_SAVES,
    DIAGNOSTICS_MAX_REPORTS,
    DIAGNOSTICS_RETENTION_SECONDS,
    DIAGNOSTICS_MAX_UPLOADS_PER_CLIENT_PER_MINUTE,
    DIAGNOSTICS_MAX_UPLOADS_PER_MINUTE,
    MAX_ACTIVE_SESSIONS,
    MAX_ACTIVE_SESSIONS_PER_CLIENT_IP,
    MAX_AUDIO_MESSAGES_PER_SECOND,
    MAX_CHANNEL_CODE_LENGTH,
    MAX_CHANNEL_PARTICIPANTS,
    MAX_CONTROL_MESSAGES_PER_SECOND,
)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", extra="ignore")

    max_channel_code_length: int = Field(
        default=MAX_CHANNEL_CODE_LENGTH,
        ge=1,
        le=MAX_CHANNEL_CODE_LENGTH,
    )
    max_channel_participants: int = Field(
        default=MAX_CHANNEL_PARTICIPANTS,
        ge=1,
        le=MAX_CHANNEL_PARTICIPANTS,
    )
    recovery_horizon_ms: int = Field(default=5_000, ge=1_000, le=60_000)
    max_active_sessions: int = Field(
        default=MAX_ACTIVE_SESSIONS,
        ge=1,
        le=MAX_ACTIVE_SESSIONS,
    )
    max_active_sessions_per_client_ip: int = Field(
        default=MAX_ACTIVE_SESSIONS_PER_CLIENT_IP,
        ge=1,
        le=MAX_ACTIVE_SESSIONS_PER_CLIENT_IP,
    )
    max_control_messages_per_second: int = Field(
        default=MAX_CONTROL_MESSAGES_PER_SECOND,
        ge=1,
        le=MAX_CONTROL_MESSAGES_PER_SECOND,
    )
    max_audio_messages_per_second: int = Field(
        default=MAX_AUDIO_MESSAGES_PER_SECOND,
        ge=1,
        le=MAX_AUDIO_MESSAGES_PER_SECOND,
    )
    diagnostics_directory: Path = Path("/data/diagnostics")
    diagnostics_max_body_bytes: int = Field(
        default=DIAGNOSTICS_MAX_BODY_BYTES,
        ge=1,
        le=DIAGNOSTICS_MAX_BODY_BYTES,
    )
    diagnostics_max_reports: int = Field(
        default=DIAGNOSTICS_MAX_REPORTS,
        ge=1,
        le=DIAGNOSTICS_MAX_REPORTS,
    )
    diagnostics_retention_seconds: int = Field(
        default=DIAGNOSTICS_RETENTION_SECONDS,
        ge=1,
        le=DIAGNOSTICS_RETENTION_SECONDS,
    )
    diagnostics_max_uploads_per_minute: int = Field(
        default=DIAGNOSTICS_MAX_UPLOADS_PER_MINUTE,
        ge=1,
        le=DIAGNOSTICS_MAX_UPLOADS_PER_MINUTE,
    )
    diagnostics_max_uploads_per_client_per_minute: int = Field(
        default=DIAGNOSTICS_MAX_UPLOADS_PER_CLIENT_PER_MINUTE,
        ge=1,
        le=DIAGNOSTICS_MAX_UPLOADS_PER_CLIENT_PER_MINUTE,
    )
    diagnostics_max_concurrent_saves: int = Field(
        default=DIAGNOSTICS_MAX_CONCURRENT_SAVES,
        ge=1,
        le=DIAGNOSTICS_MAX_CONCURRENT_SAVES,
    )
    releases_directory: Path = Path("/data/releases")
    log_level: str = "INFO"

    @model_validator(mode="after")
    def validate_security_limits(self) -> "Settings":
        if self.recovery_horizon_ms % 20 != 0:
            raise ValueError("recovery horizon must be a multiple of 20 ms")
        if self.max_active_sessions_per_client_ip > self.max_active_sessions:
            raise ValueError("per-client session limit exceeds global session limit")
        if (
            self.diagnostics_max_uploads_per_client_per_minute
            > self.diagnostics_max_uploads_per_minute
        ):
            raise ValueError("per-client diagnostics limit exceeds global diagnostics limit")
        return self
