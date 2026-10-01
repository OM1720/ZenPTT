import argparse
import json
import urllib.error
import urllib.request


def post_invalid_report(url: str, spoofed_ip: str) -> tuple[int, bytes]:
    request = urllib.request.Request(
        url.rstrip("/") + "/diagnostics",
        data=b"{}",
        headers={
            "Content-Type": "application/json",
            "X-Forwarded-For": spoofed_ip,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("server_url")
    parser.add_argument("client_limit", type=int)
    args = parser.parse_args()

    for index in range(args.client_limit):
        spoofed_ip = f"198.51.100.{index + 1}"
        status, _ = post_invalid_report(args.server_url, spoofed_ip)
        if status != 422:
            raise RuntimeError(f"Request {index + 1} returned HTTP {status}, expected 422")

    status, body = post_invalid_report(args.server_url, "203.0.113.1")
    if status != 429:
        raise RuntimeError(f"Spoofed forwarding header bypassed the limit: HTTP {status}")
    if json.loads(body) != {"detail": "Too many diagnostic reports"}:
        raise RuntimeError("Rate-limit response body is invalid")

    with urllib.request.urlopen(args.server_url.rstrip("/") + "/health", timeout=5) as response:
        health = json.load(response)
    if health != {"status": "ok"}:
        raise RuntimeError("Server health check failed after forwarding-header test")

    print("Caddy overwrote spoofed X-Forwarded-For headers: passed")


if __name__ == "__main__":
    main()
