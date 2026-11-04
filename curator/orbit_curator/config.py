"""Curator configuration and shared constants (stdlib only)."""
from __future__ import annotations

import json
from pathlib import Path

PKG_DIR = Path(__file__).resolve().parent
CONFIG_DIR = PKG_DIR / "config"

USER_AGENT = "OrbitCurator/1.0 (+https://github.com/rocketraccon606/orbit)"
SCHEMA_VERSION = 2
MIN_APP_VERSION = 2
CACHE_TTL_SECONDS = 24 * 3600

with (CONFIG_DIR / "sources.json").open(encoding="utf-8") as f:
    SOURCES = json.load(f)
with (CONFIG_DIR / "editorial.json").open(encoding="utf-8") as f:
    EDITORIAL = json.load(f)
with (CONFIG_DIR / "rules.json").open(encoding="utf-8") as f:
    RULES = json.load(f)

MALAYALAM_IDS = list(EDITORIAL["malayalam_ids"])
MALAYALAM_ORDER = list(EDITORIAL["malayalam_order"])
GLOBAL_IDS = set(EDITORIAL["global_ids"])
GLOBAL_ORDER = list(EDITORIAL["global_order"])
EDITOR_PICKS = list(EDITORIAL["editor_picks"])

CATEGORY_BUCKETS = {
    "news": "News",
    "general": "Entertainment",
    "entertainment": "Entertainment",
    "culture": "Entertainment",
    "comedy": "Entertainment",
    "series": "Entertainment",
    "talk": "Entertainment",
    "lifestyle": "Entertainment",
    "travel": "Entertainment",
    "food": "Entertainment",
    "cooking": "Entertainment",
    "quiz": "Entertainment",
    "game": "Entertainment",
    "business": "Entertainment",
    "auto": "Entertainment",
    "shop": "Entertainment",
    "weather": "Entertainment",
    "movies": "Movies",
    "classic": "Movies",
    "music": "Music",
    "relax": "Music",
    "sports": "Sports",
    "outdoor": "Sports",
    "kids": "Kids",
    "animation": "Kids",
    "family": "Kids",
    "documentary": "Documentary",
    "science": "Documentary",
    "education": "Documentary",
    "history": "Documentary",
    "religious": "Religious",
}

# Lower rank wins when choosing world/India candidates.
CATEGORY_RANK = {
    "news": 0, "general": 1, "entertainment": 2, "movies": 3, "sports": 4,
    "documentary": 5, "music": 6, "kids": 7, "education": 8, "religious": 9,
}

THEME_COLLECTIONS = [
    ("editor-picks", "Editor's Picks", "curated"),
    ("malayalam", "Malayalam", "theme"),
    ("india-news", "Indian News", "theme"),
    ("india-entertainment", "Indian Entertainment", "theme"),
    ("india-movies", "Indian Movies", "theme"),
    ("india-music", "Indian Music", "theme"),
    ("india-kids", "Indian Kids", "theme"),
    ("india-sports", "Indian Sports", "theme"),
    ("india-religious", "Indian Devotional", "theme"),
    ("india-other", "Indian Channels", "theme"),
    ("world-news", "World News", "theme"),
    ("world-entertainment", "World Entertainment", "theme"),
    ("world-movies", "World Movies", "theme"),
    ("world-music", "World Music", "theme"),
    ("world-kids", "World Kids", "theme"),
    ("world-sports", "World Sports", "theme"),
    ("world-documentary", "World Documentary", "theme"),
    ("world-other", "World Channels", "theme"),
]

BUCKET_TO_SLUG = {
    "News": "news",
    "Entertainment": "entertainment",
    "Movies": "movies",
    "Music": "music",
    "Kids": "kids",
    "Sports": "sports",
    "Documentary": "documentary",
    "Religious": "religious",
    "Other": "other",
}

MAX_CHANNELS = 1200
MAX_INDIA = 520
MAX_STREAMS_PER_CHANNEL = 3
MIN_MALAYALAM_PLAYABLE = 10
MIN_TOTAL_PLAYABLE = 250
MAX_GZIP_BYTES = 8 * 1024 * 1024
MAX_DROP_RATIO = 0.15
COUNTRY_COLLECTIONS = 10
