"""Tests for Discover (build step 6)."""
import time
from pathlib import Path

from RNS.vendor import umsgpack

from retalert.core.discover import (
    Discover, AnnounceHandler, ASPECT_LXMF_DELIVERY,
)


def _lxmf_app_data(name: str):
    """Pack app_data the way LXMRouter.get_announce_app_data does."""
    return umsgpack.packb([name.encode("utf-8"), None, [1]])


def _hash_bytes(n: int) -> bytes:
    return bytes([(n * 7 + 3) % 256 for _ in range(16)])


def test_heard_decodes_display_name():
    d = Discover()
    h = _hash_bytes(1)
    peer = d.heard(h, _lxmf_app_data("Alice"))
    assert peer.hash == h.hex()
    assert peer.display_name == "Alice"
    assert peer.aspect == ASPECT_LXMF_DELIVERY
    assert peer.app_name == "lxmf"


def test_heard_refresh_updates_last_heard():
    d = Discover()
    h = _hash_bytes(2)
    p1 = d.heard(h, _lxmf_app_data("Bob"))
    first = p1.last_heard
    time.sleep(0.02)
    p2 = d.heard(h, _lxmf_app_data("Bob"))
    assert p2.last_heard > first
    assert p2.first_heard == first  # first_heard unchanged


def test_heard_updates_name():
    d = Discover()
    h = _hash_bytes(3)
    d.heard(h, _lxmf_app_data("Old"))
    p = d.heard(h, _lxmf_app_data("New"))
    assert p.display_name == "New"


def test_list_sorted_freshest_first():
    d = Discover()
    d.heard(_hash_bytes(10), _lxmf_app_data("a"))
    time.sleep(0.02)
    d.heard(_hash_bytes(11), _lxmf_app_data("b"))
    time.sleep(0.02)
    d.heard(_hash_bytes(12), _lxmf_app_data("c"))
    peers = d.list()
    assert [p.display_name for p in peers] == ["c", "b", "a"]


def test_clear_empties_heard_cache():
    d = Discover()
    d.heard(_hash_bytes(20), _lxmf_app_data("x"))
    n = d.clear()
    assert n == 1
    assert d.list() == []


def test_star_persists_across_clear(tmp_path):
    d = Discover(starred_path=tmp_path / "starred.json")
    h = _hash_bytes(30)
    d.heard(h, _lxmf_app_data("Star"))
    assert d.star(h.hex()) is True
    d.clear()
    # Re-heard peer keeps starred flag from persisted set.
    p = d.heard(h, _lxmf_app_data("Star"))
    assert p.starred is True


def test_star_without_hearing_persists():
    d = Discover(starred_path=Path("/tmp/_retalert_test_starred.json"))
    h = _hash_bytes(31)
    ok = d.star(h.hex())
    assert ok is False  # not heard yet
    # When later heard, it shows starred.
    p = d.heard(h, _lxmf_app_data("Late"))
    assert p.starred is True
    d.unstar(h.hex())


def test_unstar():
    d = Discover(starred_path=Path("/tmp/_retalert_test_starred2.json"))
    h = _hash_bytes(32)
    d.heard(h, _lxmf_app_data("S"))
    d.star(h.hex())
    assert d.unstar(h.hex()) is True
    p = d.get(h.hex())
    assert p.starred is False


def test_get():
    d = Discover()
    h = _hash_bytes(40)
    d.heard(h, _lxmf_app_data("G"))
    assert d.get(h.hex()).display_name == "G"
    assert d.get("deadbeef") is None


def test_non_lxmf_announce_no_name():
    d = Discover()
    h = _hash_bytes(50)
    p = d.heard(h, b"some-other-app-data", aspect="custom.app")
    assert p.display_name == ""
    assert p.app_name == "custom"


def test_malformed_app_data_no_crash():
    d = Discover()
    h = _hash_bytes(51)
    p = d.heard(h, b"\x00\x01\x02bad", aspect=ASPECT_LXMF_DELIVERY)
    assert p.display_name == ""  # best-effort, no crash


def test_announce_handler_forwards():
    d = Discover()
    handler = AnnounceHandler(d, aspect_filter=ASPECT_LXMF_DELIVERY)
    h = _hash_bytes(60)
    handler.received_announce(h, object(), _lxmf_app_data("ViaHandler"))
    assert d.get(h.hex()).display_name == "ViaHandler"


def test_announce_handler_swallows_exceptions():
    d = Discover()
    handler = AnnounceHandler(d, aspect_filter=ASPECT_LXMF_DELIVERY)
    # Pass junk that would break RNS.hexrep; handler must not raise.
    handler.received_announce(None, object(), b"")
    # No assertion needed — reaching here means no exception escaped.