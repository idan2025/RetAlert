"""Discover — cache of announces heard on the mesh (Columba-style network list).

Build step: 6. See PROMPT.md § Recipients & contacts. The discover/network
screen lists announced destinations heard over RNS; users star them or
(long-press) add them to contacts.

RNS delivers announces via ``Transport.register_announce_handler(handler)``
where ``handler`` is any object with an ``aspect_filter`` attribute and a
``received_announce(destination_hash, announced_identity, app_data, ...)``
callable. ``AnnounceHandler`` below is that object; it forwards into a
``Discover`` cache.

LXMF delivery announces pack their app_data with msgpack as
``[display_name_bytes, stamp_cost, supported_functionality]`` (see
``LXMRouter.get_announce_app_data``). We decode the display name best-effort
and fall back to an empty string for non-LXMF or malformed announces.
"""
from __future__ import annotations

import json
import threading
import time
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import List, Optional

import RNS
from RNS.vendor import umsgpack


ASPECT_LXMF_DELIVERY = "lxmf.delivery"


@dataclass
class DiscoveredPeer:
    """One announced destination heard on the mesh."""
    hash: str                  # destination hash, hex, no colons
    display_name: str = ""
    aspect: str = ""
    app_name: str = ""         # first aspect segment, e.g. "lxmf"
    last_heard: float = 0.0
    first_heard: float = 0.0
    starred: bool = False

    def as_dict(self) -> dict:
        return asdict(self)


def _decode_display_name(app_data, aspect: str) -> str:
    """Best-effort decode of an LXMF delivery announce's display name."""
    if not app_data:
        return ""
    if aspect != ASPECT_LXMF_DELIVERY:
        # Non-LXMF announce; app_data format is app-defined. Leave name blank.
        return ""
    try:
        data = umsgpack.unpackb(app_data)
        if isinstance(data, list) and data:
            name_bytes = data[0]
            if isinstance(name_bytes, (bytes, bytearray)):
                return name_bytes.decode("utf-8", errors="replace")
            if isinstance(name_bytes, str):
                return name_bytes
    except Exception:
        pass
    return ""


def _app_name_from_aspect(aspect: str) -> str:
    return aspect.split(".", 1)[0] if aspect else ""


class AnnounceHandler:
    """RNS announce handler shim. ``aspect_filter`` selects which announces we
    hear; ``None`` hears all. Forwards into the ``Discover`` cache."""

    def __init__(self, discover: "Discover", aspect_filter: Optional[str] = None,
                 receive_path_responses: bool = False):
        self.discover = discover
        self.aspect_filter = aspect_filter
        self.receive_path_responses = receive_path_responses

    def received_announce(self, destination_hash, announced_identity, app_data,
                          announce_packet_hash=None, is_path_response=False):
        try:
            aspect = getattr(announced_identity, "aspect", None) or ""
            # RNS Identity doesn't expose aspect directly; derive from the
            # announce path is non-trivial, so for LXMF we use the known aspect.
            if not aspect:
                aspect = self.aspect_filter or ""
            self.discover.heard(destination_hash, app_data, aspect=aspect)
        except Exception:
            # Never let a malformed announce break the RNS transport thread.
            pass


class Discover:
    """In-memory cache of heard announces + a persisted starred set.

    The heard cache is ephemeral (cleared by ``clear()`` / on restart). The
    starred set is persisted so starred peers re-show their star when heard
    again — matching Columba's discover list behaviour.
    """

    def __init__(self, starred_path: Optional[Path] = None):
        self._peers: dict[str, DiscoveredPeer] = {}
        self._lock = threading.RLock()
        self._starred_path = Path(starred_path) if starred_path else None
        self._starred: set[str] = set()
        self._load_starred()

    # -- persistence ----------------------------------------------------

    def _load_starred(self) -> None:
        if self._starred_path is None or not self._starred_path.exists():
            return
        try:
            data = json.loads(self._starred_path.read_text("utf-8"))
            self._starred = {h.lower().strip() for h in data.get("starred", [])}
        except (json.JSONDecodeError, OSError):
            self._starred = set()

    def _save_starred(self) -> None:
        if self._starred_path is None:
            return
        self._starred_path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"starred": sorted(self._starred)}
        self._starred_path.write_text(json.dumps(payload, indent=2), "utf-8")

    # -- ingest ---------------------------------------------------------

    def heard(self, destination_hash: bytes, app_data,
              aspect: str = ASPECT_LXMF_DELIVERY) -> DiscoveredPeer:
        """Insert/refresh a heard announce. Returns the stored peer."""
        hash_hex = RNS.hexrep(destination_hash, delimit=False).lower()
        name = _decode_display_name(app_data, aspect)
        now = time.time()
        with self._lock:
            peer = self._peers.get(hash_hex)
            if peer is None:
                peer = DiscoveredPeer(
                    hash=hash_hex, display_name=name, aspect=aspect,
                    app_name=_app_name_from_aspect(aspect),
                    last_heard=now, first_heard=now,
                    starred=hash_hex in self._starred,
                )
                self._peers[hash_hex] = peer
            else:
                peer.display_name = name or peer.display_name
                peer.aspect = aspect or peer.aspect
                peer.app_name = _app_name_from_aspect(aspect) or peer.app_name
                peer.last_heard = now
                peer.starred = hash_hex in self._starred
            return peer

    # -- query ----------------------------------------------------------

    def list(self) -> List[DiscoveredPeer]:
        """Heard peers, freshest first."""
        with self._lock:
            peers = list(self._peers.values())
        peers.sort(key=lambda p: p.last_heard, reverse=True)
        return peers

    def get(self, hash_hex: str) -> Optional[DiscoveredPeer]:
        hash_hex = hash_hex.lower().strip()
        with self._lock:
            return self._peers.get(hash_hex)

    def starred_hashes(self) -> List[str]:
        with self._lock:
            return sorted(self._starred)

    # -- mutate ---------------------------------------------------------

    def star(self, hash_hex: str) -> bool:
        hash_hex = hash_hex.lower().strip()
        with self._lock:
            self._starred.add(hash_hex)
            peer = self._peers.get(hash_hex)
            if peer is not None:
                peer.starred = True
            self._save_starred()
            return peer is not None

    def unstar(self, hash_hex: str) -> bool:
        hash_hex = hash_hex.lower().strip()
        with self._lock:
            existed = hash_hex in self._starred
            self._starred.discard(hash_hex)
            peer = self._peers.get(hash_hex)
            if peer is not None:
                peer.starred = False
            self._save_starred()
            return existed

    def clear(self) -> int:
        """Clear the heard cache (discover list). Starred set persists so
        peers re-show their star when heard again. Returns count dropped."""
        with self._lock:
            count = len(self._peers)
            self._peers.clear()
            return count

    def remove(self, hash_hex: str) -> bool:
        hash_hex = hash_hex.lower().strip()
        with self._lock:
            existed = hash_hex in self._peers
            self._peers.pop(hash_hex, None)
            return existed