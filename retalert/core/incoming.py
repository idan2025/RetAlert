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

Wire format (plain text so it also lands in Sideband/Columba inboxes
readably):

  v0 (legacy, no app-ack):
    !RETALERT!<severity>!<text>
  v1 (with alert_id for app-level ack):
    !RETALERT!id:<alert_id>!<severity>!<text>

The ``id:`` prefix on the first segment disambiguates v1 from v0 (whose
first segment is the severity) even when the text body itself contains
``!``.

App-level ack (receiver -> sender, build step 9):
    !RETALERT!ack!<alert_id>
App-level reply (receiver -> sender, with optional text):
    !RETALERT!reply!<alert_id>!<text>
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
# v1 alert_id segment prefix (disambiguates from a v0 severity segment).
_ALERT_ID_PREFIX = "id:"
# Ack sub-marker: !RETALERT!ack!<alert_id>
_ACK_SEG = "ack"
# Reply sub-marker: !RETALERT!reply!<alert_id>!<text>
_REPLY_SEG = "reply"

_GEO_RE = re.compile(r"geo:(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)")


def encode_alert(severity: str, text: str, alert_id: str = "") -> str:
    """Wrap an outgoing alert body with the RetAlert marker.

    With ``alert_id`` (v1): ``!RETALERT!id:<alert_id>!<severity>!<text>``
    so the receiver can ack it. Without (v0 legacy): the receiver treats it
    as a plain alert with no app-level ack."""
    if alert_id:
        return f"{RETALERT_MARKER}{_ALERT_ID_PREFIX}{alert_id}!{severity}!{text}"
    return f"{RETALERT_MARKER}{severity}!{text}"


def decode_alert(body: str):
    """If ``body`` carries a RetAlert alert marker, return
    ``(alert_id, severity, text)`` (``alert_id`` is ``""`` for v0 legacy);
    else ``None``. Ack messages are NOT alerts — see decode_ack."""
    if not body.startswith(RETALERT_MARKER):
        return None
    rest = body[len(RETALERT_MARKER):]
    first, _, tail = rest.partition("!")
    if not first:
        return None
    # v1: first segment is the alert_id.
    if first.startswith(_ALERT_ID_PREFIX):
        alert_id = first[len(_ALERT_ID_PREFIX):]
        sev, _, text = tail.partition("!")
        if not sev:
            return None
        return alert_id, sev, text
    # v0 legacy: first segment is the severity.
    return "", first, tail


def encode_ack(alert_id: str) -> str:
    """Wire an app-level ack back to the alert's sender."""
    return f"{RETALERT_MARKER}{_ACK_SEG}!{alert_id}"


def decode_ack(body: str) -> Optional[str]:
    """If ``body`` is an ack, return its ``alert_id``; else ``None``."""
    if not body.startswith(RETALERT_MARKER):
        return None
    rest = body[len(RETALERT_MARKER):]
    seg, _, alert_id = rest.partition("!")
    if seg != _ACK_SEG or not alert_id:
        return None
    return alert_id


def encode_reply(alert_id: str, text: str = "") -> str:
    """Wire an app-level reply (ack + optional text) back to the sender."""
    return f"{RETALERT_MARKER}{_REPLY_SEG}!{alert_id}!{text}"


def decode_reply(body: str):
    """If ``body`` is a reply, return ``(alert_id, text)``; else ``None``."""
    if not body.startswith(RETALERT_MARKER):
        return None
    rest = body[len(RETALERT_MARKER):]
    seg, _, tail = rest.partition("!")
    if seg != _REPLY_SEG:
        return None
    alert_id, _, text = tail.partition("!")
    if not alert_id:
        return None
    return alert_id, text


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
    kind: str = "text"          # text | geo | alert | ack
    severity: str = ""          # set for kind == "alert"
    alert_id: str = ""          # set for kind == "alert" (v1) and "ack"
    fix: Optional[Fix] = None   # set for kind == "geo" / alert with location

    def as_dict(self) -> dict:
        return {
            "source_hash": self.source_hash,
            "text": self.text,
            "timestamp": self.timestamp,
            "kind": self.kind,
            "severity": self.severity,
            "alert_id": self.alert_id,
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
                 on_message: Optional[Callable[[IncomingMessage], None]] = None,
                 send_ack_fn: Optional[Callable[[str, str], None]] = None,
                 ack_cb: Optional[Callable[[str, str], None]] = None,
                 reply_cb: Optional[Callable[[str, str, str], None]] = None):
        self.settings = settings
        self.contacts = contacts
        self.discover = discover
        self.tracks = tracks or LiveTrackStore()
        self.on_message = on_message
        # Platform hook: called with the IncomingMessage for app-to-app
        # alerts so the receiver can bypass silent/DND. Stub logs only.
        self.bypass_silent_cb: Optional[Callable[[IncomingMessage], None]] = None
        # App-level ack/reply (step 9):
        #   send_ack_fn(alert_id, source_hex)        -> ack back to sender
        #   ack_cb(alert_id, source_hex)             -> AckTracker.on_ack (ACKED)
        #   reply_cb(alert_id, source_hex, reply)    -> AckTracker.on_ack (REPLIED)
        self.send_ack_fn = send_ack_fn
        self.ack_cb = ack_cb
        self.reply_cb = reply_cb

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

        # App-level ack/reply: hand to the tracker; no geo/bypass-silent.
        if msg.kind == "ack":
            if self.ack_cb is not None:
                try:
                    self.ack_cb(msg.alert_id, src)
                except Exception:
                    log.exception("ack callback raised")
        elif msg.kind == "reply":
            if self.reply_cb is not None:
                try:
                    self.reply_cb(msg.alert_id, src, msg.text)
                except Exception:
                    log.exception("reply callback raised")
        else:
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
                # Ack the alert back to its sender (v1 only).
                if msg.alert_id and self.send_ack_fn is not None:
                    try:
                        self.send_ack_fn(msg.alert_id, src)
                    except Exception:
                        log.exception("send_ack callback raised")

        if self.on_message is not None:
            try:
                self.on_message(msg)
            except Exception:
                log.exception("on_message callback raised")
        return msg

    def _parse(self, src: str, text: str, timestamp: float) -> IncomingMessage:
        # App-level ack? (check before alert — ack also carries the marker)
        ack_id = decode_ack(text or "")
        if ack_id is not None:
            return IncomingMessage(source_hash=src, text=text, timestamp=timestamp,
                                   kind="ack", alert_id=ack_id)
        # App-level reply? (check before alert — reply also carries the marker)
        rep = decode_reply(text or "")
        if rep is not None:
            rid, rtext = rep
            return IncomingMessage(source_hash=src, text=rtext, timestamp=timestamp,
                                   kind="reply", alert_id=rid)
        # App-to-app alert marker?
        decoded = decode_alert(text or "")
        if decoded is not None:
            alert_id, severity, body = decoded
            fix = parse_geo_body(body)  # alerts may carry a location too
            return IncomingMessage(source_hash=src, text=body, timestamp=timestamp,
                                   kind="alert", severity=severity,
                                   alert_id=alert_id, fix=fix)
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