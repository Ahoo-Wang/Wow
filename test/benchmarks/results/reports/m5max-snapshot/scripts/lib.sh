#!/usr/bin/env bash
#
# The helpers of this report's scripts (bash 3.2, macOS): the checks before a run, the harness's sessions, and a
# record of the host around each measurement, so a step that another process disturbed can be told and rerun.
#
# A run starts from a clean worktree at the commit it measures (a fixed-rate run records `git status`), and writes its
# raw output under test/benchmarks/results/e2e/ (ignored by git); `curate` then copies into the report's directory
# what reproduces its conclusions. The node logs stay out of it: each session keeps a digest of their warnings.
#
# A session is the unit of a rerun: its measurements run one after the other on the same seeded and primed stores, so
# one of them alone in a new session would not run on the same state. A session run again moves the directory of its
# first run to <raw root>/replaced/<time>/, which `curate` leaves out. A measurement with a step that is void or
# disturbed (`summarize.py --check`) runs again in its session once the host is quiet; the attempt stays beside it as
# <measurement>.void-<n>/.
#
# No other JVM may run but those in ALLOW_JVM_PIDS (a list of pids, each an idle JVM in place before the run): a step
# during which another JVM ran is void, and so is one in which an allowed JVM used VOID_JVM_CPU percent of a core or
# more in two samples in a row, or an allowed Gradle daemon's log records a build request (someone started a build).
# One sample over VOID_JVM_CPU is a spike, recorded in allowed-jvms.txt and not void (`summarize.py`).
#
#   host.txt       the load averages, swap, containers and the busiest other processes, before and after a measurement
#   host-load.tsv  sampled every SAMPLE_SECONDS during it: the load averages, the CPU of the processes that are not
#                  this benchmark's (percent of one core; OrbStack's VM, which runs the stores, counts as this
#                  benchmark's), the containers that are not the benchmark's, with their CPU, the CPU of every
#                  process on the host, OrbStack's VM among them, and of the load generator's busiest thread (percent
#                  of one core): a generator whose HTTP client's one selector thread is busy all the time is the bound,
#                  however few cores it uses in all; then the CPU of the JVMs in ALLOW_JVM_PIDS (percent of one core)
#                  and the pids of the JVMs that are neither this benchmark's nor allowed; last, each of the
#                  benchmark's containers' cumulative CPU (`name=usec`, its cgroup's `usage_usec`), from which
#                  summarize.py gives each container's cores over a step
#   mongo-stats.tsv  with the session's MongoDB, its counters every second over the measurement (`mongo_stats`):
#                  the journal's syncs and their time, the write concern's majority waits and their time, the writes'
#                  and transactions' operations and latency, the transactions aborted, the retried commands, the write
#                  conflicts, all cumulative, and the write tickets in use; summarize.py gives each step's waits
#   jfr/           with STEP_JFR set, a flight recording of each server's measured seconds of each closed-loop step,
#                  node-<n>-c<clients>.jfr, cut from one recording per server by `jcmd JFR.dump begin=-<step>s` as
#                  the step's line arrives (nodes.txt maps n to the server's pid and port)
#   saturate.log   a sweep's output, each line stamped with the epoch second it was printed: a step's line marks
#                  the end of its measured phase
#   run.log        a fixed-rate run's output, stamped the same way
#   allowed-jvms.txt  written by `summarize.py --check`: the spikes of the allowed JVMs, what their daemon logs have
#                  around each, and the commands those daemons received
#   pg-statements.txt  with WOW_BENCH_PG_PRELOAD=pg_stat_statements exported before the session's `up`: PostgreSQL's
#                  statements by calls over the measurement, from its settle on (reset before it, as the harness resets
#                  them only for a profiled session)

# Every run is on JDK 25, the servers and the load generator alike, whatever JAVA_HOME the calling shell has (this
# machine's default is JDK 21): as m5max-axon's run-all.sh does.
JAVA_HOME="$(/usr/libexec/java_home -v 25)"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The checkout measured: WOW_REPO, else the one these scripts are in.
REPO="${WOW_REPO:-$(git -C "$LIB_DIR" rev-parse --show-toplevel)}"
B="$REPO/test/benchmarks/harness/bench.sh"
LOAD="$REPO/test/benchmarks/load/build/install/benchmark-load"
WOW_INSTALL="$REPO/test/benchmarks/server/build/install/benchmark-server"
AXON_INSTALL="$REPO/test/benchmarks/axon-server/build/install/axon-server"
SESSION_FILE="$REPO/test/benchmarks/results/e2e/.session"
ACCOUNTS=100000
# Closed-loop sweeps: 1, 4, 16, 64, 256, 1,024 and 4,096 clients, 10 s of warm-up and 30 s measured a step. The load
# generator ends a sweep after a step that failed more than 1% of its commands.
SWEEP=(--sweep --growth=4 --max-concurrency=4096 --step=30 --warm-up=10)
SAMPLE_SECONDS=10
# The JFR settings of each closed-loop step's recording (`default` or `profile`), or empty for none. A recording costs
# the servers some CPU (about 1% with `default`, more with `profile`), so a sweep recorded with it is not compared with
# one without; environment.txt records the setting.
STEP_JFR="${STEP_JFR:-}"
# A measurement voided or disturbed runs again at most RERUNS times, once the host was quiet for QUIET_SAMPLES samples in
# a row: no JVM but this benchmark's and the allowed ones, those under VOID_JVM_CPU (stricter than a step's rule, which
# lets one sample over it pass), the other processes under
# DISTURBED_CPU and every other container under CONTAINER_CPU (percent of one core, as in summarize.py). It waits
# QUIET_MAX_SECONDS at most.
RERUNS=2
QUIET_SAMPLES=6
QUIET_MAX_SECONDS=1800
VOID_JVM_CPU=5
DISTURBED_CPU=200
CONTAINER_CPU=50
# The harness's public and internal ports, PostgreSQL's, MongoDB's and Kafka's.
PORTS="18080 18081 18082 19090 19091 19092 5432 27017 9092"
SESSION_OUT=""
SAMPLER=""
RUN_STAMP=$(date +%Y%m%d-%H%M%S)

say() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# The machine stays awake while the script runs.
caffeinate -dimsu -w $$ &

# Fails unless the worktree is clean (ALLOW_DIRTY=true skips that), the builds $@ and the load generator are there,
# nothing holds the harness's ports or containers (another benchmark would share the CPUs and the stores), and no other
# JVM runs, a Gradle daemon among them, but those in ALLOW_JVM_PIDS.
preflight() {
  if [ -n "$(git -C "$REPO" status --porcelain)" ] && [ "${ALLOW_DIRTY:-false}" != true ]; then
    say "$REPO has changes; the runs would record them (ALLOW_DIRTY=true runs anyway)"
    return 1
  fi
  local install
  for install in "$LOAD" "$@"; do
    if [ ! -x "$install/bin/$(basename "$install")" ]; then say "no build at $install"; return 1; fi
  done
  local port
  for port in $PORTS; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then say "port $port is in use"; return 1; fi
  done
  if [ -n "$(docker ps -aq --filter name=wow-e2e-bench)" ]; then say "benchmark containers exist"; return 1; fi
  local jvms
  jvms=$(foreign_jvms)
  if [ -n "$jvms" ]; then
    say "other JVMs run (ALLOW_JVM_PIDS lists the pids of idle ones allowed):"
    echo "$jvms"
    return 1
  fi
}

# The JVMs that are neither this benchmark's (started from $REPO) nor in ALLOW_JVM_PIDS: "<pid> <command>".
foreign_jvms() {
  local known
  known=" $(pgrep -f "$REPO/" | tr '\n' ' ') ${ALLOW_JVM_PIDS:-} "
  ps -A -o pid=,command= | awk -v known="$known" '($2 ~ /\/java$/ || $2 == "java") && !index(known, " " $1 " ")' |
    cut -c1-160
}

# The CPU of the JVMs in ALLOW_JVM_PIDS that still run, percent of one core; empty when none does.
allowed_jvm_cpu() {
  if [ -n "${ALLOW_JVM_PIDS:-}" ]; then
    # shellcheck disable=SC2086
    ps -o %cpu= -p "$(echo $ALLOW_JVM_PIDS | tr ' ' ',')" 2>/dev/null | awk '{s += $1; n++} END {if (n) printf "%.1f", s}'
  fi
}

# The CPU of the busiest container that is not the benchmark's, percent of one core.
foreign_container_cpu() {
  docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' 2>/dev/null | grep -v '^wow-e2e-bench-' |
    awk '{v = $2; sub(/%/, "", v); if (v + 0 > m) m = v + 0} END {printf "%.0f", m}'
}

# Waits until the host was quiet for QUIET_SAMPLES samples in a row (see RERUNS); fails after QUIET_MAX_SECONDS.
wait_quiet() {
  local quiet=0 waited=0
  while [ "$quiet" -lt "$QUIET_SAMPLES" ]; do
    if [ "$waited" -ge "$QUIET_MAX_SECONDS" ]; then return 1; fi
    if [ -z "$(foreign_jvms)" ] &&
      awk -v a="$(allowed_jvm_cpu)" -v limit="$VOID_JVM_CPU" 'BEGIN {exit !(a + 0 < limit)}' &&
      [ "$(foreign_processes | awk '{s += $1} END {printf "%.0f", s}')" -lt "$DISTURBED_CPU" ] &&
      [ "$(foreign_container_cpu)" -lt "$CONTAINER_CPU" ]; then
      quiet=$((quiet + 1))
    else
      quiet=0
    fi
    sleep "$SAMPLE_SECONDS"
    waited=$((waited + SAMPLE_SECONDS))
  done
}

# The commit, machine, container engine and JDK, appended to $1/environment.txt.
record_environment() {
  mkdir -p "$1"
  {
    echo "## $(basename "$0") $(date '+%Y-%m-%dT%H:%M:%S%z')"
    echo "commit: $(git -C "$REPO" rev-parse HEAD)"
    echo "changed paths: $(git -C "$REPO" status --porcelain | wc -l | tr -d ' ')"
    echo "machine: $(sysctl -n hw.model), $(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.ncpu) cores" \
      "($(sysctl -n hw.perflevel0.logicalcpu) $(sysctl -n hw.perflevel0.name)" \
      "+ $(sysctl -n hw.perflevel1.logicalcpu) $(sysctl -n hw.perflevel1.name)), $(($(sysctl -n hw.memsize) / 1073741824)) GB"
    echo "macOS: $(sw_vers -productVersion) ($(sw_vers -buildVersion))"
    echo "engine: OrbStack $(orb version 2>/dev/null | sed -n "s/^Version: //p")"
    echo "docker: $(docker version --format '{{.Server.Version}}'), $(docker info --format '{{.NCPU}} CPUs, {{.MemTotal}} bytes')"
    echo "jdk: $("$JAVA_HOME/bin/java" -version 2>&1 | sed -n 2p)"
    echo "step JFR: ${STEP_JFR:-off}"
    echo "allowed JVMs (pid, %cpu, rss KB, started, command): ${ALLOW_JVM_PIDS:-none}"
    if [ -n "${ALLOW_JVM_PIDS:-}" ]; then
      # shellcheck disable=SC2086
      ps -o pid=,%cpu=,rss=,lstart=,command= -p "$(echo $ALLOW_JVM_PIDS | tr ' ' ',')" | cut -c1-200 | sed 's/^/  /'
    fi
  } >>"$1/environment.txt"
}

# The processes that are not this benchmark's, the busiest first: "<%cpu> <pid> <name>". Not this benchmark's: not
# started from $REPO (the servers, the load generator, the harness) and not OrbStack's (its VM runs the stores).
foreign_processes() {
  local ours
  ours=" $(pgrep -f "$REPO/" | tr '\n' ' ') "
  ps -A -o %cpu=,pid=,comm= | awk -v ours="$ours" '
    {
      if (index(ours, " " $2 " ")) next
      name = $0; sub(/^ *[0-9.]+ +[0-9]+ +/, "", name)
      if (name ~ /OrbStack/) next
      n = split(name, parts, "/"); print $1, $2, parts[n]
    }' | sort -rn
}

# The CPU of the load generator's busiest thread, percent of one core; empty while no load generator runs. Its JVM is
# the one whose class path is the install's lib/ (the launcher script execs it).
generator_thread() {
  local pid
  pid=$(pgrep -f "benchmark-load/lib/" | head -1)
  # The first thread's line also names the user and the terminal before its %CPU.
  if [ -n "$pid" ]; then
    ps -M -p "$pid" | awk 'NR == 2 {max = $4 + 0} NR > 2 && $2 + 0 > max {max = $2 + 0} END {printf "%.0f", max}'
  fi
}

# One block of the host's state, titled $2, appended to $1.
host_record() {
  {
    echo "## $2 $(date '+%Y-%m-%dT%H:%M:%S%z')"
    echo "uptime: $(uptime)"
    echo "swap: $(sysctl -n vm.swapusage)"
    echo "containers: $(docker ps --format '{{.Names}}' | tr '\n' ' ')"
    echo "busiest other processes (% of one core):"
    foreign_processes | head -8 | sed 's/^/  /'
    echo "allowed JVMs (% of one core): ${ALLOW_JVM_PIDS:-none} $(allowed_jvm_cpu)"
    echo "other JVMs:"
    foreign_jvms | sed 's/^/  /'
  } >>"$1"
}

# Each of the benchmark's containers' cumulative CPU, `name=usec` comma-separated: its cgroup's `usage_usec` (the
# containers have no CPU limit).
bench_container_usage() {
  local name
  for name in $(docker ps --format '{{.Names}}' --filter name=wow-e2e-bench-); do
    printf '%s=%s\n' "$name" "$(docker exec "$name" sh -c "sed -n 's/^usage_usec //p' /sys/fs/cgroup/cpu.stat" 2>/dev/null)"
  done | grep -E '=[0-9]+$' | paste -sd, -
}

# Samples the host into $1 every SAMPLE_SECONDS until sampler_stop.
sampler_start() {
  printf 'epoch\tload1\tload5\tload15\tforeignCpu\ttopForeign\tforeignContainers\thostCpu\tgeneratorThread\tallowedJvmCpu\tforeignJvms\tbenchContainersUsec\n' >"$1"
  (
    while :; do
      procs=$(foreign_processes)
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date +%s)" \
        "$(sysctl -n vm.loadavg | tr -d '{}' | awk '{print $1 "\t" $2 "\t" $3}')" \
        "$(echo "$procs" | awk '{s += $1} END {printf "%.0f", s}')" \
        "$(echo "$procs" | head -3 | awk '{c = $1; $1 = ""; $2 = ""; sub(/^ +/, ""); printf "%s%s %.0f", (NR > 1 ? ", " : ""), $0, c}')" \
        "$(docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' 2>/dev/null | grep -v '^wow-e2e-bench-' | paste -sd, -)" \
        "$(ps -A -o %cpu= | awk '{s += $1} END {printf "%.0f", s}')" \
        "$(generator_thread)" \
        "$(allowed_jvm_cpu)" \
        "$(foreign_jvms | awk '{print $1}' | paste -sd, -)" \
        "$(bench_container_usage)" \
        >>"$1"
      sleep "$SAMPLE_SECONDS"
    done
  ) &
  SAMPLER=$!
}

sampler_stop() {
  if [ -n "$SAMPLER" ]; then
    kill "$SAMPLER" 2>/dev/null
    wait "$SAMPLER" 2>/dev/null
  fi
  SAMPLER=""
}

# The server JVMs of the session, `<node> <pid>` a line: the java processes started with --wow.node.id=node-<n>.
server_pids() {
  ps -A -o pid=,command= |
    sed -n 's/^ *\([0-9][0-9]*\) [^ ]*java .*--wow\.node\.id=node-\([0-9][0-9]*\).*/\2 \1/p' | sort -n
}

# With STEP_JFR set, starts one flight recording on each server and a watcher of the sweep in $1 (its saturate.log):
# each step's line marks the end of its measured $2 seconds, which each server's recording is then cut to, as
# $1/jfr/node-<n>-c<clients>.jfr. step_jfr_stop ends both.
STEP_WATCHER=""
step_jfr_start() {
  local out=$1 step=$2 node pid
  [ -n "$STEP_JFR" ] || return 0
  mkdir -p "$out/jfr"
  server_pids >"$out/jfr/nodes.txt"
  while read -r node pid; do
    jcmd "$pid" JFR.start name=wow-steps settings="$STEP_JFR" maxage=10m >/dev/null 2>&1 ||
      say "node-$node ($pid): no step recording"
  done <"$out/jfr/nodes.txt"
  (
    local seen=0 lines epoch clients
    while :; do
      lines=$(grep -E '^[0-9]+ \[saturate\] .*: concurrency=[0-9]+ ' "$out/saturate.log" 2>/dev/null)
      while [ "$seen" -lt "$(printf '%s' "$lines" | grep -c .)" ]; do
        seen=$((seen + 1))
        epoch=$(printf '%s\n' "$lines" | sed -n "${seen}p" | cut -d' ' -f1)
        clients=$(printf '%s\n' "$lines" | sed -n "${seen}p" | sed -n 's/.*concurrency=\([0-9]*\) .*/\1/p')
        while read -r node pid; do
          jcmd "$pid" JFR.dump name=wow-steps filename="$out/jfr/node-$node-c$clients.jfr" \
            begin="$(date -u -r $((epoch - step)) +%Y-%m-%dT%H:%M:%SZ)" \
            end="$(date -u -r "$epoch" +%Y-%m-%dT%H:%M:%SZ)" >/dev/null 2>&1 ||
            say "node-$node: no recording of the step at $clients clients"
        done <"$out/jfr/nodes.txt"
        echo "$seen" >"$out/jfr/.dumped"
      done
      sleep 1
    done
  ) &
  STEP_WATCHER=$!
}

step_jfr_stop() {
  local out=$1 node pid
  [ -n "$STEP_WATCHER" ] || return 0
  # The last step's line may have just arrived: the watcher dumps every step before it stops, 60 s at most.
  local steps waited=0
  steps=$(grep -cE '^[0-9]+ \[saturate\] .*: concurrency=[0-9]+ ' "$out/saturate.log" 2>/dev/null)
  while [ "$(cat "$out/jfr/.dumped" 2>/dev/null || echo 0)" -lt "${steps:-0}" ] && [ "$waited" -lt 60 ]; do
    sleep 1
    waited=$((waited + 1))
  done
  rm -f "$out/jfr/.dumped"
  kill "$STEP_WATCHER" 2>/dev/null
  wait "$STEP_WATCHER" 2>/dev/null
  STEP_WATCHER=""
  while read -r node pid; do
    jcmd "$pid" JFR.stop name=wow-steps >/dev/null 2>&1 || true
  done <"$out/jfr/nodes.txt"
}

# With the session's MongoDB, samples its serverStatus into $1/mongo-stats.tsv every second until mongo_stats_stop:
# one mongosh session for the whole measurement, so no step pays for its start.
MONGO_STATS=""
MONGO_STATS_COLUMNS="epoch log_sync log_sync_time_us wtime_waits wtime_ms write_ops write_latency_us transaction_ops"
MONGO_STATS_COLUMNS="$MONGO_STATS_COLUMNS transaction_latency_us transactions_aborted retried_commands write_conflicts"
MONGO_STATS_COLUMNS="$MONGO_STATS_COLUMNS write_tickets_out"
mongo_stats_start() {
  [ -n "$(docker ps -q --filter name=wow-e2e-bench-mongo)" ] || return 0
  echo "$MONGO_STATS_COLUMNS" | tr ' ' '\t' >"$1/mongo-stats.tsv"
  docker exec wow-e2e-bench-mongo mongosh --quiet --eval "while (true) { const s = db.serverStatus(),
    l = s.wiredTiger.log, w = s.metrics?.getLastError?.wtime ?? {}, lat = s.opLatencies ?? {},
    x = s.transactions ?? {}, q = s.queues?.execution?.write ?? s.wiredTiger?.concurrentTransactions?.write ?? {};
    print([Math.floor(Date.now() / 1000), l['log sync operations'], l['log sync time duration (usecs)'], w.num ?? 0,
      w.totalMillis ?? 0, lat.writes?.ops ?? 0, lat.writes?.latency ?? 0, lat.transactions?.ops ?? 0,
      lat.transactions?.latency ?? 0, x.totalAborted ?? 0, x.retriedCommandsCount ?? 0,
      s.metrics?.operation?.writeConflicts ?? 0, q.out ?? -1].map(Number).join('\t')); sleep(1000); }" \
    >>"$1/mongo-stats.tsv" 2>/dev/null &
  MONGO_STATS=$!
}

mongo_stats_stop() {
  if [ -n "$MONGO_STATS" ]; then
    kill "$MONGO_STATS" 2>/dev/null
    wait "$MONGO_STATS" 2>/dev/null
  fi
  MONGO_STATS=""
}

# Each line of the input, stamped with the epoch second it arrives.
stamp() {
  local line
  while IFS= read -r line; do printf '%s %s\n' "$(date +%s)" "$line"; done
}

# Runs `bench.sh $1 --reuse` with the arguments after $2 into $2, recording the host around it; its output, stamped,
# goes to $2/$3.
measure() {
  local command=$1 out=$2 log=$3
  shift 3
  mkdir -p "$out"
  pg_statements reset
  host_record "$out/host.txt" begin
  sampler_start "$out/host-load.tsv"
  mongo_stats_start "$out"
  if [ "$command" = saturate ]; then
    local step argument
    step=30
    for argument in "$@"; do
      case $argument in --step=*) step=${argument#--step=} ;; esac
    done
    step_jfr_start "$out" "$step"
  fi
  "$B" "$command" --reuse "$@" --out="$out" 2>&1 | stamp >"$out/$log"
  local status=${PIPESTATUS[0]}
  step_jfr_stop "$out"
  mongo_stats_stop
  sampler_stop
  host_record "$out/host.txt" end
  pg_statements "$out/pg-statements.txt"
  if [ "$status" != 0 ]; then say "bench.sh $command into $out failed ($status)"; fi
  return "$status"
}

# PostgreSQL's statements by calls into $1, or with `reset` their counts reset, when the session's PostgreSQL loaded
# pg_stat_statements.
pg_statements() {
  if [ -z "${WOW_BENCH_PG_PRELOAD:-}" ] || [ -z "$(docker ps -q --filter name=wow-e2e-bench-postgres)" ]; then return 0; fi
  if [ "$1" = reset ]; then
    docker exec wow-e2e-bench-postgres psql -U postgres -d transfer -X -q \
      -c "create extension if not exists pg_stat_statements; select pg_stat_statements_reset();" >/dev/null 2>&1 || true
    return 0
  fi
  docker exec wow-e2e-bench-postgres psql -U postgres -d transfer -X -q -c \
    "select calls, rows, round(total_exec_time::numeric, 1) as total_ms, round(mean_exec_time::numeric, 3) as mean_ms,
      left(regexp_replace(query, '\s+', ' ', 'g'), 160) as query
      from pg_stat_statements order by calls desc limit 25" >"$1" 2>&1 || true
}

# `measure $@` until none of its steps is void or disturbed (`summarize.py --check`): an attempt that has one moves to
# $2.void-<n>/, and the measurement runs again once the host is quiet, at most RERUNS times. If the host stays busy, the
# attempt stays in $2, marked in its summary.
measure_checked() {
  local out=$2 attempt status verdict
  for attempt in $(seq 1 $((RERUNS + 1))); do
    measure "$@"
    status=$?
    verdict=$(python3 -I "$LIB_DIR/summarize.py" --check "$out") && return "$status"
    say "$(basename "$(dirname "$out")")/$(basename "$out"), attempt $attempt: $verdict"
    if [ "$attempt" -gt "$RERUNS" ]; then
      say "left as it is after $RERUNS reruns"
      return "$status"
    fi
    say "waiting for a quiet host"
    if ! wait_quiet; then
      say "the host stayed busy for $QUIET_MAX_SECONDS s; the attempt stays, marked"
      return "$status"
    fi
    mv "$out" "$out.void-$attempt"
    say "running it again; the attempt is in $(basename "$out").void-$attempt"
  done
}

# A closed-loop sweep into $1 (saturate.log), the harness's arguments after it.
measure_sweep() {
  local out=$1
  shift
  say "sweep $(basename "$(dirname "$out")")/$(basename "$out")"
  measure_checked saturate "$out" saturate.log "$@"
}

# A fixed-rate run into $1 (run.log), the harness's arguments after it.
measure_rate() {
  local out=$1
  shift
  say "fixed rate $(basename "$(dirname "$out")")/$(basename "$out")"
  measure_checked run "$out" run.log "$@"
}

# Moves the session directory $1 of an earlier run, if any, to $OUT_ROOT/replaced/$RUN_STAMP/.
replace_earlier() {
  if [ -e "$1" ]; then
    local aside="$OUT_ROOT/replaced/$RUN_STAMP/${1#"$OUT_ROOT"/}"
    mkdir -p "$(dirname "$aside")"
    mv "$1" "$aside"
    say "moved the earlier $(basename "$1") to $aside"
  fi
}

# Starts a session with the harness's arguments after $1 (its directory, under OUT_ROOT), its output into $1/up.log,
# at most twice; the session's own directory (the node logs, under results/e2e/) is then SESSION_OUT.
session_up() {
  local dir=$1
  shift
  replace_earlier "$dir"
  mkdir -p "$dir"
  local attempt
  for attempt in 1 2; do
    say "up $(basename "$dir") (attempt $attempt): $*"
    host_record "$dir/host.txt" "up, attempt $attempt"
    if "$B" up "$@" --prime --seed-concurrency=128 >"$dir/up.log" 2>&1; then
      # shellcheck disable=SC1090
      SESSION_OUT=$(. "$SESSION_FILE" && echo "$S_OUT")
      cp "$SESSION_OUT/prime.json" "$dir/up-prime.json" 2>/dev/null || true
      cp "$SESSION_OUT/settle.json" "$dir/up-settle.json" 2>/dev/null || true
      return 0
    fi
    say "up $(basename "$dir") failed; see $dir/up.log"
    session_down "$dir"
  done
  return 1
}

# Stops the session, keeps a digest of its node logs' warnings and errors in $1/log-digest.md, and waits until the
# harness's ports are free again; fails if they are not after 60 s.
session_down() {
  local dir=$1
  host_record "$dir/host.txt" down
  "$B" down >/dev/null 2>&1 || true
  if [ -n "$SESSION_OUT" ] && ls "$SESSION_OUT"/node-*.log >/dev/null 2>&1; then
    python3 -I "$LIB_DIR/digest-logs.py" "$dir" "$SESSION_OUT"/node-*.log >"$dir/log-digest.md" ||
      say "no digest of $SESSION_OUT's logs"
  fi
  SESSION_OUT=""
  local _ port busy
  for _ in $(seq 1 60); do
    busy=""
    for port in $PORTS; do
      if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then busy="$busy $port"; fi
    done
    if [ -z "$busy" ]; then return 0; fi
    sleep 1
  done
  say "ports still in use after down:$busy"
  return 1
}

# Copies from the raw output $1 into the report's directory $2 what reproduces its conclusions: the scripts' logs and
# summaries, the environment, each session's up.log, host record and log digest, and each measurement's results and
# host record.
curate() {
  rsync -a --prune-empty-dirs --exclude='/replaced/' --include='/*.log' --include='/*.md' --include='*/' \
    --include='environment.txt' --include='host.txt' --include='host-load.tsv' --include='up.log' \
    --include='up-prime.json' --include='up-settle.json' --include='log-digest.md' --include='*-peak.md' \
    --include='saturate.log' --include='run.log' --include='saturation.json' --include='result.json' \
    --include='settle.json' --include='settle.log' --include='summary.md' --include='pg-statements.txt' \
    --include='hot-version.tsv' --include='tables.txt' --include='explain.txt' \
    --include='allowed-jvms.txt' --include='mongo-stats.tsv' --exclude='*' "$1/" "$2/"
}
