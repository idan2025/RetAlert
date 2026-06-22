"""RetAlert CLI — step 1 command set.

Commands:
  init                 create identity + default RNS config + storage dirs
  identity             print identity + LXMF delivery hashes
  serve                start daemon, announce, print incoming messages
  send <hash> <text>   send one LXMF text alert, wait for delivery, exit
  contacts ...         add / list / remove contacts
  update ...           self-update from GitHub releases (latest tag default)
"""
from __future__ import annotations

import argparse
import sys
import time
from typing import Optional

import RNS

from . import __version__
from .config import AppConfig
from .daemon import EmergencyDaemon
from .core.alert import Alert, SEVERITIES
from .core.geo_tracker import (
    GeoTracker, ManualFixSource, LinuxFixSource,
    clamp_lora_throttle, LORA_THROTTLE_DEFAULT,
)
from .core.announce_engine import (
    ANNOUNCE_MIN_INTERVAL, ANNOUNCE_MAX_INTERVAL,
    ANNOUNCE_PRESET_VALUES, ANNOUNCE_PRESETS, clamp_announce_interval,
)
from .storage import Contacts, Groups
from .transport.identity import load_or_create_identity, identity_hash_hex
from . import updater


def _make_daemon(args, start: bool = False) -> EmergencyDaemon:
    config = AppConfig.resolve(args.storage)
    daemon = EmergencyDaemon(config, display_name=args.display_name)
    if start:
        daemon.start()
    return daemon


# -- commands -----------------------------------------------------------

def cmd_init(args) -> int:
    config = AppConfig.resolve(args.storage)
    config.ensure_dirs()
    config.copy_default_rns_config(overwrite=args.force)
    identity = load_or_create_identity(config.identity_file)
    print(f"storage:        {config.storage_dir}")
    print(f"identity hash:  {identity_hash_hex(identity)}")
    print(f"rns config:     {config.rns_config_file}")
    print("ready. run `retalert serve` to announce and listen.")
    return 0


def cmd_identity(args) -> int:
    config = AppConfig.resolve(args.storage)
    if not config.identity_file.exists():
        print("no identity found. run `retalert init` first.", file=sys.stderr)
        return 1
    identity = load_or_create_identity(config.identity_file)
    print(f"identity hash:  {identity_hash_hex(identity)}")
    # Delivery hash needs an LXMRouter; bring RNS up briefly to compute it.
    daemon = _make_daemon(args, start=True)
    print(f"delivery hash:  {daemon.delivery_hash_hex}")
    return 0


def _print_incoming(msg) -> None:
    """Pretty-print a parsed IncomingMessage (serve mode)."""
    if msg.kind == "alert":
        sev = f" [{msg.severity}]" if msg.severity else ""
        loc = f"  @ {msg.fix.geo_uri}" if msg.fix else ""
        print(f"\n[ALERT{sev}] {msg.source_hash}: {msg.text}{loc}\n"
              f"  (bypass-silent)\nretalert> ", end="", flush=True)
    elif msg.kind == "geo":
        print(f"\n[geo] {msg.source_hash}: {msg.fix.geo_uri} "
              f"acc={msg.fix.accuracy}\nretalert> ", end="", flush=True)
    else:
        print(f"\n[incoming] {msg.source_hash}: {msg.text}\nretalert> ",
              end="", flush=True)


def cmd_serve(args) -> int:
    daemon = _make_daemon(args, start=True)
    daemon.set_incoming_callback(_print_incoming)
    print(f"identity:  {identity_hash_hex(daemon.identity)}")
    print(f"delivery:  {daemon.delivery_hash_hex}")
    print(f"filter:    receive-only-from-contacts="
          f"{daemon.settings.receive_only_from_contacts}")
    print("listening for LXMF messages. Ctrl-C to quit.\nretalert> ", end="", flush=True)
    # Re-announce periodically so peers that connect later still hear us.
    last_announce = time.monotonic()
    try:
        while True:
            time.sleep(1)
            if time.monotonic() - last_announce >= 5:
                daemon.lxmf.announce()
                last_announce = time.monotonic()
    except KeyboardInterrupt:
        print("\nbye.")
        daemon.stop()
    return 0


def _resolve_member_token(token: str, contacts: Contacts) -> str:
    """Resolve a group-member token to a destination hash.

    Accepts a hex hash (with or without colons) or a contact display name;
    the latter is resolved to its stored hash. Raises LookupError if a name
    token matches no contact and is not valid hex.
    """
    clean = token.strip().replace(":", "")
    # Valid hex destination hash (RNS hashes are 16 bytes = 32 hex chars).
    try:
        bytes.fromhex(clean)
        if len(clean) == 32:
            return clean.lower()
    except ValueError:
        pass
    # Otherwise treat as a contact display name.
    for c in contacts.list():
        if c.name == token.strip():
            return c.hash
    raise LookupError(f"cannot resolve member '{token}': not a hex hash "
                      f"and not a known contact name")


def cmd_send(args) -> int:
    daemon = _make_daemon(args, start=True)
    # Announce ourselves so the peer can reply / ack; allow time for the
    # remote announce to propagate so Identity.recall succeeds.
    daemon.lxmf.announce()

    if args.to_group:
        members = daemon.expand_group(args.to_group)
        if not members:
            print(f"group '{args.to_group}' has no members or does not exist",
                  file=sys.stderr)
            return 1
        recipients = members
        label = f"group '{args.to_group}' ({len(members)} member(s))"
    elif args.dest:
        dest = args.dest.lower()
        recipients = [dest]
        label = dest
    else:
        print("specify a destination hash or --to-group NAME", file=sys.stderr)
        return 1

    # Actively request paths so peers re-announce to us; the retry flusher
    # keeps trying until each path resolves and delivery confirms.
    for r in recipients:
        try:
            RNS.Transport.request_path(bytes.fromhex(r.replace(":", "")))
        except Exception:
            pass

    alert = Alert(
        severity=args.severity,
        text=args.text,
        recipients=recipients,
        retry_interval=args.retry_interval,
        max_attempts=args.max_attempts,
    )
    daemon.send_alert(alert)
    print(f"alert {alert.alert_id} sent to {label}: {args.text!r}")

    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        if daemon.ack.is_done(alert.alert_id):
            break
        time.sleep(0.5)

    summary = daemon.ack.summary(alert.alert_id)
    for recipient, state in summary.items():
        print(f"  {recipient}: {state}")
    states = set(summary.values())
    if states & {"delivered", "acked", "replied"}:
        print("DELIVERED")
        return 0
    if states == {"failed"}:
        print("FAILED", file=sys.stderr)
        return 3
    print("TIMEOUT (no delivery confirmation)", file=sys.stderr)
    return 4


def cmd_group(args) -> int:
    """Manage ad-hoc groups (named subsets of contacts)."""
    config = AppConfig.resolve(args.storage)
    contacts = Contacts(config.contacts_file)
    groups = Groups(config.groups_file)
    cmd = args.group_cmd
    if cmd == "create":
        members = [_resolve_member_token(t, contacts) for t in (args.members or [])]
        ok = groups.create(args.name, members)
        if not ok:
            print(f"group '{args.name}' already exists", file=sys.stderr)
            return 1
        print(f"created group '{args.name}' with {len(members)} member(s)")
    elif cmd == "list":
        rows = groups.list()
        if not rows:
            print("(no groups)")
        for g in rows:
            print(f"{g.name}  ({len(g.members)} members)")
    elif cmd == "show":
        g = groups.get(args.name)
        if g is None:
            print(f"group '{args.name}' not found", file=sys.stderr)
            return 1
        print(f"{g.name}:")
        for h in g.members:
            c = contacts.get(h)
            print(f"  {h}  {c.name if c else ''}")
    elif cmd == "remove":
        ok = groups.remove(args.name)
        print("removed" if ok else "not found", args.name)
    elif cmd == "add-member":
        h = _resolve_member_token(args.member, contacts)
        ok = groups.add_member(args.name, h)
        if not ok:
            print(f"group '{args.name}' not found", file=sys.stderr)
            return 1
        print(f"added {h} to {args.name}")
    elif cmd == "remove-member":
        h = _resolve_member_token(args.member, contacts)
        ok = groups.remove_member(args.name, h)
        if not ok:
            print(f"not a member of '{args.name}' (or group missing)",
                  file=sys.stderr)
            return 1
        print(f"removed {h} from {args.name}")
    return 0


def cmd_alerts(args) -> int:
    config = AppConfig.resolve(args.storage)
    daemon = EmergencyDaemon(config)
    if args.alerts_cmd == "list":
        pending = daemon.retry.pending()
        if not pending:
            print("(no pending alerts)")
            return 0
        for a in pending:
            print(f"{a.alert_id}  severity={a.severity}  recipients={a.recipients}")
            for r, st in daemon.ack.summary(a.alert_id).items():
                print(f"    {r}: {st}")
    return 0


def cmd_status(args) -> int:
    """Show classified interfaces + tiers + per-payload gating."""
    daemon = _make_daemon(args, start=True)
    if daemon.ti is None:
        print("transport intelligence not initialised", file=sys.stderr)
        return 1
    print(f"identity:  {identity_hash_hex(daemon.identity)}")
    print(f"delivery:  {daemon.delivery_hash_hex}")
    print("\ninterfaces:")
    ifaces = daemon.ti.classify_interfaces()
    if not ifaces:
        print("  (none)")
    for i in ifaces:
        flag = "up" if i.online else "down"
        ov = f" [override:{i.user_override}]" if i.user_override else ""
        print(f"  {i.tier:<6} {i.cls:<22} {i.name:<20} {flag}{ov}")
    print("\npayload gating (currently-up tiers):")
    for pc in ("text", "ack", "gps_oneshot", "gps_live", "photo", "audio"):
        ok, best = daemon.ti.gate_payload(pc)
        state = f"allow (best={best})" if ok else f"block (need higher tier; best={best})"
        print(f"  {pc:<12} {state}")
    return 0


def _fix_source_from_args(args):
    """Build a FixSource from CLI args. Manual if --lat/--lon given; else
    attempt the platform source (Linux on desktop, stub)."""
    if args.lat is not None and args.lon is not None:
        return ManualFixSource(args.lat, args.lon,
                               accuracy=getattr(args, "accuracy", None))
    # No manual coords: try platform source.
    try:
        return LinuxFixSource()
    except Exception:
        return None


def cmd_locate(args) -> int:
    """Get one GPS fix and print it (no RNS/daemon needed)."""
    src = _fix_source_from_args(args)
    if src is None:
        print("no fix source on this platform; pass --lat and --lon", file=sys.stderr)
        return 1
    tracker = GeoTracker(src)
    fix = tracker.one_shot()
    if fix is None:
        print("no fix available", file=sys.stderr)
        return 2
    print(fix.geo_uri)
    print(f"  lat={fix.lat} lon={fix.lon} acc={fix.accuracy} alt={fix.altitude} "
          f"src={fix.source} t={fix.timestamp}")
    return 0


def cmd_share(args) -> int:
    """Live-share location to a contact over LXMF until Ctrl-C."""
    daemon = _make_daemon(args, start=True)
    daemon.lxmf.announce()
    daemon.set_fix_source(ManualFixSource(args.lat, args.lon))

    # Apply LoRa throttle when LoRa is the sole up interface.
    if daemon.only_low_tier_up():
        interval = clamp_lora_throttle(args.lora_interval)
        print(f"only LoRa up -> throttling live-share to {interval}s")
    else:
        interval = args.interval
        print(f"live-share every {interval}s to {args.dest}")

    dest_hash_bytes = bytes.fromhex(args.dest.replace(":", ""))
    try:
        RNS.Transport.request_path(dest_hash_bytes)
    except Exception:
        pass

    daemon.start_live_share(args.dest, interval)
    print("sharing. Ctrl-C to stop.")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nstopping.")
        daemon.stop_live_share()
    return 0


def cmd_contacts(args) -> int:
    config = AppConfig.resolve(args.storage)
    contacts = Contacts(config.contacts_file)
    if args.contacts_cmd == "add":
        name = args.name
        if name is None:
            # Fall back to the discover display name, else the hash prefix.
            daemon = _make_daemon(args, start=True)
            peer = daemon.discover.get(args.hash)
            name = (peer.display_name if peer and peer.display_name
                    else args.hash[:16])
        contacts.add(args.hash, name)
        print(f"added {name} ({args.hash})")
    elif args.contacts_cmd == "remove":
        ok = contacts.remove(args.hash)
        print("removed" if ok else "not found", args.hash)
    elif args.contacts_cmd == "list":
        rows = contacts.list()
        if not rows:
            print("(no contacts)")
        for c in rows:
            print(f"{c.hash}  {c.name}")
    return 0


def cmd_announce(args) -> int:
    """Manual one-shot announce, or toggle auto-announce."""
    daemon = _make_daemon(args, start=True)
    if args.announce_cmd == "now":
        daemon.announce_now()
        print(f"announced LXMF delivery: {daemon.delivery_hash_hex}")
        return 0
    if args.announce_cmd == "auto":
        if args.off:
            daemon.set_auto_announce(False)
            print("auto-announce: off")
            return 0
        interval = clamp_announce_interval(args.interval)
        daemon.set_auto_announce(True, interval=interval)
        label = ANNOUNCE_PRESETS.get(int(interval), f"{interval:.0f}s")
        print(f"auto-announce: on  interval={label} ({interval:.0f}s)")
        return 0
    return 1


def cmd_discover(args) -> int:
    """List/clear heard announces, star/unstar peers (Columba-style)."""
    daemon = _make_daemon(args, start=True)
    if args.discover_cmd == "list":
        peers = daemon.discover_list()
        if not peers:
            print("(no announces heard)")
            return 0
        for p in peers:
            star = "*" if p.starred else " "
            name = p.display_name or "(unknown)"
            print(f"{star} {p.hash}  {name}  [{p.aspect}]  {p.last_heard:.0f}")
        return 0
    if args.discover_cmd == "clear":
        n = daemon.discover_clear()
        print(f"cleared {n} announce(s) from discover list")
        return 0
    if args.discover_cmd == "star":
        ok = daemon.star(args.hash)
        print("starred" if ok else "not heard yet (will star when heard)", args.hash)
        return 0
    if args.discover_cmd == "unstar":
        daemon.unstar(args.hash)
        print("unstarred", args.hash)
        return 0
    return 1


def cmd_settings(args) -> int:
    """Receive-side filter: receive-only-from-contacts + allow/deny."""
    config = AppConfig.resolve(args.storage)
    from .storage import Settings
    s = Settings(config.settings_file)
    cmd = args.settings_cmd
    if cmd == "show":
        print(f"receive-only-from-contacts: {s.receive_only_from_contacts}")
        print(f"allowlist: {sorted(s.allowlist) or '(none)'}")
        print(f"denylist:  {sorted(s.denylist) or '(none)'}")
        return 0
    if cmd == "receive-only":
        enabled = not args.off
        s.set_receive_only_from_contacts(enabled)
        print(f"receive-only-from-contacts: {enabled}")
        return 0
    if cmd == "allow":
        s.allow(args.hash)
        print(f"allowed {args.hash}")
        return 0
    if cmd == "deny":
        s.deny(args.hash)
        print(f"denied {args.hash}")
        return 0
    if cmd == "forget":
        s.forget(args.hash)
        print(f"forgot {args.hash}")
        return 0
    return 1


def cmd_tracks(args) -> int:
    """Inspect live-sharing peers (Map screen backend)."""
    daemon = _make_daemon(args, start=True)
    if args.tracks_cmd == "list":
        tracks = daemon.tracks.list()
        if not tracks:
            print("(no live tracks)")
            return 0
        for t in tracks:
            flag = " (following)" if daemon.tracks.followed == t.source_hash else ""
            print(f"{t.source_hash}  {t.display_name or '?'}  "
                  f"{t.fix.geo_uri}  acc={t.fix.accuracy}{flag}")
        return 0
    if args.tracks_cmd == "clear":
        n = daemon.tracks.clear()
        print(f"cleared {n} live track(s)")
        return 0
    if args.tracks_cmd == "follow":
        ok = daemon.tracks.follow(args.hash)
        print("following" if ok else "not sharing (no live track)", args.hash)
        return 0
    if args.tracks_cmd == "unfollow":
        daemon.tracks.unfollow()
        print("unfollowed")
        return 0
    return 1


def cmd_update(args) -> int:
    if args.check:
        rel = updater.check()
        newer = updater.is_newer(rel.version, __version__)
        print(f"current:  {__version__}")
        print(f"latest:   {rel.tag}  (prerelease={rel.prerelease})")
        print("update available" if newer else "up to date")
        return 0
    rel = updater.update(tag=args.tag, prerelease=args.prerelease, yes=args.yes)
    if not args.tag and not args.prerelease and not updater.is_newer(rel.version, __version__):
        print(f"already at latest: {__version__} (latest {rel.tag})")
    return 0


# -- argparse -----------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="retalert", description="RetAlert emergency-alert CLI.")
    p.add_argument("--storage", help="storage dir (default ~/.retalert)")
    p.add_argument("--display-name", default="RetAlert", help="LXMF display name")
    p.add_argument("-v", "--version", action="version", version=f"retalert {__version__}")
    sub = p.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("init", help="create identity + config + storage dirs")
    sp.add_argument("--force", action="store_true", help="overwrite existing RNS config")
    sp.set_defaults(func=cmd_init)

    sp = sub.add_parser("identity", help="print identity + delivery hashes")
    sp.set_defaults(func=cmd_identity)

    sp = sub.add_parser("serve", help="announce + listen for messages")
    sp.set_defaults(func=cmd_serve)

    sp = sub.add_parser("send", help="send one LXMF text alert")
    sp.add_argument("dest", nargs="?", default=None,
                    help="recipient delivery hash (hex); omitted if --to-group")
    sp.add_argument("text", help="message text")
    sp.add_argument("--to-group", default=None, metavar="NAME",
                    help="send to every member of a saved group instead of one dest")
    sp.add_argument("--severity", choices=SEVERITIES, default="help",
                    help="alert severity (default: help)")
    sp.add_argument("--timeout", type=float, default=30.0, help="delivery wait seconds")
    sp.add_argument("--wait-announce", type=float, default=15.0,
                    help="seconds to wait for peer announce / path")
    sp.add_argument("--retry-interval", type=float, default=3.0,
                    help="seconds between retries while unacked (emergency: fast)")
    sp.add_argument("--max-attempts", type=int, default=0,
                    help="max send attempts per recipient (0 = unlimited)")
    sp.set_defaults(func=cmd_send)

    ap = sub.add_parser("alerts", help="inspect pending alerts")
    aps = ap.add_subparsers(dest="alerts_cmd", required=True)
    al = aps.add_parser("list", help="list pending alerts + per-recipient state")
    al.set_defaults(func=cmd_alerts)

    sp = sub.add_parser("status", help="show interfaces, tiers, payload gating")
    sp.set_defaults(func=cmd_status)

    sp = sub.add_parser("locate", help="get one GPS fix and print it")
    sp.add_argument("--lat", type=float, help="manual latitude (no GPS on desktop)")
    sp.add_argument("--lon", type=float, help="manual longitude (no GPS on desktop)")
    sp.add_argument("--accuracy", type=float, default=None, help="fix accuracy in metres")
    sp.set_defaults(func=cmd_locate)

    sp = sub.add_parser("share", help="live-share location to a contact over LXMF")
    sp.add_argument("dest", help="recipient delivery hash (hex)")
    sp.add_argument("--lat", type=float, required=True, help="latitude to share")
    sp.add_argument("--lon", type=float, required=True, help="longitude to share")
    sp.add_argument("--interval", type=float, default=5.0,
                    help="seconds between fixes (normal/fast interfaces)")
    sp.add_argument("--lora-interval", type=float, default=LORA_THROTTLE_DEFAULT,
                    help="throttle seconds when only LoRa is up (10-3600, default 60)")
    sp.set_defaults(func=cmd_share)

    cp = sub.add_parser("contacts", help="manage contacts")
    cps = cp.add_subparsers(dest="contacts_cmd", required=True)
    ca = cps.add_parser("add", help="add a contact (name optional; pulled from discover if omitted)")
    ca.add_argument("hash", help="destination hash (hex)")
    ca.add_argument("name", nargs="?", default=None, help="display name (optional)")
    ca.set_defaults(func=cmd_contacts)
    cr = cps.add_parser("remove", help="remove a contact")
    cr.add_argument("hash", help="destination hash (hex)")
    cr.set_defaults(func=cmd_contacts)
    cl = cps.add_parser("list", help="list contacts")
    cl.set_defaults(func=cmd_contacts)

    # group: ad-hoc named subsets of contacts.
    gp = sub.add_parser("group", help="manage ad-hoc groups (contact subsets)")
    gps = gp.add_subparsers(dest="group_cmd", required=True)
    gcr = gps.add_parser("create", help="create a group with optional members")
    gcr.add_argument("name", help="group name")
    gcr.add_argument("members", nargs="*", help="member hashes or contact names")
    gcr.set_defaults(func=cmd_group)
    gl = gps.add_parser("list", help="list groups")
    gl.set_defaults(func=cmd_group)
    gs = gps.add_parser("show", help="show group members")
    gs.add_argument("name", help="group name")
    gs.set_defaults(func=cmd_group)
    grm = gps.add_parser("remove", help="delete a group")
    grm.add_argument("name", help="group name")
    grm.set_defaults(func=cmd_group)
    gam = gps.add_parser("add-member", help="add a member to a group")
    gam.add_argument("name", help="group name")
    gam.add_argument("member", help="member hash or contact name")
    gam.set_defaults(func=cmd_group)
    grmm = gps.add_parser("remove-member", help="remove a member from a group")
    grmm.add_argument("name", help="group name")
    grmm.add_argument("member", help="member hash or contact name")
    grmm.set_defaults(func=cmd_group)

    # announce: manual one-shot + auto-announce toggle.
    anp = sub.add_parser("announce", help="send / schedule LXMF delivery announces")
    ans = anp.add_subparsers(dest="announce_cmd", required=True)
    an_now = ans.add_parser("now", help="send one announce now")
    an_now.set_defaults(func=cmd_announce)
    an_auto = ans.add_parser("auto", help="toggle automatic re-announce")
    an_auto.add_argument("--interval", type=float, default=None,
                         help=f"seconds between announces "
                              f"(presets: {dict(ANNOUNCE_PRESETS)}; "
                              f"min {ANNOUNCE_MIN_INTERVAL}s, max {ANNOUNCE_MAX_INTERVAL}s)")
    an_auto.add_argument("--off", action="store_true", help="disable auto-announce")
    an_auto.set_defaults(func=cmd_announce)

    # discover: heard announce list (Columba-style network screen).
    dp = sub.add_parser("discover", help="discover/network list of heard announces")
    dps = dp.add_subparsers(dest="discover_cmd", required=True)
    dl = dps.add_parser("list", help="list heard announces")
    dl.set_defaults(func=cmd_discover)
    dc = dps.add_parser("clear", help="clear the heard-announce list")
    dc.set_defaults(func=cmd_discover)
    ds = dps.add_parser("star", help="star a discovered peer")
    ds.add_argument("hash", help="destination hash (hex)")
    ds.set_defaults(func=cmd_discover)
    du = dps.add_parser("unstar", help="unstar a discovered peer")
    du.add_argument("hash", help="destination hash (hex)")
    du.set_defaults(func=cmd_discover)

    # settings: receive-side filter (step 9).
    sp = sub.add_parser("settings", help="receive-side filter + allow/deny")
    sps = sp.add_subparsers(dest="settings_cmd", required=True)
    sps.add_parser("show", help="show current filter settings").set_defaults(func=cmd_settings)
    sro = sps.add_parser("receive-only", help="toggle 'receive only from contacts'")
    sro.add_argument("--off", action="store_true", help="disable (accept any sender)")
    sro.set_defaults(func=cmd_settings)
    sa = sps.add_parser("allow", help="always allow a sender")
    sa.add_argument("hash", help="destination hash (hex)")
    sa.set_defaults(func=cmd_settings)
    sd = sps.add_parser("deny", help="always deny a sender")
    sd.add_argument("hash", help="destination hash (hex)")
    sd.set_defaults(func=cmd_settings)
    sf = sps.add_parser("forget", help="remove a sender from allow/deny")
    sf.add_argument("hash", help="destination hash (hex)")
    sf.set_defaults(func=cmd_settings)

    # tracks: live-sharing peers (map backend, step 8).
    tp = sub.add_parser("tracks", help="live-sharing peers (map backend)")
    tps = tp.add_subparsers(dest="tracks_cmd", required=True)
    tps.add_parser("list", help="list live tracks").set_defaults(func=cmd_tracks)
    tps.add_parser("clear", help="clear live tracks").set_defaults(func=cmd_tracks)
    tf = tps.add_parser("follow", help="follow a live-sharing peer")
    tf.add_argument("hash", help="destination hash (hex)")
    tf.set_defaults(func=cmd_tracks)
    tps.add_parser("unfollow", help="stop following").set_defaults(func=cmd_tracks)

    sp = sub.add_parser("update", help="self-update from GitHub releases")
    sp.add_argument("--check", action="store_true", help="only check, do not install")
    sp.add_argument("--tag", help="install a specific release tag")
    sp.add_argument("--prerelease", action="store_true", help="allow prereleases")
    sp.add_argument("--yes", "-y", action="store_true", help="skip confirm prompt")
    sp.set_defaults(func=cmd_update)

    return p


def main(argv: Optional[list] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except KeyboardInterrupt:
        return 130
    except Exception as exc:  # CLI-level safety net.
        print(f"error: {exc}", file=sys.stderr)
        return 1