"""MediaChannel — live audio/photo over a raw RNS Link.

Build step: 13. See PROMPT.md § Alert transport, § Transport intelligence.
LXMF is not built for streaming, so media uses a raw RNS Link established
after the LXMF alert handshake. Gated to High/Medium tier only; refused on
Low (LoRa); queued when no qualifying interface is up. Quality caps are
dynamic per-tier.
"""


class MediaChannel:
    """Opens a RNS Link to recipient(s) and streams chunked audio frames /
    JPEG photo bursts."""

    def __init__(self, transport_intel=None):
        self.transport_intel = transport_intel

    def open_audio(self, recipients):
        raise NotImplementedError("MediaChannel.open_audio — build step 13")

    def open_photo(self, recipients):
        raise NotImplementedError("MediaChannel.open_photo — build step 13")

    def close(self):
        raise NotImplementedError("MediaChannel.close — build step 13")