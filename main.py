"""RetAlert Kivy app — Android-first on-the-go shell (also runs on desktop).

Thin UI over ``retalert.ui.AppController``: a big panic button, an interface/
tier status line, and an inbox screen to reply/ack received app-to-app alerts.
All RNS/LXMF work happens in the controller/daemon; network actions run off the
UI thread and marshal results back via ``Clock``.

Run on desktop:  ``python main.py``
Android APK:      built by Buildozer (see buildozer.spec).
"""
from __future__ import annotations

import threading

from kivy.app import App
from kivy.clock import Clock
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.popup import Popup
from kivy.uix.screenmanager import ScreenManager, Screen
from kivy.uix.scrollview import ScrollView
from kivy.uix.spinner import Spinner
from kivy.uix.textinput import TextInput

from retalert.core.alert import SEVERITIES
from retalert.ui import AppController


def _run_bg(fn, *args, on_done=None):
    """Run a (possibly blocking, network) call off the UI thread; deliver the
    result back on the Kivy main thread via Clock."""
    def worker():
        try:
            result, error = fn(*args), None
        except Exception as exc:  # surface to the UI rather than crashing
            result, error = None, exc
        if on_done is not None:
            Clock.schedule_once(lambda _dt: on_done(result, error), 0)
    threading.Thread(target=worker, daemon=True).start()


class HomeScreen(Screen):
    def __init__(self, ctl: AppController, **kw):
        super().__init__(**kw)
        self.ctl = ctl
        root = BoxLayout(orientation="vertical", padding=16, spacing=12)

        self.status = Label(text="starting…", size_hint_y=0.18,
                            halign="center", valign="middle")
        root.add_widget(self.status)

        self.panic_btn = Button(text="PANIC", font_size="40sp",
                                background_color=(0.8, 0.1, 0.1, 1))
        self.panic_btn.bind(on_release=self._on_panic)
        root.add_widget(self.panic_btn)

        self.flash = Label(text="", size_hint_y=0.12, halign="center")
        root.add_widget(self.flash)

        nav = BoxLayout(size_hint_y=0.16, spacing=8)
        for label, screen in (("Send", "send"), ("Inbox", "inbox"),
                              ("Sent", "outbox"), ("Presets", "presets")):
            b = Button(text=label)
            b.bind(on_release=lambda _w, s=screen: setattr(self.manager,
                                                           "current", s))
            nav.add_widget(b)
        root.add_widget(nav)

        self.add_widget(root)
        Clock.schedule_interval(self._refresh_status, 2)

    def _on_panic(self, *_):
        self.panic_btn.disabled = True
        self.flash.text = "firing…"

        def done(alert, error):
            self.panic_btn.disabled = False
            if error is not None:
                self.flash.text = f"error: {error}"
            elif alert is None:
                self.flash.text = "no preset to fire (configure one)"
            else:
                self.flash.text = f"sent alert {alert.alert_id or '(v0)'}"
        _run_bg(self.ctl.panic, on_done=done)

    def _refresh_status(self, _dt):
        if not self.ctl.started:
            self.status.text = "starting…"
            return
        st = self.ctl.status()
        up = [i for i in st["interfaces"] if i]
        chips = "  ".join(f"{i['tier']}:{i['name']}" for i in up) or "no interfaces"
        self.status.text = f"ready · {chips}"


class InboxScreen(Screen):
    def __init__(self, ctl: AppController, **kw):
        super().__init__(**kw)
        self.ctl = ctl
        root = BoxLayout(orientation="vertical", padding=10, spacing=8)

        bar = BoxLayout(size_hint_y=0.12, spacing=8)
        back = Button(text="< Home")
        back.bind(on_release=lambda *_: setattr(self.manager, "current", "home"))
        refresh = Button(text="Refresh")
        refresh.bind(on_release=lambda *_: self.reload())
        bar.add_widget(back)
        bar.add_widget(refresh)
        root.add_widget(bar)

        scroll = ScrollView()
        self.list_box = BoxLayout(orientation="vertical", size_hint_y=None,
                                  spacing=6, padding=2)
        self.list_box.bind(minimum_height=self.list_box.setter("height"))
        scroll.add_widget(self.list_box)
        root.add_widget(scroll)
        self.add_widget(root)

    def on_pre_enter(self, *_):
        self.reload()

    def reload(self):
        self.list_box.clear_widgets()
        entries = self.ctl.inbox()
        if not entries:
            self.list_box.add_widget(Label(text="(inbox empty)",
                                           size_hint_y=None, height=40))
            return
        for e in entries:
            self.list_box.add_widget(self._row(e))

    def _row(self, entry):
        row = BoxLayout(orientation="vertical", size_hint_y=None, height=110,
                        padding=4, spacing=2)
        head = f"[{entry.severity or '-'}] {entry.alert_id}"
        row.add_widget(Label(text=head, size_hint_y=None, height=24,
                             halign="left"))
        row.add_widget(Label(text=(entry.text or "")[:140], size_hint_y=None,
                             height=40, halign="left"))
        btns = BoxLayout(size_hint_y=None, height=40, spacing=6)
        rb = Button(text="Reply")
        rb.bind(on_release=lambda *_: self._reply_popup(entry.alert_id))
        ab = Button(text="Ack")
        ab.bind(on_release=lambda *_: _run_bg(self.ctl.ack, entry.alert_id))
        btns.add_widget(rb)
        btns.add_widget(ab)
        row.add_widget(btns)
        return row

    def _reply_popup(self, alert_id):
        box = BoxLayout(orientation="vertical", spacing=8, padding=8)
        ti = TextInput(hint_text="reply text", multiline=False)
        send = Button(text="Send reply", size_hint_y=0.4)
        box.add_widget(ti)
        box.add_widget(send)
        popup = Popup(title=f"Reply to {alert_id}", content=box,
                      size_hint=(0.9, 0.5))

        def do_send(*_):
            text = ti.text.strip()
            popup.dismiss()
            if text:
                _run_bg(self.ctl.reply, alert_id, text)
        send.bind(on_release=do_send)
        popup.open()


class SendScreen(Screen):
    def __init__(self, ctl: AppController, **kw):
        super().__init__(**kw)
        self.ctl = ctl
        root = BoxLayout(orientation="vertical", padding=12, spacing=8)

        bar = BoxLayout(size_hint_y=0.14)
        back = Button(text="< Home")
        back.bind(on_release=lambda *_: setattr(self.manager, "current", "home"))
        bar.add_widget(back)
        root.add_widget(bar)

        root.add_widget(Label(text="recipient (contact / group / hash)",
                              size_hint_y=0.1, halign="left"))
        self.target = TextInput(hint_text="Bob  ·  team  ·  <hex hash>",
                                multiline=False, size_hint_y=0.14)
        root.add_widget(self.target)

        self.severity = Spinner(text=SEVERITIES[0], values=list(SEVERITIES),
                                size_hint_y=0.12)
        root.add_widget(self.severity)

        self.body = TextInput(hint_text="message", size_hint_y=0.3)
        root.add_widget(self.body)

        self.send_btn = Button(text="Send", size_hint_y=0.16,
                               background_color=(0.1, 0.5, 0.9, 1))
        self.send_btn.bind(on_release=self._on_send)
        root.add_widget(self.send_btn)

        self.flash = Label(text="", size_hint_y=0.1)
        root.add_widget(self.flash)
        self.add_widget(root)

    def _on_send(self, *_):
        target = self.target.text.strip()
        text = self.body.text.strip()
        if not target or not text:
            self.flash.text = "need a recipient and a message"
            return
        self.send_btn.disabled = True
        self.flash.text = "sending…"

        def done(alert, error):
            self.send_btn.disabled = False
            if error is not None:
                self.flash.text = f"error: {error}"
            else:
                self.flash.text = f"sent {alert.alert_id or '(v0)'}"
                self.body.text = ""
        _run_bg(self.ctl.send_text, text, target, self.severity.text,
                on_done=done)


class OutboxScreen(Screen):
    """Alerts we have sent, with per-recipient ack/reply state."""

    def __init__(self, ctl: AppController, **kw):
        super().__init__(**kw)
        self.ctl = ctl
        root = BoxLayout(orientation="vertical", padding=10, spacing=8)

        bar = BoxLayout(size_hint_y=0.12, spacing=8)
        back = Button(text="< Home")
        back.bind(on_release=lambda *_: setattr(self.manager, "current", "home"))
        bar.add_widget(back)
        root.add_widget(bar)

        scroll = ScrollView()
        self.list_box = BoxLayout(orientation="vertical", size_hint_y=None,
                                  spacing=6, padding=2)
        self.list_box.bind(minimum_height=self.list_box.setter("height"))
        scroll.add_widget(self.list_box)
        root.add_widget(scroll)
        self.add_widget(root)

    def on_pre_enter(self, *_):
        self._refresh()
        self._ev = Clock.schedule_interval(lambda _dt: self._refresh(), 3)

    def on_pre_leave(self, *_):
        ev = getattr(self, "_ev", None)
        if ev is not None:
            ev.cancel()

    def _refresh(self):
        self.list_box.clear_widgets()
        alerts = self.ctl.sent_alerts()
        if not alerts:
            self.list_box.add_widget(Label(text="(nothing sent yet)",
                                           size_hint_y=None, height=40))
            return
        for a in alerts:
            summary = self.ctl.ack_summary(a.alert_id)
            states = "  ".join(f"{h[:6]}…={s}" for h, s in summary.items()) \
                or "(no recipients)"
            txt = f"[{a.severity}] {a.alert_id}\n{(a.text or '')[:80]}\n{states}"
            self.list_box.add_widget(Label(text=txt, size_hint_y=None,
                                           height=84, halign="left"))


class PresetsScreen(Screen):
    """Saved presets with one-tap fire (trigger -> alert dispatch)."""

    def __init__(self, ctl: AppController, **kw):
        super().__init__(**kw)
        self.ctl = ctl
        root = BoxLayout(orientation="vertical", padding=10, spacing=8)

        bar = BoxLayout(size_hint_y=0.12, spacing=8)
        back = Button(text="< Home")
        back.bind(on_release=lambda *_: setattr(self.manager, "current", "home"))
        refresh = Button(text="Refresh")
        refresh.bind(on_release=lambda *_: self._refresh())
        bar.add_widget(back)
        bar.add_widget(refresh)
        root.add_widget(bar)

        scroll = ScrollView()
        self.list_box = BoxLayout(orientation="vertical", size_hint_y=None,
                                  spacing=6, padding=2)
        self.list_box.bind(minimum_height=self.list_box.setter("height"))
        scroll.add_widget(self.list_box)
        root.add_widget(scroll)

        self.flash = Label(text="", size_hint_y=0.1)
        root.add_widget(self.flash)
        self.add_widget(root)

    def on_pre_enter(self, *_):
        self._refresh()

    def _refresh(self):
        self.list_box.clear_widgets()
        rows = self.ctl.preset_summaries()
        if not rows:
            self.list_box.add_widget(Label(text="(no presets configured)",
                                           size_hint_y=None, height=40))
            return
        for r in rows:
            self.list_box.add_widget(self._row(r))

    def _row(self, r):
        box = BoxLayout(size_hint_y=None, height=56, spacing=6)
        info = Label(text=f"{r['name']}  [{r['severity']}]\n"
                          f"fan-out={r['fan_out']}  to {r['recipients']}")
        fire = Button(text="Fire", size_hint_x=0.3,
                      background_color=(0.8, 0.2, 0.2, 1))
        fire.bind(on_release=lambda *_: self._fire(r["name"]))
        box.add_widget(info)
        box.add_widget(fire)
        return box

    def _fire(self, name):
        self.flash.text = f"firing {name}…"

        def done(alert, error):
            if error is not None:
                self.flash.text = f"error: {error}"
            elif alert is None:
                self.flash.text = f"{name}: nothing dispatched"
            else:
                self.flash.text = f"{name}: sent {alert.alert_id or '(v0)'}"
        _run_bg(self.ctl.panic, name, on_done=done)


class RetAlertApp(App):
    def build(self):
        self.title = "RetAlert"
        self.ctl = AppController()
        sm = ScreenManager()
        sm.add_widget(HomeScreen(self.ctl, name="home"))
        sm.add_widget(InboxScreen(self.ctl, name="inbox"))
        sm.add_widget(SendScreen(self.ctl, name="send"))
        sm.add_widget(OutboxScreen(self.ctl, name="outbox"))
        sm.add_widget(PresetsScreen(self.ctl, name="presets"))
        # Bring the daemon up off the UI thread so the window paints immediately.
        _run_bg(self.ctl.start)
        return sm

    def on_stop(self):
        try:
            self.ctl.stop()
        except Exception:
            pass


if __name__ == "__main__":
    RetAlertApp().run()
