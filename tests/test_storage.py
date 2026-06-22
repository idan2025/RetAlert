"""Smoke tests for RetAlert local storage (Contacts persistence)."""
import json
from pathlib import Path

from retalert.storage import Contacts


def test_contacts_roundtrip(tmp_path):
    store = Contacts(tmp_path / "contacts.json")
    store.add("0123456789abcdef0123456789abcdef", "Alice")
    store.add("fedcba9876543210fedcba9876543210", "Bob")

    # Reload from disk to verify persistence.
    store2 = Contacts(tmp_path / "contacts.json")
    names = {c.name for c in store2.list()}
    assert names == {"Alice", "Bob"}

    store2.remove("fedcba9876543210fedcba9876543210")
    store3 = Contacts(tmp_path / "contacts.json")
    assert [c.name for c in store3.list()] == ["Alice"]


def test_contacts_add_idempotent(tmp_path):
    store = Contacts(tmp_path / "contacts.json")
    store.add("aa" * 16, "Alice")
    store.add("aa" * 16, "Alice Renamed")  # same hash -> update name, no dup
    assert len(store.list()) == 1
    assert store.list()[0].name == "Alice Renamed"