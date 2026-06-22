"""Tests for MediaChannel (build step 13)."""
import hashlib

from retalert.core.media_channel import (
    MediaChannel, MediaChunk, Media, LinkAdapter, PHOTO, AUDIO, CHUNK_SIZE,
)
from retalert.core.transport_intel import HIGH, MEDIUM, LOW


def _ti_with(*tiers):
    """Fake TransportIntelligence whose up_interfaces returns given tiers."""
    class _Iface:
        def __init__(self, tier):
            self.tier = tier
    class _TI:
        def up_interfaces(self):
            return [_Iface(t) for t in tiers]
    return _TI()


# -- framing ------------------------------------------------------------

def test_chunk_roundtrip():
    c = MediaChunk(alert_id="a1", kind=PHOTO, seq=2, total=5,
                   sha256="abc", payload=b"hello")
    raw = c.as_bytes()
    c2 = MediaChunk.from_bytes(raw)
    assert c2.alert_id == "a1" and c2.kind == PHOTO
    assert c2.seq == 2 and c2.total == 5
    assert c2.sha256 == "abc" and c2.payload == b"hello"


def test_chunk_from_bad_bytes_returns_none_on_receive():
    mc = MediaChannel()
    assert mc.receive(b"\x00\x01garbage") is None


# -- photo send + reassemble -------------------------------------------

def test_send_photo_chunks_and_reassemble():
    sent = []
    mc_tx = MediaChannel(link_send_fn=sent.append)
    mc_rx = MediaChannel()
    data = bytes(range(256)) * 50  # ~12.8KB -> several chunks
    n = mc_tx.send_photo("alert1", data)
    assert n > 1
    assert len(sent) == n
    # Receiver ingests all frames; last returns completed Media.
    completed = None
    for f in sent:
        r = mc_rx.receive(f)
        if r is not None and r.complete:
            completed = r
    assert completed is not None
    assert completed.data == data
    assert completed.sha256 == hashlib.sha256(data).hexdigest()
    assert completed.kind == PHOTO


def test_send_photo_on_complete_callback():
    sent = []
    mc_tx = MediaChannel(link_send_fn=sent.append)
    got = []
    mc_rx = MediaChannel(on_complete=got.append)
    data = b"x" * (CHUNK_SIZE * 2 + 10)
    mc_tx.send_photo("a", data)
    for f in sent:
        mc_rx.receive(f)
    assert len(got) == 1
    assert got[0].data == data


def test_send_photo_out_of_order_reassembles():
    sent = []
    mc_tx = MediaChannel(link_send_fn=sent.append)
    data = b"y" * (CHUNK_SIZE + 5)
    mc_tx.send_photo("a", data)
    # Shuffle frames; receiver should still assemble by seq.
    import random
    rng = random.Random(42)
    frames = list(sent)
    rng.shuffle(frames)
    mc_rx = MediaChannel()
    completed = None
    for f in frames:
        r = mc_rx.receive(f)
        if r is not None and r.complete:
            completed = r
    assert completed is not None and completed.data == data


def test_empty_photo():
    sent = []
    mc_tx = MediaChannel(link_send_fn=sent.append)
    mc_rx = MediaChannel()
    n = mc_tx.send_photo("a", b"")
    assert n == 1
    r = mc_rx.receive(sent[0])
    assert r is not None and r.complete and r.data == b""


# -- audio stream -------------------------------------------------------

def test_send_audio_chunk():
    sent = []
    mc = MediaChannel(link_send_fn=sent.append)
    ok = mc.send_audio_chunk("a", b"\x01\x02\x03", seq=0, total=0)
    assert ok is True
    chunk = MediaChunk.from_bytes(sent[0])
    assert chunk.kind == AUDIO and chunk.seq == 0 and chunk.total == 0


def test_receive_audio_fires_callback():
    heard = []
    mc = MediaChannel(on_audio_chunk=heard.append)
    mc.send_audio_chunk = None  # not used
    # Build a frame manually via a tx channel.
    sent = []
    MediaChannel(link_send_fn=sent.append).send_audio_chunk("a", b"\x09", 1, 0)
    r = mc.receive(sent[0])
    assert r is not None and r.kind == AUDIO and r.data == b"\x09"
    assert len(heard) == 1


# -- tier gating --------------------------------------------------------

def test_gate_blocks_on_low_only():
    mc = MediaChannel(ti=_ti_with(LOW))
    ok, best = mc.gate(PHOTO)
    assert ok is False and best == LOW


def test_gate_blocks_on_medium():
    mc = MediaChannel(ti=_ti_with(MEDIUM))
    assert mc.gate(PHOTO)[0] is False


def test_gate_allows_on_high():
    mc = MediaChannel(ti=_ti_with(HIGH))
    assert mc.gate(PHOTO)[0] is True


def test_gate_allows_when_high_present_with_low():
    mc = MediaChannel(ti=_ti_with(LOW, HIGH))
    ok, best = mc.gate(PHOTO)
    assert ok is True and best == HIGH  # best tier is High


def test_gate_blocks_when_nothing_up():
    mc = MediaChannel(ti=_ti_with())
    ok, best = mc.gate(PHOTO)
    assert ok is False and best is None


def test_send_photo_refused_on_low_tier():
    sent = []
    mc = MediaChannel(ti=_ti_with(LOW), link_send_fn=sent.append)
    n = mc.send_photo("a", b"hello")
    assert n == 0
    assert sent == []


def test_send_audio_refused_on_low_tier():
    sent = []
    mc = MediaChannel(ti=_ti_with(LOW), link_send_fn=sent.append)
    assert mc.send_audio_chunk("a", b"x", 0) is False
    assert sent == []


def test_no_ti_allows():
    sent = []
    mc = MediaChannel(link_send_fn=sent.append)  # ti=None
    assert mc.send_photo("a", b"hi") >= 1


def test_send_without_link_raises():
    mc = MediaChannel()
    try:
        mc.send_photo("a", b"hi")
        assert False, "expected RuntimeError"
    except RuntimeError:
        pass


# -- LinkAdapter --------------------------------------------------------

def test_link_adapter_queues_without_link():
    la = LinkAdapter()
    assert not la.has_link()
    la.send(b"frame1")  # queued, no raise
    la.send(b"frame2")
    assert len(la._queued) == 2


def test_link_adapter_has_link_after_set():
    la = LinkAdapter()
    la.set_link(object())
    assert la.has_link()