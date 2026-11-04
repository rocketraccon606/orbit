"""Classification: category, language and country with simple confidence."""
from __future__ import annotations

import re
from dataclasses import dataclass

from .config import CATEGORY_BUCKETS, MALAYALAM_IDS, RULES

MALAYALAM_SCRIPT = re.compile(r"[\u0D00-\u0D7F]")
_STRONG_MALAYALAM = ("malayalam", "kerala", "malayali")
_BRAND_MALAYALAM = tuple(RULES["malayalam_keywords"])


@dataclass
class Classification:
    category: str
    category_id: str
    language: str
    language_id: str
    country: str
    country_id: str
    confidence: float
    malayalam: bool


def classify(
    channel: dict,
    countries: dict[str, dict],
    languages: dict[str, dict],
) -> Classification:
    name = str(channel.get("name") or "")
    alt = channel.get("alt_names") or []
    alt_text = " ".join(str(a) for a in alt) if isinstance(alt, list) else str(alt)
    haystack = f"{name} {alt_text}".casefold()
    country_code = str(channel.get("country") or "ZZ").upper()
    country = countries.get(country_code)
    country_name = country["name"] if country else "International"

    category, category_confidence = _category(channel, haystack)
    malayalam = _is_malayalam(channel, haystack, alt_text, country_code)
    language_id, language_confidence = _language(channel, haystack, country, malayalam)
    if malayalam:
        language_id = "mal"
    language_name = languages.get(language_id, {}).get("name") or {
        "und": "Unknown",
        "mal": "Malayalam",
    }.get(language_id, language_id.upper())

    confidence = max(category_confidence, language_confidence)
    return Classification(
        category=category,
        category_id=category.lower(),
        language=language_name,
        language_id=language_id,
        country=country_name,
        country_id=country_code,
        confidence=confidence,
        malayalam=malayalam,
    )


def _category(channel: dict, haystack: str) -> tuple[str, float]:
    for cat in channel.get("categories") or []:
        bucket = CATEGORY_BUCKETS.get(str(cat).lower())
        if bucket:
            return bucket, 0.85
    for bucket, keywords in RULES["category_keywords"].items():
        if any(keyword in haystack for keyword in keywords):
            return _bucket_title(bucket), 0.6
    return "Other", 0.4


def _bucket_title(key: str) -> str:
    return {
        "news": "News", "movies": "Movies", "music": "Music", "sports": "Sports",
        "kids": "Kids", "documentary": "Documentary", "religious": "Religious",
        "entertainment": "Entertainment",
    }.get(key, "Other")


def _is_malayalam(channel: dict, haystack: str, alt_text: str, country: str) -> bool:
    if channel.get("id") in MALAYALAM_IDS:
        return True
    if MALAYALAM_SCRIPT.search(str(channel.get("name") or "")) or MALAYALAM_SCRIPT.search(alt_text):
        return True
    if any(word in haystack for word in _STRONG_MALAYALAM):
        return True
    if country == "IN" and any(word in haystack for word in _BRAND_MALAYALAM):
        return True
    return False


def _language(
    channel: dict,
    haystack: str,
    country: dict | None,
    malayalam: bool,
) -> tuple[str, float]:
    for code in channel.get("languages") or []:
        code = str(code).lower()
        if code:
            return code, 0.85
    for code, keywords in RULES["language_keywords"].items():
        if any(keyword in haystack for keyword in keywords):
            return code, 0.6
    if malayalam:
        return "mal", 0.9
    if country and RULES.get("allow_country_default_language", True):
        codes = country.get("languages") or []
        if codes:
            return str(codes[0]).lower(), 0.4
    return "und", 0.3


def is_curated(channel_id: str) -> bool:
    from .config import EDITOR_PICKS, GLOBAL_IDS

    return channel_id in MALAYALAM_IDS or channel_id in GLOBAL_IDS or channel_id in EDITOR_PICKS
