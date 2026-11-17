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
# bench.sh's seeded copies, sourced by bench.sh: a large data set is seeded once per build, store and data set, and
# the sessions after restore it instead of seeding it again (L3 spent a third of its runner time seeding).
#
# - `bench.sh seed-copy [a session's options] --out=<dir>` starts the store and the nodes as a session does, seeds,
#   stops the nodes (their leases released) and then the store, cleanly, and copies the store's data directory into
#   a volume of its own. <dir>/seed.txt names that volume and what the copy is: the server's build stamp, its install,
#   server arguments and properties, the backend, the accounts and the store's image.
# - `bench.sh run|saturate|up … --restore=<dir>` creates the store's container, fills its data directory from the copy
#   and skips seeding; the check, the prime and the settle run as in any session. A copy that differs from the session
#   in any line of seed.txt, or of a build with changes or unstamped (rebuilt by each run, so never the same twice), is
#   refused, as is --tmpfs (its data directory is in memory). `down` removes the restored volume with the container.
# - Seeding afresh on PostgreSQL ends with a CHECKPOINT, so the seeding's WAL is not checkpointed inside the measured
#   phase; a restored copy starts from its clean shutdown's checkpoint. Either way the session starts checkpointed.
# Every helper answers rather than fails, except where it says it stops the run: bench.sh runs under `set -euo pipefail`.

# The label on every copy's volume: the copy's directory.
SEED_LABEL=wow-e2e-bench-seed
# The copy this session restored (--restore's directory), empty for one that seeds.
SEED_RESTORED=""

# The volume that holds the copy of directory $1.
seed_volume() { echo "wow-e2e-bench-seed-$(printf %s "$1" | cksum | cut -d' ' -f1)"; }

# The data directory of store $1 in its container.
seed_data_dir() { if [ "$1" = mongo ]; then echo /data/db; else echo /var/lib/postgresql/data; fi; }

# What a copy of store $1 is, one name=value a line (seed.txt's lines after `volume=`): its container must exist.
seed_identity() {
  echo "server=$(install_stamp "$(opt install "$SERVER_INSTALL")")"
  echo "install=$(opt install "")"
  echo "args=$(opt server-args "")"
  echo "props=${SPRING_APPLICATION_JSON:-}"
  echo "bus=$(opt no-bus false)"
  echo "backend=$BACKEND"
  echo "accounts=$ACCOUNTS"
  echo "image=$(docker inspect --format '{{.Image}}' "wow-e2e-bench-$1")"
}

# Stops the run when the server's build is not one stamp names for good: one with changes, or unstamped.
seed_refuse_unstamped() {
  local stamp
  stamp=$(install_stamp "$(opt install "$SERVER_INSTALL")")
  case "$stamp" in
    *dirty=no) ;;
    *) log "$1: the server's build is $(stamp_text "$stamp"), which a later run may rebuild; no copy of its data is kept"
      exit 2 ;;
  esac
}

# With --restore=<dir>, before the stores start (stores_up, the compose files in $2…): creates store $1's container and
# fills its data directory from the copy, or stops the run on a copy that is not this session's (see the header).
seed_restore() {
  local store=$1 dir
  shift
  dir=$(opt restore "")
  [ -n "$dir" ] || return 0
  if [ "$(opt tmpfs false)" = true ]; then log "--restore: with --tmpfs the store's data is in memory"; exit 2; fi
  if [ ! -f "$dir/seed.txt" ]; then log "--restore: $dir holds no seeded copy"; exit 2; fi
  seed_refuse_unstamped --restore
  docker compose "$@" create "$store" >/dev/null
  local want have
  want=$(grep -v '^volume=' "$dir/seed.txt")
  have=$(seed_identity "$store")
  if [ "$want" != "$have" ]; then
    log "--restore: the copy in $dir is not this session's (< the copy, > the session):"
    diff <(echo "$want") <(echo "$have") >&2 || true
    exit 2
  fi
  docker run --rm --volumes-from "wow-e2e-bench-$store" -v "$(sed -n 's/^volume=//p' "$dir/seed.txt"):/seed:ro" \
    --entrypoint cp "$(docker inspect --format '{{.Config.Image}}' "wow-e2e-bench-$store")" \
    -a /seed/. "$(seed_data_dir "$store")/"
  SEED_RESTORED=$dir
}

# The session's seeding on store $1: none after a restore; else the load generator's, then on PostgreSQL a CHECKPOINT.
seed_accounts() {
  if [ -n "$SEED_RESTORED" ]; then
    log "restored $ACCOUNTS accounts from the copy in $SEED_RESTORED"
    return
  fi
  log "seeding $ACCOUNTS accounts"
  load seed --targets="$TARGET_LIST" --accounts="$ACCOUNTS" \
    --concurrency="$(opt seed-concurrency 32)" | tee "$OUT/seed.log" >&2
  if [ "$1" = postgres ]; then postgres_sql checkpoint >/dev/null; fi
}

# How the session's data was seeded, for its meta: `restored` or `fresh`.
seed_mode() { if [ -n "$SEED_RESTORED" ]; then echo restored; else echo fresh; fi; }

# `bench.sh seed-copy`: seeds a session's store and keeps a copy of its data in --out (see the header).
seed_copy() {
  parse "$@"
  if [ -z "$(opt out "")" ] || [ -n "$(opt restore "")" ] || [ "$(opt tmpfs false)" = true ]; then
    log "seed-copy: --out=<dir> is required, and --restore and --tmpfs are not seeding"; exit 2
  fi
  seed_refuse_unstamped seed-copy
  SEED_COPY=true
  setup
  local store=postgres
  if [ "$BACKEND" = mongo ]; then store=mongo; fi
  local identity volume
  identity=$(seed_identity "$store")
  stop_nodes ${SERVER_PIDS[@]+"${SERVER_PIDS[@]}"}
  SERVER_PIDS=()
  docker stop -t 120 "wow-e2e-bench-$store" >/dev/null
  volume=$(seed_volume "$OUT")
  docker volume rm -f "$volume" >/dev/null 2>&1 || true
  docker volume create --label "$SEED_LABEL=$OUT" "$volume" >/dev/null
  docker run --rm --volumes-from "wow-e2e-bench-$store" -v "$volume:/seed" \
    --entrypoint cp "$(docker inspect --format '{{.Config.Image}}' "wow-e2e-bench-$store")" \
    -a "$(seed_data_dir "$store")/." /seed/
  printf 'volume=%s\n%s\n' "$volume" "$identity" >"$OUT/seed.txt"
  log "seeded $ACCOUNTS accounts on $store once: the copy is $volume ($OUT/seed.txt)"
}
