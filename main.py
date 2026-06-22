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
from kivy.uix.gridlayout import GridLayout
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

        nav = GridLayout(cols=3, size_hint_y=0.24, spacing=6)
        for label, screen in (("Send", "send"), ("Inbox", "inbox"),
                              ("Sent", "outbox"), ("Presets", "presets"),
                              ("Map", "map"), ("Contacts", "contacts"),
                              ("Settings", "settings")):
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


class ContactsScreen(Screen):
    def __init__(self, ctl: AppController, **kw):
        super().__init__(**kw)
        self.ctl = ctl
        root = BoxLayout(orientation="vertical", padding=10, spacing=8)

        bar = BoxLayout(size_hint_y=0.12, spacing=8)
        back = Button(text="< Home")
        back.bind(on_release=lambda *_: setattr(self.manager, "current", "home"))
        bar.add_widget(back)
        root.add_widget(bar)

        add = BoxLayout(size_hint_y=0.14, spacing=6)
        self.name = TextInput(hint_text="name", multiline=False)
        self.hash = TextInput(hint_text="hex hash", multiline=False)
        addb = Button(text="Add", size_hint_x=0.3)
        addb.bind(on_release=self._add)
        add.add_widget(self.name)
        add.add_widget(self.hash)
        add.add_widget(addb)
        root.add_widget(add)

        scroll = ScrollView()
        self.list_box = BoxLayout(orientation="vertical", size_hint_y=None,
                                  spacing=4, padding=2)
        self.list_box.bind(minimum_height=self.list_box.setter("height"))
        scroll.add_widget(self.list_box)
        root.add_widget(scroll)
        self.add_widget(root)

    def on_pre_enter(self, *_):
        self._refresh()

    def _add(self, *_):
        name, h = self.name.text.strip(), self.hash.text.strip().replace(":", "")
        if name and h:
            self.ctl.add_contact(h, name)
            self.name.text = self.hash.text = ""
            self._refresh()

    def _refresh(self):
        self.list_box.clear_widgets()
        people = self.ctl.contacts()
        if not people:
            self.list_box.add_widget(Label(text="(no contacts)",
                                           size_hint_y=None, height=36))
            return
        for c in people:
            row = BoxLayout(size_hint_y=None, height=40, spacing=6)
            row.add_widget(Label(text=f"{c.name}  {c.hash[:10]}…"))
            rm = Button(text="Remove", size_hint_x=0.3)
            rm.bind(on_release=lambda _w, h=c.hash: (self.ctl.remove_contact(h),
                                                     self._refresh()))
            row.add_widget(rm)
            self.list_box.add_widget(row)


class SettingsScreen(Screen):
    def __init__(self, ctl: AppController, **kw):
        super().__init__(**kw)
        self.ctl = ctl
        root = BoxLayout(orientation="vertical", padding=12, spacing=10)

        bar = BoxLayout(size_hint_y=0.12, spacing=8)
        back = Button(text="< Home")
        back.bind(on_release=lambda *_: setattr(self.manager, "current", "home"))
        bar.add_widget(back)
        root.add_widget(bar)

        self.recv_btn = Button(size_hint_y=0.16)
        self.recv_btn.bind(on_release=self._toggle_recv)
        root.add_widget(self.recv_btn)

        self.units_btn = Button(size_hint_y=0.16)
        self.units_btn.bind(on_release=self._toggle_units)
        root.add_widget(self.units_btn)

        deny = BoxLayout(size_hint_y=0.14, spacing=6)
        self.deny_in = TextInput(hint_text="hex hash to allow/deny",
                                 multiline=False)
        ab = Button(text="Allow", size_hint_x=0.25)
        ab.bind(on_release=lambda *_: self._filter(self.ctl.allow))
        db = Button(text="Deny", size_hint_x=0.25)
        db.bind(on_release=lambda *_: self._filter(self.ctl.deny))
        deny.add_widget(self.deny_in)
        deny.add_widget(ab)
        deny.add_widget(db)
        root.add_widget(deny)

        self.summary = Label(text="", halign="left")
        root.add_widget(self.summary)
        self.add_widget(root)

    def on_pre_enter(self, *_):
        self._refresh()

    def _toggle_recv(self, *_):
        cur = self.ctl.settings_view()["receive_only"]
        self.ctl.set_receive_only(not cur)
        self._refresh()

    def _toggle_units(self, *_):
        self.ctl.set_distance_units(
            "mi" if self.ctl.distance_units() == "km" else "km")
        self._refresh()

    def _filter(self, fn):
        h = self.deny_in.text.strip().replace(":", "")
        if h:
            fn(h)
            self.deny_in.text = ""
            self._refresh()

    def _refresh(self):
        st = self.ctl.settings_view()
        self.recv_btn.text = ("Receive only from contacts: "
                              f"{'ON' if st['receive_only'] else 'OFF'}")
        self.units_btn.text = f"Distance units: {st['distance_units']}"
        self.summary.text = (f"allow ({len(st['allow'])}): "
                             f"{', '.join(h[:8] for h in st['allow']) or '-'}\n"
                             f"deny ({len(st['deny'])}): "
                             f"{', '.join(h[:8] for h in st['deny']) or '-'}")


class MapScreen(Screen):
    """Online map (selectable provider) with live-share peer markers, tap-to-
    follow real-time tracking (map re-centers on the followed peer as their
    fix updates; zoom stays where the user left it), a distance readout, and
    an offline download of the visible area within a chosen radius (MBTiles)."""

    def __init__(self, ctl: AppController, **kw):
        super().__init__(**kw)
        self.ctl = ctl
        from retalert.core.map_tiles import PROVIDERS, get_provider
        self._get_provider = get_provider
        self._peer_map = {}      # spinner label -> source hash
        self._offline_map = {}   # basename -> .mbtiles path
        self._markers = []
        self._dl = None          # (done, total) while a download runs
        root = BoxLayout(orientation="vertical", padding=6, spacing=6)

        bar = BoxLayout(size_hint_y=0.1, spacing=6)
        back = Button(text="< Home", size_hint_x=0.3)
        back.bind(on_release=lambda *_: setattr(self.manager, "current", "home"))
        self.provider = Spinner(
            text="osm", values=[p.key for p in PROVIDERS.values()])
        self.provider.bind(text=lambda *_: self._apply_provider())
        bar.add_widget(back)
        bar.add_widget(self.provider)
        root.add_widget(bar)

        try:
            from kivy_garden.mapview import MapView, MapMarker
            self._MapMarker = MapMarker
            self.mapview = MapView(zoom=11, lat=0.0, lon=0.0)
            root.add_widget(self.mapview)
            self._ok = True
        except Exception as exc:  # mapview not installed -> graceful fallback
            self.mapview = None
            self._ok = False
            root.add_widget(Label(text=f"map widget unavailable\n({exc})"))

        follow = BoxLayout(size_hint_y=0.1, spacing=6)
        self.peer = Spinner(text="(no peers)", values=[])
        self.follow_btn = Button(text="Follow", size_hint_x=0.3)
        self.follow_btn.bind(on_release=self._toggle_follow)
        follow.add_widget(self.peer)
        follow.add_widget(self.follow_btn)
        root.add_widget(follow)

        self.dist = Label(text="", size_hint_y=0.07)
        root.add_widget(self.dist)

        dl = BoxLayout(size_hint_y=0.12, spacing=6)
        self.radius = Spinner(text="10",
                              values=[str(r) for r in ctl.map_radius_options()])
        self.radius.bind(text=lambda *_: self._update_estimate())
        self.dl_btn = Button(text="Download area")
        self.dl_btn.bind(on_release=self._download)
        dl.add_widget(Label(text="radius km", size_hint_x=0.3))
        dl.add_widget(self.radius)
        dl.add_widget(self.dl_btn)
        root.add_widget(dl)

        off = BoxLayout(size_hint_y=0.12, spacing=6)
        self.offline = Spinner(text="(no offline maps)", values=[])
        use = Button(text="Use", size_hint_x=0.2)
        use.bind(on_release=self._use_offline)
        online = Button(text="Online", size_hint_x=0.28)
        online.bind(on_release=lambda *_: self._apply_provider())
        rm = Button(text="Del", size_hint_x=0.2)
        rm.bind(on_release=self._delete_offline)
        off.add_widget(self.offline)
        off.add_widget(use)
        off.add_widget(online)
        off.add_widget(rm)
        root.add_widget(off)

        self.flash = Label(text="", size_hint_y=0.08)
        root.add_widget(self.flash)
        self.add_widget(root)

    def on_pre_enter(self, *_):
        if self._ok:
            self._apply_provider()
            self._refresh_offline_list()
            self._tick(0)
            self._update_estimate()
            # 2s cadence so a followed peer is tracked in near real time.
            self._ev = Clock.schedule_interval(self._tick, 2)

    def on_pre_leave(self, *_):
        ev = getattr(self, "_ev", None)
        if ev is not None:
            ev.cancel()

    def _apply_provider(self):
        if not self._ok:
            return
        from kivy_garden.mapview import MapSource
        p = self._get_provider(self.provider.text)
        self.mapview.map_source = MapSource(
            url=p.url_template, cache_key=p.key, min_zoom=0,
            max_zoom=p.max_zoom, attribution=p.attribution)

    def _toggle_follow(self, *_):
        if self.ctl.followed():
            self.ctl.unfollow()
        else:
            h = self._peer_map.get(self.peer.text)
            if h:
                self.ctl.follow(h)
        self._tick(0)

    def _tick(self, _dt):
        if not self._ok:
            return
        rows = self.ctl.tracks_with_distance()
        # Peer picker.
        self._peer_map = {}
        labels = []
        for r in rows:
            label = f"{r['name'] or r['hash'][:8]}"
            if label in self._peer_map:           # de-dupe by appending hash
                label = f"{label} {r['hash'][:6]}"
            self._peer_map[label] = r["hash"]
            labels.append(label)
        self.peer.values = labels
        if not labels:
            self.peer.text = "(no peers)"
        # Markers.
        for m in self._markers:
            self.mapview.remove_marker(m)
        self._markers = []
        for r in rows:
            mk = self._MapMarker(lat=r["lat"], lon=r["lon"])
            self.mapview.add_marker(mk)
            self._markers.append(mk)
        # Follow: re-center on the followed peer (keep current zoom).
        followed = self.ctl.followed()
        if followed:
            self.follow_btn.text = "Unfollow"
            fix = self.ctl.followed_fix()
            if fix is not None:
                self.mapview.center_on(fix.lat, fix.lon)
                d = self.ctl.distance_to_fix(fix) or "distance unknown"
                name = next((lbl for lbl, h in self._peer_map.items()
                             if h == followed), followed[:8])
                self.dist.text = f"following {name} · {d}"
        else:
            self.follow_btn.text = "Follow"
            self.dist.text = ""
        # Download progress (set from the worker thread).
        if self._dl is not None:
            i, n = self._dl
            self.flash.text = f"downloading {i}/{n}…"

    def _update_estimate(self):
        if not self._ok:
            return
        n = self.ctl.estimate_offline_tiles(self.mapview.lat, self.mapview.lon,
                                            int(self.radius.text))
        self.flash.text = f"~{n} tiles for {self.radius.text} km here"

    def _on_dl_progress(self, i, n):
        # Written from the download thread; rendered by _tick on the UI thread.
        self._dl = (i, n)

    def _download(self, *_):
        if not self._ok:
            return
        lat, lon = self.mapview.lat, self.mapview.lon
        radius = int(self.radius.text)
        provider = self.provider.text
        self.dl_btn.disabled = True
        self._dl = (0, 1)

        def job():
            return self.ctl.download_offline_map(
                lat, lon, radius, provider, progress=self._on_dl_progress)

        def done(summary, error):
            self.dl_btn.disabled = False
            self._dl = None
            if error is not None:
                self.flash.text = f"error: {error}"
            else:
                self.flash.text = (f"saved {summary['saved']}/"
                                   f"{summary['requested']} tiles offline")
                self._refresh_offline_list()
        _run_bg(job, on_done=done)

    def _refresh_offline_list(self):
        import os
        self._offline_map = {os.path.basename(p): p
                             for p in self.ctl.offline_maps()}
        self.offline.values = list(self._offline_map)
        if not self._offline_map:
            self.offline.text = "(no offline maps)"

    def _use_offline(self, *_):
        if not self._ok:
            return
        path = self._offline_map.get(self.offline.text)
        if not path:
            return
        try:
            from kivy_garden.mapview.mbtsource import MBTilesMapSource
            self.mapview.map_source = MBTilesMapSource(path)
            self.flash.text = f"offline: {self.offline.text}"
        except Exception as exc:
            self.flash.text = f"offline load failed: {exc}"

    def _delete_offline(self, *_):
        path = self._offline_map.get(self.offline.text)
        if path and self.ctl.delete_offline_map(path):
            self.flash.text = "deleted offline map"
            self._refresh_offline_list()


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
        sm.add_widget(MapScreen(self.ctl, name="map"))
        sm.add_widget(ContactsScreen(self.ctl, name="contacts"))
        sm.add_widget(SettingsScreen(self.ctl, name="settings"))
        # Bring the daemon up off the UI thread so the window paints immediately.
        _run_bg(self.ctl.start)
        return sm

    def on_start(self):
        # Ask for location at startup (Android shows the system prompt) and
        # start streaming GPS into our own-position fix. Best-effort: a no-op
        # on platforms without android/plyer.
        self._start_location()

    def _start_location(self):
        try:
            from android.permissions import request_permissions, Permission
            request_permissions([Permission.ACCESS_FINE_LOCATION,
                                 Permission.ACCESS_COARSE_LOCATION])
        except Exception:
            pass
        try:
            from plyer import gps
            gps.configure(on_location=self._on_location)
            gps.start(minTime=2000, minDistance=1)
        except Exception:
            pass  # no GPS provider (desktop / permission denied)

    def _on_location(self, **kwargs):
        lat, lon = kwargs.get("lat"), kwargs.get("lon")
        if lat is None or lon is None:
            return
        acc = kwargs.get("accuracy")
        # GPS callback runs off the main thread; hop back via Clock.
        Clock.schedule_once(
            lambda _dt: self.ctl.update_own_location(float(lat), float(lon),
                                                     acc), 0)

    def on_stop(self):
        try:
            from plyer import gps
            gps.stop()
        except Exception:
            pass
        try:
            self.ctl.stop()
        except Exception:
            pass


if __name__ == "__main__":
    RetAlertApp().run()
