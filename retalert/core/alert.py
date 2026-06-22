"""Alert model + delivery states.

An Alert is one outgoing emergency event, fanned out to one or more
recipients. Per-recipient delivery/ack state lives in AckTracker; the Alert
itself is the payload + recipient list + retry policy. See PROMPT.md
§ Acknowledgement protocol and § Transport intelligence & failover.
"""
from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field, asdict
from typing import Dict, List, Optional


# Per-recipient delivery states (ordered severity).
SENT = "sent"            # handed to transport, no confirmation yet
DELIVERED = "delivered"  # transport confirmed delivery (LXMF DELIVERED)
ACKED = "acked"          # app-level ack from recipient (build step 9)
REPLIED = "replied"      # recipient sent a canned reply (build step 9)
FAILED = "failed"        # transport gave up / no path

# Step 2: DELIVERED is terminal (transport confirmed). Build step 9 wires
# app-level ACK and will move DELIVERED back to ACTIVE so alerts retry until
# the recipient actually acknowledges.
ACTIVE_STATES = {SENT}
DONE_STATES = {DELIVERED, ACKED, REPLIED, FAILED}

SEVERITIES = ("help", "medical", "danger", "critical")


@dataclass
class Alert:
    alert_id: str = ""
    severity: str = "help"
    text: str = ""
    recipients: List[str] = field(default_factory=list)  # dest hash hex
    created_at: float = 0.0
    # payload toggles (filled by PresetResolver in build step 10)
    payload: Dict = field(default_factory=dict)
    # retry policy
    retry_interval: float = 3.0   # seconds between attempts while unacked
    max_attempts: int = 0         # 0 = unlimited (until ack/failed)
    # transport plan: "off" | "critical" (default) | "all" (Hail Mary fan-out)
    fan_out: str = "critical"

    def __post_init__(self):
        if not self.alert_id:
            self.alert_id = uuid.uuid4().hex
        if self.created_at == 0.0:
            self.created_at = time.time()
        if self.severity not in SEVERITIES:
            self.severity = "help"

    def as_dict(self) -> dict:
        return asdict(self)

    @classmethod
    def from_dict(cls, d: dict) -> "Alert":
        return cls(
            alert_id=d.get("alert_id") or uuid.uuid4().hex,
            severity=d.get("severity", "help"),
            text=d.get("text", ""),
            recipients=list(d.get("recipients", [])),
            created_at=float(d.get("created_at", 0.0)),
            payload=dict(d.get("payload", {})),
            retry_interval=float(d.get("retry_interval", 3.0)),
            max_attempts=int(d.get("max_attempts", 0)),
            fan_out=d.get("fan_out", "critical"),
        )