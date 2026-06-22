"""SharedInstance — attach RetAlert to a host app's RNS instance.

Build step: 12. See PROMPT.md § Shared-instance. Supported hosts:
Sideband, Columba, MeshChat, MeshChatX (Linux). The host app exposes its
Reticulum instance; RetAlert connects as a client, reusing the host's
announces and interfaces instead of running its own ``rnsd``.

Two attach modes:
  * ``local``  — connect to a shared instance on this machine via RNS's
    built-in local unix-socket sharing (``share_instance = Yes``). This is
    what Sideband/Columba offer in-process on the same host.
  * ``tcp``    — connect to a remote host's TCP server interface
    (``TCPClientInterface`` with ``target_host``/``target_port``). Set
    ``share_instance = No`` so we don't also try the local socket.

Optionally reuse the host's identity by pointing ``identity_path`` at the
host's identity file (the host must grant read access).

The manager writes ``shared_instance.json`` and regenerates the instance's
RNS config file (``rns/config``) so the daemon picks up the host interface
on next start. ``detach()`` restores the standalone default config.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Optional

from ..config import DEFAULT_RNS_CONFIG_TEMPLATE


HOSTS = ("sideband", "columba", "meshchat", "meshchatx")


@dataclass
class SharedInstanceConfig:
    enabled: bool = False
    mode: str = "local"          # local | tcp
    host: Optional[str] = None
    port: Optional[int] = None
    host_app: Optional[str] = None   # sideband | columba | meshchat | meshchatx
    identity_path: Optional[str] = None
    instance_name: str = "default"

    def as_dict(self) -> dict:
        return asdict(self)


class SharedInstanceManager:
    """Loads/saves the shared-instance config and renders the RNS config."""

    def __init__(self, config_path: Path, rns_config_file: Path,
                 default_template: Path = DEFAULT_RNS_CONFIG_TEMPLATE):
        self.config_path = Path(config_path)
        self.rns_config_file = Path(rns_config_file)
        self.default_template = Path(default_template)
        self.cfg = self._load()

    # -- persistence ----------------------------------------------------

    def _load(self) -> SharedInstanceConfig:
        if not self.config_path.exists():
            return SharedInstanceConfig()
        try:
            data = json.loads(self.config_path.read_text("utf-8"))
        except (json.JSONDecodeError, OSError):
            return SharedInstanceConfig()
        return SharedInstanceConfig(
            enabled=bool(data.get("enabled", False)),
            mode=data.get("mode", "local"),
            host=data.get("host"),
            port=data.get("port"),
            host_app=data.get("host_app"),
            identity_path=data.get("identity_path"),
            instance_name=data.get("instance_name", "default"),
        )

    def _save(self) -> None:
        self.config_path.parent.mkdir(parents=True, exist_ok=True)
        self.config_path.write_text(json.dumps(self.cfg.as_dict(), indent=2),
                                    "utf-8")

    # -- attach / detach ------------------------------------------------

    def attach_local(self, host_app: Optional[str] = None,
                     identity_path: Optional[str] = None) -> None:
        """Attach to a shared instance on this machine (local unix socket)."""
        self.cfg = SharedInstanceConfig(
            enabled=True, mode="local", host_app=host_app,
            identity_path=identity_path, instance_name="default",
        )
        self._save()
        self._render()

    def attach_tcp(self, host: str, port: int,
                   host_app: Optional[str] = None,
                   identity_path: Optional[str] = None) -> None:
        """Attach to a remote host's TCP server interface."""
        self.cfg = SharedInstanceConfig(
            enabled=True, mode="tcp", host=host, port=port, host_app=host_app,
            identity_path=identity_path, instance_name="default",
        )
        self._save()
        self._render()

    def detach(self) -> None:
        """Restore standalone mode (default RNS config)."""
        self.cfg = SharedInstanceConfig(enabled=False)
        self._save()
        # Restore the bundled default config.
        if self.default_template.exists():
            self.rns_config_file.parent.mkdir(parents=True, exist_ok=True)
            self.rns_config_file.write_text(
                self.default_template.read_text("utf-8"), "utf-8")

    # -- render RNS config ----------------------------------------------

    def _render(self) -> None:
        """Write the RNS config matching the current attach mode."""
        self.rns_config_file.parent.mkdir(parents=True, exist_ok=True)
        if not self.cfg.enabled or self.cfg.mode == "local":
            # Local shared instance: rely on RNS built-in sharing.
            lines = [
                "# RetAlert RNS config (shared-instance: local)",
                "[reticulum]",
                "  enable_transport = False",
                "  share_instance = Yes",
                f"  instance_name = {self.cfg.instance_name}",
                "",
                "[logging]",
                "  loglevel = 3",
                "",
                "[interfaces]",
                "  # Local shared instance; no own interfaces.",
            ]
        else:
            host = self.cfg.host or "127.0.0.1"
            port = self.cfg.port or 49300
            lines = [
                "# RetAlert RNS config (shared-instance: tcp client)",
                "[reticulum]",
                "  enable_transport = False",
                "  share_instance = No",
                f"  instance_name = {self.cfg.instance_name}",
                "",
                "[logging]",
                "  loglevel = 3",
                "",
                "[interfaces]",
                "  [[Shared Host]]",
                "    type = TCPClientInterface",
                "    enabled = Yes",
                f"    target_host = {host}",
                f"    target_port = {port}",
            ]
        self.rns_config_file.write_text("\n".join(lines) + "\n", "utf-8")

    # -- query ----------------------------------------------------------

    def status(self) -> SharedInstanceConfig:
        return self.cfg