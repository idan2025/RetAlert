"""InboxRegistry — remembers received app-to-app alerts so a CLI/UI user can
reply (or manually ack) by ``alert_id``.

Build step: 9 (full ack/reply tracking). Auto-acks fire on receipt regardless,
but a human reply needs the alert's source hash + original text, which this
registry captures from each inbound ``alert`` IncomingMessage and persists to
``inbox.json``.

Kept small and JSON-backed; entries are pruned to a max age so the inbox does
not grow without bound.
"""
from __future__ import annotations

import json
import time
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Dict, List, Optional


@dataclass
class InboxEntry:
    alert_id: str
    source_hash: str
    severity: str
    text: str
    received_at: float

    def as_dict(self) -> dict:
        return asdict(self)


class InboxRegistry:
    """Persisted map of received alert_id -> InboxEntry."""

    DEFAULT_MAX_AGE_S = 7 * 24 * 3600  # 7 days

    def __init__(self, path: Optional[Path] = None,
                 max_age_s: float = DEFAULT_MAX_AGE_S):
        self._path = Path(path) if path else None
        self._max_age_s = max_age_s
        self._entries: Dict[str, InboxEntry] = {}
        self._load()

    # -- persistence ----------------------------------------------------

    def _load(self) -> None:
        if self._path is None or not self._path.exists():
            return
        try:
            data = json.loads(self._path.read_text("utf-8"))
        except (json.JSONDecodeError, OSError):
            return
        for e in data.get("entries", []):
            try:
                self._entries[e["alert_id"]] = InboxEntry(
                    alert_id=e["alert_id"], source_hash=e["source_hash"],
                    severity=e.get("severity", ""), text=e.get("text", ""),
                    received_at=float(e.get("received_at", 0.0)))
            except (KeyError, TypeError, ValueError):
                continue

    def _save(self) -> None:
        if self._path is None:
            return
        self._path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"entries": [e.as_dict() for e in self._entries.values()]}
        self._path.write_text(json.dumps(payload, indent=2), "utf-8")

    # -- API ------------------------------------------------------------

    def record(self, alert_id: str, source_hash: str, severity: str,
               text: str, received_at: Optional[float] = None) -> InboxEntry:
        """Remember a received alert (v1 only — needs an alert_id)."""
        if not alert_id:
            raise ValueError("alert_id required")
        entry = InboxEntry(alert_id=alert_id, source_hash=source_hash.lower(),
                          severity=severity, text=text,
                          received_at=received_at if received_at is not None
                          else time.time())
        self._entries[alert_id] = entry
        self._save()
        return entry

    def get(self, alert_id: str) -> Optional[InboxEntry]:
        return self._entries.get(alert_id)

    def list(self) -> List[InboxEntry]:
        return sorted(self._entries.values(),
                       key=lambda e: e.received_at, reverse=True)

    def remove(self, alert_id: str) -> bool:
        changed = alert_id in self._entries
        if changed:
            del self._entries[alert_id]
            self._save()
        return changed

    def clear(self) -> int:
        n = len(self._entries)
        self._entries.clear()
        self._save()
        return n

    def prune(self, now: Optional[float] = None) -> int:
        """Drop entries older than max_age_s. Returns count removed."""
        now = now if now is not None else time.time()
        stale = [aid for aid, e in self._entries.items()
                 if now - e.received_at > self._max_age_s]
        for aid in stale:
            del self._entries[aid]
        if stale:
            self._save()
        return len(stale)