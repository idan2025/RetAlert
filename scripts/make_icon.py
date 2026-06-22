#!/usr/bin/env python3
"""Generate the RetAlert app icon + presplash as PNGs, using only the stdlib
(zlib + struct) so there's no Pillow build dependency.

Emergency theme: deep-red rounded field with a white cross.

Usage: python scripts/make_icon.py   # writes data/icon.png and data/presplash.png
"""
from __future__ import annotations

import os
import struct
import zlib

RED = (192, 57, 43)
WHITE = (245, 245, 245)
DARK = (30, 30, 34)


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


def _render(size: int, bg=RED, fg=WHITE) -> bytes:
    """A centered cross on a rounded field."""
    buf = bytearray(size * size * 3)
    arm = size * 11 // 100        # half-thickness of the cross arms
    half = size // 2
    reach = size * 32 // 100      # half-length of each arm
    radius = size * 12 // 100     # corner rounding
    for y in range(size):
        for x in range(size):
            # rounded-corner mask -> dark background outside the field
            cx = min(x, size - 1 - x)
            cy = min(y, size - 1 - y)
            if cx < radius and cy < radius and \
                    (radius - cx) ** 2 + (radius - cy) ** 2 > radius ** 2:
                r, g, b = DARK
            elif (abs(x - half) <= arm and abs(y - half) <= reach) or \
                    (abs(y - half) <= arm and abs(x - half) <= reach):
                r, g, b = fg
            else:
                r, g, b = bg
            i = (y * size + x) * 3
            buf[i], buf[i + 1], buf[i + 2] = r, g, b
    return _png(size, size, bytes(buf))


def main() -> None:
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    data = os.path.join(here, "data")
    os.makedirs(data, exist_ok=True)
    with open(os.path.join(data, "icon.png"), "wb") as f:
        f.write(_render(512))
    with open(os.path.join(data, "presplash.png"), "wb") as f:
        f.write(_render(512))
    print("wrote data/icon.png and data/presplash.png")


if __name__ == "__main__":
    main()
