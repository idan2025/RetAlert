# RetAlert

[![android-kt](https://github.com/idan2025/RetAlert/actions/workflows/android-kt.yml/badge.svg)](https://github.com/idan2025/RetAlert/actions/workflows/android-kt.yml)

Emergency alerts over [Reticulum](https://reticulum.network) / LXMF for Android —
works without internet or cell service, over Wi-Fi/LAN, TCP, LoRa or through
another Reticulum app on the phone.

## Get the app
Download `app-release.apk` from the [latest release](../../releases), allow
"install from unknown sources", and install. (Release signing:
[`docs/ANDROID_SIGNING.md`](docs/ANDROID_SIGNING.md).)

## What it does
- **Panic button** (hold to confirm): alerts your `default` preset's
  recipients, or every contact, and can start sharing your live location.
- **Presets**: saved alerts (severity, message, contacts or a group) that share
  your location never / once / live. Fire them from the app or with a
  **volume-key sequence**, even with the screen off.
- **Real-time location**: live `geo:` updates to the people you alerted,
  LoRa-aware, auto-stops after a set time, survives the app being killed.
- **Map**: people sharing with you, their trails, distance and bearing,
  tap-to-follow, offline map download.
- **Delivery tracking**: per-recipient sent / delivered / acked / replied;
  receivers ack or reply from the Inbox.
- **Alerts ring like an alarm**: on the alarm stream with vibration, through
  silent / vibrate mode and Do Not Disturb, over the lock screen, until you
  stop them. Pick the sound (six built-in tones, any phone sound or audio
  file) and the volume. The listener restarts after a reboot. Settings
  has a checklist (notifications, DND access, battery) and a test alarm.
- **Interfaces screen**: AutoInterface (LAN), TCP client and server, UDP,
  **RNode** LoRa (Bluetooth or USB), **Meshtastic** nodes (Bluetooth, Wi-Fi
  or USB), Bluetooth LE mesh and I2P — or a **shared instance** from Columba,
  Sideband or MeshChat on the same phone. USB needs an OTG cable.
- **RNS over Meshtastic**: a native port of
  [RNS_Over_Meshtastic](https://github.com/landandair/RNS_Over_Meshtastic),
  wire-compatible with its Python interface. Put it on a private secondary
  channel with a low hop limit so it stays off the public mesh. The node must
  not be connected to the Meshtastic app at the same time.
- Receive-only-from-contacts filter with per-address allow/block.

Wire formats (`!RETALERT!` markers, `geo:` bodies) match the original Python
app, so it interoperates with it and shows as readable text in other LXMF apps.

## Build
```sh
./scripts/fetch-lxmf-kt.sh                         # LXMF-kt composite build, pinned commit
cd android && ./gradlew :app:assembleDebug        # JDK 21 + Android SDK 36
./gradlew :domain:test :data:test :reticulum:test :updater:test
```
Architecture and development notes: [`PROMPT.md`](PROMPT.md).

## License
MIT — see [`LICENSE`](LICENSE).

## Historical Python app
The Python + Kivy implementation below was the v0.x reference. It was removed
from the tree when the native Android app replaced it; git history keeps it.

### Status
Backend (Python core) implemented and tested via the headless CLI. UI phase
in progress: a Kivy app (`main.py`) over a testable `retalert.ui.AppController`
with screens for panic + status, send, inbox (reply/ack), outbox (ack state),
presets (one-tap fire), contacts, and the incoming-filter settings. The map screen
has selectable tile providers, offline-area download (MBTiles, 5/10/20/50/100
km radius), tap-to-follow real-time peer tracking with a km/mi distance
readout, and Android GPS with a runtime location-permission prompt. Android
(Buildozer) and Linux desktop (PyInstaller) builds run in CI — the debug APK
already compiles. On-device audio/photo/hardware-key capture and a signed
release APK next. Build-order progress:

| Step | Feature | Status |
|------|---------|--------|
| 1 | Daemon core + standalone identity + LXMF text alert | ✅ |
| 2 | Ack tracking + persistent retry queue | ✅ |
| 3-4 | TransportIntelligence (classify/rank/Hail Mary fan-out/failover) | ✅ |
| 5 | GPS one-shot + live-share (LoRa throttle) | ✅ |
| 6 | Contacts + discover/network (announce listener) | ✅ |
| 7 | Ad-hoc group assembly from contacts | ✅ |
| 8 | Map screen (providers, offline MBTiles, tap-to-follow, distance) | ✅ |
| 9 | Incoming filter (receive-only-from-contacts + allow/deny) + bypass-silent hook + app-level ack | ✅ |
| 10 | Preset system + panic engine | ✅ backend / UI next |
| 11 | Dynamic hardware-key capture | ✅ backend / capture deferred |
| 12 | Shared-instance attach | ✅ |
| 13 | Audio/photo channel over raw RNS link | ✅ backend |
| 14 | Desktop UI pass | 🚧 Kivy app skeleton (panic + inbox) landed |
| 15 | Hardening + docs | ✅ |

A self-updater is included as an early-priority feature.

### Requirements
- Python >= 3.11 (developed on 3.14; `rns` + `lxmf` are pure-Python)
- For the future Android/Kivy build, a Python 3.12 env will be used
  (Kivy/p4a wheels may lag 3.14) — not needed for the CLI.

### Quickstart
```sh
python3 -m venv .venv
source .venv/bin/activate        # fish: source .venv/bin/activate.fish
pip install -r requirements.txt  # dev: pip install -r requirements-dev.txt

retalert init                    # identity + RNS config under ~/.retalert
retalert identity                 # identity + LXMF delivery hashes
retalert serve                    # announce + listen (Ctrl-C to quit)
retalert send <dest_hash> "help" # send one LXMF text alert
retalert update --check           # GitHub releases (latest tag default)
```

`python -m retalert ...` works equivalently if the `retalert` script isn't on
PATH.

### CLI command reference
Global flags: `--storage <dir>` (per-instance storage, for multi-instance
tests), `--display-name <name>`.

| Command | Purpose |
|---------|---------|
| `init` | Create identity + default RNS config + storage dirs |
| `identity` | Print identity + LXMF delivery hashes |
| `serve` | Announce + listen; prints incoming alerts/geo/text. Interactive console: `reply <id> <text>`, `ack <id>`, `inbox`, `quit` |
| `send [dest] <text>` | Send a text alert (`--to-group NAME`, `--severity`, `--timeout`) |
| `panic [preset]` | Fire a preset (trigger -> alert dispatch) |
| `status` | Interfaces, tiers, per-payload gating |
| `locate [--lat --lon]` | One GPS fix (`geo:lat,lon`) |
| `share <dest> --lat --lon` | Live-share location to a contact (`--interval`, `--lora-interval`) |
| `announce now` | Send one LXMF delivery announce |
| `announce auto [--interval N] [--off]` | Auto-announce (30min–12h; presets 1h/2h/3h) |
| `discover list\|clear\|star\|unstar` | Heard-announce list (Columba-style) |
| `contacts add\|list\|remove` | Manage contacts (add name optional → from discover) |
| `group create\|list\|show\|remove\|add-member\|remove-member` | Ad-hoc groups (contact subsets) |
| `preset add\|list\|show\|remove` | Saved emergency config bundles |
| `settings show\|receive-only\|allow\|deny\|forget` | Incoming filter + per-sender allow/deny |
| `tracks list\|clear\|follow\|unfollow` | Live-sharing peers (map backend) |
| `instance attach\|status\|detach` | Attach to a host app's shared RNS instance |
| `keys add\|list\|remove\|feed` | Hardware-key combos -> preset triggers |
| `alerts list` | Inspect pending alerts + per-recipient state |
| `inbox list\|show\|remove\|clear\|prune` | Received app-to-app alerts (reply-by-id registry; auto-pruned hourly) |
| `reply <alert_id> <text>` | Reply (ack + text) to a received alert by id |
| `ack <alert_id>` | Manually re-ack a received alert by id |
| `update [--check\|--tag\|--prerelease\|-y]` | Self-update from GitHub releases |

### Two-instance end-to-end test (same LAN)
AutoInterface discovers LAN peers with zero config. In two terminals:

```sh
# T1
retalert --storage ./stora init
retalert --storage ./stora serve        # note its "delivery" hash

# T2
retalert --storage ./storb init
# add T1 as a contact so T2 passes T1's receive-only-from-contacts filter
retalert --storage ./storb contacts add <T1_delivery_hash> Listener
retalert --storage ./storb send <T1_delivery_hash> "test emergency"
```

T1 prints `[ALERT [danger]] ...` (bypass-silent fires); T2 reports
`DELIVERED`.

> On a single machine both instances share the local RNS shared instance by
> default (`share_instance = Yes`). To force two fully independent stacks,
> set `share_instance = No` in each storage dir's `rns/config`.

### Presets
A preset bundles a trigger's full configuration: severity, message,
recipients (or a saved group), payload toggles, retry policy, and fan-out
mode.

```sh
retalert preset add medical \
  --severity medical --text "need help" \
  --recipient <hash> --payload text,gps_oneshot \
  --fan-out critical --retry-interval 3 --max-attempts 0

retalert preset add team_alert --severity danger --group team \
  --payload text,gps_live --lora-throttle 60

retalert panic medical           # fire the preset
retalert panic                    # fires the 'default' preset
```

Payload classes: `text`, `gps_oneshot`, `gps_live`, `photo`, `audio`.
Fan-out: `off` | `critical` (default; Hail Mary only for
critical/danger/medical) | `all`.

### Discover & contacts
```sh
retalert discover list            # heard announces, freshest first
retalert discover star <hash>     # star (persists across clear/restart)
retalert contacts add <hash>      # name pulled from discover if omitted
retalert announce auto --interval 3600   # auto-announce every 1h
```

### Incoming filter
`receive-only-from-contacts` is **on by default**: unknown senders are
dropped. Allowlist overrides it; denylist always drops.

```sh
retalert settings show
retalert settings receive-only --off    # open mode (public events)
retalert settings allow <hash>
retalert settings deny <hash>
```

App-to-app alerts (wire marker `!RETALERT!`) trigger the bypass-silent
hook on the receiver so a real emergency alarms at full volume on a
muted phone (platform callback; stub on desktop). The v1 wire format
`!RETALERT!id:<alert_id>!<sev>!<text>` carries an `alert_id` so the
receiver acks it back (`!RETALERT!ack!<alert_id>`) or replies
(`!RETALERT!reply!<alert_id>!<text>`); the sender's AckTracker moves
that recipient `DELIVERED → ACKED` (or `→ REPLIED` with the reply text).

### Shared instance
Attach to a host app's RNS instance instead of running your own `rnsd`:

```sh
retalert instance attach --mode local --host-app sideband     # same machine
retalert instance attach --mode tcp --host 10.0.0.5 --port 49300 --host-app columba
retalert instance status
retalert instance detach
```

Restart the daemon after attach/detach for the new RNS config to take effect.

### Self-updater
`retalert update` checks `idan2025/RetAlert` releases, defaults to the latest
non-prerelease tag, downloads, pip-installs into the current environment, and
removes its own temp artifacts. Options: `--check`, `--tag <tag>`,
`--prerelease`, `--yes`. (Android APK self-update is a later, separate path.)

### Tests
```sh
pytest                             # full suite (256 tests)
```

### CI / builds
GitHub Actions, under [`.github/workflows`](.github/workflows):

| Workflow | Trigger | Builds |
|----------|---------|--------|
| `android-kt.yml` | push / PR on `android/**` | Gradle `:app:assembleDebug` (JDK 21, SDK 36) |

(The historical `ci.yml` / `android.yml` / `desktop.yml` / `release.yml`
Python/Buildozer/PyInstaller workflows were removed with the Python tree in
Phase 6.) To ship a **signed** release APK, set up a keystore once — see
[`docs/ANDROID_SIGNING.md`](docs/ANDROID_SIGNING.md)
(`scripts/make-keystore.sh` + four repo secrets); without it the release APK is
built unsigned.
