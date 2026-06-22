"""AckTracker — per-recipient alert delivery/ack state.

Build step: 2 (implemented here). See PROMPT.md § Acknowledgement protocol.

Maps (alert_id, recipient_hash) -> RecipientState. LXMF delivery confirmation
counts as DELIVERED in step 1; app-level ACK/REPLY wiring arrives in build
step 9. Queries expose which recipients are still unacked so RetryQueue can
target only them.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional

from .alert import (
    Alert, SENT, DELIVERED, ACKED, REPLIED, FAILED,
    ACTIVE_STATES, DONE_STATES,
)


@dataclass
class RecipientState:
    state: str = SENT
    attempts: int = 0
    last_attempt: float = 0.0
    last_error: str = ""
    reply: str = ""

    def as_dict(self) -> dict:
        return {
            "state": self.state,
            "attempts": self.attempts,
            "last_attempt": self.last_attempt,
            "last_error": self.last_error,
            "reply": self.reply,
        }


class AckTracker:
    """In-memory per-recipient state for outgoing alerts.

    State is rebuilt from delivery callbacks on each run; persistence of the
    alerts themselves (for retry across restart) lives in RetryQueue.
    """

    def __init__(self):
        # alert_id -> {recipient_hex -> RecipientState}
        self._state: Dict[str, Dict[str, RecipientState]] = {}

    # -- registration ---------------------------------------------------

    def track(self, alert: Alert) -> None:
        """Register an alert and all its recipients as SENT."""
        per = self._state.setdefault(alert.alert_id, {})
        for r in alert.recipients:
            per.setdefault(r, RecipientState(state=SENT))

    def forget(self, alert_id: str) -> None:
        self._state.pop(alert_id, None)

    # -- transitions ----------------------------------------------------

    def _get(self, alert_id: str, recipient: str) -> Optional[RecipientState]:
        return self._state.get(alert_id, {}).get(recipient)

    def on_sent(self, alert_id: str, recipient: str) -> None:
        rs = self._get(alert_id, recipient)
        if rs:
            rs.state = SENT
            rs.last_attempt = time.time()
            rs.attempts += 1

    def on_delivered(self, alert_id: str, recipient: str) -> None:
        rs = self._get(alert_id, recipient)
        if rs and rs.state != ACKED and rs.state != REPLIED:
            rs.state = DELIVERED

    def on_failed(self, alert_id: str, recipient: str, error: str = "") -> None:
        rs = self._get(alert_id, recipient)
        if rs and rs.state not in (ACKED, REPLIED):
            rs.state = FAILED
            rs.last_error = error

    def on_ack(self, alert_id: str, recipient: str, reply: str = "") -> None:
        """App-level ack (build step 9 wires this from inbound messages)."""
        rs = self._get(alert_id, recipient)
        if not rs:
            return
        rs.state = REPLIED if reply else ACKED
        if reply:
            rs.reply = reply

    # -- queries --------------------------------------------------------

    def state(self, alert_id: str, recipient: str) -> Optional[str]:
        rs = self._get(alert_id, recipient)
        return rs.state if rs else None

    def unacked_recipients(self, alert_id: str) -> List[str]:
        """Recipients still worth retrying (SENT or DELIVERED, not acked/failed)."""
        per = self._state.get(alert_id, {})
        return [r for r, rs in per.items() if rs.state in ACTIVE_STATES]

    def failed_recipients(self, alert_id: str) -> List[str]:
        per = self._state.get(alert_id, {})
        return [r for r, rs in per.items() if rs.state == FAILED]

    def is_done(self, alert_id: str) -> bool:
        """True when no recipient is in an active (retryable) state."""
        per = self._state.get(alert_id, {})
        if not per:
            return True
        return all(rs.state in DONE_STATES for rs in per.values())

    def summary(self, alert_id: str) -> Dict[str, str]:
        per = self._state.get(alert_id, {})
        return {r: rs.state for r, rs in per.items()}