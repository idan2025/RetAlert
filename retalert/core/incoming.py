"""IncomingDispatcher — receive-side filtering + parsing of LXMF messages.

Build steps: 8 (map backend: geo parsing) + 9 (incoming filter +
bypass-silent hook). See PROMPT.md § Incoming alerts and § Map screen.

Responsibilities:
  * Apply the "receive only from contacts" toggle + per-sender allow/deny.
  * Parse message bodies into typed IncomingMessage objects:
      - ``geo``  : a live-share GPS fix (``geo:lat,lon`` + acc/alt/src suffix)
      - ``alert``: a RetAlert app-to-app emergency (``!RETALERT!<sev>!<text>``)
      - ``text`` : a plain LXMF message (casual, no bypass-silent)
  * Feed geo fixes into the LiveTrackStore for the Map screen.
  * Fire the bypass-silent hook for app-to-app alerts so a real emergency
    alarms at full volume on a muted phone (platform callback; stub here).

Wire format (v0 POC, plain text so it also lands in Sideband/Columba
inboxes readably):
    !RETALERT!<severity>!<text>
"""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from typing import Callable, Optional

from .geo_tracker import Fix
from .live_tracks import LiveTrackStore

log = logging.getLogger("retalert.incoming")

# Marker distinguishing RetAlert app-to-app alerts from casual LXMF text.
RETALERT_MARKER = "!RETALERT!"

_GEO_RE = re.compile(r"geo:(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)")


def encode_alert(severity: str, text: str) -> str:
    """Wrap an outgoing alert body with the RetAlert marker + severity."""
    return f"{RETALERT_MARKER}{severity}!{text}"


def decode_alert(body: str):
    """If ``body`` carries a RetAlert alert marker, return
    ``(severity, text)``; else ``None``."""
    if not body.startswith(RETALERT_MARKER):
        return None
    rest = body[len(RETALERT_MARKER):]
    sev, _, text = rest.partition("!")
    if not sev:
        return None
    return sev, text


def parse_geo_body(body: str) -> Optional[Fix]:
    """Parse a live-share message body into a Fix, or None if not geo.

    Daemon sends: ``geo:lat,lon acc=.. alt=.. src=..``
    """
    m = _GEO_RE.search(body)
    if not m:
        return None
    lat = float(m.group(1))
    lon = float(m.group(2))

    def _suffix(key: str):
        mm = re.search(rf"\b{key}=([^\s]+)", body)
        return mm.group(1) if mm else None

    acc = _suffix("acc")
    alt = _suffix("alt")
    src = _suffix("src") or "lxmf"
    accuracy = float(acc) if acc is not None else None
    altitude = float(alt) if alt is not None else None
    return Fix(lat=lat, lon=lon, accuracy=accuracy, altitude=altitude,
               source=src)


@dataclass
class IncomingMessage:
    """A received, filtered, parsed inbound message."""
    source_hash: str
    text: str
    timestamp: float
    kind: str = "text"          # text | geo | alert
    severity: str = ""          # set for kind == "alert"
    fix: Optional[Fix] = None   # set for kind == "geo" / alert with location

    def as_dict(self) -> dict:
        return {
            "source_hash": self.source_hash,
            "text": self.text,
            "timestamp": self.timestamp,
            "kind": self.kind,
            "severity": self.severity,
            "fix": self.fix.as_dict() if self.fix else None,
        }


class IncomingDispatcher:
    """Filter + parse inbound LXMF messages.

    ``settings`` provides ``receive_only_from_contacts`` and the allow/deny
    sets; ``contacts`` and ``discover`` resolve whether a sender is known.
    Parsed geo/alert messages are handed to ``on_message`` (wired by the
    daemon/UI) and geo fixes also update the LiveTrackStore.
    """

    def __init__(self, settings, contacts, discover=None,
                 tracks: Optional[LiveTrackStore] = None,
                 on_message: Optional[Callable[[IncomingMessage], None]] = None):
        self.settings = settings
        self.contacts = contacts
        self.discover = discover
        self.tracks = tracks or LiveTrackStore()
        self.on_message = on_message
        # Platform hook: called with the IncomingMessage for app-to-app
        # alerts so the receiver can bypass silent/DND. Stub logs only.
        self.bypass_silent_cb: Optional[Callable[[IncomingMessage], None]] = None

    # -- filter ---------------------------------------------------------

    def _is_allowed(self, source_hash: str) -> bool:
        src = source_hash.lower().strip()
        if src in self.settings.denylist:
            return False
        if src in self.settings.allowlist:
            return True
        if self.settings.receive_only_from_contacts:
            return self.contacts.get(src) is not None
        return True  # open mode: accept any announced sender

    # -- dispatch -------------------------------------------------------

    def handle(self, source_hex: str, text: str, timestamp: float) -> Optional[IncomingMessage]:
        """Filter + parse one inbound message. Returns the parsed message, or
        None if it was dropped by the filter."""
        src = source_hex.lower().strip()
        if not self._is_allowed(src):
            log.info("dropped inbound from %s (filtered)", src)
            return None

        msg = self._parse(src, text, timestamp)

        # Geo updates the live-track store regardless of alert/text kind.
        if msg.fix is not None:
            name = ""
            if self.discover is not None:
                peer = self.discover.get(src)
                if peer is not None:
                    name = peer.display_name
            self.tracks.update(src, msg.fix, display_name=name)

        if msg.kind == "alert":
            self._fire_bypass_silent(msg)

        if self.on_message is not None:
            try:
                self.on_message(msg)
            except Exception:
                log.exception("on_message callback raised")
        return msg

    def _parse(self, src: str, text: str, timestamp: float) -> IncomingMessage:
        # App-to-app alert marker?
        decoded = decode_alert(text or "")
        if decoded is not None:
            severity, body = decoded
            fix = parse_geo_body(body)  # alerts may carry a location too
            return IncomingMessage(source_hash=src, text=body, timestamp=timestamp,
                                   kind="alert", severity=severity, fix=fix)
        # Live-share geo fix?
        fix = parse_geo_body(text or "")
        if fix is not None:
            return IncomingMessage(source_hash=src, text=text, timestamp=timestamp,
                                   kind="geo", fix=fix)
        # Plain casual message.
        return IncomingMessage(source_hash=src, text=text or "",
                               timestamp=timestamp, kind="text")

    def _fire_bypass_silent(self, msg: IncomingMessage) -> None:
        if self.bypass_silent_cb is not None:
            try:
                self.bypass_silent_cb(msg)
            except Exception:
                log.exception("bypass_silent callback raised")
        else:
            log.warning("app-to-app alert from %s (bypass-silent not wired)",
                        msg.source_hash)