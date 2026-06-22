"""TransportIntelligence — interface classification, tier policy, ranking,
fan-out and failover planning.

Build steps: 3-4 (implemented here). See PROMPT.md § Transport intelligence &
failover (the dedicated section). This is the app's "smart" transport brain:

* Classify active RNS interfaces by type -> bandwidth tier
  (WAN/LAN/internet = High, AutoInterface/UDP = Medium, LoRa/radio = Low,
  unknown -> Low safe default). User can override an interface's tier.
* Per-payload tier policy: text/ack/GPS-one-shot = any; GPS-live = Medium+;
  photo = High (degrade on Medium); audio = High only. A conversation/media
  channel is never opened over a Low interface.
* Rank usable interfaces per recipient (tier compat, then bandwidth, then
  online/path). Path-per-interface is approximated globally via
  RNS.Transport.has_path for now; per-interface path tracking arrives with
  the failover wiring.
* Delivery plan: "Hail Mary" fan-out (parallel on every compatible interface,
  deduped by alert_id) for critical/danger/medical (default-on, user-toggle to
  all/off); sequential fast failover otherwise.

Actual interface-pinned parallel send uses RNS.Packet(attached_interface=...);
the LXMF send path is wired to it in the fan-out integration step. The plan
returned here is what the orchestrator acts on.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

# -- tiers -------------------------------------------------------------
HIGH = "high"
MEDIUM = "medium"
LOW = "low"
TIER_RANK = {HIGH: 3, MEDIUM: 2, LOW: 1}

# RNS interface class name -> bandwidth tier.
CLASS_TIER: Dict[str, str] = {
    "LocalServerInterface": HIGH,
    "LocalClientInterface": HIGH,
    "TCPClientInterface": HIGH,
    "TCPServerInterface": HIGH,
    "I2PInterface": HIGH,
    "BackboneInterface": HIGH,
    "BackboneClientInterface": HIGH,
    "AutoInterface": MEDIUM,
    "UDPInterface": MEDIUM,
    "RNodeInterface": LOW,
    "KISSInterface": LOW,
    "AX25KISSInterface": LOW,
    "SerialInterface": LOW,
}

# Payload class -> minimum tier required to carry it.
PAYLOAD_MIN_TIER: Dict[str, str] = {
    "text": LOW,
    "ack": LOW,
    "gps_oneshot": LOW,
    "gps_live": MEDIUM,
    "photo": HIGH,
    "audio": HIGH,
}

CRITICAL_SEVERITIES = ("critical", "danger", "medical")
FAN_OUT_OFF = "off"
FAN_OUT_CRITICAL = "critical"   # default: Hail Mary only for critical severities
FAN_OUT_ALL = "all"             # Hail Mary for every alert


@dataclass
class IfaceInfo:
    """A classified interface."""

    interface: object
    name: str
    cls: str
    tier: str
    online: bool
    out_capable: bool
    has_path: bool = True  # path to the recipient in question (set per rank call)
    user_override: Optional[str] = None

    @property
    def rank_value(self) -> int:
        return TIER_RANK.get(self.tier, 0)


@dataclass
class DeliveryPlan:
    """How an alert should be sent."""

    mode: str  # "parallel" (Hail Mary) or "sequential" (fast failover)
    interfaces: List[IfaceInfo] = field(default_factory=list)
    payload_classes: List[str] = field(default_factory=list)
    min_tier: str = LOW
    queued: bool = False  # no qualifying interface up -> queue for later
    reason: str = ""


class TransportIntelligence:
    """Classifies interfaces, enforces per-payload policy, ranks interfaces per
    recipient, and produces a delivery plan (fan-out vs sequential failover)."""

    def __init__(self, reticulum=None, overrides: Optional[Dict[str, str]] = None):
        self.reticulum = reticulum
        self.overrides = dict(overrides or {})

    # -- classification -------------------------------------------------

    def tier_of(self, iface) -> str:
        """Tier for one interface, honoring user overrides by name."""
        name = getattr(iface, "name", None) or type(iface).__name__
        if name in self.overrides:
            return self.overrides[name]
        cls = type(iface).__name__
        if cls in self.overrides:
            return self.overrides[cls]
        return CLASS_TIER.get(cls, LOW)  # unknown -> Low (safe default)

    def classify_interfaces(self) -> List[IfaceInfo]:
        """Classify all current RNS interfaces."""
        if self.reticulum is None:
            return []
        import RNS
        out: List[IfaceInfo] = []
        for iface in RNS.Transport.interfaces:
            cls = type(iface).__name__
            name = getattr(iface, "name", None) or cls
            tier = self.tier_of(iface)
            online = bool(getattr(iface, "online", False))
            out_cap = bool(getattr(iface, "OUT", False)) or bool(getattr(iface, "IN", False))
            out.append(IfaceInfo(
                interface=iface, name=name, cls=cls, tier=tier,
                online=online, out_capable=out_cap,
                user_override=self.overrides.get(name),
            ))
        return out

    def up_interfaces(self) -> List[IfaceInfo]:
        return [i for i in self.classify_interfaces() if i.online and i.out_capable]

    # -- policy ---------------------------------------------------------

    @staticmethod
    def min_tier_for(payload_class: str) -> str:
        return PAYLOAD_MIN_TIER.get(payload_class, LOW)

    @staticmethod
    def allowed(payload_class: str, tier: str) -> bool:
        """True if ``tier`` can carry ``payload_class``."""
        return TIER_RANK.get(tier, 0) >= TIER_RANK[TransportIntelligence.min_tier_for(payload_class)]

    def required_tier(self, payload_classes: List[str]) -> str:
        """Highest min-tier among the payload classes (the binding constraint)."""
        if not payload_classes:
            return LOW
        return max((self.min_tier_for(pc) for pc in payload_classes),
                   key=lambda t: TIER_RANK[t])

    def gate_payload(self, payload_class: str) -> Tuple[bool, Optional[str]]:
        """Given currently-up interfaces, can ``payload_class`` be sent now?

        Returns (allowed, best_available_tier).
        """
        tiers = [i.tier for i in self.up_interfaces()]
        if not tiers:
            return False, None
        best = max(tiers, key=lambda t: TIER_RANK[t])
        return self.allowed(payload_class, best), best

    # -- ranking --------------------------------------------------------

    def rank_for(self, recipient_hash: Optional[bytes],
                 payload_class: str) -> List[IfaceInfo]:
        """Ranked usable interfaces for a recipient + payload class.

        Filters by tier compatibility and online/out, then sorts by tier
        (High > Medium > Low). ``recipient_hash`` path status is marked via
        RNS.Transport.has_path (global); per-interface path tracking arrives
        with the failover wiring.
        """
        import RNS
        min_tier = self.min_tier_for(payload_class)
        has_path = True
        if recipient_hash is not None:
            try:
                has_path = RNS.Transport.has_path(recipient_hash)
            except Exception:
                has_path = False
        usable = [
            i for i in self.up_interfaces()
            if TIER_RANK[i.tier] >= TIER_RANK[min_tier]
        ]
        for i in usable:
            i.has_path = has_path
        usable.sort(key=lambda i: (-i.rank_value, i.name))
        return usable

    # -- delivery plan --------------------------------------------------

    def delivery_plan(self, alert, fan_out: str = FAN_OUT_CRITICAL) -> DeliveryPlan:
        """Produce a delivery plan for an alert.

        ``alert`` needs ``severity``, ``recipients`` (hex), and ``payload``
        (dict of payload classes). ``fan_out``: off | critical | all.
        """
        payload_classes = list(getattr(alert, "payload", {}).keys()) or ["text"]
        required = self.required_tier(payload_classes)
        recipient_hex = alert.recipients[0] if alert.recipients else None
        recipient_hash = bytes.fromhex(recipient_hex.replace(":", "")) if recipient_hex else None

        ranked = self.rank_for(recipient_hash, max(payload_classes, key=lambda pc: TIER_RANK[self.min_tier_for(pc)]))

        # Hail Mary fan-out: only for minimal payloads (text/ack/gps_oneshot).
        # Heavy payloads (photo/audio) are never fanned across Low/Medium.
        is_critical = getattr(alert, "severity", "help") in CRITICAL_SEVERITIES
        do_fanout = (fan_out == FAN_OUT_ALL) or (fan_out == FAN_OUT_CRITICAL and is_critical)
        minimal = all(self.min_tier_for(pc) == LOW for pc in payload_classes)

        if do_fanout and minimal:
            # Parallel on every up interface (compat with LOW = all up).
            ifaces = self.up_interfaces()
            ifaces.sort(key=lambda i: (-i.rank_value, i.name))
            return DeliveryPlan(mode="parallel", interfaces=ifaces,
                                payload_classes=payload_classes, min_tier=LOW)

        # Sequential failover on the ranked, tier-compatible interfaces.
        if not ranked:
            return DeliveryPlan(mode="sequential", interfaces=[],
                                payload_classes=payload_classes, min_tier=required,
                                queued=True, reason=f"no interface up at tier {required}")
        return DeliveryPlan(mode="sequential", interfaces=ranked,
                            payload_classes=payload_classes, min_tier=required)