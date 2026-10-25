#!/bin/bash
# The same layout A at batch sizes 1, 8 and 64 (8 workers, w:majority): mongod's CPU per inserted document.
set -uo pipefail
C=wow-e2e-bench-mongo
D=$(cd "$(dirname "$0")" && pwd)
docker cp "$D/layout.js" $C:/tmp/layout.js
ticks() { docker exec $C sh -c 'awk "{print \$14+\$15}" /proc/1/stat'; }
for spec in ${SPECS:-1:40000 8:160000 64:400000 64:400000 8:160000 1:40000}; do
  IFS=: read -r batch n <<<"$spec"
  docker exec -e LAYOUT=A -e SETUP=1 $C mongosh --quiet /tmp/layout.js
  t0=$(ticks); s0=$(python3 -c 'import time;print(time.time())')
  for w in 0 1 2 3 4 5 6 7; do docker exec -e LAYOUT=A -e BATCH=$batch -e N=$n -e WORKER=$w -e WORKERS=8 $C mongosh --quiet /tmp/layout.js & done
  wait
  s1=$(python3 -c 'import time;print(time.time())'); t1=$(ticks)
  python3 -c "import sys; a=sys.argv; n=int(a[2]); print(f'batch {a[1]:>2} docs {n} wall {float(a[4])-float(a[3]):.1f}s inserts/s {n/(float(a[4])-float(a[3])):.0f} cpu/doc {(int(a[6])-int(a[5]))*10000/n:.1f}us')" $batch $n $s0 $s1 $t0 $t1
done
