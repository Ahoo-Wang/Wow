#!/usr/bin/env bash
set -uo pipefail

export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"
export WOW_BENCH_PG_PRELOAD="pg_stat_statements"

REPO="/Users/ahoo/.gemini/antigravity/worktrees/Wow/wow-m4pro-rerun"
B="$REPO/test/benchmarks/harness/bench.sh"
OUT="$REPO/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun"
LOG="/tmp/m4pro-rerun.log"
mkdir -p "$OUT"

say() {
  local msg="$(date '+%Y-%m-%d %H:%M:%S') $*"
  echo "$msg"
  echo "$msg" >> "$LOG"
}

say "Script started using Java: $("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1)"
say "Repo commit: $(git -C "$REPO" rev-parse HEAD)"

peak() {
  python3 -I -c "import json,sys;print(json.load(open(sys.argv[1]))['saturationThroughput'])" "$1/saturation.json"
}

rate() {
  python3 -I -c "import sys;print(round(float(sys.argv[1])*float(sys.argv[2])))" "$1" "$2"
}

dump_pg_stats() {
  local out_dir=$1
  {
    echo "# Top statements by total execution time (pg_stat_statements, since the run's warm-up began)"
    docker exec wow-e2e-bench-postgres psql -U postgres -d transfer -X -q -c "select calls, round(total_exec_time::numeric, 1) as total_ms, round(mean_exec_time::numeric, 3) as mean_ms, round(max_exec_time::numeric, 1) as max_ms, rows, left(regexp_replace(query, '\s+', ' ', 'g'), 140) as query from pg_stat_statements order by total_exec_time desc limit 15" 2>&1 || true
    echo "# Checkpoints (pg_stat_checkpointer)"
    docker exec wow-e2e-bench-postgres psql -U postgres -d transfer -X -q -c "select * from pg_stat_checkpointer" 2>&1 || true
    echo "# Waits now (pg_stat_activity)"
    docker exec wow-e2e-bench-postgres psql -U postgres -d transfer -X -q -c "select wait_event_type, wait_event, count(*) from pg_stat_activity group by 1, 2 order by 3 desc" 2>&1 || true
  } > "$out_dir/postgres-stats.txt" 2>&1 || true
}

dump_mongo_stats() {
  local target_file=$1
  docker exec wow-e2e-bench-mongo mongosh --quiet --eval 'const s=db.serverStatus(); printjson({opcounters:s.opcounters, wt_cache:{bytes:s.wiredTiger.cache["bytes currently in the cache"], max:s.wiredTiger.cache["maximum bytes configured"], evicted:s.wiredTiger.cache["unmodified pages evicted"]}, checkpoint_ms:s.wiredTiger.transaction["transaction checkpoint most recent time (msecs)"], checkpoint_max_ms:s.wiredTiger.transaction["transaction checkpoint max time (msecs)"]})' > "$target_file" 2>&1 || true
}

capture_mongo_currentop() {
  local target_file=$1
  (
    sleep 45
    docker exec wow-e2e-bench-mongo mongosh --quiet --eval 'printjson(db.currentOp({active:true}).inprog.map(o=>({op:o.op, ns:o.ns, secs:o.secs_running, cmd:Object.keys(o.command||{})[0]})))' > "$target_file" 2>&1 || true
  ) &
}

check_failure_note() {
  local run_dir=$1
  python3 -I -c '
import json, sys, os
d = sys.argv[1]
if not os.path.exists(d + "/result.json"): sys.exit(0)
res = json.load(open(d + "/result.json"))
ok = res["results"]["ok"]
failed_dict = res["results"].get("failed", {})
total_failed = sum(failed_dict.values())
total = ok + total_failed
if total > 0 and (total_failed / total) > 0.01:
    res_info = res["resources"]
    sec = res_info["seconds"]
    node_cpu = res_info["processes"]["node-1"]["cpuSeconds"] * 1000 / ok if ok else 0
    containers = res_info.get("containers", {})
    note = f"Failure rate > 1%: total={total}, failed={total_failed} ({total_failed/total*100:.2f}%)\n"
    note += f"Error codes: {json.dumps(failed_dict)}\n"
    note += f"Node CPU: {node_cpu:.3f} ms/op\n"
    for c, v in containers.items():
        c_cpu = v["cpuPercentAverage"] / 100 * sec * 1000 / ok if ok else 0
        note += f"{c} CPU: {c_cpu:.3f} ms/op (avg {v[\"cpuPercentAverage\"]:.1f}%)\n"
    bf, af = d + "/mongo-stats-before.json", d + "/mongo-stats-after.json"
    if os.path.exists(bf) and os.path.exists(af):
        note += "\nMongo serverStatus diff:\n"
        b_data, a_data = json.load(open(bf)), json.load(open(af))
        note += f"Before: {json.dumps(b_data)}\n"
        note += f"After: {json.dumps(a_data)}\n"
    open(d + "/note.txt", "w").write(note)
    print("Wrote note.txt for " + d)
' "$run_dir" || true
}

ACCOUNTS=100000

safe_up() {
  local backend=$1 out=$2 heap=${3:-1g}
  local attempt=1
  while [ $attempt -le 2 ]; do
    say "safe_up attempt $attempt: backend=$backend heap=$heap out=$out"
    if $B up --backend="$backend" --accounts="$ACCOUNTS" --heap="$heap" --prime --seed-concurrency=128 --out="$out" > "$out.log" 2>&1; then
      say "safe_up succeeded for $backend"
      if [ "$backend" = postgres ]; then
        docker exec wow-e2e-bench-postgres psql -U postgres -d transfer -X -q -c "create extension if not exists pg_stat_statements; select pg_stat_statements_reset();" >/dev/null 2>&1 || true
      fi
      return 0
    else
      say "safe_up attempt $attempt failed for $backend; running down and retrying..."
      $B down >/dev/null 2>&1 || true
      sleep 5
      attempt=$((attempt + 1))
    fi
  done
  say "safe_up permanently failed for $backend, skipping this group"
  echo "safe_up failed for $backend $heap at $(date)" >> "$OUT/failures.txt"
  return 1
}

stage_commit() {
  local pattern=$1 msg=$2
  say "Staging and committing: $msg"
  (
    cd "$REPO"
    find "$OUT" -path "*/$pattern/*" \( -name "*.json" -o -name "*.md" -o -name "*.txt" -o -name "*.tsv" \) -print0 | xargs -0 git add
    git status --short
    git commit -m "$msg"
    git push origin claude/local-baseline-m4pro-rerun
  ) || say "git commit $msg failed"
}

# ==================== 1. 第 0 阶段：A/A 噪声 ====================
stage0() {
  say "=================== STARTING STAGE 0 (A/A NOISE) ==================="
  if ! safe_up postgres "$OUT/s0-up-1"; then
    say "Stage 0 up failed, skipping stage 0"
    return 1
  fi

  say "Stage 0 sweep to determine peak"
  $B saturate --reuse --backend=postgres --accounts="$ACCOUNTS" --scenario=E1 --distribution=uniform \
    --sweep --step=30 --warm-up=10 --max-concurrency=1024 --out="$OUT/s0-sweep" > "$OUT/s0-sweep.log" 2>&1
  local p=$(peak "$OUT/s0-sweep")
  S0_RATE=$(rate "$p" 0.5)
  say "Stage 0 peak: $p/s, 50% rate: $S0_RATE/s"

  for r in 1 2; do
    say "Stage 0 session 1 run $r at $S0_RATE/s"
    $B run --reuse --backend=postgres --accounts="$ACCOUNTS" --scenario=E1 --distribution=uniform \
      --rate="$S0_RATE" --warm-up=30 --measure=60 --out="$OUT/s0-1-$r" > "$OUT/s0-1-$r.log" 2>&1 || say "s0-1-$r run failed"
    dump_pg_stats "$OUT/s0-1-$r"
    check_failure_note "$OUT/s0-1-$r"
  done
  $B down >/dev/null 2>&1 || true

  for s in 2 3; do
    say "Stage 0 session $s"
    if safe_up postgres "$OUT/s0-up-$s"; then
      for r in 1 2; do
        say "Stage 0 session $s run $r at $S0_RATE/s"
        $B run --reuse --backend=postgres --accounts="$ACCOUNTS" --scenario=E1 --distribution=uniform \
          --rate="$S0_RATE" --warm-up=30 --measure=60 --out="$OUT/s0-$s-$r" > "$OUT/s0-$s-$r.log" 2>&1 || say "s0-$s-$r run failed"
        dump_pg_stats "$OUT/s0-$s-$r"
        check_failure_note "$OUT/s0-$s-$r"
      done
      $B down >/dev/null 2>&1 || true
    fi
  done
  say "=================== FINISHED STAGE 0 ==================="
  stage_commit "s0-*" "bench(m4pro-rerun): stage 0 A/A noise on PostgreSQL"
}

# ==================== 2. 第 1 阶段：成本与饱和 ====================
stage1() {
  say "=================== STARTING STAGE 1 (COST & SATURATION) ==================="
  for backend in postgres mongo; do
    say "Stage 1: starting $backend"
    if ! safe_up "$backend" "$OUT/s1-$backend-up"; then
      say "Stage 1 $backend up failed, skipping"
      continue
    fi

    for key in E1:uniform E1:zipf E3:uniform; do
      local scenario=${key%%:*}
      local distribution=${key##*:}
      local max_c=512
      if [ "$scenario" = "E1" ] && [ "$distribution" = "uniform" ]; then max_c=1024; fi

      say "Stage 1 $backend $scenario $distribution sweep (max_concurrency=$max_c)"
      local sweep_out="$OUT/s1-$backend-$scenario-$distribution-sweep"
      $B saturate --reuse --backend="$backend" --accounts="$ACCOUNTS" --scenario="$scenario" --distribution="$distribution" \
        --sweep --step=30 --warm-up=10 --max-concurrency="$max_c" --out="$sweep_out" > "$sweep_out.log" 2>&1
      local p=$(peak "$sweep_out")
      say "Stage 1 $backend $scenario $distribution peak: $p/s"

      for share in 0.25 0.5 0.75; do
        local r=$(rate "$p" "$share")
        say "Stage 1 $backend $scenario $distribution at $share ($r/s)"
        local run_out="$OUT/s1-$backend-$scenario-$distribution-$share"
        $B run --reuse --backend="$backend" --accounts="$ACCOUNTS" --scenario="$scenario" --distribution="$distribution" \
          --rate="$r" --warm-up=30 --measure=60 --out="$run_out" > "$run_out.log" 2>&1 || say "s1-$backend-$scenario-$distribution-$share run failed"
        if [ "$backend" = postgres ]; then dump_pg_stats "$run_out"; fi
        check_failure_note "$run_out"
      done
    done
    $B down >/dev/null 2>&1 || true
  done
  say "=================== FINISHED STAGE 1 ==================="
  stage_commit "s1-*" "bench(m4pro-rerun): stage 1 cost and saturation on PostgreSQL and MongoDB"
}

# ==================== 3. 第 2 阶段：ABAB 1G 对 2G 堆 ====================
stage2_abab() {
  say "=================== STARTING STAGE 2 (ABAB 1G VS 2G HEAP) ==================="
  for backend in postgres mongo; do
    say "=== ABAB Backend: $backend ==="
    local round_idx=1
    local rate_e1=0
    local rate_e3=0

    # 4 sessions: 1g-1, 2g-1, 1g-2, 2g-2
    for session_tag in 1g-1 2g-1 1g-2 2g-2; do
      local heap="1g"
      if [[ "$session_tag" == 2g* ]]; then heap="2g"; fi

      say "ABAB $backend session $session_tag (heap=$heap)"
      if ! safe_up "$backend" "$OUT/abab-$backend-$session_tag-up" "$heap"; then
        say "ABAB $backend session $session_tag up failed, skipping"
        continue
      fi

      # 1. E1 uniform sweep (max 1024)
      local e1_sweep_out="$OUT/abab-$backend-$session_tag-E1-sweep"
      say "ABAB $backend $session_tag E1 sweep"
      $B saturate --reuse --backend="$backend" --accounts="$ACCOUNTS" --heap="$heap" --scenario=E1 --distribution=uniform \
        --sweep --step=30 --warm-up=10 --max-concurrency=1024 --out="$e1_sweep_out" > "$e1_sweep_out.log" 2>&1
      local p_e1=$(peak "$e1_sweep_out")
      say "ABAB $backend $session_tag E1 peak: $p_e1/s"

      # 2. E3 uniform sweep (max 512)
      local e3_sweep_out="$OUT/abab-$backend-$session_tag-E3-sweep"
      say "ABAB $backend $session_tag E3 sweep"
      $B saturate --reuse --backend="$backend" --accounts="$ACCOUNTS" --heap="$heap" --scenario=E3 --distribution=uniform \
        --sweep --step=30 --warm-up=10 --max-concurrency=512 --out="$e3_sweep_out" > "$e3_sweep_out.log" 2>&1
      local p_e3=$(peak "$e3_sweep_out")
      say "ABAB $backend $session_tag E3 peak: $p_e3/s"

      # If this is 1g-1, fix the rates
      if [ "$session_tag" = "1g-1" ]; then
        rate_e1=$(rate "$p_e1" 0.5)
        rate_e3=$(rate "$p_e3" 0.5)
        say "ABAB $backend fixed rates: E1=$rate_e1/s, E3=$rate_e3/s"
      fi

      # 3. E1 fixed-rate run
      local e1_run_out="$OUT/abab-$backend-$session_tag-E1-run"
      say "ABAB $backend $session_tag E1 run at $rate_e1/s"
      mkdir -p "$e1_run_out"
      if [ "$backend" = mongo ]; then
        dump_mongo_stats "$e1_run_out/mongo-stats-before.json"
        capture_mongo_currentop "$e1_run_out/mongo-currentop.json"
      fi
      $B run --reuse --backend="$backend" --accounts="$ACCOUNTS" --heap="$heap" --scenario=E1 --distribution=uniform \
        --rate="$rate_e1" --warm-up=30 --measure=60 --out="$e1_run_out" > "$e1_run_out.log" 2>&1 || say "$e1_run_out failed"
      if [ "$backend" = mongo ]; then
        dump_mongo_stats "$e1_run_out/mongo-stats-after.json"
      fi
      if [ "$backend" = postgres ]; then dump_pg_stats "$e1_run_out"; fi
      check_failure_note "$e1_run_out"

      # 4. E3 fixed-rate run
      local e3_run_out="$OUT/abab-$backend-$session_tag-E3-run"
      say "ABAB $backend $session_tag E3 run at $rate_e3/s"
      mkdir -p "$e3_run_out"
      if [ "$backend" = mongo ]; then
        dump_mongo_stats "$e3_run_out/mongo-stats-before.json"
        capture_mongo_currentop "$e3_run_out/mongo-currentop.json"
      fi
      $B run --reuse --backend="$backend" --accounts="$ACCOUNTS" --heap="$heap" --scenario=E3 --distribution=uniform \
        --rate="$rate_e3" --warm-up=30 --measure=60 --out="$e3_run_out" > "$e3_run_out.log" 2>&1 || say "$e3_run_out failed"
      if [ "$backend" = mongo ]; then
        dump_mongo_stats "$e3_run_out/mongo-stats-after.json"
      fi
      if [ "$backend" = postgres ]; then dump_pg_stats "$e3_run_out"; fi
      check_failure_note "$e3_run_out"

      $B down >/dev/null 2>&1 || true
      say "Sleeping 30s before next ABAB session..."
      sleep 30
    done
  done
  say "=================== FINISHED STAGE 2 (ABAB) ==================="
  stage_commit "abab-*" "bench(m4pro-rerun): stage 2 ABAB 1g vs 2g heap comparison"
}

# ==================== 4. 第 3 阶段：L1 组件 ====================
stage3_l1() {
  say "=================== STARTING STAGE 3 (L1 COMPONENT) ==================="
  for backend in postgres mongo; do
    for scenario in E1 E3; do
      for c in 16 64 256; do
        say "L1 $backend $scenario concurrency $c"
        local l1_out="$OUT/l1-$backend-$scenario-$c"
        $B component --backend="$backend" --scenario="$scenario" --accounts="$ACCOUNTS" --concurrency="$c" \
          --out="$l1_out" > "$l1_out.log" 2>&1 || say "L1 $backend $scenario $c failed"
      done
    done
  done
  say "=================== FINISHED STAGE 3 (L1) ==================="
  stage_commit "l1-*" "bench(m4pro-rerun): stage 3 L1 component benchmarks"
}

# ==================== 5. 第 4 阶段：汇总交付 ====================
deliver_summary() {
  say "=================== GENERATING SUMMARY & TSV ==================="
  python3 "$REPO/test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/scripts/cost.py" "$OUT" \
    > "$OUT/cost-per-op.tsv" || say "cost.py failed"

  python3 -I -c '
import json, glob, os, sys

OUT = sys.argv[1]

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

# Helper
def get_res(d):
    if not os.path.exists(d + "/result.json"): return None
    r = json.load(open(d + "/result.json"))
    res = r["results"]
    ops = res["ok"]
    fails = sum(res.get("failed", {}).values())
    fail_str = json.dumps(res.get("failed", {})) if fails > 0 else "0"
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
                    # line: PostgreSQL per second: commits 7931, WAL flushes 2682, WAL flush time 809 ms (max 896 ms), WAL write time 17 ms
                    p = line.split("(max")[1].split("ms)")[0].strip()
                    wal_max = float(p)
                except: pass
    # Check mongo checkpoint
    chk_max = 0
    after_file = d + "/mongo-stats-after.json"
    if os.path.exists(after_file):
        try:
            m_s = json.load(open(after_file))
            chk_max = m_s.get("checkpoint_max_ms", 0)
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
md.append("| Scenario | Share | Target Rate | Throughput | OK | Failed | p50 (ms) | p99 (ms) | Max (ms) | Server CPU | Store Max Latency (WAL/Chk ms) |")
md.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |")
for bk in ["postgres", "mongo"]:
    for sc, dist in [("E1", "uniform"), ("E1", "zipf"), ("E3", "uniform")]:
        for sh in ["0.25", "0.5", "0.75"]:
            d = f"{OUT}/s1-{bk}-{sc}-{dist}-{sh}"
            info = get_res(d)
            if info:
                rate = json.load(open(d + "/result.json"))["load"]["rate"]
                store_lat = f"WAL: {info['wal_max']:.0f}" if bk == "postgres" else f"Chk: {info['chk_max']:.0f}"
                if info['wal_max'] > 500: store_lat = f"**WAL: {info['wal_max']:.0f} (exceed 500ms)**"
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
        e1_sp1 = json.load(open(f"{OUT}/abab-{bk}-{s1}-E1-sweep/saturation.json"))["saturationThroughput"]
        e1_sp2 = json.load(open(f"{OUT}/abab-{bk}-{s2}-E1-sweep/saturation.json"))["saturationThroughput"]
        e3_sp1 = json.load(open(f"{OUT}/abab-{bk}-{s1}-E3-sweep/saturation.json"))["saturationThroughput"]
        e3_sp2 = json.load(open(f"{OUT}/abab-{bk}-{s2}-E3-sweep/saturation.json"))["saturationThroughput"]
        
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
print("SUMMARY.md generated successfully.")
' "$OUT" || say "SUMMARY.md generation failed"

  (cd "$REPO" && git add test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/cost-per-op.tsv \
                       test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/SUMMARY.md \
                       test/benchmarks/results/reports/v10-local-baseline-m4pro/rerun/scripts/ && \
   git commit -m "bench(m4pro-rerun): add cost-per-op.tsv, SUMMARY.md and scripts" && \
   git push origin claude/local-baseline-m4pro-rerun) || say "git commit summary failed"
}

START_TIME=$SECONDS

stage0
stage1
stage2_abab
stage3_l1
deliver_summary

TOTAL_TIME=$((SECONDS - START_TIME))
say "All rerun tasks completed in $TOTAL_TIME seconds."
say "Final commit: $(git -C "$REPO" rev-parse HEAD)"
