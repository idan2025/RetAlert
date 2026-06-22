"""AnnounceEngine — manual + automatic re-announce of our LXMF delivery.

Build step: 6. See PROMPT.md § Recipients & contacts (Discover / network
screen) and the user requirement: manual announce send, auto-announce
toggle with 1h/2h/3h presets or custom up to 12h in seconds, minimum
30 minutes (1800s) enforced.

The engine is transport-agnostic: it holds a callable ``announce_fn`` (wired
by the daemon to ``LXMFTransport.announce``) and a background thread that
re-announces every ``interval`` seconds when auto mode is on. ``announce_now``
fires an immediate announce (manual button / CLI one-shot).
"""
from __future__ import annotations

import threading
import time
from typing import Callable, Optional


# Auto-announce interval bounds (seconds). User-chosen; min 30 min enforced.
ANNOUNCE_MIN_INTERVAL = 1800       # 30 minutes
ANNOUNCE_MAX_INTERVAL = 43200      # 12 hours
ANNOUNCE_PRESET_VALUES = (3600, 7200, 10800)   # 1h, 2h, 3h
ANNOUNCE_PRESETS = {3600: "1h", 7200: "2h", 10800: "3h"}


def clamp_announce_interval(seconds: Optional[float]) -> float:
    """Clamp a user auto-announce interval to [30min, 12h]; default 30min."""
    if seconds is None:
        return ANNOUNCE_MIN_INTERVAL
    return max(ANNOUNCE_MIN_INTERVAL, min(ANNOUNCE_MAX_INTERVAL, float(seconds)))


class AnnounceEngine:
    """Owns the auto-announce loop. ``announce_fn`` is called on each tick."""

    def __init__(self, announce_fn: Callable[[], None]):
        self._announce_fn = announce_fn
        self._auto = False
        self._interval = float(ANNOUNCE_MIN_INTERVAL)
        self._thread: Optional[threading.Thread] = None
        self._running = False
        self._last_announce = 0.0

    # -- state ----------------------------------------------------------

    @property
    def auto(self) -> bool:
        return self._auto

    @property
    def interval(self) -> float:
        return self._interval

    @property
    def last_announce_monotonic(self) -> float:
        return self._last_announce

    # -- manual ---------------------------------------------------------

    def announce_now(self) -> None:
        """Fire one announce immediately (manual send)."""
        self._announce_fn()
        self._last_announce = time.monotonic()

    # -- automatic ------------------------------------------------------

    def set_auto(self, enabled: bool,
                 interval: Optional[float] = None) -> float:
        """Enable/disable auto-announce. If ``interval`` is given it is clamped
        and applied. Returns the effective interval."""
        if interval is not None:
            self._interval = clamp_announce_interval(interval)
        self._auto = bool(enabled)
        if self._auto:
            self._start()
        else:
            self._stop()
        return self._interval

    def stop(self) -> None:
        """Stop the auto loop (daemon shutdown)."""
        self._auto = False
        self._stop()

    def _start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def _stop(self) -> None:
        self._running = False
        if self._thread is not None:
            self._thread.join(timeout=2.0)
            self._thread = None

    def _loop(self) -> None:
        # Announce once immediately, then every `interval` seconds.
        while self._running and self._auto:
            try:
                self.announce_now()
            except Exception:
                # Never let the auto-announce loop die on a transport error.
                pass
            slept = 0.0
            while self._running and self._auto and slept < self._interval:
                time.sleep(0.5)
                slept += 0.5