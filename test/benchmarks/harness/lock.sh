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
# bench.sh's batch lock, sourced by bench.sh: one harness on this machine, so one batch at a time.
#
#   bench.sh lock   --owner=<name> [--pid=<pid>] [--wait[=<seconds>]]   (a batch script, before its first round)
#   bench.sh unlock --owner=<name>                                      (the same script, after its last)
#
# The lock is a directory (mkdir is atomic; macOS has no flock), $WOW_BENCH_LOCK or /tmp/wow-e2e-bench.lock, holding
# its owner's name, pid and start. While another live owner holds it, run, saturate, measure, up, down and abba refuse,
# saying who holds it, since when and how to wait. The owner's own commands pass: those whose WOW_BENCH_OWNER is its
# name (a batch script exports it before its rounds), or whose process descends from its pid (abba's sweeps). A lock
# whose pid is gone is a crashed batch's: it is taken over. --wait queues behind the holder, printing who holds it,
# and takes the lock the moment it is released (or fails after <seconds>; without a number it waits as long as it
# takes).

LOCK_DIR="${WOW_BENCH_LOCK:-/tmp/wow-e2e-bench.lock}"

# The lock's field $1 (owner, pid, since), empty when it is not held.
lock_field() { sed -n "s/^$1=//p" "$LOCK_DIR/holder" 2>/dev/null; }

# Whether pid $1 is alive.
lock_alive() { [ -n "$1" ] && kill -0 "$1" 2>/dev/null; }

# Whether this process descends from pid $1.
lock_descends() {
  local pid=$$
  while [ -n "$pid" ] && [ "$pid" -gt 1 ]; do
    if [ "$pid" = "$1" ]; then return 0; fi
    pid=$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d ' ')
  done
  return 1
}

# Epoch second $1 as the time of day (macOS's date takes -r seconds, GNU's -d @seconds).
lock_time() { date -r "$1" '+%H:%M:%S' 2>/dev/null || date -d "@$1" '+%H:%M:%S' 2>/dev/null; }

# The seconds since the lock's directory was made (macOS's stat -f, GNU's -c).
lock_age() {
  local made
  made=$(stat -f %m "$LOCK_DIR" 2>/dev/null || stat -c %Y "$LOCK_DIR" 2>/dev/null)
  echo $(($(date +%s) - ${made:-0}))
}

# Who holds the lock, in words.
lock_holder() { echo "$(lock_field owner) (pid $(lock_field pid), since $(lock_time "$(lock_field since)"))"; }

# Releases the lock when owner $1 holds it.
lock_release() { if [ "$(lock_field owner)" = "$1" ]; then rm -rf "$LOCK_DIR"; fi; }

# Takes the lock for owner $1 and pid $2, once: 0 when taken (or already this owner's), 1 while another live owner
# holds it. A lock whose pid is gone is taken over.
lock_try() {
  if mkdir "$LOCK_DIR" 2>/dev/null; then
    printf 'owner=%s\npid=%s\nsince=%s\n' "$1" "$2" "$(date +%s)" >"$LOCK_DIR/holder"
    return 0
  fi
  local holder pid
  holder=$(lock_field owner)
  pid=$(lock_field pid)
  if [ "$holder" = "$1" ]; then return 0; fi
  if [ -z "$pid" ] && [ "$(lock_age)" -lt 5 ]; then
    # Another taker made the directory and has not written its holder yet.
    return 1
  fi
  if ! lock_alive "$pid"; then
    log "taking over the lock of ${holder:-a batch} (pid ${pid:-unknown}, gone)"
    rm -rf "$LOCK_DIR"
    lock_try "$1" "$2"
    return $?
  fi
  return 1
}

# Takes the lock for owner $1 and pid $2, waiting up to $3 seconds (empty: not at all; `forever`: as long as it takes)
# while another live owner holds it. Fails, saying who holds it and how to wait, when it is still held.
lock_take() {
  local owner=$1 pid=$2 wait=$3 waited=0 told=0
  while ! lock_try "$owner" "$pid"; do
    if [ -z "$wait" ] || { [ "$wait" != forever ] && [ "$waited" -ge "$wait" ]; }; then
      log "the harness is held by $(lock_holder); wait for it with --wait (bench.sh lock --wait, or abba --wait)"
      return 1
    fi
    if [ $((waited - told)) -ge 30 ] || [ "$waited" = 0 ]; then
      log "waiting for the harness: held by $(lock_holder)"
      told=$waited
    fi
    sleep 2
    waited=$((waited + 2))
  done
}

# The --wait option's value: empty without it, `forever` without a number, else its seconds.
lock_wait_option() {
  local wait
  wait=$(opt wait "")
  case "$wait" in "") echo "" ;; true) echo forever ;; *) echo "$wait" ;; esac
}

# Fails, saying who holds the lock and how to wait, when another live owner holds it and this process is neither its
# owner's (WOW_BENCH_OWNER) nor descends from its pid. A dead owner's lock is let be: the next lock takes it over.
lock_guard() {
  local pid owner
  pid=$(lock_field pid)
  owner=$(lock_field owner)
  if [ -z "$owner" ] || ! lock_alive "$pid"; then return 0; fi
  if [ "${WOW_BENCH_OWNER:-}" = "$owner" ] || lock_descends "$pid"; then return 0; fi
  log "the harness is held by $(lock_holder); this command waits for no one: wait for the batch with bench.sh lock --wait"
  exit 2
}

# `bench.sh lock`: takes the lock for --owner (required) and --pid (the calling script's, by default).
bench_lock() {
  parse "$@"
  local owner
  owner=$(opt owner "")
  if [ -z "$owner" ]; then log "bench.sh lock needs --owner=<name>"; exit 2; fi
  lock_take "$owner" "$(opt pid "$PPID")" "$(lock_wait_option)" || exit 2
  log "the harness is held by $(lock_holder)"
}

# `bench.sh unlock`: releases the lock when --owner holds it; a lock another owner holds is left alone.
bench_unlock() {
  parse "$@"
  local owner
  owner=$(opt owner "")
  if [ "$(lock_field owner)" = "$owner" ]; then
    lock_release "$owner"
    log "the harness is released by $owner"
  elif [ -d "$LOCK_DIR" ]; then
    log "the harness is held by $(lock_holder), not $owner: left alone"
    exit 2
  fi
}
