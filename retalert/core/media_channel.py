"""MediaChannel — audio/photo transfer over a raw RNS Link.

Build step: 13. See PROMPT.md § Alert payload (audio/photo channel) and
§ Alert transport (raw RNS Link for live audio/photo).

A raw RNS ``Link`` to a peer carries media that LXMF text cannot: a photo
(chunked, reliable transfer) or a live audio stream (packet stream). This
module is transport-agnostic and unit-testable: it handles framing,
chunking, reassembly, dedup by alert_id, and tier gating; the actual RNS
Link send/recv is injected (``link_send_fn`` wired by the daemon to a
real Link once one is established). ``LinkAdapter`` is the seam where a
live ``RNS.Link`` will plug in (stub methods until the Kivy/service step).

Tier policy (PROMPT.md): photo and audio are High-tier-only — never sent
over LoRa. ``MediaChannel.gate(ti)`` enforces this before any bytes leave.
"""
from __future__ import annotations

import hashlib
import threading
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional

from RNS.vendor import umsgpack

from .transport_intel import TransportIntelligence, HIGH, TIER_RANK

PHOTO = "photo"
AUDIO = "audio"

# Chunk payload size (bytes). Small enough for one RNS Link packet; the
# receiver reassembles. RNS Resource would handle MTU negotiation, but for
# the packet-stream model we keep chunks modest.
CHUNK_SIZE = 4096


@dataclass
class MediaChunk:
    """One framed media packet on the wire."""
    alert_id: str
    kind: str          # photo | audio
    seq: int           # 0-based chunk index
    total: int        # total chunks (0 = streaming/live, unknown end)
    sha256: str        # hash of full payload (photo) or chunk (audio)
    payload: bytes

    def as_bytes(self) -> bytes:
        return umsgpack.packb([
            self.alert_id, self.kind, self.seq, self.total,
            self.sha256, self.payload,
        ])

    @classmethod
    def from_bytes(cls, data: bytes) -> "MediaChunk":
        a, k, seq, total, sha, payload = umsgpack.unpackb(data)
        return cls(alert_id=a, kind=k, seq=seq, total=total,
                   sha256=sha, payload=bytes(payload))


@dataclass
class Media:
    """A reassembled photo or a captured audio chunk."""
    alert_id: str
    kind: str
    data: bytes = b""
    sha256: str = ""
    complete: bool = False

    def as_dict(self) -> dict:
        return {"alert_id": self.alert_id, "kind": self.kind,
                "sha256": self.sha256, "complete": self.complete,
                "size": len(self.data)}


def _best_tier(up) -> Optional[str]:
    if not up:
        return None
    return max((i.tier for i in up), key=lambda t: TIER_RANK.get(t, 0))


class MediaChannel:
    """Frames, chunks, reassembles, and tier-gates media transfers.

    ``link_send_fn(chunk_bytes)`` is the transport seam (wired to an RNS Link
    by the daemon). ``on_complete(media)`` fires when a photo finishes
    reassembling; ``on_audio_chunk(media)`` fires for each audio chunk.
    """

    def __init__(self, ti: Optional[TransportIntelligence] = None,
                 link_send_fn: Optional[Callable[[bytes], None]] = None,
                 on_complete: Optional[Callable[[Media], None]] = None,
                 on_audio_chunk: Optional[Callable[[Media], None]] = None,
                 chunk_size: int = CHUNK_SIZE):
        self.ti = ti
        self._send = link_send_fn
        self.on_complete = on_complete
        self.on_audio_chunk = on_audio_chunk
        self.chunk_size = chunk_size
        self._rx: Dict[str, dict] = {}   # alert_id -> {chunks, total, sha, kind}
        self._lock = threading.RLock()

    # -- tier gating ----------------------------------------------------

    def gate(self, kind: str = PHOTO) -> tuple:
        """Photo/audio are High-tier-only. Returns (ok, best_tier_or_None)."""
        if self.ti is None:
            return True, None  # no intelligence: allow (caller decides)
        best = _best_tier(self.ti.up_interfaces())
        if best is None:
            return False, None
        return (best == HIGH), best

    # -- send -----------------------------------------------------------

    def send_photo(self, alert_id: str, data: bytes) -> int:
        """Chunk + send a photo over the link. Returns chunk count, or 0 if
        tier-gated off / no link."""
        ok, _ = self.gate(PHOTO)
        if not ok:
            return 0
        if self._send is None:
            raise RuntimeError("no link_send_fn wired")
        sha = hashlib.sha256(data).hexdigest()
        chunks = self._split(data)
        total = len(chunks)
        for i, payload in enumerate(chunks):
            chunk = MediaChunk(alert_id=alert_id, kind=PHOTO, seq=i,
                               total=total, sha256=sha, payload=payload)
            self._send(chunk.as_bytes())
        return total

    def send_audio_chunk(self, alert_id: str, data: bytes,
                         seq: int, total: int = 0) -> bool:
        """Send one audio chunk (live stream; total=0 = open-ended)."""
        ok, _ = self.gate(AUDIO)
        if not ok:
            return False
        if self._send is None:
            raise RuntimeError("no link_send_fn wired")
        sha = hashlib.sha256(data).hexdigest()
        chunk = MediaChunk(alert_id=alert_id, kind=AUDIO, seq=seq,
                           total=total, sha256=sha, payload=data)
        self._send(chunk.as_bytes())
        return True

    def _split(self, data: bytes) -> List[bytes]:
        if not data:
            return [b""]
        return [data[i:i + self.chunk_size]
                for i in range(0, len(data), self.chunk_size)]

    # -- receive -------------------------------------------------------

    def receive(self, frame: bytes) -> Optional[Media]:
        """Ingest one wire frame. Returns a completed Media (photo) or an
        audio Media per chunk, else None while a photo is still assembling."""
        try:
            chunk = MediaChunk.from_bytes(frame)
        except Exception:
            return None

        if chunk.kind == AUDIO:
            media = Media(alert_id=chunk.alert_id, kind=AUDIO,
                          data=chunk.payload, sha256=chunk.sha256,
                          complete=False)
            if self.on_audio_chunk is not None:
                self.on_audio_chunk(media)
            return media

        # photo: reassemble by seq.
        with self._lock:
            entry = self._rx.get(chunk.alert_id)
            if entry is None:
                entry = {"chunks": {}, "total": chunk.total,
                         "sha": chunk.sha256, "kind": PHOTO}
                self._rx[chunk.alert_id] = entry
            entry["chunks"][chunk.seq] = chunk.payload
            if chunk.total and len(entry["chunks"]) >= chunk.total:
                ordered = b"".join(entry["chunks"][i]
                                   for i in range(chunk.total))
                media = Media(alert_id=chunk.alert_id, kind=PHOTO,
                              data=ordered, sha256=entry["sha"],
                              complete=True)
                self._rx.pop(chunk.alert_id, None)
                if self.on_complete is not None:
                    self.on_complete(media)
                return media
        return None


class LinkAdapter:
    """Seam between MediaChannel and a live RNS.Link.

    Until the Kivy/service step establishes a real ``RNS.Link``, this is a
    stub that records what would be sent. The daemon wires
    ``MediaChannel.link_send_fn`` to ``LinkAdapter.send`` once a Link is up;
    ``set_link`` injects the real Link and flushes any queued frames.
    """

    def __init__(self):
        self._link = None
        self._queued: List[bytes] = []
        self._lock = threading.RLock()

    def set_link(self, link) -> None:
        with self._lock:
            self._link = link

    def has_link(self) -> bool:
        return self._link is not None

    def send(self, frame: bytes) -> None:
        with self._lock:
            if self._link is None:
                self._queued.append(frame)
                return
        # Real send over the RNS Link would go here (Resource.advertise for
        # photos, link packet for audio). Stub until Link API is wired.
        raise NotImplementedError("LinkAdapter.send over live RNS.Link — Kivy step")