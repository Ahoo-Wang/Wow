#!/bin/bash
# The layout experiment: per layout, the collection created, then 8 workers inserting 400,000 documents at once;
# the wall time and mongod's CPU (/proc/1/stat, utime + stime) of the inserts, and the collection's sizes.
set -uo pipefail
C=wow-e2e-bench-mongo
D=$(cd "$(dirname "$0")" && pwd)
docker cp "$D/layout.js" $C:/tmp/layout.js
ticks() { docker exec $C sh -c 'awk "{print \$14+\$15}" /proc/1/stat'; }
for layout in ${LAYOUTS:-A B C C B A}; do
  docker exec -e LAYOUT=$layout -e SETUP=1 $C mongosh --quiet /tmp/layout.js
  t0=$(ticks); s0=$(date +%s.%N 2>/dev/null || python3 -c 'import time;print(time.time())')
  for w in 0 1 2 3 4 5 6 7; do docker exec -e LAYOUT=$layout -e WORKER=$w -e WORKERS=8 $C mongosh --quiet /tmp/layout.js & done
  wait
  s1=$(python3 -c 'import time;print(time.time())'); t1=$(ticks)
  sizes=$(docker exec $C mongosh --quiet --eval "const s=db.getSiblingDB('layouts').getCollection('log_$layout').stats(); print(s.count, s.size, s.totalIndexSize, s.storageSize)")
  python3 -c "import sys; a=sys.argv; print(f'{a[1]} wall {float(a[3])-float(a[2]):.2f}s cpu {(int(a[5])-int(a[4]))/100:.2f}s cpu/insert {(int(a[5])-int(a[4]))*10000/400000:.1f}us count/data/index/storage {a[6:]}')" $layout $s0 $s1 $t0 $t1 $sizes
done
