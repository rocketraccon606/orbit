"""Publish gate: refuse to replace a healthy catalog with a broken one."""
from __future__ import annotations

import json
import sqlite3
from pathlib import Path

from .config import (
    MAX_DROP_RATIO,
    MAX_GZIP_BYTES,
    MIN_MALAYALAM_PLAYABLE,
    MIN_TOTAL_PLAYABLE,
    SCHEMA_VERSION,
)

REQUIRED_TABLES = {"meta", "country", "language", "category", "channel", "stream",
                   "collection", "collection_item", "channel_fts"}


def validate(
    catalog_path: Path,
    report: dict,
    previous_report: dict | None = None,
) -> list[str]:
    reasons: list[str] = []
    if not catalog_path.exists():
        return ["catalog.db is missing"]

    try:
        conn = sqlite3.connect(f"file:{catalog_path}?mode=ro", uri=True)
    except sqlite3.Error as exc:
        return [f"catalog.db cannot be opened: {exc}"]

    try:
        version = conn.execute("PRAGMA user_version").fetchone()[0]
        if version != SCHEMA_VERSION:
            reasons.append(f"schema version is {version}, expected {SCHEMA_VERSION}")
        tables = {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type IN ('table','view')")}
        missing = REQUIRED_TABLES - tables
        if missing:
            reasons.append("missing tables: " + ", ".join(sorted(missing)))
        integrity = conn.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            reasons.append(f"integrity check failed: {integrity}")
        if "channel" in tables and "channel_fts" in tables:
            channels = conn.execute("SELECT COUNT(*) FROM channel").fetchone()[0]
            fts_rows = conn.execute("SELECT COUNT(*) FROM channel_fts").fetchone()[0]
            if channels != fts_rows:
                reasons.append(f"FTS rows {fts_rows} != channel rows {channels}")
    except sqlite3.Error as exc:
        reasons.append(f"catalog query failed: {exc}")
    finally:
        conn.close()

    counts = report.get("counts") or {}
    malayalam = int(counts.get("malayalam") or 0)
    if malayalam < MIN_MALAYALAM_PLAYABLE:
        reasons.append(f"malayalam playable {malayalam} < {MIN_MALAYALAM_PLAYABLE}")
    playable = int(counts.get("playable") or 0)
    if playable < MIN_TOTAL_PLAYABLE:
        reasons.append(f"total playable {playable} < {MIN_TOTAL_PLAYABLE}")
    gzip_size = int(report.get("gzip_size_bytes") or 0)
    if gzip_size > MAX_GZIP_BYTES:
        reasons.append(f"catalog.gz is {gzip_size} bytes > {MAX_GZIP_BYTES}")

    if previous_report:
        previous_counts = previous_report.get("counts") or {}
        previous_playable = int(previous_counts.get("playable") or 0)
        if previous_playable > 0 and playable < previous_playable * (1 - MAX_DROP_RATIO):
            reasons.append(
                f"playable dropped from {previous_playable} to {playable} "
                f"(> {int(MAX_DROP_RATIO * 100)}%)"
            )
        previous_collections = {
            c.get("id"): int(c.get("count") or 0)
            for c in previous_report.get("collections", []) if c.get("count", 0) > 0
        }
        current_collections = {
            c.get("id") for c in report.get("collections", []) if c.get("count", 0) > 0
        }
        for collection_id in sorted(set(previous_collections) - current_collections):
            # A tiny country row can vanish when all of its streams fail one
            # health sweep; that is noise, not a broken catalog. Core thematic
            # collections and large country rows still block the publish.
            if collection_id.startswith("country-") and previous_collections[collection_id] < 10:
                continue
            reasons.append(f"collection {collection_id} became empty")
    return reasons


def load_report(path: Path | None) -> dict | None:
    if path is None or not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, OSError):
        return None
