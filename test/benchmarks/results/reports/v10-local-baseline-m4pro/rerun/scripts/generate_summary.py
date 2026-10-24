#!/usr/bin/env python3
import json, glob, os, sys

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..")

md = []
md.append("# Rerun Benchmark Summary (Apple M4 Pro 14 Cores)")
md.append("")
md.append("## Environment")
md.append("| Property | Value |")
md.append("| :--- | :--- |")
md.append("| Machine | MacBook Pro, Apple M4 Pro (14 cores: 10P + 4E), 24 GB RAM |")
md.append("| OS | macOS 27.0.1 (Build 26A434) |")
md.append("| Docker Desktop | 4.96.3 (244472), Engine: 29.9.2 |")
md.append("| Docker Resources | 12 CPUs, 15.6 GiB (~16 GB) RAM |")
md.append("| Disk Image & Virtualization | `Docker.raw` (ext4 with discard on /dev/vda1), VirtioFS file sharing |")
md.append("| JDK | OpenJDK 21.0.13 (Temurin-21.0.13+9-LTS) |")
md.append("| Accounts | 100,000 primed accounts, no JFR |")
md.append("")

def get_res(d):
    if not os.path.exists(d + "/result.json"): return None
    r = json.load(open(d + "/result.json"))
    res = r["results"]
    ops = res["ok"]
    failed = res.get("failed", 0)
    fails = failed if isinstance(failed, int) else sum(failed.values())
    fail_str = str(fails)
    if fails > 0 and "errors" in res:
        err_keys = list(res["errors"].keys())
        if len(err_keys) == 1:
            err_name = err_keys[0].split("-")[-1]
            fail_str = f"{fails} ({err_name})"
        else:
            fail_str = f"{fails}"
    p50, p99, mx = res["latencyMs"]["p50"], res["latencyMs"]["p99"], res["latencyMs"]["max"]
    n1 = r["resources"]["processes"]["node-1"]
    srv_cpu = n1["cpuSeconds"] * 1000 / ops if ops else 0
    alloc = n1["allocatedBytes"] / ops / 1024 if ops else 0
    gc_cnt = n1.get("gcCountDelta", 0)
    gc_sec = n1.get("gcSecondsDelta", 0)
    
    # Check max WAL flush time from summary.md
    wal_max = 0
    sum_file = d + "/summary.md"
    if os.path.exists(sum_file):
        for line in open(sum_file):
            if "WAL flush time" in line and "(max" in line:
                try:
                    p = line.split("(max")[1].split("ms)")[0].strip()
                    wal_max = float(p)
                except: pass
    # Check mongo checkpoint
    chk_max = 0
    after_file = d + "/mongo-stats-after.json"
    if os.path.exists(after_file):
        try:
            m_txt = open(after_file).read()
            import re
            m = re.search(r"checkpoint_max_ms:\s*(\d+)", m_txt)
            if m:
                chk_max = float(m.group(1))
        except: pass

    return dict(thr=res["throughput"], ok=ops, fails=fail_str, p50=p50, p99=p99, max=mx,
                cpu=srv_cpu, alloc=alloc, gc_cnt=gc_cnt, gc_sec=gc_sec, wal_max=wal_max, chk_max=chk_max)

# Stage 0
md.append("## Stage 0: A/A Noise (PostgreSQL E1)")
md.append("| Session, Run | Throughput | OK | Failed | p50 (ms) | p99 (ms) | Max (ms) | Server CPU (ms/op) | Alloc (KB/op) | WAL Max Flush Time (ms) |")
md.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |")
for s in [1, 2, 3]:
    for r in [1, 2]:
        d = f"{OUT}/s0-{s}-{r}"
        info = get_res(d)
        if info:
            wal_str = f"**{info['wal_max']:.0f}**" if info['wal_max'] > 500 else f"{info['wal_max']:.0f}"
            md.append(f"| {s}, {r} | {info['thr']:.1f}/s | {info['ok']} | {info['fails']} | {info['p50']:.2f} | {info['p99']:.2f} | {info['max']:.1f} | {info['cpu']:.3f} | {info['alloc']:.1f} | {wal_str} |")
md.append("")

# Stage 1
md.append("## Stage 1: Cost and Saturation")
md.append("### Closed-Loop Sweeps Peak")
md.append("| Scenario | Sweep Max Concurrency | Peak Throughput | Concurrency at Peak | Mark |")
md.append("| :--- | :--- | :--- | :--- | :--- |")
for bk in ["postgres", "mongo"]:
    for sc, dist, mc in [("E1", "uniform", 1024), ("E1", "zipf", 512), ("E3", "uniform", 512)]:
        sp = f"{OUT}/s1-{bk}-{sc}-{dist}-sweep/saturation.json"
        if os.path.exists(sp):
            sat = json.load(open(sp))
            thr = sat["saturationThroughput"]
            c_peak = sat.get("saturationConcurrency", 0)
            mark = "≥" if c_peak >= mc else ""
            md.append(f"| {bk} {sc} {dist} | {mc} | {thr:.1f}/s | {c_peak} | {mark} |")
md.append("")

md.append("### Open-Loop Measurements")
md.append("| Scenario | Share | Target Rate | Throughput | OK | Failed | p50 (ms) | p99 (ms) | Max (ms) | Server CPU (ms/op) | Store Max Latency (ms) |")
md.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |")
for bk in ["postgres", "mongo"]:
    for sc, dist in [("E1", "uniform"), ("E1", "zipf"), ("E3", "uniform")]:
        for sh in ["0.25", "0.5", "0.75"]:
            d = f"{OUT}/s1-{bk}-{sc}-{dist}-{sh}"
            info = get_res(d)
            if info:
                rate = json.load(open(d + "/result.json"))["load"]["rate"]
                store_lat = f"WAL: {info['wal_max']:.0f}" if bk == "postgres" else f"Chk: {info['chk_max']:.0f}"
                if bk == "postgres" and info['wal_max'] > 500: store_lat = f"**WAL: {info['wal_max']:.0f} (exceed 500ms)**"
                md.append(f"| {bk} {sc} {dist} | {sh} | {rate:.0f}/s | {info['thr']:.1f}/s | {info['ok']} | {info['fails']} | {info['p50']:.2f} | {info['p99']:.2f} | {info['max']:.1f} | {info['cpu']:.3f} | {store_lat} |")
md.append("")

# Stage 2 ABAB
md.append("## Stage 2: ABAB 1 GB vs 2 GB Heap Comparison")
md.append("### ABAB Sweeps and Constant Rate Runs")
md.append("| Backend | Session | E1 Peak | E3 Peak | E1 Run (p50 / p99) | E3 Run (p50 / p99) | E1 Fails | E3 Fails | Store Max (WAL/Chk ms) |")
md.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |")
for bk in ["postgres", "mongo"]:
    for sess in ["1g-1", "2g-1", "1g-2", "2g-2"]:
        e1_sp = f"{OUT}/abab-{bk}-{sess}-E1-sweep/saturation.json"
        e3_sp = f"{OUT}/abab-{bk}-{sess}-E3-sweep/saturation.json"
        p_e1 = json.load(open(e1_sp))["saturationThroughput"] if os.path.exists(e1_sp) else 0
        p_e3 = json.load(open(e3_sp))["saturationThroughput"] if os.path.exists(e3_sp) else 0
        
        e1_run = get_res(f"{OUT}/abab-{bk}-{sess}-E1-run")
        e3_run = get_res(f"{OUT}/abab-{bk}-{sess}-E3-run")
        
        e1_str = f"{e1_run['p50']:.2f} / {e1_run['p99']:.2f} ms" if e1_run else "n/a"
        e3_str = f"{e3_run['p50']:.2f} / {e3_run['p99']:.2f} ms" if e3_run else "n/a"
        e1_f = e1_run["fails"] if e1_run else "0"
        e3_f = e3_run["fails"] if e3_run else "0"
        
        st_max = ""
        if bk == "postgres" and e1_run:
            st_max = f"WAL: {max(e1_run['wal_max'], e3_run['wal_max'] if e3_run else 0):.0f}"
        elif bk == "mongo" and e1_run:
            st_max = f"Chk: {max(e1_run['chk_max'], e3_run['chk_max'] if e3_run else 0):.0f}"

        md.append(f"| {bk} | {sess} | {p_e1:.1f}/s | {p_e3:.1f}/s | {e1_str} | {e3_str} | {e1_f} | {e3_f} | {st_max} |")
md.append("")

md.append("### ABAB 2g / 1g Ratios")
md.append("| Backend | Round | Metric | 1g Value | 2g Value | Ratio (2g / 1g) |")
md.append("| :--- | :--- | :--- | :--- | :--- | :--- |")
for bk in ["postgres", "mongo"]:
    for rnd, s1, s2 in [(1, "1g-1", "2g-1"), (2, "1g-2", "2g-2")]:
        e1_sp1_f = f"{OUT}/abab-{bk}-{s1}-E1-sweep/saturation.json"
        e1_sp2_f = f"{OUT}/abab-{bk}-{s2}-E1-sweep/saturation.json"
        e3_sp1_f = f"{OUT}/abab-{bk}-{s1}-E3-sweep/saturation.json"
        e3_sp2_f = f"{OUT}/abab-{bk}-{s2}-E3-sweep/saturation.json"
        if not (os.path.exists(e1_sp1_f) and os.path.exists(e1_sp2_f) and os.path.exists(e3_sp1_f) and os.path.exists(e3_sp2_f)):
            continue
        e1_sp1 = json.load(open(e1_sp1_f))["saturationThroughput"]
        e1_sp2 = json.load(open(e1_sp2_f))["saturationThroughput"]
        e3_sp1 = json.load(open(e3_sp1_f))["saturationThroughput"]
        e3_sp2 = json.load(open(e3_sp2_f))["saturationThroughput"]
        
        e1_r1 = get_res(f"{OUT}/abab-{bk}-{s1}-E1-run")
        e1_r2 = get_res(f"{OUT}/abab-{bk}-{s2}-E1-run")
        e3_r1 = get_res(f"{OUT}/abab-{bk}-{s1}-E3-run")
        e3_r2 = get_res(f"{OUT}/abab-{bk}-{s2}-E3-run")

        md.append(f"| {bk} | Round {rnd} | E1 Sweep Peak | {e1_sp1:.1f}/s | {e1_sp2:.1f}/s | {e1_sp2/e1_sp1:.3f} |")
        md.append(f"| {bk} | Round {rnd} | E3 Sweep Peak | {e3_sp1:.1f}/s | {e3_sp2:.1f}/s | {e3_sp2/e3_sp1:.3f} |")
        if e1_r1 and e1_r2:
            md.append(f"| {bk} | Round {rnd} | E1 Run Throughput | {e1_r1['thr']:.1f}/s | {e1_r2['thr']:.1f}/s | {e1_r2['thr']/e1_r1['thr']:.3f} |")
            md.append(f"| {bk} | Round {rnd} | E1 Run p50 | {e1_r1['p50']:.2f} ms | {e1_r2['p50']:.2f} ms | {e1_r2['p50']/e1_r1['p50']:.3f} |")
            md.append(f"| {bk} | Round {rnd} | E1 Run p99 | {e1_r1['p99']:.2f} ms | {e1_r2['p99']:.2f} ms | {e1_r2['p99']/e1_r1['p99']:.3f} |")
        if e3_r1 and e3_r2:
            md.append(f"| {bk} | Round {rnd} | E3 Run Throughput | {e3_r1['thr']:.1f}/s | {e3_r2['thr']:.1f}/s | {e3_r2['thr']/e3_r1['thr']:.3f} |")
            md.append(f"| {bk} | Round {rnd} | E3 Run p50 | {e3_r1['p50']:.2f} ms | {e3_r2['p50']:.2f} ms | {e3_r2['p50']/e3_r1['p50']:.3f} |")
            md.append(f"| {bk} | Round {rnd} | E3 Run p99 | {e3_r1['p99']:.2f} ms | {e3_r2['p99']:.2f} ms | {e3_r2['p99']/e3_r1['p99']:.3f} |")
md.append("")

# Stage 3 L1
md.append("## Stage 3: L1 Component Scaling")
md.append("| Backend | Scenario | Concurrency | Throughput | Round Trips / op | Alloc (KB/op) | p50 (ms) | p99 (ms) |")
md.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |")
for bk in ["postgres", "mongo"]:
    for sc in ["E1", "E3"]:
        for c in [16, 64, 256]:
            d = f"{OUT}/l1-{bk}-{sc}-{c}/component.json"
            if os.path.exists(d):
                data = json.load(open(d))
                thr = data.get("throughput", 0)
                rt = data.get("storeRoundTripsPerOp", 0)
                alloc = data.get("allocatedBytesPerOp", 0) / 1024
                p50 = data.get("latencyMs", {}).get("p50", 0)
                p99 = data.get("latencyMs", {}).get("p99", 0)
                md.append(f"| {bk} | {sc} | {c} | {thr:.1f}/s | {rt:.2f} | {alloc:.1f} | {p50:.2f} | {p99:.2f} |")

open(OUT + "/SUMMARY.md", "w").write("\n".join(md) + "\n")
print("SUMMARY.md generated successfully at " + OUT + "/SUMMARY.md")
