"""Local JSON persistence for RetAlert.

Step 1 implements Contacts (a simple address book of RNS/LXMF destination
hashes + display names). Presets is a stub holder for build step 10.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Optional


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


class Settings:
    """User receive-side settings: "receive only from contacts" toggle +
    per-sender allow/deny lists (PROMPT.md § Incoming alerts).

    Persisted to JSON. Allowlist takes precedence over denylist; both take
    precedence over the receive-only-from-contacts toggle.
    """

    def __init__(self, path: Path):
        self.path = Path(path)
        self.receive_only_from_contacts: bool = True
        self.allowlist: set[str] = set()
        self.denylist: set[str] = set()
        self._load()

    def _load(self) -> None:
        if not self.path.exists():
            return
        try:
            data = json.loads(self.path.read_text("utf-8"))
        except (json.JSONDecodeError, OSError):
            return
        self.receive_only_from_contacts = bool(data.get("receive_only_from_contacts", True))
        self.allowlist = {h.lower().strip() for h in data.get("allowlist", [])}
        self.denylist = {h.lower().strip() for h in data.get("denylist", [])}

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "receive_only_from_contacts": self.receive_only_from_contacts,
            "allowlist": sorted(self.allowlist),
            "denylist": sorted(self.denylist),
        }
        self.path.write_text(json.dumps(payload, indent=2), "utf-8")

    def set_receive_only_from_contacts(self, enabled: bool) -> None:
        self.receive_only_from_contacts = bool(enabled)
        self._save()

    def allow(self, hash_hex: str) -> None:
        self.allowlist.add(hash_hex.lower().strip())
        self.denylist.discard(hash_hex.lower().strip())
        self._save()

    def deny(self, hash_hex: str) -> None:
        self.denylist.add(hash_hex.lower().strip())
        self.allowlist.discard(hash_hex.lower().strip())
        self._save()

    def forget(self, hash_hex: str) -> None:
        h = hash_hex.lower().strip()
        self.allowlist.discard(h)
        self.denylist.discard(h)
        self._save()


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


@dataclass
class Group:
    """An ad-hoc group: a named subset of contact destination hashes.

    Per PROMPT.md § Recipients & contacts, a group is just a named
    contact-subset — alerts fan out to each member as an individual LXMF
    destination. No persistent group destination or membership token.
    """
    name: str
    members: List[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {"name": self.name, "members": list(self.members)}


class Groups:
    """JSON-backed named groups of destination hashes, keyed by group name."""

    def __init__(self, path: Path):
        self.path = Path(path)
        self._groups: dict[str, list[str]] = {}
        self._load()

    def _load(self) -> None:
        if self.path.exists():
            try:
                data = json.loads(self.path.read_text("utf-8"))
            except (json.JSONDecodeError, OSError):
                data = {}
            self._groups = {
                g["name"]: [h.lower().strip() for h in g.get("members", [])]
                for g in data.get("groups", [])
            }

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"groups": [Group(n, m).as_dict()
                              for n, m in self._groups.items()]}
        self.path.write_text(json.dumps(payload, indent=2), "utf-8")

    def create(self, name: str, members: List[str]) -> bool:
        """Create a new group. Returns False if the name already exists."""
        name = name.strip()
        if name in self._groups:
            return False
        self._groups[name] = [h.lower().strip() for h in members]
        self._save()
        return True

    def remove(self, name: str) -> bool:
        existed = name in self._groups
        self._groups.pop(name, None)
        if existed:
            self._save()
        return existed

    def add_member(self, name: str, hash_hex: str) -> bool:
        """Add a member to a group (idempotent). Returns False if group missing."""
        name = name.strip()
        if name not in self._groups:
            return False
        hash_hex = hash_hex.lower().strip()
        if hash_hex not in self._groups[name]:
            self._groups[name].append(hash_hex)
            self._save()
        return True

    def remove_member(self, name: str, hash_hex: str) -> bool:
        name = name.strip()
        if name not in self._groups:
            return False
        hash_hex = hash_hex.lower().strip()
        if hash_hex in self._groups[name]:
            self._groups[name].remove(hash_hex)
            self._save()
            return True
        return False

    def list(self) -> List[Group]:
        return [Group(n, m) for n, m in self._groups.items()]

    def get(self, name: str) -> Optional[Group]:
        name = name.strip()
        members = self._groups.get(name)
        return Group(name, list(members)) if members is not None else None

    def members(self, name: str) -> List[str]:
        g = self.get(name)
        return g.members if g is not None else []