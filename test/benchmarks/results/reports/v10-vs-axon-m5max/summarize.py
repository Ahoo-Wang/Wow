# The industry comparison's tables (v10-vs-axon.md) from its sessions: <root>/<n>-<arm>-postgres/{e1,e3,e1-sweep,e3-sweep}.
#   python3 -I summarize.py <root> [<root> ...]
import json
import os
import sys

ARMS = {
    "W": "Wow, Kafka off",
    "A": "Axon",
    "K": "Wow, Kafka on",
    "A32": "Axon (pool 32)",
    "W32": "Wow, Kafka off (pool 32)",
}


def load(path):
    try:
        with open(path, encoding="utf-8") as file:
            return json.load(file)
    except OSError:
        return None


def sessions(roots):
    for root in roots:
        if not os.path.exists(root):
            continue
        for name in sorted(os.listdir(root)):
            parts = name.split("-")
            if len(parts) >= 2 and parts[1] in ARMS:
                yield parts[1], f"{os.path.basename(root)}/{name}", os.path.join(root, name)


def fixed(session, phase):
    result = load(os.path.join(session, phase, "result.json"))
    if result is None:
        return "| (none) |" + " |" * 7
    r, res = result["results"], result["resources"]
    ops = r["ok"] or 1
    node = res["processes"]["node-1"]["cpuSeconds"] * 1000 / ops
    pg = res["containers"]["wow-e2e-bench-postgres"]["cpuPercentAverage"] / 100 * res["seconds"] * 1000 / ops
    commits = res["stores"]["postgres"]["delta"].get("xact_commit", 0) / ops
    lat = r["latencyMs"]
    return (f"| {r['throughput']:,.0f}/s | {r['failed']} | {lat['p50']:.2f} ms | {lat['p99']:.1f} ms | "
            f"{lat['p999']:.1f} ms | {node:.3f} ms | {pg:.3f} ms | {commits:.2f} |")


def sweep(session, phase):
    result = load(os.path.join(session, phase, "saturation.json"))
    if result is None:
        return "(none)"
    steps = []
    for step in result["steps"]:
        errors = sum(step["errors"].values())
        steps.append(f"{step['concurrency']}: {step['throughput']:,.0f}/s"
                     + (f" ({errors} errors)" if errors else f", p99 {step['p99Ms']:.0f} ms"))
    return "; ".join(steps)


def main(roots):
    found = list(sessions(roots))
    header = "| Session | Arm | Throughput | Failed | p50 | p99 | p999 | Node CPU/op | PostgreSQL CPU/op | Commits/op |"
    rule = "| --- " * 10 + "|"
    for phase, title in (("e1", "E1 at 2,500/s"), ("e3", "E3 at 700/s")):
        print(f"\n### {title}\n\n{header}\n{rule}")
        for arm, name, path in found:
            arm_label = ARMS[arm]
            if "pool-32" in name and not ("pool 32" in arm_label):
                arm_label += " (pool 32)"
            print(f"| {name} | {arm_label} {fixed(path, phase)}")
    for phase, title in (("e1-sweep", "E1 closed loop"), ("e3-sweep", "E3 closed loop")):
        print(f"\n### {title}\n")
        for arm, name, path in found:
            arm_label = ARMS[arm]
            if "pool-32" in name and not ("pool 32" in arm_label):
                arm_label += " (pool 32)"
            print(f"- {name} ({arm_label}): {sweep(path, phase)}")


if __name__ == "__main__":
    main(sys.argv[1:])
