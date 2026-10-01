import json
import os
import re
import threading
from concurrent.futures import ThreadPoolExecutor
from itertools import count

import pytest
from fastapi.testclient import TestClient

from app.diagnostics import (
    DiagnosticReport,
    DiagnosticStore,
    DiagnosticUploadLimiter,
    REPORT_CODE_ALPHABET,
)
from app.main import create_app
from app.settings import Settings


def report(details: str = "channel=normal (redacted)\nmetrics=RTT 10ms") -> dict:
    return {
        "schemaVersion": 1,
        "createdAtMs": 123,
        "appVersion": "0.3.0",
        "androidVersion": "16 (SDK 36)",
        "networkStatus": "Connected",
        "headsetStatus": "Connected",
        "audioRoute": "Bluetooth headset",
        "details": details,
    }


def test_uploads_redacted_report_and_returns_id(tmp_path) -> None:
    app = create_app(Settings(diagnostics_directory=tmp_path))

    with TestClient(app) as client:
        response = client.post("/diagnostics", json=report())

    assert response.status_code == 201
    report_id = response.json()["report_id"]
    assert re.fullmatch(r"\d{6}-[0-9A-HJKMNP-TV-Z]{4}", report_id)
    stored = json.loads((tmp_path / f"{report_id}.json").read_text(encoding="utf-8"))
    assert stored["report"]["details"].startswith("channel=normal (redacted)")
    assert "SECRET42" not in json.dumps(stored)


def test_report_code_uses_server_utc_date_and_unambiguous_alphabet(tmp_path) -> None:
    store = DiagnosticStore(tmp_path, 500, 604_800, now=lambda: 0, suffix=lambda: "A234")

    report_id = store.save(DiagnosticReport.model_validate(report()))

    assert report_id == "700101-A234"
    assert set(REPORT_CODE_ALPHABET).isdisjoint("ILOU")


def test_retries_colliding_report_code_without_overwriting(tmp_path) -> None:
    existing = tmp_path / "700101-AAAA.json"
    existing.write_text("original", encoding="utf-8")
    suffixes = iter(["AAAA", "B234"])
    store = DiagnosticStore(
        tmp_path,
        500,
        604_800,
        now=lambda: 0,
        suffix=lambda: next(suffixes),
    )

    report_id = store.save(DiagnosticReport.model_validate(report()))

    assert report_id == "700101-B234"
    assert existing.read_text(encoding="utf-8") == "original"


def test_parallel_saves_have_unique_codes(tmp_path) -> None:
    sequence = count()
    store = DiagnosticStore(
        tmp_path,
        500,
        604_800,
        now=lambda: 0,
        suffix=lambda: f"{next(sequence):04X}",
    )
    diagnostic_report = DiagnosticReport.model_validate(report())

    with ThreadPoolExecutor(max_workers=8) as executor:
        report_ids = list(executor.map(lambda _: store.save(diagnostic_report), range(32)))

    assert len(set(report_ids)) == 32
    assert len(list(tmp_path.glob("*.json"))) == 32


def test_rejects_sensitive_details_and_unknown_fields(tmp_path) -> None:
    app = create_app(Settings(diagnostics_directory=tmp_path))

    with TestClient(app) as client:
        clear_channel = client.post("/diagnostics", json=report("channel=SECRET42"))
        audio_payload = client.post("/diagnostics", json=report("AuDiO_PaYlOaD=AAE="))
        extra_field = client.post("/diagnostics", json={**report(), "audioPayload": "AAE="})

    assert clear_channel.status_code == 422
    assert audio_payload.status_code == 422
    assert extra_field.status_code == 422
    assert list(tmp_path.glob("*.json")) == []


def test_rejects_unsupported_schema_and_large_body(tmp_path) -> None:
    app = create_app(
        Settings(diagnostics_directory=tmp_path, diagnostics_max_body_bytes=64),
    )
    unsupported = {**report(), "schemaVersion": 2}

    with TestClient(app) as client:
        schema_response = client.post("/diagnostics", json=unsupported)
        size_response = client.post("/diagnostics", json=report())

    assert schema_response.status_code == 422
    assert size_response.status_code == 413


def test_removes_expired_reports_and_limits_count(tmp_path) -> None:
    old_report = tmp_path / "old.json"
    old_report.write_text("{}", encoding="utf-8")
    os.utime(old_report, (0, 0))
    app = create_app(
        Settings(
            diagnostics_directory=tmp_path,
            diagnostics_max_reports=2,
            diagnostics_retention_seconds=1,
        ),
    )

    with TestClient(app) as client:
        for created_at in range(3):
            payload = {**report(), "createdAtMs": created_at}
            assert client.post("/diagnostics", json=payload).status_code == 201

    assert not old_report.exists()
    assert len(list(tmp_path.glob("*.json"))) == 2


def test_storage_failure_returns_safe_error(tmp_path) -> None:
    blocked = tmp_path / "not-a-directory"
    blocked.write_text("file", encoding="utf-8")
    app = create_app(Settings(diagnostics_directory=blocked))

    with TestClient(app) as client:
        response = client.post("/diagnostics", json=report())

    assert response.status_code == 503
    assert response.json() == {"detail": "Diagnostics storage unavailable"}


def test_rate_limits_diagnostic_uploads_before_storage(tmp_path) -> None:
    app = create_app(
        Settings(
            diagnostics_directory=tmp_path,
            diagnostics_max_uploads_per_minute=2,
            diagnostics_max_uploads_per_client_per_minute=2,
        )
    )

    with TestClient(app) as client:
        assert client.post("/diagnostics", json=report()).status_code == 201
        assert client.post("/diagnostics", json=report()).status_code == 201
        rejected = client.post("/diagnostics", json=report())

    assert rejected.status_code == 429
    assert rejected.json() == {"detail": "Too many diagnostic reports"}
    assert rejected.headers["retry-after"] == "60"
    assert len(list(tmp_path.glob("*.json"))) == 2


@pytest.mark.asyncio
async def test_diagnostic_rate_limit_resets_after_window() -> None:
    current = [10.0]
    limiter = DiagnosticUploadLimiter(1, 1, 60, now=lambda: current[0])

    assert await limiter.allow("client-a")
    assert not await limiter.allow("client-a")
    current[0] += 60
    assert await limiter.allow("client-a")


@pytest.mark.asyncio
async def test_diagnostic_client_limit_does_not_consume_other_clients_global_slots() -> None:
    limiter = DiagnosticUploadLimiter(3, 1, 60)

    assert await limiter.allow("client-a")
    assert not await limiter.allow("client-a")
    assert await limiter.allow("client-b")
    assert await limiter.allow("client-c")
    assert not await limiter.allow("client-d")


def test_busy_diagnostic_storage_fails_fast(tmp_path) -> None:
    app = create_app(
        Settings(
            diagnostics_directory=tmp_path,
            diagnostics_max_concurrent_saves=1,
        )
    )
    started = threading.Event()
    release = threading.Event()

    def blocking_save(_report: DiagnosticReport) -> str:
        started.set()
        assert release.wait(timeout=2)
        return "260723-TEST"

    app.state.diagnostic_store.save = blocking_save
    with TestClient(app) as client, ThreadPoolExecutor(max_workers=1) as executor:
        first = executor.submit(client.post, "/diagnostics", json=report())
        assert started.wait(timeout=1)
        try:
            busy = client.post("/diagnostics", json=report())
        finally:
            release.set()
        completed = first.result(timeout=2)

    assert completed.status_code == 201
    assert busy.status_code == 429
    assert busy.json() == {"detail": "Diagnostics server is busy"}
