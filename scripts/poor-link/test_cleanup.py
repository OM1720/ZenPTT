"""Keep fallback cleanup scoped to the incomplete test run."""

from __future__ import annotations

import json

from cleanup import cases


def test_cleanup_only_targets_named_containers_in_incomplete_case(tmp_path) -> None:
    case = tmp_path / "case"
    case.mkdir()
    (case / "settings.json").write_text(json.dumps({"containers": {
        "sender": "zpt-012345abcdef", "receiver": "unrelated-container",
        "contender": "zpt-fedcba543210",
    }}), encoding="utf-8")
    assert cases(tmp_path) == [
        (case, "sender", "zpt-012345abcdef"),
        (case, "contender", "zpt-fedcba543210"),
    ]
    (case / "result.json").write_text("{}", encoding="utf-8")
    assert cases(tmp_path) == []
