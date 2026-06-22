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
from .storage import Contacts, Presets, Groups, Settings
from .core.alert import Alert
from .core.ack_tracker import AckTracker
from .core.retry_queue import RetryQueue
from .core.transport_intel import TransportIntelligence, FAN_OUT_CRITICAL, LOW
from .core.geo_tracker import GeoTracker, FixSource, Fix
from .core.announce_engine import AnnounceEngine
from .core.discover import Discover, AnnounceHandler, ASPECT_LXMF_DELIVERY
from .core.live_tracks import LiveTrackStore
from .core.incoming import IncomingDispatcher, encode_alert, encode_ack
from .core.preset import Preset, PresetStore
from .core.panic_engine import PanicEngine, PresetResolver
from .core.hardware_keys import HardwareKeyManager, KeyCaptureBackend
from .core.media_channel import MediaChannel, LinkAdapter, PHOTO, AUDIO
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
        self.preset_store = PresetStore(config.presets_file)
        self.groups = Groups(config.groups_file)
        self.settings = Settings(config.settings_file)

        self.ack = AckTracker()
        self.retry = RetryQueue(config.alerts_file, self.ack,
                                send_fn=self._send_to_recipient)
        self.ti: Optional[TransportIntelligence] = None
        self.geo: Optional[GeoTracker] = None
        self.panic = PanicEngine(
            store=self.preset_store, groups=self.groups,
            send_alert_fn=self.send_alert,
            expand_group_fn=self.expand_group,
            start_live_share_fn=self.start_live_share,
            get_fix_fn=self._get_current_fix,
        )
        self.keys = HardwareKeyManager(fire_fn=self._fire_trigger,
                                       path=config.keys_file)
        self.discover = Discover(starred_path=config.starred_file)
        self.media = MediaChannel()  # ti + link_send_fn wired on start()
        self.tracks = LiveTrackStore()
        self.incoming = IncomingDispatcher(
            settings=self.settings, contacts=self.contacts,
            discover=self.discover, tracks=self.tracks,
            on_message=self._on_parsed_incoming,
            send_ack_fn=self._send_ack,
            ack_cb=self._on_inbound_ack,
        )
        self.announce_engine = AnnounceEngine(self._do_announce)
        self._retry_thread: Optional[threading.Thread] = None
        self._running = False

        self._incoming_cb: Optional[Callable[[object], None]] = None

    # -- setup ----------------------------------------------------------

    def init_identity(self) -> None:
        """Create or load the persisted RNS identity."""
        self.identity = load_or_create_identity(self.config.identity_file)
        log.info("identity hash: %s", identity_hash_hex(self.identity))

    def set_incoming_callback(self, cb: Callable[[object], None]) -> None:
        """``cb(IncomingMessage)`` is called for each parsed, filter-passed
        inbound message."""
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
        # Wire transport intelligence into the media channel for tier gating.
        self.media.ti = self.ti

        self.lxmf = LXMFTransport(
            identity=self.identity,
            storage_path=self.config.lxmf_storage,
            display_name=self.display_name,
        )
        self.lxmf.register()
        # Route all inbound LXMF through the dispatcher (filter + parse),
        # then forward the parsed message to the user-facing callback.
        self.lxmf.set_incoming_callback(self._route_incoming)
        self.lxmf.start()
        self.lxmf.announce()
        log.info("announced LXMF delivery: %s", self.lxmf.delivery_hash_hex)
        # Register an announce handler so heard peers populate the discover list.
        RNS.Transport.register_announce_handler(
            AnnounceHandler(self.discover, aspect_filter=ASPECT_LXMF_DELIVERY)
        )
        self._start_retry_flusher()

    def stop(self) -> None:
        """Best-effort shutdown."""
        self._running = False
        self.announce_engine.stop()
        self.stop_live_share()
        log.info("daemon stopping")

    # -- announce / discover -------------------------------------------

    def _do_announce(self) -> None:
        """Transport callback for AnnounceEngine (and manual announce_now)."""
        if self.lxmf is not None:
            self.lxmf.announce()

    def announce_now(self) -> None:
        """Send one announce immediately (manual / CLI one-shot)."""
        if self.lxmf is None:
            raise RuntimeError("daemon not started")
        self.announce_engine.announce_now()

    def set_auto_announce(self, enabled: bool,
                          interval: Optional[float] = None) -> float:
        """Toggle auto-announce; ``interval`` clamped to [30min, 12h]."""
        return self.announce_engine.set_auto(enabled, interval=interval)

    @property
    def auto_announce(self) -> bool:
        return self.announce_engine.auto

    def discover_list(self):
        return self.discover.list()

    def discover_clear(self) -> int:
        return self.discover.clear()

    def star(self, hash_hex: str) -> bool:
        return self.discover.star(hash_hex)

    def unstar(self, hash_hex: str) -> bool:
        return self.discover.unstar(hash_hex)

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

    def expand_group(self, name: str) -> list[str]:
        """Return the member destination hashes for a saved group, or [] if
        the group does not exist."""
        return self.groups.members(name)

    def send_to_group(self, group_name: str, *, severity: str = "help",
                      text: str = "", retry_interval: float = 3.0,
                      max_attempts: int = 0, fan_out: str = "critical") -> Alert:
        """Build an alert addressed to every member of a group and send it.

        Each member is an individual LXMF destination (no persistent group
        destination). TransportIntelligence fans out per-recipient.
        """
        members = self.expand_group(group_name)
        if not members:
            raise LookupError(f"group '{group_name}' has no members "
                              f"(or does not exist)")
        alert = Alert(severity=severity, text=text, recipients=members,
                      retry_interval=retry_interval,
                      max_attempts=max_attempts, fan_out=fan_out)
        return self.send_alert(alert)

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

        # Wrap the body with the RetAlert marker so receivers parse it as an
        # app-to-app alert (bypass-silent) rather than casual text. Include
        # the alert_id (v1) so the receiver can ack it back.
        body = encode_alert(alert.severity, alert.text, alert_id=alert.alert_id)
        self.lxmf.send_message(recipient_hex, body,
                               on_delivered=on_delivered, on_failed=on_failed)

    # -- incoming -------------------------------------------------------

    def _route_incoming(self, source_hex: str, text: str, timestamp: float) -> None:
        """LXMF inbound entry point: filter + parse via the dispatcher."""
        self.incoming.handle(source_hex, text, timestamp)

    def _on_parsed_incoming(self, msg) -> None:
        """Forward a parsed IncomingMessage to the user-facing callback."""
        if self._incoming_cb is not None:
            try:
                self._incoming_cb(msg)
            except Exception:
                log.exception("incoming callback raised")

    def _send_ack(self, alert_id: str, source_hex: str) -> None:
        """Receiver side: ack an inbound app-to-app alert back to its sender."""
        if self.lxmf is None:
            log.warning("cannot send ack: daemon not started")
            return
        self.lxmf.send_message(source_hex, encode_ack(alert_id))

    def _on_inbound_ack(self, alert_id: str, source_hex: str) -> None:
        """Sender side: a recipient acked our alert -> ACKED."""
        self.ack.on_ack(alert_id, source_hex)

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

    def _get_current_fix(self):
        """One-shot GPS fix for PanicEngine (None if no fix source set)."""
        if self.geo is None:
            return None
        try:
            return self.geo.one_shot()
        except Exception:
            return None

    def _fire_trigger(self, trigger: str) -> None:
        """HardwareKeyManager fire callback -> PanicEngine.fire."""
        try:
            self.panic.fire(trigger)
        except Exception:
            log.exception("panic fire failed for trigger %r", trigger)

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