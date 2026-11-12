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
# bench.sh's raw-driver ceiling (`measure --program=raw`), sourced by bench.sh: its helpers and settings are in scope.

# The store alone doing what Wow asks of it per command (beta.4's evaluation, §3): on fresh containers, RawStoreBench on
# the install's class path runs the benchmark's domain on the store for a few thousand commands, then each write shape
# with the driver alone on clones of Wow's tables (test/benchmarks/README.md), and writes raw.json to --out. The store's
# container is named to it, so it reads its CPU from the container's cgroup. Every option the harness does not know
# (--shapes, --variants, --connections, --seconds, --warm-up, --log-batch, --snapshot-batch, --aggregates,
# --templates) goes to it. Exit 3 when the install has no RawStoreBench, a baseline older than it.
measure_raw() {
  check_java
  BACKEND=$(opt backend postgres)
  local main=me.ahoo.wow.benchmark.transfer.raw.RawStoreBenchKt
  OUT=$(opt out "$REPO/test/benchmarks/results/e2e/$(date +%Y%m%d-%H%M%S)-measure-raw-$BACKEND")
  mkdir -p "$OUT"
  local install jar class
  install=$(opt install "$REPO/test/benchmarks/server/build/install/benchmark-server")
  class="$(echo "$main" | tr . /).class"
  for jar in "$install"/lib/benchmark-server*.jar; do
    if [ -f "$jar" ] && "$SERVER_JAVA/bin/jar" tf "$jar" | grep -q "^$class$"; then break; fi
    jar=""
  done
  if [ -z "$jar" ]; then log "$install has no ${main##*.}"; exit 3; fi
  trap down EXIT
  down
  log "starting kafka and $BACKEND"
  stores_up "$BACKEND"
  pin_containers "$BACKEND"
  pin server-cpus
  log "measure raw on $BACKEND"
  ${PREFIX[@]+"${PREFIX[@]}"} "$SERVER_JAVA/bin/java" -Xms"$(opt heap 2g)" -Xmx"$(opt heap 2g)" -XX:+UseG1GC \
    -cp "$install/lib/*" "$main" --backend="$BACKEND" --container="wow-e2e-bench-$BACKEND" ${PASS[@]+"${PASS[@]}"} \
    --out="$OUT" 2>&1 | tee "$OUT/measure.log" >&2
  log "results in $OUT"
}
