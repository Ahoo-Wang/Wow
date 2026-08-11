#!/usr/bin/env bash
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

set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly RUNTIME_CLASSPATH_TASK="writeQueryApiRuntimeClasspath"
readonly MODULES=(
    wow-api
    wow-query
    wow-webflux
    wow-spring
    wow-spring-boot-starter
    wow-mongo
    wow-elasticsearch
    wow-cosec
)

tasks=()
for module in "${MODULES[@]}"; do
    tasks+=(":$module:$RUNTIME_CLASSPATH_TASK")
done

"$ROOT_DIR/gradlew" "${tasks[@]}"

missing_entries=0
for module in "${MODULES[@]}"; do
    classpath_file="$ROOT_DIR/$module/build/query-api/runtime-classpath.txt"
    [[ -s "$classpath_file" ]] || {
        echo "Missing runtime classpath output for $module: $classpath_file" >&2
        exit 1
    }
    while IFS= read -r entry; do
        [[ -z "$entry" ]] && continue
        if [[ ! -e "$entry" ]]; then
            echo "Missing runtime classpath entry [$module]: $entry" >&2
            missing_entries=1
        fi
    done < <(tr ':' '\n' <"$classpath_file")
done

[[ "$missing_entries" -eq 0 ]] || exit 1
echo 'PASS: every declared runtime classpath entry exists after the Gradle task graph runs'
