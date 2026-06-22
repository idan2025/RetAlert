"""PresetResolver — maps a trigger to a preset config bundle.

Build step: 10. See PROMPT.md § Alert payload, § Triggers, § Decisions.
A preset bundles: recipient set, payload toggles (text/severity/GPS/live-share/
audio/photo), retry policy, fan-out mode, min interface tier.
"""


class Preset:
    """A saved emergency preset. Schema finalised in build step 10."""

    def __init__(self, name, recipients=None, severity="help", payload=None,
                 fan_out="critical", min_tier="low", retry=None):
        self.name = name
        self.recipients = recipients or []
        self.severity = severity
        self.payload = payload or {}
        self.fan_out = fan_out
        self.min_tier = min_tier
        self.retry = retry or {}


class PresetResolver:
    """Looks up a preset by trigger key and returns a resolved Preset."""

    def __init__(self, storage):
        self.storage = storage

    def resolve(self, trigger):
        """Return the Preset mapped to ``trigger``."""
        raise NotImplementedError("PresetResolver.resolve — build step 10")