"""Three-state stream health checks and cross-run history merging."""
from __future__ import annotations

import json
import os
import re
import socket
import ssl
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urljoin, urlparse

from .config import USER_AGENT

_ATTR_RE = re.compile(r'([A-Z0-9-]+)=("[^"]*"|[^,]*)')
_DRM_RE = re.compile(
    r"#EXT-X-KEY:[^\n]*(METHOD=SAMPLE-AES|KEYFORMAT=\"urn:uuid:|KEYFORMAT=\"com\.apple\.streamingkeydelivery\")",
    re.IGNORECASE,
)
_HTML_TYPES = ("text/html", "application/json", "text/xml", "application/xml")


@dataclass
class HealthResult:
    state: str
    hard_fail: bool
    latency_ms: int | None
    resolution: str
    reason: str


def _soft(reason: str, latency: int | None = None, resolution: str = "") -> HealthResult:
    return HealthResult("unverified", False, latency, resolution, reason)


def _hard(reason: str, latency: int | None = None) -> HealthResult:
    return HealthResult("unverified", True, latency, "", reason)


def probe(
    url: str,
    timeout: float = 8.0,
    user_agent: str | None = None,
    referrer: str | None = None,
) -> HealthResult:
    headers = {"User-Agent": user_agent or USER_AGENT, "Accept": "*/*"}
    if referrer:
        headers["Referer"] = referrer
    started = time.monotonic()
    try:
        with urllib.request.urlopen(
            urllib.request.Request(url, headers=headers), timeout=timeout,
        ) as response:
            latency = int((time.monotonic() - started) * 1000)
            if response.status >= 400:
                return _soft(f"http {response.status}", latency)
            content_type = (response.headers.get("Content-Type") or "").lower()
            data = response.read(262_144)
    except urllib.error.HTTPError as exc:
        latency = int((time.monotonic() - started) * 1000)
        if exc.code in (404, 410):
            return _hard(f"http {exc.code}", latency)
        return _soft(f"http {exc.code}", latency)
    except urllib.error.URLError as exc:
        reason = getattr(exc, "reason", exc)
        latency = int((time.monotonic() - started) * 1000)
        if isinstance(reason, socket.gaierror):
            return _hard(f"dns {reason}", latency)
        return _soft(str(reason), latency)
    except (TimeoutError, socket.timeout):
        return _soft("timeout", int((time.monotonic() - started) * 1000))
    except (ssl.SSLError, OSError) as exc:
        return _soft(str(exc), int((time.monotonic() - started) * 1000))

    if not data:
        return _hard("empty body", latency)

    text = data.decode("utf-8", "ignore") if _looks_textual(data, content_type) else ""
    is_hls = (
        urlparse(url).path.lower().endswith(".m3u8")
        or "mpegurl" in content_type
        or text.lstrip().startswith("#EXTM3U")
    )
    if is_hls:
        if not text:
            return _hard("invalid hls manifest", latency)
        effective, resolution = _resolve_hls(text, url, timeout, headers)
        if effective is None:
            return _soft("hls did not load", latency)
        if _DRM_RE.search(effective):
            return _soft("drm", latency, resolution)
        segment = _first_segment(effective, url)
        if segment is None:
            return _soft("no media segment", latency, resolution)
        if _fetch_bytes(segment, timeout, headers, 65_536) > 0:
            return HealthResult("alive", False, latency, resolution, "ok")
        return _soft("segment empty", latency, resolution)

    if any(marker in content_type for marker in _HTML_TYPES):
        return _hard("invalid body", latency)
    if "video" in content_type or "audio" in content_type or "mpeg" in content_type or "octet-stream" in content_type:
        return HealthResult("alive", False, latency, "", "ok")
    if data[0] == 0x47 or data[4:8] == b"ftyp":
        return HealthResult("alive", False, latency, "", "ok")
    return _hard("unrecognized stream", latency)


def _looks_textual(data: bytes, content_type: str) -> bool:
    if "mpegurl" in content_type or "text" in content_type or "json" in content_type:
        return True
    return data.lstrip().startswith(b"#EXTM3U")


def _parse_attrs(line: str) -> dict[str, str]:
    return {
        key: value.strip('"')
        for key, value in _ATTR_RE.findall(line.split(":", 1)[-1])
    }


def _resolve_hls(text: str, base: str, timeout: float, headers: dict[str, str]) -> tuple[str | None, str]:
    """Follow a master playlist to its lowest-bandwidth variant; return media text."""
    if "#EXT-X-STREAM-INF" not in text:
        return text, _resolution_from_attrs({})

    variants = parse_master_variants(text, base)
    if not variants:
        return None, ""
    variants.sort(key=lambda item: (item[0] or 10**9, _height(item[1])))
    bandwidth, resolution, variant_url = variants[0]
    try:
        with urllib.request.urlopen(urllib.request.Request(variant_url, headers=headers), timeout=timeout) as response:
            if response.status >= 400:
                return None, resolution
            return response.read(262_144).decode("utf-8", "ignore"), resolution
    except (urllib.error.URLError, OSError, TimeoutError):
        return None, resolution


def parse_master_variants(text: str, base: str) -> list[tuple[int, str, str]]:
    """Return (bandwidth, resolution, absolute_url) for each master variant."""
    variants: list[tuple[int, str, str]] = []
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if not line.startswith("#EXT-X-STREAM-INF"):
            continue
        attrs = _parse_attrs(line)
        resolution = _resolution_from_attrs(attrs)
        bandwidth = int(attrs.get("BANDWIDTH") or attrs.get("AVERAGE-BANDWIDTH") or 0)
        for uri in lines[index + 1:]:
            candidate = uri.strip()
            if not candidate or candidate.startswith("#"):
                continue
            variants.append((bandwidth, resolution, urljoin(base, candidate)))
            break
    return variants


def _resolution_from_attrs(attrs: dict[str, str]) -> str:
    value = attrs.get("RESOLUTION", "")
    if "x" in value:
        return f"{value.split('x', 1)[1]}p"
    return ""


def _height(resolution: str) -> int:
    digits = re.search(r"(\d{3,4})", resolution or "")
    return int(digits.group(1)) if digits else 0


def _first_segment(text: str, base: str) -> str | None:
    for line in text.splitlines():
        candidate = line.strip()
        if candidate and not candidate.startswith("#"):
            return urljoin(base, candidate)
    return None


def _fetch_bytes(url: str, timeout: float, headers: dict[str, str], limit: int) -> int:
    request = urllib.request.Request(url, headers={**headers, "Range": f"bytes=0-{limit - 1}"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            if response.status >= 400:
                return 0
            return len(response.read(limit))
    except (urllib.error.URLError, OSError, TimeoutError):
        return 0


def load_history(path: Path) -> dict[str, dict]:
    if not path.exists():
        return {}
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
        return raw if isinstance(raw, dict) else {}
    except (ValueError, TypeError):
        return {}


def save_history(path: Path, history: dict[str, dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(history, sort_keys=True), encoding="utf-8")
    os.replace(temp, path)


def merge_result(previous: dict | None, result: HealthResult, resolution_hint: str = "") -> dict:
    previous = previous or {}
    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    if result.state == "alive":
        return {
            "state": "alive",
            "hard_fails": 0,
            "last_ok": now,
            "latency_ms": result.latency_ms,
            "checked_at": now,
            "resolution": result.resolution or resolution_hint,
        }
    if result.hard_fail:
        hard_fails = int(previous.get("hard_fails") or 0) + 1
        return {
            "state": "dead" if hard_fails >= 2 else "unverified",
            "hard_fails": hard_fails,
            "last_ok": previous.get("last_ok"),
            "latency_ms": previous.get("latency_ms"),
            "checked_at": now,
            "resolution": previous.get("resolution") or resolution_hint,
        }
    return {
        "state": "dead" if int(previous.get("hard_fails") or 0) >= 2 else "unverified",
        "hard_fails": int(previous.get("hard_fails") or 0),
        "last_ok": previous.get("last_ok"),
        "latency_ms": result.latency_ms or previous.get("latency_ms"),
        "checked_at": now,
        "resolution": result.resolution or previous.get("resolution") or resolution_hint,
    }
