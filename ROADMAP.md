# RetAlert roadmap

Ideas for what to build next, ordered by how much they help in a real
emergency. Current release: **0.5.1**.

## Highest value

### 1. Quick replies on the alert pop-up — ✅ done (0.5)
The full-screen alert only offers **Stop alarm**. Add one-tap replies —
**On my way**, **Can't come**, **Call me** — that send a reply straight from
the pop-up (and from the notification). The sender then sees *who is coming*,
not just "acked".
- Builds on the existing reply path (`Wire.reply`, Outbox reply state).
- Reply texts editable in Settings.

### 2. Store-and-forward delivery (LXMF propagation nodes)
If a recipient's phone is off or out of range, RetAlert retries and eventually
gives up. LXMF propagation nodes hold messages until the recipient is reachable
again, and lxmf-kt supports them.
- Fall back to a propagation node when direct delivery keeps failing.
- Pick a node automatically (heard announces) or set one in Settings.
- Show "queued on propagation node" in Sent alerts.

### 3. Escalation when nobody answers
If no recipient acknowledges within N minutes: send to a second group, resend
with current location, or both. Today an unanswered alert only keeps retrying
the same people.
- Per preset: escalation group + delay.

### 4. Check-in timer (dead man's switch)
"If I don't check in within 30 minutes, send my alert with my location."
For hiking, lone work or an unsafe meeting — when you may not be able to press
anything.
- Start from Home or a preset; reminder notification before it fires; one tap
  to extend or cancel.

### 5. Add contacts by QR code
Typing a 32-character address is error-prone. Show your own address as a QR
code and scan others'. Read Columba's QR identity codes too.

### Per-alert chat — ✅ done (0.5)
An alert is one message plus one reply today. Give every alert a conversation
thread — on the sender's and the receiver's side — so both can keep talking
("Where are you?" / "Ground floor, door is open"). Plain LXMF messages, so
Columba and Sideband users can join in.

## Strong additions

### 6. Panic from the home screen and lock screen
A home-screen widget and a Quick Settings tile, alongside the volume-key
trigger.

### 7. Silent panic (duress mode)
Sends the alert and location with no sound, vibration or visible screen on the
sender's phone — for when making noise is dangerous.

### 8. Backup and restore identity and contacts
Reinstalling or changing phones creates a new address, so everyone must re-add
you. Add an encrypted export/import of identity, contacts, groups and presets.

### 9. Meshtastic private-channel helper
Generate the RetAlert secondary channel (name + random key) once and share it
as a QR code / Meshtastic channel URL, so every node gets the same settings
without manual setup.

### 10. Hebrew interface
A real translation with right-to-left layout done properly (the UI is forced
left-to-right today because it is English-only).

## Smaller items

- **Real database migrations** — the Room database falls back to a destructive
  migration, so any schema change would wipe contacts, settings and history.
  Add proper migrations before the next schema change.
- **Responder map** — show alert recipients who share their location back,
  with distance and ETA.
- **Low-battery alert** — automatic alert with last location when the battery
  runs low.
- **Connection warning on Home** — a clear banner when no interface is online.
- **Hardware not yet tested** — RNode over USB and Bluetooth, Bluetooth LE mesh,
  I2P.
- **Photo and voice attachments** — code exists but isn't finished; needs a
  Reticulum link transfer for media.

## Suggested next step
Quick replies and per-alert chat shipped in 0.5. Next: **store-and-forward
(2)**, so alerts still reach people who are offline for a while, then
**escalation (3)**.
