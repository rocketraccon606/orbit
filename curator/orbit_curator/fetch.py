"""Cached HTTP fetch of the iptv-org API files."""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

from .config import CACHE_TTL_SECONDS, SOURCES, USER_AGENT


class SourceError(RuntimeError):
    pass


def load_sources(
    cache_dir: Path,
    source_dir: Path | None = None,
    offline: bool = False,
    ttl: int = CACHE_TTL_SECONDS,
) -> dict[str, object]:
    """Return {filename: parsed json} for every source file.

    Uses a local directory when given, otherwise the cached API copy, refreshing it
    when stale. On a network error the stale cache is used if it exists.
    """
    cache_dir.mkdir(parents=True, exist_ok=True)
    data: dict[str, object] = {}
    required = list(SOURCES["files"])
    names = required + [n for n in SOURCES.get("optional_files", []) if n not in required]
    for name in names:
        is_required = name in required
        if source_dir is not None:
            path = source_dir / name
            if not path.exists():
                if is_required:
                    raise SourceError(f"missing source file: {path}")
                continue
            data[name] = json.loads(path.read_text(encoding="utf-8"))
            continue
        path = cache_dir / name
        if offline:
            if path.exists():
                data[name] = json.loads(path.read_text(encoding="utf-8"))
            elif is_required:
                raise SourceError(f"offline and no cached {name} in {cache_dir}")
            continue
        if path.exists() and time.time() - path.stat().st_mtime < ttl:
            data[name] = json.loads(path.read_text(encoding="utf-8"))
            continue
        try:
            data[name] = _download(name, path)
        except (urllib.error.URLError, OSError, ValueError) as exc:
            if path.exists():
                print(f"warning: {name} refresh failed ({exc}); using cached copy", flush=True)
                data[name] = json.loads(path.read_text(encoding="utf-8"))
            elif is_required:
                raise SourceError(f"could not fetch {name}: {exc}") from exc
            else:
                print(f"warning: optional {name} unavailable ({exc}); skipping", flush=True)
    return data


def _download(name: str, path: Path) -> object:
    url = f"{SOURCES['api_base']}/{name}"
    etag_path = path.with_suffix(path.suffix + ".etag")
    headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    if etag_path.exists():
        headers["If-None-Match"] = etag_path.read_text(encoding="utf-8").strip()
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            body = response.read()
            temp = path.with_suffix(path.suffix + ".tmp")
            temp.write_bytes(body)
            os.replace(temp, path)
            etag = response.headers.get("ETag")
            if etag:
                etag_path.write_text(etag, encoding="utf-8")
            return json.loads(body)
    except urllib.error.HTTPError as exc:
        if exc.code == 304 and path.exists():
            os.utime(path, None)
            return json.loads(path.read_text(encoding="utf-8"))
        raise
