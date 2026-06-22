"""PresetResolver — re-export from preset.py + panic_engine.py.

Historical stub file; the real implementations live in
``retalert.core.preset`` (Preset, PresetStore) and
``retalert.core.panic_engine`` (PresetResolver). Re-exported here so
historical imports keep working.
"""
from .preset import Preset, PresetStore
from .panic_engine import PresetResolver, PanicEngine

__all__ = ["Preset", "PresetStore", "PresetResolver", "PanicEngine"]