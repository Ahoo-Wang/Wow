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
# bench.sh's build stamps, sourced by bench.sh: the commit each installed distribution was built from.
#
# installDist writes `wow-build-stamp` beside a distribution's bin and lib (test/benchmarks/build-stamp.gradle.kts):
# `commit=<sha>` and `dirty=yes|no`, whether the tree had changes then. Before a run starts its servers, this tree's
# installs (the server's, unless --install names another build, and the load generator's) are checked against the
# tree: built from another commit, from a tree with changes, unstamped or missing, they are rebuilt, so a run measures
# the tree it runs from. On CI (CI set) the builds are the workflow's: a stamp of another commit stops the run, and an
# unstamped one (a build older than the stamp) is only named. Every run names its server's and load generator's stamps
# in its log and in <out>/install.txt, which the abba table and the suites' summaries print. Every helper answers
# rather than fails: bench.sh runs under `set -euo pipefail`.

STAMP_FILE=wow-build-stamp
SERVER_INSTALL="$REPO/test/benchmarks/server/build/install/benchmark-server"
LOAD_INSTALL="$REPO/test/benchmarks/load/build/install/benchmark-load"

# The tree's state as a stamp: `commit=<HEAD> dirty=yes|no` (unknown and yes when git cannot tell).
tree_stamp() {
  local commit status
  commit=$(git -C "$REPO" rev-parse HEAD 2>/dev/null || echo unknown)
  status=$(git -C "$REPO" status --porcelain 2>/dev/null || echo unknown)
  echo "commit=$commit dirty=$([ -z "$status" ] && echo no || echo yes)"
}

# The stamp of the installed distribution at $1, in tree_stamp's words; `unstamped` without one.
install_stamp() {
  local file="$1/$STAMP_FILE" commit dirty
  if [ ! -f "$file" ]; then
    echo unstamped
    return
  fi
  commit=$(sed -n 's/^commit=//p' "$file" 2>/dev/null || true)
  dirty=$(sed -n 's/^dirty=//p' "$file" 2>/dev/null || true)
  echo "commit=${commit:-unknown} dirty=${dirty:-yes}"
}

# A stamp in short words: `<commit, ten characters>` or `<…> with changes`, or `unstamped`.
stamp_text() {
  case "$1" in
    unstamped) echo unstamped ;;
    *dirty=no) local commit=${1#commit=}; echo "${commit:0:10}" ;;
    *) local commit=${1#commit=}; echo "${commit:0:10} with changes" ;;
  esac
}

# Rebuilds this tree's installs that are not its build: another commit's, a tree with changes', unstamped or missing
# (see the header). On CI, stops the run on a stamp of another commit and builds nothing.
fresh_installs() {
  local dirs=("$LOAD_INSTALL") dir stamp tree stale=""
  if [ -z "$(opt install "")" ]; then dirs=("$SERVER_INSTALL" "${dirs[@]}"); fi
  tree=$(tree_stamp)
  for dir in "${dirs[@]}"; do
    stamp=$(install_stamp "$dir")
    if [ "$stamp" = "$tree" ] && [ "${tree##*dirty=}" = no ]; then continue; fi
    if [ -n "${CI:-}" ]; then
      if [ "$stamp" != unstamped ] && [ "${stamp%% *}" != "${tree%% *}" ]; then
        log "$dir is the build of $(stamp_text "$stamp"), not of this tree ($(stamp_text "$tree")):" \
          "on CI the builds are the workflow's, so the run stops"
        exit 2
      fi
      continue
    fi
    stale="$stale ${dir##*/} ($(stamp_text "$stamp"))"
  done
  if [ -n "$stale" ]; then
    log "this tree is $(stamp_text "$tree"); its installs are not its build:$stale; rebuilding"
    build
  fi
}

# Names the builds the run measures with: the server's (its --install's, or this tree's) and the load generator's
# stamps, in the log and in $OUT/install.txt (`server=<stamp>`, `load=<stamp>`).
record_installs() {
  local server load
  server=$(install_stamp "$(opt install "$SERVER_INSTALL")")
  load=$(install_stamp "$LOAD_INSTALL")
  printf 'server=%s\nload=%s\n' "$server" "$load" >"$OUT/install.txt"
  log "server build $(stamp_text "$server"), load generator $(stamp_text "$load")"
}
