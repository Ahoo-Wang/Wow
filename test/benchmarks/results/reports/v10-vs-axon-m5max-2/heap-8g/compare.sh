#!/bin/bash
# Wow against Axon Framework 4 on PostgreSQL on Apple M5 Max with 8 GB Heap (Rerun)
# Arms: W A A W K K, then A32, W32
# Wow with Kafka off uses --wow.transport=none (per X-33)
set -uo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPTS_DIR/../../../../../.." && pwd)"

export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

pgrep -x caffeinate >/dev/null || caffeinate -dimsu &

B="$REPO/test/benchmarks/harness/bench.sh"
WOW="$REPO/test/benchmarks/server/build/install/benchmark-server"
AXON="$REPO/test/benchmarks/axon-server/build/install/axon-server"
OUT="$SCRIPTS_DIR"

say() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

ensure_clean_env() {
  local port pids
  for port in 18080 18081 18082 19090 19091 19092; do
    pids=$(lsof -t -iTCP:$port -sTCP:LISTEN 2>/dev/null || true)
    if [ -n "$pids" ]; then
      say "Killing lingering process on port $port: $pids"
      kill -9 $pids 2>/dev/null || true
    fi
  done
  local cids
  cids=$(docker ps -q --filter "name=wow-e2e-bench" 2>/dev/null || true)
  if [ -n "$cids" ]; then
    say "Removing running benchmark docker containers: $cids"
    docker rm -f $cids >/dev/null 2>&1 || true
  fi
}

save_node_logs() {
  local target_dir="$1"
  mkdir -p "$target_dir/logs"
  local session_file="$REPO/test/benchmarks/results/e2e/.session"
  if [ -f "$session_file" ]; then
    local s_out
    s_out=$(. "$session_file"; echo "$S_OUT")
    if [ -n "$s_out" ] && [ -d "$s_out" ]; then
      cp -f "$s_out"/node-*.log "$target_dir/logs/" 2>/dev/null || true
      say "Copied node logs from $s_out to $target_dir/logs/"
    fi
  fi
}

# 1. Standard sessions (W A A W K K)
ARMS=(W A A W K K)
n=0
for arm in "${ARMS[@]}"; do
  n=$((n + 1))
  O="$OUT/sessions/$n-$arm-postgres"
  mkdir -p "$O"
  case "$arm" in
    W) C=(--install="$WOW" "--server-args=--wow.transport=none") ;;
    A) C=(--install="$AXON") ;;
    K) C=(--install="$WOW") ;;
  esac
  C+=(--backend=postgres --accounts=100000 --heap=8g)

  say "Session $n ($arm) up"
  ensure_clean_env
  $B up "${C[@]}" --mix=E1,E3 --prime --seed-concurrency=128 --out="$O/up" > "$O/up.log" 2>&1 || {
    say "Session $n up failed"
    $B down >/dev/null 2>&1
    ensure_clean_env
    continue
  }

  say "Session $n ($arm) E1 fixed 2500"
  $B run --reuse "${C[@]}" --scenario=E1 --rate=2500 --warm-up=20 --measure=40 --out="$O/e1" > "$O/e1.log" 2>&1 || say "e1 failed"

  say "Session $n ($arm) E3 fixed 700"
  $B run --reuse "${C[@]}" --scenario=E3 --rate=700 --warm-up=20 --measure=40 --out="$O/e3" > "$O/e3.log" 2>&1 || say "e3 failed"

  say "Session $n ($arm) E1 sweep (max 4096)"
  $B saturate --reuse "${C[@]}" --scenario=E1 --distribution=uniform --max-concurrency=4096 --sweep --growth=4 --step=30 --warm-up=10 --out="$O/e1-sweep" > "$O/e1-sweep.log" 2>&1 || say "e1 sweep failed"

  say "Session $n ($arm) E3 sweep (max 4096)"
  $B saturate --reuse "${C[@]}" --scenario=E3 --distribution=uniform --max-concurrency=4096 --sweep --growth=4 --step=30 --warm-up=10 --out="$O/e3-sweep" > "$O/e3-sweep.log" 2>&1 || say "e3 sweep failed"

  save_node_logs "$O"
  say "Session $n ($arm) down"
  $B down > /dev/null 2>&1
  ensure_clean_env
  sleep 5
done

# 2. Pool-32 sessions (A32, W32)
POOL_ARMS=(A W)
m=0
for arm in "${POOL_ARMS[@]}"; do
  m=$((m + 1))
  O="$OUT/pool-32/$m-$arm-postgres"
  mkdir -p "$O"
  case "$arm" in
    A) C=(--install="$AXON" "--server-args=--spring.datasource.hikari.maximum-pool-size=32") ;;
    W) C=(--install="$WOW" "--server-args=--wow.transport=none --spring.datasource.hikari.maximum-pool-size=32") ;;
  esac
  C+=(--backend=postgres --accounts=100000 --heap=8g)

  say "Pool-32 Session $m ($arm) up"
  ensure_clean_env
  $B up "${C[@]}" --mix=E1,E3 --prime --seed-concurrency=128 --out="$O/up" > "$O/up.log" 2>&1 || {
    say "Pool-32 Session $m up failed"
    $B down >/dev/null 2>&1
    ensure_clean_env
    continue
  }

  say "Pool-32 Session $m ($arm) E1 fixed 2500"
  $B run --reuse "${C[@]}" --scenario=E1 --rate=2500 --warm-up=20 --measure=40 --out="$O/e1" > "$O/e1.log" 2>&1 || say "pool-32 e1 failed"

  say "Pool-32 Session $m ($arm) E3 fixed 700"
  $B run --reuse "${C[@]}" --scenario=E3 --rate=700 --warm-up=20 --measure=40 --out="$O/e3" > "$O/e3.log" 2>&1 || say "pool-32 e3 failed"

  say "Pool-32 Session $m ($arm) E1 sweep (max 4096)"
  $B saturate --reuse "${C[@]}" --scenario=E1 --distribution=uniform --max-concurrency=4096 --sweep --growth=4 --step=30 --warm-up=10 --out="$O/e1-sweep" > "$O/e1-sweep.log" 2>&1 || say "pool-32 e1 sweep failed"

  say "Pool-32 Session $m ($arm) E3 sweep (max 4096)"
  $B saturate --reuse "${C[@]}" --scenario=E3 --distribution=uniform --max-concurrency=4096 --sweep --growth=4 --step=30 --warm-up=10 --out="$O/e3-sweep" > "$O/e3-sweep.log" 2>&1 || say "pool-32 e3 sweep failed"

  save_node_logs "$O"
  say "Pool-32 Session $m ($arm) down"
  $B down > /dev/null 2>&1
  ensure_clean_env
  sleep 5
done

say "All 8 GB sessions completed successfully."
