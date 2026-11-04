from __future__ import annotations

import gzip
import hashlib
import json
import sqlite3
from pathlib import Path

import pytest

from orbit_curator.build import _best_logos, build
from orbit_curator.classify import classify
from orbit_curator.collections import build_collections
from orbit_curator.config import MALAYALAM_IDS, MALAYALAM_ORDER
from orbit_curator.health import HealthResult, merge_result, parse_master_variants
from orbit_curator.normalize import clean_name, dedupe_urls
from orbit_curator.parse import parse_m3u
from orbit_curator.score import order_streams, stream_score
from orbit_curator.validate import validate

FIXTURES = Path(__file__).parent / "fixtures"
COUNTRIES = {
    "IN": {"code": "IN", "name": "India", "languages": ["hin"]},
    "US": {"code": "US", "name": "United States", "languages": ["eng"]},
    "GB": {"code": "GB", "name": "United Kingdom", "languages": ["eng"]},
}
LANGUAGES = {
    "hin": {"code": "hin", "name": "Hindi"},
    "eng": {"code": "eng", "name": "English"},
    "mal": {"code": "mal", "name": "Malayalam"},
    "tam": {"code": "tam", "name": "Tamil"},
}


def test_parse_messy_m3u():
    entries = parse_m3u((FIXTURES / "messy.m3u").read_text())
    assert [entry.url for entry in entries] == [
        "http://example.com/one.m3u8",
        "http://example.com/two.ts",
        "https://example.com/three.m3u8",
    ]
    assert entries[0].name == "Channel One HD"
    assert entries[0].group == "News"
    assert entries[0].logo == "http://logo/one.png"
    assert entries[0].user_agent == "Mozilla/5.0"
    assert entries[0].referrer == "http://referrer.example/"
    assert entries[1].group == "Movies"


def test_clean_name_and_dedupe():
    assert clean_name("Asianet  [Geo-blocked]  HD") == "Asianet"
    assert clean_name("News (1080p)") == "News"
    assert clean_name("Kairali TV") == "Kairali TV"
    assert dedupe_urls(["a", "a", "b", "a"]) == ["a", "b"]


def test_malayalam_classification():
    curated = classify(
        {"id": "Asianet.in", "name": "Asianet", "country": "IN", "categories": ["general"]},
        COUNTRIES, LANGUAGES,
    )
    assert curated.malayalam and curated.language_id == "mal"
    assert curated.category == "Entertainment"

    script = classify(
        {"id": "X.in", "name": "\u0d2e\u0d32\u0d2f\u0d3e\u0d33\u0d02", "country": "IN"},
        COUNTRIES, LANGUAGES,
    )
    assert script.malayalam

    metadata = classify(
        {"id": "Y.in", "name": "Sun TV", "country": "IN", "languages": ["tam"]},
        COUNTRIES, LANGUAGES,
    )
    assert metadata.language_id == "tam"
    assert not metadata.malayalam


def test_three_state_history():
    bad = HealthResult("unverified", True, None, "", "http 404")
    soft = HealthResult("unverified", False, None, "", "timeout")
    good = HealthResult("alive", False, 300, "720p", "ok")

    first = merge_result(None, bad)
    assert first["state"] == "unverified" and first["hard_fails"] == 1
    second = merge_result(first, bad)
    assert second["state"] == "dead" and second["hard_fails"] == 2
    revived_soft = merge_result(second, soft)
    assert revived_soft["state"] == "dead"
    alive = merge_result(revived_soft, good)
    assert alive["state"] == "alive" and alive["hard_fails"] == 0


def test_stream_scoring_and_order():
    assert stream_score("alive", "1080p", 300, 0) == 100
    assert stream_score("alive", "720p", 900, 0) == 94
    assert stream_score("dead", "1080p", 100, 2) == 0
    streams = [
        {"state": "unverified", "score": 30, "resolution": "1080p", "latency_ms": 100, "url": "u"},
        {"state": "alive", "score": 80, "resolution": "720p", "latency_ms": 900, "url": "a"},
        {"state": "dead", "score": 0, "resolution": "", "latency_ms": None, "url": "d"},
    ]
    ordered = order_streams(streams)
    assert [s["url"] for s in ordered] == ["a", "u", "d"]
    assert [s["position"] for s in ordered] == [0, 1, 2]


def test_master_playlist_parsing():
    text = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
low/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
high/index.m3u8
"""
    variants = parse_master_variants(text, "https://cdn.example/live/master.m3u8")
    assert variants == [
        (800000, "360p", "https://cdn.example/live/low/index.m3u8"),
        (2400000, "720p", "https://cdn.example/live/high/index.m3u8"),
    ]


def _channel(channel_id: str, name: str, country: str, category: str, score: int,
             malayalam: bool = False) -> dict:
    return {
        "id": channel_id, "name": name, "best_score": score, "state": "alive",
        "malayalam": malayalam, "country_id": country, "country": country,
        "category": category,
    }


def test_collection_order_and_filtering():
    channels = [
        _channel("Asianet.in", "Asianet", "IN", "Entertainment", 90, True),
        _channel("KairaliTV.in", "Kairali TV", "IN", "Entertainment", 80, True),
        _channel("ZZNews.in", "Zed News", "IN", "News", 70),
        _channel("dead.in", "Dead One", "IN", "News", 0),
        _channel("usnews.us", "US News", "US", "News", 60),
        _channel("uknews.uk", "UK News", "GB", "News", 50),
    ]
    channels[3]["state"] = "dead"
    collections = build_collections(channels)
    ids = [c["id"] for c in collections]
    assert ids[0] == "editor-picks"
    assert ids[1] == "malayalam"
    malayalam = collections[1]["items"]
    assert malayalam == ["Asianet.in", "KairaliTV.in"]
    india_news = next(c for c in collections if c["id"] == "india-news")
    assert india_news["items"] == ["ZZNews.in"]
    world_news = next(c for c in collections if c["id"] == "world-news")
    assert world_news["items"] == ["usnews.us", "uknews.uk"]


def _make_source_dir(root: Path, malayalam_count: int = 12) -> Path:
    source = root / "api"
    source.mkdir(parents=True)
    channels, streams = [], []
    for index, channel_id in enumerate(MALAYALAM_IDS[:malayalam_count]):
        channels.append({
            "id": channel_id, "name": f"Malayalam {index}", "alt_names": [],
            "country": "IN", "categories": ["general"], "is_nsfw": False, "closed": None,
        })
        streams.append({
            "channel": channel_id, "url": f"https://mal.example/{index}.m3u8",
            "quality": "720p", "user_agent": None, "referrer": None,
        })
    channels[0].update({
        "website": "https://mal.example/",
        "network": "Malayalam Network",
        "launched": "2005-01-01",
        "owners": ["Owner A", "Owner B"],
    })
    for index in range(260):
        channel_id = f"IN{index}.in"
        channels.append({
            "id": channel_id, "name": f"India Channel {index:03d}", "alt_names": [],
            "country": "IN", "categories": ["news"], "is_nsfw": False, "closed": None,
        })
        streams.append({
            "channel": channel_id, "url": f"https://in.example/{index}.m3u8",
            "quality": "1080p", "user_agent": "TestUA", "referrer": None,
        })
    duplicate = "IN0.in"
    streams.append({"channel": duplicate, "url": "https://in.example/0.m3u8", "quality": "1080p"})
    streams.append({"channel": duplicate, "url": "https://in.example/0-low.m3u8", "quality": "480p"})
    for index in range(3):
        for code in ("US", "GB"):
            channel_id = f"{code}{index}.{code.lower()}"
            channels.append({
                "id": channel_id, "name": f"{code} News {index}", "alt_names": [],
                "country": code, "categories": ["news"], "is_nsfw": False, "closed": None,
            })
            streams.append({
                "channel": channel_id, "url": f"https://{code.lower()}.example/{index}.m3u8",
                "quality": "720p", "user_agent": None, "referrer": None,
            })
    (source / "channels.json").write_text(json.dumps(channels))
    (source / "streams.json").write_text(json.dumps(streams))
    (source / "countries.json").write_text(json.dumps(list(COUNTRIES.values())))
    (source / "languages.json").write_text(json.dumps(list(LANGUAGES.values())))
    (source / "categories.json").write_text(json.dumps([
        {"id": "news", "name": "News"}, {"id": "general", "name": "General"},
    ]))
    (source / "logos.json").write_text(json.dumps([
        {"channel": MALAYALAM_IDS[0], "url": "https://logo.example/a.svg",
         "format": "SVG", "width": 1000, "height": 1000, "in_use": True},
        {"channel": MALAYALAM_IDS[0], "url": "https://logo.example/a.png",
         "format": "PNG", "width": 200, "height": 200, "in_use": True},
        {"channel": MALAYALAM_IDS[0], "url": "https://logo.example/a.jpg",
         "format": "JPEG", "width": 400, "height": 400, "in_use": True},
        {"channel": MALAYALAM_IDS[0], "url": "https://logo.example/old.png",
         "format": "PNG", "width": 9999, "height": 9999, "in_use": False},
        {"channel": MALAYALAM_IDS[0], "url": "http://logo.example/plain.png",
         "format": "PNG", "width": 9999, "height": 9999, "in_use": True},
    ]))
    return source


def test_best_logos_prefers_raster_https():
    chosen = _best_logos([
        {"channel": "a", "url": "https://x/a.svg", "format": "SVG", "width": 900, "height": 900},
        {"channel": "a", "url": "https://x/a.png", "format": "PNG", "width": 100, "height": 100},
        {"channel": "a", "url": "https://x/a.jpg", "format": "JPEG", "width": 300, "height": 300},
        {"channel": "b", "url": "http://x/b.png", "format": "PNG", "width": 500, "height": 500},
        {"channel": "c", "url": "https://x/c.png", "format": "PNG", "width": 500, "height": 500,
         "in_use": False},
        {"channel": "d", "url": "https://x/d.webp", "format": "WEBP", "width": 500, "height": 500},
    ])
    assert chosen == {"a": "https://x/a.jpg", "d": "https://x/d.webp"}


def test_build_end_to_end_and_version_hash(tmp_path):
    source = _make_source_dir(tmp_path)
    out = tmp_path / "dist"
    code = build(
        out_dir=out,
        cache_dir=tmp_path / "cache",
        history_path=tmp_path / "health.json",
        fast=True,
        source_dir=source,
    )
    assert code == 0
    db = sqlite3.connect(out / "catalog.db")
    try:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 2
        channels = db.execute("SELECT COUNT(*) FROM channel").fetchone()[0]
        fts = db.execute("SELECT COUNT(*) FROM channel_fts").fetchone()[0]
        assert channels == fts == 278
        malayalam = db.execute(
            "SELECT COUNT(*) FROM collection_item WHERE collection_id='malayalam'"
        ).fetchone()[0]
        assert malayalam == 12
        first_malayalam = db.execute(
            "SELECT c.name FROM collection_item i JOIN channel c ON c.id=i.channel_id"
            " WHERE i.collection_id='malayalam' ORDER BY i.position LIMIT 1"
        ).fetchone()[0]
        assert first_malayalam == "Malayalam 0"
        first_collection = db.execute(
            "SELECT id FROM collection ORDER BY position LIMIT 1"
        ).fetchone()[0]
        assert first_collection == "editor-picks"
        positions = [row[0] for row in db.execute(
            "SELECT position FROM collection_item WHERE collection_id='malayalam' ORDER BY position"
        )]
        assert positions == list(range(12))
        streams = db.execute(
            "SELECT COUNT(*) FROM stream WHERE channel_id='IN0.in'"
        ).fetchone()[0]
        assert streams == 2
        logo, website, network, launched, owners = db.execute(
            "SELECT logo_url, website, network, launched, owners FROM channel WHERE id=?",
            (MALAYALAM_IDS[0],),
        ).fetchone()
        assert logo == "https://logo.example/a.jpg"
        assert website == "https://mal.example/"
        assert network == "Malayalam Network"
        assert launched == "2005-01-01"
        assert owners == "Owner A | Owner B"
        numbers = [row[0] for row in db.execute("SELECT number FROM channel ORDER BY number")]
        assert numbers == list(range(1, 279))
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    finally:
        db.close()

    version = json.loads((out / "version.json").read_text())
    digest = hashlib.sha256((out / "catalog.db.gz").read_bytes()).hexdigest()
    assert version["sha256"] == digest
    assert version["counts"]["channels"] == 278
    assert gzip.decompress((out / "catalog.db.gz").read_bytes())[:16] == b"SQLite format 3\x00"
    assert (out / "state" / "health.json").exists()

    current = json.loads((out / "report.json").read_text())
    previous = json.loads((out / "report.json").read_text())
    previous["counts"]["playable"] = previous["counts"]["playable"] * 2
    reasons = validate(out / "catalog.db", current, previous)
    assert any("dropped" in reason for reason in reasons)

    previous = json.loads((out / "report.json").read_text())
    previous["collections"] = previous["collections"] + [
        {"id": "india-movies", "title": "Indian Movies", "count": 5},
    ]
    reasons = validate(out / "catalog.db", current, previous)
    assert any("india-movies" in reason for reason in reasons)

    previous = json.loads((out / "report.json").read_text())
    previous["collections"] = previous["collections"] + [
        {"id": "country-uk", "title": "United Kingdom", "count": 4},
        {"id": "country-xx", "title": "Example", "count": 12},
    ]
    reasons = validate(out / "catalog.db", current, previous)
    assert all("country-uk" not in reason for reason in reasons)
    assert any("country-xx" in reason for reason in reasons)
