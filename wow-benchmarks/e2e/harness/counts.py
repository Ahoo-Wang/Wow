#!/usr/bin/env python3
"""The tables of counts.sh (decision D224's count-based tier; PS-2 of D240). Standard library only.

    python3 -I counts.py scenarios <out> [<json> <md>]   # per-operation counts of E1 to E3, per setup
    python3 -I counts.py ps2 <out> [<json> <md>]         # per-partition idle and trickle cost, per P and subscriptions

A window's count of a kind, less the idle window's rate of that kind over the window's length, divided by the window's
completed operations, is that kind's count per operation (B.D's method). Round trips are what the store answered
(MongoDB's serverStatus command counters, PostgreSQL's statements); operations are what the store did (MongoDB's
profiled operations, PostgreSQL's statements), so a batched write is one round trip and many operations.
"""
import json
import os
import re
import sys

# The MongoDB commands that carry data; the drivers' monitoring (hello, isMaster, ...) and the harness's own
# (serverStatus, mongosh's start-up) are left out.
MONGO_DATA = ["find", "insert", "update", "delete", "findAndModify", "aggregate", "getMore", "count", "distinct",
              "commitTransaction", "abortTransaction", "bulkWrite"]
KINDS = ["request-id check", "aggregate load", "log append", "snapshot", "state read", "tail", "checkpoint", "lease",
         "other periodic", "other"]


def mongo_kind(version, collection, name, txn, shape):
    """The kind of a profiled MongoDB operation of the given version, from its collection and shape."""
    if version == "9x":
        if collection.endswith("_event_stream"):
            return "log append" if name == "insert" else "aggregate load"
        if collection.endswith("_snapshot"):
            if name == "update":
                return "snapshot"
            # Loading an aggregate reads its snapshot by id; the snapshot read of E2 queries by aggregate and tenant.
            return "aggregate load" if shape == "_id" else "state read"
        return "other"
    if collection == "wow_checkpoint":
        return "checkpoint"
    if collection in ("wow_lease", "wow_lease_member"):
        return "lease"
    if collection.startswith("wow_log__") and collection.endswith("__partition"):
        if name == "findAndModify":
            return "log append"
        if name == "aggregate":
            return "tail"
        return "other periodic"
    if collection.startswith("wow_log__"):
        if name == "insert" or txn:
            return "log append"
        if name == "query":
            return "request-id check"
        return "other"
    if collection.startswith("wow_snapshot__"):
        return "state read" if name == "query" else "snapshot"
    if collection.startswith(("wow_timer", "wow_failure_ledger", "wow_snapshot_table")):
        return "other periodic"
    return "other"


def pg_kind(query):
    """The kind of a PostgreSQL statement, from its text; None for the harness's own."""
    q = query.lower()
    if "pg_stat" in q or "pg_database" in q:
        return None
    if "wow_checkpoint" in q:
        return "checkpoint"
    if "wow_lease" in q:
        return "lease"
    if q.startswith("with current as") and "wow_log__" in q:
        return "log append"
    if "left join lateral" in q and "__partition" in q:
        return "tail"
    if "wow_log__" in q and "request_id = " in q:
        return "request-id check"
    if "wow_snapshot__" in q:
        return "snapshot" if q.startswith("insert") or q.startswith("update") else "state read"
    if re.search(r"wow_timer|wow_failure_ledger|wow_snapshot_table|__partition", q):
        return "other periodic"
    return "other"


def read_windows(run):
    out = {}
    for line in open(os.path.join(run, "windows.txt")):
        name, start, end = line.split()
        out[name] = (int(end) - int(start)) / 1000.0
    return out


def config(run):
    values = {}
    for line in open(os.path.join(run, "config.txt")):
        for part in line.strip().split(" ") if line.startswith(("rate=", "partitions=")) else [line.strip()]:
            if "=" in part:
                key, value = part.split("=", 1)
                values[key] = value
    return values


def completed(run, window):
    path = os.path.join(run, f"{window}-load", "result.json")
    if not os.path.exists(path):
        return None
    results = json.load(open(path))["results"]
    return {"ok": results["ok"], "failed": results["failed"], "errors": results["errors"]}


def mongo_counts(run, window, version):
    """(round trips by command, operations by kind, round trips by kind) of a MongoDB window."""
    a = json.load(open(os.path.join(run, f"{window}-counters-0.json")))["commands"]
    b = json.load(open(os.path.join(run, f"{window}-counters-1.json")))["commands"]
    commands = {k: b.get(k, 0) - a.get(k, 0) for k in MONGO_DATA if b.get(k, 0) - a.get(k, 0)}
    operations, by_name = {}, {}
    path = os.path.join(run, f"{window}-profile.jsonl")
    for line in open(path) if os.path.exists(path) else []:
        line = line.strip()
        if not line.startswith("{"):
            continue
        r = json.loads(line)
        collection = r["ns"].split(".", 1)[1]
        kind = mongo_kind(version, collection, r["name"], r["txn"], r["shape"])
        operations[kind] = operations.get(kind, 0) + r["count"]
        command = {"query": "find", "insert": "insert", "update": "update"}.get(r["name"], r["name"])
        by_name.setdefault(command, {})
        by_name[command][kind] = by_name[command].get(kind, 0) + r["count"]
    # Round trips by kind: an operation is a round trip, except the writes a bulk command batches (the checkpoints of
    # a tick): their round trips are the command's count less the other kinds' operations of that command.
    trips = {}
    for command, kinds in by_name.items():
        bulk = {k: v for k, v in kinds.items() if k == "checkpoint"}
        single = {k: v for k, v in kinds.items() if k != "checkpoint"}
        for kind, count in single.items():
            trips[kind] = trips.get(kind, 0) + count
        if bulk:
            trips["checkpoint"] = trips.get("checkpoint", 0) + max(0, commands.get(command, 0) - sum(single.values()))
    # The transaction's commit is a round trip of the append (the admin database is not profiled).
    if commands.get("commitTransaction"):
        trips["log append"] = trips.get("log append", 0) + commands["commitTransaction"]
        operations["log append"] = operations.get("log append", 0) + commands["commitTransaction"]
    return commands, operations, trips


def pg_counts(run, window, seconds):
    """(statements by text, operations by kind, round trips by kind) of a PostgreSQL window."""
    statements, operations = {}, {}
    for line in open(os.path.join(run, f"{window}-statements.tsv")):
        parts = line.rstrip("\n").split("\t", 2)
        if len(parts) != 3:
            continue
        kind = pg_kind(parts[2])
        if kind is None:
            continue
        statements[parts[2][:120]] = statements.get(parts[2][:120], 0) + int(parts[0])
        operations[kind] = operations.get(kind, 0) + int(parts[0])
    trips = dict(operations)
    # A tick's checkpoints go in one JDBC batch: at most one round trip a tick (1 s), whatever the statements.
    if "checkpoint" in trips:
        trips["checkpoint"] = min(trips["checkpoint"], round(seconds))
    xact = [int(open(os.path.join(run, f"{window}-xact-{i}.txt")).read().strip() or 0) for i in (0, 1)]
    return statements, operations, trips, xact[1] - xact[0]


def kafka(run, window):
    def offsets(i):
        total = {}
        path = os.path.join(run, f"{window}-kafka-{i}.txt")
        for line in open(path) if os.path.exists(path) else []:
            parts = line.strip().rsplit(":", 2)
            if len(parts) == 3 and parts[2].isdigit():
                total[parts[0]] = total.get(parts[0], 0) + int(parts[2])
        return total
    a, b = offsets(0), offsets(1)
    return {topic: b[topic] - a.get(topic, 0) for topic in b if b[topic] - a.get(topic, 0)}


def alloc(run, window):
    try:
        a = json.load(open(os.path.join(run, f"{window}-alloc-0.json")))
        b = json.load(open(os.path.join(run, f"{window}-alloc-1.json")))
        return sum(b[k] - a[k] for k in b if a.get(k) is not None and b[k] is not None)
    except (OSError, ValueError):
        return None


def window_counts(run, window, version, store, seconds):
    if store == "mongo":
        commands, operations, trips = mongo_counts(run, window, version)
        extra = {"commands": commands}
    else:
        statements, operations, trips, xact = pg_counts(run, window, seconds)
        extra = {"transactions": xact}
    return {"seconds": seconds, "operations": operations, "roundTrips": trips, "kafka": kafka(run, window),
            "allocatedBytes": alloc(run, window), **extra}


def rate(counts):
    s = counts["seconds"]
    return {
        "roundTrips": {k: v / s for k, v in counts["roundTrips"].items()},
        "operations": {k: v / s for k, v in counts["operations"].items()},
        "kafka": {k: v / s for k, v in counts["kafka"].items()},
        "allocatedBytes": counts["allocatedBytes"] / s if counts["allocatedBytes"] is not None else None,
    }


def per_operation(load, idle, n):
    s = load["seconds"]
    idle_rate = rate(idle)

    def excess(field):
        keys = set(load[field]) | set(idle_rate[field])
        return {k: round((load[field].get(k, 0) - idle_rate[field].get(k, 0) * s) / n, 3) for k in keys
                if abs(load[field].get(k, 0) - idle_rate[field].get(k, 0) * s) / n >= 0.005}

    out = {"roundTrips": excess("roundTrips"), "operations": excess("operations"), "kafka": excess("kafka")}
    out["roundTripsTotal"] = round(sum(out["roundTrips"].values()), 2)
    out["operationsTotal"] = round(sum(out["operations"].values()), 2)
    out["kafkaTotal"] = round(sum(out["kafka"].values()), 2)
    if load["allocatedBytes"] is not None and idle_rate["allocatedBytes"] is not None:
        out["allocatedKiB"] = round((load["allocatedBytes"] - idle_rate["allocatedBytes"] * s) / n / 1024, 1)
    return out


def scenarios(out):
    report = {}
    for setup in sorted(os.listdir(out)):
        run = os.path.join(out, setup)
        if not os.path.exists(os.path.join(run, "windows.txt")):
            continue
        cfg = config(run)
        version, store = cfg["version"], "mongo" if cfg["backend"] == "mongo" else "postgres"
        windows = read_windows(run)
        idle = window_counts(run, "idle", version, store, windows["idle"])
        entry = {"config": cfg, "idlePerSecond": {k: {kk: round(vv, 2) for kk, vv in v.items()} if isinstance(v, dict)
                                                  else v for k, v in rate(idle).items()}, "scenarios": {}}
        for window, seconds in windows.items():
            if window == "idle":
                continue
            done = completed(run, window)
            load = window_counts(run, window, version, store, seconds)
            entry["scenarios"][window] = {"completed": done, "window": load,
                                          "perOperation": per_operation(load, idle, max(1, done["ok"]))}
        report[setup] = entry
    return report


def ps2(out):
    report = {}
    for backend in sorted(os.listdir(out)):
        root = os.path.join(out, backend)
        if not os.path.isdir(root):
            continue
        for name in sorted(os.listdir(root), key=lambda n: [int(x) for x in re.findall(r"\d+", n)]):
            run = os.path.join(root, name)
            if not os.path.exists(os.path.join(run, "windows.txt")):
                continue
            cfg = config(run)
            p, s = int(cfg["partitions"]), int(cfg["extraSubscriptions"])
            windows = read_windows(run)
            store = "mongo" if backend == "mongo" else "postgres"
            idle = window_counts(run, "idle", "v10", store, windows["idle"])
            idle_rate = rate(idle)
            entry = {
                "backend": backend, "partitions": p, "extraSubscriptions": s,
                "subscriptions": [x for x in open(os.path.join(run, "subscriptions.txt")).read().split() if x]
                if os.path.exists(os.path.join(run, "subscriptions.txt")) else None,
                "idle": {
                    "seconds": windows["idle"],
                    "roundTripsPerSecond": {k: round(v, 2) for k, v in idle_rate["roundTrips"].items()},
                    "roundTripsPerSecondTotal": round(sum(idle_rate["roundTrips"].values()), 2),
                    "roundTripsPerSecondPerPartition": round(sum(idle_rate["roundTrips"].values()) / p, 3),
                    "operationsPerSecond": {k: round(v, 2) for k, v in idle_rate["operations"].items()},
                    "allocatedMiBPerSecond": round(idle_rate["allocatedBytes"] / 2 ** 20, 2)
                    if idle_rate["allocatedBytes"] is not None else None,
                },
            }
            trickle = windows.get("E1-trickle")
            if trickle:
                load = window_counts(run, "E1-trickle", "v10", store, trickle)
                done = completed(run, "E1-trickle")
                lr = rate(load)
                entry["trickle"] = {
                    "seconds": trickle, "completed": done,
                    "roundTripsPerSecond": {k: round(v, 2) for k, v in lr["roundTrips"].items()},
                    "operationsPerSecond": {k: round(v, 2) for k, v in lr["operations"].items()},
                    "checkpointWritesPerSecond": round(lr["operations"].get("checkpoint", 0), 2),
                    "perCommand": per_operation(load, idle, max(1, done["ok"])),
                }
            report.setdefault(backend, []).append(entry)
    return report


def fmt(value):
    return "" if value is None else (f"{value:.2f}" if isinstance(value, float) else str(value))


def scenarios_md(report):
    lines = []
    setups = list(report)
    names = {"9x-mongo": "9.x MongoDB", "v10-mongo": "V10 MongoDB", "v10-postgres": "V10 PostgreSQL"}
    windows = sorted({w for s in setups for w in report[s]["scenarios"]})
    for window in windows:
        lines += [f"### {window}", "", "| Kind | " + " | ".join(names.get(s, s) for s in setups) + " |",
                  "| --- |" + " ---: |" * len(setups)]
        for kind in KINDS:
            cells = [report[s]["scenarios"].get(window, {}).get("perOperation", {}).get("roundTrips", {}).get(kind)
                     for s in setups]
            if any(c for c in cells):
                lines.append(f"| {kind} | " + " | ".join(fmt(c) for c in cells) + " |")
        for label, key in [("**Round trips**", "roundTripsTotal"), ("Store operations", "operationsTotal"),
                           ("Kafka messages", "kafkaTotal"), ("Allocated KiB", "allocatedKiB")]:
            cells = [report[s]["scenarios"].get(window, {}).get("perOperation", {}).get(key) for s in setups]
            lines.append(f"| {label} | " + " | ".join(fmt(c) for c in cells) + " |")
        lines.append("")
    return "\n".join(lines)


def ps2_md(report):
    lines = []
    for backend, entries in report.items():
        lines += [f"### {backend}", "",
                  "| P | Extra subscriptions | Idle round trips/s | per partition | lease | tail | checkpoint | other "
                  "periodic | Idle alloc MiB/s | Trickle checkpoint writes/s | Trickle round trips/s |",
                  "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
        for e in entries:
            idle = e["idle"]["roundTripsPerSecond"]
            t = e.get("trickle", {})
            lines.append("| " + " | ".join(fmt(x) for x in [
                e["partitions"], e["extraSubscriptions"], e["idle"]["roundTripsPerSecondTotal"],
                e["idle"]["roundTripsPerSecondPerPartition"], idle.get("lease", 0.0), idle.get("tail", 0.0),
                idle.get("checkpoint", 0.0), idle.get("other periodic", 0.0), e["idle"]["allocatedMiBPerSecond"],
                t.get("checkpointWritesPerSecond"),
                round(sum(t.get("roundTripsPerSecond", {}).values()), 2) if t else None]) + " |")
        lines.append("")
    return "\n".join(lines)


def main():
    mode, out = sys.argv[1], sys.argv[2]
    report = scenarios(out) if mode == "scenarios" else ps2(out)
    md = scenarios_md(report) if mode == "scenarios" else ps2_md(report)
    if len(sys.argv) > 4:
        with open(sys.argv[3], "w") as f:
            json.dump(report, f, indent=1, sort_keys=True)
            f.write("\n")
        with open(sys.argv[4], "w") as f:
            f.write(md + "\n")
    print(md)


if __name__ == "__main__":
    main()
