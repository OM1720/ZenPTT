import pytest
import json
import subprocess
from types import SimpleNamespace
from zipfile import ZipFile

import client_proxy
from client_proxy import qdisc, sent_packets, successful, upstream_caddyfile


def test_proxy_caddyfile_keeps_the_verified_upstream_host():
    config = upstream_caddyfile("wss://test.example.net/ws")
    assert "reverse_proxy https://test.example.net" in config
    assert "header_up Host test.example.net" in config
    with pytest.raises(ValueError):
        upstream_caddyfile("wss://test.example.net/bad")


def test_proxy_profiles_and_counter_evidence():
    assert "rate 48kbit" in qdisc("egress-48")
    assert "police rate 48kbit" in qdisc("ingress-police-48")
    assert "seed 2017" in qdisc("unstable", 2017)
    assert qdisc("baseline") is None
    assert sent_packets("Sent 42 bytes 3 pkt\nSent 100 bytes 7 pkt") == 7


def test_proxy_requires_complete_measurement_evidence():
    result = {"test_exit_code": 0, "tc_confirmed": True, "browser_direction_count": 2,
              "health_after": {"status": "ok"}, "server_logs": {"exit_code": 0}}
    assert successful(result)
    assert not successful({**result, "browser_direction_count": 1})
    assert not successful({**result, "tc_confirmed": False})
    assert not successful({**result, "server_logs": {"exit_code": 1}})
    assert not successful({**result, "system_error": "lost Docker connection"})


def test_proxy_verifies_the_exact_packaged_web_files(tmp_path, monkeypatch):
    monkeypatch.setattr(client_proxy, "ROOT", tmp_path)
    static = tmp_path / "web/dist/index.html"
    static.parent.mkdir(parents=True)
    static.write_bytes(b"published")
    package = tmp_path / "bundle.zip"
    with ZipFile(package, "w") as archive:
        archive.writestr("web/dist/index.html", b"published")
    assert client_proxy.verify_web_dist(package) == 1
    static.write_bytes(b"different")
    with pytest.raises(RuntimeError, match="differs"):
        client_proxy.verify_web_dist(package)


def test_experimental_manifest_is_independent_of_server_package(tmp_path, monkeypatch):
    import json
    monkeypatch.setattr(client_proxy, "ROOT", tmp_path)
    static = tmp_path / "web/dist/index.html"
    static.parent.mkdir(parents=True)
    static.write_bytes(b"candidate")
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({"variant": "P150", "dist": {"index.html": client_proxy.sha256(static)}}))
    assert client_proxy.verify_web_manifest(manifest)["variant"] == "P150"
    static.write_bytes(b"different")
    with pytest.raises(RuntimeError, match="manifest"):
        client_proxy.verify_web_manifest(manifest)


@pytest.mark.parametrize("status", ["pending", "failed"])
def test_proxy_rejects_incomplete_cleanup(status):
    result = {"test_exit_code": 0, "tc_confirmed": True, "browser_direction_count": 2,
              "health_after": {"status": "ok"}, "server_logs": {"exit_code": 0},
              "cleanup_status": status}
    assert not successful(result)


@pytest.mark.parametrize("failure", ["exception", "timeout", "exit_code"])
def test_cleanup_preserves_pending_result_and_continues(tmp_path, monkeypatch, failure):
    monkeypatch.setattr(client_proxy, "ROOT", tmp_path)
    output = tmp_path / "acceptance/artifacts/poor-link/case"
    config = tmp_path / "config.json"
    config.write_text(json.dumps({"server_url": "wss://test.example.net/ws"}))
    args = SimpleNamespace(output=str(output), config=str(config), server_url=None,
        profile="baseline", impaired="A", seed=1009, target_frames=100,
        diagnose_audio=False, prebuffer_ms=0, lightweight_observer=True, series=False,
        trace_browser=False, retry_of=None, port_a=18091, port_b=18092,
        expected_bundle="fixture", package_zip=None, web_manifest=None)
    calls = []

    def docker(*command, **kwargs):
        calls.append(command)
        if "up" in command:
            raise RuntimeError("original startup failure")
        if "down" in command:
            pending = json.loads((output / "result.json").read_text())
            assert pending["cleanup_status"] == "pending"
            assert pending["system_error"] == "RuntimeError: original startup failure"
            if failure == "exception":
                raise RuntimeError("cleanup failure")
            if failure == "timeout":
                raise subprocess.TimeoutExpired(command, 45)
            return SimpleNamespace(stdout="", stderr="cleanup failure", returncode=1)
        return SimpleNamespace(stdout="", stderr="", returncode=0)

    class Response:
        status = 200

        def __enter__(self):
            return self

        def __exit__(self, *_):
            pass

        def read(self):
            return b'{"status":"ok"}'

    monkeypatch.setattr(client_proxy, "docker", docker)
    monkeypatch.setattr(client_proxy, "method_hashes", lambda: {"fixture": "hash"})
    monkeypatch.setattr(client_proxy, "installed_bundle", lambda _: "fixture")
    monkeypatch.setattr(client_proxy, "clock_offset", lambda *a, **kw: {})
    monkeypatch.setattr(client_proxy, "url_health", lambda _: {"status": "ok"})
    monkeypatch.setattr(client_proxy, "tc_stats", lambda _: "Sent 42 bytes 3 pkt")
    monkeypatch.setattr("urllib.request.urlopen", lambda *a, **kw: Response())
    assert client_proxy.run_case(args) == output
    result = json.loads((output / "result.json").read_text())
    assert result["cleanup_status"] == "failed"
    assert result["cleanup_errors"]
    assert result["system_error"] == "RuntimeError: original startup failure"
    assert len([call for call in calls if call[:2] == ("rm", "-f")]) == 2
    assert (output / "proxy-A.log").exists()
    assert (output / "report.md").exists()
