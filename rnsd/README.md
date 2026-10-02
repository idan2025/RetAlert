# Meshtastic interface for rnsd

`Meshtastic_Interface.py` lets a desktop/Pi `rnsd` run Reticulum over a
Meshtastic node, wire-compatible with RetAlert's built-in Meshtastic interface
and with upstream [RNS_Over_Meshtastic](https://github.com/landandair/RNS_Over_Meshtastic)
(it's a fork of that file with the RetAlert port's fixes; see the header).

## Install

```sh
pip install meshtastic
cp Meshtastic_Interface.py ~/.reticulum/interfaces/
```

Add to `~/.reticulum/config`:

```ini
  [[Meshtastic]]
    type = Meshtastic_Interface
    enabled = yes
    mode = gateway
    port = /dev/serial/by-id/usb-...   # or ble_port = ..., or tcp_port = host:4403
    channel = 0       # tunnel channel index; must exist on the node
    hop_limit = 3     # 0-7; higher floods every relay on the channel
```

The node's radio settings are never changed; send pacing follows its modem
preset. Every RNS node on the tunnel needs the same preset, frequency and
channel key. Don't connect the Meshtastic app to the node at the same time.

In Docker, install `meshtastic` in the image and pass the node through, e.g.
`devices: ["/dev/serial/by-id/usb-...:/dev/meshtastic"]`, then `port = /dev/meshtastic`.

## Tests

```sh
pip install rns meshtastic pytest
python -m pytest rnsd/
```
