#!/usr/bin/env bash
set -uo pipefail

export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

pgrep -x caffeinate >/dev/null || caffeinate -dimsu &

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
OUT_ROOT="$(cd "$SCRIPTS_DIR/.." && pwd)"
REPO="$(cd "$SCRIPTS_DIR/../../../../../.." && pwd)"
B="$REPO/test/benchmarks/harness/bench.sh"
ACCOUNTS=100000
SWEEP_ARGS="--max-concurrency=4096 --sweep --growth=4 --step=30 --warm-up=10"

say() {
  local msg="[$(date '+%Y-%m-%d %H:%M:%S')] $*"
  echo "$msg"
}

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

safe_down() {
  say "Stopping session..."
  "$B" down >/dev/null 2>&1 || true
  ensure_clean_env
  sleep 3
}

safe_up() {
  local backend="$1"
  local heap="${2:-1g}"
  shift 2 || true
  local extra_args=("$@")
  local attempt=1
  while [ $attempt -le 2 ]; do
    say "Attempting up (attempt $attempt/2): backend=$backend heap=$heap accounts=$ACCOUNTS ${extra_args[*]:-}"
    ensure_clean_env
    if "$B" up --backend="$backend" --accounts="$ACCOUNTS" --heap="$heap" --prime --seed-concurrency=128 ${extra_args[@]+"${extra_args[@]}"}; then
      say "Session successfully started: backend=$backend heap=$heap ${extra_args[*]:-}"
      return 0
    else
      say "Attempt $attempt failed for backend=$backend heap=$heap. Tearing down and retrying..."
      "$B" down >/dev/null 2>&1 || true
      ensure_clean_env
      sleep 5
      attempt=$((attempt + 1))
    fi
  done
  say "ERROR: Failed to bring up session after 2 attempts for backend=$backend heap=$heap ${extra_args[*]:-}"
  return 1
}

write_sweep_summary() {
  local d="$1"
  local sat="$d/saturation.json"
  if [ -f "$sat" ]; then
    python3 -c "
import json, sys
sat = json.load(open(sys.argv[1]))
lines = []
scenario = sat.get('scenario', '')
dist = sat.get('keys', {}).get('distribution', '')
lines.append(f'# Closed-loop Saturation: {scenario} ({dist})')
lines.append('')
lines.append(f'- **Peak Throughput**: {sat.get(\"saturationThroughput\", 0):.1f}/s')
lines.append(f'- **Peak Concurrency**: {sat.get(\"saturationConcurrency\", 0)}')
lines.append('')
lines.append('| Concurrency | Throughput (/s) | p50 (ms) | p99 (ms) | OK | Errors |')
lines.append('| ---: | ---: | ---: | ---: | ---: | :--- |')
for s in sat.get('steps', []):
    lines.append(f\"| {s.get('concurrency')} | {s.get('throughput', 0):.1f} | {s.get('p50Ms', 0):.2f} | {s.get('p99Ms', 0):.2f} | {s.get('ok', 0)} | {s.get('errors', {})} |\")
with open(sys.argv[2], 'w') as f:
    f.write('\n'.join(lines) + '\n')
" "$sat" "$d/summary.md"
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

save_health_metrics() {
  local target_dir="$1"
  local nodes="${2:-1}"
  mkdir -p "$target_dir/health"
  for i in $(seq 0 $((nodes - 1))); do
    local port=$((18080 + i))
    curl -s "http://localhost:$port/actuator/health" > "$target_dir/health/node-$i-health.json" 2>/dev/null || true
    curl -s "http://localhost:$port/actuator/health/wow" > "$target_dir/health/node-$i-wow.json" 2>/dev/null || true
    curl -s "http://localhost:$port/actuator/metrics" > "$target_dir/health/node-$i-metrics.json" 2>/dev/null || true
  done
  say "Saved health and metrics for $nodes nodes in $target_dir/health/"
}
