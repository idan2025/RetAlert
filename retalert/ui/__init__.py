"""RetAlert UI layer.

``controller.AppController`` is a UI-agnostic façade over ``EmergencyDaemon``
(no Kivy import — unit-testable). The Kivy app in ``main.py`` and the desktop
shell talk to it; they never touch RNS/LXMF directly.
"""
from .controller import AppController

__all__ = ["AppController"]
