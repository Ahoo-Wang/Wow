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
readonly ABI_SCRIPT="$ROOT_DIR/scripts/query-api-abi.sh"
readonly TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/query-api-abi-test.XXXXXX")"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

expect_success() {
    local scenario="$1"
    shift
    if ! "$@" >"$TEMP_DIR/$scenario.out" 2>&1; then
        cat "$TEMP_DIR/$scenario.out" >&2
        fail "$scenario should succeed"
    fi
    echo "PASS: $scenario"
}

expect_failure() {
    local scenario="$1"
    local expected="$2"
    shift 2
    if "$@" >"$TEMP_DIR/$scenario.out" 2>&1; then
        cat "$TEMP_DIR/$scenario.out" >&2
        fail "$scenario should fail"
    fi
    if ! grep -Fq "$expected" "$TEMP_DIR/$scenario.out"; then
        cat "$TEMP_DIR/$scenario.out" >&2
        fail "$scenario should explain: $expected"
    fi
    echo "PASS: $scenario"
}

compile_api() {
    local source="$1"
    local output_dir="$2"
    mkdir -p "$output_dir/src/fixture" "$output_dir/classes"
    printf '%s\n' "$source" >"$output_dir/src/fixture/Api.java"
    javac -d "$output_dir/classes" "$output_dir/src/fixture/Api.java"
    jar --create --file "$output_dir/mini.jar" -C "$output_dir/classes" .
}

readonly MANIFEST="$TEMP_DIR/modules.tsv"
readonly ARTIFACTS="$TEMP_DIR/artifacts"
readonly BASELINES="$TEMP_DIR/baselines"
readonly ALLOWLIST="$TEMP_DIR/approved-removals.txt"
mkdir -p "$ARTIFACTS" "$BASELINES"
printf 'mini\tmini.jar\tfixture/\n' >"$MANIFEST"
: >"$ALLOWLIST"

if [[ ! -f "$ABI_SCRIPT" ]]; then
    echo "RED: production script is missing: scripts/query-api-abi.sh" >&2
    exit 1
fi

readonly API_V1='package fixture;
public class Api {
    public void retained() {}
    public void removed() {}
    public int descriptorChanged() { return 1; }
}'
readonly API_WITH_ADDITION='package fixture;
public class Api {
    public void retained() {}
    public void removed() {}
    public int descriptorChanged() { return 1; }
    public String added() { return "added"; }
}'
readonly API_WITH_REMOVAL='package fixture;
public class Api {
    public void retained() {}
    public int descriptorChanged() { return 1; }
}'
readonly API_WITH_DESCRIPTOR_CHANGE='package fixture;
public class Api {
    public void retained() {}
    public void removed() {}
    public long descriptorChanged() { return 1L; }
}'

compile_api "$API_V1" "$TEMP_DIR/v1"
cp "$TEMP_DIR/v1/mini.jar" "$ARTIFACTS/mini.jar"
expect_success dump_v1 bash "$ABI_SCRIPT" dump \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

compile_api "$API_WITH_ADDITION" "$TEMP_DIR/addition"
cp "$TEMP_DIR/addition/mini.jar" "$ARTIFACTS/mini.jar"
expect_success addition_is_allowed bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

compile_api "$API_WITH_REMOVAL" "$TEMP_DIR/removal"
cp "$TEMP_DIR/removal/mini.jar" "$ARTIFACTS/mini.jar"
expect_failure removal_is_rejected 'Unapproved removed ABI symbol' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

compile_api "$API_WITH_DESCRIPTOR_CHANGE" "$TEMP_DIR/descriptor"
cp "$TEMP_DIR/descriptor/mini.jar" "$ARTIFACTS/mini.jar"
expect_failure descriptor_change_is_rejected 'Unapproved removed ABI symbol' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

cp "$TEMP_DIR/removal/mini.jar" "$ARTIFACTS/mini.jar"
grep -F '#public void removed();|descriptor: ()V' "$BASELINES/mini-8.x.baseline" >"$ALLOWLIST"
expect_success exact_allowlist_is_allowed bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

printf '%s\n' 'fixture.Api#public void absent();|descriptor: ()V' >"$ALLOWLIST"
cp "$TEMP_DIR/v1/mini.jar" "$ARTIFACTS/mini.jar"
expect_failure stale_allowlist_is_rejected 'Allowlist symbol is absent from baseline' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

: >"$ALLOWLIST"
expect_failure missing_jar_is_rejected 'Missing jar for module mini' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$TEMP_DIR/no-artifacts" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

mkdir -p "$TEMP_DIR/empty-baselines"
expect_failure empty_baseline_is_rejected 'Baseline is missing or empty for module mini' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/empty-baselines" --allowlist "$ALLOWLIST"

expect_failure missing_jdk_tool_is_rejected 'Missing required JDK tool: javap' env PATH="$TEMP_DIR/no-jdk-tools" /bin/bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

echo 'PASS: all ABI script behavior scenarios'
