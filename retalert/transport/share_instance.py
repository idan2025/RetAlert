"""SharedInstance — attach to a host app's shared RNS instance.

Build step: 12. See PROMPT.md § Instance mode, § Decisions #4.
Supported hosts: Sideband, Columba, MeshChat, MeshChatX (Linux). Reuses the
host's identity, announces, and interfaces — no second rnsd. Stub for now;
step 1 runs standalone only.
"""


class SharedInstanceAttach:
    """Attaches RetAlert to a running host app's RNS share-instance interface."""

    HOSTS = ("sideband", "columba", "meshchat", "meshchatx")

    def __init__(self, host):
        if host.lower() not in self.HOSTS:
            raise ValueError(f"unknown host: {host}")
        self.host = host.lower()

    def attach(self):
        raise NotImplementedError("SharedInstanceAttach.attach — build step 12")

    def detach(self):
        raise NotImplementedError("SharedInstanceAttach.detach — build step 12")