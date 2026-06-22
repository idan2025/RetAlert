"""GeoTracker — one-shot GPS fix and live-share mode.

Build step: 5. See PROMPT.md § Map screen, § Transport intelligence (LoRa
GPS throttle interval: user-chosen 10s-3600s, default 60s, only when LoRa is
the sole up interface).
"""


class GeoTracker:
    """Produces GPS fixes. One-shot (small coords packet, any tier) or
    live-share (periodic updates, Medium+ tier, throttled on LoRa-only)."""

    def __init__(self):
        self._sharing = False

    def one_shot(self):
        """Return a single last-known GPS fix."""
        raise NotImplementedError("GeoTracker.one_shot — build step 5")

    def start_live_share(self, interval=None):
        raise NotImplementedError("GeoTracker.start_live_share — build step 5")

    def stop_live_share(self):
        raise NotImplementedError("GeoTracker.stop_live_share — build step 5")