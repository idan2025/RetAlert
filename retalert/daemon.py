"""EmergencyDaemon — owns the RNS stack, identity, and LXMF transport.

Step 1: a thin orchestrator. Later build steps attach PanicEngine,
AckTracker, RetryQueue, GeoTracker, MediaChannel, TransportIntelligence
(see core/ stubs + PROMPT.md § Suggested build order).
"""
from __future__ import annotations

import logging
from typing import Callable, Optional

import RNS

from .config import AppConfig
from .storage import Contacts, Presets
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

    def stop(self) -> None:
        """Best-effort shutdown."""
        # RNS has no explicit teardown API; rely on process exit / daemon threads.
        log.info("daemon stopping")

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