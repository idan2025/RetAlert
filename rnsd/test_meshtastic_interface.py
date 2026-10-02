# Tests for Meshtastic_Interface.py's tunnel logic (no radio needed).
# Run: python -m pytest rnsd/  (needs `pip install rns meshtastic pytest`)
import os
import random
import struct
import sys
import threading

sys.path.insert(0, os.path.dirname(__file__))
from Meshtastic_Interface import MeshtasticInterface, PacketHandler  # noqa: E402

FIXTURE = os.path.join(os.path.dirname(__file__), "..", "android", "domain", "src", "test", "resources", "py_frags.txt")
BROADCAST = 0xFFFFFFFF


def tunnel():
    """Interface instance with tunnel state only (no node, no threads)."""
    t = MeshtasticInterface.__new__(MeshtasticInterface)
    t.lock = threading.Lock()
    t.online = True
    t.broadcast_num = BROADCAST
    t.packet_i_queue = []
    t.outgoing_packet_storage = {}
    t.packet_index = 0
    t.dest_to_node_dict = {}
    t.peers = {}
    return t


def drain(t):
    out = []
    while (o := t.next_outgoing()) is not None:
        out.append(o)
    return out


def test_fragments_match_upstream_fixture():
    # Same fixture the Kotlin port is checked against: upstream PacketHandler output.
    for line in open(FIXTURE).read().split():
        n, hexes = line.split(":")
        size = int(n)
        data = bytes((i * 7 + size) % 256 for i in range(size))
        frags = [PacketHandler(data, size % 256)[k] for k in PacketHandler(data, size % 256).get_keys()]
        assert ",".join(f.hex() for f in frags) == hexes, size


def test_round_trip_with_reordering_and_index_wrap():
    tx, rx = tunnel(), tunnel()
    rng = random.Random(1)
    for n in range(300):
        data = bytes(rng.randrange(256) for _ in range(rng.randrange(1, 565)))
        tx.process_outgoing(data)
        frags = [p for p, _ in drain(tx)]
        if len(frags) > 1 and n % 3 == 0:
            frags = [frags[-1]] + frags[:-1]  # last fragment first
        got = [d for f in frags if (d := rx.receive(7, f))]
        assert got == [data], n


def test_link_replies_go_unicast():
    t = tunnel()
    link_dest = bytes(range(16))
    link_pkt = bytes([0b00001100, 0]) + link_dest + b"payload"
    t.receive(0x1234, struct.pack("Bb", 0, -1) + link_pkt)
    t.process_outgoing(bytes([0, 0]) + link_dest + b"reply")
    t.process_outgoing(bytes([0, 0]) + bytes(16) + b"other")
    assert [d for _, d in drain(t)] == [0x1234, BROADCAST]


def test_gap_triggers_req_and_req_resends():
    tx, rx = tunnel(), tunnel()
    tx.process_outgoing(bytes(300))  # two fragments
    tx.process_outgoing(bytes(10))
    f1, f2, f3 = [p for p, _ in drain(tx)]
    rx.receive(9, f1)
    rx.receive(9, f3)  # skipped f2
    req = drain(rx)
    assert req == [(b"REQ" + struct.pack("Bb", 0, 2), BROADCAST)]
    tx.receive(9, req[0][0])
    assert drain(tx) == [(f2, BROADCAST)]
    assert rx.receive(9, f2) == bytes(300)
