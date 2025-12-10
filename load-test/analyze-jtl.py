#!/usr/bin/env python3
"""Print a compact, dependency-free summary for a JMeter CSV JTL file."""

from __future__ import annotations

import csv
import math
import statistics
import sys
from collections import defaultdict
from pathlib import Path


def percentile(values: list[int], ratio: float) -> int:
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * ratio) - 1)
    return ordered[index]


def main() -> int:
    if len(sys.argv) != 2:
        print(f"Usage: {Path(sys.argv[0]).name} results.jtl", file=sys.stderr)
        return 2

    path = Path(sys.argv[1])
    groups: dict[str, list[dict[str, str]]] = defaultdict(list)
    all_rows: list[dict[str, str]] = []

    with path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        required = {"timeStamp", "elapsed", "label", "success"}
        if reader.fieldnames is None or not required.issubset(reader.fieldnames):
            print(f"Unsupported JTL columns: {reader.fieldnames}", file=sys.stderr)
            return 2
        for row in reader:
            try:
                int(row["timeStamp"])
                int(row["elapsed"])
            except (TypeError, ValueError):
                continue
            all_rows.append(row)
            groups[row["label"]].append(row)

    if not all_rows:
        print("No samples found in JTL.", file=sys.stderr)
        return 1

    print("API/JMeter summary (latency in ms; this is request latency, not judge completion latency)")
    print(
        f"{'label':<28} {'samples':>8} {'errors':>7} {'error%':>8} "
        f"{'avg':>8} {'p50':>8} {'p95':>8} {'p99':>8} {'max':>8} {'req/s':>8}"
    )

    for label in sorted(groups):
        rows = groups[label]
        elapsed = [int(row["elapsed"]) for row in rows]
        errors = sum(row["success"].lower() != "true" for row in rows)
        started = min(int(row["timeStamp"]) for row in rows)
        finished = max(int(row["timeStamp"]) + int(row["elapsed"]) for row in rows)
        seconds = max((finished - started) / 1000.0, 0.001)
        display_label = label if len(label) <= 28 else label[:25] + "..."
        print(
            f"{display_label:<28} {len(rows):>8} {errors:>7} "
            f"{errors / len(rows) * 100:>7.2f}% {statistics.fmean(elapsed):>8.1f} "
            f"{percentile(elapsed, 0.50):>8} {percentile(elapsed, 0.95):>8} "
            f"{percentile(elapsed, 0.99):>8} {max(elapsed):>8} {len(rows) / seconds:>8.2f}"
        )

    failed = [row for row in all_rows if row["success"].lower() != "true"]
    if failed:
        print("\nTop failures")
        failure_groups: dict[tuple[str, str, str], int] = defaultdict(int)
        for row in failed:
            key = (
                row.get("label", ""),
                row.get("responseCode", ""),
                row.get("failureMessage", "") or row.get("responseMessage", ""),
            )
            failure_groups[key] += 1
        for (label, code, message), count in sorted(
            failure_groups.items(), key=lambda item: item[1], reverse=True
        )[:10]:
            clean_message = " ".join(message.split())[:160]
            print(f"{count:>6}  {label}  HTTP/code={code}  {clean_message}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
