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

    delivered = {"v": False}
    failed = {"v": False}

    def on_delivered():
        delivered["v"] = True

    def on_failed():
        failed["v"] = True

    # Wait for the peer's announce to be heard (recall may fail right away).
    # Actively request the path so the peer re-announces to us.
    dest_hash_bytes = bytes.fromhex(args.dest.replace(":", ""))
    deadline_recall = time.monotonic() + args.wait_announce
    lxm = None
    requested_path = False
    while True:
        try:
            lxm = daemon.send_message(args.dest, text,
                                      on_delivered=on_delivered, on_failed=on_failed)
            break
        except LookupError:
            if not requested_path:
                try:
                    RNS.Transport.request_path(dest_hash_bytes)
                except Exception:
                    pass
                requested_path = True
            if time.monotonic() >= deadline_recall:
                print(f"no path to {args.dest} (no announce heard within "
                      f"{args.wait_announce}s). is the peer running `serve`?",
                      file=sys.stderr)
                return 2
            time.sleep(1)

    print(f"sent to {args.dest}: {text!r}")
    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        if delivered["v"]:
            print("DELIVERED")
            return 0
        if failed["v"]:
            print("FAILED", file=sys.stderr)
            return 3
        time.sleep(0.5)
    print("TIMEOUT (no delivery confirmation)", file=sys.stderr)
    return 4


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
    sp.add_argument("--timeout", type=float, default=30.0, help="delivery wait seconds")
    sp.add_argument("--wait-announce", type=float, default=15.0,
                    help="seconds to wait for peer announce / path")
    sp.set_defaults(func=cmd_send)

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