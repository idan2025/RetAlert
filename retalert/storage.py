"""Local JSON persistence for RetAlert.

Step 1 implements Contacts (a simple address book of RNS/LXMF destination
hashes + display names). Presets is a stub holder for build step 10.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import List


@dataclass
class Contact:
    hash: str
    name: str

    def as_dict(self) -> dict:
        return {"hash": self.hash, "name": self.name}


class Contacts:
    """A JSON-backed contact list keyed by destination hash.

    Adding an existing hash updates its name in place (idempotent, no dup).
    """

    def __init__(self, path: Path):
        self.path = Path(path)
        self._entries: dict[str, str] = {}
        self._load()

    def _load(self) -> None:
        if self.path.exists():
            try:
                data = json.loads(self.path.read_text("utf-8"))
            except (json.JSONDecodeError, OSError):
                data = {}
            self._entries = {e["hash"]: e["name"] for e in data.get("contacts", [])}

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"contacts": [Contact(h, n).as_dict() for h, n in self._entries.items()]}
        self.path.write_text(json.dumps(payload, indent=2), "utf-8")

    def add(self, hash_hex: str, name: str) -> None:
        hash_hex = hash_hex.lower().strip()
        self._entries[hash_hex] = name
        self._save()

    def remove(self, hash_hex: str) -> bool:
        hash_hex = hash_hex.lower().strip()
        existed = hash_hex in self._entries
        self._entries.pop(hash_hex, None)
        if existed:
            self._save()
        return existed

    def list(self) -> List[Contact]:
        return [Contact(h, n) for h, n in self._entries.items()]

    def get(self, hash_hex: str):
        hash_hex = hash_hex.lower().strip()
        name = self._entries.get(hash_hex)
        return Contact(hash_hex, name) if name is not None else None


class Presets:
    """Stub preset store. Schema finalised in build step 10."""

    def __init__(self, path: Path):
        self.path = Path(path)

    def list(self) -> list:
        if not self.path.exists():
            return []
        try:
            return json.loads(self.path.read_text("utf-8")).get("presets", [])
        except (json.JSONDecodeError, OSError):
            return []

    def save(self, presets: list) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps({"presets": presets}, indent=2), "utf-8")