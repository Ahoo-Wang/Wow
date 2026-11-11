#!/usr/bin/env bash
#
# A controlled check of one of 1-S-postgres's sweeps, by default e1-hot: one fresh single-node PostgreSQL session per
# arm (100,000 accounts, primed, 1 GB heap, the same seed as 01-single-node.sh), in it only that sweep, 1 to 4,096
# clients: --scenario (E1 by default) with --keys (hot by default, one hot account; uniform or zipf). Every arm runs this checkout's harness and load generator; an arm changes only the server's
# distribution (--install, another commit's build) or its JDK (--server-java). With pg_stat_statements loaded, each
# sweep keeps PostgreSQL's statements over it (pg-statements.txt, lib.sh), and with hot keys the session keeps the hot
# account's version every 5 s (hot-version.tsv: epoch, version), read from the account's log table, so a step's
# version at its start and end can be told. After the sweep, its measurement over, the session keeps the plan of the appends' version read on the
# hot account at its last version (EXPLAIN ANALYZE, BUFFERS) and the tables' statistics before and after it
# (pg_stat_user_tables): explain.txt and tables.txt. An install must be inside this checkout (results/e2e/ is ignored
# by git): a server started from anywhere else reads as another JVM (lib.sh's foreign_jvms), and every step as void.
#
#   03-e1-hot-arms.sh [--scenario=E1] [--keys=hot] <name>=<install>[,<server JDK home>] ...     (the arms, in order)
#
# Each arm's session is <raw root>/<scenario>-<keys>-arms/<n>-<name>/ (e1-hot-arms/ by default), its sweep
# <scenario>-<keys>-sweep/, n counting from ARM_FROM (1 by default). WOW_REPO and BENCH_OUT as for 01-single-node.sh.
set -uo pipefail

# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"
OUT_ROOT="${BENCH_OUT:-$REPO/test/benchmarks/results/e2e/m5max-snapshot}"
export WOW_BENCH_PG_PRELOAD=pg_stat_statements
HOT_ACCOUNT=acc-0000000
VERSIONS=""
SCENARIO=E1
KEYS=hot
while [ $# -gt 0 ]; do
  case $1 in
    --scenario=*) SCENARIO=${1#--scenario=} ;;
    --keys=*) KEYS=${1#--keys=} ;;
    *) break ;;
  esac
  shift
done
case $KEYS in
  hot) DISTRIBUTION=(--distribution=hot --hot-keys=1) ;;
  uniform | zipf) DISTRIBUTION=(--distribution="$KEYS") ;;
  *) say "--keys is hot, uniform or zipf, not $KEYS"; exit 2 ;;
esac
# The check's name in its directories: e1-hot, e3-uniform.
CHECK="$(echo "$SCENARIO" | tr '[:upper:]' '[:lower:]')-$KEYS"

psql_value() { docker exec wow-e2e-bench-postgres psql -U postgres -d transfer -XAtq -c "$1" 2>/dev/null; }

# Samples the hot account's version into $1 every 5 s until versions_stop. The log table is the account's, whatever the
# commit names it: the one not a partition whose name starts wow_log and names the account.
versions_start() {
  local table
  table=$(psql_value "select relname from pg_class where relname like 'wow_log%account%' and relkind in ('r', 'p')
    and not relispartition order by length(relname) limit 1")
  say "the hot account's versions from ${table:-no table}"
  printf 'epoch\tversion\n' >"$1"
  if [ -z "$table" ]; then return 0; fi
  (
    while :; do
      printf '%s\t%s\n' "$(date +%s)" \
        "$(psql_value "select coalesce(max(version), 0) from $table where aggregate_id = '$HOT_ACCOUNT'")" >>"$1"
      sleep 5
    done
  ) &
  VERSIONS=$!
}

# The tables' statistics, titled $2, appended to $1.
tables() {
  { echo "## $2 $(date '+%Y-%m-%dT%H:%M:%S%z')"
    docker exec wow-e2e-bench-postgres psql -U postgres -d transfer -X -c "select relname, n_tup_ins, n_tup_upd,
      n_tup_hot_upd, n_live_tup, n_dead_tup, autovacuum_count from pg_stat_user_tables where relname like 'wow_%'
      order by relname" 2>&1; } >>"$1"
}

# The plan of the version read the appends make (PostgresEventLogTables' appendGroup), on acc-0000000
# (the hot account with hot keys), into $1.
explain() {
  local tenant
  tenant=$(psql_value "select tenant_id from wow_log__transfer__account where aggregate_id = '$HOT_ACCOUNT' limit 1")
  docker exec wow-e2e-bench-postgres psql -U postgres -d transfer -X \
    -c "select max(version) from wow_log__transfer__account where aggregate_id = '$HOT_ACCOUNT'" \
    -c "\\d wow_log__transfer__account" \
    -c "EXPLAIN (ANALYZE, BUFFERS) WITH input (ord, branch, aggregate_id, tenant_id) AS
      (VALUES (0, 0, '$HOT_ACCOUNT'::text, '$tenant'::text)) SELECT input.ord, coalesce((SELECT max(log.version)
      FROM wow_log__transfer__account AS log WHERE log.aggregate_id = input.aggregate_id
      AND log.tenant_id = input.tenant_id), 0) AS version FROM input WHERE input.branch = 0" >"$1" 2>&1
}

versions_stop() {
  if [ -n "$VERSIONS" ]; then
    kill "$VERSIONS" 2>/dev/null
    wait "$VERSIONS" 2>/dev/null
  fi
  VERSIONS=""
}

arm() {
  local n=$1 name=$2 install=$3 java=$4 dir="$OUT_ROOT/$CHECK-arms/$1-$2"
  local C=(--backend=postgres --accounts="$ACCOUNTS" --heap=1g --install="$install")
  if [ -n "$java" ]; then C+=(--server-java="$java"); fi
  session_up "$dir" "${C[@]}" || return 1
  if [ "$KEYS" = hot ]; then versions_start "$dir/hot-version.tsv"; fi
  tables "$dir/tables.txt" "before the sweep"
  measure_sweep "$dir/$CHECK-sweep" "${C[@]}" --scenario="$SCENARIO" "${DISTRIBUTION[@]}" "${SWEEP[@]}"
  versions_stop
  tables "$dir/tables.txt" "after the sweep"
  explain "$dir/explain.txt"
  session_down "$dir"
}

installs=()
for spec in "$@"; do
  value=${spec#*=}
  installs+=("${value%%,*}")
done
preflight "$WOW_INSTALL" "${installs[@]}" || exit 1
record_environment "$OUT_ROOT/$CHECK-arms"
n=$((${ARM_FROM:-1} - 1))
for spec in "$@"; do
  n=$((n + 1))
  name=${spec%%=*}
  value=${spec#*=}
  install=${value%%,*}
  java=""
  if [ "$value" != "$install" ]; then java=${value#*,}; fi
  arm "$n" "$name" "$install" "$java" || say "the $name session failed"
done
say "$CHECK arms done"
