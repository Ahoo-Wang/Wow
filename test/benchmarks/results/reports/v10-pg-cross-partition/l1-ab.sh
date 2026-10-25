#!/bin/bash
# Roadmap §6 item 15's prototype: PostgreSQL's cross-partition group commit on L1 (the kernel and the store in one
# JVM, through the append queue), one build, merging off (A) against on (B), arms A B B A, E1 and E3 at concurrency
# 64 and 256, 100,000 accounts, the store on disk. Beside each run, the PostgreSQL container's CPU, its commits and
# its WAL syncs are sampled every second; the last sample before the run ends gives the run's totals.
set -uo pipefail
export JAVA_HOME=${JAVA_HOME:?a JDK 21}
S=${S:?scratch}; B=${BENCH:?bench.sh}; I=${INSTALL:?install}
O=$S/pgx/l1; mkdir -p "$O"
sample() {
  while true; do
    v=$(docker exec wow-e2e-bench-postgres sh -c "grep usage_usec /sys/fs/cgroup/cpu.stat | cut -d' ' -f2; psql -U postgres -tAc \"SELECT (SELECT sum(xact_commit) FROM pg_stat_database), (SELECT wal_sync FROM pg_stat_wal)\"" 2>/dev/null | tr '\n|' '  ')
    [ -n "$v" ] && echo "$(date +%s) $v" >> "$1"
    sleep 1
  done
}
for c in 256 64; do for s in E1 E3; do n=0
  for arm in A B B A; do n=$((n+1))
    case $arm in A) export SPRING_APPLICATION_JSON='{"wow.log.append-queue.merge":false}';; B) export SPRING_APPLICATION_JSON='{"wow.log.append-queue.merge":true}';; esac
    R=$O/c$c-$s-$n-$arm; mkdir -p "$R"
    sample "$R/pg.samples" & SP=$!
    $B component --backend=postgres --scenario=$s --accounts=100000 --ops=40000 --warm-up-ops=4000 --concurrency=$c --install=$I --out="$R" > "$R/run.log" 2>&1
    kill $SP 2>/dev/null; wait $SP 2>/dev/null
    echo "$(date +%T) c$c $s $n $arm done"
  done
done; done
