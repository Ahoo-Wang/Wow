"""Per-command store operations of a measure.sh run: the load window minus the idle window scaled to its length."""
import json
import os
import re
import sys


def windows(run):
    text = open(os.path.join(run, "windows.txt")).read()
    return {k: int(v) for k, v in re.findall(r"(\w+)_ms=(\d+)", text)}


def commands(run):
    summary = open(os.path.join(run, "load", "summary.md")).read()
    row = [line for line in summary.splitlines() if line.startswith("| ") and "/s |" in line][0]
    cells = [c.strip() for c in row.strip("|").split("|")]
    return int(cells[1])


def mongo(run):
    w = windows(run)
    scale = w["load"] / w["idle"]
    n = commands(run)

    def rows(name):
        out = {}
        for line in open(os.path.join(run, name)):
            line = line.strip()
            if not line.startswith("{"):
                continue
            r = json.loads(line)
            key = (r["ns"].split(".", 1)[1], r["name"], "txn" if r["txn"] else "")
            out[key] = out.get(key, 0) + r["count"]
        return out

    idle, load = rows("idle-profile.jsonl"), rows("load-profile.jsonl")
    print(f"# {os.path.basename(run)}: {n} commands, idle {w['idle']} ms, load {w['load']} ms")
    print(f"{'collection':36} {'kind':14} {'txn':4} {'load':>7} {'idle/s':>7} {'per cmd':>8}")
    total = 0.0
    for key in sorted(set(idle) | set(load), key=lambda k: -load.get(k, 0)):
        excess = (load.get(key, 0) - idle.get(key, 0) * scale) / n
        total += excess
        print(f"{key[0]:36} {key[1]:14} {key[2]:4} {load.get(key, 0):7} {idle.get(key, 0) * 1000 / w['idle']:7.2f} {excess:8.2f}")
    print(f"{'total (profiled, per cmd, idle removed)':56} {total:8.2f}")
    c = [json.load(open(os.path.join(run, f"counters-{i}.json"))) for i in range(3)]
    print("server command counters per cmd (idle removed):")
    for k in sorted(c[2]["commands"]):
        a, b, d = (c[i]["commands"].get(k, 0) for i in range(3))
        per = ((d - b) - (b - a) * scale) / n
        if abs(per) >= 0.01:
            print(f"  {k:24} {per:6.2f}  (idle {(b - a) * 1000 / w['idle']:.2f}/s)")


def postgres(run):
    w = windows(run)
    scale = w["load"] / w["idle"]
    n = commands(run)

    def statements(name):
        out = {}
        for line in open(os.path.join(run, name)):
            parts = line.rstrip("\n").split("\t", 2)
            if len(parts) == 3:
                out[parts[2]] = out.get(parts[2], 0) + int(parts[0])
        return out

    idle, load = statements("idle-statements.tsv"), statements("load-statements.tsv")
    print(f"# {os.path.basename(run)}: {n} commands, idle {w['idle']} ms, load {w['load']} ms")
    total = 0.0
    for q in sorted(set(idle) | set(load), key=lambda q: -load.get(q, 0)):
        excess = (load.get(q, 0) - idle.get(q, 0) * scale) / n
        if q.startswith("select pg_stat") or "pg_stat_" in q:
            continue
        total += excess
        if load.get(q, 0) or idle.get(q, 0):
            print(f"{excess:7.2f} {load.get(q, 0):6} idle/s={idle.get(q, 0) * 1000 / w['idle']:6.2f}  {q[:170]}")
    print(f"total statements per cmd (idle removed): {total:.2f}")
    for name in ("idle-tables.tsv", "load-tables.tsv", "idle-database.tsv", "load-database.tsv"):
        print("==", name)
        print(open(os.path.join(run, name)).read())


for run in sys.argv[1:]:
    (mongo if os.path.exists(os.path.join(run, "load-profile.jsonl")) else postgres)(run)
    print()
