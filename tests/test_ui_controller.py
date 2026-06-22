"""Tests for retalert.ui.AppController (UI-agnostic daemon façade).

Covers the surface a Kivy/desktop shell relies on without starting RNS: the
inbound feed, inbox/reply/ack plumbing, presets/contacts views, status gating
before start, and panic with no preset configured.
"""
from retalert.ui import AppController
from retalert.core.incoming import IncomingMessage, encode_reply, encode_ack
from retalert.core.preset import Preset


class _FakeLXMF:
    def __init__(self):
        self.sent = []

    def send_message(self, dest, body):
        self.sent.append((dest, body))


def _ctl(tmp_path):
    return AppController(storage_dir=str(tmp_path))


def _alert(alert_id="aid1", src="aa" * 16, sev="danger", text="help"):
    return IncomingMessage(source_hash=src, text=text, timestamp=1.0,
                           kind="alert", severity=sev, alert_id=alert_id)


def test_not_started_status_and_hash(tmp_path):
    c = _ctl(tmp_path)
    assert c.started is False
    assert c.delivery_hash is None
    st = c.status()
    assert st["ready"] is False and st["interfaces"] == [] and st["gating"] == {}


def test_feed_starts_empty_and_records(tmp_path):
    c = _ctl(tmp_path)
    assert c.feed() == []
    c.daemon._on_parsed_incoming(_alert(text="roof"))
    feed = c.feed()
    assert len(feed) == 1
    assert feed[0]["alert_id"] == "aid1" and feed[0]["text"] == "roof"


def test_clear_feed(tmp_path):
    c = _ctl(tmp_path)
    c.daemon._on_parsed_incoming(_alert())
    c.clear_feed()
    assert c.feed() == []


def test_inbound_alert_lands_in_inbox(tmp_path):
    c = _ctl(tmp_path)
    c.daemon._on_parsed_incoming(_alert())
    inbox = c.inbox()
    assert len(inbox) == 1 and inbox[0].alert_id == "aid1"


def test_reply_routes_through_daemon(tmp_path):
    c = _ctl(tmp_path)
    c.daemon.lxmf = _FakeLXMF()
    c.daemon._on_parsed_incoming(_alert(src="cd" * 16))
    assert c.reply("aid1", "omw") is True
    assert c.daemon.lxmf.sent == [("cd" * 16, encode_reply("aid1", "omw"))]


def test_reply_unknown_returns_false(tmp_path):
    c = _ctl(tmp_path)
    c.daemon.lxmf = _FakeLXMF()
    assert c.reply("nope", "x") is False
    assert c.daemon.lxmf.sent == []


def test_ack_routes_through_daemon(tmp_path):
    c = _ctl(tmp_path)
    c.daemon.lxmf = _FakeLXMF()
    c.daemon._on_parsed_incoming(_alert())
    assert c.ack("aid1") is True
    assert c.daemon.lxmf.sent == [("aa" * 16, encode_ack("aid1"))]


def test_presets_view(tmp_path):
    c = _ctl(tmp_path)
    assert c.presets() == []
    c.daemon.preset_store.put(Preset(name="sos", severity="danger",
                                     recipients=["aa" * 16]))
    names = [p.name for p in c.presets()]
    assert "sos" in names


def test_preset_summaries(tmp_path):
    c = _ctl(tmp_path)
    assert c.preset_summaries() == []
    c.daemon.preset_store.put(Preset(name="sos", severity="danger",
                                     recipients=["aa" * 16, "bb" * 16],
                                     fan_out="all"))
    rows = c.preset_summaries()
    assert rows == [{"name": "sos", "severity": "danger", "fan_out": "all",
                     "recipients": 2}]


def test_panic_with_no_preset_returns_none(tmp_path):
    # No preset configured -> fire resolves nothing and returns None without
    # touching the network.
    c = _ctl(tmp_path)
    assert c.panic("default") is None


def test_resolve_recipients_hex(tmp_path):
    c = _ctl(tmp_path)
    assert c.resolve_recipients("AA" * 16) == ["aa" * 16]
    # colon-grouped hex is accepted too
    colon = ":".join(["aa"] * 16)
    assert c.resolve_recipients(colon) == ["aa" * 16]


def test_resolve_recipients_contact_name(tmp_path):
    c = _ctl(tmp_path)
    c.daemon.contacts.add("bb" * 16, "Bob")
    assert c.resolve_recipients("Bob") == ["bb" * 16]


def test_resolve_recipients_group(tmp_path):
    c = _ctl(tmp_path)
    c.daemon.groups.create("team", ["aa" * 16, "bb" * 16])
    assert c.resolve_recipients("team") == ["aa" * 16, "bb" * 16]


def test_resolve_recipients_unknown(tmp_path):
    assert _ctl(tmp_path).resolve_recipients("nobody") == []


def test_send_text_unknown_target_raises(tmp_path):
    c = _ctl(tmp_path)
    import pytest
    with pytest.raises(ValueError):
        c.send_text("help", "nobody")


def test_send_alert_records_outbox(tmp_path, monkeypatch):
    c = _ctl(tmp_path)
    # Stub the network send so we exercise only the outbox bookkeeping.
    monkeypatch.setattr(c.daemon, "send_alert", lambda alert: alert)
    a = c.send_alert("hi", ["aa" * 16], severity="danger", alert_id="x1")
    assert a.alert_id == "x1"
    assert [al.alert_id for al in c.sent_alerts()] == ["x1"]


def test_panic_records_outbox(tmp_path, monkeypatch):
    from retalert.core.alert import Alert
    c = _ctl(tmp_path)
    fake = Alert(severity="danger", text="x", recipients=["aa" * 16],
                 alert_id="p1")
    monkeypatch.setattr(c.daemon.panic, "fire", lambda trigger="default": fake)
    assert c.panic().alert_id == "p1"
    assert "p1" in [a.alert_id for a in c.sent_alerts()]


def test_sent_alerts_newest_first(tmp_path, monkeypatch):
    c = _ctl(tmp_path)
    monkeypatch.setattr(c.daemon, "send_alert", lambda alert: alert)
    c.send_alert("a", ["aa" * 16], alert_id="1")
    c.send_alert("b", ["aa" * 16], alert_id="2")
    assert [a.alert_id for a in c.sent_alerts()] == ["2", "1"]


def test_settings_view_defaults(tmp_path):
    st = _ctl(tmp_path).settings_view()
    assert st == {"receive_only": True, "allow": [], "deny": []}


def test_set_receive_only(tmp_path):
    c = _ctl(tmp_path)
    c.set_receive_only(False)
    assert c.settings_view()["receive_only"] is False


def test_allow_deny_forget(tmp_path):
    c = _ctl(tmp_path)
    c.allow("aa" * 16)
    c.deny("bb" * 16)
    st = c.settings_view()
    assert "aa" * 16 in st["allow"] and "bb" * 16 in st["deny"]
    c.forget("aa" * 16)
    assert "aa" * 16 not in c.settings_view()["allow"]


def test_add_remove_contact(tmp_path):
    c = _ctl(tmp_path)
    c.add_contact("aa" * 16, "Alice")
    assert [k.name for k in c.contacts()] == ["Alice"]
    assert c.remove_contact("aa" * 16) is True
    assert c.contacts() == []


def test_map_providers_and_radius(tmp_path):
    c = _ctl(tmp_path)
    keys = [p["key"] for p in c.map_providers()]
    assert "osm" in keys
    assert c.map_radius_options() == [5, 10, 20, 50, 100]


def test_estimate_and_no_offline_maps_initially(tmp_path):
    c = _ctl(tmp_path)
    assert c.estimate_offline_tiles(40.0, -73.0, 5) > 0
    assert c.offline_maps() == []


def test_download_offline_map_writes_mbtiles(tmp_path):
    c = _ctl(tmp_path)
    summary = c.download_offline_map(40.0, -73.0, 5, provider="osm",
                                     zooms=[12], fetch=lambda url: b"TILE")
    assert summary["saved"] == summary["requested"] > 0
    maps = c.offline_maps()
    assert len(maps) == 1 and maps[0].endswith(".mbtiles")


def test_tracks_empty_initially(tmp_path):
    assert _ctl(tmp_path).tracks() == []


def test_start_is_idempotent_flag(tmp_path):
    c = _ctl(tmp_path)
    # Simulate started without bringing up RNS.
    c._started = True
    c.start()  # no-op branch
    assert c.started is True
