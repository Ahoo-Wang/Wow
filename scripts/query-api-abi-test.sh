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
readonly CLASS_OVERRIDES="$TEMP_DIR/class-overrides.tsv"
mkdir -p "$ARTIFACTS" "$BASELINES"
printf 'mini\tmini.jar\tfixture/\n' >"$MANIFEST"
: >"$ALLOWLIST"
: >"$CLASS_OVERRIDES"

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

readonly DEFAULT_POLICY_ROOT="$TEMP_DIR/default-policy-root"
mkdir -p "$DEFAULT_POLICY_ROOT/scripts" "$DEFAULT_POLICY_ROOT/config/query-api" \
    "$DEFAULT_POLICY_ROOT/artifacts" "$DEFAULT_POLICY_ROOT/baselines"
cp "$ABI_SCRIPT" "$DEFAULT_POLICY_ROOT/scripts/query-api-abi.sh"
printf 'mini\tmini.jar\tfixture/\n' >"$DEFAULT_POLICY_ROOT/config/query-api/modules.tsv"
printf 'mini\nrequired\n' >"$DEFAULT_POLICY_ROOT/config/query-api/expected-modules.txt"
: >"$DEFAULT_POLICY_ROOT/config/query-api/class-overrides.tsv"
: >"$DEFAULT_POLICY_ROOT/config/query-api/approved-removals.txt"
cp "$TEMP_DIR/v1/mini.jar" "$DEFAULT_POLICY_ROOT/artifacts/mini.jar"
cp "$BASELINES/mini-8.x.baseline" "$DEFAULT_POLICY_ROOT/baselines/mini-8.x.baseline"
ln -s "config/query-api/modules.tsv" "$DEFAULT_POLICY_ROOT/modules-alias.tsv"

expect_default_manifest_alias_failure() {
    local scenario="$1"
    local manifest_alias="$2"
    # shellcheck disable=SC2016 # Expand positional parameters only inside the child shell.
    expect_failure "$scenario" 'Missing expected manifest module: required' bash -c '
        cd "$1"
        bash scripts/query-api-abi.sh check \
            --manifest "$2" \
            --artifacts-dir "$1/artifacts" \
            --baseline-dir "$1/baselines" \
            --allowlist "$1/config/query-api/approved-removals.txt" \
            --class-overrides "$1/config/query-api/class-overrides.tsv"
    ' _ "$DEFAULT_POLICY_ROOT" "$manifest_alias"
}

expect_default_manifest_alias_failure default_manifest_relative_alias_keeps_expected_policy \
    'config/query-api/modules.tsv'
expect_default_manifest_alias_failure default_manifest_dot_alias_keeps_expected_policy \
    "$DEFAULT_POLICY_ROOT/config/query-api/./modules.tsv"
expect_default_manifest_alias_failure default_manifest_symlink_alias_keeps_expected_policy \
    "$DEFAULT_POLICY_ROOT/modules-alias.tsv"

printf '\tmini\tmini.jar\tfixture/\n' >"$TEMP_DIR/leading-empty-manifest-field.tsv"
expect_failure leading_empty_manifest_field_is_rejected 'Invalid manifest row' bash "$ABI_SCRIPT" check \
    --manifest "$TEMP_DIR/leading-empty-manifest-field.tsv" --artifacts-dir "$ARTIFACTS" \
    --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST" --class-overrides "$CLASS_OVERRIDES"

printf 'mini\t\tmini.jar\tfixture/\n' >"$TEMP_DIR/middle-empty-manifest-field.tsv"
expect_failure middle_empty_manifest_field_is_rejected 'Invalid manifest row' bash "$ABI_SCRIPT" check \
    --manifest "$TEMP_DIR/middle-empty-manifest-field.tsv" --artifacts-dir "$ARTIFACTS" \
    --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST" --class-overrides "$CLASS_OVERRIDES"

printf 'mini\tmini.jar\tfixture/\t\n' >"$TEMP_DIR/trailing-empty-manifest-field.tsv"
expect_failure trailing_empty_manifest_field_is_rejected 'Invalid manifest row' bash "$ABI_SCRIPT" check \
    --manifest "$TEMP_DIR/trailing-empty-manifest-field.tsv" --artifacts-dir "$ARTIFACTS" \
    --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST" --class-overrides "$CLASS_OVERRIDES"

printf 'mini\tmini.jar\tfixture/\textra\n' >"$TEMP_DIR/extra-manifest-field.tsv"
expect_failure extra_manifest_field_is_rejected 'Invalid manifest row' bash "$ABI_SCRIPT" check \
    --manifest "$TEMP_DIR/extra-manifest-field.tsv" --artifacts-dir "$ARTIFACTS" \
    --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST" --class-overrides "$CLASS_OVERRIDES"

printf '\tmini\tfixture/Api.class\tinclude\tretained-public-facade\n' \
    >"$TEMP_DIR/leading-empty-class-override-field.tsv"
expect_failure leading_empty_class_override_field_is_rejected 'Invalid class override row' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" \
    --allowlist "$ALLOWLIST" --class-overrides "$TEMP_DIR/leading-empty-class-override-field.tsv"

printf 'mini\t\tfixture/Api.class\tinclude\tretained-public-facade\n' \
    >"$TEMP_DIR/middle-empty-class-override-field.tsv"
expect_failure middle_empty_class_override_field_is_rejected 'Invalid class override row' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" \
    --allowlist "$ALLOWLIST" --class-overrides "$TEMP_DIR/middle-empty-class-override-field.tsv"

printf 'mini\tfixture/Api.class\tinclude\tretained-public-facade\t\n' \
    >"$TEMP_DIR/trailing-empty-class-override-field.tsv"
expect_failure trailing_empty_class_override_field_is_rejected 'Invalid class override row' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" \
    --allowlist "$ALLOWLIST" --class-overrides "$TEMP_DIR/trailing-empty-class-override-field.tsv"

printf 'mini\tfixture/Api.class\tinclude\tretained-public-facade\textra\n' \
    >"$TEMP_DIR/extra-class-override-field.tsv"
expect_failure extra_class_override_field_is_rejected 'Invalid class override row' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" \
    --allowlist "$ALLOWLIST" --class-overrides "$TEMP_DIR/extra-class-override-field.tsv"

printf 'mini\tmini.jar\tfixture/\nmini\tmini.jar\tfixture/\n' >"$TEMP_DIR/duplicate-modules.tsv"
expect_failure duplicate_manifest_module_is_rejected 'Duplicate manifest module or baseline target: mini' bash "$ABI_SCRIPT" check \
    --manifest "$TEMP_DIR/duplicate-modules.tsv" --artifacts-dir "$ARTIFACTS" \
    --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST" --class-overrides "$CLASS_OVERRIDES"

printf 'mini\nrequired\n' >"$TEMP_DIR/missing-expected-modules.txt"
expect_failure missing_expected_manifest_module_is_rejected 'Missing expected manifest module: required' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --expected-modules "$TEMP_DIR/missing-expected-modules.txt" \
    --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

printf 'mini\n' >"$TEMP_DIR/exact-expected-modules.txt"
expect_success exact_expected_manifest_modules_are_allowed bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --expected-modules "$TEMP_DIR/exact-expected-modules.txt" \
    --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

printf 'mini\tmini.jar\tfixture/\nextra\tmini.jar\tfixture/\n' >"$TEMP_DIR/unexpected-module.tsv"
expect_failure unexpected_manifest_module_is_rejected 'Unexpected manifest module: extra' bash "$ABI_SCRIPT" check \
    --manifest "$TEMP_DIR/unexpected-module.tsv" --expected-modules "$TEMP_DIR/exact-expected-modules.txt" \
    --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST" \
    --class-overrides "$CLASS_OVERRIDES"

printf 'mini\nmini\n' >"$TEMP_DIR/duplicate-expected-modules.txt"
expect_failure duplicate_expected_module_is_rejected 'Duplicate expected module: mini' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --expected-modules "$TEMP_DIR/duplicate-expected-modules.txt" \
    --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

: >"$TEMP_DIR/empty-expected-modules.txt"
expect_failure empty_expected_modules_are_rejected 'Expected modules are empty' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --expected-modules "$TEMP_DIR/empty-expected-modules.txt" \
    --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

expect_failure missing_expected_modules_file_is_rejected 'Expected modules do not exist' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --expected-modules "$TEMP_DIR/absent-expected-modules.txt" \
    --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

printf '../escape\n' >"$TEMP_DIR/invalid-expected-modules.txt"
expect_failure invalid_expected_module_is_rejected 'Invalid expected module: ../escape' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --expected-modules "$TEMP_DIR/invalid-expected-modules.txt" \
    --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

awk -F '\t' '$1 != "wow-cosec"' "$ROOT_DIR/config/query-api/modules.tsv" \
    >"$TEMP_DIR/production-missing-cosec.tsv"
expect_failure production_expected_module_omission_is_rejected 'Missing expected manifest module: wow-cosec' \
    bash "$ABI_SCRIPT" check \
    --manifest "$TEMP_DIR/production-missing-cosec.tsv" \
    --expected-modules "$ROOT_DIR/config/query-api/expected-modules.txt" \
    --class-overrides "$ROOT_DIR/config/query-api/class-overrides.tsv" \
    --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

printf '../escape\tmini.jar\tfixture/\n' >"$TEMP_DIR/invalid-module.tsv"
expect_failure invalid_manifest_module_is_rejected 'Invalid manifest module: ../escape' bash "$ABI_SCRIPT" check \
    --manifest "$TEMP_DIR/invalid-module.tsv" --artifacts-dir "$ARTIFACTS" \
    --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST" --class-overrides "$CLASS_OVERRIDES"

printf 'mini\tmini.jar\tfixture/;;other/\n' >"$TEMP_DIR/empty-prefix-component.tsv"
expect_failure empty_manifest_prefix_component_is_rejected 'Invalid manifest prefix components for module mini' bash "$ABI_SCRIPT" check \
    --manifest "$TEMP_DIR/empty-prefix-component.tsv" --artifacts-dir "$ARTIFACTS" \
    --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST" --class-overrides "$CLASS_OVERRIDES"

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

cp "$TEMP_DIR/v1/mini.jar" "$ARTIFACTS/mini.jar"
expect_failure present_allowlist_is_rejected 'Allowlist symbol is still present in current surface' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

printf '%s\n' 'fixture.Api#public void *();|descriptor: ()V' >"$ALLOWLIST"
expect_failure wildcard_allowlist_is_rejected 'Allowlist symbol must be exact and cannot contain wildcard' \
    bash "$ABI_SCRIPT" check --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" \
    --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

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
arguments=()
classpath_seen=0
for ((index = 1; index <= $#; index++)); do
    argument="${!index}"
    if [[ "$argument" == "-classpath" ]]; then
        next_index=$((index + 1))
        classpath="${!next_index}"
        [[ "$classpath" == "$EXPECTED_JAVAP_CLASSPATH" ]] || {
            echo "unexpected javap classpath: $classpath" >&2
            exit 79
        }
        arguments+=("$argument" "${classpath//;/:}")
        classpath_seen=1
        index=$next_index
    else
        arguments+=("$argument")
    fi
done
[[ "$classpath_seen" -eq 1 ]] || {
    echo "javap did not receive -classpath" >&2
    exit 79
}
exec "$JAVAP_REAL" "${arguments[@]}"
EOF
chmod +x "$TEMP_DIR/jdk-tool-wrapper/javap"
cp "$TEMP_DIR/external-api/mini.jar" "$ARTIFACTS/mini.jar"
expect_success external_runtime_classpath_is_passed_to_javap env \
    PATH="$TEMP_DIR/jdk-tool-wrapper:$PATH" \
    EXPECTED_JAVAP_CLASSPATH="$ARTIFACTS/mini.jar:$TEMP_DIR/external/dependency.jar" \
    JAVAP_REAL="$(command -v javap)" \
    bash "$ABI_SCRIPT" dump \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/external-baselines" \
    --allowlist "$ALLOWLIST" --runtime-classpath "$TEMP_DIR/external/dependency.jar"

expect_failure invalid_classpath_separator_is_rejected 'Invalid classpath separator: ,' bash "$ABI_SCRIPT" dump \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/external-invalid-separator-baselines" \
    --allowlist "$ALLOWLIST" --runtime-classpath "$TEMP_DIR/external/dependency.jar" --classpath-separator ','

expect_success semicolon_runtime_classpath_is_passed_to_javap env \
    PATH="$TEMP_DIR/jdk-tool-wrapper:$PATH" \
    EXPECTED_JAVAP_CLASSPATH="$ARTIFACTS/mini.jar;$TEMP_DIR/external/dependency.jar" \
    JAVAP_REAL="$(command -v javap)" \
    bash "$ABI_SCRIPT" dump \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/external-semicolon-baselines" \
    --allowlist "$ALLOWLIST" --runtime-classpath "$TEMP_DIR/external/dependency.jar" --classpath-separator ';'

if grep -Fq 'fixture.PackagePrivate' "$TEMP_DIR/external-baselines/mini-8.x.baseline"; then
    fail 'package-private class should not be present in ABI baseline'
fi
echo 'PASS: package_private_class_is_excluded'

expect_failure missing_jdk_tool_is_rejected 'Missing required JDK tool: javap' env PATH="$TEMP_DIR/no-jdk-tools" /bin/bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$BASELINES" --allowlist "$ALLOWLIST"

compile_classification_api() {
    local output_dir="$1"
    local variant="$2"
    mkdir -p "$output_dir/src/fixture" "$output_dir/classes"
    if [[ "$variant" != "public-deletion" ]]; then
        if [[ "$variant" == "public-descriptor-change" ]]; then
            printf '%s\n' 'package fixture; public final class QueryableKt { public static int isEmpty() { return 0; } }' \
                >"$output_dir/src/fixture/QueryableKt.java"
        elif [[ "$variant" == "public-visibility-change" ]]; then
            printf '%s\n' 'package fixture; final class QueryableKt { public static boolean isEmpty() { return true; } }' \
                >"$output_dir/src/fixture/QueryableKt.java"
        else
            printf '%s\n' 'package fixture; public final class QueryableKt { public static boolean isEmpty() { return true; } }' \
                >"$output_dir/src/fixture/QueryableKt.java"
        fi
        printf '%s\n' 'package fixture; public final class DslKt { public static void query() {} }' \
            >"$output_dir/src/fixture/DslKt.java"
        printf '%s\n' 'package fixture; public final class DataMaskingKt { public static Object tryMask(Object value) { return value; } }' \
            >"$output_dir/src/fixture/DataMaskingKt.java"
    fi
    if [[ "$variant" != "internal-deletion" ]]; then
        if [[ "$variant" == "internal-change" ]]; then
            printf '%s\n' 'package fixture; public final class InternalFacadeKt { public static long internalOnly() { return 1L; } }' \
                >"$output_dir/src/fixture/InternalFacadeKt.java"
            printf '%s\n' 'package fixture; public final class InternalImpl { public long internalOnly() { return 1L; } }' \
                >"$output_dir/src/fixture/InternalImpl.java"
        else
            printf '%s\n' 'package fixture; public final class InternalFacadeKt { public static int internalOnly() { return 1; } }' \
                >"$output_dir/src/fixture/InternalFacadeKt.java"
            printf '%s\n' 'package fixture; public final class InternalImpl { public int internalOnly() { return 1; } }' \
                >"$output_dir/src/fixture/InternalImpl.java"
        fi
    fi
    if [[ "$variant" == "unclassified-facade" ]]; then
        printf '%s\n' 'package fixture; public final class NewFacadeKt { public static void newApi() {} }' \
            >"$output_dir/src/fixture/NewFacadeKt.java"
    fi
    javac -d "$output_dir/classes" "$output_dir"/src/fixture/*.java
    jar --create --file "$output_dir/mini.jar" -C "$output_dir/classes" .
}

cat >"$CLASS_OVERRIDES" <<'EOF'
mini	fixture/QueryableKt.class	include	retained-public-facade
mini	fixture/DslKt.class	include	retained-public-facade
mini	fixture/DataMaskingKt.class	include	retained-public-facade
mini	fixture/InternalFacadeKt.class	exclude	kotlin-internal
mini	fixture/InternalImpl.class	exclude	kotlin-internal
EOF

compile_classification_api "$TEMP_DIR/classification-v1" v1
cp "$TEMP_DIR/classification-v1/mini.jar" "$ARTIFACTS/mini.jar"
expect_success classified_facades_dump bash "$ABI_SCRIPT" dump \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/classification-baselines" \
    --allowlist "$ALLOWLIST" --class-overrides "$CLASS_OVERRIDES"
grep -Fq 'fixture.QueryableKt#' "$TEMP_DIR/classification-baselines/mini-8.x.baseline" || \
    fail 'classified retained facade should be present in ABI baseline'
if grep -Fq 'fixture.Internal' "$TEMP_DIR/classification-baselines/mini-8.x.baseline"; then
    fail 'classified Kotlin internal classes should not be present in ABI baseline'
fi

compile_classification_api "$TEMP_DIR/public-deletion" public-deletion
cp "$TEMP_DIR/public-deletion/mini.jar" "$ARTIFACTS/mini.jar"
expect_failure classified_public_facade_deletion_is_rejected 'Configured included class is absent' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/classification-baselines" \
    --allowlist "$ALLOWLIST" --class-overrides "$CLASS_OVERRIDES"
expect_failure classified_public_facade_deletion_is_rejected_during_dump \
    'Configured included class is absent' bash "$ABI_SCRIPT" dump \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/public-deletion-baselines" \
    --allowlist "$ALLOWLIST" --class-overrides "$CLASS_OVERRIDES"

compile_classification_api "$TEMP_DIR/public-descriptor-change" public-descriptor-change
cp "$TEMP_DIR/public-descriptor-change/mini.jar" "$ARTIFACTS/mini.jar"
expect_failure classified_public_facade_descriptor_change_is_rejected 'Unapproved removed ABI symbol' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/classification-baselines" \
    --allowlist "$ALLOWLIST" --class-overrides "$CLASS_OVERRIDES"

compile_classification_api "$TEMP_DIR/internal-change" internal-change
cp "$TEMP_DIR/internal-change/mini.jar" "$ARTIFACTS/mini.jar"
expect_success classified_internal_descriptor_change_is_allowed bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/classification-baselines" \
    --allowlist "$ALLOWLIST" --class-overrides "$CLASS_OVERRIDES"

compile_classification_api "$TEMP_DIR/internal-deletion" internal-deletion
cp "$TEMP_DIR/internal-deletion/mini.jar" "$ARTIFACTS/mini.jar"
expect_failure classified_internal_deletion_with_stale_exclude_is_rejected \
    'Configured excluded class is absent' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/classification-baselines" \
    --allowlist "$ALLOWLIST" --class-overrides "$CLASS_OVERRIDES"

awk -F '\t' '$2 != "fixture/InternalFacadeKt.class" && $2 != "fixture/InternalImpl.class"' \
    "$CLASS_OVERRIDES" >"$TEMP_DIR/internal-deletion-class-overrides.tsv"
expect_success classified_internal_deletion_with_synchronized_overrides_is_allowed bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/classification-baselines" \
    --allowlist "$ALLOWLIST" --class-overrides "$TEMP_DIR/internal-deletion-class-overrides.tsv"

compile_classification_api "$TEMP_DIR/unclassified-facade" unclassified-facade
cp "$TEMP_DIR/unclassified-facade/mini.jar" "$ARTIFACTS/mini.jar"
expect_failure unclassified_kotlin_facade_is_rejected 'Unclassified Kotlin facade' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/classification-baselines" \
    --allowlist "$ALLOWLIST" --class-overrides "$CLASS_OVERRIDES"

cp "$CLASS_OVERRIDES" "$TEMP_DIR/duplicate-class-overrides.tsv"
printf '%s\n' 'mini	fixture/QueryableKt.class	include	retained-public-facade' >>"$TEMP_DIR/duplicate-class-overrides.tsv"
expect_failure duplicate_class_override_is_rejected 'Duplicate configured class' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/classification-baselines" \
    --allowlist "$ALLOWLIST" --class-overrides "$TEMP_DIR/duplicate-class-overrides.tsv"

sed 's#fixture/InternalImpl.class#fixture/TypoInternalImpl.class#' "$CLASS_OVERRIDES" \
    >"$TEMP_DIR/stale-class-overrides.tsv"
cp "$TEMP_DIR/classification-v1/mini.jar" "$ARTIFACTS/mini.jar"
expect_failure stale_excluded_class_is_rejected_during_dump 'Configured excluded class is absent' bash "$ABI_SCRIPT" dump \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/stale-baselines" \
    --allowlist "$ALLOWLIST" --class-overrides "$TEMP_DIR/stale-class-overrides.tsv"

sed 's/\texclude\tkotlin-internal$/\tunknown\tkotlin-internal/' "$CLASS_OVERRIDES" \
    >"$TEMP_DIR/invalid-action-class-overrides.tsv"
expect_failure invalid_class_override_action_is_rejected 'Invalid class override action' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/classification-baselines" \
    --allowlist "$ALLOWLIST" --class-overrides "$TEMP_DIR/invalid-action-class-overrides.tsv"

cp "$CLASS_OVERRIDES" "$TEMP_DIR/unknown-module-class-overrides.tsv"
printf '%s\n' 'unknown	fixture/UnknownKt.class	exclude	kotlin-internal' \
    >>"$TEMP_DIR/unknown-module-class-overrides.tsv"
expect_failure unknown_class_override_module_is_rejected 'Configured class references unknown or duplicate module' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/classification-baselines" \
    --allowlist "$ALLOWLIST" --class-overrides "$TEMP_DIR/unknown-module-class-overrides.tsv"

cp "$CLASS_OVERRIDES" "$TEMP_DIR/outside-prefix-class-overrides.tsv"
printf '%s\n' 'mini	outside/OtherKt.class	exclude	kotlin-internal' \
    >>"$TEMP_DIR/outside-prefix-class-overrides.tsv"
expect_failure outside_prefix_class_override_is_rejected 'Configured class is outside module prefixes' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/classification-baselines" \
    --allowlist "$ALLOWLIST" --class-overrides "$TEMP_DIR/outside-prefix-class-overrides.tsv"

expect_failure missing_class_overrides_is_rejected 'Class overrides do not exist' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/classification-baselines" \
    --allowlist "$ALLOWLIST" --class-overrides "$TEMP_DIR/missing-class-overrides.tsv"

sed 's/retained-public-facade/unknown-reason/' "$CLASS_OVERRIDES" \
    >"$TEMP_DIR/invalid-reason-class-overrides.tsv"
expect_failure invalid_class_override_reason_is_rejected 'Invalid class override reason' bash "$ABI_SCRIPT" check \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/classification-baselines" \
    --allowlist "$ALLOWLIST" --class-overrides "$TEMP_DIR/invalid-reason-class-overrides.tsv"

compile_classification_api "$TEMP_DIR/public-visibility-change" public-visibility-change
cp "$TEMP_DIR/public-visibility-change/mini.jar" "$ARTIFACTS/mini.jar"
expect_failure non_public_included_class_is_rejected_during_dump 'Configured included class has no public ABI' bash "$ABI_SCRIPT" dump \
    --manifest "$MANIFEST" --artifacts-dir "$ARTIFACTS" --baseline-dir "$TEMP_DIR/non-public-baselines" \
    --allowlist "$ALLOWLIST" --class-overrides "$CLASS_OVERRIDES"

echo 'PASS: all ABI script behavior scenarios'
