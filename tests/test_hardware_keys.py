"""Tests for HardwareKeyManager (build step 11)."""
import time

import pytest

from retalert.core.hardware_keys import HardwareKeyManager, KeyCombo


def test_direct_fire_no_arm_combos():
    fired = []
    km = HardwareKeyManager(fire_fn=fired.append)
    km.register(("vol_down", "vol_down", "vol_down"), "danger")
    r = km.feed_sequence(["vol_down", "vol_down", "vol_down"])
    assert r == "danger"
    assert fired == ["danger"]


def test_no_match_returns_none():
    fired = []
    km = HardwareKeyManager(fire_fn=fired.append)
    km.register(("a", "b"), "x")
    assert km.feed_sequence(["a", "c"]) is None
    assert fired == []


def test_arm_then_fire():
    fired = []
    km = HardwareKeyManager(fire_fn=fired.append)
    km.register(("power", "power"), "arm_trigger", arm=True)
    km.register(("power", "power", "power"), "danger")
    # Arm combo first.
    assert km.feed_sequence(["power", "power"]) is None
    assert km.armed is True
    # Fire combo now goes through.
    r = km.feed_sequence(["power", "power", "power"])
    assert r == "danger"
    assert fired == ["danger"]
    assert km.armed is False  # fire consumes arm


def test_fire_without_arm_ignored_when_arm_required():
    fired = []
    km = HardwareKeyManager(fire_fn=fired.append)
    km.register(("power", "power"), "arm", arm=True)
    km.register(("power", "power", "power"), "danger")
    # Fire combo without arming -> ignored.
    assert km.feed_sequence(["power", "power", "power"]) is None
    assert fired == []


def test_arm_expires():
    fired = []
    km = HardwareKeyManager(fire_fn=fired.append, arm_window_s=0.05)
    km.register(("power", "power"), "arm", arm=True)
    km.register(("power", "power", "power"), "danger")
    km.feed_sequence(["power", "power"])
    assert km.armed
    time.sleep(0.1)
    assert km.feed_sequence(["power", "power", "power"]) is None
    assert fired == []


def test_cooldown_dedup():
    fired = []
    km = HardwareKeyManager(fire_fn=fired.append, cooldown_s=0.2)
    km.register(("x",), "t")
    km.feed("x")
    # Immediate re-feed within cooldown -> ignored.
    assert km.feed("x") is None
    assert fired == ["t"]
    time.sleep(0.25)
    assert km.feed("x") == "t"
    assert fired == ["t", "t"]


def test_unregister():
    km = HardwareKeyManager(fire_fn=lambda t: None)
    km.register(("a", "b"), "x")
    assert km.unregister(("a", "b")) is True
    assert km.list_combos() == []
    assert km.unregister(("a", "b")) is False


def test_register_replaces_same_combo():
    km = HardwareKeyManager(fire_fn=lambda t: None)
    km.register(("a",), "x")
    km.register(("a",), "y")
    assert len(km.list_combos()) == 1
    assert km.list_combos()[0].trigger == "y"


def test_register_empty_raises():
    km = HardwareKeyManager(fire_fn=lambda t: None)
    with pytest.raises(ValueError):
        km.register((), "x")


def test_persistence(tmp_path):
    p = tmp_path / "keys.json"
    km = HardwareKeyManager(fire_fn=lambda t: None, path=p)
    km.register(("a", "b"), "x", arm=True)
    km.register(("c",), "y")
    km2 = HardwareKeyManager(fire_fn=lambda t: None, path=p)
    combos = {(c.combo, c.trigger, c.arm) for c in km2.list_combos()}
    assert (("a", "b"), "x", True) in combos
    assert (("c",), "y", False) in combos


def test_persistence_roundtrip_fires(tmp_path):
    p = tmp_path / "keys.json"
    fired = []
    km = HardwareKeyManager(fire_fn=fired.append, path=p)
    km.register(("v", "v", "v"), "danger")
    km2 = HardwareKeyManager(fire_fn=fired.append, path=p)
    assert km2.feed_sequence(["v", "v", "v"]) == "danger"


def test_clear(tmp_path):
    p = tmp_path / "keys.json"
    km = HardwareKeyManager(fire_fn=lambda t: None, path=p)
    km.register(("a",), "x")
    km.clear()
    assert km.list_combos() == []
    # Reload: cleared persists.
    km2 = HardwareKeyManager(fire_fn=lambda t: None, path=p)
    assert km2.list_combos() == []


def test_arm_and_disarm_methods():
    km = HardwareKeyManager(fire_fn=lambda t: None)
    assert not km.armed
    km.arm()
    assert km.armed
    km.disarm()
    assert not km.armed


def test_feed_sequence_returns_last_fire():
    km = HardwareKeyManager(fire_fn=lambda t: None)
    km.register(("x",), "a")
    km.register(("y",), "b")
    r = km.feed_sequence(["x", "y"])
    assert r == "b"