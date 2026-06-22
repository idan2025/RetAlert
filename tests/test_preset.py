"""Tests for Preset, PresetStore, PanicEngine (build step 10)."""
import time

import pytest

from retalert.core.preset import Preset, PresetStore, PAYLOAD_CLASSES
from retalert.core.panic_engine import PanicEngine, PresetResolver
from retalert.core.alert import Alert
from retalert.core.geo_tracker import Fix, ManualFixSource, GeoTracker


def _h(n: int) -> str:
    return f"{n:032x}"


# -- Preset -------------------------------------------------------------

def test_preset_defaults_text_on():
    p = Preset(name="x")
    assert p.payload["text"] is True
    assert p.id  # auto-generated


def test_preset_payload_normalises_unknown_keys():
    p = Preset(name="x", payload={"text": True, "bogus": True})
    assert "bogus" not in p.payload
    assert set(p.payload) == set(PAYLOAD_CLASSES)


def test_preset_roundtrip(tmp_path):
    store = PresetStore(tmp_path / "presets.json")
    p = Preset(name="medical", severity="medical", text="help",
               recipients=[_h(1)], payload={"text": True, "gps_oneshot": True},
               fan_out="all", lora_throttle=60)
    store.put(p)
    store2 = PresetStore(tmp_path / "presets.json")
    got = store2.by_name("medical")
    assert got is not None
    assert got.severity == "medical"
    assert got.payload["gps_oneshot"] is True
    assert got.fan_out == "all"
    assert got.lora_throttle == 60


def test_preset_put_updates_existing(tmp_path):
    store = PresetStore(tmp_path / "presets.json")
    p = Preset(name="x", severity="help", recipients=[_h(1)])
    store.put(p)
    p2 = Preset(id=p.id, name="x", severity="danger", recipients=[_h(1)])
    store.put(p2)
    assert len(store.list()) == 1
    assert store.by_name("x").severity == "danger"


def test_preset_remove(tmp_path):
    store = PresetStore(tmp_path / "presets.json")
    p = Preset(name="x", recipients=[_h(1)])
    store.put(p)
    assert store.remove(p.id) is True
    assert store.by_name("x") is None
    assert store.remove("nope") is False


def test_preset_by_name_missing(tmp_path):
    store = PresetStore(tmp_path / "presets.json")
    assert store.by_name("nope") is None


# -- PresetResolver ----------------------------------------------------

def test_resolver_exact_match(tmp_path):
    store = PresetStore(tmp_path / "p.json")
    store.put(Preset(name="danger", severity="danger"))
    r = PresetResolver(store)
    assert r.resolve("danger").name == "danger"


def test_resolver_falls_back_to_default(tmp_path):
    store = PresetStore(tmp_path / "p.json")
    store.put(Preset(name="default", severity="help"))
    r = PresetResolver(store)
    assert r.resolve("nonexistent").name == "default"


def test_resolver_none_when_no_default(tmp_path):
    store = PresetStore(tmp_path / "p.json")
    store.put(Preset(name="danger", severity="danger"))
    r = PresetResolver(store)
    assert r.resolve("nonexistent") is None


# -- PanicEngine --------------------------------------------------------

class _FakeGroups:
    def __init__(self, members):
        self._members = members

    def members(self, name):
        return self._members.get(name, [])


def _engine(tmp_path, recipients=None, group_members=None, get_fix=None,
            start_live=None, payload=None):
    store = PresetStore(tmp_path / "p.json")
    store.put(Preset(
        name="default", severity="danger", text="help me",
        recipients=recipients or [_h(1), _h(2)],
        payload=payload or {"text": True},
        fan_out="critical",
    ))
    sent = []
    groups = _FakeGroups(group_members or {})
    eng = PanicEngine(
        store=store, groups=groups,
        send_alert_fn=lambda a: sent.append(a) or a,
        expand_group_fn=groups.members,
        start_live_share_fn=start_live,
        get_fix_fn=get_fix,
    )
    return eng, sent


def test_panic_fire_sends_alert_to_recipients(tmp_path):
    eng, sent = _engine(tmp_path)
    alert = eng.fire("default")
    assert alert is not None
    assert len(sent) == 1
    assert alert.recipients == [_h(1), _h(2)]
    assert alert.severity == "danger"
    assert alert.text == "help me"


def test_panic_fire_no_preset_returns_none(tmp_path):
    store = PresetStore(tmp_path / "p.json")  # empty
    eng = PanicEngine(store=store, groups=_FakeGroups({}),
                     send_alert_fn=lambda a: a, expand_group_fn=lambda n: [])
    assert eng.fire("nothing") is None


def test_panic_fire_expands_group(tmp_path):
    store = PresetStore(tmp_path / "p.json")
    store.put(Preset(name="default", severity="help", group="team",
                     payload={"text": True}))
    groups = _FakeGroups({"team": [_h(5), _h(6)]})
    sent = []
    eng = PanicEngine(store=store, groups=groups,
                     send_alert_fn=lambda a: sent.append(a) or a,
                     expand_group_fn=groups.members)
    alert = eng.fire("default")
    assert alert.recipients == [_h(5), _h(6)]


def test_panic_fire_attaches_gps_oneshot(tmp_path):
    fix = Fix(lat=1.0, lon=2.0)
    eng, sent = _engine(tmp_path, get_fix=lambda: fix,
                        payload={"text": True, "gps_oneshot": True})
    alert = eng.fire("default")
    assert "geo:1.0,2.0" in alert.text
    assert "help me" in alert.text


def test_panic_fire_starts_live_share(tmp_path):
    started = []
    eng, _ = _engine(tmp_path, start_live=lambda r, i: started.append((r, i)))
    store = eng.resolver.store
    p = store.by_name("default")
    store.put(Preset(id=p.id, name="default", severity="help",
                     recipients=[_h(1)], payload={"gps_live": True},
                     lora_throttle=30))
    eng.fire("default")
    assert started == [(_h(1), 30.0)]


def test_panic_dedup_within_window(tmp_path):
    eng, sent = _engine(tmp_path)
    eng.fire("default")
    # Immediate re-fire is deduped.
    alert2 = eng.fire("default")
    assert alert2 is None
    assert len(sent) == 1


def test_panic_dedup_cleared_on_done(tmp_path):
    eng, sent = _engine(tmp_path)
    alert = eng.fire("default")
    eng.on_alert_done(alert.alert_id)
    alert2 = eng.fire("default")
    assert alert2 is not None
    assert len(sent) == 2