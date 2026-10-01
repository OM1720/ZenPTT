"""Composes the FastAPI app and exposes health, diagnostics, release, and WebSocket routes."""

import asyncio
import logging

from fastapi import FastAPI, HTTPException, Request, WebSocket, status
from fastapi.responses import FileResponse, JSONResponse

from .diagnostics import (
    DiagnosticReceipt,
    DiagnosticReport,
    DiagnosticStore,
    DiagnosticUploadLimiter,
)
from .releases import AppRelease, ReleaseStore
from .session import SessionManager
from .settings import Settings

_DIAGNOSTICS_RATE_WINDOW_SECONDS = 60


def create_app(settings: Settings | None = None) -> FastAPI:
    active_settings = settings or Settings()
    logging.getLogger("zenptt.server").setLevel(active_settings.log_level.upper())
    application = FastAPI(
        title="ZenPTT",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    manager = SessionManager(active_settings)
    diagnostic_store = DiagnosticStore(
        active_settings.diagnostics_directory,
        active_settings.diagnostics_max_reports,
        active_settings.diagnostics_retention_seconds,
    )
    diagnostic_limiter = DiagnosticUploadLimiter(
        active_settings.diagnostics_max_uploads_per_minute,
        active_settings.diagnostics_max_uploads_per_client_per_minute,
        _DIAGNOSTICS_RATE_WINDOW_SECONDS,
    )
    diagnostic_saves = asyncio.Semaphore(active_settings.diagnostics_max_concurrent_saves)
    application.state.session_manager = manager
    application.state.diagnostic_store = diagnostic_store
    application.state.diagnostic_upload_limiter = diagnostic_limiter
    release_store = ReleaseStore(active_settings.releases_directory)

    @application.middleware("http")
    async def limit_diagnostic_uploads(request: Request, call_next):
        if request.method == "POST" and request.url.path == "/diagnostics":
            client_key = request.client.host if request.client else "unknown"
            if not await diagnostic_limiter.allow(client_key):
                return JSONResponse(
                    {"detail": "Too many diagnostic reports"},
                    status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                    headers={"Retry-After": str(_DIAGNOSTICS_RATE_WINDOW_SECONDS)},
                )
        return await call_next(request)

    @application.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @application.post(
        "/diagnostics",
        response_model=DiagnosticReceipt,
        status_code=status.HTTP_201_CREATED,
    )
    async def upload_diagnostics(request: Request, report: DiagnosticReport) -> DiagnosticReceipt:
        content_length = request.headers.get("content-length")
        try:
            declared_size = int(content_length) if content_length else None
        except ValueError:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, "Invalid Content-Length") from None
        if declared_size is not None and declared_size > active_settings.diagnostics_max_body_bytes:
            raise HTTPException(413, "Report is too large")
        body = await request.body()
        if len(body) > active_settings.diagnostics_max_body_bytes:
            raise HTTPException(413, "Report is too large")
        if diagnostic_saves.locked():
            raise HTTPException(
                status.HTTP_429_TOO_MANY_REQUESTS,
                "Diagnostics server is busy",
            )
        try:
            async with diagnostic_saves:
                report_id = await asyncio.to_thread(diagnostic_store.save, report)
        except OSError:
            logging.getLogger("zenptt.server").exception("diagnostic_report_storage_failed")
            raise HTTPException(
                status.HTTP_503_SERVICE_UNAVAILABLE,
                "Diagnostics storage unavailable",
            ) from None
        return DiagnosticReceipt(report_id=report_id)

    def published_release() -> AppRelease:
        release = release_store.latest()
        if release is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "No APK release published")
        return release

    @application.get("/app/latest", response_model=AppRelease)
    def latest_app_release() -> AppRelease:
        return published_release()

    def release_response(release: AppRelease) -> FileResponse:
        return FileResponse(
            release_store.apk_path(release.version_code),
            media_type="application/vnd.android.package-archive",
            filename=f"ZenPTT-{release.version_name}.apk",
        )

    @application.get("/app/download", response_class=FileResponse)
    def download_app_release() -> FileResponse:
        return release_response(published_release())

    @application.get("/app/releases/{version_code}/download", response_class=FileResponse)
    def download_versioned_app_release(version_code: int) -> FileResponse:
        artifact = release_store.apk_path(version_code)
        if version_code < 1 or not artifact.is_file():
            raise HTTPException(status.HTTP_404_NOT_FOUND, "APK release not found")
        return FileResponse(
            artifact,
            media_type="application/vnd.android.package-archive",
            filename=f"ZenPTT-{version_code}.apk",
        )

    @application.websocket("/ws")
    async def websocket_endpoint(websocket: WebSocket) -> None:
        await manager.handle(websocket)

    @application.websocket("/internal/echo/control")
    async def echo_control_endpoint(websocket: WebSocket) -> None:
        await manager.echo.handle_control(websocket)

    @application.get("/internal/echo/status")
    async def echo_status() -> dict[str, int | bool | str]:
        return manager.echo.status()

    return application


app = create_app()
