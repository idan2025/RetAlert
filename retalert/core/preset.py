"""Preset — a saved emergency configuration bundle.

Build step: 10. See PROMPT.md § Alert payload and § PanicEngine/
PresetResolver. A preset bundles everything needed to fire an emergency
from a single trigger: recipients (or a saved group), payload toggles
(text / gps one-shot / gps live / audio / photo), severity, retry policy,
and Hail Mary fan-out mode.

PanicEngine resolves a trigger -> preset -> Alert and dispatches it
(``core/panic_engine.py``). The on-screen panic button + preset editor UI
is the Kivy step; the backend here is UI-agnostic and CLI-testable.
"""
from __future__ import annotations

import json
import uuid
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Dict, List, Optional

PAYLOAD_CLASSES = ("text", "gps_oneshot", "gps_live", "photo", "audio")


@dataclass
class Preset:
    """One saved emergency preset."""
    id: str = ""
    name: str = ""
    severity: str = "help"
    text: str = ""                       # message template (may be empty)
    recipients: List[str] = field(default_factory=list)  # destination hashes
    group: Optional[str] = None          # saved group name (expands to members)
    payload: Dict[str, bool] = field(default_factory=lambda: {"text": True})
    retry_interval: float = 3.0
    max_attempts: int = 0
    fan_out: str = "critical"            # off | critical | all
    lora_throttle: Optional[float] = None  # gps_live LoRa throttle (None=60 default)

    def __post_init__(self):
        if not self.id:
            self.id = uuid.uuid4().hex[:16]
        # Normalise payload keys to the known set, defaulting text on if empty.
        clean = {}
        for k in PAYLOAD_CLASSES:
            clean[k] = bool(self.payload.get(k, False)) if self.payload else False
        if not any(clean.values()):
            clean["text"] = True
        self.payload = clean

    def as_dict(self) -> dict:
        d = asdict(self)
        return d

    @classmethod
    def from_dict(cls, d: dict) -> "Preset":
        return cls(
            id=d.get("id", ""),
            name=d.get("name", ""),
            severity=d.get("severity", "help"),
            text=d.get("text", ""),
            recipients=list(d.get("recipients", [])),
            group=d.get("group"),
            payload=dict(d.get("payload", {})),
            retry_interval=float(d.get("retry_interval", 3.0)),
            max_attempts=int(d.get("max_attempts", 0)),
            fan_out=d.get("fan_out", "critical"),
            lora_throttle=d.get("lora_throttle"),
        )


class PresetStore:
    """JSON-backed preset collection, keyed by preset id."""

    def __init__(self, path: Path):
        self.path = Path(path)
        self._presets: dict[str, Preset] = {}
        self._load()

    def _load(self) -> None:
        if not self.path.exists():
            return
        try:
            data = json.loads(self.path.read_text("utf-8"))
        except (json.JSONDecodeError, OSError):
            return
        for d in data.get("presets", []):
            try:
                p = Preset.from_dict(d)
                self._presets[p.id] = p
            except Exception:
                continue

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"presets": [p.as_dict() for p in self._presets.values()]}
        self.path.write_text(json.dumps(payload, indent=2), "utf-8")

    def put(self, preset: Preset) -> Preset:
        """Insert or replace a preset by id."""
        self._presets[preset.id] = preset
        self._save()
        return preset

    def remove(self, preset_id: str) -> bool:
        existed = preset_id in self._presets
        self._presets.pop(preset_id, None)
        if existed:
            self._save()
        return existed

    def get(self, preset_id: str) -> Optional[Preset]:
        return self._presets.get(preset_id)

    def by_name(self, name: str) -> Optional[Preset]:
        name = name.strip()
        for p in self._presets.values():
            if p.name == name:
                return p
        return None

    def list(self) -> List[Preset]:
        return list(self._presets.values())