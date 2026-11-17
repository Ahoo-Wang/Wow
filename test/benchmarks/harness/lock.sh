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
#   bench.sh lock   --owner=<name> [--pid=<pid>] [--wait[=<seconds>]] [--quiet-wait=<seconds>]
#                                                                        (a batch script, before its first round)
#   bench.sh unlock --owner=<name>                                      (the same script, after its last)
#   quiet.sh <command> [<argument>...]                                  (any session, in front of heavy work)
#
# The lock is a directory (mkdir is atomic; macOS has no flock), $WOW_BENCH_LOCK or /tmp/wow-e2e-bench.lock, holding
# its owner's name, pid and start. While another live owner holds it, run, saturate, measure, up, down and abba refuse,
# saying who holds it, since when and how to wait. The owner's own commands pass: those whose WOW_BENCH_OWNER is its
# name (a batch script exports it before its rounds), or whose process descends from its pid (abba's sweeps). A lock
# whose pid is gone is a crashed batch's: it is taken over. --wait queues behind the holder, printing who holds it,
# and takes the lock the moment it is released (or fails after <seconds>; without a number it waits as long as it
# takes). No rule here reads a clock: a lock or a take-over is stale only when its holder's pid is gone (#6346).
#
# The lock is also the machine's quiet window, one place for every worktree. Any session runs its heavy work (a Gradle
# build, `pnpm test` or `build`, a container command) through quiet.sh, which waits while another live owner holds the
# lock, saying who and since when, then runs it. A batch that takes the lock first waits, --quiet-wait seconds at most
# (600 by default; 0 waits for nothing), for the heavy work already running that is not its own to end, saying what it
# waits for, and fails naming what still runs when the time is up: one that started just before the lock was taken.
# Release is the holder's unlock, or its exit: a lock whose pid is gone holds no one.

LOCK_DIR="${WOW_BENCH_LOCK:-/tmp/wow-e2e-bench.lock}"

# The lock's field $1 (owner, pid, since), empty when it is not held. Every helper here answers rather than fails:
# bench.sh runs under `set -euo pipefail`, where a failed assignment would end it with no word said.
lock_field() { sed -n "s/^$1=//p" "$LOCK_DIR/holder" 2>/dev/null || true; }

# Whether pid $1 is alive.
lock_alive() { [ -n "$1" ] && kill -0 "$1" 2>/dev/null; }

# Whether this process descends from pid $1.
lock_descends() { lock_pid_descends $$ "$1"; }

# Whether pid $1 is pid $2 or descends from it.
lock_pid_descends() {
  local pid=$1
  while [ -n "$pid" ] && [ "$pid" -gt 1 ]; do
    if [ "$pid" = "$2" ]; then return 0; fi
    pid=$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d ' ' || true)
  done
  return 1
}

# The heavy work a batch waits for: a Gradle build (its client), a Vitest or Playwright run, a headless browser, a
# `pnpm test` or `build`. Idle Gradle and Kotlin daemons are not work. WOW_BENCH_HEAVY replaces it (a test's own).
LOCK_HEAVY="${WOW_BENCH_HEAVY:-GradleWrapperMain|gradle-wrapper\.jar|org\.gradle\.launcher\.GradleMain|vitest|playwright|chrome-headless-shell|(^|[ /])pnpm( [^ ]+)* (test|build)( |$)}"
LOCK_DOCKER="${WOW_BENCH_DOCKER:-docker}"

# The heavy work running that is not the lock holder's ($1, its pid), this process's or its ancestors': one "pid <command>" line
# each, and one "container <name>" line for each container that is not the benchmark's (wow-e2e-bench-*). The process
# list is read whole before it is matched, so the match runs in no process of its own.
lock_foreign_work() {
  local holder=$1 listing pid command name
  listing=$(ps -A -o pid=,command= 2>/dev/null || true)
  while read -r pid command; do
    [ -n "$pid" ] || continue
    [[ $command =~ $LOCK_HEAVY ]] || continue
    # Gone since the listing: the listing's own subshell, among others.
    lock_alive "$pid" || continue
    # The holder's own work, this process's, and the processes this one runs under (the script that took the lock).
    if lock_pid_descends "$pid" "$holder" || lock_pid_descends "$pid" $$ || lock_pid_descends $$ "$pid"; then continue; fi
    echo "$pid ${command:0:160}"
  done <<<"$listing"
  if command -v "$LOCK_DOCKER" >/dev/null 2>&1; then
    while read -r name; do
      case "$name" in "" | wow-e2e-bench*) ;; *) echo "container $name" ;; esac
    done <<<"$("$LOCK_DOCKER" ps --format '{{.Names}}' 2>/dev/null || true)"
  fi
}

# Waits, $2 seconds at most, until no heavy work runs that is not the holder's ($1, its pid), saying what it waits for
# every 30 s; fails naming what still runs when the time is up.
lock_await_quiet() {
  local holder=$1 bound=$2 waited=0 told=-30 busy line
  while :; do
    busy=$(lock_foreign_work "$holder")
    if [ -z "$busy" ]; then return 0; fi
    if [ "$waited" -ge "$bound" ]; then
      log "still running after ${bound}s, not this batch's (it holds the lock, so new work waits; this did not):"
      while IFS= read -r line; do echo "  $line" >&2; done <<<"$busy"
      return 1
    fi
    if [ $((waited - told)) -ge 30 ]; then
      log "waiting up to ${bound}s for work already running to end: $(echo "$busy" | tr '\n' ';' | sed 's/;$//; s/;/; /g')"
      told=$waited
    fi
    sleep 2
    waited=$((waited + 2))
  done
}

# Waits while another live owner holds the lock and this process is neither its owner's (WOW_BENCH_OWNER) nor
# descends from its pid, saying every 30 s who holds it and since when, and what waits ($1). A lock whose pid is gone
# holds no one.
lock_await_free() {
  local what=$1 pid owner waited=0 told=-30
  while :; do
    owner=$(lock_field owner)
    pid=$(lock_field pid)
    if [ -z "$owner" ] || ! lock_alive "$pid" || [ "${WOW_BENCH_OWNER:-}" = "$owner" ] || lock_descends "$pid"; then
      return 0
    fi
    if [ $((waited - told)) -ge 30 ]; then
      log "the machine is held by $(lock_holder) for a benchmark batch; $what runs once it is released"
      told=$waited
    fi
    sleep 2
    waited=$((waited + 2))
  done
}

# Epoch second $1 as the time of day (macOS's date takes -r seconds, GNU's -d @seconds).
lock_time() { date -r "$1" '+%H:%M:%S' 2>/dev/null || date -d "@$1" '+%H:%M:%S' 2>/dev/null || true; }

# Who holds the lock, in words.
lock_holder() { echo "$(lock_field owner) (pid $(lock_field pid), since $(lock_time "$(lock_field since)"))"; }

# Releases the lock when owner $1 holds it.
lock_release() { if [ "$(lock_field owner)" = "$1" ]; then rm -rf "$LOCK_DIR"; fi; }

# The take-over's mutex: the first of $LOCK_DIR.takeover.0, .1, ... that no gone holder left. Each is a symlink whose
# target is its holder's pid, made by one call: `ln -s` fails while the link exists, as mkdir does, and the link names
# its holder from the instant it exists. A directory made first and a pid written after would leave a moment in which
# a holder that died left a mutex nobody could ever call stale. A mutex is stale only when its holder's pid is gone,
# never by its age: a wall clock that stepped forward once made a live holder's mutex look old, and two takers took
# one dead lock over (#6346). Only its holder removes a link, when it is done: a gone holder's link is passed over to
# the next, never removed by another taker, who could not tell it from one made since by a live taker. 0 when taken,
# the link in LOCK_MUTEX; 1 while a live holder has it, LOCK_MUTEX and LOCK_MUTEX_PID naming them.
lock_mutex() {
  local n=0 tries=0 link holder
  while [ "$tries" -lt 100 ]; do
    link="$LOCK_DIR.takeover.$n"
    LOCK_MUTEX=$link
    if ln -s "${BASHPID:-$$}" "$link" 2>/dev/null; then return 0; fi
    holder=$(readlink "$link" 2>/dev/null || true)
    tries=$((tries + 1))
    if [ -z "$holder" ]; then
      # Released between the two calls: try it again.
      continue
    elif lock_alive "$holder"; then
      LOCK_MUTEX_PID=$holder
      return 1
    fi
    n=$((n + 1))
  done
  LOCK_MUTEX_PID=unknown
  return 1
}

# Takes the lock for owner $1 and pid $2, once: 0 when taken (or already this owner's), 1 while another live owner
# holds it, or another taker is taking a dead owner's over (LOCK_MUTEX_PID names it). A lock whose pid is gone is
# taken over; a lock whose holder is not written yet is never, since its taker is writing it.
lock_try() {
  LOCK_MUTEX_PID=""
  if mkdir "$LOCK_DIR" 2>/dev/null; then
    printf 'owner=%s\npid=%s\nsince=%s\n' "$1" "$2" "$(date +%s)" >"$LOCK_DIR/holder"
    return 0
  fi
  local holder pid
  holder=$(lock_field owner)
  pid=$(lock_field pid)
  if [ "$holder" = "$1" ]; then return 0; fi
  if [ -n "$pid" ] && ! lock_alive "$pid"; then
    # The take-over under the mutex (lock_mutex): its one holder reads the lock again, and only while it is still the
    # dead one replaces it; the other takers lose. A live lock is never moved, and a taker without the lock can only
    # mkdir, which fails while any lock exists, so two takers never both hold it.
    lock_mutex || return 1
    local taken=1
    if [ "$(lock_field pid)" = "$pid" ] && ! lock_alive "$pid"; then
      log "taking over the lock of ${holder:-a batch} (pid $pid, gone)"
      rm -rf "$LOCK_DIR"
      if mkdir "$LOCK_DIR" 2>/dev/null; then
        printf 'owner=%s\npid=%s\nsince=%s\n' "$1" "$2" "$(date +%s)" >"$LOCK_DIR/holder"
        taken=0
      fi
    fi
    rm -f "$LOCK_MUTEX"
    return "$taken"
  fi
  return 1
}

# Who blocks a taker that lost, in words: the take-over's mutex and the pid that holds it, a lock whose holder is not
# written (its taker is writing it, or died doing so: remove $LOCK_DIR once no batch runs), or the lock's holder.
lock_blocker() {
  if [ -n "${LOCK_MUTEX_PID:-}" ]; then
    echo "the take-over of $(lock_holder) by pid $LOCK_MUTEX_PID ($LOCK_MUTEX)"
  elif [ -d "$LOCK_DIR" ] && [ -z "$(lock_field pid)" ]; then
    echo "a lock with no holder written yet ($LOCK_DIR)"
  else
    lock_holder
  fi
}

# Takes the lock for owner $1 and pid $2, waiting up to $3 seconds (empty: not at all; `forever`: as long as it takes)
# while another live owner holds it. Fails, saying who holds it and how to wait, when it is still held.
lock_take() {
  local owner=$1 pid=$2 wait=$3 waited=0 told=0
  while ! lock_try "$owner" "$pid"; do
    if [ -z "$wait" ] || { [ "$wait" != forever ] && [ "$waited" -ge "$wait" ]; }; then
      log "the harness is held by $(lock_blocker); wait for it with --wait (bench.sh lock --wait, or abba --wait)"
      return 1
    fi
    if [ $((waited - told)) -ge 30 ] || [ "$waited" = 0 ]; then
      log "waiting for the harness: held by $(lock_blocker)"
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
  local pid
  pid=$(opt pid "$PPID")
  lock_take "$owner" "$pid" "$(lock_wait_option)" || exit 2
  log "the harness is held by $(lock_holder)"
  local quiet
  quiet=$(opt quiet-wait 600)
  if [ "$quiet" -gt 0 ] && ! lock_await_quiet "$pid" "$quiet"; then
    lock_release "$owner"
    log "the harness is released by $owner: the machine did not go quiet"
    exit 2
  fi
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
