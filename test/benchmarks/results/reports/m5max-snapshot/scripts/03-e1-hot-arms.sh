#!/usr/bin/env bash
#
# The controlled check of 1-S-postgres's e1-hot sweep: one fresh single-node PostgreSQL session per arm (100,000
# accounts, primed, 1 GB heap, the same seed as 01-single-node.sh), in it only the e1-hot sweep, 1 to 4,096 clients on one
# hot account. Every arm runs this checkout's harness and load generator; an arm changes only the server's
# distribution (--install, another commit's build) or its JDK (--server-java). With pg_stat_statements loaded, each
# sweep keeps PostgreSQL's statements over it (pg-statements.txt, lib.sh), and the session the hot account's version
# every 5 s (hot-version.tsv: epoch, version), read from the account's log table, so a step's version at its start and
# end can be told.
#
#   03-e1-hot-arms.sh <name>=<install>[,<server JDK home>] ...     (the arms, in the order they run)
#
# Each arm's session is <raw root>/e1-hot-arms/<n>-<name>/. WOW_REPO and BENCH_OUT as for 01-single-node.sh.
set -uo pipefail

# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"
OUT_ROOT="${BENCH_OUT:-$REPO/test/benchmarks/results/e2e/m5max-snapshot}"
export WOW_BENCH_PG_PRELOAD=pg_stat_statements
HOT_ACCOUNT=acc-0000000
VERSIONS=""

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

versions_stop() {
  if [ -n "$VERSIONS" ]; then
    kill "$VERSIONS" 2>/dev/null
    wait "$VERSIONS" 2>/dev/null
  fi
  VERSIONS=""
}

arm() {
  local n=$1 name=$2 install=$3 java=$4 dir="$OUT_ROOT/e1-hot-arms/$1-$2"
  local C=(--backend=postgres --accounts="$ACCOUNTS" --heap=1g --install="$install")
  if [ -n "$java" ]; then C+=(--server-java="$java"); fi
  session_up "$dir" "${C[@]}" || return 1
  versions_start "$dir/hot-version.tsv"
  measure_sweep "$dir/e1-hot-sweep" "${C[@]}" --scenario=E1 --distribution=hot --hot-keys=1 "${SWEEP[@]}"
  versions_stop
  session_down "$dir"
}

installs=()
for spec in "$@"; do
  value=${spec#*=}
  installs+=("${value%%,*}")
done
preflight "$WOW_INSTALL" "${installs[@]}" || exit 1
record_environment "$OUT_ROOT/e1-hot-arms"
n=0
for spec in "$@"; do
  n=$((n + 1))
  name=${spec%%=*}
  value=${spec#*=}
  install=${value%%,*}
  java=""
  if [ "$value" != "$install" ]; then java=${value#*,}; fi
  arm "$n" "$name" "$install" "$java" || say "the $name session failed"
done
say "e1-hot arms done"
