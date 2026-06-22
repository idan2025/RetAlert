"""TransportIntelligence — interface classification, ranking, fan-out, failover.

Build step: 3-4. See PROMPT.md § Transport intelligence & failover (the
dedicated section). This is the core of the app's "smart" transport behaviour:

* Classify active RNS interfaces by type/bandwidth (WAN/LAN High, BLE Medium,
  LoRa Low, Unknown -> Low safe default).
* Per-payload tier policy: text/ack/GPS-one-shot = any; live-GPS = Medium+;
  photo = High (degrade on Medium); audio = High only. Never open a
  conversation/media channel over a Low interface.
* Rank usable interfaces per recipient (tier compat, bandwidth, latency, health).
* Delivery plan: "Hail Mary" fan-out (parallel on every compatible interface,
  deduped by alert_id) default-on for critical/danger/medical, user-toggle;
  sequential fast failover otherwise.
* Fast failover on interface/path death; backoff suspended while an alert is
  unacked.
"""


class TransportIntelligence:
    """Classifies interfaces, enforces per-payload policy, ranks interfaces per
    recipient, and produces a delivery plan (fan-out vs sequential failover)."""

    def __init__(self, reticulum=None):
        self.reticulum = reticulum

    def classify_interfaces(self):
        raise NotImplementedError("TransportIntelligence.classify_interfaces — build step 3")

    def rank_for(self, recipient_hash, payload_class):
        raise NotImplementedError("TransportIntelligence.rank_for — build step 3")

    def delivery_plan(self, preset):
        raise NotImplementedError("TransportIntelligence.delivery_plan — build step 4")