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

    # -- incoming filter / settings ------------------------------------

    def settings_view(self) -> dict:
        s = self.daemon.settings
        return {"receive_only": s.receive_only_from_contacts,
                "allow": sorted(s.allowlist), "deny": sorted(s.denylist)}

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
