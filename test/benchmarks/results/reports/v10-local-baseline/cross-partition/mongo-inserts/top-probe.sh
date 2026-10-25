#!/bin/bash
# Where MongoDB's time goes under E1: `top` (time per collection and kind, µs) and the command counters, read before
# and after a fixed-rate E1 run on a primed session (warm-up and measured phase both inside the window).
set -uo pipefail
export JAVA_HOME=${JAVA_HOME:?a JDK 21}
S=${S:?the scratch directory}
B=$(git rev-parse --show-toplevel)/test/benchmarks/harness/bench.sh
O=$S/mongo-ops/run; mkdir -p $O
C="--version=v10 --backend=mongo --accounts=100000 --install=$S/ab/SB"
snap() { docker exec wow-e2e-bench-mongo mongosh --quiet --eval 'printjson({top: db.adminCommand({top: 1}).totals, cmds: db.serverStatus().metrics.commands, lat: db.serverStatus().opLatencies, ops: db.serverStatus().opcounters})' > "$O/$1.json"; }
$B up $C --prime --seed-concurrency=128 --out=$O/up > $O/up.log 2>&1 || exit 1
snap before
$B run --reuse $C --scenario=E1 --rate=${RATE:-1720} --warm-up=30 --measure=60 --out=$O/e1 > $O/e1.log 2>&1
snap after
$B down > /dev/null 2>&1
echo done
