"""Build the catalog: select, check, classify, score, collect, write, validate."""
from __future__ import annotations

import gzip
import hashlib
import json
import os
import re
import sqlite3
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from .classify import classify, is_curated
from .collections import build_collections
from .config import (
    CATEGORY_BUCKETS,
    CATEGORY_RANK,
    EDITOR_PICKS,
    GLOBAL_IDS,
    GLOBAL_ORDER,
    MALAYALAM_IDS,
    MAX_CHANNELS,
    MAX_INDIA,
    MAX_STREAMS_PER_CHANNEL,
    MIN_APP_VERSION,
    RULES,
    SCHEMA_VERSION,
    SOURCES,
)
from .fetch import load_sources
from .health import load_history, merge_result, probe, save_history
from .normalize import alt_names, clean_name
from .report import make_report, write_report
from .score import channel_score, channel_state, order_streams, playable, stream_score
from .validate import load_report, validate

QUALITY_RE = re.compile(r"(\d{3,4})")
MALAYALAM_SCRIPT = re.compile(r"[\u0D00-\u0D7F]")

BUCKET_TITLES = ["News", "Entertainment", "Movies", "Music", "Kids", "Sports",
                 "Documentary", "Religious", "Other"]


def build(
    out_dir: Path,
    cache_dir: Path,
    history_path: Path,
    previous_report_path: Path | None = None,
    *,
    fast: bool = False,
    offline: bool = False,
    max_checks: int | None = None,
    workers: int = 24,
    timeout: float = 8.0,
    source_dir: Path | None = None,
) -> int:
    started = time.monotonic()
    durations: dict[str, float] = {}

    sources = load_sources(cache_dir, source_dir=source_dir, offline=offline)
    raw_channels = sources["channels.json"]
    raw_streams = sources["streams.json"]
    countries = {item["code"]: item for item in sources["countries.json"]}
    languages = {item["code"]: item for item in sources["languages.json"]}
    by_id = {item["id"]: item for item in raw_channels}
    logo_by_id = _best_logos(sources.get("logos.json") or [])

    streams_by_channel = _streams_by_channel(raw_streams, by_id)
    selected = _select_channels(by_id, streams_by_channel)
    candidates = {
        "channels": len(selected),
        "urls": sum(len(streams_by_channel[c["id"]]) for c in selected),
    }

    url_info: dict[str, dict] = {}
    for channel in selected:
        for stream in streams_by_channel[channel["id"]]:
            url_info.setdefault(stream["url"], {
                "quality": stream.get("quality") or "",
                "user_agent": stream.get("user_agent"),
                "referrer": stream.get("referrer"),
                "kind": _stream_kind(stream["url"]),
            })

    history = load_history(history_path)
    checked = 0
    if not fast and not offline:
        checked = _run_health(url_info, history, workers, timeout, max_checks)
    save_history(history_path, history)
    durations["fetch_and_select"] = round(time.monotonic() - started, 1)

    models: list[dict] = []
    for channel in selected:
        model = _build_channel(channel, streams_by_channel[channel["id"]], url_info,
                               history, countries, languages, logo_by_id)
        if model is not None:
            models.append(model)
    models.sort(key=lambda c: c["id"])

    collections = build_collections(models)
    _assign_numbers(models, collections)
    durations["build"] = round(time.monotonic() - started, 1)

    version = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    out_dir.mkdir(parents=True, exist_ok=True)
    db_path = out_dir / "catalog.db"
    _write_database(db_path, models, collections, countries, languages, version)
    db_size = db_path.stat().st_size

    gz_path = out_dir / "catalog.db.gz"
    _write_gzip(db_path, gz_path)
    gz_size = gz_path.stat().st_size

    sha256 = _sha256(gz_path)
    counts = {
        "channels": len(models),
        "playable": sum(1 for m in models if playable(m["state"])),
        "alive": sum(1 for m in models if m["state"] == "alive"),
        "unverified": sum(1 for m in models if m["state"] == "unverified"),
        "dead": sum(1 for m in models if m["state"] == "dead"),
        "malayalam": sum(1 for m in models if m["malayalam"] and playable(m["state"])),
        "collections": len(collections),
    }
    counts["live_share"] = round(counts["alive"] / checked, 3) if checked else 0.0

    (out_dir / "version.json").write_text(json.dumps({
        "schema": SCHEMA_VERSION,
        "catalog_version": version,
        "built_at": version,
        "sha256": sha256,
        "size_bytes": gz_size,
        "min_app_version": MIN_APP_VERSION,
        "counts": {
            "channels": counts["channels"],
            "playable": counts["playable"],
            "collections": counts["collections"],
            "malayalam": counts["malayalam"],
        },
    }, indent=2), encoding="utf-8")

    dead_examples = [
        {
            "id": m["id"],
            "name": m["name"],
            "country": m["country"],
            "category": m["category"],
        }
        for m in models if m["state"] == "dead"
    ]
    durations["total"] = round(time.monotonic() - started, 1)
    report = make_report(
        version=version,
        source=SOURCES["source"],
        license_name=SOURCES["source_license"],
        durations=durations,
        candidates={**candidates, "checked": checked},
        counts=counts,
        collections=collections,
        gzip_size=gz_size,
        db_size=db_size,
        dead_examples=dead_examples,
        history_entries=len(history),
    )
    previous_report = load_report(previous_report_path)
    reasons = validate(db_path, report, previous_report)
    report["gate"] = {"passed": not reasons, "reasons": reasons}
    write_report(out_dir / "report.json", report)

    state_out = out_dir / "state" / "health.json"
    state_out.parent.mkdir(parents=True, exist_ok=True)
    state_out.write_text(json.dumps(history), encoding="utf-8")

    print(
        f"catalog {version}: {counts['playable']}/{counts['channels']} playable "
        f"(alive {counts['alive']}, unverified {counts['unverified']}, dead {counts['dead']}), "
        f"malayalam {counts['malayalam']}, gz {gz_size / 1024:.0f} KiB",
        flush=True,
    )
    if reasons:
        print("publish gate failed:", flush=True)
        for reason in reasons:
            print(f"  - {reason}", flush=True)
        return 1
    print("publish gate passed", flush=True)
    return 0


def _streams_by_channel(streams: list[dict], by_id: dict[str, dict]) -> dict[str, list[dict]]:
    result: dict[str, list[dict]] = defaultdict(list)
    for stream in streams:
        channel_id = stream.get("channel")
        if not channel_id or channel_id not in by_id:
            continue
        channel = by_id[channel_id]
        if channel.get("is_nsfw") or channel.get("closed"):
            continue
        url = str(stream.get("url") or "")
        if not url.lower().startswith(("http://", "https://")):
            continue
        if "youtube.com" in url or "youtu.be" in url:
            continue
        result[channel_id].append(stream)
    cleaned: dict[str, list[dict]] = {}
    for channel_id, items in result.items():
        seen: set[str] = set()
        kept = []
        for stream in sorted(items, key=lambda s: (-_quality_height(s.get("quality")), s["url"])):
            if stream["url"] in seen:
                continue
            seen.add(stream["url"])
            kept.append(stream)
            if len(kept) >= MAX_STREAMS_PER_CHANNEL:
                break
        if kept:
            cleaned[channel_id] = kept
    return cleaned


def _select_channels(by_id: dict[str, dict], streams_by_channel: dict[str, list[dict]]) -> list[dict]:
    available = set(streams_by_channel)
    selected: list[str] = []
    chosen: set[str] = set()

    def add(channel_id: str) -> None:
        if channel_id in available and channel_id not in chosen:
            chosen.add(channel_id)
            selected.append(channel_id)

    malayalam: list[str] = [cid for cid in MALAYALAM_IDS if cid in available]
    for channel_id in MALAYALAM_IDS:
        add(channel_id)
    for channel_id in sorted(available - chosen):
        channel = by_id[channel_id]
        if channel.get("country") != "IN":
            continue
        text = (str(channel.get("name") or "") + " " + " ".join(
            str(a) for a in (channel.get("alt_names") or [])
        )).casefold()
        if MALAYALAM_SCRIPT.search(text) or any(
            keyword in text for keyword in RULES["malayalam_keywords"]
        ):
            malayalam.append(channel_id)
            add(channel_id)

    for channel_id in GLOBAL_ORDER:
        add(channel_id)

    india = [
        by_id[cid] for cid in available
        if by_id[cid].get("country") == "IN" and cid not in chosen
    ]
    india.sort(key=_india_rank)
    for channel in india[:MAX_INDIA]:
        add(channel["id"])

    world_pool: dict[str, list[dict]] = defaultdict(list)
    for channel_id in available - chosen:
        channel = by_id[channel_id]
        country = str(channel.get("country") or "ZZ")
        if country == "IN":
            continue
        world_pool[country].append(channel)
    extras: list[dict] = []
    for country in sorted(world_pool):
        channels = sorted(world_pool[country], key=_world_rank)
        extras.extend(channels[:3])

    budget = max(0, MAX_CHANNELS - len(selected))
    for channel in extras[:budget]:
        add(channel["id"])

    return [by_id[cid] for cid in selected]


def _india_rank(channel: dict) -> tuple:
    editorial = 0 if channel["id"] in set(EDITOR_PICKS) | GLOBAL_IDS else 1
    return (editorial, _category_rank(channel), channel["name"].casefold())


def _world_rank(channel: dict) -> tuple:
    return (_category_rank(channel), "HD" not in channel["name"], channel["name"].casefold())


def _category_rank(channel: dict) -> int:
    categories = channel.get("categories") or []
    return CATEGORY_RANK.get(categories[0] if categories else "", 99)


def _run_health(
    url_info: dict[str, dict],
    history: dict[str, dict],
    workers: int,
    timeout: float,
    max_checks: int | None,
) -> int:
    urls = sorted(url_info)
    if max_checks is not None:
        urls = urls[:max_checks]
    if not urls:
        return 0
    print(f"health-checking {len(urls)} streams with {workers} workers ...", flush=True)
    checked = 0
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {}
        for url in urls:
            info = url_info[url]
            futures[executor.submit(probe, url, timeout, info["user_agent"], info["referrer"])] = url
        for future in as_completed(futures):
            url = futures[future]
            info = url_info[url]
            try:
                result = future.result()
            except Exception:  # noqa: BLE001 - one bad URL must not stop the run
                continue
            history[url] = merge_result(history.get(url), result, info["quality"])
            checked += 1
            if checked % 200 == 0:
                print(f"  checked {checked}/{len(futures)}", flush=True)
    return checked


_RASTER_FORMATS = {"PNG", "JPEG", "JPG", "WEBP", "GIF", "AVIF"}


def _best_logos(items: list) -> dict[str, str]:
    """Pick one https raster logo per channel, preferring in-use and larger art."""
    best: dict[str, tuple[tuple[int, int], str]] = {}
    for item in items:
        if not isinstance(item, dict) or not item.get("in_use", True):
            continue
        channel_id = item.get("channel")
        url = str(item.get("url") or "").strip()
        if not channel_id or not url.startswith("https://"):
            continue
        fmt = str(item.get("format") or "").upper()
        if fmt == "SVG":
            # The app's Coil build has no SVG decoder.
            continue
        try:
            area = int(item.get("width") or 0) * int(item.get("height") or 0)
        except (TypeError, ValueError):
            area = 0
        score = (0 if fmt in _RASTER_FORMATS else 1, -area)
        if channel_id not in best or score < best[channel_id][0]:
            best[channel_id] = (score, url)
    return {channel_id: url for channel_id, (_, url) in best.items()}


def _owners(value: object) -> str:
    if isinstance(value, list):
        return " | ".join(str(item).strip() for item in value if str(item).strip())
    return str(value).strip() if value else ""


def _build_channel(
    channel: dict,
    streams: list[dict],
    url_info: dict[str, dict],
    history: dict[str, dict],
    countries: dict[str, dict],
    languages: dict[str, dict],
    logo_by_id: dict[str, str],
) -> dict | None:
    classification = classify(channel, countries, languages)
    built_streams = []
    for stream in streams:
        info = url_info[stream["url"]]
        entry = history.get(stream["url"]) or {}
        state = entry.get("state") or "unverified"
        resolution = entry.get("resolution") or info["quality"] or ""
        latency = entry.get("latency_ms")
        hard_fails = int(entry.get("hard_fails") or 0)
        built_streams.append({
            "id": hashlib.sha1(stream["url"].encode("utf-8")).hexdigest()[:16],
            "url": stream["url"],
            "kind": info["kind"],
            "state": state,
            "latency_ms": latency,
            "resolution": resolution,
            "score": stream_score(state, resolution, latency, hard_fails),
            "user_agent": stream.get("user_agent"),
            "referrer": stream.get("referrer"),
            "position": 0,
        })
    if not built_streams:
        return None
    order_streams(built_streams)
    state = channel_state(built_streams)
    return {
        "id": channel["id"],
        "name": clean_name(str(channel.get("name") or channel["id"])),
        "alt_names": alt_names(channel.get("alt_names")),
        "logo_url": logo_by_id.get(channel["id"]) or channel.get("logo") or channel.get("logo_url"),
        "website": channel.get("website") or "",
        "network": channel.get("network") or "",
        "launched": channel.get("launched") or "",
        "owners": _owners(channel.get("owners")),
        "category": classification.category,
        "category_id": classification.category_id,
        "language": classification.language,
        "language_id": classification.language_id,
        "country": classification.country,
        "country_id": classification.country_id,
        "number": None,
        "state": state,
        "best_score": channel_score(built_streams),
        "is_curated": 1 if is_curated(channel["id"]) else 0,
        "malayalam": classification.malayalam,
        "streams": built_streams,
    }


def _assign_numbers(models: list[dict], collections: list[dict]) -> None:
    by_id = {m["id"]: m for m in models}
    number = 1
    for collection in collections:
        for channel_id in collection["items"]:
            channel = by_id.get(channel_id)
            if channel is not None and channel["number"] is None:
                channel["number"] = number
                number += 1
    for channel in sorted(models, key=lambda c: (c["name"].casefold(), c["id"])):
        if channel["number"] is None:
            channel["number"] = number
            number += 1


def _write_database(
    path: Path,
    models: list[dict],
    collections: list[dict],
    countries: dict[str, dict],
    languages: dict[str, dict],
    version: str,
) -> None:
    path.unlink(missing_ok=True)
    conn = sqlite3.connect(path)
    try:
        conn.execute("PRAGMA journal_mode=DELETE")
        conn.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
        conn.executescript("""
            CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE country  (code TEXT PRIMARY KEY, name TEXT NOT NULL);
            CREATE TABLE language (code TEXT PRIMARY KEY, name TEXT NOT NULL);
            CREATE TABLE category (id   TEXT PRIMARY KEY, title TEXT NOT NULL);
            CREATE TABLE channel (
              id TEXT PRIMARY KEY, name TEXT NOT NULL, alt_names TEXT NOT NULL DEFAULT '',
              logo_url TEXT, website TEXT, network TEXT, launched TEXT, owners TEXT,
              category_id TEXT, language_id TEXT, country_id TEXT,
              number INTEGER, state TEXT NOT NULL, best_score INTEGER NOT NULL,
              is_curated INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE stream (
              id TEXT PRIMARY KEY, channel_id TEXT NOT NULL, url TEXT NOT NULL,
              kind TEXT NOT NULL, state TEXT NOT NULL, latency_ms INTEGER,
              resolution TEXT, score INTEGER NOT NULL, user_agent TEXT, referrer TEXT,
              position INTEGER NOT NULL
            );
            CREATE INDEX idx_stream_channel ON stream(channel_id, position);
            CREATE TABLE collection (
              id TEXT PRIMARY KEY, title TEXT NOT NULL, kind TEXT NOT NULL,
              position INTEGER NOT NULL
            );
            CREATE TABLE collection_item (
              collection_id TEXT NOT NULL, channel_id TEXT NOT NULL,
              position INTEGER NOT NULL, PRIMARY KEY (collection_id, channel_id)
            );
            CREATE INDEX idx_collection_order ON collection_item(collection_id, position);
            CREATE VIRTUAL TABLE channel_fts USING fts5(
              channel_id UNINDEXED, name, alt_names, language, category, country,
              tokenize = 'unicode61 remove_diacritics 2'
            );
        """)

        categories = sorted({(m["category_id"], m["category"]) for m in models})
        for title in BUCKET_TITLES:
            categories.append((title.lower(), title))
        conn.executemany("INSERT OR REPLACE INTO category(id, title) VALUES (?, ?)", categories)

        used_languages = {(m["language_id"], m["language"]) for m in models}
        used_languages.add(("und", "Unknown"))
        used_languages.add(("mal", "Malayalam"))
        conn.executemany(
            "INSERT OR REPLACE INTO language(code, name) VALUES (?, ?)",
            [(code, languages.get(code, {}).get("name") or name) for code, name in used_languages],
        )

        used_countries = {(m["country_id"], m["country"]) for m in models}
        used_countries.add(("ZZ", "International"))
        conn.executemany(
            "INSERT OR REPLACE INTO country(code, name) VALUES (?, ?)",
            [(code, countries.get(code, {}).get("name") or name) for code, name in used_countries],
        )

        meta = {
            "schema": str(SCHEMA_VERSION),
            "catalog_version": version,
            "built_at": version,
            "source": SOURCES["source"],
            "source_license": SOURCES["source_license"],
            "min_app_version": str(MIN_APP_VERSION),
        }
        conn.executemany("INSERT INTO meta(key, value) VALUES (?, ?)", meta.items())

        conn.executemany(
            "INSERT INTO channel(id, name, alt_names, logo_url, website, network,"
            " launched, owners, category_id, language_id, country_id, number, state,"
            " best_score, is_curated) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            [
                (m["id"], m["name"], m["alt_names"], m["logo_url"], m["website"],
                 m["network"], m["launched"], m["owners"], m["category_id"],
                 m["language_id"], m["country_id"], m["number"], m["state"],
                 m["best_score"], m["is_curated"])
                for m in models
            ],
        )
        conn.executemany(
            "INSERT INTO stream(id, channel_id, url, kind, state, latency_ms, resolution,"
            " score, user_agent, referrer, position) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            [
                (s["id"], m["id"], s["url"], s["kind"], s["state"], s["latency_ms"],
                 s["resolution"], s["score"], s["user_agent"], s["referrer"], s["position"])
                for m in models for s in m["streams"]
            ],
        )
        conn.executemany(
            "INSERT INTO collection(id, title, kind, position) VALUES (?,?,?,?)",
            [(c["id"], c["title"], c["kind"], index) for index, c in enumerate(collections)],
        )
        conn.executemany(
            "INSERT INTO collection_item(collection_id, channel_id, position) VALUES (?,?,?)",
            [
                (c["id"], channel_id, position)
                for c in collections for position, channel_id in enumerate(c["items"])
            ],
        )
        conn.executemany(
            "INSERT INTO channel_fts(channel_id, name, alt_names, language, category, country)"
            " VALUES (?,?,?,?,?,?)",
            [
                (m["id"], m["name"], m["alt_names"], m["language"], m["category"], m["country"])
                for m in models
            ],
        )
        conn.commit()
        conn.execute("VACUUM")
        conn.commit()
    finally:
        conn.close()


def _write_gzip(source: Path, target: Path) -> None:
    temp = target.with_suffix(target.suffix + ".tmp")
    with source.open("rb") as raw, temp.open("wb") as fileobj:
        with gzip.GzipFile(filename="", mode="wb", fileobj=fileobj, mtime=0, compresslevel=9) as gz:
            while chunk := raw.read(256 * 1024):
                gz.write(chunk)
    os.replace(temp, target)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(256 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _quality_height(quality: object) -> int:
    match = QUALITY_RE.search(str(quality or ""))
    return int(match.group(1)) if match else 0


def _stream_kind(url: str) -> str:
    lowered = url.lower()
    if ".m3u8" in lowered:
        return "hls"
    if ".mp4" in lowered or ".m4v" in lowered:
        return "mp4"
    if ".ts" in lowered:
        return "ts"
    return "other"
