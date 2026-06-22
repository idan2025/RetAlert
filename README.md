# RetAlert

Reticulum + LXMF emergency-alert app. Panic button (UI + hardware-key mapping),
transport-aware failover (prioritize fastest interface, never start a heavy
channel over LoRa, redundant fan-out for critical alerts), live location
tracking with a map screen, and standalone or shared-instance operation
(Sideband / Columba / MeshChat / MeshChatX). Android + Linux desktop first,
iOS later.

Full design spec: [`PROMPT.md`](PROMPT.md). Architecture map:
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Status
Step 1 implemented: daemon core + standalone RNS identity + LXMF single-contact
text alert, via CLI. Later modules (TransportIntelligence, fan-out, GPS/Map,
media, UI, hardware keys, shared-instance attach) are stubs pending their build
step. A self-updater is included as an early-priority feature.

## Requirements
- Python >= 3.11 (developed on 3.14; rns + lxmf are pure-Python and work there)
- For the future Android/Kivy build, a Python 3.12 env will be used (Kivy/p4a
  wheels may lag 3.14) — not needed for step 1.

## Quickstart
```sh
python3 -m venv .venv
source .venv/bin/activate        # fish: source .venv/bin/activate.fish
pip install -r requirements.txt  # dev: pip install -r requirements-dev.txt

retalert init                    # creates identity + RNS config under ~/.retalert
retalert identity                # prints identity + LXMF delivery hashes
retalert serve                   # announce + listen for messages (Ctrl-C to quit)
retalert send <dest_hash> "help" # send one LXMF text alert
retalert contacts add <hash> Alice
retalert contacts list
retalert update --check          # check GitHub releases (latest tag by default)
retalert update                  # download + install latest, self-cleaning
```

`python -m retalert ...` works equivalently if the `retalert` script isn't on
PATH.

## Two-instance end-to-end test (same LAN)
AutoInterface discovers LAN peers with zero config. In two terminals:

```sh
# T1
retalert --storage ./stora init
retalert --storage ./stora serve        # note its "delivery" hash

# T2
retalert --storage ./storb init
retalert --storage ./storb send <T1_delivery_hash> "test emergency"
```

T1 prints the incoming message; T2 reports `DELIVERED`.

> On a single machine both instances share the local RNS shared instance by
> default (`share_instance = Yes` in the RNS config). To force two fully
> independent stacks, set `share_instance = No` in each storage dir's
> `rns/config`.

## Self-updater
`retalert update` checks `idan2025/RetAlert` releases, defaults to the latest
non-prerelease tag, downloads, pip-installs into the current environment, and
removes its own temp artifacts. Options: `--check`, `--tag <tag>`,
`--prerelease`, `--yes`. (Android APK self-update is a later, separate path.)

## License
MIT.