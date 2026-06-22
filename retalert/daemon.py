"""EmergencyDaemon — owns the RNS stack, identity, and LXMF transport.

Step 1: a thin orchestrator. Later build steps attach PanicEngine,
AckTracker, RetryQueue, GeoTracker, MediaChannel, TransportIntelligence
(see core/ stubs + PROMPT.md § Suggested build order).
"""
from __future__ import annotations

import logging
import threading
import time
from typing import Callable, Optional

import RNS

from .config import AppConfig
from .storage import Contacts, Presets
from .core.alert import Alert
from .core.ack_tracker import AckTracker
from .core.retry_queue import RetryQueue
from .core.transport_intel import TransportIntelligence, FAN_OUT_CRITICAL, LOW
from .core.geo_tracker import GeoTracker, FixSource, Fix
from .transport.identity import load_or_create_identity, identity_hash_hex
from .transport.lxmf_transport import LXMFTransport

log = logging.getLogger("retalert.daemon")


class EmergencyDaemon:
    """One RetAlert instance: RNS + identity + LXMF router + local stores."""

    def __init__(self, config: AppConfig, display_name: str = "RetAlert",
                 loglevel: int = 3):
        self.config = config
        self.display_name = display_name
        self.loglevel = loglevel

        self.reticulum: Optional[RNS.Reticulum] = None
        self.identity = None
        self.lxmf: Optional[LXMFTransport] = None
        self.contacts = Contacts(config.contacts_file)
        self.presets = Presets(config.presets_file)

        self.ack = AckTracker()
        self.retry = RetryQueue(config.alerts_file, self.ack,
                                send_fn=self._send_to_recipient)
        self.ti: Optional[TransportIntelligence] = None
        self.geo: Optional[GeoTracker] = None
        self._retry_thread: Optional[threading.Thread] = None
        self._running = False

        self._incoming_cb: Optional[Callable[[str, str, float], None]] = None

    # -- setup ----------------------------------------------------------

    def init_identity(self) -> None:
        """Create or load the persisted RNS identity."""
        self.identity = load_or_create_identity(self.config.identity_file)
        log.info("identity hash: %s", identity_hash_hex(self.identity))

    def set_incoming_callback(self, cb: Callable[[str, str, float], None]) -> None:
        self._incoming_cb = cb

    # -- lifecycle ------------------------------------------------------

    def start(self) -> None:
        """Bring up RNS, identity, LXMF router, announce, and run."""
        if self.identity is None:
            self.init_identity()

        self.reticulum = RNS.Reticulum(
            configdir=str(self.config.rns_config_dir),
            loglevel=self.loglevel,
        )
        self.ti = TransportIntelligence(reticulum=self.reticulum)

        self.lxmf = LXMFTransport(
            identity=self.identity,
            storage_path=self.config.lxmf_storage,
            display_name=self.display_name,
        )
        self.lxmf.register()
        if self._incoming_cb:
            self.lxmf.set_incoming_callback(self._incoming_cb)
        self.lxmf.start()
        self.lxmf.announce()
        log.info("announced LXMF delivery: %s", self.lxmf.delivery_hash_hex)
        self._start_retry_flusher()

    def stop(self) -> None:
        """Best-effort shutdown."""
        self._running = False
        log.info("daemon stopping")

    # -- retry flusher --------------------------------------------------

    def _start_retry_flusher(self) -> None:
        if self._retry_thread and self._retry_thread.is_alive():
            return
        self._running = True
        self._retry_thread = threading.Thread(target=self._flush_loop, daemon=True)
        self._retry_thread.start()

    def _flush_loop(self) -> None:
        # Fast retry while alerts are unacked; idle poll otherwise.
        while self._running:
            try:
                self.retry.flush()
            except Exception as exc:  # never let the flusher die
                log.warning("retry flush error: %s", exc)
            time.sleep(1)

    # -- alert send -----------------------------------------------------

    def send_alert(self, alert: Alert) -> Alert:
        """Enqueue + immediately send an alert to all its recipients.

        TransportIntelligence produces the delivery plan (Hail Mary fan-out
        vs sequential failover, tier gating). RetryQueue persists the alert
        and re-sends to unacked recipients on its flush loop until acked/
        failed. If no qualifying interface is up, the plan is queued and the
        flusher retries once one appears.
        """
        if self.lxmf is None:
            raise RuntimeError("daemon not started")
        if self.ti is not None:
            plan = self.ti.delivery_plan(alert, fan_out=alert.fan_out)
            if plan.queued:
                log.warning("alert %s queued: %s", alert.alert_id, plan.reason)
            else:
                log.info("alert %s plan: %s on %d iface(s)",
                         alert.alert_id, plan.mode, len(plan.interfaces))
        self.retry.enqueue(alert)
        return alert

    def _send_to_recipient(self, alert: Alert, recipient_hex: str) -> None:
        """Transport callback for RetryQueue: send to one recipient and wire
        its delivery/failed callbacks back into AckTracker."""
        if self.lxmf is None:
            raise RuntimeError("daemon not started")

        def on_delivered():
            self.ack.on_delivered(alert.alert_id, recipient_hex)

        def on_failed():
            # LXMF failed callback gives no detail; record generic failure.
            self.ack.on_failed(alert.alert_id, recipient_hex, "lxmf failed")

        self.lxmf.send_message(recipient_hex, alert.text,
                               on_delivered=on_delivered, on_failed=on_failed)

    # -- location -------------------------------------------------------

    def set_fix_source(self, fix_source: FixSource) -> None:
        """Install a GPS fix source (manual/platform) for GeoTracker."""
        self.geo = GeoTracker(fix_source)

    def send_location(self, dest_hash_hex: str, fix: Fix) -> None:
        """Send one GPS fix to a recipient as an LXMF message.

        Step 5 sends a compact ``geo:lat,lon`` text body; step 8 (map) parses
        incoming geo and renders it, and a structured LXMF field replaces the
        text body.
        """
        if self.lxmf is None:
            raise RuntimeError("daemon not started")
        body = f"{fix.geo_uri} acc={fix.accuracy} alt={fix.altitude} src={fix.source}"
        self.lxmf.send_message(dest_hash_hex, body)

    def start_live_share(self, dest_hash_hex: str, interval: float) -> None:
        """Begin periodic live location sharing to one recipient."""
        if self.geo is None:
            raise RuntimeError("no fix source set; call set_fix_source first")
        if self.lxmf is None:
            raise RuntimeError("daemon not started")
        self.geo.start_live_share(
            interval, send_fn=lambda fix: self.send_location(dest_hash_hex, fix))

    def stop_live_share(self) -> None:
        if self.geo is not None:
            self.geo.stop_live_share()

    def only_low_tier_up(self) -> bool:
        """True if every up interface is Low tier (LoRa-only situation)."""
        if self.ti is None:
            return False
        up = self.ti.up_interfaces()
        return bool(up) and all(i.tier == LOW for i in up)

    # -- convenience ----------------------------------------------------

    @property
    def delivery_hash_hex(self) -> str:
        if self.lxmf is None:
            raise RuntimeError("daemon not started")
        return self.lxmf.delivery_hash_hex

    def send_message(self, dest_hash_hex: str, text: str,
                     on_delivered: Optional[Callable[[], None]] = None,
                     on_failed: Optional[Callable[[], None]] = None):
        if self.lxmf is None:
            raise RuntimeError("daemon not started")
        return self.lxmf.send_message(dest_hash_hex, text, on_delivered, on_failed)