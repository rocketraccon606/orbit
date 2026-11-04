"""Command line entry point."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .build import build
from .report import write_report
from .validate import load_report, validate


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="orbit_curator", description="Build the Orbit IPTV catalog")
    sub = parser.add_subparsers(dest="command", required=True)

    build_parser = sub.add_parser("build", help="fetch, check and write the catalog")
    build_parser.add_argument("--out", default="curator/dist")
    build_parser.add_argument("--cache", default="curator/.cache")
    build_parser.add_argument("--history", default="curator/.state/health.json")
    build_parser.add_argument("--previous-report")
    build_parser.add_argument("--source-dir")
    build_parser.add_argument("--fast", action="store_true", help="skip network health checks")
    build_parser.add_argument("--offline", action="store_true", help="use cached sources only")
    build_parser.add_argument("--max-checks", type=int)
    build_parser.add_argument("--workers", type=int, default=24)
    build_parser.add_argument("--timeout", type=float, default=8.0)

    validate_parser = sub.add_parser("validate", help="check a built catalog against the publish gate")
    validate_parser.add_argument("--catalog", required=True)
    validate_parser.add_argument("--report", required=True)
    validate_parser.add_argument("--previous-report")

    args = parser.parse_args(argv)

    if args.command == "build":
        return build(
            out_dir=Path(args.out),
            cache_dir=Path(args.cache),
            history_path=Path(args.history),
            previous_report_path=Path(args.previous_report) if args.previous_report else None,
            fast=args.fast,
            offline=args.offline,
            max_checks=args.max_checks,
            workers=max(1, args.workers),
            timeout=max(1.0, args.timeout),
            source_dir=Path(args.source_dir) if args.source_dir else None,
        )

    catalog = Path(args.catalog)
    report_path = Path(args.report)
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print(f"cannot read report: {exc}", file=sys.stderr)
        return 1
    reasons = validate(catalog, report, load_report(Path(args.previous_report) if args.previous_report else None))
    if reasons:
        report["gate"] = {"passed": False, "reasons": reasons}
        write_report(report_path, report)
        print("publish gate failed:", file=sys.stderr)
        for reason in reasons:
            print(f"  - {reason}", file=sys.stderr)
        return 1
    print("publish gate passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
