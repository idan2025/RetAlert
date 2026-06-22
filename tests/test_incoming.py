"""Tests for IncomingDispatcher, LiveTrackStore, Settings (steps 8+9)."""
import time

import pytest

from retalert.core.incoming import (
    IncomingDispatcher, IncomingMessage,
    encode_alert, decode_alert, parse_geo_body, RETALERT_MARKER,
)
from retalert.core.live_tracks import LiveTrackStore, LiveTrack
from retalert.core.geo_tracker import Fix
from retalert.storage import Contacts, Settings


# -- wire format --------------------------------------------------------

def test_encode_decode_alert_roundtrip():
    body = encode_alert("danger", "help! roof collapsing")
    sev, text = decode_alert(body)
    assert sev == "danger"
    assert text == "help! roof collapsing"


def test_decode_alert_returns_none_for_plain_text():
    assert decode_alert("just a message") is None
    assert decode_alert("") is None


def test_parse_geo_body_basic():
    fix = parse_geo_body("geo:12.34,-56.78 acc=15 alt=100 src=manual")
    assert fix is not None
    assert fix.lat == 12.34 and fix.lon == -56.78
    assert fix.accuracy == 15.0 and fix.altitude == 100.0
    assert fix.source == "manual"


def test_parse_geo_body_minimal():
    fix = parse_geo_body("geo:1.0,2.0")
    assert fix is not None and fix.lat == 1.0 and fix.lon == 2.0
    assert fix.accuracy is None and fix.altitude is None


def test_parse_geo_body_non_geo_returns_none():
    assert parse_geo_body("hello there") is None


def test_alert_with_embedded_location():
    body = encode_alert("medical", "geo:40.0,-73.0 acc=5")
    sev, text = decode_alert(body)
    fix = parse_geo_body(text)
    assert sev == "medical"
    assert fix is not None and fix.lat == 40.0


# -- LiveTrackStore -----------------------------------------------------

def _fix(lat=1.0, lon=2.0):
    return Fix(lat=lat, lon=lon, accuracy=10.0, source="lxmf")


def test_tracks_update_and_list():
    store = LiveTrackStore()
    store.update("aa" * 16, _fix(1, 2), display_name="Alice")
    store.update("bb" * 16, _fix(3, 4), display_name="Bob")
    tracks = store.list()
    assert len(tracks) == 2
    names = {t.display_name for t in tracks}
    assert names == {"Alice", "Bob"}


def test_tracks_get_missing():
    store = LiveTrackStore()
    assert store.get("cc" * 16) is None


def test_tracks_follow_requires_existing():
    store = LiveTrackStore()
    assert store.follow("aa" * 16) is False
    store.update("aa" * 16, _fix())
    assert store.follow("aa" * 16) is True
    assert store.followed == "aa" * 16
    assert store.followed_track() is not None


def test_tracks_unfollow():
    store = LiveTrackStore()
    store.update("aa" * 16, _fix())
    store.follow("aa" * 16)
    store.unfollow()
    assert store.followed is None


def test_tracks_remove_clears_follow():
    store = LiveTrackStore()
    store.update("aa" * 16, _fix())
    store.follow("aa" * 16)
    store.remove("aa" * 16)
    assert store.followed is None


def test_tracks_clear():
    store = LiveTrackStore()
    store.update("aa" * 16, _fix())
    store.update("bb" * 16, _fix())
    n = store.clear()
    assert n == 2
    assert store.list() == []


def test_tracks_clear_stale():
    store = LiveTrackStore()
    store.update("aa" * 16, _fix())
    # Force old timestamp.
    store.get("aa" * 16).last_updated = time.time() - 100
    n = store.clear_stale(10)
    assert n == 1
    assert store.list() == []


def test_tracks_update_preserves_name():
    store = LiveTrackStore()
    store.update("aa" * 16, _fix(), display_name="Alice")
    store.update("aa" * 16, _fix())  # no name this time
    assert store.get("aa" * 16).display_name == "Alice"


# -- Settings -----------------------------------------------------------

def test_settings_defaults(tmp_path):
    s = Settings(tmp_path / "settings.json")
    assert s.receive_only_from_contacts is True
    assert s.allowlist == set() and s.denylist == set()


def test_settings_allow_deny_persist(tmp_path):
    p = tmp_path / "settings.json"
    s = Settings(p)
    s.allow("aa" * 16)
    s.deny("bb" * 16)
    s2 = Settings(p)
    assert "aa" * 16 in s2.allowlist
    assert "bb" * 16 in s2.denylist


def test_settings_allow_removes_from_deny(tmp_path):
    s = Settings(tmp_path / "settings.json")
    s.deny("aa" * 16)
    s.allow("aa" * 16)
    assert "aa" * 16 not in s.denylist
    assert "aa" * 16 in s.allowlist


def test_settings_toggle(tmp_path):
    p = tmp_path / "settings.json"
    s = Settings(p)
    s.set_receive_only_from_contacts(False)
    assert Settings(p).receive_only_from_contacts is False


def test_settings_forget(tmp_path):
    s = Settings(tmp_path / "settings.json")
    s.allow("aa" * 16)
    s.forget("aa" * 16)
    assert "aa" * 16 not in s.allowlist


# -- IncomingDispatcher filter -----------------------------------------

def _make_dispatcher(tmp_path, contacts=None, receive_only=True,
                     allow=(), deny=()):
    contacts = contacts if contacts is not None else Contacts(tmp_path / "c.json")
    s = Settings(tmp_path / "s.json")
    s.receive_only_from_contacts = receive_only
    for h in allow:
        s.allow(h)
    for h in deny:
        s.deny(h)
    return IncomingDispatcher(settings=s, contacts=contacts)


def test_dispatcher_drops_unknown_when_receive_only(tmp_path):
    d = _make_dispatcher(tmp_path)
    msg = d.handle("aa" * 16, "hi", 0.0)
    assert msg is None  # filtered


def test_dispatcher_accepts_known_contact(tmp_path):
    contacts = Contacts(tmp_path / "c.json")
    contacts.add("aa" * 16, "Alice")
    d = _make_dispatcher(tmp_path, contacts=contacts)
    msg = d.handle("aa" * 16, "hi", 0.0)
    assert msg is not None
    assert msg.kind == "text"
    assert msg.text == "hi"


def test_dispatcher_allowlist_overrides_receive_only(tmp_path):
    d = _make_dispatcher(tmp_path, allow=["bb" * 16])
    msg = d.handle("bb" * 16, "hi", 0.0)
    assert msg is not None


def test_dispatcher_denylist_overrides_contact(tmp_path):
    contacts = Contacts(tmp_path / "c.json")
    contacts.add("aa" * 16, "Alice")
    d = _make_dispatcher(tmp_path, contacts=contacts, deny=["aa" * 16])
    msg = d.handle("aa" * 16, "hi", 0.0)
    assert msg is None


def test_dispatcher_open_mode_accepts_anyone(tmp_path):
    d = _make_dispatcher(tmp_path, receive_only=False)
    msg = d.handle("zz" * 16, "hi", 0.0)
    assert msg is not None


def test_dispatcher_parses_alert(tmp_path):
    contacts = Contacts(tmp_path / "c.json")
    contacts.add("aa" * 16, "Alice")
    d = _make_dispatcher(tmp_path, contacts=contacts)
    body = encode_alert("danger", "roof collapsing")
    msg = d.handle("aa" * 16, body, 0.0)
    assert msg.kind == "alert"
    assert msg.severity == "danger"
    assert msg.text == "roof collapsing"


def test_dispatcher_parses_geo_and_updates_tracks(tmp_path):
    contacts = Contacts(tmp_path / "c.json")
    contacts.add("aa" * 16, "Alice")
    d = _make_dispatcher(tmp_path, contacts=contacts)
    d.handle("aa" * 16, "geo:10.0,20.0 acc=5", 0.0)
    track = d.tracks.get("aa" * 16)
    assert track is not None
    assert track.fix.lat == 10.0 and track.fix.lon == 20.0


def test_dispatcher_bypass_silent_fires_on_alert(tmp_path):
    contacts = Contacts(tmp_path / "c.json")
    contacts.add("aa" * 16, "Alice")
    d = _make_dispatcher(tmp_path, contacts=contacts)
    fired = []
    d.bypass_silent_cb = fired.append
    d.handle("aa" * 16, encode_alert("medical", "help"), 0.0)
    assert len(fired) == 1
    assert fired[0].kind == "alert"


def test_dispatcher_bypass_silent_not_fired_for_text(tmp_path):
    contacts = Contacts(tmp_path / "c.json")
    contacts.add("aa" * 16, "Alice")
    d = _make_dispatcher(tmp_path, contacts=contacts)
    fired = []
    d.bypass_silent_cb = fired.append
    d.handle("aa" * 16, "casual hello", 0.0)
    assert fired == []


def test_dispatcher_on_message_callback(tmp_path):
    contacts = Contacts(tmp_path / "c.json")
    contacts.add("aa" * 16, "Alice")
    seen = []
    d = IncomingDispatcher(settings=Settings(tmp_path / "s.json"),
                           contacts=contacts, on_message=seen.append)
    d.handle("aa" * 16, "hi", 0.0)
    assert len(seen) == 1
    assert seen[0].text == "hi"