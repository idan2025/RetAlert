"""PanicEngine + PresetResolver — trigger -> preset -> dispatched alert.

Build step: 10 (backend). See PROMPT.md § PanicEngine / PresetResolver and
§ Alert payload.

PanicEngine resolves a named trigger to a Preset (via PresetResolver),
builds an Alert from it (expanding a group to its members, attaching a
one-shot GPS fix to the text if the preset requests it), dispatches it via
the daemon's send path, and optionally starts live-share when the preset
wants ``gps_live``. Hold-to-confirm / hardware-key arming is UI-side; this
engine just dispatches a confirmed trigger.

Dedup: if the same preset is fired again while its alert is still active
(unacked), the engine returns the existing alert id instead of re-sending.
"""
from __future__ import annotations

import logging
import time
from typing import Callable, Optional

from .alert import Alert
from .preset import Preset, PresetStore
from .geo_tracker import Fix

log = logging.getLogger("retalert.panic")


class PresetResolver:
    """Maps a trigger name to a Preset.

    Resolution order: exact preset name match; if no preset matches and a
    preset named ``default`` exists, use it; else None.
    """

    DEFAULT_NAME = "default"

    def __init__(self, store: PresetStore):
        self.store = store

    def resolve(self, trigger: str) -> Optional[Preset]:
        if not trigger:
            return self.store.by_name(self.DEFAULT_NAME)
        p = self.store.by_name(trigger)
        if p is not None:
            return p
        return self.store.by_name(self.DEFAULT_NAME)


class PanicEngine:
    """Dispatches confirmed triggers as alerts."""

    # Dedup window: a re-fire of the same preset within this many seconds of
    # an active (unacked) alert returns the existing alert id.
    DEDUP_WINDOW_S = 30.0

    def __init__(self, store: PresetStore, groups, send_alert_fn: Callable[[Alert], Alert],
                 expand_group_fn: Callable[[str], list],
                 start_live_share_fn: Optional[Callable[[str, float], None]] = None,
                 get_fix_fn: Optional[Callable[[], Optional[Fix]]] = None):
        self.resolver = PresetResolver(store)
        self.groups = groups
        self._send_alert = send_alert_fn
        self._expand_group = expand_group_fn
        self._start_live_share = start_live_share_fn
        self._get_fix = get_fix_fn
        self._active: dict[str, tuple[str, float]] = {}  # preset_id -> (alert_id, fired_at)

    # -- dispatch ------------------------------------------------------

    def fire(self, trigger: str = "default") -> Optional[Alert]:
        """Fire a confirmed trigger. Returns the dispatched Alert, or None if
        no preset resolved / deduped."""
        preset = self.resolver.resolve(trigger)
        if preset is None:
            log.warning("no preset for trigger %r (and no default)", trigger)
            return None

        # Dedup: still-active alert for this preset within the window.
        active = self._active.get(preset.id)
        if active is not None:
            alert_id, fired_at = active
            if time.monotonic() - fired_at < self.DEDUP_WINDOW_S:
                log.info("dedup: preset %s already active (%s)", preset.id, alert_id)
                return None

        recipients = self._recipients_for(preset)
        if not recipients:
            log.warning("preset %s has no recipients", preset.id)
            return None

        text = preset.text
        fix = None
        if preset.payload.get("gps_oneshot") and self._get_fix is not None:
            try:
                fix = self._get_fix()
            except Exception:
                log.exception("gps one-shot failed")
        if fix is not None:
            text = (text + " " if text else "") + fix.geo_uri

        alert = Alert(
            severity=preset.severity,
            text=text,
            recipients=recipients,
            payload=dict(preset.payload),
            retry_interval=preset.retry_interval,
            max_attempts=preset.max_attempts,
            fan_out=preset.fan_out,
        )
        self._send_alert(alert)
        self._active[preset.id] = (alert.alert_id, time.monotonic())

        # gps_live: start live-share to each recipient (throttled if LoRa).
        if preset.payload.get("gps_live") and self._start_live_share is not None:
            interval = preset.lora_throttle if preset.lora_throttle else 5.0
            for r in recipients:
                try:
                    self._start_live_share(r, float(interval))
                except Exception:
                    log.exception("live-share start failed for %s", r)
        return alert

    def _recipients_for(self, preset: Preset) -> list[str]:
        if preset.group:
            return self._expand_group(preset.group)
        return [h.lower().strip() for h in preset.recipients]

    # -- ack feedback (clears dedup) -----------------------------------

    def on_alert_done(self, alert_id: str) -> None:
        """Clear dedup state once an alert is fully acked/failed."""
        stale = [pid for pid, (aid, _) in self._active.items() if aid == alert_id]
        for pid in stale:
            self._active.pop(pid, None)