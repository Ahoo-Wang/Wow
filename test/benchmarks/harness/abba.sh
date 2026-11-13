#!/usr/bin/env bash
#
# Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#      http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# bench.sh's ABBA comparison on one machine (`abba`), sourced by bench.sh: its helpers and settings are in scope.
#
#   bench.sh abba --backend=postgres --scenario=E1 --concurrencies=1024,4096 [--sweeps=2] [--step=30] [--warm-up=10]
#                 [--wait[=<seconds>]] (behind another batch's lock, lock.sh)
#                 --arm='main|<install>|<server args>' --arm='branch|<install>|<server args>' [--out=<root>]
#                 [--profile=constrained] [options]
#
# Each arm is its name, the server's install (empty: this tree's) and its servers' arguments, `|` between them. For
# each sweep the arms run in turn, the order reversed every other sweep (A B, B A, ...), each a fresh `bench.sh
# saturate` (empty stores, seeded and checked) at the given steps, its servers with the `bench-pipeline` line on, into
# <root>/<sweep>-<arm>-<backend>/sweep with its output stamped (each line's epoch seconds), so each step's window
# can be read from the line. Every other option goes to each sweep. Then `pnpm bench:abba` (bench-abba.mjs) writes the
# table, <root>/abba.md: each step a sweep, the arms' ratio to the first, each arm's own spread, the refused share,
# and the line's calls, clock and windows over each step. --profile=constrained puts the store and Kafka each on one
# CPU of the containers' VM (`--store-cpus=1 --bus-cpus=2`), as the paired suites lay them out on a runner of four, so
# what a store with no core to spare does is measured here; the node, a host JVM, is not pinned (macOS has no CPU
# affinity), and the table gives each step's store and Kafka cores from their cgroups. It refuses to start while the
# harness's containers are up (another run measuring on them, which a sweep's start would take down), unless --force.
abba() {
  parse "$@"
  BACKEND=$(opt backend postgres)
  local scenario sweeps root arms=() argument
  scenario=$(opt scenario E1)
  sweeps=$(opt sweeps 2)
  root=$(opt out "$REPO/test/benchmarks/results/e2e/$(date +%Y%m%d-%H%M%S)-abba-$BACKEND")
  for argument in "$@"; do
    case "$argument" in --arm=*) arms+=("${argument#--arm=}") ;; esac
  done
  if [ ${#arms[@]} -lt 2 ]; then log "abba needs two --arm='name|install|server args' at least"; exit 2; fi
  if [ -z "$(opt concurrencies "")" ]; then log "abba needs --concurrencies"; exit 2; fi
  # The batch lock first, held for the whole batch (lock.sh); --wait queues behind another batch.
  local owner="abba-$$"
  lock_take "$owner" "$$" "$(lock_wait_option)" || exit 2
  export WOW_BENCH_OWNER=$owner
  trap 'lock_release "$WOW_BENCH_OWNER"' EXIT
  # Under the lock, a second guard: every sweep starts by taking the harness down, and with its containers up another
  # run is measuring on them.
  local running
  running=$(docker ps --format '{{.Names}}' --filter name=wow-e2e-bench- | paste -sd, - || true)
  if [ -n "$running" ] && [ "$(opt force false)" != true ]; then
    log "the harness is in use ($running): another run is measuring; abba starts once it is down (--force takes it down)"
    exit 2
  fi
  local rest=()
  for argument in ${PASS[@]+"${PASS[@]}"}; do
    case "$argument" in
      --arm=* | --sweeps=* | --scenario=* | --concurrencies=* | --step=* | --warm-up=*) ;;
      --force* | --wait* | --profile=*) ;;
      *) rest+=("$argument") ;;
    esac
  done
  # The constrained profile: the store and Kafka each on one CPU of the containers' VM, as the paired suites lay them
  # out on a runner of four (the node is a host JVM, which macOS cannot pin: it stays on the host's cores).
  local pinned=()
  case "$(opt profile "")" in
    "") ;;
    constrained) pinned=(--store-cpus=1 --bus-cpus=2) ;;
    *) log "abba knows the profile constrained alone"; exit 2 ;;
  esac
  mkdir -p "$root"
  # The arms' names in the order given, the first the base of the table's ratios.
  for argument in "${arms[@]}"; do echo "${argument%%|*}"; done >"$root/arms.txt"
  echo "$(opt profile "")" >"$root/profile.txt"
  # The host's foreign CPU each second, beside the sweeps (abba_host): a step it disturbed is flagged in the table.
  abba_host "$root/host.tsv" "${arms[@]}" &
  local sampler=$!
  local sweep index order arm name install args out status
  for sweep in $(seq 1 "$sweeps"); do
    order=()
    for index in "${!arms[@]}"; do order+=("$index"); done
    if [ $((sweep % 2)) = 0 ]; then
      local reversed=()
      for index in "${order[@]}"; do reversed=("$index" ${reversed[@]+"${reversed[@]}"}); done
      order=("${reversed[@]}")
    fi
    for index in "${order[@]}"; do
      arm=${arms[$index]}
      name=${arm%%|*}
      install=$(echo "$arm" | cut -d'|' -f2)
      args=$(echo "$arm" | cut -d'|' -f3-)
      out="$root/$sweep-$name-$BACKEND/sweep"
      mkdir -p "$out"
      log "abba sweep $sweep: $name"
      # A failed sweep is logged and the others run: bench.sh's errexit is off around it.
      set +e
      "$HARNESS/bench.sh" saturate --backend="$BACKEND" --scenario="$scenario" \
        --concurrencies="$(opt concurrencies "")" --step="$(opt step 30)" --warm-up="$(opt warm-up 10)" \
        --accounts="$(opt accounts 100000)" ${install:+--install="$install"} \
        --server-args="$args --bench.pipeline.enabled=true" --out="$out" ${pinned[@]+"${pinned[@]}"} \
        ${rest[@]+"${rest[@]}"} 2>&1 |
        while IFS= read -r line; do printf '%s %s\n' "$(date +%s)" "$line"; done >"$out/saturate.log"
      status=${PIPESTATUS[0]}
      set -e
      if [ "$status" != 0 ]; then log "abba sweep $sweep, $name: bench.sh saturate failed ($status); see $out/saturate.log"; fi
    done
  done
  kill "$sampler" 2>/dev/null || true
  (cd "$REPO" && pnpm --silent bench:abba "$root" | tee "$root/abba.md")
  log "table in $root/abba.md"
}

# Each second until killed, into $1: the epoch, the cores the host's foreign processes used (every process but this
# tree's and the arms' installs' (after $1), OrbStack's VM, which runs the containers, and the JVMs in
# ALLOW_JVM_PIDS, idle shared daemons), the busiest of them, and each of the harness's containers' CPU so far in
# microseconds (its cgroup's, from the daemon's stats API), so the table gives the store's cores over each step.
abba_host() {
  local file=$1 arm paths="$REPO"
  shift
  for arm in "$@"; do
    arm=$(echo "$arm" | cut -d'|' -f2)
    if [ -n "$arm" ]; then paths="$paths|$arm"; fi
  done
  local socket
  socket=$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null || true)
  socket=${socket#unix://}
  printf 'epoch\tforeignCores\ttop\tcontainers\n' >"$file"
  while true; do
    ps -A -o pid=,%cpu=,args= | awk -v paths="$paths" -v allowed=" ${ALLOW_JVM_PIDS:-} " -v now="$(date +%s)" '
      BEGIN { n = split(paths, own, "|") }
      {
        pid = $1; cpu = $2; $1 = ""; $2 = ""; command = substr($0, 3)
        if (index(allowed, " " pid " ") || command ~ /OrbStack|com\.docker|vpnkit/) next
        for (i = 1; i <= n; i++) if (own[i] != "" && index(command, own[i])) next
        total += cpu; if (cpu > 1) top = top sprintf("%s %.0f, ", substr(command, 1, 40), cpu)
      }
      END { printf "%s\t%.2f\t%s\t", now, total / 100, top }' >>"$file"
    # Read through the daemon's stats API, not `docker exec`: a process in the container would run on its pinned CPU
    # and count in the usage it reads (about one percent of a core a second, idle, against none measurable this way).
    local container usage containers=""
    for container in $(docker ps --format '{{.Names}}' --filter name=wow-e2e-bench-); do
      usage=$(curl -s --unix-socket "$socket" \
        "http://localhost/containers/$container/stats?stream=false&one-shot=true" |
        grep -o '"total_usage":[0-9]*' | head -1 | awk -F: '{ printf "%.0f", $2 / 1000 }' || true)
      if [ -n "$usage" ]; then containers="$containers$container=$usage,"; fi
    done
    echo "$containers" >>"$file"
    sleep 1
  done
}
