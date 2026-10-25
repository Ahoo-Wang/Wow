#!/usr/bin/env bash
set -uo pipefail

export JAVA_HOME=$(/usr/libexec/java_home -v 21.0.13)
SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
OUT_ROOT="$(cd "$SCRIPTS_DIR/.." && pwd)"
REPO="$(git -C "$SCRIPTS_DIR" rev-parse --show-toplevel)"
B="$REPO/test/benchmarks/harness/bench.sh"
ACCOUNTS=100000

say() {
  local msg="[$(date '+%Y-%m-%d %H:%M:%S')] $*"
  echo "$msg"
  mkdir -p "$OUT_ROOT"
  echo "$msg" >> "$OUT_ROOT/benchmark-run.log"
}

ensure_clean_env() {
  # Clean up any lingering processes on 19090 or 18080
  local pids
  pids=$(lsof -t -iTCP:19090 -sTCP:LISTEN 2>/dev/null || true)
  if [ -n "$pids" ]; then
    say "Killing lingering process on port 19090: $pids"
    kill -9 $pids 2>/dev/null || true
  fi
  pids=$(lsof -t -iTCP:18080 -sTCP:LISTEN 2>/dev/null || true)
  if [ -n "$pids" ]; then
    say "Killing lingering process on port 18080: $pids"
    kill -9 $pids 2>/dev/null || true
  fi
  # Clean up containers
  local cids
  cids=$(docker ps -q 2>/dev/null || true)
  if [ -n "$cids" ]; then
    say "Removing running docker containers: $cids"
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
  local attempt=1
  while [ $attempt -le 2 ]; do
    say "Attempting up (attempt $attempt/2): backend=$backend heap=$heap accounts=$ACCOUNTS"
    ensure_clean_env
    if "$B" up --backend="$backend" --accounts="$ACCOUNTS" --heap="$heap" --prime --seed-concurrency=128; then
      say "Session successfully started: backend=$backend heap=$heap"
      return 0
    else
      say "Attempt $attempt failed for backend=$backend heap=$heap. Tearing down and retrying..."
      "$B" down >/dev/null 2>&1 || true
      ensure_clean_env
      sleep 5
      attempt=$((attempt + 1))
    fi
  done
  say "ERROR: Failed to bring up session after 2 attempts for backend=$backend heap=$heap"
  return 1
}

dump_mongo_stats() {
  local target_file="$1"
  mkdir -p "$(dirname "$target_file")"
  if docker ps | grep -q "wow-e2e-bench-mongo"; then
    docker cp "$SCRIPTS_DIR/mongo-stats.js" wow-e2e-bench-mongo:/tmp/mongo-stats.js >/dev/null 2>&1 || true
    docker exec wow-e2e-bench-mongo mongosh --quiet /tmp/mongo-stats.js > "$target_file" 2>&1 || true
  else
    say "Warning: wow-e2e-bench-mongo is not running, skipping mongo stats dump to $target_file"
  fi
}

get_saturation_peak() {
  local f="$1"
  python3 -c "import json, sys; data = json.load(open(sys.argv[1])); print(data['saturationThroughput'])" "$f"
}

calc_rate() {
  local peak="$1"
  local pct="$2"
  python3 -c "import sys; print(round(float(sys.argv[1]) * float(sys.argv[2])))" "$peak" "$pct"
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
