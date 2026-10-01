#!/usr/bin/env python3
import argparse
import http.client
import json
import ssl
import urllib.parse
import urllib.request


def connection_for(parsed: urllib.parse.SplitResult) -> http.client.HTTPConnection:
    host = parsed.hostname
    if host is None:
        raise ValueError("Server URL must contain a host")
    if parsed.scheme == "https":
        return http.client.HTTPSConnection(
            host,
            parsed.port or 443,
            timeout=10,
            context=ssl.create_default_context(),
        )
    if parsed.scheme == "http":
        return http.client.HTTPConnection(host, parsed.port or 80, timeout=10)
    raise ValueError("Server URL must use http or https")


def post_oversized(
    parsed: urllib.parse.SplitResult,
    payload: bytes,
    *,
    chunked: bool,
) -> None:
    connection = connection_for(parsed)
    path = (parsed.path.rstrip("/") or "") + "/diagnostics"
    headers = {"Content-Type": "application/json"}
    body = [payload[index : index + 16_384] for index in range(0, len(payload), 16_384)]
    try:
        connection.request(
            "POST",
            path,
            body=body if chunked else payload,
            headers=headers,
            encode_chunked=chunked,
        )
        response = connection.getresponse()
        response_body = response.read()
    finally:
        connection.close()

    mode = "chunked" if chunked else "content-length"
    if response.status != 413:
        raise RuntimeError(f"{mode} oversized request returned HTTP {response.status}")
    if b"Report is too large" in response_body:
        raise RuntimeError(f"{mode} request reached FastAPI instead of being stopped by Caddy")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("server_url")
    parser.add_argument("max_body_bytes", type=int)
    args = parser.parse_args()

    report = {
        "schemaVersion": 1,
        "createdAtMs": 1,
        "appVersion": "limit-gate",
        "androidVersion": "automated",
        "networkStatus": "Connected",
        "headsetStatus": "Not checked",
        "audioRoute": "Not checked",
        "details": "limit_gate=true",
    }
    encoded = json.dumps(report, separators=(",", ":")).encode("utf-8")
    payload = encoded + b" " * (args.max_body_bytes + 1 - len(encoded))
    parsed = urllib.parse.urlsplit(args.server_url)

    post_oversized(parsed, payload, chunked=False)
    post_oversized(parsed, payload, chunked=True)

    health_url = args.server_url.rstrip("/") + "/health"
    with urllib.request.urlopen(health_url, timeout=5) as response:
        health = json.load(response)
    if health != {"status": "ok"}:
        raise RuntimeError("Server health check failed after oversized requests")
    print("Caddy rejected content-length and chunked oversized diagnostics with HTTP 413")


if __name__ == "__main__":
    main()
