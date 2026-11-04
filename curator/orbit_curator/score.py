"""Stream and channel scoring plus fallback-chain ordering."""
from __future__ import annotations

import re

_HEIGHT_RE = re.compile(r"(\d{3,4})")


def height(resolution: str | None) -> int:
    if not resolution:
        return 0
    match = _HEIGHT_RE.search(str(resolution))
    return int(match.group(1)) if match else 0


def stream_score(state: str, resolution: str | None, latency_ms: int | None, hard_fails: int) -> int:
    res_bonus = {1080: 15, 720: 12, 576: 8, 480: 5}.get(
        _bucket_height(height(resolution)), 2,
    )
    latency = latency_ms if latency_ms is not None else 99999
    if latency <= 500:
        latency_bonus = 15
    elif latency <= 1000:
        latency_bonus = 12
    elif latency <= 2000:
        latency_bonus = 8
    elif latency <= 4000:
        latency_bonus = 4
    else:
        latency_bonus = 1
    if state == "alive":
        return max(0, 70 + res_bonus + latency_bonus - min(10 * hard_fails, 30))
    if state == "unverified":
        return max(0, 30 + res_bonus // 2 + latency_bonus // 2 - min(5 * hard_fails, 20))
    return 0


def _bucket_height(value: int) -> int:
    if value >= 1080:
        return 1080
    if value >= 720:
        return 720
    if value >= 576:
        return 576
    if value >= 480:
        return 480
    return 0


def stream_sort_key(stream: dict) -> tuple:
    state_rank = {"alive": 0, "unverified": 1, "dead": 2}.get(stream["state"], 3)
    return (
        state_rank,
        -stream["score"],
        -height(stream.get("resolution")),
        stream.get("latency_ms") or 999999,
        stream["url"],
    )


def order_streams(streams: list[dict]) -> list[dict]:
    ordered = sorted(streams, key=stream_sort_key)
    for position, stream in enumerate(ordered):
        stream["position"] = position
    return ordered


def channel_state(streams: list[dict]) -> str:
    states = {s["state"] for s in streams}
    if "alive" in states:
        return "alive"
    if "unverified" in states:
        return "unverified"
    return "dead"


def channel_score(streams: list[dict]) -> int:
    return max((s["score"] for s in streams), default=0)


def playable(state: str) -> bool:
    return state in ("alive", "unverified")


def collection_sort_key(channel: dict, boost: dict[str, int]) -> tuple:
    return (
        boost.get(channel["id"], len(boost)),
        -channel["best_score"],
        channel["name"].casefold(),
    )
