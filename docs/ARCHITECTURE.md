# RetAlert Architecture

Full spec: [`PROMPT.md`](../PROMPT.md) (read it first). This file is a quick
module map + current build status.

## Layout
```
retalert/
  config.py            paths + storage dir resolution + default config copy
  storage.py           Contacts / Presets JSON persistence
  daemon.py            EmergencyDaemon: owns RNS + Identity + LXMFTransport
  updater.py           self-updater: GitHub releases, latest-tag default, self-cleaning
  cli.py / __main__.py CLI entrypoints (init/identity/serve/send/contacts/update)
  transport/
    identity.py        create/load RNS.Identity (standalone)
    lxmf_transport.py  LXMRouter wrapper: announce, send_message, incoming cb
    rns_link.py        stub — raw RNS Link for live media (build step 13)
    share_instance.py  stub — attach to Sideband/Columba/MeshChat/MeshChatX (step 12)
  core/
    alert.py              Alert model + delivery states
    panic_engine.py       stub — trigger dispatch (step 10)
    preset_resolver.py    stub — trigger->preset (step 10)
    ack_tracker.py        per-recipient delivery/ack state (step 2)
    retry_queue.py        persistent unsent queue, fast retry (step 2)
    geo_tracker.py        GPS one-shot + live-share thread + LoRa throttle (step 5)
    media_channel.py      stub — audio/photo over RNS Link (step 13)
    transport_intel.py    interface classify/rank/fanout/failover (steps 3-4)
```

## Build order (from PROMPT.md)
1. Daemon core + standalone identity + LXMF single-contact text alert (CLI) — **done**
2. Ack tracking + RetryQueue — **done**
3-4. TransportIntelligence: classify + tier policy + ranking + Hail Mary fan-out / fast failover — **done**
3. TransportIntelligence: classify + tier policy + ranking
4. Hail Mary fan-out + fast failover
5. GPS one-shot, then live-share (LoRa throttle) — **done**
6. Contacts + discover/network screen
7. Ad-hoc group assembly from contacts
8. Map screen: live tracking + offline tiles
9. Incoming bypass-silent/DND + receive-from-contacts toggle
10. Preset system + on-screen panic button (Kivy)
11. Dynamic hardware-key capture
12. Shared-instance attach (Sideband/Columba/MeshChat/MeshChatX)
13. Audio/photo channel over raw RNS link
14. Desktop UI pass + interface-chip status bar
15. Hardening, persistence, docs

Updater is an early-priority cross-cutting feature, implemented alongside step 1.

## Data flow (step 1)
```
CLI -> EmergencyDaemon -> LXMFTransport -> LXMRouter (LXMF) -> RNS -> mesh
                          ^-- register_delivery_callback --> incoming print
```