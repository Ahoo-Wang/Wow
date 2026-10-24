#!/bin/bash
# One PostgreSQL session: E1 at 1,906/s fresh, then a closed loop to 512, then E1 again; table statistics between.
set -uo pipefail
S=${S:?the working directory holding the installs and wt-bench/}
B=$S/wt-bench/test/benchmarks/harness/bench.sh
O=$S/bloat; mkdir -p $O
C="--version=v10 --backend=postgres --accounts=100000 --install=$S/D2"
stats() {
  echo "## $1 $(date +%T)" >> $O/stats.txt
  docker exec wow-e2e-bench-postgres psql -U postgres -d transfer -X -q -c "select relname, n_live_tup, n_dead_tup, n_tup_ins, n_tup_upd, n_tup_hot_upd, autovacuum_count, autoanalyze_count, pg_size_pretty(pg_relation_size(relid)) as heap, pg_size_pretty(pg_indexes_size(relid)) as indexes, pg_size_pretty(pg_total_relation_size(relid)) as total from pg_stat_user_tables where relname like 'wow_snapshot__%' or relname like 'wow_log__%account' or relname = 'wow_checkpoint' order by relname" >> $O/stats.txt 2>&1
}
$B up $C --prime --seed-concurrency=128 --out=$O/up > $O/up.log 2>&1 || { echo up failed; exit 1; }
stats after-prime
$B run --reuse $C --scenario=E1 --rate=1906 --warm-up=30 --measure=60 --out=$O/e1-fresh > $O/e1-fresh.log 2>&1
stats after-e1-fresh
$B saturate --reuse $C --scenario=E1 --max-concurrency=512 --sweep --out=$O/sweep > $O/sweep.log 2>&1
stats after-sweep
$B run --reuse $C --scenario=E1 --rate=1906 --warm-up=30 --measure=60 --out=$O/e1-after > $O/e1-after.log 2>&1
stats after-e1-after
$B down > /dev/null 2>&1
echo "$(date +%T) done"
