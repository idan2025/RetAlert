"""AppController — UI-agnostic façade over EmergencyDaemon.

The Kivy app (``main.py``) and any other shell talk to this, never to RNS/LXMF
directly. It owns the daemon, exposes flat action/view methods, and keeps a
small thread-safe buffer of recent inbound messages so a polling UI (e.g. a
Kivy ``Clock`` tick on the main thread) can render them without callbacks
crossing thread boundaries.
"""
from __future__ import annotations

import threading
from collections import deque
from typing import Deque, Dict, List, Optional

from pathlib import Path

from ..config import AppConfig
from ..daemon import EmergencyDaemon
from ..core.alert import Alert
from ..core.preset import PAYLOAD_CLASSES
from ..core.map_tiles import (
    PROVIDERS, RADIUS_OPTIONS, DEFAULT_PROVIDER, DISTANCE_UNITS, get_provider,
    estimate_tile_count, TileDownloader, haversine_km, format_distance,
)


class AppController:
    """Thin, testable bridge between a UI and the emergency daemon."""

    def __init__(self, storage_dir=None, display_name: str = "RetAlert",
                 max_feed: int = 200):
        self.config = AppConfig.resolve(storage_dir)
        self.config.ensure_dirs()
        self.daemon = EmergencyDaemon(self.config, display_name=display_name)
        self._feed: Deque[dict] = deque(maxlen=max_feed)
        self._feed_lock = threading.Lock()
        self._sent: Dict[str, Alert] = {}  # alert_id -> our outbound Alert
        self._started = False
        self.daemon.set_incoming_callback(self._on_incoming)

    # -- lifecycle ------------------------------------------------------

    @property
    def started(self) -> bool:
        return self._started

    def start(self) -> None:
        """Bring the daemon up (non-blocking; its flush loop is a daemon
        thread) and announce ourselves. Idempotent."""
        if self._started:
            return
        self.daemon.start()
        try:
            self.daemon.lxmf.announce()
        except Exception:
            pass
        self._started = True

    def stop(self) -> None:
        if not self._started:
            return
        self.daemon.stop()
        self._started = False

    # -- inbound feed ---------------------------------------------------

    def _on_incoming(self, msg) -> None:
        d = msg.as_dict() if hasattr(msg, "as_dict") else dict(msg)
        with self._feed_lock:
            self._feed.append(d)

    def feed(self) -> List[dict]:
        """Snapshot of recent inbound messages (newest last)."""
        with self._feed_lock:
            return list(self._feed)

    def clear_feed(self) -> None:
        with self._feed_lock:
            self._feed.clear()

    # -- actions --------------------------------------------------------

    def panic(self, trigger: str = "default") -> Optional[Alert]:
        """Fire a preset by name/trigger -> dispatched Alert (or None)."""
        alert = self.daemon.panic.fire(trigger)
        self._record_sent(alert)
        return alert

    def send_alert(self, text: str, recipients, severity: str = "help",
                   **kw) -> Alert:
        alert = Alert(severity=severity, text=text,
                      recipients=list(recipients), **kw)
        sent = self.daemon.send_alert(alert)
        self._record_sent(sent)
        return sent

    def _record_sent(self, alert: Optional[Alert]) -> None:
        if alert is not None and getattr(alert, "alert_id", ""):
            self._sent[alert.alert_id] = alert

    def sent_alerts(self) -> List[Alert]:
        """Our outbound alerts (newest first) for an outbox / ack view."""
        return list(reversed(list(self._sent.values())))

    def resolve_recipients(self, token: str) -> List[str]:
        """Resolve a token to destination hashes. Accepts a 32-hex-char hash
        (with or without colons), a group name, or a contact display name.
        Returns [] if nothing matches."""
        token = (token or "").strip()
        clean = token.replace(":", "").lower()
        try:
            bytes.fromhex(clean)
            if len(clean) == 32:
                return [clean]
        except ValueError:
            pass
        members = self.daemon.expand_group(token)
        if members:
            return list(members)
        for c in self.daemon.contacts.list():
            if c.name == token:
                return [c.hash]
        return []

    def send_text(self, text: str, target: str, severity: str = "help",
                  **kw) -> Alert:
        """Resolve ``target`` (hash / group / contact) and send an alert.
        Raises ValueError if the target resolves to no recipient."""
        recipients = self.resolve_recipients(target)
        if not recipients:
            raise ValueError(f"no recipient for {target!r}")
        return self.send_alert(text, recipients, severity=severity, **kw)

    def reply(self, alert_id: str, text: str) -> bool:
        return self.daemon.reply_to_alert(alert_id, text)

    def ack(self, alert_id: str) -> bool:
        return self.daemon.ack_alert(alert_id)

    # -- views ----------------------------------------------------------

    def presets(self):
        return self.daemon.preset_store.list()

    def preset_summaries(self) -> List[dict]:
        """Flat preset rows for a UI list: name, severity, fan-out, and how
        many recipients are configured."""
        return [{"name": p.name, "severity": p.severity, "fan_out": p.fan_out,
                 "recipients": len(p.recipients)}
                for p in self.daemon.preset_store.list()]

    def inbox(self):
        return self.daemon.inbox.list()

    def contacts(self):
        return self.daemon.contacts.list()

    def add_contact(self, hash_hex: str, name: str) -> None:
        self.daemon.contacts.add(hash_hex, name)

    def remove_contact(self, hash_hex: str) -> bool:
        return self.daemon.contacts.remove(hash_hex)

    def discovered(self):
        """Heard-announce peers (Columba-style discover list)."""
        return self.daemon.discover.list()

    # -- map / live tracks ---------------------------------------------

    def tracks(self):
        """Live-share peers (markers for the map)."""
        return self.daemon.tracks.list()

    # -- own location / follow / distance ------------------------------

    def own_fix(self):
        """Our current GPS fix (or None if location is off/unavailable)."""
        return self.daemon._get_current_fix()

    def update_own_location(self, lat: float, lon: float,
                            accuracy: Optional[float] = None) -> None:
        """Push a new own-position fix (platform GPS callback or manual)."""
        from ..core.geo_tracker import ManualFixSource
        self.daemon.set_fix_source(ManualFixSource(lat, lon, accuracy=accuracy))

    def follow(self, source_hash: str) -> bool:
        """Tap-to-follow a live-share peer; the map re-centers on them."""
        return self.daemon.tracks.follow(source_hash)

    def unfollow(self) -> None:
        self.daemon.tracks.unfollow()

    def followed(self) -> Optional[str]:
        return self.daemon.tracks.followed

    def followed_fix(self):
        t = self.daemon.tracks.followed_track()
        return t.fix if t is not None else None

    def distance_units(self) -> str:
        return self.daemon.settings.distance_units

    def set_distance_units(self, units: str) -> None:
        self.daemon.settings.set_distance_units(units)

    def distance_to_fix(self, fix) -> Optional[str]:
        """Formatted distance (km/mi per settings) from us to ``fix``; None if
        our own location is unknown."""
        own = self.own_fix()
        if own is None or fix is None:
            return None
        km = haversine_km(own.lat, own.lon, fix.lat, fix.lon)
        return format_distance(km, self.distance_units())

    def tracks_with_distance(self) -> List[dict]:
        """Peers with name, position, follow flag, and distance from us."""
        own = self.own_fix()
        units = self.distance_units()
        followed = self.daemon.tracks.followed
        rows = []
        for t in self.daemon.tracks.list():
            dist = None
            if own is not None:
                dist = format_distance(
                    haversine_km(own.lat, own.lon, t.fix.lat, t.fix.lon), units)
            rows.append({"hash": t.source_hash, "name": t.display_name,
                         "lat": t.fix.lat, "lon": t.fix.lon,
                         "distance": dist, "followed": t.source_hash == followed})
        return rows

    def map_providers(self) -> List[dict]:
        return [{"key": p.key, "name": p.name} for p in PROVIDERS.values()]

    def map_radius_options(self) -> List[int]:
        return list(RADIUS_OPTIONS)

    def maps_dir(self) -> Path:
        d = Path(self.config.storage_dir) / "maps"
        d.mkdir(parents=True, exist_ok=True)
        return d

    def offline_maps(self) -> List[str]:
        """Paths of downloaded .mbtiles offline maps."""
        return sorted(str(p) for p in self.maps_dir().glob("*.mbtiles"))

    def delete_offline_map(self, path: str) -> bool:
        """Delete a downloaded offline map. Guarded: only removes a .mbtiles
        file inside our maps dir."""
        p = Path(path)
        if (p.suffix == ".mbtiles" and p.parent == self.maps_dir()
                and p.is_file()):
            p.unlink()
            return True
        return False

    def estimate_offline_tiles(self, lat: float, lon: float,
                               radius_km: float, zooms=None) -> int:
        return estimate_tile_count(lat, lon, radius_km, zooms)

    def download_offline_map(self, lat: float, lon: float, radius_km: float,
                             provider: str = DEFAULT_PROVIDER, zooms=None,
                             progress=None, out_path: Optional[str] = None,
                             fetch=None) -> dict:
        """Download an offline map (MBTiles) around (lat, lon). ``fetch`` is
        injectable for testing; production uses the module's HTTP fetch."""
        prov = get_provider(provider)
        if out_path is None:
            name = f"{provider}_r{int(radius_km)}_{lat:.3f}_{lon:.3f}.mbtiles"
            out_path = str(self.maps_dir() / name)
        downloader = TileDownloader(prov, fetch=fetch)
        return downloader.download(lat, lon, radius_km, out_path, zooms=zooms,
                                   progress=progress)

    # -- incoming filter / settings ------------------------------------

    def settings_view(self) -> dict:
        s = self.daemon.settings
        return {"receive_only": s.receive_only_from_contacts,
                "allow": sorted(s.allowlist), "deny": sorted(s.denylist),
                "distance_units": s.distance_units}

    def set_receive_only(self, value: bool) -> None:
        self.daemon.settings.set_receive_only_from_contacts(bool(value))

    def allow(self, hash_hex: str) -> None:
        self.daemon.settings.allow(hash_hex)

    def deny(self, hash_hex: str) -> None:
        self.daemon.settings.deny(hash_hex)

    def forget(self, hash_hex: str) -> None:
        self.daemon.settings.forget(hash_hex)

    def ack_summary(self, alert_id: str) -> Dict[str, str]:
        return self.daemon.ack.summary(alert_id)

    def status(self) -> dict:
        """Interface classification + per-payload gating. ``ready`` is False
        (and the lists empty) until the daemon has been started."""
        if self.daemon.ti is None:
            return {"ready": False, "interfaces": [], "gating": {}}
        ifaces = [{"name": i.name, "cls": i.cls, "tier": i.tier}
                  for i in self.daemon.ti.classify_interfaces()]
        gating = {}
        for pc in PAYLOAD_CLASSES:
            ok, best = self.daemon.ti.gate_payload(pc)
            gating[pc] = {"ok": ok, "best": best}
        return {"ready": True, "interfaces": ifaces, "gating": gating}

    @property
    def delivery_hash(self) -> Optional[str]:
        if not self._started:
            return None
        return getattr(self.daemon, "delivery_hash_hex", None)
