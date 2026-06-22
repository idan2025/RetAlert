"""Offline map tiles — slippy-map math, tile providers, and an MBTiles writer
so a user can pre-download a map around a destination for use with no network.

Pure-Python and dependency-free (sqlite3 + urllib from the stdlib); the HTTP
fetch is injectable so the download logic is unit-testable offline. The Kivy
map screen consumes this via the UI controller; kivy_garden.mapview can read
the resulting ``.mbtiles`` file directly as an offline source.

MBTiles spec: a SQLite DB with a ``tiles(zoom_level, tile_column, tile_row,
tile_data)`` table addressed in TMS scheme (y flipped vs. the XYZ/slippy used
by web tile servers) plus a ``metadata`` name/value table.
"""
from __future__ import annotations

import math
import sqlite3
from dataclasses import dataclass
from typing import Callable, Dict, Iterator, List, Optional, Tuple

# Radius presets (km) offered in the UI for an offline download.
RADIUS_OPTIONS: List[int] = [5, 10, 20, 50, 100]

BBox = Tuple[float, float, float, float]  # (min_lat, min_lon, max_lat, max_lon)


# -- tile providers -----------------------------------------------------

@dataclass(frozen=True)
class TileProvider:
    key: str
    name: str
    url_template: str          # XYZ template with {z} {x} {y}
    attribution: str
    max_zoom: int = 19
    tile_size: int = 256

    def tile_url(self, z: int, x: int, y: int) -> str:
        return self.url_template.format(z=z, x=x, y=y)


# Keep to providers with permissive tile-usage policies; attribution is carried
# into the MBTiles metadata. (Heavy bulk downloads should respect each
# provider's tile-usage policy.)
PROVIDERS: Dict[str, TileProvider] = {
    "osm": TileProvider(
        "osm", "OpenStreetMap",
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        "© OpenStreetMap contributors", 19),
    "opentopomap": TileProvider(
        "opentopomap", "OpenTopoMap",
        "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
        "© OpenTopoMap (CC-BY-SA), © OpenStreetMap contributors", 17),
    "carto_light": TileProvider(
        "carto_light", "Carto Light",
        "https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png",
        "© OpenStreetMap contributors, © CARTO", 20),
    "carto_dark": TileProvider(
        "carto_dark", "Carto Dark",
        "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        "© OpenStreetMap contributors, © CARTO", 20),
    "esri_satellite": TileProvider(
        "esri_satellite", "Esri World Imagery",
        "https://server.arcgisonline.com/ArcGIS/rest/services/"
        "World_Imagery/MapServer/tile/{z}/{y}/{x}",
        "Source: Esri, Maxar, Earthstar Geographics", 18),
}

DEFAULT_PROVIDER = "osm"


def provider_keys() -> List[str]:
    return list(PROVIDERS)


def get_provider(key: str) -> TileProvider:
    return PROVIDERS[key]


# -- slippy-map math ----------------------------------------------------

def deg2num(lat: float, lon: float, zoom: int) -> Tuple[int, int]:
    """(lat, lon) -> (xtile, ytile) at ``zoom`` (XYZ scheme), clamped to range."""
    lat = max(min(lat, 85.05112878), -85.05112878)
    n = 2 ** zoom
    x = int((lon + 180.0) / 360.0 * n)
    y = int((1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n)
    return _clamp(x, n), _clamp(y, n)


def num2deg(x: int, y: int, zoom: int) -> Tuple[float, float]:
    """North-west corner (lat, lon) of tile (x, y) at ``zoom``."""
    n = 2 ** zoom
    lon = x / n * 360.0 - 180.0
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / n))))
    return lat, lon


def _clamp(v: int, n: int) -> int:
    return max(0, min(v, n - 1))


def radius_bbox(lat: float, lon: float, radius_km: float) -> BBox:
    """Bounding box covering ``radius_km`` around (lat, lon)."""
    dlat = radius_km / 111.32
    dlon = radius_km / (111.32 * max(math.cos(math.radians(lat)), 1e-6))
    return (lat - dlat, lon - dlon, lat + dlat, lon + dlon)


def tiles_for_bbox(bbox: BBox, zoom: int) -> Iterator[Tuple[int, int, int]]:
    """Yield (z, x, y) XYZ tiles covering ``bbox`` at a single ``zoom``."""
    min_lat, min_lon, max_lat, max_lon = bbox
    x0, y0 = deg2num(max_lat, min_lon, zoom)   # north-west
    x1, y1 = deg2num(min_lat, max_lon, zoom)   # south-east
    for x in range(min(x0, x1), max(x0, x1) + 1):
        for y in range(min(y0, y1), max(y0, y1) + 1):
            yield (zoom, x, y)


def default_zooms_for_radius(radius_km: float) -> List[int]:
    """A sensible zoom span per radius — more detail for small areas — kept
    modest so downloads stay in the hundreds/low-thousands of tiles."""
    top = {5: 16, 10: 15, 20: 14, 50: 13, 100: 12}
    hi = top.get(int(radius_km), 13)
    return list(range(max(hi - 3, 1), hi + 1))


def tiles_for_radius(lat: float, lon: float, radius_km: float,
                     zooms: Optional[List[int]] = None
                     ) -> List[Tuple[int, int, int]]:
    zooms = zooms if zooms is not None else default_zooms_for_radius(radius_km)
    bbox = radius_bbox(lat, lon, radius_km)
    out: List[Tuple[int, int, int]] = []
    for z in zooms:
        out.extend(tiles_for_bbox(bbox, z))
    return out


def estimate_tile_count(lat: float, lon: float, radius_km: float,
                        zooms: Optional[List[int]] = None) -> int:
    return len(tiles_for_radius(lat, lon, radius_km, zooms))


# -- MBTiles writer -----------------------------------------------------

class MBTilesWriter:
    """Minimal MBTiles (SQLite) writer. Stores XYZ tiles with the TMS y-flip
    the MBTiles spec mandates."""

    def __init__(self, path: str):
        self.conn = sqlite3.connect(path)
        self._init_schema()

    def _init_schema(self) -> None:
        c = self.conn
        c.execute("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
        c.execute("CREATE TABLE IF NOT EXISTS tiles ("
                  "zoom_level INTEGER, tile_column INTEGER, "
                  "tile_row INTEGER, tile_data BLOB)")
        c.execute("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles "
                  "(zoom_level, tile_column, tile_row)")
        self.conn.commit()

    def set_metadata(self, **pairs) -> None:
        for name, value in pairs.items():
            self.conn.execute("INSERT INTO metadata (name, value) VALUES (?, ?)",
                              (name, str(value)))
        self.conn.commit()

    def add_tile(self, z: int, x: int, y: int, data: bytes) -> None:
        tms_y = (2 ** z - 1) - y  # XYZ -> TMS
        self.conn.execute(
            "INSERT OR REPLACE INTO tiles "
            "(zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)",
            (z, x, tms_y, sqlite3.Binary(data)))

    def count(self) -> int:
        return self.conn.execute("SELECT COUNT(*) FROM tiles").fetchone()[0]

    def commit(self) -> None:
        self.conn.commit()

    def close(self) -> None:
        self.conn.commit()
        self.conn.close()


# -- downloader ---------------------------------------------------------

def _http_fetch(url: str, timeout: float = 20.0) -> Optional[bytes]:
    """Fetch a tile over HTTP. A descriptive User-Agent is required by OSM's
    tile-usage policy. Returns bytes, or None on any error."""
    import urllib.request
    req = urllib.request.Request(url, headers={"User-Agent": "RetAlert/0.1"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.read()
    except Exception:
        return None


# Refuse absurd jobs by default so a wrong radius/zoom can't try to pull
# millions of tiles.
MAX_TILES = 50000


class TileDownloader:
    """Downloads the tiles covering a radius into an MBTiles file."""

    def __init__(self, provider: TileProvider,
                 fetch: Optional[Callable[[str], Optional[bytes]]] = None):
        self.provider = provider
        self._fetch = fetch or _http_fetch

    def download(self, lat: float, lon: float, radius_km: float, out_path: str,
                 zooms: Optional[List[int]] = None,
                 progress: Optional[Callable[[int, int], None]] = None,
                 max_tiles: int = MAX_TILES) -> dict:
        """Download tiles around (lat, lon) within ``radius_km`` into an
        ``.mbtiles`` at ``out_path``. Returns a summary dict. Raises ValueError
        if the job exceeds ``max_tiles``."""
        zooms = zooms if zooms is not None else default_zooms_for_radius(radius_km)
        tiles = tiles_for_radius(lat, lon, radius_km, zooms)
        if len(tiles) > max_tiles:
            raise ValueError(f"{len(tiles)} tiles exceeds max_tiles={max_tiles}; "
                             f"reduce radius or zoom")
        bbox = radius_bbox(lat, lon, radius_km)
        writer = MBTilesWriter(out_path)
        writer.set_metadata(
            name=f"RetAlert {self.provider.name} r{int(radius_km)}km",
            type="baselayer", version="1.1", format="png",
            minzoom=min(zooms), maxzoom=max(zooms),
            attribution=self.provider.attribution,
            bounds=f"{bbox[1]},{bbox[0]},{bbox[3]},{bbox[2]}",
            center=f"{lon},{lat},{max(zooms)}")
        saved = 0
        total = len(tiles)
        for i, (z, x, y) in enumerate(tiles, 1):
            data = self._fetch(self.provider.tile_url(z, x, y))
            if data:
                writer.add_tile(z, x, y, data)
                saved += 1
            if progress is not None:
                progress(i, total)
        writer.close()
        return {"requested": total, "saved": saved, "path": out_path,
                "zooms": zooms}
