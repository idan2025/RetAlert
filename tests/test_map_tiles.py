"""Tests for retalert.core.map_tiles — slippy-map math, MBTiles writer, and
the offline tile downloader (HTTP fetch injected, no network)."""
import sqlite3

import pytest

from retalert.core.map_tiles import (
    RADIUS_OPTIONS, PROVIDERS, DEFAULT_PROVIDER, provider_keys, get_provider,
    deg2num, num2deg, radius_bbox, tiles_for_bbox, tiles_for_radius,
    default_zooms_for_radius, estimate_tile_count,
    MBTilesWriter, TileDownloader,
)


# -- providers ----------------------------------------------------------

def test_providers_registry():
    assert DEFAULT_PROVIDER in PROVIDERS
    assert set(provider_keys()) == set(PROVIDERS)
    assert RADIUS_OPTIONS == [5, 10, 20, 50, 100]


def test_provider_tile_url():
    p = get_provider("osm")
    assert p.tile_url(5, 1, 2) == "https://tile.openstreetmap.org/5/1/2.png"


def test_esri_uses_z_y_x_order():
    # Esri's template is .../{z}/{y}/{x}; confirm y and x land in the right slots.
    url = get_provider("esri_satellite").tile_url(4, 6, 9)
    assert url.endswith("/4/9/6")


# -- slippy math --------------------------------------------------------

def test_deg2num_origin():
    assert deg2num(0.0, 0.0, 1) == (1, 1)
    assert deg2num(0.0, -180.0, 1) == (0, 1)


def test_deg2num_clamped_in_range():
    # Extreme latitudes clamp instead of overflowing the tile grid.
    z = 3
    x, y = deg2num(89.0, 200.0, z)
    assert 0 <= x < 2 ** z and 0 <= y < 2 ** z


def test_num2deg_roundtrip():
    lat, lon = 40.0, -73.0
    z = 12
    x, y = deg2num(lat, lon, z)
    blat, blon = num2deg(x, y, z)  # NW corner of the tile
    # Corner is within one tile's span of the original point.
    assert abs(blat - lat) < 0.1 and abs(blon - lon) < 0.1


def test_radius_bbox_ordering_and_growth():
    small = radius_bbox(40.0, -73.0, 5)
    big = radius_bbox(40.0, -73.0, 100)
    assert small[0] < 40.0 < small[2] and small[1] < -73.0 < small[3]
    # Larger radius -> wider span.
    assert (big[2] - big[0]) > (small[2] - small[0])


def test_tiles_for_bbox_nonempty():
    bbox = radius_bbox(40.0, -73.0, 10)
    tiles = list(tiles_for_bbox(bbox, 12))
    assert tiles and all(t[0] == 12 for t in tiles)


def test_default_zooms_per_radius():
    assert default_zooms_for_radius(5)[-1] == 16
    assert default_zooms_for_radius(100)[-1] == 12
    for r in RADIUS_OPTIONS:
        zs = default_zooms_for_radius(r)
        assert zs == sorted(zs) and len(zs) == 4


def test_tile_count_grows_with_radius():
    c5 = estimate_tile_count(40.0, -73.0, 5)
    c100 = estimate_tile_count(40.0, -73.0, 100)
    assert c100 > c5 > 0


# -- distance -----------------------------------------------------------

def test_haversine_zero_and_known():
    from retalert.core.map_tiles import haversine_km
    assert haversine_km(40.0, -73.0, 40.0, -73.0) == 0.0
    d = haversine_km(40.7128, -74.0060, 34.0522, -118.2437)  # NYC->LA
    assert 3900 < d < 3980


def test_format_distance_units():
    from retalert.core.map_tiles import format_distance
    assert format_distance(10.0, "km") == "10.0 km"
    assert format_distance(1.609344, "mi") == "1.0 mi"


# -- MBTiles writer -----------------------------------------------------

def test_mbtiles_writer_schema_and_tms_flip(tmp_path):
    path = str(tmp_path / "m.mbtiles")
    w = MBTilesWriter(path)
    w.set_metadata(name="t", format="png")
    w.add_tile(3, 2, 1, b"PNGDATA")   # XYZ y=1 -> TMS row = (2^3-1)-1 = 6
    assert w.count() == 1
    w.close()

    conn = sqlite3.connect(path)
    row = conn.execute("SELECT zoom_level, tile_column, tile_row, tile_data "
                       "FROM tiles").fetchone()
    assert row[0] == 3 and row[1] == 2 and row[2] == 6
    assert bytes(row[3]) == b"PNGDATA"
    meta = dict(conn.execute("SELECT name, value FROM metadata").fetchall())
    assert meta["format"] == "png" and meta["name"] == "t"


def test_mbtiles_replace_same_tile(tmp_path):
    path = str(tmp_path / "m.mbtiles")
    w = MBTilesWriter(path)
    w.add_tile(3, 2, 1, b"A")
    w.add_tile(3, 2, 1, b"B")  # unique index -> replace
    assert w.count() == 1
    w.close()


# -- downloader ---------------------------------------------------------

def test_downloader_writes_tiles(tmp_path):
    calls = []

    def fake_fetch(url):
        calls.append(url)
        return b"TILE"

    dl = TileDownloader(get_provider("osm"), fetch=fake_fetch)
    out = str(tmp_path / "area.mbtiles")
    progress = []
    summary = dl.download(40.0, -73.0, 5, out, zooms=[12],
                          progress=lambda i, n: progress.append((i, n)))
    assert summary["saved"] == summary["requested"] > 0
    assert len(calls) == summary["requested"]
    assert progress[-1] == (summary["requested"], summary["requested"])
    # File is a real MBTiles with the tiles in it.
    conn = sqlite3.connect(out)
    assert conn.execute("SELECT COUNT(*) FROM tiles").fetchone()[0] == \
        summary["saved"]


def test_downloader_skips_failed_fetch(tmp_path):
    dl = TileDownloader(get_provider("osm"), fetch=lambda url: None)
    out = str(tmp_path / "empty.mbtiles")
    summary = dl.download(40.0, -73.0, 5, out, zooms=[12])
    assert summary["requested"] > 0 and summary["saved"] == 0


def test_downloader_max_tiles_guard(tmp_path):
    dl = TileDownloader(get_provider("osm"), fetch=lambda url: b"X")
    with pytest.raises(ValueError):
        dl.download(40.0, -73.0, 100, str(tmp_path / "x.mbtiles"),
                    zooms=[12], max_tiles=5)
