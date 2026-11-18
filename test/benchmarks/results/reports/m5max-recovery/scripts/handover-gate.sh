#!/usr/bin/env bash
# The hand-over gate of #6407's follow-up: CI's e3-handover step on MongoDB, main (#6412) against the patch (a free
# read only for a backlog that fits a page), three rounds each alternating, then one PostgreSQL round with the patch, in
# one hold of main's bench lock. Clean trees: main binds the stores to loopback itself. Node 2 stops (SIGTERM) 25 s into the 90 s
# measurement and restarts 20 s later; E3 at 50/s on 10,000 accounts. "fix" is the patch.
set -u
S=/private/tmp/claude-501/-Users-ahoowang-work-ahoo-Wow--claude-worktrees-rt-subs-p1-2727ce/4a806155-973d-405b-99f7-d65b7252944a/scratchpad
MAIN=$S/gate-main FIX=$S/wt-fits H=$S/gate-main/test/benchmarks/harness/bench.sh
RUNS=${RUNS:-main-1 fix-1 main-2 fix-2 main-3 fix-3 pgfix-1}
B=$S/gate/out; mkdir -p $B
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
  for f in $B/*/node-restarted.pid ; do
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
  (cd $t && ./gradlew --console=plain -q :benchmark-server:installDist :benchmark-load:installDist) > $B/build-$(basename $t).log 2>&1 \
    || { echo "BUILD FAILED"; tail -20 $B/build-$(basename $t).log; exit 2; }
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
    --server-args=--logging.level.me.ahoo.wow.runtime.cluster=info --label=gate-$run --out=$out > $out/bench-output.log 2>&1
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
  echo "RUN $run rc=$rc $(date -u +%H:%M:%SZ) $(python3 -I -c "import json,sys;r=json.load(open(sys.argv[1]))['results'];print('failed',r['sent']['failed'],'p99',round(r['latencyMs']['p99']),'ms',r['errors'])" $out/result.json 2>&1)"
done
echo "GATE done $(date -u +%H:%M:%SZ)"
