"""Loads validated Android release metadata and resolves immutable APK artifacts."""

import json
import logging
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, ValidationError

RELEASE_METADATA_FILE = "release.json"


class AppRelease(BaseModel):
    model_config = ConfigDict(extra="forbid")

    version_code: int = Field(ge=1)
    version_name: str = Field(min_length=1, max_length=40, pattern=r"^[0-9A-Za-z._-]+$")
    sha256: str = Field(pattern=r"^[0-9a-fA-F]{64}$")
    size_bytes: int = Field(ge=1)


class ReleaseStore:
    def __init__(self, directory: Path) -> None:
        self.directory = directory

    def apk_path(self, version_code: int) -> Path:
        return self.directory / f"zenptt-{version_code}.apk"

    def latest(self) -> AppRelease | None:
        try:
            release = AppRelease.model_validate_json(
                (self.directory / RELEASE_METADATA_FILE).read_text(encoding="utf-8"),
            )
            artifact = self.apk_path(release.version_code)
            if not artifact.is_file() or artifact.stat().st_size != release.size_bytes:
                return None
            return release
        except (OSError, ValidationError, json.JSONDecodeError) as error:
            logging.getLogger("zenptt.server").warning(
                "apk_release_unavailable: %s",
                error.__class__.__name__,
            )
            return None
