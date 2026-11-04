"""Build pre-sorted collections and country rows."""
from __future__ import annotations

from collections import Counter

from .config import (
    BUCKET_TO_SLUG,
    COUNTRY_COLLECTIONS,
    EDITOR_PICKS,
    GLOBAL_ORDER,
    MALAYALAM_ORDER,
    THEME_COLLECTIONS,
)
from .score import collection_sort_key, playable


def _boost_map() -> dict[str, int]:
    boost: dict[str, int] = {}
    for index, channel_id in enumerate(EDITOR_PICKS):
        boost.setdefault(channel_id, index)
    for index, channel_id in enumerate(GLOBAL_ORDER):
        boost.setdefault(channel_id, index + 1000)
    return boost


def build_collections(channels: list[dict]) -> list[dict]:
    by_id = {c["id"]: c for c in channels}
    pool = [c for c in channels if playable(c["state"])]
    boost = _boost_map()
    malayalam_boost = {cid: index for index, cid in enumerate(MALAYALAM_ORDER)}

    result: list[dict] = []
    for collection_id, title, kind in THEME_COLLECTIONS:
        items: list[dict] = []
        if collection_id == "editor-picks":
            items = [by_id[cid] for cid in EDITOR_PICKS if cid in by_id and playable(by_id[cid]["state"])]
            items.sort(key=lambda c: (EDITOR_PICKS.index(c["id"]), -c["best_score"], c["name"].casefold()))
            items = items[:30]
        elif collection_id == "malayalam":
            items = [c for c in pool if c["malayalam"]]
            items.sort(key=lambda c: (malayalam_boost.get(c["id"], len(malayalam_boost)),
                                      -c["best_score"], c["name"].casefold()))
        elif collection_id.startswith("india-"):
            slug = collection_id.split("-", 1)[1]
            items = [c for c in pool if c["country_id"] == "IN" and not c["malayalam"]
                     and BUCKET_TO_SLUG.get(c["category"], "other") == slug]
            items.sort(key=lambda c: collection_sort_key(c, boost))
        elif collection_id.startswith("world-"):
            slug = collection_id.split("-", 1)[1]
            items = [c for c in pool if c["country_id"] != "IN"
                     and BUCKET_TO_SLUG.get(c["category"], "other") == slug]
            items.sort(key=lambda c: collection_sort_key(c, boost))

        if items or collection_id == "malayalam":
            result.append({
                "id": collection_id,
                "title": title,
                "kind": kind,
                "items": [c["id"] for c in items],
            })

    country_counts = Counter(
        c["country_id"] for c in pool if c["country_id"] != "IN"
    )
    for code, count in country_counts.most_common(COUNTRY_COLLECTIONS):
        if count < 3 or code == "ZZ":
            continue
        items = [c for c in pool if c["country_id"] == code]
        items.sort(key=lambda c: collection_sort_key(c, boost))
        title = items[0]["country"] if items else code
        result.append({
            "id": f"country-{code.lower()}",
            "title": title,
            "kind": "country",
            "items": [c["id"] for c in items],
        })
    return result
