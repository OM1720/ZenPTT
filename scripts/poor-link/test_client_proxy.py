import pytest
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
