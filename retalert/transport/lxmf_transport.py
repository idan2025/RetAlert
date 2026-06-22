"""LXMF transport — wraps lxmf.LXMRouter for announce / send / receive.

Step 1: text-only LXMF messages to a single contact. Lands in Sideband /
Columba / MeshChat / MeshChatX inboxes too (they all speak LXMF). See
PROMPT.md § Alert transport.
"""
from __future__ import annotations

import threading
from pathlib import Path
from typing import Callable, Optional

import RNS
import LXMF

APP_NAME = LXMF.APP_NAME  # "lxmf"


def _hash_bytes(hex_str: str) -> bytes:
    """Parse a destination hash from hex (with or without ``:`` delimiters)."""
    clean = hex_str.strip().replace(":", "")
    return bytes.fromhex(clean)


class LXMFTransport:
    """Owns one LXMRouter bound to our identity. Announces a delivery
    destination, sends outbound LXMF messages, and surfaces inbound messages
    via a callback."""

    def __init__(self, identity: "RNS.Identity", storage_path: Path,
                 display_name: str = "RetAlert"):
        self.identity = identity
        self.storage_path = str(storage_path)
        self.display_name = display_name
        self.router = LXMF.LXMRouter(
            identity=identity,
            storagepath=self.storage_path,
            autopeer=True,
        )
        self._delivery_hash: Optional[bytes] = None
        self._incoming_cb: Optional[Callable[[str, str, float], None]] = None
        self._job_thread: Optional[threading.Thread] = None

    # -- setup ----------------------------------------------------------

    def register(self) -> None:
        """Register our delivery identity + inbound callback. Must be called
        once after RNS.Reticulum is up."""
        self.router.register_delivery_identity(self.identity, display_name=self.display_name)
        self.router.register_delivery_callback(self._on_inbound)
        # Our delivery destination hash = the one key in delivery_destinations.
        self._delivery_hash = next(iter(self.router.delivery_destinations.keys()))

    @property
    def delivery_hash(self) -> bytes:
        if self._delivery_hash is None:
            raise RuntimeError("LXMFTransport.register() not called yet")
        return self._delivery_hash

    @property
    def delivery_hash_hex(self) -> str:
        return RNS.hexrep(self.delivery_hash, delimit=False)

    def set_incoming_callback(self, cb: Callable[[str, str, float], None]) -> None:
        """``cb(source_hash_hex, text, timestamp)`` is called for each inbound
        LXMF message."""
        self._incoming_cb = cb

    # -- loop -----------------------------------------------------------

    def start(self) -> None:
        """Start the router job loop in a background thread."""
        if self._job_thread and self._job_thread.is_alive():
            return
        self._job_thread = threading.Thread(target=self.router.jobloop, daemon=True)
        self._job_thread.start()

    def announce(self) -> None:
        """Announce our delivery destination on all enabled interfaces."""
        self.router.announce(self.delivery_hash)

    # -- send -----------------------------------------------------------

    def send_message(self, dest_hash_hex: str, text: str,
                     on_delivered: Optional[Callable[[], None]] = None,
                     on_failed: Optional[Callable[[], None]] = None):
        """Send a text LXMF message to ``dest_hash_hex``.

        Requires the remote's announce to have been heard (RNS.Identity.recall
        succeeds). Returns the LXMessage. Delivery/failed callbacks fire
        asynchronously once the router job loop is running.
        """
        dest_hash = _hash_bytes(dest_hash_hex)
        remote_identity = RNS.Identity.recall(dest_hash)
        if remote_identity is None:
            raise LookupError(
                f"no known announce for {dest_hash_hex}; wait for the peer to "
                f"announce (run `serve`) before sending"
            )

        destination = RNS.Destination(
            remote_identity, RNS.Destination.OUT, RNS.Destination.SINGLE,
            APP_NAME, "delivery",
        )
        source = RNS.Destination(
            self.identity, RNS.Destination.OUT, RNS.Destination.SINGLE,
            APP_NAME, "delivery",
        )

        lxm = LXMF.LXMessage(destination, source, content="")
        lxm.set_title_from_string("RetAlert")
        lxm.set_content_from_string(text)

        def _delivered(_lxm):
            if on_delivered:
                on_delivered()

        def _failed(_lxm):
            if on_failed:
                on_failed()

        lxm.register_delivery_callback(_delivered)
        lxm.register_failed_callback(_failed)

        self.router.handle_outbound(lxm)
        return lxm

    # -- receive --------------------------------------------------------

    def _on_inbound(self, message) -> None:
        try:
            source_hex = RNS.hexrep(message.get_source().hash, delimit=False)
            text = message.content_as_string() or ""
            timestamp = float(getattr(message, "timestamp", 0.0) or 0.0)
        except Exception:
            return
        if self._incoming_cb:
            self._incoming_cb(source_hex, text, timestamp)