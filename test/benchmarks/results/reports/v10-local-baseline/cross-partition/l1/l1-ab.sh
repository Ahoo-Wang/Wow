#!/bin/bash
# L1 ABAB of the cross-partition group commit spike on MongoDB: one build, the merger off (A) and on (B), E1 at
# concurrency 16, 64 and 256, order A B B A at each; mongod's CPU over the whole run (sampled each second).
set -uo pipefail
export JAVA_HOME=${JAVA_HOME:?a JDK 21}
S=${S:?the scratch directory}
B=$(git rev-parse --show-toplevel)/test/benchmarks/harness/bench.sh
O=$S/xpart/l1; mkdir -p $O
for c in ${CONCURRENCIES:-16 64 256}; do n=0; for arm in A B B A; do n=$((n+1))
  D=$O/c$c-$n-$arm; mkdir -p $D
  if [ $arm = B ]; then export WOW_SPIKE_MERGE=true; else unset WOW_SPIKE_MERGE; fi
  ( last=0; while true; do v=$(docker exec wow-e2e-bench-mongo sh -c 'awk "{print \$14+\$15}" /proc/1/stat' 2>/dev/null) && last=$v && echo $last > $D/mongod-ticks; sleep 1; done ) & sampler=$!
  $B component --backend=mongo --scenario=E1 --accounts=10000 --ops=40000 --warm-up-ops=4000 --concurrency=$c --install=$S/ab/SX --out=$D > $D/component.log 2>&1
  kill $sampler 2>/dev/null; wait $sampler 2>/dev/null
  echo "$(date +%T) c$c $n $arm done"
done; done
