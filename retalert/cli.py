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
from .storage import Contacts
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


def _print_incoming(source_hex: str, text: str, timestamp: float) -> None:
    print(f"\n[incoming] {source_hex}: {text}\nretalert> ", end="", flush=True)


def cmd_serve(args) -> int:
    daemon = _make_daemon(args, start=True)
    daemon.set_incoming_callback(_print_incoming)
    # Re-register the callback (set before start preferred); ensure applied.
    daemon.lxmf.set_incoming_callback(_print_incoming)
    print(f"identity:  {identity_hash_hex(daemon.identity)}")
    print(f"delivery:  {daemon.delivery_hash_hex}")
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


def cmd_send(args) -> int:
    daemon = _make_daemon(args, start=True)
    # Announce ourselves so the peer can reply / ack; allow time for the
    # remote announce to propagate so Identity.recall succeeds.
    daemon.lxmf.announce()
    text = args.text
    dest = args.dest.lower()
    dest_hash_bytes = bytes.fromhex(dest.replace(":", ""))

    alert = Alert(
        severity=args.severity,
        text=text,
        recipients=[dest],
        retry_interval=args.retry_interval,
        max_attempts=args.max_attempts,
    )

    # Actively request the path so the peer re-announces to us; the retry
    # flusher will keep trying until the path resolves and delivery confirms.
    try:
        RNS.Transport.request_path(dest_hash_bytes)
    except Exception:
        pass

    daemon.send_alert(alert)
    print(f"alert {alert.alert_id} sent to {dest}: {text!r}")

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


def cmd_contacts(args) -> int:
    config = AppConfig.resolve(args.storage)
    contacts = Contacts(config.contacts_file)
    if args.contacts_cmd == "add":
        contacts.add(args.hash, args.name)
        print(f"added {args.name} ({args.hash})")
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
    sp.add_argument("dest", help="recipient delivery hash (hex)")
    sp.add_argument("text", help="message text")
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

    cp = sub.add_parser("contacts", help="manage contacts")
    cps = cp.add_subparsers(dest="contacts_cmd", required=True)
    ca = cps.add_parser("add", help="add a contact")
    ca.add_argument("hash", help="destination hash (hex)")
    ca.add_argument("name", help="display name")
    ca.set_defaults(func=cmd_contacts)
    cr = cps.add_parser("remove", help="remove a contact")
    cr.add_argument("hash", help="destination hash (hex)")
    cr.set_defaults(func=cmd_contacts)
    cl = cps.add_parser("list", help="list contacts")
    cl.set_defaults(func=cmd_contacts)

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