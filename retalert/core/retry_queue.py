"""RetryQueue — persistent unsent/unacked alerts, fast retry mid-emergency.

Build step: 2 (implemented here). See PROMPT.md § RetryQueue and
§ Transport intelligence & failover.

* Persists alerts to ``alerts.json`` so they survive restart.
* Fast retry: while an alert is unacked, re-send to unacked recipients every
  ``alert.retry_interval`` seconds (default 3s) — no long backoff during an
  active emergency. Backoff/resume-after-ack refinements come with
  TransportIntelligence (build step 4).
* ``max_attempts`` per alert caps retries (0 = unlimited).
* Done alerts (all recipients acked/failed) are dropped from the queue.
"""
from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Callable, Dict, List, Optional

from .alert import Alert
from .ack_tracker import AckTracker


class RetryQueue:
    """JSON-backed queue of alerts awaiting ack/delivery.

    ``send_fn(alert, recipient_hex)`` is the transport callback that actually
    transmits to one recipient (wired by EmergencyDaemon). It should update
    AckTracker on delivery/failure.
    """

    def __init__(self, path: Path, ack: AckTracker,
                 send_fn: Optional[Callable[[Alert, str], None]] = None):
        self.path = Path(path)
        self.ack = ack
        self.send_fn = send_fn
        # alert_id -> {"alert": Alert, "next_attempt": float}
        self._pending: Dict[str, dict] = {}
        self._load()

    # -- persistence ----------------------------------------------------

    def _load(self) -> None:
        if not self.path.exists():
            return
        try:
            data = json.loads(self.path.read_text("utf-8"))
        except (json.JSONDecodeError, OSError):
            return
        for entry in data.get("alerts", []):
            alert = Alert.from_dict(entry.get("alert", {}))
            self._pending[alert.alert_id] = {
                "alert": alert,
                "next_attempt": float(entry.get("next_attempt", 0.0)),
            }

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "alerts": [
                {"alert": p["alert"].as_dict(), "next_attempt": p["next_attempt"]}
                for p in self._pending.values()
            ]
        }
        self.path.write_text(json.dumps(payload, indent=2), "utf-8")

    # -- queue ops ------------------------------------------------------

    def enqueue(self, alert: Alert) -> None:
        """Add an alert (already tracked in AckTracker) and send immediately."""
        self.ack.track(alert)
        self._pending[alert.alert_id] = {
            "alert": alert,
            "next_attempt": time.time(),  # send now
        }
        self._save()
        self._send_to_unacked(alert)

    def remove(self, alert_id: str) -> None:
        if self._pending.pop(alert_id, None) is not None:
            self._save()

    def pending(self) -> List[Alert]:
        return [p["alert"] for p in self._pending.values()]

    # -- flush ----------------------------------------------------------

    def flush(self) -> int:
        """Retry due alerts. Returns number of send attempts made."""
        if not self._pending:
            return 0
        now = time.time()
        attempts = 0
        done_ids: List[str] = []
        for alert_id, p in list(self._pending.items()):
            alert: Alert = p["alert"]
            if self.ack.is_done(alert_id):
                done_ids.append(alert_id)
                continue
            if now < p["next_attempt"]:
                continue
            unacked = self.ack.unacked_recipients(alert_id)
            if not unacked:
                done_ids.append(alert_id)
                continue
            # Respect max_attempts (per recipient, tracked in AckTracker).
            for r in unacked:
                rs = self.ack._get(alert_id, r)  # noqa: SLF001 (internal coord)
                if alert.max_attempts and rs and rs.attempts >= alert.max_attempts:
                    self.ack.on_failed(alert_id, r, "max attempts reached")
                    continue
                self._send_one(alert, r)
                attempts += 1
            p["next_attempt"] = now + alert.retry_interval
        for aid in done_ids:
            self._pending.pop(aid, None)
        if done_ids or attempts:
            self._save()
        return attempts

    # -- internal -------------------------------------------------------

    def _send_to_unacked(self, alert: Alert) -> None:
        for r in self.ack.unacked_recipients(alert.alert_id):
            self._send_one(alert, r)

    def _send_one(self, alert: Alert, recipient: str) -> None:
        """Hand one recipient to the transport.

        ``LookupError`` from the transport means "no path/announce yet" — a
        retryable condition, not a failure: leave state SENT (attempt counted)
        and let the next flush try again. Other exceptions mark failed.
        """
        self.ack.on_sent(alert.alert_id, recipient)
        if not self.send_fn:
            return
        try:
            self.send_fn(alert, recipient)
        except LookupError:
            return  # retryable; state stays SENT
        except Exception as exc:
            self.ack.on_failed(alert.alert_id, recipient, str(exc))