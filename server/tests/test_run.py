from app import run
from app.protocol import MAX_MESSAGE_BYTES


def test_launcher_applies_transport_and_proxy_limits(monkeypatch) -> None:
    captured: dict = {}

    def capture_run(app: str, **kwargs) -> None:
        captured["app"] = app
        captured.update(kwargs)

    monkeypatch.setattr(run.uvicorn, "run", capture_run)

    run.main()

    log_config = captured.pop("log_config")
    assert log_config["loggers"]["zenptt.server"] == {
        "handlers": ["default"],
        "level": "INFO",
        "propagate": False,
    }
    assert captured == {
        "app": "app.main:app",
        "host": "0.0.0.0",
        "port": 8000,
        "workers": 1,
        "proxy_headers": True,
        "forwarded_allow_ips": "*",
        "limit_concurrency": 200,
        "ws_max_size": MAX_MESSAGE_BYTES,
        "ws_max_queue": 16,
        "ws_ping_interval": 20,
        "ws_ping_timeout": 60,
    }
