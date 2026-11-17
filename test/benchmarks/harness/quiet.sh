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
# Runs a command once the machine is not held for a benchmark batch: while another live owner holds the batch lock
# (lock.sh, one for every worktree on the machine), it waits, saying every 30 s who holds it and since when, then runs
# the command in its place. A lock whose pid is gone holds no one; the holder's own commands (WOW_BENCH_OWNER, or a
# process that descends from its pid) run at once. A session puts it in front of its heavy work:
#
#   test/benchmarks/harness/quiet.sh ./gradlew :kernel:check
#   test/benchmarks/harness/quiet.sh pnpm test
#   test/benchmarks/harness/quiet.sh docker compose up -d
set -euo pipefail

HARNESS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
log() { echo "[quiet] $*" >&2; }
# shellcheck source=lock.sh
. "$HARNESS/lock.sh"

if [ "$#" = 0 ]; then
  log "usage: quiet.sh <command> [<argument>...]"
  exit 2
fi
lock_await_free "[$*]"
exec "$@"
