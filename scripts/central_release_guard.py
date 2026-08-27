#!/usr/bin/env python3
"""Report org.unlaxer publish operations visible in Maven Central.

Maven Central's Usage Center needs a browser session.  This guard uses the
public repository directory timestamps instead, grouping reactor artifacts
published in the same UTC minute into one publish operation.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Callable
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


ENTRY_RE = re.compile(
    r'<a href="([^"?#]+?)/"[^>]*>.*?</a>\s+'
    r'(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})',
    re.IGNORECASE,
)
DIRECTORY_RE = re.compile(r'<a href="([^"?#]+?)/"', re.IGNORECASE)


@dataclass(frozen=True)
class DirectoryEntry:
    name: str
    published_at: datetime


def parse_directory_listing(html: str) -> list[DirectoryEntry]:
    entries: list[DirectoryEntry] = []
    for name, date_text, time_text in ENTRY_RE.findall(html):
        if name == ".." or "/" in name:
            continue
        published_at = datetime.strptime(
            f"{date_text} {time_text}", "%Y-%m-%d %H:%M"
        ).replace(tzinfo=timezone.utc)
        entries.append(DirectoryEntry(name=name, published_at=published_at))
    return entries


def parse_directory_names(html: str) -> list[str]:
    return sorted(
        {name for name in DIRECTORY_RE.findall(html) if name != ".." and "/" not in name}
    )


def fetch_url(url: str) -> str:
    request = Request(url, headers={"User-Agent": "unlaxer-central-release-guard/1"})
    with urlopen(request, timeout=20) as response:
        return response.read().decode("utf-8")


def collect_release_events(
    repository_base: str,
    namespace: str,
    month: str,
    fetch: Callable[[str], str] = fetch_url,
) -> list[dict[str, object]]:
    if not re.fullmatch(r"\d{4}-\d{2}", month):
        raise ValueError("month must use YYYY-MM")

    namespace_path = "/".join(quote(part, safe="") for part in namespace.split("."))
    group_url = f"{repository_base.rstrip('/')}/{namespace_path}/"
    artifacts = parse_directory_names(fetch(group_url))
    if not artifacts:
        raise RuntimeError(f"no artifact directories found at {group_url}")

    by_minute: dict[str, list[str]] = defaultdict(list)
    for artifact in artifacts:
        artifact_url = f"{group_url}{quote(artifact, safe='')}/"
        for version in parse_directory_listing(fetch(artifact_url)):
            minute = version.published_at.strftime("%Y-%m-%dT%H:%MZ")
            if minute.startswith(month):
                by_minute[minute].append(f"{namespace}:{artifact}:{version.name}")

    return [
        {"publishedAt": minute, "coordinates": sorted(coordinates)}
        for minute, coordinates in sorted(by_minute.items())
    ]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Report or enforce the org.unlaxer monthly Central release budget."
    )
    parser.add_argument("--namespace", default="org.unlaxer")
    parser.add_argument(
        "--month",
        default=datetime.now(timezone.utc).strftime("%Y-%m"),
        help="UTC calendar month (YYYY-MM)",
    )
    parser.add_argument(
        "--repository-base",
        default="https://repo1.maven.org/maven2",
    )
    parser.add_argument(
        "--max-releases",
        type=int,
        help="Fail when the current count has no capacity for another publish.",
    )
    parser.add_argument("--json", action="store_true", dest="as_json")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.max_releases is not None and args.max_releases < 1:
        print("--max-releases must be at least 1", file=sys.stderr)
        return 2

    try:
        events = collect_release_events(
            args.repository_base, args.namespace, args.month
        )
    except (HTTPError, URLError, TimeoutError, RuntimeError, ValueError) as error:
        print(f"Central release guard could not determine usage: {error}", file=sys.stderr)
        return 2

    count = len(events)
    has_capacity = args.max_releases is None or count < args.max_releases
    report = {
        "namespace": args.namespace,
        "month": args.month,
        "publishedOperations": count,
        "maxPublishOperations": args.max_releases,
        "hasCapacity": has_capacity,
        "events": events,
        "source": args.repository_base,
    }

    if args.as_json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"Central publish usage for {args.namespace} ({args.month}, UTC)")
        print(f"Published operations: {count}")
        if args.max_releases is not None:
            print(f"Monthly policy maximum: {args.max_releases}")
        for event in events:
            coordinates = event["coordinates"]
            print(f"- {event['publishedAt']}: {len(coordinates)} artifact version(s)")
            for coordinate in coordinates:
                print(f"    {coordinate}")

    if not has_capacity:
        print(
            "Monthly Central release budget is exhausted. Queue the change for "
            "next month or use the explicit emergency path.",
            file=sys.stderr,
        )
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
