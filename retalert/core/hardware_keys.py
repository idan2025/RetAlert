"""HardwareKeyManager — dynamic hardware-key combos -> preset triggers.

Build step: 11. See PROMPT.md § Hardware key mapping.

The user chooses key combos (Android accessibility service capturing
volume-down x3 / power x5, or Android Emergency SOS integration, or a
desktop global hotkey) and maps each combo to a preset trigger. The
on-screen + platform capture is UI/Android-side; this module is the
testable core:

  * register a combo (an ordered tuple of key tokens) -> trigger name
  * feed observed key presses; detect a completed combo
  * "arm then press" mode: a first combo arms the engine, a second (within
    an arm window) fires — prevents accidental full-volume alerts
  * dedup: a re-detected combo within a cooldown is ignored
  * on firing, calls ``fire_fn(trigger)`` (wired to PanicEngine.fire)

``KeyCaptureBackend`` is the platform seam: Android accessibility / desktop
hotkey libs plug in here (stub until the Kivy/service step).
"""
from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple


@dataclass
class KeyCombo:
    """An ordered sequence of key tokens that fires a trigger."""
    combo: Tuple[str, ...]
    trigger: str
    arm: bool = False        # this combo arms (does not fire) the engine


@dataclass
class _ArmState:
    armed_at: float = 0.0
    armed: bool = False


class HardwareKeyManager:
    """Maps detected key combos to preset triggers with arm/dedup logic."""

    DEFAULT_ARM_WINDOW_S = 5.0     # how long an arm stays valid
    DEFAULT_COOLDOWN_S = 5.0       # min gap between fires of the same combo

    def __init__(self, fire_fn: Callable[[str], object],
                 arm_window_s: float = DEFAULT_ARM_WINDOW_S,
                 cooldown_s: float = DEFAULT_COOLDOWN_S,
                 path: Optional[Path] = None):
        self.fire_fn = fire_fn
        self.arm_window_s = arm_window_s
        self.cooldown_s = cooldown_s
        self._path = Path(path) if path else None
        self._combos: List[KeyCombo] = []
        self._buffer: List[str] = []          # recent key tokens
        self._max_buffer = 8
        self._arm = _ArmState()
        self._last_fire: Dict[Tuple[str, ...], float] = {}
        self._load()

    # -- persistence ----------------------------------------------------

    def _load(self) -> None:
        if self._path is None or not self._path.exists():
            return
        try:
            data = json.loads(self._path.read_text("utf-8"))
        except (json.JSONDecodeError, OSError):
            return
        for e in data.get("combos", []):
            try:
                self._combos.append(KeyCombo(
                    combo=tuple(e["combo"]), trigger=e["trigger"],
                    arm=bool(e.get("arm", False))))
            except (KeyError, TypeError):
                continue

    def _save(self) -> None:
        if self._path is None:
            return
        self._path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"combos": [{"combo": list(c.combo), "trigger": c.trigger,
                               "arm": c.arm} for c in self._combos]}
        self._path.write_text(json.dumps(payload, indent=2), "utf-8")

    # -- registration ---------------------------------------------------

    def register(self, combo, trigger: str, arm: bool = False) -> None:
        if isinstance(combo, str):
            combo = (combo,)
        else:
            combo = tuple(combo)
        if not combo:
            raise ValueError("combo must be non-empty")
        # Replace any existing combo with the same sequence.
        self._combos = [c for c in self._combos if c.combo != combo]
        self._combos.append(KeyCombo(combo=combo, trigger=trigger, arm=arm))
        self._save()

    def unregister(self, combo) -> bool:
        combo = (combo,) if isinstance(combo, str) else tuple(combo)
        before = len(self._combos)
        self._combos = [c for c in self._combos if c.combo != combo]
        changed = len(self._combos) < before
        if changed:
            self._save()
        return changed

    def list_combos(self) -> List[KeyCombo]:
        return list(self._combos)

    def clear(self) -> None:
        self._combos.clear()
        self._buffer.clear()
        self._arm = _ArmState()
        self._save()

    # -- arm state ------------------------------------------------------

    @property
    def armed(self) -> bool:
        return self._arm.armed

    def arm(self) -> None:
        self._arm.armed = True
        self._arm.armed_at = time.monotonic()

    def disarm(self) -> None:
        self._arm.armed = False

    def _arm_expired(self) -> bool:
        if not self._arm.armed:
            return True
        return (time.monotonic() - self._arm.armed_at) > self.arm_window_s

    # -- detection ------------------------------------------------------

    def feed(self, key: str) -> Optional[str]:
        """Feed one observed key press. Returns the trigger fired, or None."""
        self._buffer.append(key)
        if len(self._buffer) > self._max_buffer:
            self._buffer = self._buffer[-self._max_buffer:]
        return self._check()

    def feed_sequence(self, keys) -> Optional[str]:
        """Feed several keys at once (e.g. a captured burst). Returns the
        last trigger fired, if any."""
        result = None
        for k in keys:
            r = self.feed(k)
            if r is not None:
                result = r
        return result

    def _check(self) -> Optional[str]:
        now = time.monotonic()
        for kc in self._combos:
            n = len(kc.combo)
            if len(self._buffer) < n:
                continue
            if tuple(self._buffer[-n:]) != kc.combo:
                continue
            # Matched. Arm combo: arm the engine, don't fire.
            if kc.arm:
                # Already armed: skip so a longer fire combo can still assemble
                # from the same key stream (otherwise the arm combo would keep
                # matching and clearing the buffer).
                if self._arm.armed and not self._arm_expired():
                    continue
                self.arm()
                self._buffer.clear()
                return None
            # Fire combo: only if armed (and arm not expired), or no arm
            # combos are registered at all (direct-fire mode).
            arm_required = any(c.arm for c in self._combos)
            if arm_required and (not self._arm.armed or self._arm_expired()):
                # Not armed (or arm expired): ignore the fire combo.
                self._buffer.clear()
                return None
            # Cooldown dedup.
            last = self._last_fire.get(kc.combo, 0.0)
            if now - last < self.cooldown_s:
                self._buffer.clear()
                return None
            self._last_fire[kc.combo] = now
            self._buffer.clear()
            self.disarm()  # a fire consumes the arm
            self.fire_fn(kc.trigger)
            return kc.trigger
        return None


class KeyCaptureBackend:
    """Platform seam for capturing hardware keys.

    Implementations (Android accessibility service / desktop global hotkey
    via pynput) feed observed keys into ``HardwareKeyManager.feed``. Stub
    until the Kivy/service build step.
    """

    def __init__(self, manager: HardwareKeyManager):
        self.manager = manager
        self._running = False

    def start(self) -> None:
        raise NotImplementedError("KeyCaptureBackend.start — Kivy/service step")

    def stop(self) -> None:
        self._running = False