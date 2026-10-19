#!/usr/bin/env python3
"""Aggregates the B.1 (E1 write) cells of decision D224: per setup, distribution and rate, the median and the min-max
of the three repetitions, plus the resource cost per 1,000 completed commands. Standard library only.

    python3 -I aggregate.py [<folder>] # Markdown tables on stdout, summary.json in the folder (this script's by default)

The folder is a tier's report folder of one scenario (harness/tiers.sh): v10-vs-9x-e1, -e2 or -e3. Two repetitions
are a cell's median when no third was needed.
"""
import json
import os
import statistics
import sys

HERE = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
SETUPS = ["9x-mongo", "v10-mongo", "v10-postgres"]
DISTRIBUTIONS = ["uniform", "zipf", "hot"]
SETUP_NAMES = {"9x-mongo": "9.x Mongo", "v10-mongo": "V10 Mongo", "v10-postgres": "V10 PostgreSQL"}
LATENCIES = ["p50", "p95", "p99", "p999", "max"]


def load(path):
    with open(path) as f:
        return json.load(f)


def cell_metrics(result):
    r = result["results"]
    res = result["resources"]
    ok = max(1, r["ok"])
    per_k = 1000.0 / ok
    node = res["processes"]["node-1"]
    gc = node.get("gc", {})
    delta = gc.get("delta", {})
    seconds = res["seconds"]
    containers = res.get("containers", {})
    container_cpu = {name: c.get("cpuPercentAverage", 0.0) / 100.0 * seconds for name, c in containers.items()}
    stores = res.get("stores", {}).get("delta", {})
    mongo = stores.get("mongo", {})
    pg = stores.get("postgres", {})
    kafka = stores.get("kafkaEndOffsets", {})
    mongo_ops = sum(mongo.get(k, 0) for k in ["insert", "query", "update", "delete", "getmore", "command"])
    pg_tuples = sum(pg.get(k, 0) for k in ["tup_inserted", "tup_updated", "tup_deleted", "tup_fetched"])
    kafka_records = sum(v for k, v in kafka.items() if not k.startswith("__"))
    m = {
        "throughput": r["throughput"],
        "ok": r["ok"],
        "failed": r["failed"],
        "errorPercent": r["errorPercent"],
        "errors": r.get("errors", {}),
        "worstSecondP99": r["worstSecondP99Ms"],
        "maxSendLagMs": r["maxSendLagMs"],
        "serverCpuSeconds": node["cpuSeconds"],
        "serverCores": node["averageCores"],
        "serverRssMaxMb": node["rssMaxMb"],
        "heapUsedMaxMb": gc.get("heapUsedMaxMb"),
        "heapUsedAverageMb": gc.get("heapUsedAverageMb"),
        "gcCount": delta.get("YGC", 0) + delta.get("FGC", 0) + delta.get("CGC", 0),
        "gcSeconds": delta.get("GCT", 0.0),
        "serverCpuPer1k": node["cpuSeconds"] * per_k,
        "gcMsPer1k": delta.get("GCT", 0.0) * 1000 * per_k,
        "containerCpuPer1k": sum(container_cpu.values()) * per_k,
        "totalCpuPer1k": (node["cpuSeconds"] + sum(container_cpu.values())) * per_k,
        "kafkaCpuPer1k": sum(v for k, v in container_cpu.items() if "kafka" in k) * per_k,
        "storeCpuPer1k": sum(v for k, v in container_cpu.items() if "kafka" not in k) * per_k,
        "mongoOpsPer1k": mongo_ops * per_k if mongo else None,
        "mongoPer1k": {k: v * per_k for k, v in mongo.items()} if mongo else None,
        "pgCommitsPer1k": pg.get("xact_commit", 0) * per_k if pg else None,
        "pgTuplesPer1k": pg_tuples * per_k if pg else None,
        "pgPer1k": {k: v * per_k for k, v in pg.items()} if pg else None,
        "kafkaRecordsPer1k": kafka_records * per_k,
        "kafkaPer1k": {k: v * per_k for k, v in kafka.items() if not k.startswith("__")},
    }
    for name in LATENCIES:
        m[name] = r["latencyMs"][name]
    return m


def summarize(cells):
    """Median and min-max over the repetitions of every numeric metric."""
    out = {}
    for key in cells[0]:
        values = [c[key] for c in cells]
        if all(isinstance(v, (int, float)) for v in values):
            out[key] = {"median": statistics.median(values), "min": min(values), "max": max(values)}
        elif all(isinstance(v, dict) for v in values) and all(
            all(isinstance(x, (int, float)) for x in v.values()) for v in values
        ):
            keys = sorted(set().union(*values))
            out[key] = {k: statistics.median([v.get(k, 0) for v in values]) for k in keys}
    return out


def collect():
    data = {}
    for setup in SETUPS:
        for dist in DISTRIBUTIONS:
            base = os.path.join(HERE, setup, dist)
            if not os.path.isdir(base):
                continue
            entry = data.setdefault(setup, {}).setdefault(dist, {"rates": {}})
            sat = os.path.join(base, "saturation", "saturation.json")
            if os.path.exists(sat):
                s = load(sat)
                entry["saturation"] = {
                    "throughput": s["saturationThroughput"],
                    "concurrency": s["saturationConcurrency"],
                    "steps": s["steps"],
                }
            groups = {}
            for name in sorted(os.listdir(base)):
                path = os.path.join(base, name, "result.json")
                if name == "saturation" or not os.path.exists(path):
                    continue
                label, rep = name.rsplit("-r", 1)
                result = load(path)
                groups.setdefault(label, []).append((int(rep), result))
            for label, items in groups.items():
                items.sort()
                cells = [cell_metrics(result) for _, result in items]
                entry["rates"][label] = {
                    "rate": items[0][1]["load"]["rate"],
                    "repetitions": len(items),
                    "summary": summarize(cells),
                    "cells": cells,
                }
    return data


def fmt(v, digits=1):
    if v is None:
        return "-"
    return f"{v:,.{digits}f}"


def med(summary, key, digits=1):
    s = summary[key]
    return fmt(s["median"], digits)


def spread(summary, key, digits=1):
    s = summary[key]
    return f"{fmt(s['median'], digits)} ({fmt(s['min'], digits)}–{fmt(s['max'], digits)})"


def latency_table(rows):
    lines = [
        "| Rate | Setup | Throughput /s | Errors | p50 ms | p95 ms | p99 ms | p999 ms | Max ms | Worst-second p99 ms |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for rate_name, setup, entry in rows:
        s = entry["summary"]
        lines.append(
            f"| {rate_name} | {SETUP_NAMES[setup]} | {spread(s, 'throughput')} | {med(s, 'failed', 0)} "
            f"| {spread(s, 'p50', 2)} | {spread(s, 'p95', 2)} | {spread(s, 'p99', 2)} | {spread(s, 'p999', 1)} "
            f"| {spread(s, 'max', 1)} | {spread(s, 'worstSecondP99', 1)} |"
        )
    return "\n".join(lines)


def resource_table(rows):
    lines = [
        "| Rate | Setup | Server cores | Server CPU-s /1k | GC ms /1k | GC count | Heap max MB | RSS max MB "
        "| Store CPU-s /1k | Kafka CPU-s /1k | All CPU-s /1k | Store ops /1k | Kafka records /1k |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for rate_name, setup, entry in rows:
        s = entry["summary"]
        if "mongoOpsPer1k" in s:
            ops = f"Mongo {med(s, 'mongoOpsPer1k', 0)}"
        else:
            ops = f"PG {med(s, 'pgCommitsPer1k', 0)} commits, {med(s, 'pgTuplesPer1k', 0)} tuples"
        lines.append(
            f"| {rate_name} | {SETUP_NAMES[setup]} | {med(s, 'serverCores', 2)} | {spread(s, 'serverCpuPer1k', 2)} "
            f"| {spread(s, 'gcMsPer1k', 1)} | {med(s, 'gcCount', 0)} | {med(s, 'heapUsedMaxMb', 0)} "
            f"| {med(s, 'serverRssMaxMb', 0)} | {med(s, 'storeCpuPer1k', 2)} | {med(s, 'kafkaCpuPer1k', 2)} "
            f"| {spread(s, 'totalCpuPer1k', 2)} | {ops} | {med(s, 'kafkaRecordsPer1k', 0)} |"
        )
    return "\n".join(lines)


RATE_ORDER = ["9x-50", "9x-80", "v10-50", "v10-80", "pg-50", "pg-80"]
RATE_NAMES = {
    "9x-50": "50% of 9.x sat.",
    "9x-80": "80% of 9.x sat.",
    "v10-50": "50% of V10 sat.",
    "v10-80": "80% of V10 sat.",
    "pg-50": "50% of own sat.",
    "pg-80": "80% of own sat.",
}


def markdown(data):
    out = []
    out.append("### Saturation points (closed loop)\n")
    out.append("| Distribution | 9.x Mongo | V10 Mongo | V10 PostgreSQL |")
    out.append("| --- | --- | --- | --- |")
    for dist in DISTRIBUTIONS:
        row = [dist]
        for setup in SETUPS:
            sat = data.get(setup, {}).get(dist, {}).get("saturation")
            row.append(f"{fmt(sat['throughput'])}/s at c={sat['concurrency']}" if sat else "-")
        out.append("| " + " | ".join(row) + " |")
    for dist in DISTRIBUTIONS:
        pair, pg = [], []
        for label in RATE_ORDER:
            for setup in SETUPS:
                entry = data.get(setup, {}).get(dist, {}).get("rates", {}).get(label)
                if not entry:
                    continue
                name = f"{RATE_NAMES[label]} = {fmt(entry['rate'], 0)}/s"
                (pg if setup == "v10-postgres" else pair).append((name, setup, entry))
        if not pair and not pg:
            continue
        out.append(f"\n### {dist}\n")
        if pair:
            out.append("9.x vs V10, MongoDB + Kafka (median of 3, min–max in brackets):\n")
            out.append(latency_table(pair))
            out.append("\nResource cost per 1,000 completed commands (median of 3):\n")
            out.append(resource_table(pair))
        if pg:
            out.append("\nV10, PostgreSQL + Kafka, on its own (D224):\n")
            out.append(latency_table(pg))
            out.append("")
            out.append(resource_table(pg))
    return "\n".join(out) + "\n"


def main():
    data = collect()
    with open(os.path.join(HERE, "summary.json"), "w") as f:
        json.dump(data, f, indent=1)
        f.write("\n")
    sys.stdout.write(markdown(data))


if __name__ == "__main__":
    main()
