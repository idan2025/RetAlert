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

from ..config import AppConfig
from ..daemon import EmergencyDaemon
from ..core.alert import Alert
from ..core.preset import PAYLOAD_CLASSES


class AppController:
    """Thin, testable bridge between a UI and the emergency daemon."""

    def __init__(self, storage_dir=None, display_name: str = "RetAlert",
                 max_feed: int = 200):
        self.config = AppConfig.resolve(storage_dir)
        self.config.ensure_dirs()
        self.daemon = EmergencyDaemon(self.config, display_name=display_name)
        self._feed: Deque[dict] = deque(maxlen=max_feed)
        self._feed_lock = threading.Lock()
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
        return self.daemon.panic.fire(trigger)

    def send_alert(self, text: str, recipients, severity: str = "help",
                   **kw) -> Alert:
        alert = Alert(severity=severity, text=text,
                      recipients=list(recipients), **kw)
        return self.daemon.send_alert(alert)

    def reply(self, alert_id: str, text: str) -> bool:
        return self.daemon.reply_to_alert(alert_id, text)

    def ack(self, alert_id: str) -> bool:
        return self.daemon.ack_alert(alert_id)

    # -- views ----------------------------------------------------------

    def presets(self):
        return self.daemon.preset_store.list()

    def inbox(self):
        return self.daemon.inbox.list()

    def contacts(self):
        return self.daemon.contacts.list()

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
