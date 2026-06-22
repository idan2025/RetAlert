"""LiveTrackStore — incoming live location streams for the Map screen.

Build step: 8 (map backend). See PROMPT.md § Map screen. Peers sharing their
location live send periodic ``geo:`` fixes over LXMF; this store holds the
latest fix per source and which source the user is following (tap-to-follow).

The Map screen (Kivy, step 8 UI) renders markers from ``list()`` and
recentres on the followed source. The store itself is UI-agnostic and unit-
tested here so the tracking logic lands before the UI.
"""
from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field
from typing import List, Optional

from .geo_tracker import Fix


@dataclass
class LiveTrack:
    """One peer's latest live-share fix."""
    source_hash: str
    fix: Fix
    display_name: str = ""
    last_updated: float = 0.0

    def as_dict(self) -> dict:
        return {
            "source_hash": self.source_hash,
            "display_name": self.display_name,
            "fix": self.fix.as_dict(),
            "last_updated": self.last_updated,
        }


class LiveTrackStore:
    """Thread-safe store of live-sharing peers + the followed source."""

    def __init__(self):
        self._tracks: dict[str, LiveTrack] = {}
        self._followed: Optional[str] = None
        self._lock = threading.RLock()

    def update(self, source_hash: str, fix: Fix,
               display_name: str = "") -> LiveTrack:
        hash_hex = source_hash.lower().strip()
        with self._lock:
            existing = self._tracks.get(hash_hex)
            name = display_name or (existing.display_name if existing else "")
            track = LiveTrack(source_hash=hash_hex, fix=fix,
                              display_name=name, last_updated=time.time())
            self._tracks[hash_hex] = track
            return track

    def get(self, source_hash: str) -> Optional[LiveTrack]:
        hash_hex = source_hash.lower().strip()
        with self._lock:
            return self._tracks.get(hash_hex)

    def list(self) -> List[LiveTrack]:
        with self._lock:
            return list(self._tracks.values())

    def remove(self, source_hash: str) -> bool:
        hash_hex = source_hash.lower().strip()
        with self._lock:
            existed = hash_hex in self._tracks
            self._tracks.pop(hash_hex, None)
            if self._followed == hash_hex:
                self._followed = None
            return existed

    def clear(self) -> int:
        with self._lock:
            n = len(self._tracks)
            self._tracks.clear()
            self._followed = None
            return n

    # -- tap-to-follow --------------------------------------------------

    def follow(self, source_hash: str) -> bool:
        """Follow a peer's live track (map recentres on them). Returns False
        if the peer is not currently sharing."""
        hash_hex = source_hash.lower().strip()
        with self._lock:
            if hash_hex not in self._tracks:
                return False
            self._followed = hash_hex
            return True

    def unfollow(self) -> None:
        with self._lock:
            self._followed = None

    @property
    def followed(self) -> Optional[str]:
        with self._lock:
            return self._followed

    def followed_track(self) -> Optional[LiveTrack]:
        with self._lock:
            if self._followed is None:
                return None
            return self._tracks.get(self._followed)

    def clear_stale(self, max_age_seconds: float) -> int:
        """Drop tracks not updated within ``max_age_seconds``. Returns count."""
        cutoff = time.time() - max_age_seconds
        with self._lock:
            stale = [h for h, t in self._tracks.items()
                     if t.last_updated < cutoff]
            for h in stale:
                self._tracks.pop(h, None)
                if self._followed == h:
                    self._followed = None
            return len(stale)