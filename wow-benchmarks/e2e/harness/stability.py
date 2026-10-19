#!/usr/bin/env python3
"""Whether a shorter warm-up and measured phase would give the same results (decision D224's measurement tiers).

    python3 -I stability.py <report folder> [--threshold=5]

Reads every open-loop result.json under the folder that was run with a 30 s warm-up and a 60 s measured phase and
recorded its warm-up's per-second latencies (the load generator's `warmUpPerSecond`). On the run's timeline (second 0
is the first of the warm-up), it compares the standard window, seconds 30 to 89, with the shortened one, seconds 20
to 64 (20 s warm-up, 45 s measured): the throughput, the median of the per-second p50 and the median of the
per-second p99. The shortening is accepted when every run's three differ by at most the threshold (5%), and at least
four runs were checked. The verdict goes to <report folder>/stability.json, which tiers.sh --timing=auto reads.
Standard library only.
"""
import json
import os
import statistics
import sys

LONG = (30, 90)
SHORT = (20, 65)
MIN_RUNS = 4


def timeline(result):
    r = result["results"]
    warm = result["load"]["warmUpSeconds"]
    seconds = {}
    for s in r.get("warmUpPerSecond", []):
        seconds[s["second"]] = s
    for s in r["perSecond"]:
        seconds[warm + s["second"]] = s
    return seconds


def window(seconds, bounds):
    picked = [seconds[t] for t in range(*bounds) if t in seconds]
    if len(picked) < (bounds[1] - bounds[0]) * 0.9:
        return None
    return {
        "throughput": sum(s["ok"] for s in picked) / (bounds[1] - bounds[0]),
        "p50": statistics.median(s["p50Ms"] for s in picked),
        "p99": statistics.median(s["p99Ms"] for s in picked),
    }


def main():
    folder = sys.argv[1]
    threshold = 5.0
    for argument in sys.argv[2:]:
        if argument.startswith("--threshold="):
            threshold = float(argument.split("=", 1)[1])
    runs = []
    for root, _, files in os.walk(folder):
        if "result.json" not in files:
            continue
        result = json.load(open(os.path.join(root, "result.json")))
        load = result.get("load", {})
        if load.get("warmUpSeconds") != 30 or load.get("measureSeconds") != 60:
            continue
        seconds = timeline(result)
        long_, short = window(seconds, LONG), window(seconds, SHORT)
        if long_ is None or short is None:
            continue
        diff = {k: round(abs(short[k] - long_[k]) / long_[k] * 100, 2) if long_[k] else None for k in long_}
        runs.append({"run": os.path.relpath(root, folder), "long": long_, "short": short, "differencePercent": diff,
                     "within": all(v is not None and v <= threshold for v in diff.values())})
    accepted = len(runs) >= MIN_RUNS and all(r["within"] for r in runs)
    verdict = {
        "schema": "wow-e2e-bench/stability/1",
        "thresholdPercent": threshold,
        "long": "warm-up 30 s, measured 60 s (seconds 30-89)",
        "short": "warm-up 20 s, measured 45 s (seconds 20-64)",
        "runsChecked": len(runs),
        "shortenAccepted": accepted,
        "runs": runs,
    }
    with open(os.path.join(folder, "stability.json"), "w") as f:
        json.dump(verdict, f, indent=1)
        f.write("\n")
    worst = max((max(v for v in r["differencePercent"].values() if v is not None) for r in runs), default=None)
    print(f"[stability] {folder}: {len(runs)} runs checked, worst difference {worst}%, "
          f"shortening {'accepted' if accepted else 'not accepted'}")


if __name__ == "__main__":
    main()
