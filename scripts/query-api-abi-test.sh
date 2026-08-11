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
    local classpath="${3:-}"
    mkdir -p "$output_dir/src/fixture" "$output_dir/classes"
    printf '%s\n' "$source" >"$output_dir/src/fixture/Api.java"
    if [[ -n "$classpath" ]]; then
        javac -classpath "$classpath" -d "$output_dir/classes" "$output_dir/src/fixture/Api.java"
    else
        javac -d "$output_dir/classes" "$output_dir/src/fixture/Api.java"
    fi
    jar --create --file "$output_dir/mini.jar" -C "$output_dir/classes" .
}

expect_output_contains() {
    local scenario="$1"
    local expected="$2"
    grep -Fq "$expected" "$TEMP_DIR/$scenario.out" || fail "$scenario should report: $expected"
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
public class Api implements Marker {
    public void retained() {}
    public void removed() {}
    public int descriptorChanged() { return 1; }
    protected void hook() {}
}
interface Marker {}'
readonly API_WITH_ADDITION='package fixture;
public class Api implements Marker {
    public void retained() {}
    public void removed() {}
    public int descriptorChanged() { return 1; }
    protected void hook() {}
    public String added() { return "added"; }
}
interface Marker {}'
readonly API_WITH_VISIBILITY_AND_CLASS_HEADER_BREAK='package fixture;
public final class Api {
    public void retained() {}
    public void removed() {}
    public int descriptorChanged() { return 1; }
}
interface Marker {}'
readonly API_WITH_EXTERNAL_PARENT='package fixture;
public class Api extends fixture.dependency.ExternalBase {
    public void retained() {}
}
class PackagePrivate {
    public void internal() {}
}'
readonly API_WITH_REMOVAL='package fixture;
public class Api implements Marker {
    public void retained() {}
    public int descriptorChanged() { return 1; }
    protected void hook() {}
}
interface Marker {}'
readonly API_WITH_DESCRIPTOR_CHANGE='package fixture;
public class Api implements Marker {
    public void retained() {}
    public void removed() {}
    public long descriptorChanged() { return 1L; }
    protected void hook() {}
}
interface Marker {}'

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

compile_api "$API_WITH_VISIBILITY_AND_CLASS_HEADER_BREAK" "$TEMP_DIR/visibility-and-header-break"
cp "$TEMP_DIR/visibility-and-header-break/mini.jar" "$ARTIFACTS/mini.jar"
expect_failure protected_member_and_class_header_break_are_rejected 'Unapproved removed ABI symbol' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"
expect_output_contains protected_member_and_class_header_break_are_rejected 'fixture.Api#class|public class fixture.Api implements fixture.Marker {'
expect_output_contains protected_member_and_class_header_break_are_rejected 'fixture.Api#protected void hook();|descriptor: ()V'

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
: >"$TEMP_DIR/empty-baselines/mini-8.x.baseline"
expect_failure empty_baseline_is_rejected 'Baseline is missing or empty for module mini' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/empty-baselines" --allowlist "$ALLOWLIST"

mkdir -p "$TEMP_DIR/external/src/fixture/dependency" "$TEMP_DIR/external/classes"
printf '%s\n' 'package fixture.dependency; public class ExternalBase {}' >"$TEMP_DIR/external/src/fixture/dependency/ExternalBase.java"
javac -d "$TEMP_DIR/external/classes" "$TEMP_DIR/external/src/fixture/dependency/ExternalBase.java"
jar --create --file "$TEMP_DIR/external/dependency.jar" -C "$TEMP_DIR/external/classes" .
compile_api "$API_WITH_EXTERNAL_PARENT" "$TEMP_DIR/external-api" "$TEMP_DIR/external/dependency.jar"
mkdir -p "$TEMP_DIR/jdk-tool-wrapper"
cat >"$TEMP_DIR/jdk-tool-wrapper/javap" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
for ((index = 1; index <= $#; index++)); do
    if [[ "${!index}" == "-classpath" ]]; then
        next_index=$((index + 1))
        classpath="${!next_index}"
        case ":$classpath:" in
            *":$EXPECTED_RUNTIME_ENTRY:"*) exec "$JAVAP_REAL" "$@" ;;
            *) echo "runtime classpath entry was not passed to javap" >&2; exit 79 ;;
        esac
    fi
done
echo "javap did not receive -classpath" >&2
exit 79
EOF
chmod +x "$TEMP_DIR/jdk-tool-wrapper/javap"
cp "$TEMP_DIR/external-api/mini.jar" "$ARTIFACTS/mini.jar"
expect_success external_runtime_classpath_is_passed_to_javap env \
    PATH="$TEMP_DIR/jdk-tool-wrapper:$PATH" \
    EXPECTED_RUNTIME_ENTRY="$TEMP_DIR/external/dependency.jar" \
    JAVAP_REAL="$(command -v javap)" \
    bash "$ABI_SCRIPT" dump \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/external-baselines" \
    --allowlist "$ALLOWLIST" --runtime-classpath "$TEMP_DIR/external/dependency.jar"

if grep -Fq 'fixture.PackagePrivate' "$TEMP_DIR/external-baselines/mini-8.x.baseline"; then
    fail 'package-private class should not be present in ABI baseline'
fi
echo 'PASS: package_private_class_is_excluded'

expect_failure missing_jdk_tool_is_rejected 'Missing required JDK tool: javap' env PATH="$TEMP_DIR/no-jdk-tools" /bin/bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

echo 'PASS: all ABI script behavior scenarios'
