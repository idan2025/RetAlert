"""PanicEngine — receives trigger events and dispatches via PresetResolver.

Build step: 10 (presets + on-screen panic button). See PROMPT.md § Triggers.
"""


class PanicEngine:
    """Handles incoming trigger events (UI button, hardware keys, dead-man),
    dedupes them, and resolves+fires the matching preset."""

    def __init__(self, daemon):
        self.daemon = daemon

    def fire(self, trigger):
        """Fire the preset mapped to ``trigger``.

        ``trigger`` identifies a preset key (e.g. "danger", "medical") or a
        hardware-key combo. Not implemented in step 1.
        """
        raise NotImplementedError("PanicEngine.fire — build step 10")