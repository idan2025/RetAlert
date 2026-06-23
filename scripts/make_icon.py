#!/usr/bin/env python3
"""Generate the RetAlert app icon + presplash as PNGs, using only the stdlib
(zlib + struct) so there's no Pillow build dependency.

Emergency theme: deep-red field with a white cross (aid) and three radio-wave
arcs (an emergency alert going out over the Reticulum mesh) — distinguishes
RetAlert from a generic first-aid kit icon.

Usage: python scripts/make_icon.py   # writes data/icon.png and data/presplash.png
"""
from __future__ import annotations

import math
import os
import struct
import zlib

RED = (192, 57, 43)
RED_DARK = (160, 44, 33)      # subtle field gradient (outer)
WHITE = (245, 245, 245)
DARK = (30, 30, 34)           # outside the rounded field (icon corners)


def _png(width: int, height: int, pixels: bytes) -> bytes:
    """Encode RGB pixels (row-major, 3 bytes/pixel) as a PNG."""
    raw = bytearray()
    stride = width * 3
    for y in range(height):
        raw.append(0)                      # filter type 0 (None)
        raw += pixels[y * stride:(y + 1) * stride]

    def chunk(typ: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + typ + data
                + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)  # 8-bit truecolor
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b""))


def _rounded_field(size, x, y, radius):
    """True if (x,y) is outside the rounded red field (-> dark corner)."""
    cx = min(x, size - 1 - x)
    cy = min(y, size - 1 - y)
    if cx < radius and cy < radius and \
            (radius - cx) ** 2 + (radius - cy) ** 2 > radius ** 2:
        return True
    return False


def _on_cross(size, x, y, arm, reach):
    """True if (x,y) is on the centered white cross."""
    half = size // 2
    return ((abs(x - half) <= arm and abs(y - half) <= reach) or
            (abs(y - half) <= arm and abs(x - half) <= reach))


def _on_waves(size, x, y, origin, radii, thick):
    """True if (x,y) is on one of the radio-wave arcs in the upper-right
    quadrant of ``origin`` (an emergency alert radiating out)."""
    ox, oy = origin
    dx, dy = x - ox, y - oy
    if dx <= 0 or dy >= 0:          # only upper-right quadrant
        return False
    d = math.hypot(dx, dy)
    for r in radii:
        if abs(d - r) <= thick:
            return True
    return False


def _render(size: int, rounded: bool = True) -> bytes:
    """Cross + radio-wave arcs on a red field.

    ``rounded``: True for the launcher icon (dark corners outside the field);
    False for the presplash (full-bleed red, no corner mask)."""
    buf = bytearray(size * size * 3)
    arm = size * 11 // 100          # half-thickness of the cross arms
    reach = size * 32 // 100       # half-length of each arm
    radius = size * 13 // 100       # corner rounding (icon only)
    # Radio waves in the upper-right, radiating up-right from this origin.
    origin = (int(size * 0.80), int(size * 0.20))
    radii = (size * 7 // 100, size * 12 // 100, size * 17 // 100)
    thick = max(2, size // 64)
    dot_r = max(2, size // 70)     # broadcast origin dot
    cx0, cy0 = size // 2, size // 2
    for y in range(size):
        for x in range(size):
            if rounded and _rounded_field(size, x, y, radius):
                r, g, b = DARK
            elif _on_cross(size, x, y, arm, reach):
                r, g, b = WHITE
            elif _on_waves(size, x, y, origin, radii, thick):
                r, g, b = WHITE
            elif math.hypot(x - origin[0], y - origin[1]) <= dot_r:
                r, g, b = WHITE
            else:
                # subtle radial shade: darker at the edges, lighter at center
                t = math.hypot(x - cx0, y - cy0) / (size * 0.72)
                t = max(0.0, min(1.0, t))
                r = int(RED[0] + (RED_DARK[0] - RED[0]) * t)
                g = int(RED[1] + (RED_DARK[1] - RED[1]) * t)
                b = int(RED[2] + (RED_DARK[2] - RED[2]) * t)
            i = (y * size + x) * 3
            buf[i], buf[i + 1], buf[i + 2] = r, g, b
    return _png(size, size, bytes(buf))


def main() -> None:
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    data = os.path.join(here, "data")
    os.makedirs(data, exist_ok=True)
    with open(os.path.join(data, "icon.png"), "wb") as f:
        f.write(_render(512, rounded=True))
    with open(os.path.join(data, "presplash.png"), "wb") as f:
        f.write(_render(512, rounded=False))
    print("wrote data/icon.png and data/presplash.png (cross + radio waves)")


if __name__ == "__main__":
    main()