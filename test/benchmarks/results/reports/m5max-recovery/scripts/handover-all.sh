#!/usr/bin/env bash
# #6407's option 4, measured in one hold of main's bench lock:
#  1. CI's e3-handover step (three nodes, E3 at 50/s, node 2 SIGTERM 25 s into the 90 s measurement, restarted 20 s
#     later) on MongoDB, main (l4x/tree) against the fix (l4y/tree) alternately, three rounds each, then one
#     PostgreSQL round with the fix; both trees carry the measurement probes only (never pushed).
#  2. The migration rehearsal's shape, locally: one PostgreSQL node on a seeded history (ACCOUNTS accounts), stopped,
#     every subscription checkpoint set back to 0, restarted with the arm's build, then E1 at 50/s for 60 s during its
#     catch-up. Arms: no bound first (the fix with max-catch-ups=0), which must fail commands or blow the p99 for the
#     shape to mean anything; then main and the fix at the default.
set -u
S=/private/tmp/claude-501/-Users-ahoowang-work-ahoo-Wow--claude-worktrees-rt-subs-p1-2727ce/4a806155-973d-405b-99f7-d65b7252944a/scratchpad
MAIN=$S/l4x/tree FIX=$S/l4y/tree H=$S/l4x/tree/test/benchmarks/harness/bench.sh
ACCOUNTS=${ACCOUNTS:-100000} RUNS=${RUNS:-main-1 fix-1 main-2 fix-2 main-3 fix-3 pgfix-1} ARMS=${ARMS:-nobound main fix}
B=$S/l4y/out; mkdir -p $B
export DOCKER_HOST=unix:///Users/ahoowang/.orbstack/run/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export WOW_BENCH_OWNER=rt-subs-p1
export SPRING_APPLICATION_JSON='{"bench.signals.enabled":true}'
. $S/guard.sh
now() { perl -MTime::HiRes=time -e 'printf "%.3f", time'; }
install() { echo "$1/test/benchmarks/server/build/install/benchmark-server"; }
echo "QUEUED $(date -u +%H:%M:%SZ) main $(git -C $MAIN rev-parse --short=10 HEAD) fix $(git -C $FIX rev-parse --short=10 HEAD)"
$H lock --owner=rt-subs-p1 --pid=$$ --wait || { echo "LOCK FAILED"; exit 1; }
echo "LOCKED $(date -u +%H:%M:%SZ) load $(sysctl -n vm.loadavg)"
# On the way out: the nodes this script started stop first, while their stores still answer, and the lock is released
# only once each is gone (KILL after 60 s); then the stores go.
stop_own() {
  local f p
  for f in $B/*/node-restarted.pid $B/rehearsal/*/node.pid; do
    [ -f "$f" ] || continue
    p=$(cat "$f"); kill -TERM "$p" 2>/dev/null || continue
    for _ in $(seq 1 300); do kill -0 "$p" 2>/dev/null || break; sleep 0.2; done
    kill -0 "$p" 2>/dev/null && { echo "node $p did not stop in 60 s: killing it"; kill -KILL "$p"; }
  done
}
trap 'stop_own; $H down >/dev/null 2>&1; $H unlock --owner=rt-subs-p1; echo "UNLOCKED $(date -u +%H:%M:%SZ)"' EXIT
f=$(foreign); [ -z "$f" ] || { echo "FOREIGN after the lock: $f"; exit 4; }
# The stores are published on loopback only in this checkout (kubefwd holds 27017 and 9092 on loopback aliases, so
# the wildcard bind of the harness's compose fails): each must answer on localhost before the first round.
HD=$(dirname $H)
(cd $HD && docker compose -p wow-e2e-bench -f compose.yml up -d --wait mongo kafka postgres) > $B/preflight.log 2>&1 || { echo "PREFLIGHT up failed"; tail -5 $B/preflight.log; exit 3; }
for port in 27017 9092 5432; do
  nc -z -w 3 127.0.0.1 $port && nc -z -w 3 localhost $port || { echo "PREFLIGHT $port unreachable"; (cd $HD && docker compose -p wow-e2e-bench -f compose.yml down -v) >/dev/null 2>&1; exit 3; }
done
(cd $HD && docker compose -p wow-e2e-bench -f compose.yml down -v) >> $B/preflight.log 2>&1
echo "PREFLIGHT 27017 9092 5432 reachable on localhost (published on 127.0.0.1)"
for t in $MAIN $FIX; do
  echo "BUILD $(basename $(dirname $t)) start $(date -u +%H:%M:%SZ)"
  (cd $t && ./gradlew --console=plain -q :benchmark-server:installDist :benchmark-load:installDist) > $B/build-$(basename $(dirname $t)).log 2>&1 \
    || { echo "BUILD FAILED"; tail -20 $B/build-$(basename $(dirname $t)).log; exit 2; }
done
echo "BUILD done $(date -u +%H:%M:%SZ)"
# CI's hand-over helper, on macOS: the node by its port, its command line and working directory from ps and lsof.
handover() {
  local log=$1 dir=$2
  for _ in $(seq 1 900); do grep -q ': measure$' "$log" && break; sleep 1; done
  grep -q ': measure$' "$log" || { echo "no measured phase"; return 0; }
  sleep 25
  local pid; pid=$(pgrep -f -- "--server.port=18081" | head -1)
  [ -n "$pid" ] || { echo "no node on port 18081"; return 0; }
  local argv cwd; argv=$(ps -ww -o command= -p "$pid"); cwd=$(lsof -a -p "$pid" -d cwd -Fn | sed -n 's/^n//p')
  echo "[handover] $(now) stopping the node on port 18081 (pid $pid)"
  kill -TERM "$pid"
  for _ in $(seq 1 300); do kill -0 "$pid" 2>/dev/null || break; sleep 0.1; done
  echo "[handover] $(now) stopped; down for 20 s"
  sleep 20
  (set -f; cd "$cwd" && exec $argv) >> "$dir/node-restarted.log" 2>&1 &
  echo $! > "$dir/node-restarted.pid"
  echo "[handover] $(now) restarted (pid $!)"
}
echo "== hand-over $(date -u +%H:%M:%SZ)"
for run in $RUNS; do
  arm=${run%-*}; backend=mongo; tree=$MAIN
  [ $arm = fix ] && tree=$FIX
  [ $arm = pgfix ] && { tree=$FIX; backend=postgres; }
  out=$B/$run; rm -rf $out; mkdir -p $out
  f=$(foreign); [ -z "$f" ] || { echo "FOREIGN before $run: $f"; exit 4; }
  echo "RUN $run start $(date -u +%H:%M:%SZ)"
  handover $out/bench-output.log $out >> $out/handover.log 2>&1 &
  helper=$!
  WOW_BENCH_ADOPTED_PIDS=$out/node-restarted.pid $H run --backend=$backend --nodes=3 --scenario=E3 --distribution=uniform \
    --accounts=10000 --seed-concurrency=256 --rate=50.0 --warm-up=15 --measure=90 --install=$(install $tree) \
    --server-args=--logging.level.me.ahoo.wow.runtime.cluster=info --label=l4y-$run --out=$out > $out/bench-output.log 2>&1
  rc=$?
  kill $helper 2>/dev/null; wait $helper 2>/dev/null
  if [ -f $out/node-restarted.pid ]; then
    p=$(cat $out/node-restarted.pid); kill -TERM $p 2>/dev/null
    for _ in $(seq 1 150); do kill -0 $p 2>/dev/null || break; sleep 0.2; done
    kill -0 $p 2>/dev/null && { echo "restarted node $p did not stop: killing it"; kill -KILL $p; }
  fi
  $H down > /dev/null 2>&1
  grep -q "restarted" $out/handover.log || { echo "RUN $run: no hand-over ($(tail -1 $out/handover.log))"; exit 5; }
  grep -q "internal port bound" $out/node-restarted.log 2>/dev/null || { echo "RUN $run: the restarted node did not come up"; exit 5; }
  echo "RUN $run rc=$rc $(date -u +%H:%M:%SZ) $(python3 -I $S/l4x/summary.py $out 2>&1 | head -1)"
done
# The rehearsal's shape.
echo "== rehearsal $(date -u +%H:%M:%SZ)"
R=$B/rehearsal; rm -rf $R; mkdir -p $R
$H up --backend=postgres --nodes=1 --accounts=$ACCOUNTS --seed-concurrency=256 --install=$(install $MAIN) \
  --server-args=--logging.level.me.ahoo.wow.runtime.cluster=info --out=$R/up > $R/up.out 2>&1 || { echo "REHEARSAL up failed"; tail -5 $R/up.out; exit 6; }
echo "SEEDED $(date -u +%H:%M:%SZ) $(grep -h '\[seed\]' $R/up.out | tail -1)"
LOAD=$(install $MAIN | sed 's#server/build/install/benchmark-server#load/build/install/benchmark-load#')/bin/benchmark-load
for arm in $ARMS; do
  out=$R/$arm; mkdir -p $out
  pid=$(pgrep -f -- "--server.port=18080" | head -1)
  argv=$(ps -ww -o command= -p "$pid"); cwd=$(lsof -a -p "$pid" -d cwd -Fn | sed -n 's/^n//p')
  kill -TERM "$pid"; for _ in $(seq 1 300); do kill -0 "$pid" 2>/dev/null || break; sleep 0.1; done
  kill -0 "$pid" 2>/dev/null && { echo "REHEARSAL node $pid did not stop"; exit 6; }
  reset=$(docker exec wow-e2e-bench-postgres psql -U postgres -d transfer -tAc "update wow_checkpoint set position = 0" 2>&1)
  behind=$(docker exec wow-e2e-bench-postgres psql -U postgres -d transfer -tAc "select count(*) from wow_checkpoint" 2>&1)
  tree=$MAIN; [ $arm != main ] && tree=$FIX
  argv=${argv//$(install $MAIN)/$(install $tree)}; argv=${argv//$(install $FIX)/$(install $tree)}
  [ $arm = nobound ] && argv="$argv --wow.subscription.max-catch-ups=0"
  echo "$argv" > $out/argv
  started=$(now)
  (set -f; cd "$cwd" && exec $argv) > $out/node.log 2>&1 &
  echo $! > $out/node.pid
  for _ in $(seq 1 600); do curl -sf -o /dev/null http://127.0.0.1:18080/wow/manifest && break; sleep 0.2; done
  echo "ARM $arm up $(date -u +%H:%M:%SZ) ($reset, $behind checkpoints at 0), $(awk "BEGIN{print $(now)-$started}")s to answer"
  "$LOAD" run --targets=http://127.0.0.1:18080 --accounts=$ACCOUNTS --scenario=E1 --rate=50 --warm-up=0 --measure=60 \
    --label=rehearsal-$arm --out=$out/run > $out/run.out 2>&1
  line=$(python3 -I - $out <<'PY'
import json, sys, re, os
d = sys.argv[1]
r = json.load(open(f'{d}/run/result.json'))['results']
caught = [int(m.group(1)) for m in (re.search(r'caught up in (\d+)ms', l) for l in open(f'{d}/node.log', errors='replace')) if m]
print(f"failed {r['failed']} of {r['ok'] + r['failed']}, worst-second p99 {r['worstSecondP99Ms']:.0f} ms, p99 {r['latencyMs']['p99']:.0f} ms, "
      f"errors {r['errors']} | catch-ups logged {len(caught)}, the longest {max(caught) if caught else '-'} ms")
PY
)
  echo "ARM $arm $line"
  if [ $arm = nobound ]; then
    case "$line" in
      "failed 0 "*) p99=$(echo "$line" | sed 's/.*worst-second p99 \([0-9]*\) ms.*/\1/'); if [ "$p99" -lt 2000 ]; then echo "REHEARSAL: no bound failed nothing (worst-second p99 ${p99} ms): the shape does not exercise the bound; stopping"; exit 0; fi ;;
    esac
  fi
done
