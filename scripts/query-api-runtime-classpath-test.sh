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
readonly ORDER_TEST_INIT_SCRIPT="$ROOT_DIR/scripts/query-api-runtime-classpath-order.init.gradle.kts"
readonly ORDER_TEST_MODULE="wow-api"
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

assert_entries_exist() {
    local module="$1"
    local classpath_file="$ROOT_DIR/$module/build/query-api/runtime-classpath.txt"
    [[ -s "$classpath_file" ]] || {
        echo "Missing runtime classpath output for $module: $classpath_file" >&2
        return 1
    }

    local missing_entries=0
    while IFS= read -r entry; do
        [[ -z "$entry" ]] && continue
        if [[ ! -e "$entry" ]]; then
            echo "Missing runtime classpath entry [$module]: $entry" >&2
            missing_entries=1
        fi
    done <"$classpath_file"
    [[ "$missing_entries" -eq 0 ]]
}

assert_source_outputs_are_excluded() {
    local module="$1"
    local classpath_file="$ROOT_DIR/$module/build/query-api/runtime-classpath.txt"
    if grep -E -q "^$ROOT_DIR/$module/build/(classes|resources)/" "$classpath_file"; then
        echo "Runtime dependency classpath incorrectly contains $module main source output" >&2
        return 1
    fi
}

assert_marker_order() {
    local classpath_file="$1"
    local first_marker="$2"
    local second_marker="$3"
    local first_index
    local second_index
    first_index="$(grep -n -F -x "$first_marker" "$classpath_file" | cut -d: -f1 || true)"
    second_index="$(grep -n -F -x "$second_marker" "$classpath_file" | cut -d: -f1 || true)"
    [[ -n "$first_index" && -n "$second_index" && "$first_index" -lt "$second_index" ]] || {
        echo "Runtime classpath did not preserve the expected marker order: $first_marker before $second_marker" >&2
        return 1
    }
}

run_ordered_writer() {
    local marker_directory="$1"
    local order="$2"
    shift 2
    "$ROOT_DIR/gradlew" \
        --init-script "$ORDER_TEST_INIT_SCRIPT" \
        "-DqueryApiRuntimeClasspathOrderTestDirectory=$marker_directory" \
        "-DqueryApiRuntimeClasspathOrderTestOrder=$order" \
        ":$ORDER_TEST_MODULE:$RUNTIME_CLASSPATH_TASK" \
        --console=plain \
        "$@"
}

order_test_parent="${TMPDIR:-/tmp}"
order_test_parent="${order_test_parent%/}"
order_test_directory="$(mktemp -d "$order_test_parent/query-api-runtime-classpath-order.XXXXXX")"
trap 'rm -rf "$order_test_directory"' EXIT
touch "$order_test_directory/first.jar" "$order_test_directory/second.jar"

forward_log="$order_test_directory/forward.log"
reverse_log="$order_test_directory/reverse.log"
run_ordered_writer "$order_test_directory" forward --rerun-tasks >"$forward_log"
assert_marker_order \
    "$ROOT_DIR/$ORDER_TEST_MODULE/build/query-api/runtime-classpath.txt" \
    "$order_test_directory/first.jar" \
    "$order_test_directory/second.jar"
run_ordered_writer "$order_test_directory" reverse >"$reverse_log"
if grep -F -x -q "> Task :$ORDER_TEST_MODULE:$RUNTIME_CLASSPATH_TASK UP-TO-DATE" "$reverse_log"; then
    echo 'Runtime classpath order change incorrectly left writer task UP-TO-DATE' >&2
    exit 1
fi
assert_marker_order \
    "$ROOT_DIR/$ORDER_TEST_MODULE/build/query-api/runtime-classpath.txt" \
    "$order_test_directory/second.jar" \
    "$order_test_directory/first.jar"

tasks=()
for module in "${MODULES[@]}"; do
    tasks+=(":$module:$RUNTIME_CLASSPATH_TASK")
done

"$ROOT_DIR/gradlew" "${tasks[@]}"

for module in "${MODULES[@]}"; do
    assert_entries_exist "$module"
    assert_source_outputs_are_excluded "$module"
done

"$ROOT_DIR/gradlew" :wow-openapi:clean --console=plain >/dev/null
producer_dry_run="$order_test_directory/wow-openapi-producer-dry-run.log"
"$ROOT_DIR/gradlew" \
    :wow-webflux:"$RUNTIME_CLASSPATH_TASK" \
    --dry-run \
    --console=plain >"$producer_dry_run"
grep -F -x -q ':wow-openapi:jar SKIPPED' "$producer_dry_run" || {
    echo 'Runtime classpath task graph does not include :wow-openapi:jar' >&2
    exit 1
}
producer_execution="$order_test_directory/wow-openapi-producer-execution.log"
"$ROOT_DIR/gradlew" :wow-webflux:"$RUNTIME_CLASSPATH_TASK" --console=plain >"$producer_execution"
grep -F -q '> Task :wow-openapi:jar' "$producer_execution" || {
    echo 'Runtime classpath task did not execute or observe the :wow-openapi:jar producer' >&2
    exit 1
}
wow_openapi_jar="$(find "$ROOT_DIR/wow-openapi/build/libs" -maxdepth 1 -type f -name 'wow-openapi-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)"
[[ -n "$wow_openapi_jar" ]] || {
    echo 'Expected :wow-openapi:jar output is absent after writer task execution' >&2
    exit 1
}
grep -F -x -q "$wow_openapi_jar" "$ROOT_DIR/wow-webflux/build/query-api/runtime-classpath.txt" || {
    echo 'Runtime classpath output omits the built :wow-openapi jar' >&2
    exit 1
}

echo 'PASS: every declared runtime classpath entry exists after the Gradle task graph runs'
