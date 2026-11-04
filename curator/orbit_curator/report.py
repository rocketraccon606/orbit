"""Human/machine readable run report."""
from __future__ import annotations

import json
from pathlib import Path


def make_report(
    *,
    version: str,
    source: str,
    license_name: str,
    durations: dict,
    candidates: dict,
    counts: dict,
    collections: list[dict],
    gzip_size: int,
    db_size: int,
    dead_examples: list[dict],
    history_entries: int,
) -> dict:
    return {
        "version": version,
        "built_at": version,
        "source": source,
        "source_license": license_name,
        "durations": durations,
        "candidates": candidates,
        "counts": counts,
        "collections": [
            {"id": c["id"], "title": c["title"], "count": len(c["items"])}
            for c in collections
        ],
        "gzip_size_bytes": gzip_size,
        "db_size_bytes": db_size,
        "history_entries": history_entries,
        "gate": {"passed": False, "reasons": []},
        "dead_examples": dead_examples[:20],
    }


def write_report(path: Path, report: dict) -> None:
    path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
