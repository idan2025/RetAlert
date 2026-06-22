"""Tests for InboxRegistry + daemon inbox/reply/ack wiring (step 9 round-trip).

The InboxRegistry remembers received app-to-app alerts so a CLI/UI user can
reply (or re-ack) by ``alert_id``; the daemon records inbound ``alert``
IncomingMessages into it and exposes ``reply_to_alert`` / ``ack_alert``.
"""
import time

import pytest

from retalert.core.inbox import InboxRegistry, InboxEntry
from retalert.core.incoming import (
    IncomingMessage, encode_reply, encode_ack, RETALERT_MARKER,
)


# -- InboxRegistry ------------------------------------------------------

def test_record_and_get():
    reg = InboxRegistry()
    reg.record("aid1", "AA" * 16, "danger", "help", received_at=10.0)
    e = reg.get("aid1")
    assert e is not None
    assert e.alert_id == "aid1"
    assert e.severity == "danger"
    assert e.text == "help"
    assert e.received_at == 10.0


def test_record_lowercases_source_hash():
    reg = InboxRegistry()
    reg.record("aid1", "AA" * 16, "danger", "help")
    assert reg.get("aid1").source_hash == "aa" * 16


def test_record_requires_alert_id():
    reg = InboxRegistry()
    with pytest.raises(ValueError):
        reg.record("", "aa" * 16, "danger", "help")


def test_record_defaults_received_at_to_now():
    reg = InboxRegistry()
    before = time.time()
    reg.record("aid1", "aa" * 16, "danger", "help")
    assert reg.get("aid1").received_at >= before


def test_get_missing_returns_none():
    assert InboxRegistry().get("nope") is None


def test_list_newest_first():
    reg = InboxRegistry()
    reg.record("old", "aa" * 16, "danger", "1", received_at=1.0)
    reg.record("new", "bb" * 16, "danger", "2", received_at=2.0)
    ids = [e.alert_id for e in reg.list()]
    assert ids == ["new", "old"]


def test_record_overwrites_same_alert_id():
    reg = InboxRegistry()
    reg.record("aid1", "aa" * 16, "danger", "first")
    reg.record("aid1", "aa" * 16, "danger", "second")
    assert len(reg.list()) == 1
    assert reg.get("aid1").text == "second"


def test_remove():
    reg = InboxRegistry()
    reg.record("aid1", "aa" * 16, "danger", "help")
    assert reg.remove("aid1") is True
    assert reg.get("aid1") is None
    assert reg.remove("aid1") is False  # already gone


def test_clear_returns_count():
    reg = InboxRegistry()
    reg.record("a", "aa" * 16, "danger", "1")
    reg.record("b", "bb" * 16, "danger", "2")
    assert reg.clear() == 2
    assert reg.list() == []


def test_prune_drops_stale_keeps_fresh():
    reg = InboxRegistry(max_age_s=100)
    now = 1000.0
    reg.record("stale", "aa" * 16, "danger", "old", received_at=now - 200)
    reg.record("fresh", "bb" * 16, "danger", "new", received_at=now - 10)
    assert reg.prune(now=now) == 1
    assert reg.get("stale") is None
    assert reg.get("fresh") is not None


# -- persistence --------------------------------------------------------

def test_persists_and_reloads(tmp_path):
    path = tmp_path / "inbox.json"
    reg = InboxRegistry(path=path)
    reg.record("aid1", "AA" * 16, "danger", "help", received_at=5.0)
    # Fresh instance reads the same file.
    reg2 = InboxRegistry(path=path)
    e = reg2.get("aid1")
    assert e is not None
    assert e.source_hash == "aa" * 16
    assert e.text == "help"
    assert e.received_at == 5.0


def test_load_tolerates_missing_file(tmp_path):
    reg = InboxRegistry(path=tmp_path / "nope.json")
    assert reg.list() == []


def test_load_tolerates_corrupt_file(tmp_path):
    path = tmp_path / "inbox.json"
    path.write_text("{not valid json", "utf-8")
    reg = InboxRegistry(path=path)
    assert reg.list() == []  # ignored, not raised


def test_remove_persists(tmp_path):
    path = tmp_path / "inbox.json"
    reg = InboxRegistry(path=path)
    reg.record("aid1", "aa" * 16, "danger", "help")
    reg.remove("aid1")
    assert InboxRegistry(path=path).get("aid1") is None


# -- daemon integration -------------------------------------------------

class _FakeLXMF:
    """Captures outbound (dest, body) pairs instead of touching the network."""

    def __init__(self):
        self.sent = []

    def send_message(self, dest, body):
        self.sent.append((dest, body))


def _make_daemon(tmp_path):
    from retalert.config import AppConfig
    from retalert.daemon import EmergencyDaemon
    cfg = AppConfig.resolve(str(tmp_path))
    cfg.ensure_dirs()
    return EmergencyDaemon(cfg)


def _alert_msg(alert_id="aid1", src="aa" * 16, sev="danger", text="help",
               ts=123.0):
    return IncomingMessage(source_hash=src, text=text, timestamp=ts,
                           kind="alert", severity=sev, alert_id=alert_id)


def test_daemon_records_inbound_alert(tmp_path):
    daemon = _make_daemon(tmp_path)
    daemon._on_parsed_incoming(_alert_msg())
    e = daemon.inbox.get("aid1")
    assert e is not None
    assert e.source_hash == "aa" * 16
    assert e.severity == "danger"
    assert e.text == "help"
    assert e.received_at == 123.0


def test_daemon_ignores_v0_alert_without_id(tmp_path):
    daemon = _make_daemon(tmp_path)
    daemon._on_parsed_incoming(_alert_msg(alert_id=""))
    assert daemon.inbox.list() == []


def test_daemon_ignores_non_alert(tmp_path):
    daemon = _make_daemon(tmp_path)
    msg = IncomingMessage(source_hash="aa" * 16, text="hi", timestamp=1.0,
                          kind="text")
    daemon._on_parsed_incoming(msg)
    assert daemon.inbox.list() == []


def test_daemon_record_still_forwards_to_callback(tmp_path):
    daemon = _make_daemon(tmp_path)
    seen = []
    daemon.set_incoming_callback(seen.append)
    daemon._on_parsed_incoming(_alert_msg())
    assert len(seen) == 1 and seen[0].alert_id == "aid1"
    assert daemon.inbox.get("aid1") is not None


def test_daemon_inbox_persists_across_instances(tmp_path):
    daemon = _make_daemon(tmp_path)
    daemon._on_parsed_incoming(_alert_msg())
    # A second daemon on the same storage dir sees the recorded alert.
    daemon2 = _make_daemon(tmp_path)
    assert daemon2.inbox.get("aid1") is not None


def test_reply_to_alert_unknown_returns_false(tmp_path):
    daemon = _make_daemon(tmp_path)
    daemon.lxmf = _FakeLXMF()
    assert daemon.reply_to_alert("nope", "hi") is False
    assert daemon.lxmf.sent == []


def test_reply_to_alert_known_sends_reply(tmp_path):
    daemon = _make_daemon(tmp_path)
    daemon.lxmf = _FakeLXMF()
    daemon._on_parsed_incoming(_alert_msg())
    assert daemon.reply_to_alert("aid1", "on my way") is True
    assert daemon.lxmf.sent == [("aa" * 16, encode_reply("aid1", "on my way"))]


def test_ack_alert_unknown_returns_false(tmp_path):
    daemon = _make_daemon(tmp_path)
    daemon.lxmf = _FakeLXMF()
    assert daemon.ack_alert("nope") is False
    assert daemon.lxmf.sent == []


def test_ack_alert_known_sends_ack(tmp_path):
    daemon = _make_daemon(tmp_path)
    daemon.lxmf = _FakeLXMF()
    daemon._on_parsed_incoming(_alert_msg())
    assert daemon.ack_alert("aid1") is True
    assert daemon.lxmf.sent == [("aa" * 16, encode_ack("aid1"))]


def test_reply_roundtrip_receive_then_reply(tmp_path):
    """End-to-end at the daemon: an inbound alert is recorded, then the user
    replies by alert_id and the reply is wired back to the original sender."""
    daemon = _make_daemon(tmp_path)
    daemon.lxmf = _FakeLXMF()
    daemon._on_parsed_incoming(_alert_msg(src="cd" * 16))
    daemon.reply_to_alert("aid1", "coming")
    dest, body = daemon.lxmf.sent[0]
    assert dest == "cd" * 16
    assert body.startswith(f"{RETALERT_MARKER}reply!aid1!")
    assert body.endswith("coming")
