#!/usr/bin/env bash
#
# MongoDB's cost per commit below the knee: one fresh single-node MongoDB session per arm and round (100,000 accounts,
# primed, 1 GB heap, Kafka on), in it one fixed-rate E1 run with uniform keys (--rate, 8,000/s by default; 30 s of
# warm-up, 120 s measured). Around the run the session reads the store itself: mongod's CPU from its container's cgroup
# (`cpu.stat` `usage_usec`; the container has no CPU limit), `serverStatus()` (`opcounters`, `opLatencies`), and the log's
# and the snapshot's `$collStats` (`latencyStats`, `storageStats`) and the log's indexes: `store-before.json` and
# `store-after.json`; after the run, the keys of each of the log's indexes and the last document's `access`
# (`index-check.json`). Before them, 20 s with no load give mongod's own floor (`idle.json`). store-cost.py divides each
# delta by the log documents the window added, its commits.
#
# An arm changes the server's build (--install) and, after `up` and before the run, the log's two access indexes:
# `drop` drops `access_owner` and `access_space`, `create` creates them as main creates them (keyed by the tenant and
# the attribute, partial on the streams). The host creates them only at its start, so the change lasts the session.
# Every arm runs this checkout's harness and load generator; an install must be inside the checkout (results/e2e/ is
# ignored by git), as for 03-e1-hot-arms.sh.
#
#   04-mongo-store-cost.sh [--rate=8000] [--sequence="<round>:<arm> ..."] <arm>=<install>[,drop|create] ...
#
# The sessions run in --sequence's order, each <OUT_ROOT>/r<round>-<arm>/; without it, round 1 in the order given and
# round 2 reversed. A session that is void (`summarize.py --check`) or failed runs again at the end, at most MAX_RERUNS
# (2) of them; its attempt stays as r<round>-<arm>.void-1/. BENCH_OUT as for 01-single-node.sh.
set -uo pipefail

# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"
OUT_ROOT="${BENCH_OUT:-$REPO/test/benchmarks/results/e2e/mongo-store-cost}"
RATE=8000
SEQUENCE=""
WARM=30
MEASURE=120
LOG=wow_log__transfer__account
SNAP=wow_snapshot__transfer__account
while [ $# -gt 0 ]; do
  case $1 in
    --rate=*) RATE=${1#--rate=} ;;
    --sequence=*) SEQUENCE=${1#--sequence=} ;;
    *) break ;;
  esac
  shift
done
ARMS=("$@")
[ ${#ARMS[@]} -gt 0 ] || { say "no arms"; exit 2; }

mongo_eval() { docker exec wow-e2e-bench-mongo mongosh --quiet transfer --eval "$1"; }
cgroup_usec() { docker exec wow-e2e-bench-mongo sh -c "sed -n 's/^usage_usec //p' /sys/fs/cgroup/cpu.stat"; }

# The store's counters as one JSON line.
store_stats() {
  local usec
  usec=$(cgroup_usec)
  mongo_eval "
    const one = n => { const s = db.getCollection(n).aggregate([{\$collStats: {latencyStats: {}, storageStats: {}}}]).toArray()[0];
      const t = s.storageStats; return {latency: s.latencyStats, count: t.count, size: t.size, avgObjSize: t.avgObjSize,
      storageSize: t.storageSize, nindexes: t.nindexes, totalIndexSize: t.totalIndexSize, indexSizes: t.indexSizes}; };
    const ss = db.serverStatus();
    print(EJSON.stringify({at: Date.now() / 1000, cgroupUsec: $usec, opcounters: ss.opcounters, opLatencies: ss.opLatencies,
      log: one('$LOG'), snapshot: one('$SNAP'),
      logIndexes: db.getCollection('$LOG').getIndexes().map(i => ({name: i.name, key: i.key, partial: i.partialFilterExpression}))},
      {relaxed: true}));"
}

# After the run, out of its window: the keys each of the log's indexes holds (`validate`), its documents, and the
# `access` of the last document written, so whether a commit adds an entry to an access index can be read.
index_check() {
  mongo_eval "
    const v = db.$LOG.validate({full: false});
    const last = db.$LOG.find({version: {\$exists: true}}).sort({\$natural: -1}).limit(1).next();
    print(EJSON.stringify({documents: v.nrecords, keysPerIndex: v.keysPerIndex, valid: v.valid,
      lastAccess: last.access === undefined ? '(absent)' : last.access, lastFields: Object.keys(last)}, {relaxed: true}));"
}

indexes_for() {
  case $1 in
    create) mongo_eval "
      db.$LOG.createIndex({tenant_id: 1, 'access.owner': 1}, {name: 'access_owner', partialFilterExpression: {version: {\$exists: true}}});
      db.$LOG.createIndex({tenant_id: 1, 'access.space': 1}, {name: 'access_space', partialFilterExpression: {version: {\$exists: true}}});" ;;
    drop) mongo_eval "db.$LOG.dropIndex('access_owner'); db.$LOG.dropIndex('access_space');" ;;
    *) : ;;
  esac
}

spec_of() {
  local arm=$1 spec
  for spec in "${ARMS[@]}"; do
    if [ "${spec%%=*}" = "$arm" ]; then echo "${spec#*=}"; return 0; fi
  done
  return 1
}

session() {
  local round=$1 arm=$2 dir="$OUT_ROOT/r$1-$2" spec install action=""
  spec=$(spec_of "$arm") || { say "no arm $arm"; return 1; }
  install=${spec%%,*}
  if [ "$spec" != "$install" ]; then action=${spec#*,}; fi
  local C=(--backend=mongo --accounts="$ACCOUNTS" --heap=1g --install="$install")
  session_up "$dir" "${C[@]}" || { echo "r$round-$arm up-failed" >>"$OUT_ROOT/verdicts.txt"; return 1; }
  indexes_for "$action" >"$dir/indexes.log" 2>&1
  local idle0 idle1
  idle0=$(cgroup_usec)
  sleep 20
  idle1=$(cgroup_usec)
  printf '{"seconds":20,"usec":%s}\n' "$((idle1 - idle0))" >"$dir/idle.json"
  store_stats >"$dir/store-before.json"
  measure run "$dir/run" run.log "${C[@]}" --scenario=E1 --distribution=uniform --rate="$RATE" \
    --warm-up="$WARM" --measure="$MEASURE"
  store_stats >"$dir/store-after.json"
  index_check >"$dir/index-check.json"
  local verdict
  verdict=$(python3 -I "$LIB_DIR/summarize.py" --check "$dir/run" 2>&1) && verdict=ok
  echo "r$round-$arm $verdict" >>"$OUT_ROOT/verdicts.txt"
  session_down "$dir"
}

installs=()
for spec in "${ARMS[@]}"; do
  value=${spec#*=}
  installs+=("${value%%,*}")
done
preflight "${installs[@]}" || exit 1
mkdir -p "$OUT_ROOT"
record_environment "$OUT_ROOT/env-$RUN_STAMP"
if [ -z "$SEQUENCE" ]; then
  forward="" backward=""
  for spec in "${ARMS[@]}"; do
    forward="$forward 1:${spec%%=*}"
    backward="2:${spec%%=*} $backward"
  done
  SEQUENCE="$forward $backward"
fi
say "rate $RATE/s, warm-up $WARM s, measured $MEASURE s; sessions:$SEQUENCE"
for item in $SEQUENCE; do session "${item%%:*}" "${item#*:}" || say "r${item%%:*}-${item#*:} failed"; done
reruns=0
# A session's last verdict counts: one that ran again and passed is not run a third time.
for line in $(awk '{v[$1] = $NF} END {for (k in v) if (v[k] != "ok") print k}' "$OUT_ROOT/verdicts.txt" 2>/dev/null | sort); do
  if [ "$reruns" -ge "${MAX_RERUNS:-2}" ]; then say "left for later: $line"; continue; fi
  name=${line#r}
  mv "$OUT_ROOT/$line" "$OUT_ROOT/$line.void-1" 2>/dev/null || true
  wait_quiet || { say "the host stayed busy; $line not run again"; break; }
  say "running $line again"
  session "${name%%-*}" "${name#*-}" || say "$line failed again"
  reruns=$((reruns + 1))
done
say "store cost done; verdicts in $OUT_ROOT/verdicts.txt"
