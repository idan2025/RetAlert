"""RNSLink — raw RNS Link for live audio/photo streaming.

Build step: 13. See PROMPT.md § Alert transport. LXMF carries text/GPS/ack;
this carries streamed media. Stub for now.
"""


class RNSLink:
    """A raw RNS Link to a recipient for chunked media transfer."""

    def __init__(self, destination_hash, identity):
        self.destination_hash = destination_hash
        self.identity = identity

    def establish(self):
        raise NotImplementedError("RNSLink.establish — build step 13")

    def send_chunk(self, chunk):
        raise NotImplementedError("RNSLink.send_chunk — build step 13")

    def close(self):
        raise NotImplementedError("RNSLink.close — build step 13")