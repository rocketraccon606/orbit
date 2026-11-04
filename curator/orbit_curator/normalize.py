"""Name cleanup, ids and dedupe helpers."""
from __future__ import annotations

import hashlib
import re

_TAG_RE = re.compile(r"[\[(]([^\])]*)[\])]")
_QUALITY_RE = re.compile(
    r"\b(uhd|fhd|hd|sd|4k|8k|1080p?|720p?|576p?|480p?|360p?|240p?)\b",
    re.IGNORECASE,
)
_GEO_RE = re.compile(
    r"(geo[- ]?blocked|not 24/7|24/7|backup|alternative|alt|test|offline)",
    re.IGNORECASE,
)
_SPACE_RE = re.compile(r"\s+")


def clean_name(name: str) -> str:
    if not name:
        return ""
    value = name.strip()
    value = _TAG_RE.sub(lambda m: "" if _is_noise(m.group(1)) else f"({m.group(1)})", value)
    value = _QUALITY_RE.sub("", value)
    value = _GEO_RE.sub("", value)
    value = _SPACE_RE.sub(" ", value).strip(" -|/")
    return value or name.strip()


def _is_noise(inner: str) -> bool:
    lowered = inner.strip().lower()
    return (
        "geo" in lowered
        or "24/7" in lowered
        or lowered in {"hd", "fhd", "uhd", "sd", "4k", "backup", "alt", "test", "offline"}
        or _QUALITY_RE.fullmatch(lowered) is not None
    )


def alt_names(value: object) -> str:
    if isinstance(value, list):
        items = [str(v).strip() for v in value if str(v).strip()]
    elif isinstance(value, str):
        items = [part.strip() for part in value.split(";") if part.strip()]
    else:
        return ""
    return " | ".join(dict.fromkeys(items))


def stable_id(name: str, country: str) -> str:
    seed = f"{clean_name(name).casefold()}|{(country or 'ZZ').upper()}"
    return hashlib.sha1(seed.encode("utf-8")).hexdigest()[:16]


def dedupe_urls(urls: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for url in urls:
        if url and url not in seen:
            seen.add(url)
            result.append(url)
    return result
