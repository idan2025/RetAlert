"""Tests for AnnounceEngine (build step 6)."""
import time

from retalert.core.announce_engine import (
    AnnounceEngine, clamp_announce_interval,
    ANNOUNCE_MIN_INTERVAL, ANNOUNCE_MAX_INTERVAL,
    ANNOUNCE_PRESET_VALUES,
)


def test_clamp_min_enforced():
    assert clamp_announce_interval(10) == ANNOUNCE_MIN_INTERVAL
    assert clamp_announce_interval(None) == ANNOUNCE_MIN_INTERVAL


def test_clamp_max_enforced():
    assert clamp_announce_interval(999999) == ANNOUNCE_MAX_INTERVAL


def test_clamp_presets_in_range():
    for v in ANNOUNCE_PRESET_VALUES:
        assert clamp_announce_interval(v) == v


def test_announce_now_calls_fn():
    calls = []
    eng = AnnounceEngine(announce_fn=lambda: calls.append(1))
    eng.announce_now()
    assert calls == [1]
    assert eng.last_announce_monotonic > 0


def test_set_auto_off_does_not_start_thread():
    eng = AnnounceEngine(announce_fn=lambda: None)
    eng.set_auto(False)
    assert not eng.auto
    assert eng._thread is None


def test_set_auto_on_starts_thread_then_off_stops():
    eng = AnnounceEngine(announce_fn=lambda: None)
    eng.set_auto(True)
    assert eng.auto
    assert eng._thread is not None and eng._thread.is_alive()
    eng.set_auto(False)
    assert not eng.auto
    assert not (eng._thread and eng._thread.is_alive())


def test_auto_loop_calls_announce_fn():
    calls = []
    eng = AnnounceEngine(announce_fn=lambda: calls.append(1))
    # Bypass clamp for the test by setting interval directly.
    eng._interval = 0.2
    eng.set_auto(True)
    time.sleep(0.55)
    eng.set_auto(False)
    assert len(calls) >= 2  # immediate announce + at least one tick


def test_set_auto_clamps_interval():
    eng = AnnounceEngine(announce_fn=lambda: None)
    eff = eng.set_auto(True, interval=5)   # below min -> clamped to 30min
    assert eff == ANNOUNCE_MIN_INTERVAL
    eng.set_auto(False)
    eff = eng.set_auto(True, interval=999999)
    assert eff == ANNOUNCE_MAX_INTERVAL
    eng.set_auto(False)