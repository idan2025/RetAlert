# RetAlert Architecture

Full spec: [`PROMPT.md`](../PROMPT.md) (read it first). This file is a quick
module map + current build status.

## Layout
```
retalert/
  config.py            paths + storage dir resolution + default config copy
  storage.py           Contacts / Groups / Presets JSON persistence
  daemon.py            EmergencyDaemon: owns RNS + Identity + LXMFTransport
  updater.py           self-updater: GitHub releases, latest-tag default, self-cleaning
  cli.py / __main__.py CLI entrypoints (init/identity/serve/send/contacts/update)
  transport/
    identity.py        create/load RNS.Identity (standalone)
    lxmf_transport.py  LXMRouter wrapper: announce, send_message, incoming cb
    rns_link.py        stub — raw RNS Link for live media (build step 13)
    share_instance.py  attach to host RNS instance (local socket or TCP client) (step 12)
  core/
    alert.py              Alert model + delivery states
    panic_engine.py       PanicEngine: trigger->preset->alert dispatch (step 10)
    preset.py             Preset config bundle + PresetStore (step 10)
    preset_resolver.py    stub — trigger->preset (now in panic_engine.PresetResolver)
    ack_tracker.py        per-recipient delivery/ack state (step 2)
    retry_queue.py        persistent unsent queue, fast retry (step 2)
    geo_tracker.py        GPS one-shot + live-share thread + LoRa throttle (step 5)
    announce_engine.py    manual + auto-announce loop, 30min-12h interval (step 6)
    discover.py           heard-announce cache + starred set, RNS handler (step 6)
    live_tracks.py        incoming live-share store + tap-to-follow (step 8)
    incoming.py           receive filter + geo/alert parse + bypass-silent hook (steps 8-9)
    hardware_keys.py      key combo -> preset trigger, arm/dedup, persist (step 11)
    media_channel.py      chunked photo + audio stream over RNS Link seam (step 13)
    transport_intel.py    interface classify/rank/fanout/failover (steps 3-4)
```

## Build order (from PROMPT.md)
1. Daemon core + standalone identity + LXMF single-contact text alert (CLI) — **done**
2. Ack tracking + RetryQueue — **done**
3-4. TransportIntelligence: classify + tier policy + ranking + Hail Mary fan-out / fast failover — **done**
3. TransportIntelligence: classify + tier policy + ranking
4. Hail Mary fan-out + fast failover
5. GPS one-shot, then live-share (LoRa throttle) — **done**
6. Contacts + discover/network screen — **done** (backend: AnnounceEngine + Discover; CLI: announce/auto, discover list/clear/star/unstar)
7. Ad-hoc group assembly from contacts — **done** (Groups store + CLI group create/list/show/remove/add-member/remove-member; send --to-group fans out per-member)
8. Map screen: live tracking + offline tiles — backend done (LiveTrackStore + geo parse); UI deferred (Kivy)
9. Incoming bypass-silent/DND + receive-from-contacts toggle + app-level ack — **done** (IncomingDispatcher + Settings; bypass-silent hook stub; v1 wire format `!RETALERT!id:<aid>!<sev>!<text>` + `!RETALERT!ack!<aid>` → AckTracker DELIVERED→ACKED)
10. Preset system + on-screen panic button (Kivy) — preset backend **done** (Preset/PresetStore/PanicEngine/PresetResolver + CLI preset/panic); on-screen button + hold-to-confirm UI deferred (Kivy)
11. Dynamic hardware-key capture — backend **done** (HardwareKeyManager: combo->trigger, arm-then-fire, cooldown dedup, persist; KeyCaptureBackend platform seam stub); Android accessibility / desktop hotkey capture deferred
12. Shared-instance attach (Sideband/Columba/MeshChat/MeshChatX) — **done** (SharedInstanceManager: local socket or TCPClientInterface, RNS config render, host-identity reuse; CLI share attach/status/detach)
13. Audio/photo channel over raw RNS link — **done** (MediaChannel: chunked photo reassembly + audio stream, tier-gated High-only, LinkAdapter seam for live RNS.Link)
14. Desktop UI pass + interface-chip status bar
15. Hardening, persistence, docs

Updater is an early-priority cross-cutting feature, implemented alongside step 1.

## Data flow (step 1)
```
CLI -> EmergencyDaemon -> LXMFTransport -> LXMRouter (LXMF) -> RNS -> mesh
                          ^-- register_delivery_callback --> incoming print
```