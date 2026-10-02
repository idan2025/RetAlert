# RNS-over-Meshtastic interface for rnsd, maintained alongside RetAlert.
#
# Fork of landandair/RNS_Over_Meshtastic (Interface/Meshtastic_Interface.py,
# commit e5eb5d2, MIT) and wire-compatible with it, so it talks to nodes
# running the upstream interface as well as to the RetAlert Android app.
# It carries over the fixes from RetAlert's Kotlin port (RnsTunnel.kt):
#
#   - replies to link traffic really go unicast (upstream compared the stored
#     handler against the PacketHandler class, so everything was broadcast)
#   - a packet is reassembled once all fragments are in, even when the last
#     one arrived before a resent middle one (upstream dropped it)
#   - the node's radio config is never rewritten: pacing follows the preset
#     the node already uses (upstream wrote data_speed into the node and then
#     stayed offline until a reconnect)
#   - one send thread for the interface's lifetime; reconnects with backoff
#     instead of stacking a new send loop and giving up on the first failure
#   - only tunnel packets on the configured channel, addressed to us or
#     broadcast, are accepted; per-peer state is bounded; state is locked
#
# Install: put this file in ~/.reticulum/interfaces/, `pip install meshtastic`,
# and add to ~/.reticulum/config:
#
#   [[Meshtastic Interface]]
#     type = Meshtastic_Interface
#     enabled = yes
#     mode = gateway
#     port = /dev/ttyACM0         # serial, or:
#     # ble_port = short_1234     # BLE name/address, or:
#     # tcp_port = 10.0.0.5:4403  # node's TCP API (port optional)
#     channel = 0                 # channel index used for the tunnel (0-7)
#     hop_limit = 1
#     # data_speed = 3            # only used if the node doesn't report a preset

from RNS.Interfaces.Interface import Interface
import RNS
import struct
import threading
import time


class MeshtasticInterface(Interface):
    DEFAULT_IFAC_SIZE = 8

    # Seconds between transmissions per Meshtastic modem preset.
    speed_to_delay = {8: .4,   # SHORT_TURBO
                      6: 1,    # SHORT_FAST
                      5: 3,    # SHORT_SLOW
                      4: 4,    # MEDIUM_FAST
                      3: 6,    # MEDIUM_SLOW
                      0: 8,    # LONG_FAST
                      7: 12,   # LONG_MODERATE
                      1: 15,   # LONG_SLOW
                      }
    DEFAULT_DELAY = 7

    MAX_QUEUE = 256
    MAX_ROUTES = 20
    MAX_REQUESTED = 10
    MAX_EXPECTED = 32
    MAX_PARTIAL = 16
    MIN_BACKOFF = 5
    MAX_BACKOFF = 60

    def __init__(self, owner, configuration):
        import importlib.util
        if importlib.util.find_spec('meshtastic') is None:
            RNS.log("Using this interface requires a meshtastic module to be installed.", RNS.LOG_CRITICAL)
            RNS.log("You can install one with the command: python3 -m pip install meshtastic", RNS.LOG_CRITICAL)
            RNS.panic()
        import meshtastic
        from pubsub import pub
        self.mt_bin_port = meshtastic.portnums_pb2.RETICULUM_TUNNEL_APP
        self.broadcast_num = meshtastic.BROADCAST_NUM

        super().__init__()

        ifconf = Interface.get_config_obj(configuration)
        self.name = ifconf["name"]
        self.port = ifconf["port"] if "port" in ifconf else None
        self.ble_port = ifconf["ble_port"] if "ble_port" in ifconf else None
        self.tcp_port = ifconf["tcp_port"] if "tcp_port" in ifconf else None
        self.speed = int(ifconf["data_speed"]) if "data_speed" in ifconf else None
        self.hop_limit = int(ifconf["hop_limit"]) if "hop_limit" in ifconf else 1
        self.channel = int(ifconf["channel"]) if "channel" in ifconf else 0
        if not (self.port or self.ble_port or self.tcp_port):
            raise ValueError(f"No port, ble_port or tcp_port specified for {self}")

        self.HW_MTU = 564
        self.bitrate = int(ifconf["bitrate"]) if "bitrate" in ifconf else 500
        self.online = False
        self.owner = owner

        self.interface = None
        self.my_node = None
        self.preset = None
        self.detached = False
        self.lock = threading.Lock()
        self.lost = threading.Event()

        self.packet_i_queue = []          # (index, pos) to send; index -1 = raw REQ
        self.outgoing_packet_storage = {}  # index -> PacketHandler, -1 -> [REQ payload]
        self.packet_index = 0
        self.dest_to_node_dict = {}        # link destination hash -> node number
        self.peers = {}                    # node number -> _Peer

        pub.subscribe(self.process_message, "meshtastic.receive")
        pub.subscribe(self.connection_closed, "meshtastic.connection.lost")

        threading.Thread(target=self.supervisor, daemon=True).start()
        threading.Thread(target=self.write_loop, daemon=True).start()

    # -- connection ---------------------------------------------------------

    def open_interface(self):
        if self.port:
            RNS.log(f"Meshtastic: Opening serial port {self.port}...", RNS.LOG_VERBOSE)
            from meshtastic.serial_interface import SerialInterface
            return SerialInterface(devPath=self.port)
        if self.ble_port:
            RNS.log(f"Meshtastic: Opening BLE device {self.ble_port}...", RNS.LOG_VERBOSE)
            from meshtastic.ble_interface import BLEInterface
            return BLEInterface(address=self.ble_port)
        RNS.log(f"Meshtastic: Opening TCP device {self.tcp_port}...", RNS.LOG_VERBOSE)
        from meshtastic.tcp_interface import TCPInterface, DEFAULT_TCP_PORT
        host, port = self.tcp_port, DEFAULT_TCP_PORT
        if ":" in self.tcp_port:
            host, port = self.tcp_port.rsplit(":", 1)
        return TCPInterface(hostname=host, portNumber=int(port))

    def supervisor(self):
        """Keep one connection to the node open, reconnecting with backoff."""
        backoff = self.MIN_BACKOFF
        while not self.detached:
            self.lost.clear()
            try:
                # The meshtastic constructors block until the node's config is in.
                self.interface = self.open_interface()
                self.configure_device(self.interface)
                backoff = self.MIN_BACKOFF
                self.lost.wait()
                RNS.log(f"{self}: connection to node lost", RNS.LOG_WARNING)
            except Exception as e:
                RNS.log(f"{self}: could not connect to node: {e}", RNS.LOG_ERROR)
            self.online = False
            iface, self.interface = self.interface, None
            if iface is not None:
                try:
                    iface.close()
                except Exception:
                    pass
            if self.detached:
                break
            time.sleep(backoff)
            backoff = min(backoff * 2, self.MAX_BACKOFF)

    def configure_device(self, interface):
        """Adopt the node's own settings; never write config to it."""
        self.my_node = interface.myInfo.my_node_num if interface.myInfo else None
        node = interface.getNode('^local')
        lora = node.localConfig.lora
        self.preset = lora.modem_preset if lora.use_preset else None
        if self.speed is not None and self.preset is not None and self.speed != self.preset:
            RNS.log(f"{self}: data_speed {self.speed} differs from the node's preset {self.preset}; "
                    f"pacing for the node's preset (the node is not reconfigured)", RNS.LOG_WARNING)
        ch = node.channels[self.channel] if node.channels and self.channel < len(node.channels) else None
        if ch is None or ch.role == 0:  # DISABLED
            raise IOError(f"channel {self.channel} isn't set up on the node")
        RNS.log(f"{self}: connected to node {self.my_node or 0:08x}, channel {self.channel} "
                f"'{ch.settings.name}', preset {self.preset}, send delay {self.send_delay()}s")
        self.online = True

    def connection_closed(self, interface):
        if interface is self.interface:
            self.online = False
            self.lost.set()

    def detach(self):
        self.detached = True
        self.online = False
        self.lost.set()

    # -- outgoing -----------------------------------------------------------

    def send_delay(self):
        speed = self.preset if self.preset is not None else self.speed
        return self.speed_to_delay.get(speed, self.DEFAULT_DELAY)

    def process_outgoing(self, data: bytes):
        if not self.online or not data:
            return
        with self.lock:
            if len(self.packet_i_queue) >= self.MAX_QUEUE:
                RNS.log(f"{self}: send queue full, dropped {len(data)} bytes", RNS.LOG_WARNING)
                return
            dest = self.dest_to_node_dict.get(bytes(data[2:18]), self.broadcast_num)
            handler = PacketHandler(data, self.packet_index, custom_destination_id=dest)
            self.outgoing_packet_storage[handler.index] = handler
            for key in handler.get_keys():
                self.packet_i_queue.append((handler.index, key))
            self.packet_index = calc_index(self.packet_index)

    def next_outgoing(self):
        with self.lock:
            while self.packet_i_queue:
                index, position = self.packet_i_queue.pop(0)
                stored = self.outgoing_packet_storage.get(index)
                if stored is None:
                    continue
                if isinstance(stored, PacketHandler):
                    data = stored[position]
                    if data:
                        return data, stored.destination_id
                else:
                    return stored[0], self.broadcast_num
        return None

    def write_loop(self):
        RNS.log(f"{self}: outgoing loop started", RNS.LOG_VERBOSE)
        while not self.detached:
            iface = self.interface
            if not self.online or iface is None:
                time.sleep(0.5)
                continue
            out = self.next_outgoing()
            if out is None:
                time.sleep(0.1)
                continue
            data, dest = out
            try:
                iface.sendData(data,
                               destinationId=dest,
                               portNum=self.mt_bin_port,
                               wantAck=False,
                               wantResponse=False,
                               channelIndex=self.channel,
                               hopLimit=self.hop_limit)
                self.txb += len(data)
            except Exception as e:
                RNS.log(f"{self}: send failed: {e}", RNS.LOG_WARNING)
                self.lost.set()
            time.sleep(self.send_delay())

    # -- incoming -----------------------------------------------------------

    def process_message(self, packet, interface):
        if interface is not self.interface:
            return
        decoded = packet.get("decoded")
        if not decoded or decoded.get("portnum") != "RETICULUM_TUNNEL_APP":
            return
        sender = packet.get("from")
        to = packet.get("to", self.broadcast_num)
        if packet.get("channel", 0) != self.channel or sender is None or sender == self.my_node:
            return
        if to != self.broadcast_num and to != self.my_node:
            return
        payload = decoded.get("payload")
        if not payload:
            return
        with self.lock:
            data = self.receive(sender, bytes(payload))
        if data:
            self.rxb += len(data)
            self.owner.inbound(data, self)

    def receive(self, sender, payload):
        """Upstream's gap-detection / REQ protocol; returns a completed packet or None."""
        if payload[:3] == b'REQ':
            if len(payload) >= 5:
                self.packet_i_queue.insert(0, struct.unpack(PacketHandler.struct_format, payload[3:5]))
            return None
        if len(payload) <= 2:
            return None
        peer = self.peers.setdefault(sender, _Peer())
        index, pos = struct.unpack(PacketHandler.struct_format, payload[:2])
        key = (index, abs(pos))
        expect_followup = True
        if key in peer.expected:
            peer.expected = [k for k in peer.expected if k != key]
        elif key in peer.requested:
            peer.requested.remove(key)
            expect_followup = False
        elif peer.expected:
            # Not what we expected next: ask for the oldest fragment we're missing.
            ex_index, ex_pos = peer.expected.pop(0)
            peer.requested.append((ex_index, abs(ex_pos)))
            if len(peer.requested) > self.MAX_REQUESTED:
                peer.requested.pop(0)
            self.outgoing_packet_storage[-1] = [b'REQ' + struct.pack(PacketHandler.struct_format, ex_index, ex_pos)]
            self.packet_i_queue.insert(0, (-1, 0))

        handler = peer.assembly.get(index)
        if handler is None:
            handler = peer.assembly[index] = PacketHandler()
        data = handler.process_packet(payload)
        if data:
            peer.assembly.pop(index, None)
            self.learn_route(data, sender)
        while len(peer.assembly) > self.MAX_PARTIAL:
            peer.assembly.pop(next(iter(peer.assembly)))

        if expect_followup:
            peer.expected.insert(0, (calc_index(index), 1) if pos < 0 else (index, pos + 1))
            del peer.expected[self.MAX_EXPECTED:]
        return data

    def learn_route(self, data, sender):
        """Link traffic (header byte 00..11..) tells us which node a link lives
        behind, so replies to it can go unicast."""
        if len(data) < 18:
            return
        flags = data[0]
        if flags & 0b11000000 == 0 and flags & 0b00001100 == 0b00001100:
            dest = bytes(data[2:18])
            self.dest_to_node_dict.pop(dest, None)
            self.dest_to_node_dict[dest] = sender
            while len(self.dest_to_node_dict) > self.MAX_ROUTES:
                self.dest_to_node_dict.pop(next(iter(self.dest_to_node_dict)))

    @staticmethod
    def should_ingress_limit():
        return False

    def __str__(self):
        return "MeshtasticInterface[" + self.name + "]"


class _Peer:
    def __init__(self):
        self.expected = []
        self.requested = []
        self.assembly = {}


class PacketHandler:
    """One RNS packet as tunnel fragments: [index u8][pos i8] + chunk."""
    struct_format = 'Bb'

    def __init__(self, data=None, index=None, max_payload=200, custom_destination_id=None):
        self.max_payload = max_payload
        self.index = index
        self.data_dict = {}
        self.last = None
        self.destination_id = custom_destination_id
        if data:
            self.split_data(data)

    def split_data(self, data: bytes):
        """Split data into even chunks (same chunking as upstream)."""
        data_len = len(data)
        num_packets = data_len // self.max_payload + 1
        packet_size = data_len // num_packets + 1
        chunks = [data[i:i + packet_size] for i in range(0, data_len, packet_size)]
        for i, chunk in enumerate(chunks):
            pos = i + 1
            if pos == len(chunks):
                pos = -pos
            self.data_dict[pos] = struct.pack(self.struct_format, self.index, pos) + chunk

    def __getitem__(self, i):
        if i in self.data_dict:
            return self.data_dict[i]
        if -i in self.data_dict:
            return self.data_dict[-i]
        return None

    def get_keys(self):
        return list(self.data_dict.keys())

    def process_packet(self, packet: bytes):
        """Store a received fragment; returns the packet once every part is in."""
        index, pos = struct.unpack(self.struct_format, packet[:2])
        if pos == 0:
            return None
        self.index = index
        self.data_dict[abs(pos)] = packet
        if pos < 0:
            self.last = abs(pos)
        if self.last is None or sorted(self.data_dict) != list(range(1, self.last + 1)):
            return None
        return b''.join(self.data_dict[k][2:] for k in range(1, self.last + 1))


def calc_index(curr_index):
    return (curr_index + 1) % 256


interface_class = MeshtasticInterface
