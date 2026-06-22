"""RNS identity management (standalone mode).

Create or load a persisted RNS.Identity for this RetAlert instance. The
identity is saved to ``<storage>/identity`` and reused across runs so the
LXMF delivery destination hash stays stable.
"""
from __future__ import annotations

from pathlib import Path

import RNS


def load_or_create_identity(path: Path) -> "RNS.Identity":
    """Load an identity from ``path`` if present, else create + save a new one.

    Returns a RNS.Identity instance.
    """
    path = Path(path)
    if path.exists() and path.stat().st_size > 0:
        identity = RNS.Identity.from_file(str(path))
        if identity is not None:
            return identity
        # File existed but was unreadable; fall through to create a fresh one.

    identity = RNS.Identity()
    path.parent.mkdir(parents=True, exist_ok=True)
    identity.to_file(str(path))
    return identity


def identity_hash_hex(identity: "RNS.Identity") -> str:
    """Truncated hex of the identity's hash (RNS' canonical public form)."""
    return RNS.hexrep(identity.hash, delimit=False)


def full_hash_hex(identity: "RNS.Identity") -> str:
    """Full hex of the identity's hash."""
    return RNS.hexrep(identity.get_full_hash(), delimit=False)