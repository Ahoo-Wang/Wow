#!/bin/bash
# The D277 layout probe (roadmap §6 item 18): a fresh single-member replica set in a container, then per layout, in
# the order LAYOUTS gives (each pair alternating), the collection created and each step run by 8 workers at once;
# the wall time and mongod's CPU (/proc/1/stat, utime + stime) of each step, and the collection's sizes.
set -uo pipefail
C=wow-d277-probe
D=$(cd "$(dirname "$0")" && pwd)
docker rm -f -v $C >/dev/null 2>&1
docker run -d --name $C mongo:7.0 mongod --replSet rs0 --bind_ip_all --wiredTigerCacheSizeGB 2 >/dev/null
until docker exec $C mongosh --quiet --eval 'try { rs.status().ok } catch (e) { rs.initiate().ok }' 2>/dev/null | grep -q 1; do sleep 1; done
until docker exec $C mongosh --quiet --eval 'db.hello().isWritablePrimary' 2>/dev/null | grep -q true; do sleep 1; done
docker cp "$D/layout.js" $C:/tmp/layout.js
ticks() { docker exec $C sh -c 'awk "{print \$14+\$15}" /proc/1/stat'; }
now() { python3 -c 'import time;print(time.time())'; }
for layout in ${LAYOUTS:-L0 L1 L1 L0 S0 S1 S1 S0}; do
  docker exec -e LAYOUT=$layout -e STEP=setup $C mongosh --quiet /tmp/layout.js
  case $layout in L*) steps="insert read"; ops=${N:-400000};; *) steps="insert update read"; ops=${ACCOUNTS:-200000};; esac
  for step in $steps; do
    [ $step = read ] && [ ${layout:0:1} = L ] && ops=${ACCOUNTS:-200000}
    t0=$(ticks); s0=$(now)
    for w in 0 1 2 3 4 5 6 7; do docker exec -e LAYOUT=$layout -e STEP=$step -e WORKER=$w -e WORKERS=8 $C mongosh --quiet /tmp/layout.js & done
    wait
    s1=$(now); t1=$(ticks)
    python3 -c "import sys; a=sys.argv; print(f'{a[1]} {a[2]:6} wall {float(a[4])-float(a[3]):6.2f}s cpu/op {(int(a[6])-int(a[5]))*10000/int(a[7]):6.1f}us')" $layout $step $s0 $s1 $t0 $t1 $ops
  done
  docker exec $C mongosh --quiet --eval "const s=db.getSiblingDB('d277').getCollection('c_$layout').stats(); print('$layout sizes count', s.count, 'data', s.size, 'indexes', s.totalIndexSize, JSON.stringify(s.indexSizes))"
done
docker rm -f -v $C >/dev/null
