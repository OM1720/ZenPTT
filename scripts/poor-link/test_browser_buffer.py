import json
import os
from pathlib import Path
import stat
from types import SimpleNamespace

import pytest

import browser_buffer
from browser_buffer import build_variants, cases


def test_matrix_has_all_45_lightweight_and_six_pcm_cases_in_rotated_order():
    planned = cases()
    assert len(planned) == 51
    assert sum(case["observer"] == "lightweight" for case in planned) == 45
    assert len({tuple(case.items()) for case in planned}) == 51
    for profile in ("baseline", "unstable", "egress-32", "egress-48", "egress-64"):
        for seed, order in ((1009, ["P100", "P150", "P200"]),
                            (2017, ["P150", "P200", "P100"]),
                            (3037, ["P200", "P100", "P150"])):
            assert [case["variant"] for case in planned if case["profile"] == profile
                    and case["seed"] == seed and case["observer"] == "lightweight"] == order


@pytest.mark.parametrize("default_delay", [100, 150, 200])
def test_builds_all_candidates_from_any_working_default(tmp_path, monkeypatch, default_delay):
    repo = tmp_path / "repo"
    processor = repo / "web/src/audio/processor.ts"
    processor.parent.mkdir(parents=True)
    original = (f"export const PLAYBACK_START_DELAY_MS = {default_delay}\n"
                "const CAPTURE_CUE_MS = 100\n")
    processor.write_text(original)
    unchanged = repo / "web/src/unchanged.ts"
    unchanged.write_text("export const KEEP = 100\n")
    output = tmp_path / "study"
    output.mkdir()
    builds = []
    monkeypatch.setattr(browser_buffer, "ROOT", repo)
    monkeypatch.setattr(browser_buffer, "command", lambda args, **_: SimpleNamespace(stdout=
        "web/src/audio/processor.ts\nweb/src/unchanged.ts\n" if args[1] == "ls-files" else "fixture-commit\n"))

    def fake_docker(*args, **_):
        if args[0] == "cp":
            (Path(args[-1]) / "index.html").write_text("fixture build")
        return SimpleNamespace(stdout="fixture-image\n")

    def fake_build(args, **_):
        builds.append(Path(args[-1]))

    monkeypatch.setattr(browser_buffer, "docker", fake_docker)
    monkeypatch.setattr(browser_buffer.subprocess, "run", fake_build)
    build_variants(output)

    assert processor.read_text() == original
    assert (output / "source-common/src/audio/processor.ts").read_text() == original
    assert builds == [output / f"P{delay}/source" for delay in (100, 150, 200)]
    for delay in (100, 150, 200):
        candidate = output / f"P{delay}"
        expected = original.replace(f"PLAYBACK_START_DELAY_MS = {default_delay}",
                                    f"PLAYBACK_START_DELAY_MS = {delay}")
        assert (candidate / "source/src/audio/processor.ts").read_text() == expected
        assert (candidate / "source/src/unchanged.ts").read_text() == unchanged.read_text()
        manifest = json.loads((candidate / "manifest.json").read_text())
        assert manifest["delay_ms"] == delay
        assert manifest["source"] == browser_buffer.file_hashes(candidate / "source")
        assert manifest["dist"] == browser_buffer.file_hashes(candidate / "dist")
        assert bool((candidate / "buffer.patch").read_text()) == (delay != default_delay)


def dist_fixture(tmp_path, monkeypatch):
    monkeypatch.setattr(browser_buffer, "ROOT", tmp_path)
    destination = tmp_path / "web/dist"
    destination.mkdir(parents=True)
    (destination / "old.js").write_bytes(b"original")
    candidate = tmp_path / "acceptance/artifacts/poor-link/study/candidate"
    candidate.mkdir(parents=True)
    (candidate / "new.js").write_bytes(b"candidate")
    return destination, candidate


@pytest.mark.parametrize("invalid", ["missing", "outside"])
def test_invalid_candidate_preserves_current_dist(tmp_path, monkeypatch, invalid):
    destination, candidate = dist_fixture(tmp_path, monkeypatch)
    before = browser_buffer.file_hashes(destination)
    if invalid == "missing":
        candidate = candidate / "missing"
    else:
        candidate = tmp_path / "outside"
        candidate.mkdir()
        (candidate / "file").write_bytes(b"outside")
    with pytest.raises((ValueError, FileNotFoundError)):
        browser_buffer.select_dist(candidate)
    assert browser_buffer.file_hashes(destination) == before


def test_destination_cannot_resolve_to_another_web_directory(tmp_path, monkeypatch):
    destination, candidate = dist_fixture(tmp_path, monkeypatch)
    other = tmp_path / "web/other"
    other.mkdir()
    (other / "user.js").write_bytes(b"preserve")
    real_resolve = Path.resolve
    monkeypatch.setattr(Path, "resolve", lambda self, *a, **kw: (
        other if self == destination else real_resolve(self, *a, **kw)))
    with pytest.raises(ValueError, match="Unexpected web/dist"):
        browser_buffer.select_dist(candidate)
    assert (other / "user.js").read_bytes() == b"preserve"


@pytest.mark.parametrize("readonly", [False, True])
def test_only_confirmed_readonly_deletion_is_retried(tmp_path, monkeypatch, readonly):
    destination, candidate = dist_fixture(tmp_path, monkeypatch)
    target = destination / "old.js"
    calls = []
    real_rmtree = browser_buffer.shutil.rmtree

    def rmtree(path, **kwargs):
        def retry(item):
            calls.append(item)
        error = PermissionError("fixture denial")
        kwargs["onerror"](retry, str(target), (PermissionError, error, None))
        real_rmtree(path)

    real_stat = Path.stat
    monkeypatch.setattr(Path, "stat", lambda self, *a, **kw: (
        SimpleNamespace(st_file_attributes=stat.FILE_ATTRIBUTE_READONLY if readonly else 0)
        if self == target else real_stat(self, *a, **kw)))
    monkeypatch.setattr(browser_buffer.shutil, "rmtree", rmtree)
    real_chmod = os.chmod
    monkeypatch.setattr(browser_buffer.os, "chmod", lambda path, mode, **kwargs: (
        calls.append((path, mode)) if Path(path) == target else real_chmod(path, mode, **kwargs)))
    if readonly:
        browser_buffer.select_dist(candidate)
        assert calls == [(target, stat.S_IWRITE), target]
        assert browser_buffer.file_hashes(destination) == browser_buffer.file_hashes(candidate)
    else:
        with pytest.raises(PermissionError):
            browser_buffer.select_dist(candidate)
        assert calls == []


@pytest.mark.skipif(os.name != "nt", reason="Windows ReadOnly attributes")
def test_real_readonly_file_and_directory(tmp_path, monkeypatch):
    destination, candidate = dist_fixture(tmp_path, monkeypatch)
    assets = destination / "assets"
    assets.mkdir()
    file = assets / "asset.js"
    file.write_bytes(b"old")
    os.chmod(file, stat.S_IREAD)
    os.chmod(assets, stat.S_IREAD)
    try:
        browser_buffer.select_dist(candidate)
        assert browser_buffer.file_hashes(destination) == browser_buffer.file_hashes(candidate)
    finally:
        for path in (file, assets):
            if path.exists():
                os.chmod(path, stat.S_IWRITE)


def test_restoration_preserves_original_error_and_verifies_hashes(tmp_path, monkeypatch):
    destination, backup = dist_fixture(tmp_path, monkeypatch)
    expected = browser_buffer.file_hashes(backup)
    with pytest.raises(RuntimeError, match="original scenario failure"):
        try:
            raise RuntimeError("original scenario failure")
        finally:
            browser_buffer.restore_dist(backup)
    record = json.loads((backup.parent / (backup.name + "-restoration.json")).read_text())
    assert record["restored"]
    assert record["primary_error"] == "RuntimeError: original scenario failure"
    assert browser_buffer.file_hashes(destination) == expected


def test_restoration_failure_records_both_errors(tmp_path, monkeypatch):
    _, backup = dist_fixture(tmp_path, monkeypatch)
    monkeypatch.setattr(browser_buffer, "select_dist", lambda _: (_ for _ in ()).throw(PermissionError("restore denied")))
    with pytest.raises(RuntimeError, match="original scenario failure"):
        try:
            raise RuntimeError("original scenario failure")
        finally:
            browser_buffer.restore_dist(backup)
    record = json.loads((backup.parent / (backup.name + "-restoration.json")).read_text())
    assert not record["restored"]
    assert "original scenario failure" in record["primary_error"]
    assert "restore denied" in record["restoration_error"]
