"""Tests for GeoTracker (build step 5)."""
import time

import pytest

from retalert.core.geo_tracker import (
    GeoTracker, Fix, ManualFixSource, LastKnownFixSource, LinuxFixSource,
    clamp_lora_throttle,
    LORA_THROTTLE_MIN, LORA_THROTTLE_MAX, LORA_THROTTLE_DEFAULT,
)


def test_fix_geo_uri():
    fix = Fix(lat=12.345, lon=-67.89)
    assert fix.geo_uri == "geo:12.345,-67.89"


def test_fix_timestamp_auto_set():
    fix = Fix(lat=1.0, lon=2.0)
    assert fix.timestamp > 0


def test_manual_source_returns_fix_with_fresh_timestamp():
    src = ManualFixSource(lat=1.0, lon=2.0, accuracy=10.0)
    f1 = src.get_fix()
    first_ts = f1.timestamp
    time.sleep(0.01)
    f2 = src.get_fix()
    assert f2.timestamp > first_ts  # refreshed each call
    assert f2.lat == 1.0 and f2.lon == 2.0
    assert f2.accuracy == 10.0
    assert f2.source == "manual"


def test_one_shot_returns_fix():
    tracker = GeoTracker(ManualFixSource(lat=5.0, lon=6.0))
    fix = tracker.one_shot()
    assert fix is not None
    assert fix.geo_uri == "geo:5.0,6.0"


def test_one_shot_fires_on_fix():
    seen = []
    tracker = GeoTracker(ManualFixSource(lat=1.0, lon=2.0), on_fix=seen.append)
    tracker.one_shot()
    assert len(seen) == 1


def test_one_shot_none_when_source_empty():
    class Empty:
        def get_fix(self):
            return None
    tracker = GeoTracker(Empty())
    assert tracker.one_shot() is None


def test_live_share_emits_fixes_via_send_fn():
    sent = []
    tracker = GeoTracker(ManualFixSource(lat=1.0, lon=2.0))
    # GeoTracker enforces a 1.0s interval floor; use it and wait long enough.
    tracker.start_live_share(interval=1.0, send_fn=sent.append)
    time.sleep(2.25)
    tracker.stop_live_share()
    assert len(sent) >= 2  # at least two fixes in the window
    assert all(isinstance(f, Fix) for f in sent)


def test_is_sharing_state():
    tracker = GeoTracker(ManualFixSource(lat=1.0, lon=2.0))
    assert not tracker.is_sharing
    tracker.start_live_share(interval=0.5, send_fn=lambda f: None)
    assert tracker.is_sharing
    tracker.stop_live_share()
    assert not tracker.is_sharing


def test_start_live_share_idempotent():
    tracker = GeoTracker(ManualFixSource(lat=1.0, lon=2.0))
    tracker.start_live_share(interval=0.5, send_fn=lambda f: None)
    first_thread = tracker._thread
    tracker.start_live_share(interval=0.5, send_fn=lambda f: None)
    assert tracker._thread is first_thread  # no second thread
    tracker.stop_live_share()


def test_stop_live_share_terminates_thread():
    tracker = GeoTracker(ManualFixSource(lat=1.0, lon=2.0))
    tracker.start_live_share(interval=5.0, send_fn=lambda f: None)
    t = tracker._thread
    tracker.stop_live_share()
    assert not t.is_alive()


def test_last_known_source_caches():
    class Flaky:
        def __init__(self):
            self.calls = 0
        def get_fix(self):
            self.calls += 1
            if self.calls == 1:
                return Fix(lat=3.0, lon=4.0, source="flaky")
            return None
    src = LastKnownFixSource(Flaky())
    f1 = src.get_fix()
    assert f1.lat == 3.0
    f2 = src.get_fix()
    assert f2 is not None  # cached
    assert f2.lat == 3.0 and f2.lon == 4.0
    assert f2.source == "last_known"


def test_last_known_source_none_when_no_cache():
    class AlwaysEmpty:
        def get_fix(self):
            return None
    src = LastKnownFixSource(AlwaysEmpty())
    assert src.get_fix() is None


def test_linux_source_stub_raises():
    src = LinuxFixSource()
    with pytest.raises(NotImplementedError):
        src.get_fix()


def test_clamp_lora_throttle_defaults():
    assert clamp_lora_throttle(None) == LORA_THROTTLE_DEFAULT


def test_clamp_lora_throttle_clamps_low():
    assert clamp_lora_throttle(1) == LORA_THROTTLE_MIN
    assert clamp_lora_throttle(LORA_THROTTLE_MIN) == LORA_THROTTLE_MIN


def test_clamp_lora_throttle_clamps_high():
    assert clamp_lora_throttle(99999) == LORA_THROTTLE_MAX
    assert clamp_lora_throttle(LORA_THROTTLE_MAX) == LORA_THROTTLE_MAX


def test_clamp_lora_throttle_preserves_in_range():
    assert clamp_lora_throttle(45) == 45


def test_interval_clamped_to_minimum():
    tracker = GeoTracker(ManualFixSource(lat=1.0, lon=2.0))
    tracker.start_live_share(interval=0.01, send_fn=lambda f: None)
    assert tracker.interval >= 1.0  # GeoTracker enforces 1s floor
    tracker.stop_live_share()