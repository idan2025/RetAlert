"""Tests for IncomingDispatcher, LiveTrackStore, Settings (steps 8+9)."""
import time

import pytest

from retalert.core.incoming import (
    IncomingDispatcher, IncomingMessage,
    encode_alert, decode_alert, encode_ack, decode_ack,
    encode_reply, decode_reply,
    parse_geo_body, RETALERT_MARKER,
)
from retalert.core.live_tracks import LiveTrackStore, LiveTrack
from retalert.core.geo_tracker import Fix
from retalert.storage import Contacts, Settings


# -- wire format --------------------------------------------------------

def test_encode_decode_alert_roundtrip():
    body = encode_alert("danger", "help! roof collapsing")
    alert_id, sev, text = decode_alert(body)
    assert alert_id == ""  # v0 legacy
    assert sev == "danger"
    assert text == "help! roof collapsing"


def test_encode_decode_alert_v1_with_alert_id():
    body = encode_alert("danger", "help! now", alert_id="abc123")
    assert body.startswith(f"{RETALERT_MARKER}id:abc123!")
    alert_id, sev, text = decode_alert(body)
    assert alert_id == "abc123"
    assert sev == "danger"
    assert text == "help! now"


def test_decode_alert_returns_none_for_plain_text():
    assert decode_alert("just a message") is None
    assert decode_alert("") is None


def test_encode_decode_ack_roundtrip():
    body = encode_ack("abc123")
    assert body == f"{RETALERT_MARKER}ack!abc123"
    assert decode_ack(body) == "abc123"


def test_decode_ack_returns_none_for_non_ack():
    assert decode_ack("just a message") is None
    assert decode_ack(encode_alert("danger", "help")) is None  # alert, not ack
    assert decode_ack("") is None


def test_encode_decode_reply_roundtrip():
    body = encode_reply("aid1", "on my way")
    assert body == f"{RETALERT_MARKER}reply!aid1!on my way"
    rid, text = decode_reply(body)
    assert rid == "aid1"
    assert text == "on my way"


def test_encode_decode_reply_empty_text():
    body = encode_reply("aid1")
    rid, text = decode_reply(body)
    assert rid == "aid1"
    assert text == ""


def test_decode_reply_returns_none_for_non_reply():
    assert decode_reply("just a message") is None
    assert decode_reply(encode_ack("aid1")) is None  # ack, not reply
    assert decode_reply(encode_alert("danger", "help")) is None
    assert decode_reply("") is None


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
    alert_id, sev, text = decode_alert(body)
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


# -- app-level ack (step 9) ---------------------------------------------

def test_dispatcher_sends_ack_on_v1_alert(tmp_path):
    contacts = Contacts(tmp_path / "c.json")
    contacts.add("aa" * 16, "Alice")
    acks = []
    d = IncomingDispatcher(settings=Settings(tmp_path / "s.json"),
                          contacts=contacts,
                          send_ack_fn=lambda aid, src: acks.append((aid, src)))
    body = encode_alert("danger", "help", alert_id="aid1")
    msg = d.handle("aa" * 16, body, 0.0)
    assert msg.kind == "alert"
    assert msg.alert_id == "aid1"
    assert acks == [("aid1", "aa" * 16)]


def test_dispatcher_no_ack_for_v0_legacy_alert(tmp_path):
    contacts = Contacts(tmp_path / "c.json")
    contacts.add("aa" * 16, "Alice")
    acks = []
    d = IncomingDispatcher(settings=Settings(tmp_path / "s.json"),
                          contacts=contacts,
                          send_ack_fn=lambda aid, src: acks.append((aid, src)))
    body = encode_alert("danger", "help")  # no alert_id
    msg = d.handle("aa" * 16, body, 0.0)
    assert msg.kind == "alert"
    assert msg.alert_id == ""
    assert acks == []  # nothing to ack


def test_dispatcher_inbound_ack_calls_ack_cb(tmp_path):
    contacts = Contacts(tmp_path / "c.json")
    contacts.add("aa" * 16, "Alice")
    acked = []
    d = IncomingDispatcher(settings=Settings(tmp_path / "s.json"),
                          contacts=contacts,
                          ack_cb=lambda aid, src: acked.append((aid, src)))
    msg = d.handle("aa" * 16, encode_ack("aid1"), 0.0)
    assert msg.kind == "ack"
    assert msg.alert_id == "aid1"
    assert acked == [("aid1", "aa" * 16)]


def test_dispatcher_ack_does_not_fire_bypass_silent(tmp_path):
    contacts = Contacts(tmp_path / "c.json")
    contacts.add("aa" * 16, "Alice")
    fired = []
    d = IncomingDispatcher(settings=Settings(tmp_path / "s.json"),
                          contacts=contacts,
                          ack_cb=lambda aid, src: None)
    d.bypass_silent_cb = fired.append
    d.handle("aa" * 16, encode_ack("aid1"), 0.0)
    assert fired == []


def test_app_ack_roundtrip_end_to_end(tmp_path):
    """Sender alerts recipient 'aa' -> recipient acks -> sender AckTracker
    moves 'aa' to ACKED."""
    from retalert.core.ack_tracker import AckTracker
    from retalert.core.alert import Alert, ACKED

    contacts = Contacts(tmp_path / "c.json")
    contacts.add("aa" * 16, "Alice")  # recipient is a known contact

    # Recipient side: receives the alert, fires send_ack_fn back.
    sent_acks = []
    recv = IncomingDispatcher(settings=Settings(tmp_path / "rs.json"),
                              contacts=contacts,
                              send_ack_fn=lambda aid, src: sent_acks.append((aid, src)))
    alert = Alert(severity="danger", text="help", recipients=["aa" * 16],
                  alert_id="aid1")
    ack = AckTracker()
    ack.track(alert)
    recv.handle("aa" * 16, encode_alert("danger", "help", alert_id="aid1"),
                0.0)
    # Recipient acked the alert_id back to the (implied) sender.
    assert sent_acks == [("aid1", "aa" * 16)]

    # Sender side: receives the ack from recipient 'aa' -> AckTracker.on_ack.
    sender = IncomingDispatcher(settings=Settings(tmp_path / "ss.json"),
                               contacts=contacts,
                               ack_cb=ack.on_ack)
    sender.handle("aa" * 16, encode_ack("aid1"), 0.0)
    assert ack.state("aid1", "aa" * 16) == ACKED


def test_dispatcher_inbound_reply_calls_reply_cb(tmp_path):
    contacts = Contacts(tmp_path / "c.json")
    contacts.add("aa" * 16, "Alice")
    replied = []
    d = IncomingDispatcher(settings=Settings(tmp_path / "s.json"),
                          contacts=contacts,
                          reply_cb=lambda aid, src, txt: replied.append((aid, src, txt)))
    msg = d.handle("aa" * 16, encode_reply("aid1", "on my way"), 0.0)
    assert msg.kind == "reply"
    assert msg.alert_id == "aid1"
    assert msg.text == "on my way"
    assert replied == [("aid1", "aa" * 16, "on my way")]


def test_dispatcher_reply_does_not_fire_bypass_silent(tmp_path):
    contacts = Contacts(tmp_path / "c.json")
    contacts.add("aa" * 16, "Alice")
    fired = []
    d = IncomingDispatcher(settings=Settings(tmp_path / "s.json"),
                          contacts=contacts,
                          reply_cb=lambda aid, src, txt: None)
    d.bypass_silent_cb = fired.append
    d.handle("aa" * 16, encode_reply("aid1", "ok"), 0.0)
    assert fired == []


def test_app_reply_roundtrip_end_to_end(tmp_path):
    """Recipient replies to an alert -> sender AckTracker -> REPLIED."""
    from retalert.core.ack_tracker import AckTracker
    from retalert.core.alert import Alert, REPLIED

    contacts = Contacts(tmp_path / "c.json")
    contacts.add("aa" * 16, "Alice")
    ack = AckTracker()
    alert = Alert(severity="danger", text="help", recipients=["aa" * 16],
                  alert_id="aid1")
    ack.track(alert)

    sender = IncomingDispatcher(settings=Settings(tmp_path / "ss.json"),
                               contacts=contacts,
                               reply_cb=ack.on_ack)
    sender.handle("aa" * 16, encode_reply("aid1", "on my way"), 0.0)
    assert ack.state("aid1", "aa" * 16) == REPLIED
    assert ack.summary("aid1")["aa" * 16] == REPLIED