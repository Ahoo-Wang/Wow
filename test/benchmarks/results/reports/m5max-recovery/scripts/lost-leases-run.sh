#!/usr/bin/env bash
# #6379's confirming run (1'): base against fix, one node on MongoDB, cluster logging at info, 1,000 accounts not
# primed, a 45 s E1 pre-load, then the CI mix at 760/s (10 s warm-up + 60 s measured). At 15 s into the measurement
# MongoDB is paused PAUSE seconds (10: the leases go overdue 4-7 s into it, the margin being 7 s, so every round loses
# them while the store is still stopped), then, with THROTTLE=<cpus>, served at that CPU for THROTTLED seconds: slow,
# not stopped, so the lost partitions' drains take long while their acquisitions can still go through. Three rounds a
# side, alternating. Run under both.sh, which holds the lock.
set -u
S=/private/tmp/claude-501/-Users-ahoowang-work-ahoo-Wow--claude-worktrees-rt-subs-p1-2727ce/4a806155-973d-405b-99f7-d65b7252944a/scratchpad
PAUSE=${PAUSE:-10} THROTTLE=${THROTTLE:-} THROTTLED=${THROTTLED:-30} ROUNDS=${ROUNDS:-3} ARMS=${ARMS:-base fix}
B=${OUT:-$S/lostx/out}; mkdir -p $B
export DOCKER_HOST=unix:///Users/ahoowang/.orbstack/run/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export WOW_BENCH_OWNER=rt-subs-p1
H=$S/beta5/tree-rc/test/benchmarks/harness/bench.sh
. $S/guard.sh
COMMON="--backend=mongo --nodes=1 --accounts=1000 --server-args=--logging.level.me.ahoo.wow.runtime.cluster=info"
MIX="--mix=E1:50,E4-state:40,E4-list:10 --rate=760 --warm-up=10 --measure=60"
M=wow-e2e-bench-mongo
now() { perl -MTime::HiRes=time -e 'printf "%.3f", time'; }
[ -n "${BOTH_HOLDS_LOCK:-}" ] || { echo "run under both.sh, which holds the lock"; exit 1; }
trap 'docker unpause $M >/dev/null 2>&1; $H down >/dev/null 2>&1' EXIT
for round in $(seq 1 $ROUNDS); do
  for arm in $ARMS; do
    install=$S/lostx/tree-base/test/benchmarks/server/build/install/benchmark-server
    [ $arm = fix ] && install=$S/wt-lost/test/benchmarks/server/build/install/benchmark-server
    out=$B/$arm-$round; mkdir -p $out
    f=$(foreign); [ -z "$f" ] || { echo "FOREIGN before $arm-$round: $f"; exit 4; }
    echo "RUN $arm-$round start $(date -u +%H:%M:%SZ) pause ${PAUSE}s throttle ${THROTTLE:-none}"
    $H up $COMMON --install=$install --out=$out/up > $out/up.out 2>&1 || { echo "RUN $arm-$round up failed"; exit 3; }
    $H run --reuse $COMMON --install=$install --scenario=E1 --rate=760 --warm-up=5 --measure=45 \
      --label=pre-$arm-$round --out=$out/pre > $out/pre.out 2>&1
    $H run --reuse $COMMON --install=$install $MIX --label=$arm-$round --out=$out/run > $out/run.out 2>&1 &
    runner=$!
    until grep -q '^\[bench\] running' $out/run.out 2>/dev/null; do kill -0 $runner 2>/dev/null || break; sleep 0.2; done
    sleep 25
    kill -0 $runner 2>/dev/null || { echo "RUN $arm-$round: the load ended before the pause ($out/run.out)"; exit 5; }
    echo "pause $(now)" > $out/pause.txt
    docker pause $M > /dev/null; sleep $PAUSE; docker unpause $M > /dev/null
    echo "unpause $(now)" >> $out/pause.txt
    if [ -n "$THROTTLE" ]; then
      docker update --cpus=$THROTTLE $M > /dev/null || { echo "RUN $arm-$round: docker update failed"; exit 6; }
      sleep $THROTTLED; docker update --cpus=0 $M > /dev/null
      echo "unthrottle $(now)" >> $out/pause.txt
    fi
    wait $runner; rc=$?
    $H down > /dev/null 2>&1
    echo "RUN $arm-$round rc=$rc $(date -u +%H:%M:%SZ)"
  done
done
