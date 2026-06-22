"""AckTracker — per-recipient alert state: sent / delivered / acked / replied.

Build step: 2. See PROMPT.md § Acknowledgement protocol.
"""


class AckTracker:
    """Tracks delivery + acknowledgement state for each outgoing alert, per
    recipient. Drives RetryQueue re-sends until ack or policy exhausted."""

    def __init__(self):
        self._state = {}

    def track(self, alert_id, recipient_hash):
        raise NotImplementedError("AckTracker.track — build step 2")

    def on_delivered(self, alert_id, recipient_hash):
        raise NotImplementedError("AckTracker.on_delivered — build step 2")

    def on_ack(self, alert_id, recipient_hash, reply=None):
        raise NotImplementedError("AckTracker.on_ack — build step 2")