"""Validates, rate-limits, redacts, and persistently retains client diagnostic reports."""

import asyncio
import json
import re
import secrets
import threading
import time
from collections.abc import Callable
from datetime import datetime, timezone
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, field_validator

SAFE_CHANNEL = re.compile(r"(?m)^channel=(?:none|ECHO|normal \(redacted\))$")
ANY_CHANNEL = re.compile(r"(?m)^channel=.+$")
REPORT_CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
REPORT_CODE_ATTEMPTS = 32


class DiagnosticUploadLimiter:
    def __init__(
        self,
        limit: int,
        client_limit: int,
        window_seconds: int,
        now: Callable[[], float] = time.monotonic,
    ) -> None:
        self.limit = limit
        self.client_limit = client_limit
        self.window_seconds = window_seconds
        self.now = now
        self.window_started = now()
        self.count = 0
        self.client_counts: dict[str, int] = {}
        self.lock = asyncio.Lock()

    async def allow(self, client_key: str) -> bool:
        async with self.lock:
            current = self.now()
            if current - self.window_started >= self.window_seconds:
                self.window_started = current
                self.count = 0
                self.client_counts.clear()
            client_count = self.client_counts.get(client_key, 0)
            if self.count >= self.limit or client_count >= self.client_limit:
                return False
            self.count += 1
            self.client_counts[client_key] = client_count + 1
            return True


def random_report_suffix() -> str:
    return "".join(secrets.choice(REPORT_CODE_ALPHABET) for _ in range(4))


class DiagnosticReport(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schemaVersion: int = Field(ge=1, le=1)
    createdAtMs: int = Field(ge=0)
    appVersion: str = Field(min_length=1, max_length=40)
    androidVersion: str = Field(min_length=1, max_length=80)
    networkStatus: str = Field(min_length=1, max_length=40)
    headsetStatus: str = Field(min_length=1, max_length=80)
    audioRoute: str = Field(min_length=1, max_length=40)
    details: str = Field(max_length=48_000)

    @field_validator("details")
    @classmethod
    def reject_sensitive_details(cls, value: str) -> str:
        channel_lines = ANY_CHANNEL.findall(value)
        if any(SAFE_CHANNEL.fullmatch(line) is None for line in channel_lines):
            raise ValueError("channel must be redacted")
        if "audio_payload=" in value.lower():
            raise ValueError("audio payloads are not accepted")
        return value


class DiagnosticReceipt(BaseModel):
    report_id: str


class DiagnosticStore:
    def __init__(
        self,
        directory: Path,
        max_reports: int,
        retention_seconds: int,
        now: Callable[[], float] = time.time,
        suffix: Callable[[], str] = random_report_suffix,
    ) -> None:
        self.directory = directory
        self.max_reports = max_reports
        self.retention_seconds = retention_seconds
        self.now = now
        self.suffix = suffix
        self.lock = threading.Lock()

    def save(self, report: DiagnosticReport) -> str:
        with self.lock:
            self.directory.mkdir(parents=True, exist_ok=True)
            now = self.now()
            self._cleanup(now)
            report_id = self._save_unique(report, now)
            self._trim_to_limit()
            return report_id

    def _save_unique(self, report: DiagnosticReport, now: float) -> str:
        date = datetime.fromtimestamp(now, timezone.utc).strftime("%y%m%d")
        for _ in range(REPORT_CODE_ATTEMPTS):
            report_id = f"{date}-{self.suffix()}"
            target = self.directory / f"{report_id}.json"
            payload = {
                "report_id": report_id,
                "received_at_ms": int(now * 1000),
                "report": report.model_dump(),
            }
            try:
                with target.open("x", encoding="utf-8") as stream:
                    json.dump(payload, stream, ensure_ascii=False)
            except FileExistsError:
                continue
            return report_id
        raise OSError("Unable to allocate diagnostic report code")

    def _cleanup(self, now: float) -> None:
        cutoff = now - self.retention_seconds
        for path in self.directory.glob("*.json"):
            if path.stat().st_mtime < cutoff:
                path.unlink(missing_ok=True)

    def _trim_to_limit(self) -> None:
        reports = sorted(self.directory.glob("*.json"), key=lambda path: path.stat().st_mtime)
        for path in reports[: max(0, len(reports) - self.max_reports)]:
            path.unlink(missing_ok=True)
