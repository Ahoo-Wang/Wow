"""The per-commit store figures of 04-mongo-store-cost.sh's sessions, as Markdown.

    python3 -I store-cost.py <out root> [<a>/<b> ...]

Each session r<round>-<arm>/ gives one row: mongod's CPU per commit (its cgroup's `usage_usec` over the window, net of
the 20 s idle floor scaled to the window), the log's insert time per commit (its `latencyStats` writes, which are per
`insertMany` batch, summed and divided by the commits), the snapshot's update latency per operation, the store's
inserts and updates per commit, the log's average document and indexes at the end, and the run's p50 and p99 (the
measured phase's, from result.json) with the load generator's drops. A commit is a log document the window added.
Where a session has index-check.json, the keys each of the log's indexes holds after the run and the last document's
`access` are given too.
Each <a>/<b> names two arms whose ratios are given round by round.
"""
import json
import os
import statistics
import sys


def load(path):
    with open(path) as f:
        text = f.read().strip()
    try:
        return json.loads(text)
    except ValueError:
        return json.loads(text.splitlines()[-1])


def session(d):
    b = load(os.path.join(d, "store-before.json"))
    a = load(os.path.join(d, "store-after.json"))
    idle = load(os.path.join(d, "idle.json"))
    window = a["at"] - b["at"]
    commits = a["log"]["count"] - b["log"]["count"]
    cpu = a["cgroupUsec"] - b["cgroupUsec"]
    floor = idle["usec"] / idle["seconds"] * window

    def spent(coll):
        x, y = b[coll]["latency"]["writes"], a[coll]["latency"]["writes"]
        return y["latency"] - x["latency"], y["ops"] - x["ops"]

    log_us, _ = spent("log")
    snap_us, snap_ops = spent("snapshot")
    result = load(os.path.join(d, "run", "result.json"))["results"]
    check = os.path.join(d, "index-check.json")
    keys = load(check) if os.path.exists(check) else None
    return {
        "check": keys,
        "commits": commits,
        "cpu": (cpu - floor) / commits,
        "idle": idle["usec"] / idle["seconds"] / 1e6,
        "log": log_us / commits,
        "snap": snap_us / snap_ops,
        "inserts": (a["opcounters"]["insert"] - b["opcounters"]["insert"]) / commits,
        "updates": (a["opcounters"]["update"] - b["opcounters"]["update"]) / commits,
        "obj": a["log"]["avgObjSize"],
        "nindexes": a["log"]["nindexes"],
        "index_mb": a["log"]["totalIndexSize"] / 2**20,
        "indexes": {k: v / 2**20 for k, v in a["log"]["indexSizes"].items()},
        "p50": result["latencyMs"]["p50"],
        "p99": result["latencyMs"]["p99"],
        "drops": sum(result["errors"].values()),
    }


def main(root, pairs):
    rows = {}
    for name in sorted(os.listdir(root)):
        d = os.path.join(root, name)
        if name.startswith("r") and "-" in name and ".void" not in name and os.path.isdir(d):
            rows[name] = session(d)
    print("| Session | Commits | mongod CPU µs/commit, net | Idle cores | Log insert µs/commit | Snapshot update µs/op "
          "| Inserts, updates /commit | Log avgObjSize | Log indexes, MB | p50 / p99 ms | Drops |")
    print("| --- | ---: | ---: | ---: | ---: | ---: | --- | ---: | --- | --- | ---: |")
    for name, r in rows.items():
        print(f"| {name} | {r['commits']:,} | {r['cpu']:.1f} | {r['idle']:.3f} | {r['log']:.2f} | {r['snap']:.1f} "
              f"| {r['inserts']:.3f}, {r['updates']:.3f} | {r['obj']} | {r['nindexes']}, {r['index_mb']:.1f} "
              f"| {r['p50']:.2f} / {r['p99']:.1f} | {r['drops']} |")
    print()
    print("| Session | " + " | ".join(sorted({k for r in rows.values() for k in r["indexes"]})) + " |")
    names = sorted({k for r in rows.values() for k in r["indexes"]})
    print("| --- |" + " ---: |" * len(names))
    for name, r in rows.items():
        print(f"| {name} | " + " | ".join(f"{r['indexes'][k]:.1f}" if k in r["indexes"] else "" for k in names) + " |")
    checked = {n: r["check"] for n, r in rows.items() if r["check"]}
    if checked:
        print()
        print("| Session | Log documents | Keys per index | The last document's `access` |")
        print("| --- | ---: | --- | --- |")
        for name, c in checked.items():
            keys = ", ".join(f"{k} {v:,}" for k, v in sorted(c["keysPerIndex"].items()))
            print(f"| {name} | {c['documents']:,} | {keys} | `{json.dumps(c['lastAccess'])}` |")
    for pair in pairs:
        x, y = pair.split("/")
        rounds = sorted({n.split("-", 1)[0] for n in rows if n.split("-", 1)[1] in (x, y)})
        cpu, log = [], []
        for rnd in rounds:
            a, b = rows.get(f"{rnd}-{x}"), rows.get(f"{rnd}-{y}")
            if a and b:
                cpu.append(a["cpu"] / b["cpu"])
                log.append(a["log"] / b["log"])
        if cpu:
            print(f"\n{x} / {y}: CPU " + ", ".join(f"{v:.3f}" for v in cpu) + f" (median {statistics.median(cpu):.3f});"
                  " log insert " + ", ".join(f"{v:.3f}" for v in log) + f" (median {statistics.median(log):.3f})")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("usage: store-cost.py <out root> [<a>/<b> ...]")
    main(sys.argv[1], sys.argv[2:])
