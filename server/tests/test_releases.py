import hashlib
import json

from fastapi.testclient import TestClient

from app.main import create_app
from app.settings import Settings


def publish_release(
    directory,
    apk: bytes = b"fake-apk",
    version_code: int = 14,
    version_name: str = "0.5.0",
) -> dict:
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f"zenptt-{version_code}.apk").write_bytes(apk)
    release = {
        "version_code": version_code,
        "version_name": version_name,
        "sha256": hashlib.sha256(apk).hexdigest(),
        "size_bytes": len(apk),
    }
    (directory / "release.json").write_text(json.dumps(release), encoding="utf-8")
    return release


def test_returns_latest_release_and_current_apk(tmp_path) -> None:
    release = publish_release(tmp_path)
    app = create_app(Settings(releases_directory=tmp_path))

    with TestClient(app) as client:
        latest = client.get("/app/latest")
        download = client.get("/app/download")
        versioned_download = client.get("/app/releases/14/download")

    assert latest.status_code == 200
    assert latest.json() == release
    assert download.status_code == 200
    assert download.content == b"fake-apk"
    assert download.headers["content-type"] == "application/vnd.android.package-archive"
    assert "ZenPTT-0.5.0.apk" in download.headers["content-disposition"]
    assert versioned_download.status_code == 200
    assert versioned_download.content == b"fake-apk"


def test_old_version_remains_available_with_range_after_new_release(tmp_path) -> None:
    publish_release(tmp_path, b"first-apk", version_code=13, version_name="0.4.0")
    publish_release(tmp_path, b"second-apk", version_code=14, version_name="0.5.0")
    app = create_app(Settings(releases_directory=tmp_path))

    with TestClient(app) as client:
        old_download = client.get("/app/releases/13/download")
        old_range = client.get("/app/releases/13/download", headers={"range": "bytes=0-3"})
        current_range = client.get("/app/releases/14/download", headers={"range": "bytes=0-3"})
        unknown = client.get("/app/releases/12/download")

    assert old_download.status_code == 200
    assert old_download.content == b"first-apk"
    assert old_range.status_code == 206
    assert old_range.content == b"firs"
    assert old_range.headers["content-range"] == "bytes 0-3/9"
    assert current_range.status_code == 206
    assert current_range.content == b"seco"
    assert current_range.headers["content-range"] == "bytes 0-3/10"
    assert unknown.status_code == 404
    assert unknown.json() == {"detail": "APK release not found"}


def test_missing_or_incomplete_release_returns_safe_not_found(tmp_path) -> None:
    app = create_app(Settings(releases_directory=tmp_path))

    with TestClient(app) as client:
        missing = client.get("/app/latest")
        publish_release(tmp_path)
        (tmp_path / "zenptt-14.apk").write_bytes(b"wrong-size")
        incomplete = client.get("/app/download")

    assert missing.status_code == 404
    assert missing.json() == {"detail": "No APK release published"}
    assert incomplete.status_code == 404
    assert incomplete.json() == {"detail": "No APK release published"}


def test_invalid_release_metadata_is_not_exposed(tmp_path) -> None:
    tmp_path.joinpath("release.json").write_text('{"version_name":"../bad"}', encoding="utf-8")
    app = create_app(Settings(releases_directory=tmp_path))

    with TestClient(app) as client:
        response = client.get("/app/latest")

    assert response.status_code == 404
    assert response.json() == {"detail": "No APK release published"}
