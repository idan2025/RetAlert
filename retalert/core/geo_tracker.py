"""GeoTracker — GPS one-shot fix and live-share mode.

Build step: 5 (implemented here). See PROMPT.md § Map screen and
§ Transport intelligence (LoRa GPS throttle).

Desktop has no GPS in step 5, so a FixSource abstraction is used:
``ManualFixSource`` (injected coords, for tests/CLI) and platform stubs
(Android/Linux) that raise NotImplementedError until their build step.
Live-share runs a thread that pulls a fix every ``interval`` seconds and
hands it to ``send_fn(fix)`` (wired by the daemon to send over LXMF/RNS).

The LoRa throttle interval (user-chosen 10s-3600s, default 60s) is applied
by the caller (daemon) when TransportIntelligence reports LoRa as the sole
up interface; GeoTracker itself just honours the interval it is given.
"""
from __future__ import annotations

import math
import threading
import time
from dataclasses import dataclass, asdict
from typing import Callable, Optional


# User-configurable LoRa live-share throttle bounds (seconds).
LORA_THROTTLE_MIN = 10
LORA_THROTTLE_MAX = 3600
LORA_THROTTLE_DEFAULT = 60
LORA_THROTTLE_PRESETS = (15, 30, 60, 120, 180)


def clamp_lora_throttle(seconds: Optional[float]) -> float:
    """Clamp a user LoRa throttle interval to [min, max]; default if None."""
    if seconds is None:
        return LORA_THROTTLE_DEFAULT
    return max(LORA_THROTTLE_MIN, min(LORA_THROTTLE_MAX, float(seconds)))


@dataclass
class Fix:
    lat: float
    lon: float
    accuracy: Optional[float] = None
    altitude: Optional[float] = None
    timestamp: float = 0.0
    source: str = "manual"

    def __post_init__(self):
        if self.timestamp == 0.0:
            self.timestamp = time.time()

    def as_dict(self) -> dict:
        return asdict(self)

    @property
    def geo_uri(self) -> str:
        """``geo:lat,lon`` URI (RFC 5870-ish). Compact, fits low-bandwidth links."""
        return f"geo:{self.lat},{self.lon}"


class FixSource:
    """Base class. ``get_fix`` returns a Fix or None if unavailable."""

    name = "base"

    def get_fix(self) -> Optional[Fix]:
        raise NotImplementedError


class ManualFixSource(FixSource):
    """Returns a fixed/injected fix. Used for tests and CLI ``--lat/--lon``."""

    name = "manual"

    def __init__(self, lat: float, lon: float, accuracy: Optional[float] = None,
                 altitude: Optional[float] = None):
        self._fix = Fix(lat=lat, lon=lon, accuracy=accuracy, altitude=altitude,
                        source="manual")

    def get_fix(self) -> Optional[Fix]:
        # Refresh timestamp each call so live-share emits fresh fixes.
        self._fix.timestamp = time.time()
        return self._fix


class LastKnownFixSource(FixSource):
    """Wraps another source, caching the last successful fix. Returns the
    cached fix (with refreshed timestamp) when the underlying source has no
    current fix — useful when GPS is intermittent."""

    name = "last_known"

    def __init__(self, inner: FixSource):
        self.inner = inner
        self._last: Optional[Fix] = None

    def get_fix(self) -> Optional[Fix]:
        fix = self.inner.get_fix()
        if fix is not None:
            self._last = fix
            return fix
        if self._last is not None:
            cached = Fix(lat=self._last.lat, lon=self._last.lon,
                         accuracy=self._last.accuracy, altitude=self._last.altitude,
                         source="last_known")
            return cached
        return None


class AndroidFixSource(FixSource):
    """Android GPS via platform API. Stub until the Kivy/service build step."""

    name = "android"

    def get_fix(self) -> Optional[Fix]:
        raise NotImplementedError("AndroidFixSource — Kivy/service build step")


class LinuxFixSource(FixSource):
    """Linux GPS (GeoClue / gpsd). Stub until a desktop backend is wired."""

    name = "linux"

    def get_fix(self) -> Optional[Fix]:
        raise NotImplementedError("LinuxFixSource — desktop backend build step")


class GeoTracker:
    """Produces GPS fixes: one-shot or live-share (periodic thread)."""

    def __init__(self, fix_source: FixSource,
                 on_fix: Optional[Callable[[Fix], None]] = None):
        self.fix_source = fix_source
        self.on_fix = on_fix
        self._sharing = False
        self._thread: Optional[threading.Thread] = None
        self._interval: float = 5.0

    # -- one-shot -------------------------------------------------------

    def one_shot(self) -> Optional[Fix]:
        fix = self.fix_source.get_fix()
        if fix is not None and self.on_fix:
            self.on_fix(fix)
        return fix

    # -- live-share -----------------------------------------------------

    def start_live_share(self, interval: float,
                         send_fn: Optional[Callable[[Fix], None]] = None) -> None:
        """Emit a fix every ``interval`` seconds, calling ``send_fn(fix)``
        and ``on_fix`` for each. Runs until ``stop_live_share``."""
        if self._sharing:
            return
        self._interval = max(1.0, float(interval))
        self._sharing = True
        self._thread = threading.Thread(target=self._share_loop,
                                        args=(send_fn,), daemon=True)
        self._thread.start()

    def stop_live_share(self) -> None:
        self._sharing = False
        if self._thread is not None:
            self._thread.join(timeout=self._interval + 1)
            self._thread = None

    @property
    def is_sharing(self) -> bool:
        return self._sharing

    @property
    def interval(self) -> float:
        return self._interval

    def _share_loop(self, send_fn: Optional[Callable[[Fix], None]]) -> None:
        while self._sharing:
            fix = self.fix_source.get_fix()
            if fix is not None:
                if self.on_fix:
                    try:
                        self.on_fix(fix)
                    except Exception:
                        pass
                if send_fn:
                    try:
                        send_fn(fix)
                    except Exception:
                        pass
            # Sleep in small slices so stop responds promptly.
            slept = 0.0
            while self._sharing and slept < self._interval:
                time.sleep(0.2)
                slept += 0.2


# --- geo helpers -------------------------------------------------------

EARTH_RADIUS_M = 6371000.0


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Distance in metres between two lat/lon points."""
    p1 = math.radians(lat1)
    p2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlmb = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlmb / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(a))