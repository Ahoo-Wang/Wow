#!/bin/bash
set -uo pipefail
S=${S:?the working directory holding the installs A/ and C/ and wt-bench/}
B=$S/wt-bench/test/benchmarks/harness/bench.sh
arms=${ARMS:-A C}
for round in 1 2; do for arm in $arms; do for sc in E1 E3; do
  O=$S/l1/$round-$arm-$sc
  echo "$(date +%T) $round $arm $sc"
  $B component --backend=mongo --scenario=$sc --accounts=100000 --concurrency=16 --install=$S/$arm --out=$O > $O.log 2>&1 || echo failed
done; done; done
echo "$(date +%T) done"
