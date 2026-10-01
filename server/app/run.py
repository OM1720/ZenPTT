"""Starts the single-worker Uvicorn server with bounded WebSocket and connection settings."""

from copy import deepcopy

import uvicorn
from uvicorn.config import LOGGING_CONFIG

from .protocol import MAX_MESSAGE_BYTES
from .security_constants import MAX_ACTIVE_SESSIONS


def server_log_config() -> dict:
    config = deepcopy(LOGGING_CONFIG)
    config["loggers"]["zenptt.server"] = {
        "handlers": ["default"],
        "level": "INFO",
        "propagate": False,
    }
    return config


def main() -> None:
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8000,
        workers=1,
        proxy_headers=True,
        # Port 8000 is private and Caddy replaces X-Forwarded-For.
        forwarded_allow_ips="*",
        # Keep a small reserve above the active WebSocket session ceiling.
        limit_concurrency=MAX_ACTIVE_SESSIONS + 20,
        ws_max_size=MAX_MESSAGE_BYTES,
        ws_max_queue=16,
        ws_ping_interval=20,
        ws_ping_timeout=60,
        log_config=server_log_config(),
    )


if __name__ == "__main__":
    main()
