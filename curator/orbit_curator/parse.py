"""Tolerant M3U parser used for personal playlists and tests."""
from __future__ import annotations

import re
from dataclasses import dataclass, field

ATTR_RE = re.compile(r'([A-Za-z0-9_-]+)="([^"]*)"')


@dataclass
class M3uEntry:
    name: str
    url: str
    group: str = ""
    logo: str = ""
    tvg_id: str = ""
    attrs: dict[str, str] = field(default_factory=dict)
    user_agent: str | None = None
    referrer: str | None = None


def parse_m3u(text: str) -> list[M3uEntry]:
    entries: list[M3uEntry] = []
    pending: M3uEntry | None = None
    group = ""
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#EXTM3U"):
            continue
        if line.startswith("#EXTINF"):
            attrs = dict(ATTR_RE.findall(line))
            name = line.rsplit(",", 1)[-1].strip() if "," in line else ""
            pending = M3uEntry(
                name=name,
                url="",
                group=attrs.get("group-title", group),
                logo=attrs.get("tvg-logo", ""),
                tvg_id=attrs.get("tvg-id", ""),
                attrs=attrs,
            )
            continue
        if line.startswith("#EXTGRP:"):
            group = line.split(":", 1)[1].strip()
            if pending is not None and not pending.group:
                pending.group = group
            continue
        if line.startswith("#EXTVLCOPT:") and pending is not None:
            option = line.split(":", 1)[1].strip()
            if option.startswith("http-user-agent="):
                pending.user_agent = option.split("=", 1)[1]
            elif option.startswith("http-referrer=") or option.startswith("http-referer="):
                pending.referrer = option.split("=", 1)[1]
            continue
        if line.startswith("#"):
            continue
        if pending is None:
            continue
        if not line.lower().startswith(("http://", "https://")):
            continue
        pending.url = line
        if pending.name:
            entries.append(pending)
        pending = None
    return entries
