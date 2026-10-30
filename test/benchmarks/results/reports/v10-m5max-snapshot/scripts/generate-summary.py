#!/usr/bin/env python3
import os
import sys
import json
import re

def parse_run(result_path, summary_path=None, before_mongo=None, after_mongo=None):
    if not os.path.exists(result_path):
        return None
    with open(result_path) as f:
        data = json.load(f)

    results = data.get("results", {})
    resources = data.get("resources", {})
    processes = resources.get("processes", {})
    node = processes.get("node-1", {})
    gc = node.get("gc", {}).get("delta", {})

    errors = results.get("errors", {})
    failed = results.get("failed", 0)

    # WAL sync from summary.md
    wal_avg = None
    wal_max = None
    if summary_path and os.path.exists(summary_path):
        with open(summary_path) as f:
            content = f.read()
            m = re.search(r"WAL flush time\s+([\d\.]+)\s+ms\s+\(max\s+([\d\.]+)\s+ms\)", content)
            if m:
                wal_avg = float(m.group(1))
                wal_max = float(m.group(2))

    # Mongo stats
    mongo_before = None
    mongo_after = None
    if before_mongo and os.path.exists(before_mongo):
        with open(before_mongo) as f:
            try: mongo_before = json.load(f)
            except: pass
    if after_mongo and os.path.exists(after_mongo):
        with open(after_mongo) as f:
            try: mongo_after = json.load(f)
            except: pass

    containers = resources.get("containers", {})
    kafka_c = None
    db_c = None
    for cname, cval in containers.items():
        if "kafka" in cname:
            kafka_c = cval.get("cpuPercentAverage")
        elif "postgres" in cname or "mongo" in cname:
            db_c = cval.get("cpuPercentAverage")

    ok = results.get("ok", 0)
    cpu_sec = node.get("cpuSeconds")
    node_cpu_ms = (cpu_sec * 1000 / ok) if (cpu_sec is not None and ok > 0) else None

    alloc_b = node.get("allocatedBytes")
    alloc_kb = (alloc_b / 1024 / ok) if (alloc_b is not None and ok > 0) else None

    ygc = gc.get("YGC") or 0
    fgc = gc.get("FGC") or 0
    gct = gc.get("GCT") or 0.0

    # Store counters per op
    stores = resources.get("stores", {})
    tup_fetched_per_op = None
    blks_read_per_op = None
    query_per_op = None

    pg_store = stores.get("postgres", {})
    if pg_store.get("before") and pg_store.get("after") and ok > 0:
        if "tup_fetched" in pg_store["after"] and "tup_fetched" in pg_store["before"]:
            tup_fetched_per_op = (pg_store["after"]["tup_fetched"] - pg_store["before"]["tup_fetched"]) / ok
        if "blks_read" in pg_store["after"] and "blks_read" in pg_store["before"]:
            blks_read_per_op = (pg_store["after"]["blks_read"] - pg_store["before"]["blks_read"]) / ok

    mg_store = stores.get("mongo", {})
    if mg_store.get("before") and mg_store.get("after") and ok > 0:
        if "query" in mg_store["after"] and "query" in mg_store["before"]:
            query_per_op = (mg_store["after"]["query"] - mg_store["before"]["query"]) / ok

    server_jvm = data.get("meta", {}).get("serverJvm", "")

    return {
        "ok": ok,
        "failed": failed,
        "errors": errors,
        "throughput": results.get("throughput", 0.0),
        "p50": results.get("latencyMs", {}).get("p50", 0.0),
        "p99": results.get("latencyMs", {}).get("p99", 0.0),
        "node_cores": node.get("averageCores"),
        "node_cpu_ms_per_op": node_cpu_ms,
        "allocated_kb_per_op": alloc_kb,
        "gc_count": ygc + fgc,
        "gc_time_s": gct,
        "kafka_cpu_avg": kafka_c,
        "db_cpu_avg": db_c,
        "wal_avg": wal_avg,
        "wal_max": wal_max,
        "tup_fetched": tup_fetched_per_op,
        "blks_read": blks_read_per_op,
        "query": query_per_op,
        "mongo_before": mongo_before,
        "mongo_after": mongo_after,
        "server_jvm": server_jvm
    }

def parse_sweep(sat_path):
    if not os.path.exists(sat_path):
        return None
    with open(sat_path) as f:
        data = json.load(f)
    steps = data.get("steps", [])
    top = steps[-1].get("concurrency") if steps else 4096
    peak_concurrency = data.get("saturationConcurrency", 0)
    is_top = (peak_concurrency == top)
    
    total_errors = {}
    for s in steps:
        for err_k, err_v in s.get("errors", {}).items():
            total_errors[err_k] = total_errors.get(err_k, 0) + err_v

    server_jvm = data.get("meta", {}).get("serverJvm", "")

    return {
        "peak": data.get("saturationThroughput", 0.0),
        "concurrency": peak_concurrency,
        "is_top": is_top,
        "steps": steps,
        "errors": total_errors,
        "server_jvm": server_jvm
    }

def parse_l1(component_path):
    if not os.path.exists(component_path):
        return None
    with open(component_path) as f:
        data = json.load(f)
    return {
        "throughput": data.get("throughput", 0.0),
        "p50": data.get("latencyMs", {}).get("p50", 0.0),
        "p99": data.get("latencyMs", {}).get("p99", 0.0),
        "round_trips": data.get("storeRoundTripsPerOp", 0.0),
        "alloc_kb": data.get("allocatedBytesPerOp", 0.0) / 1024.0
    }

def generate_summary(root_dir, out_md_path):
    lines = []
    lines.append("# Wow V10 里程碑饱和快照（Apple M5 Max）")
    lines.append("")
    lines.append("## 0. 环境")
    lines.append("")
    lines.append("| 项目 | 配置 |")
    lines.append("| :--- | :--- |")
    lines.append("| 分支 | `bench/m5max-snapshot` |")
    lines.append("| 提交号 (HEAD) | `cebdb88d01e87a81fb23ff90fbd4d301ed1e7da8` (`cebdb88d0`) |")
    lines.append("| 机器硬件 | Apple M5 Max (18 核: 6 Super 超大核 + 12 Performance 性能核, 64 GB 统一内存) |")
    lines.append("| 操作系统 | macOS 27.2 (Build 26B123, Darwin 25.x) |")
    lines.append("| JDK 版本 | Eclipse Adoptium Temurin 21.0.13+9 (运行时阶段 6 对比 Temurin 25.0.5+9) |")
    lines.append("| 容器运行时 | OrbStack 2.2.5 (Engine 29.8.2, 16 CPUs, 32 GB 内存) |")
    lines.append("| 存储设置 | PostgreSQL `max_wal_size=8GB`, `checkpoint_timeout=15min`, `wal_compression=lz4`, `shared_buffers=512MB`；MongoDB WiredTiger cache 2 GB |")
    lines.append("| 测试数据集 | 100,000 账户 (`--accounts=100000`)，`--prime --seed-concurrency=128` |")
    lines.append("| 节点堆内存 | 默认 1 GB (`-Xms1g -Xmx1g -XX:+UseG1GC`) |")
    lines.append("| 饱和扫描步长 | 并发 1, 4, 16, 64, 256, 1024, 4096 (`--growth=4 --max-concurrency=4096 --step=30 --warm-up=10`) |")
    lines.append("")

    # --- Section 1 ---
    lines.append("## 1. 成本与饱和 (1-S-postgres, 1-S-mongo)")
    lines.append("")
    lines.append("### 1.1 闭环扫描 (Sweeps)")
    lines.append("")
    lines.append("| 后端 | 场景 / 分布 | 峰值吞吐 | 饱和并发 | 是否顶档 | 扫描总错误数 | 错误码分布 |")
    lines.append("| :--- | :--- | :---: | :---: | :---: | :---: | :--- |")

    sweeps_def = [
        ("postgres", "E1 均匀", "e1-sweep"),
        ("postgres", "E1 Zipf", "e1-zipf-sweep"),
        ("postgres", "E1 单热点 (1 key)", "e1-hot-sweep"),
        ("postgres", "E3 均匀", "e3-sweep"),
        ("mongo", "E1 均匀", "e1-sweep"),
        ("mongo", "E1 Zipf", "e1-zipf-sweep"),
        ("mongo", "E1 单热点 (1 key)", "e1-hot-sweep"),
        ("mongo", "E3 均匀", "e3-sweep"),
    ]

    for bk, sc_name, ph in sweeps_def:
        sat_p = os.path.join(root_dir, f"1-S-{bk}", ph, "saturation.json")
        sw = parse_sweep(sat_p)
        if sw:
            pk_str = f"≥ {sw['peak']:.1f}/s" if sw['is_top'] else f"{sw['peak']:.1f}/s"
            top_str = "是 (≥)" if sw['is_top'] else "否"
            err_cnt = sum(sw['errors'].values())
            err_str = json.dumps(sw['errors']) if err_cnt > 0 else "0"
            lines.append(f"| {bk} | {sc_name} | {pk_str} | {sw['concurrency']} | {top_str} | {err_cnt} | {err_str} |")
        else:
            lines.append(f"| {bk} | {sc_name} | - | - | - | - | - |")
    lines.append("")

    lines.append("### 1.2 开环测量 (Open-Loop)")
    lines.append("")
    lines.append("| 后端 | Phase | 吞吐 (/s) | p50 (ms) | p99 (ms) | 失败数 | 429 错误 | 504 错误 | Node 均核 | Node CPU (ms/op) | 分配 (KB/op) | GC 次数 (时间) | PG WAL Flush 均/最长 (ms) | 每命令读取 (PG: tup/blks, Mongo: query) |")
    lines.append("| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :--- |")

    phases_s1 = ["e1-25", "e1-50", "e1-75", "e3-25", "e3-50", "e3-75"]
    for bk in ["postgres", "mongo"]:
        for ph in phases_s1:
            p_dir = os.path.join(root_dir, f"1-S-{bk}", ph)
            r = parse_run(os.path.join(p_dir, "result.json"), os.path.join(p_dir, "summary.md"))
            if r:
                f429 = r["errors"].get("429", 0) + r["errors"].get("wow.command.busy", 0)
                f504 = r["errors"].get("504", 0) + r["errors"].get("wow.wait.timeout", 0)
                node_c = f"{r['node_cores']:.2f}" if r['node_cores'] is not None else "-"
                node_ms = f"{r['node_cpu_ms_per_op']:.3f}" if r['node_cpu_ms_per_op'] is not None else "-"
                alloc = f"{r['allocated_kb_per_op']:.1f}" if r['allocated_kb_per_op'] is not None else "-"
                gc_str = f"{r['gc_count']} ({r['gc_time_s']:.2f}s)"
                wal_str = f"{r['wal_avg']:.0f} / {r['wal_max']:.0f}" if r['wal_avg'] is not None else "-"

                reads_str = "-"
                if bk == "postgres" and r['tup_fetched'] is not None:
                    blks = f"{r['blks_read']:.2f}" if r['blks_read'] is not None else "-"
                    reads_str = f"{r['tup_fetched']:.2f} tup, {blks} blks"
                elif bk == "mongo" and r['query'] is not None:
                    reads_str = f"{r['query']:.3f} queries"

                lines.append(f"| {bk} | {ph} | {r['throughput']:.1f} | {r['p50']:.2f} | {r['p99']:.2f} | {r['failed']} | {f429} | {f504} | {node_c} | {node_ms} | {alloc} | {gc_str} | {wal_str} | {reads_str} |")
            else:
                lines.append(f"| {bk} | {ph} | - | - | - | - | - | - | - | - | - | - | - | - |")
    lines.append("")

    lines.append("### 1.3 MongoDB 开环缓存与检查点指标")
    lines.append("")
    lines.append("| Phase | 状态点 | 缓存占用 (MB) | 配置上限 (MB) | 未修改页淘汰 | 已修改页淘汰 | 最长检查点 (ms) | 最近检查点 (ms) |")
    lines.append("| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: |")
    for ph in phases_s1:
        p_dir = os.path.join(root_dir, "1-S-mongo", ph)
        bf = os.path.join(p_dir, "mongo-stats-before.json")
        af = os.path.join(p_dir, "mongo-stats-after.json")
        for tag, fpath in [("before", bf), ("after", af)]:
            if os.path.exists(fpath):
                try:
                    st = json.load(open(fpath))
                    cache_mb = st.get("bytes currently in the cache", 0) / (1024*1024)
                    max_mb = st.get("maximum bytes configured", 0) / (1024*1024)
                    unmod = st.get("unmodified pages evicted", 0)
                    mod = st.get("modified pages evicted", 0)
                    chk_max = st.get("transaction checkpoint max time (msecs)", 0)
                    chk_rec = st.get("transaction checkpoint most recent time (msecs)", 0)
                    lines.append(f"| {ph} | {tag} | {cache_mb:.1f} | {max_mb:.1f} | {unmod} | {mod} | {chk_max} | {chk_rec} |")
                except:
                    lines.append(f"| {ph} | {tag} | parse error | - | - | - | - | - |")
            else:
                lines.append(f"| {ph} | {tag} | - | - | - | - | - | - |")
    lines.append("")

    # --- Section 2 ---
    lines.append("## 2. A/A 校验 (PostgreSQL E1, 50% 峰值速率)")
    lines.append("")
    lines.append("| 会话 / Round | Throughput (/s) | p50 (ms) | p99 (ms) | 失败数 | 429 错误 | 504 错误 | Node 均核 | Node CPU (ms/op) | 分配 (KB/op) | GC 次数 (时间) | PG WAL Flush 均/最长 (ms) | 每命令读取 (tup_fetched, blks_read) |")
    lines.append("| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :--- |")
    aa_runs = {}
    for rnd in [2, 3]:
        p_dir = os.path.join(root_dir, f"{rnd}-S-postgres", "e1-aa")
        r = parse_run(os.path.join(p_dir, "result.json"), os.path.join(p_dir, "summary.md"))
        aa_runs[rnd] = r
        if r:
            f429 = r["errors"].get("429", 0) + r["errors"].get("wow.command.busy", 0)
            f504 = r["errors"].get("504", 0) + r["errors"].get("wow.wait.timeout", 0)
            node_c = f"{r['node_cores']:.2f}" if r['node_cores'] is not None else "-"
            node_ms = f"{r['node_cpu_ms_per_op']:.3f}" if r['node_cpu_ms_per_op'] is not None else "-"
            alloc = f"{r['allocated_kb_per_op']:.1f}" if r['allocated_kb_per_op'] is not None else "-"
            gc_str = f"{r['gc_count']} ({r['gc_time_s']:.2f}s)"
            wal_str = f"{r['wal_avg']:.0f} / {r['wal_max']:.0f}" if r['wal_avg'] is not None else "-"
            reads_str = f"{r['tup_fetched']:.2f} tup, {r['blks_read']:.2f} blks" if r['tup_fetched'] is not None else "-"
            lines.append(f"| Round {rnd} ({rnd}-S-postgres) | {r['throughput']:.1f} | {r['p50']:.2f} | {r['p99']:.2f} | {r['failed']} | {f429} | {f504} | {node_c} | {node_ms} | {alloc} | {gc_str} | {wal_str} | {reads_str} |")
        else:
            lines.append(f"| Round {rnd} ({rnd}-S-postgres) | - | - | - | - | - | - | - | - | - | - | - | - |")

    r2 = aa_runs.get(2)
    r3 = aa_runs.get(3)
    if r2 and r3 and r2['throughput'] > 0 and r2['node_cpu_ms_per_op'] and r3['node_cpu_ms_per_op']:
        thr_diff = (r3['throughput'] - r2['throughput']) / r2['throughput'] * 100
        cpu_diff = (r3['node_cpu_ms_per_op'] - r2['node_cpu_ms_per_op']) / r2['node_cpu_ms_per_op'] * 100
        alloc_diff = (r3['allocated_kb_per_op'] - r2['allocated_kb_per_op']) / r2['allocated_kb_per_op'] * 100 if r2['allocated_kb_per_op'] else 0
        lines.append(f"| **相对差异 (R3 vs R2)** | **{thr_diff:+.2f}%** | - | - | - | - | - | - | **{cpu_diff:+.2f}%** | **{alloc_diff:+.2f}%** | - | - | - |")
    lines.append("")

    # --- Section 3 ---
    lines.append("## 3. 磁盘与内存对照 (4-T-* vs 1-S-*)")
    lines.append("")
    lines.append("| 后端 | 场景 | 磁盘峰值 (1-S-*) | 内存峰值 (4-T-*, tmpfs) | 内存 vs 磁盘增益 | 内存顶档 | 内存扫描错误数 |")
    lines.append("| :--- | :--- | :---: | :---: | :---: | :---: | :---: |")
    for bk in ["postgres", "mongo"]:
        for sc, ph in [("E1 均匀", "e1-sweep"), ("E3 均匀", "e3-sweep")]:
            disk_sat = os.path.join(root_dir, f"1-S-{bk}", ph, "saturation.json")
            mem_sat = os.path.join(root_dir, f"4-T-{bk}", ph, "saturation.json")
            d_sw = parse_sweep(disk_sat)
            m_sw = parse_sweep(mem_sat)
            d_str = f"≥ {d_sw['peak']:.1f}/s" if (d_sw and d_sw['is_top']) else (f"{d_sw['peak']:.1f}/s" if d_sw else "-")
            m_str = f"≥ {m_sw['peak']:.1f}/s" if (m_sw and m_sw['is_top']) else (f"{m_sw['peak']:.1f}/s" if m_sw else "-")
            diff_str = "-"
            if d_sw and m_sw and d_sw['peak'] > 0:
                diff_val = (m_sw['peak'] - d_sw['peak']) / d_sw['peak'] * 100
                diff_str = f"{diff_val:+.2f}%"
            m_top = ("是 (≥)" if m_sw['is_top'] else "否") if m_sw else "-"
            m_err = str(sum(m_sw['errors'].values())) if m_sw else "-"
            lines.append(f"| {bk} | {sc} | {d_str} | {m_str} | {diff_str} | {m_top} | {m_err} |")
    lines.append("")

    # --- Section 4 ---
    lines.append("## 4. L1 组件单机测试 (Component Scaling)")
    lines.append("")
    lines.append("| 后端 | 场景 | 并发度 | 吞吐 (/s) | p50 (ms) | p99 (ms) | Store 交互次数 / op | 分配 (KB/op) |")
    lines.append("| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: |")

    for bk in ["postgres", "mongo"]:
        for sc in ["E1", "E3"]:
            for c in [16, 64, 256, 1024]:
                l1_dir = os.path.join(root_dir, "l1", f"{bk}-{sc}-{c}")
                l1_file = os.path.join(l1_dir, "component.json")
                comp = parse_l1(l1_file)
                if comp:
                    lines.append(f"| {bk} | {sc} | {c} | {comp['throughput']:.1f} | {comp['p50']:.2f} | {comp['p99']:.2f} | {comp['round_trips']:.2f} | {comp['alloc_kb']:.1f} |")
                else:
                    lines.append(f"| {bk} | {sc} | {c} | - | - | - | - | - |")
    lines.append("")

    # --- Section 5 ---
    lines.append("## 5. 3 节点集群扩展性 (5-N3-* vs 1-S-*)")
    lines.append("")
    lines.append("| 后端 | 场景 | 单节点峰值 (1-S-*) | 3 节点峰值 (5-N3-*) | 扩展比 (3N / 1N) | 3N 顶档 | 3N 错误数 |")
    lines.append("| :--- | :--- | :---: | :---: | :---: | :---: | :---: |")
    for bk in ["postgres", "mongo"]:
        for sc, ph in [("E1 均匀", "e1-sweep"), ("E3 均匀", "e3-sweep")]:
            n1_sat = os.path.join(root_dir, f"1-S-{bk}", ph, "saturation.json")
            n3_sat = os.path.join(root_dir, f"5-N3-{bk}", ph, "saturation.json")
            n1_sw = parse_sweep(n1_sat)
            n3_sw = parse_sweep(n3_sat)
            n1_str = f"≥ {n1_sw['peak']:.1f}/s" if (n1_sw and n1_sw['is_top']) else (f"{n1_sw['peak']:.1f}/s" if n1_sw else "-")
            n3_str = f"≥ {n3_sw['peak']:.1f}/s" if (n3_sw and n3_sw['is_top']) else (f"{n3_sw['peak']:.1f}/s" if n3_sw else "-")
            scale_str = "-"
            if n1_sw and n3_sw and n1_sw['peak'] > 0:
                scale_val = n3_sw['peak'] / n1_sw['peak']
                scale_str = f"{scale_val:.2f}x"
            n3_top = ("是 (≥)" if n3_sw['is_top'] else "否") if n3_sw else "-"
            n3_err = str(sum(n3_sw['errors'].values())) if n3_sw else "-"
            lines.append(f"| {bk} | {sc} | {n1_str} | {n3_str} | {scale_str} | {n3_top} | {n3_err} |")
    lines.append("")

    # --- Section 6 ---
    lines.append("## 6. JDK 25 运行时对比 (6-J25-postgres vs 1-S-postgres)")
    lines.append("")
    lines.append("| 场景 | JDK 21 峰值 (1-S-postgres) | JDK 25 峰值 (6-J25-postgres) | 增益 | JDK 25 顶档 | JVM 确认 | 扫描错误数 |")
    lines.append("| :--- | :---: | :---: | :---: | :---: | :--- | :---: |")
    for sc, ph in [("E1 均匀", "e1-sweep"), ("E3 均匀", "e3-sweep")]:
        j21_sat = os.path.join(root_dir, "1-S-postgres", ph, "saturation.json")
        j25_sat = os.path.join(root_dir, "6-J25-postgres", ph, "saturation.json")
        j21_sw = parse_sweep(j21_sat)
        j25_sw = parse_sweep(j25_sat)
        j21_str = f"≥ {j21_sw['peak']:.1f}/s" if (j21_sw and j21_sw['is_top']) else (f"{j21_sw['peak']:.1f}/s" if j21_sw else "-")
        j25_str = f"≥ {j25_sw['peak']:.1f}/s" if (j25_sw and j25_sw['is_top']) else (f"{j25_sw['peak']:.1f}/s" if j25_sw else "-")
        diff_str = "-"
        if j21_sw and j25_sw and j21_sw['peak'] > 0:
            diff_val = (j25_sw['peak'] - j21_sw['peak']) / j21_sw['peak'] * 100
            diff_str = f"{diff_val:+.2f}%"
        j25_top = ("是 (≥)" if j25_sw['is_top'] else "否") if j25_sw else "-"
        jvm_ver = j25_sw['server_jvm'][:30] if (j25_sw and j25_sw['server_jvm']) else "-"
        j25_err = str(sum(j25_sw['errors'].values())) if j25_sw else "-"
        lines.append(f"| {sc} | {j21_str} | {j25_str} | {diff_str} | {j25_top} | {jvm_ver} | {j25_err} |")
    lines.append("")

    with open(out_md_path, "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"Summary written to {out_md_path}")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: generate-summary.py <reports_root_dir> <output_summary_md>")
        sys.exit(1)
    generate_summary(sys.argv[1], sys.argv[2])
