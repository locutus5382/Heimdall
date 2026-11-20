#!/usr/bin/env python3
"""Reproducible, dependency-free SQLite window-query microbenchmark.

Compares the v5 per-session insertion-order index with the v6 elapsed-time
index using identical synthetic rows and output order. This is NOT a physical
Android/Odin benchmark and never sets a CI performance threshold.
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import statistics
import time


def measure(db: sqlite3.Connection, sql: str, args: tuple[int, int, int],
            repeats: int) -> tuple[float, list[tuple[int, int, float]]]:
    durations = []
    expected = None
    for _ in range(repeats):
        t0 = time.perf_counter_ns()
        values = db.execute(sql, args).fetchall()
        durations.append((time.perf_counter_ns() - t0) / 1_000_000)
        if expected is None:
            expected = values
        assert values == expected, "query changed across benchmark repetitions"
    return statistics.median(durations), expected or []


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=int, default=50_000)
    parser.add_argument("--window-samples", type=int, default=100)
    parser.add_argument("--repetitions", type=int, default=15)
    args = parser.parse_args()
    if args.samples < 1000 or not (2 <= args.window_samples < args.samples):
        parser.error("need >=1000 samples and a smaller selected window")
    if args.repetitions < 3:
        parser.error("need at least three repetitions")

    with sqlite3.connect(":memory:") as db:
        db.execute(
            "CREATE TABLE samples (id INTEGER PRIMARY KEY, session_id INTEGER,"
            " elapsed_ms INTEGER, cpu_load_pct REAL)"
        )
        db.execute("CREATE INDEX samples_session_idx ON samples(session_id,id)")
        db.executemany(
            "INSERT INTO samples(session_id,elapsed_ms,cpu_load_pct) "
            "VALUES (?,?,?)",
            ((1, i * 2000, 30.0 + i % 40) for i in range(args.samples))
        )
        db.commit()
        center = args.samples // 2
        start = (center - args.window_samples // 2) * 2000
        end = start + (args.window_samples - 1) * 2000
        params = (1, start, end)
        sql = (
            "SELECT elapsed_ms,cpu_load_pct FROM samples "
            "WHERE session_id=? AND elapsed_ms>=? AND elapsed_ms<=? "
            "ORDER BY id"
        )
        before_plan = [row[3] for row in
                       db.execute("EXPLAIN QUERY PLAN " + sql, params)]
        before_ms, old_rows = measure(
            db, sql, params, args.repetitions
        )
        db.execute(
            "CREATE INDEX samples_session_elapsed_idx "
            "ON samples(session_id,elapsed_ms,id)"
        )
        indexed_sql = sql.replace(
            "FROM samples WHERE", "FROM samples "
            "INDEXED BY samples_session_elapsed_idx WHERE"
        )
        after_plan = [row[3] for row in
                      db.execute("EXPLAIN QUERY PLAN " + indexed_sql, params)]
        after_ms, new_rows = measure(
            db, indexed_sql, params, args.repetitions
        )
        assert old_rows == new_rows, "index must preserve source row contents/order"
        assert len(new_rows) == args.window_samples
        assert any("samples_session_elapsed_idx" in p for p in after_plan)
        print(json.dumps({
            "environment": "host_python_sqlite_synthetic_not_android",
            "rows_total": args.samples,
            "rows_selected": len(new_rows),
            "repetitions": args.repetitions,
            "baseline_median_ms": round(before_ms, 3),
            "indexed_median_ms": round(after_ms, 3),
            "observed_speedup_ratio": round(before_ms / after_ms, 2)
            if after_ms > 0 else None,
            "baseline_plan": before_plan,
            "indexed_plan": after_plan,
            "identical_results": True
        }, indent=2))


if __name__ == "__main__":
    main()
