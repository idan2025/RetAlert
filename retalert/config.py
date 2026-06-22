"""Path + storage-dir resolution for RetAlert.

Each RetAlert instance owns a storage dir (default ``~/.retalert``, override
with ``--storage``). Inside it: an RNS identity, an RNS config, contacts.json,
presets.json, and LXMF message store. On first init the bundled default RNS
config is copied in.
"""
from __future__ import annotations

import os
import shutil
from dataclasses import dataclass
from pathlib import Path

# Bundled default RNS config, shipped in the repo under config/.
PACKAGE_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RNS_CONFIG_TEMPLATE = PACKAGE_ROOT / "config" / "rns_default_config"

DEFAULT_STORAGE_DIR = Path(os.path.expanduser("~/.retalert"))


@dataclass(frozen=True)
class AppConfig:
    """Resolved filesystem paths for one RetAlert instance."""

    storage_dir: Path
    rns_config_dir: Path
    rns_config_file: Path
    identity_file: Path
    contacts_file: Path
    presets_file: Path
    alerts_file: Path
    lxmf_storage: Path

    @classmethod
    def resolve(cls, storage_dir: os.PathLike | str | None = None) -> "AppConfig":
        base = Path(storage_dir).expanduser() if storage_dir else DEFAULT_STORAGE_DIR
        rns_dir = base / "rns"
        return cls(
            storage_dir=base,
            rns_config_dir=rns_dir,
            rns_config_file=rns_dir / "config",
            identity_file=base / "identity",
            contacts_file=base / "contacts.json",
            presets_file=base / "presets.json",
            alerts_file=base / "alerts.json",
            lxmf_storage=base / "lxmf",
        )

    def ensure_dirs(self) -> None:
        self.storage_dir.mkdir(parents=True, exist_ok=True)
        self.rns_config_dir.mkdir(parents=True, exist_ok=True)
        self.lxmf_storage.mkdir(parents=True, exist_ok=True)

    def copy_default_rns_config(self, overwrite: bool = False) -> None:
        """Copy the bundled default RNS config into this instance's rns dir."""
        if self.rns_config_file.exists() and not overwrite:
            return
        if not DEFAULT_RNS_CONFIG_TEMPLATE.exists():
            raise FileNotFoundError(
                f"bundled default RNS config missing: {DEFAULT_RNS_CONFIG_TEMPLATE}"
            )
        shutil.copyfile(DEFAULT_RNS_CONFIG_TEMPLATE, self.rns_config_file)