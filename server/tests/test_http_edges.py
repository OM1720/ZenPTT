import json

from fastapi.testclient import TestClient

from app.main import create_app
from app.settings import Settings


def diagnostic_payload() -> dict:
    return {
        "schemaVersion": 1,
        "createdAtMs": 1,
        "appVersion": "test",
        "androidVersion": "test",
        "networkStatus": "Connected",
        "headsetStatus": "Not connected",
        "audioRoute": "Phone",
        "details": "channel=none\n" + "x" * 1_000,
    }


def test_diagnostics_enforces_actual_body_size_not_only_declared_length(tmp_path) -> None:
    app = create_app(Settings(diagnostics_directory=tmp_path, diagnostics_max_body_bytes=256))
    body = json.dumps(diagnostic_payload()).encode()

    with TestClient(app) as client:
        response = client.post(
            "/diagnostics",
            content=body,
            headers={"content-type": "application/json", "content-length": "1"},
        )

    assert response.status_code == 413
    assert list(tmp_path.glob("*.json")) == []


def test_diagnostics_rejects_malformed_content_length_safely(tmp_path) -> None:
    app = create_app(Settings(diagnostics_directory=tmp_path))

    with TestClient(app) as client:
        response = client.post(
            "/diagnostics",
            content=json.dumps(diagnostic_payload()),
            headers={"content-type": "application/json", "content-length": "invalid"},
        )

    assert response.status_code == 400
    assert response.json() == {"detail": "Invalid Content-Length"}


def test_openapi_schema_is_not_public() -> None:
    with TestClient(create_app()) as client:
        response = client.get("/openapi.json")

    assert response.status_code == 404
