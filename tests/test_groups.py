"""Tests for Groups storage + group send (build step 7)."""
from pathlib import Path

import pytest

from retalert.storage import Contacts, Groups
from retalert.cli import _resolve_member_token


def _h(n: int) -> str:
    return f"{n:032x}"


def test_groups_create_and_list(tmp_path):
    g = Groups(tmp_path / "groups.json")
    assert g.create("team", [_h(1), _h(2)]) is True
    rows = g.list()
    assert len(rows) == 1
    assert rows[0].name == "team"
    assert rows[0].members == [_h(1), _h(2)]


def test_groups_create_duplicate_returns_false(tmp_path):
    g = Groups(tmp_path / "groups.json")
    g.create("team", [_h(1)])
    assert g.create("team", [_h(2)]) is False
    # Original members untouched.
    assert g.members("team") == [_h(1)]


def test_groups_add_remove_member(tmp_path):
    g = Groups(tmp_path / "groups.json")
    g.create("team", [_h(1)])
    assert g.add_member("team", _h(2)) is True
    assert _h(2) in g.members("team")
    # idempotent
    g.add_member("team", _h(2))
    assert g.members("team").count(_h(2)) == 1
    assert g.remove_member("team", _h(2)) is True
    assert _h(2) not in g.members("team")
    assert g.remove_member("team", _h(99)) is False  # not a member


def test_groups_add_member_missing_group(tmp_path):
    g = Groups(tmp_path / "groups.json")
    assert g.add_member("nope", _h(1)) is False


def test_groups_remove(tmp_path):
    g = Groups(tmp_path / "groups.json")
    g.create("team", [_h(1)])
    assert g.remove("team") is True
    assert g.get("team") is None
    assert g.remove("team") is False


def test_groups_persist_across_instances(tmp_path):
    p = tmp_path / "groups.json"
    g = Groups(p)
    g.create("team", [_h(1), _h(2)])
    g2 = Groups(p)
    assert g2.members("team") == [_h(1), _h(2)]


def test_groups_get_missing(tmp_path):
    g = Groups(tmp_path / "groups.json")
    assert g.get("nope") is None
    assert g.members("nope") == []


def test_groups_lowercases_hashes(tmp_path):
    g = Groups(tmp_path / "groups.json")
    g.create("team", ["AB" * 16])
    assert g.members("team") == [("ab" * 16)]


def test_resolve_member_token_hex(tmp_path):
    contacts = Contacts(tmp_path / "contacts.json")
    h = "ab" * 16
    assert _resolve_member_token(h, contacts) == h


def test_resolve_member_token_hex_with_colons(tmp_path):
    contacts = Contacts(tmp_path / "contacts.json")
    h = "ab" * 16
    token = ":".join(h[i:i + 2] for i in range(0, len(h), 2))
    assert _resolve_member_token(token, contacts) == h


def test_resolve_member_token_contact_name(tmp_path):
    contacts = Contacts(tmp_path / "contacts.json")
    h = _h(7)
    contacts.add(h, "Alice")
    assert _resolve_member_token("Alice", contacts) == h


def test_resolve_member_token_unknown_raises(tmp_path):
    contacts = Contacts(tmp_path / "contacts.json")
    with pytest.raises(LookupError):
        _resolve_member_token("nobody", contacts)


def test_daemon_expand_group(tmp_path):
    from retalert.config import AppConfig
    from retalert.daemon import EmergencyDaemon
    cfg = AppConfig.resolve(str(tmp_path))
    cfg.ensure_dirs()
    daemon = EmergencyDaemon(cfg)
    daemon.groups.create("team", [_h(1), _h(2), _h(3)])
    assert daemon.expand_group("team") == [_h(1), _h(2), _h(3)]
    assert daemon.expand_group("missing") == []