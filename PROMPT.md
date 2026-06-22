# Reticulum Emergency App — Build Prompt / Spec

## One-line
Cross-platform emergency-alert app over Reticulum + LXMF. Panic button (UI + hardware key mapping) sends configurable alert — text+severity, GPS (one-shot or live-share), and/or an open audio/photo channel — to a contact or an ad-hoc group assembled from contacts. Runs standalone with its own RNS identity, or attaches to a shared Reticulum instance exposed by Sideband / Columba / MeshChat / MeshChatX. Android and Linux desktop first; iOS later. Transport-aware: classifies interfaces (WAN/LAN/BLE/LoRa), prioritizes fastest, fans out redundant copies for critical alerts, and never opens a heavy channel over LoRa.

## Goals
1. **Alert fast, alert anywhere.** No internet, no cell — pure Reticulum mesh. Works at protests, disasters, backcountry.
2. **Pluggable transport.** Own identity+rnsd, *or* ride an existing Sideband/Columba "Share instance" so user does not have to run two stacks.
3. **Android-first, portable core.** Shared daemon in Python; thin native/UI shells per platform. iOS not blocked by architecture but deferred.
4. **No false alarms, no missed alarms.** Hold-to-confirm UI; hardware-key combos; dead-man's switch optional; retry-until-ack.
5. **Transport-aware.** App knows which interfaces are up (internet/LAN, LoRa, BLE, I2P, etc.), their bandwidth class, and which path to a recipient each offers. It never starts a bandwidth-heavy channel over a low-bandwidth interface (LoRa tested with Columba — unusable). It prioritizes the fastest usable interface, fans out redundant copies in parallel for critical alerts, and fails over to the next interface fast when one path dies. Emergencies are time-critical — redundancy and speed beat elegance.

## Non-goals (v1)
- iOS build.
- Voice/video streaming beyond chunked audio/photo over a RNS link.
- Cloud dependency of any kind.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│  UI shells (Android/Kivy, Linux GTK/Qt, future iOS)     │
│   - panic button, presets, contact/group mgmt, ack view │
└───────────────┬─────────────────────────────────────────┘
                │ local IPC (sideband-style share iface /
                │  in-process for standalone)
┌───────────────▼─────────────────────────────────────────┐
│  Emergency Daemon (Python, UI-agnostic)                │
│   PanicEngine │ PresetResolver │ GeoTracker │           │
│   MediaChannel │ AckTracker │ RetryQueue │              │
│   TransportIntelligence (interface classify/rank/      │
│    failover/fanout + per-payload policy)               │
└───────────────┬─────────────────────────────────────────┘
                │ RNS API
┌───────────────▼─────────────────────────────────────────┐
│  Reticulum transport                                    │
│   [ own rnsd + identity ]  OR  [ shared Sideband/Columba│
│                                  instance via share API]│
└─────────────────────────────────────────────────────────┘
```

### Instance mode (selectable at first run, switchable later)
- **Standalone:** app creates/owns a Reticulum identity, runs or connects to `rnsd` (auto-start bundled config, or user-provided). App is its own RNS node.
- **Shared-instance:** app attaches to a running host app's RNS instance through that app's "share instance" interface. Supported hosts: **Sideband, Columba, MeshChat, MeshChatX (Linux)**. Reuses the host's identity, announces, interfaces — no second `rnsd`. Host selection offered at first run; user can switch host later.

### Core daemon modules (UI-agnostic — all logic lives here)
- **PanicEngine:** receives trigger events (UI / hardware keys / dead-man), dedupes, calls PresetResolver.
- **PresetResolver:** maps a trigger to a *preset* (config bundle): recipient set, payload toggles, severity, retry policy.
- **GeoTracker:** one-shot GPS fix *and* live-share mode (periodic updates over a RNS link or announce stream). Toggle per preset.
- **MediaChannel:** opens a RNS Link to recipient(s) and streams chunked audio frames / JPEG photo bursts. "Open a channel" = establish link + advertise media capability; receiver prompts to accept.
- **AckTracker:** per-recipient state — sent / delivered / acknowledged / replied. Retries unacked alerts per policy.
- **RetryQueue:** persists unsent alerts (no network/path) and flushes when transport returns; survives restart.
- **TransportIntelligence:** see dedicated section below. Classifies active RNS interfaces by type/bandwidth, ranks usable interfaces per recipient, enforces per-payload policy (e.g. never open audio/photo over LoRa), fans out critical alerts in parallel, and fails over fast when an interface/path dies.

## Triggers
| Trigger | Behavior |
|---|---|
| On-screen panic button | Hold-to-confirm (e.g. 1.5s) to prevent accidental; instant if long-press already armed. |
| Hardware key mapping | **Dynamic, user-chosen.** Options: (a) Android accessibility service capturing custom key combos (volume-down x3, power x5, etc.) in background; (b) integration with Android's built-in Emergency SOS / Safety Signal preset; (c) both. User picks method in settings; app requests the relevant permission at enable time (accessibility perm prompted only when that method is chosen). Desktop: global hotkey. Service must be running. |
| Dead-man switch *(optional, v1.1)* | User sets check-in window; missed check-in auto-fires. |

## Alert payload (all optional, ticked per preset)
- **Text + severity** — static message + level (`help` / `medical` / `danger`).
- **GPS location** — one-shot last-known, *and/or* live-share toggle (continuous updates).
- **Audio channel** — open mic stream over RNS link (chunked frames).
- **Photo channel** — open camera snapshot(s) over RNS link.
- **Ack / reply tracking** — per recipient: sent → ack → reply. Retry unacked.

User configures each preset: "send GPS + photo, live-share off, retry 5x/30s" etc.

## Recipients & contacts
- **Contact:** a saved RNS/LXMF destination (destination hash + display name). Source: discovered announces, or manual entry.
- **Discover / network screen (Columba-style):** live list of announced destinations heard on the mesh. Star button, or long-press → "Add to contacts", adds entry to the contacts list. This is the primary way users find each other.
- **Group (ad-hoc, assembled from contacts):** when firing an emergency, user picks a subset of contacts to alert together. Saved group presets are just named contact-subsets; the alert is fanned out to each member as an individual LXMF/RNS destination (no persistent shared group destination, no membership token needed). TransportIntelligence fans out across interfaces per-recipient.

## Alert transport (LXMF + raw RNS hybrid)
- **Text / GPS / ack / replies → LXMF messages.** Lets alerts land directly in Sideband, Columba, MeshChat, MeshChatX inboxes (custom message body + embedded location), and in this app's own inbox. Reuses LXMF delivery + delivery-state machinery.
- **Live audio / photo channel → raw RNS Link** (LXMF is not built for streaming). Established after the LXMF alert handshake; TransportIntelligence gates it to High/Medium tier only.
- **App-to-app alert (both ends run this app):** richer — full ack/reply tracking, and the receiver can **bypass silent / vibrate / DND** so a real emergency alarms at full volume even on a muted phone (Android: full-screen intent + critical notification channel that bypasses DND; desktop: audible alarm + always-on-top banner). Non-app recipients just get the LXMF message.
- **Incoming filter:** user toggle "receive alerts only from contacts" (default on). When off, alerts from any announced sender are shown (useful at large public events); when on, unknown senders are silently dropped/logged. Per-sender allow/deny list on top.

## Acknowledgement protocol (lightweight, app-level over RNS)
- Alert packet: `{preset_id, severity, text, geo?, media_link_id?, timestamp, alert_id}`.
- Recipient auto-acks `alert_id` on receipt; UI shows ack + offers canned replies ("coming" / "send help" / "safe").
- RetryQueue re-sends alert (and re-establishes media link) until ack or policy exhausted.

## Map screen — live location tracking
- **Map screen** shows all contacts currently sharing location live (incoming live-share streams) plus the user's own position.
- Each live-sharing contact = a marker on the map. User **taps a contact's marker (or picks them from a list on the map screen) to follow** — the map recenters on that contact and **tracks them as they move**, panning automatically with each new fix (like live-tracking in map/nav apps). Tap again / "stop following" to release.
- **Own live-share:** user can start broadcasting their own location live (one-shot or continuous) to a contact/group from here too.
- **Fix source + cadence:** positions arrive as GPS fixes over LXMF (one-shot) or a raw RNS link/announce stream (live). Live-share cadence governed by TransportIntelligence tier policy and the LoRa throttle interval above.
- **Offline maps:** maps must work with no internet (mesh-only scenario) — bundle offline tile set (e.g. MBTiles / OSM raster) and allow user-loaded tile packs. Online tiles used opportunistically when a WAN interface is up.
- **Privacy:** live-share is per-session and must be explicitly accepted by the viewer on first share (prompt: "X is sharing their location live — track?"). Viewer can stop tracking at any time; sharer sees who is tracking and can revoke.

## Transport intelligence & failover

### Why
Emergencies are time-critical and interfaces are unequal. LoRa (RNode) reaches far but is low-bandwidth — tested with Columba, audio/photo/conversation over it is unusable. Internet/LAN/I2P are fast but may be down or unavailable. BLE is short-range, medium bandwidth. The app must be smart enough to (a) know what is up, (b) pick the fastest interface that can actually carry the payload, (c) never start a heavy channel over a low-bandwidth link, (d) stay redundant so a single dead path does not cost seconds.

### Interface classification
TransportIntelligence reads the live RNS `Transport` instance and tags each active interface:

| RNS interface class | Tag | Bandwidth class | Typical use |
|---|---|---|---|
| `TCPInterface` (internet/LAN), `I2PInterface`, `LocalClientInterface` | `WAN/LAN` | **High** | Full payload incl. audio/photo |
| `AutoInterface` (LAN TCP + BLE modes) | `LAN` or `BLE` by mode | High / **Medium** | Text, GPS, photo; audio degraded |
| `RNodeInterface`, `KISSInterface`/`SerialInterface` (radio) | `LoRa/Radio` | **Low** | Text alert + GPS one-shot + ack only |
| Other / unknown | `Unknown` | treat as Low (safe default) |

Classification is config-overridable (user can force an interface tag) and cached, refreshed on interface up/down events and on a short poll (e.g. 2s) during an active emergency.

### Per-payload policy (what may ride which tier)
| Payload | Min tier allowed | Notes |
|---|---|---|
| Text alert + severity | Low (any) | Always sendable, even LoRa. |
| Ack / canned reply | Low (any) | Tiny. |
| GPS one-shot | Low (any) | Small coords packet. |
| GPS live-share | Medium+ | Periodic updates; on LoRa-only, throttle to user-chosen interval (see below). |
| Photo channel | High (degrade to thumbnail on Medium) | Block on LoRa entirely; auto-downscale by tier. |
| Audio channel | High only | Refuse to start on Medium/Low; surface "audio unavailable — interface is LoRa/BLE" to user. |

**Media caps are dynamic per-tier** (not one fixed ceiling): caps scale to the active interface's bandwidth class at runtime — e.g. photo resolution/quality and opus bitrate step down on Medium, drop to thumbnail-only on marginal Medium, and the channel refuses on Low. Exact per-tier numbers tuned in config; defaults reasonable (High: opus ~16kbps / JPEG 1280px; Medium: opus ~8kbps / JPEG 640px; Low: no media).

**LoRa GPS live-share interval (user-chosen):** when only LoRa is up and a preset wants live-share, the user picks the throttle interval: **15 / 30 / 60 / 120 / 180 seconds, or custom constrained to 10s–3600s (1h)**. Default 60s. If a faster interface is up, live-share runs at full rate and this throttle does not apply.

Rule: **a conversation/media channel is never opened over a Low interface.** If only LoRa is up, the alert still goes out (text + GPS one-shot + ack), and the UI says "media queued — waiting for faster path." RetryQueue flushes media once a High/Medium interface returns.

### Ranking & delivery plan
For each outgoing alert, TransportIntelligence builds a ranked interface list for the recipient:
1. Resolve which interfaces currently have a path/announce to the recipient (RNS announce reachability per interface).
2. Rank by: payload-tier compatibility first, then bandwidth class, then observed latency/RTT, then interface health (recent failures demoted).
3. Produce a **delivery plan** keyed by payload class:
   - **"Hail Mary" fan-out:** send the minimal alert (text+severity+GPS one-shot) in parallel over *every* compatible interface simultaneously. Whichever delivers first wins; the rest are deduped by `alert_id` at the receiver. Redundancy over elegance — fastest wall-clock to ack. **Default-on for `critical`/`danger`/`medical` presets only.** User can toggle: keep critical-only (default), extend Hail Mary to *all* presets, or disable fan-out entirely. UI explains it in plain terms: *"Send your alert over every available connection at once for the fastest possible delivery — uses more airtime. On for emergencies by default."*
   - **Sequential failover (when fan-out off, or for bandwidth-heavy payloads):** try fastest usable interface; on no-ack within short timeout (e.g. 3s) or link failure, fail over to next ranked interface immediately — no long backoff during an active emergency.
   - **Media channels:** only attempt on High (or Medium-degraded) interfaces; if none up, queue and keep probing interface state; auto-start when a qualifying interface appears.

### Failover behavior
- Monitor per-interface path status to recipient (announce freshness + link RTT + packet-send success/failure callbacks).
- On interface failure mid-send: abort that copy, immediately retry the same payload on the next-ranked interface, keep the original `alert_id` so receiver dedupes.
- Exponential backoff is suspended during an active (unacked, in-window) emergency — retries are fast and aggressive until ack or policy cap. After ack, normal backoff resumes for any lingering media flush.
- If *all* interfaces down/no path: RetryQueue persists; the moment any interface returns it fans out the queued alert immediately (fast path, not a slow poll).

### RNS-level enforcement
Where RNS lets the app steer transport:
- Send explicit packets on chosen interfaces where the RNS API permits (`Packet(..., interface=<iface>)` when available), and/or restrict announces to preferred interfaces so path hints steer over them.
- For fan-out, emit duplicate minimal-alert packets on each chosen interface with identical `alert_id`; receiver dedupes.
- Where RNS auto-routes and the API will not pin an interface, achieve policy by (a) ordering which interfaces are enabled/announced and (b) gating heavy payloads before send so they never enter a Low path.
- Treat shared-instance mode (Sideband/Columba) as reading the *host's* interface set — same classification applies; if the host exposes only LoRa, heavy payloads queue.

### UX
- Status bar shows live interface chips: `🌐 WAN ↑  📶 LAN ↑  📻 LoRa ↑  🔵 BLE ↓` with per-recipient path indicator.
- When a payload is blocked by tier policy, UI shows why and what is queued ("photo held — no fast interface; will send when one comes up").
- Preset editor shows each preset's required min tier so user knows which interfaces can carry it.

## Platform plan
| Platform | Status | Stack |
|---|---|---|
| Android | **v1 primary** | Python core + **Kivy UI** (single UI codebase, shared with desktop), build via python-for-android / Buildozer. Foreground service for hardware-key + dead-man + bypass-silent incoming alerts. |
| Linux desktop | **v1** | Same Python core + same Kivy UI (or native window). rnsd as systemd user unit or bundled. |
| Headless daemon | **v1** | UI-agnostic core; CLI control + status. Enables servers/routers to relay + alert. |
| iOS | Future | Deferred; architecture (Python core) may need native port or BeeWare. Not in v1 scope. |

**UI framework decision:** Kivy on both Android and desktop, one UI codebase. Chosen to minimize friction integrating the Python libraries the app depends on (`rns`, `lxmf`, etc.) and to ship one UI across platforms.

## Threat model notes
- Alerts must fire even with screen locked / app backgrounded → Android foreground service.
- No cloud → all routing via mesh; assume intermittent/no path. RetryQueue + persistence mandatory.
- Identity in shared-instance mode is the *host app's* identity — surface this to user so they understand alerts come from their Sideband identity, not a separate one.
- Prevent false alarms: hold-to-confirm + configurable "arm then press" for hardware keys.

## Decisions (locked)
1. **UI framework:** Kivy on both Android and desktop, single UI codebase (chosen for easiest `rns`/`lxmf` integration).
2. **Recipients & groups:** Columba-style discover/network screen receives announces; star or long-press → add to contacts. Emergency "group" = ad-hoc subset assembled from contacts; alert fanned out to each member as an individual destination. No persistent group destination / membership token.
3. **Media caps:** dynamic per-tier (scale with active interface bandwidth class); no single fixed ceiling.
4. **Shared-instance hosts:** selectable — standalone (own identity) or attach to Sideband / Columba / MeshChat / MeshChatX (Linux). Switchable later.
5. **Alert transport:** LXMF + raw RNS hybrid — LXMF for text/GPS/ack/replies (lands in Sideband/Columba/MeshChat/MeshChatX inboxes too), raw RNS Link for live audio/photo. App-to-app alerts can bypass silent/vibrate/DND on the receiver for real emergencies.
6. **Incoming alerts:** user toggle "receive only from contacts" (default on) + per-sender allow/deny.
7. **Hardware keys:** dynamic, user-chosen method — Android accessibility service (custom combos) and/or Android Emergency SOS preset integration; permission requested at enable time. Desktop: global hotkey.
8. **Fan-out ("Hail Mary"):** default-on for `critical`/`danger`/`medical` presets only; user can extend to all presets or disable, with a plain-language explanation in UI.
9. **LoRa GPS live-share:** user-chosen throttle interval — 15/30/60/120/180s or custom (10s–3600s), default 60s; only applies when LoRa is the sole up interface.
10. **Interface-tier manual override:** yes, advanced setting (user can re-tag an interface's bandwidth class).
11. **Map screen:** live location tracking — tap a live-sharing contact to follow; map recenters and tracks them as they move. Offline tiles bundled/loadable; online tiles opportunistic on WAN.

## Open items (minor, decide during build)
- Sideband/Columba/MeshChat/MeshChatX share-instance API specifics + version pins (verify each host's current share interface at build time).
- Offline map tile provider/format (MBTiles/OSM raster) and default bundle size for Android APK.
- Exact per-tier media cap numbers (tune against real RNode airtime budgets).

## Deliverables (v1)
- [ ] Emergency daemon (Python): PanicEngine, PresetResolver, GeoTracker, MediaChannel, AckTracker, RetryQueue, **TransportIntelligence**.
- [ ] RNS + LXMF transport layer: standalone identity + rnsd; shared-instance attach (Sideband/Columba/MeshChat/MeshChatX).
- [ ] Interface classification + per-payload tier policy + dynamic per-tier media caps + Hail Mary fan-out / fast failover.
- [ ] Contacts + discover/network screen (announce listener, star/long-press add to contacts).
- [ ] Ad-hoc group assembly from contacts.
- [ ] LXMF alert send/receive; raw RNS Link for live audio/photo.
- [ ] Incoming alert bypass-silent/DND (app-to-app); "receive only from contacts" toggle + per-sender allow/deny.
- [ ] Map screen: live location tracking (tap-to-follow), offline tiles, own live-share.
- [ ] Android app: Kivy UI, foreground service, panic button, dynamic hardware-key capture, presets, ack view.
- [ ] Linux desktop app: same Kivy UI on same daemon.
- [ ] Headless CLI: trigger + status + config.
- [ ] Preset + contact config persistence.
- [ ] Docs: setup (standalone + shared-instance per host), preset authoring, discover/contacts, map.

## Suggested build order
1. Daemon core + standalone RNS identity + LXMF single-contact text alert end-to-end (CLI first).
2. Ack tracking + RetryQueue.
3. **TransportIntelligence: interface classification + tier policy + ranking** (gate here before adding heavy payloads).
4. **Hail Mary fan-out + fast failover** for the text alert (critical presets default-on).
5. GPS one-shot, then live-share (respect tier; LoRa throttle interval user-config 10–3600s).
6. Contacts + discover/network screen (announce listener, add-to-contacts).
7. Ad-hoc group assembly from contacts.
8. Map screen: own position + incoming live-share markers + tap-to-follow tracking + offline tiles.
9. Incoming alert bypass-silent/DND + "receive only from contacts" toggle + per-sender allow/deny.
10. Preset system + on-screen panic button (Kivy); preset shows required min tier + fan-out toggle.
11. Dynamic hardware-key capture (Android service + perm prompt / Emergency SOS preset).
12. Shared-instance attach mode (Sideband/Columba/MeshChat/MeshChatX) — reuse host interface set, same classification.
13. Audio/photo channel over raw RNS link — High/Medium tier only, dynamic per-tier caps, degrade/refuse on Low, queue when no fast path.
14. Desktop UI pass + interface-chip status bar.
15. Hardening, persistence, docs.

## Reference stack
- Reticulum / RNS — `rns` Python package, `rnsd`, Link/Channel/Destination/Transport APIs.
- LXMF — `lxmf` Python package; messaging/delivery over RNS (Sideband/Columba/MeshChat/MeshChatX all speak LXMF).
- Sideband — Android RNS/LXMF client; "share instance" interface reference.
- Columba — RNS/LXMF client; discover/contacts UX reference + share-instance target.
- MeshChat / MeshChatX — LXMF clients (MeshChatX on Linux); share-instance targets.
- Kivy + python-for-android / Buildozer — Android + desktop UI, single codebase.

---

# v0.2 — Handoff & remaining work

Staff-pass for the next agent (Claude Code). Read this section first; it
explains where v0.1 landed, the conventions to keep, the CI mechanics that bite,
and the ordered v0.2 backlog. **Everything below "Reference stack" is the only
part that changes between handoffs — keep it current.**

## Where v0.1 landed (all green in CI)
- **Backend + CLI**: complete. Daemon, transport intelligence, ack/retry,
  presets/panic, contacts/groups/discover, incoming filter + app-level
  ack/reply, shared-instance attach, media-channel + hardware-key + geo
  *logic* (platform bindings still stubbed). ~250+ pytest tests.
- **CLI round-trip**: send → `serve` (interactive console: `reply`/`ack`/
  `inbox`/`quit`) → inbox registry → reply/ack by id.
- **Kivy UI** (`main.py` + `retalert/ui/controller.py`): 8 screens — Home
  (panic + interface/tier status), Send, Inbox (reply/ack), Outbox (per-
  recipient ack state), Presets (one-tap fire), Map, Contacts, Settings.
- **Map**: selectable tile providers; offline-area download to MBTiles
  (radius 5/10/20/50/100 km); tap-to-follow real-time peer tracking (re-centers,
  keeps zoom); km/mi distance readout; Android GPS + runtime location-permission
  prompt (plyer).
- **CI**: `ci.yml` (pytest 3.11–3.13 + wheel), `android.yml` (Buildozer APK —
  **builds green, ~22 MB artifact**), `desktop.yml` (PyInstaller Linux binary),
  `release.yml` (tag `v*` → GitHub Release with APK + desktop binary + wheel;
  signing wired via secrets, hyphen tags = pre-release).
- **Release signing**: `scripts/make-keystore.sh` + `docs/ANDROID_SIGNING.md`;
  CI signs when the four `ANDROID_*` secrets are set, else builds unsigned.

## Conventions — KEEP THESE
1. **Testable-controller rule.** All UI logic goes in
   `retalert/ui/controller.py` (no Kivy import) or `retalert/core/*`, with a
   unit test. `main.py` stays a thin Kivy shell. The Kivy screens are validated
   only by the desktop PyInstaller build compiling — so any real logic must be
   in the controller/core where it can be pytested.
2. **Network off the UI thread.** Screens call `_run_bg(fn, *args, on_done=cb)`;
   results marshal back via `Clock`. Never block the Kivy thread on RNS/LXMF.
3. **Guard platform imports.** `android.*`, `plyer`, `kivy_garden.mapview` are
   imported lazily inside try/except so desktop/headless still runs.
4. **Commit per step; push when a step is a coherent whole.** Batch app-code
   commits and push together to avoid stacking ~30-min APK builds.

## Build / test / run
```sh
.venv/bin/python -m pytest -q                  # full suite (run from repo root)
pip install -e ".[ui]" && python main.py       # desktop app
retalert --help                                # headless CLI
python scripts/make_icon.py                     # regenerate data/icon.png
```

## CI gotchas (learned the hard way)
- **APK builds with a direct Buildozer invocation**, not a Docker action (the
  old `ArtemSBulgakov/buildozer-action` fails at its own image build). JDK 17 +
  pinned `buildozer>=1.5` + `cython==0.29.36` (p4a breaks on Cython 3).
- **`android.yml` is `cancel-in-progress: false`** — a new push must not kill a
  running 30-min APK build. android/desktop have `paths:` filters (only rebuild
  on app-code changes); the buildozer SDK/NDK cache key is
  `hashFiles('buildozer.spec')`, so changing the spec forces a full rebuild.
- **Desktop PyInstaller needs `xvfb-run` + `KIVY_GL_BACKEND=mock`** — the Kivy
  hook imports `kivy.core.window` during analysis (needs a display).
- **p4a needs transitive pure-Python deps listed explicitly** in
  `buildozer.spec` `requirements` (e.g. mapview → requests, urllib3, idna,
  charset-normalizer, certifi).
- Watch a run: `gh run watch <id> --exit-status`; logs:
  `gh run view <id> --log-failed`.

## v0.2 backlog (ordered; each = one testable step where possible)
1. **On-device media capture** (spec step 13 UI). Implement
   `LinkAdapter.send` (`retalert/core/media_channel.py:219`) and `RNSLink`
   (`retalert/transport/rns_link.py:16/19/22`) over a live `RNS.Link`; capture
   via plyer camera + audio. Put framing/chunking logic in core with tests;
   keep capture calls in `main.py`.
2. **Foreground service** so the daemon keeps listening when backgrounded.
   p4a service entrypoint + buildozer `services =`; `FOREGROUND_SERVICE` perm
   already declared. Add a persistent notification.
3. **Incoming-alert notification / bypass-silent** (spec step 9 UX). Wire
   `IncomingDispatcher.bypass_silent_cb` (`retalert/core/incoming.py:193`) to a
   platform notifier (plyer.notification + sound/vibrate) via the controller.
4. **Offline map use + management.** Switch `MapView` to a downloaded MBTiles
   source (`kivy_garden.mapview.mbtsource.MBTilesMapSource`); list/delete
   offline maps (`controller.offline_maps()` exists); add a download progress
   bar (downloader already takes a `progress` callback).
5. **Hardware-key capture (Android)**: implement `KeyCaptureBackend.start`
   (`retalert/core/hardware_keys.py:210`) — key-event service + permission
   prompt → feeds `HardwareKeyManager`.
6. **Desktop/own location without GPS**: implement `LinuxFixSource`
   (`retalert/core/geo_tracker.py:125`) or add a manual "set my location" entry
   on the Map screen (controller has `update_own_location`).
7. **Signed v0.1.0 release**: user adds the keystore secrets
   (`docs/ANDROID_SIGNING.md`), then `git tag v0.1.0 && git push --tags`.
8. **Polish**: own-position marker distinct from peers + marker callouts;
   multi-arch APK (add `armeabi-v7a`); bump actions off Node 20.
9. **iOS** — deferred per spec (architecture allows BeeWare/native later).

## Key files
- `retalert/daemon.py` — orchestrator (owns RNS/LXMF + all stores).
- `retalert/ui/controller.py` — UI façade (start here for any UI feature).
- `main.py` — Kivy screens.
- `retalert/core/` — all logic (alert, ack_tracker, retry_queue, transport_intel,
  geo_tracker, live_tracks, incoming, inbox, preset, panic_engine, map_tiles,
  media_channel, hardware_keys).
- `.github/workflows/` — ci / android / desktop / release.
- `buildozer.spec` — APK config (requirements, perms, icon, archs).
- Map: offline-capable (MBTiles / OSM raster) map widget for Kivy (e.g. `mapview` or equivalent), online tiles opportunistic.