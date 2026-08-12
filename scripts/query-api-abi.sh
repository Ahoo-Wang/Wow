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
readonly DEFAULT_MANIFEST="$ROOT_DIR/config/query-api/modules.tsv"
readonly DEFAULT_ARTIFACTS_DIR="$ROOT_DIR"
readonly DEFAULT_BASELINE_DIR="$ROOT_DIR/config/query-api"
readonly DEFAULT_ALLOWLIST="$ROOT_DIR/config/query-api/approved-removals.txt"

usage() {
    echo "Usage: $0 dump|check [--manifest FILE] [--artifacts-dir DIR] [--baseline-dir DIR] [--allowlist FILE] [--class-overrides FILE] [--runtime-classpath PATH] [--classpath-separator :|;]" >&2
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

require_jdk_tools() {
    command -v javap >/dev/null 2>&1 || die "Missing required JDK tool: javap"
    command -v jar >/dev/null 2>&1 || die "Missing required JDK tool: jar"
}

entry_matches_prefixes() {
    local entry="$1"
    local prefixes="$2"
    local prefix
    IFS=';' read -r -a prefix_list <<<"$prefixes"
    for prefix in "${prefix_list[@]}"; do
        [[ "$entry" == "$prefix"* ]] && return 0
    done
    return 1
}

configured_class_action() {
    local module="$1"
    local entry="$2"
    awk -F '\t' -v module="$module" -v entry="$entry" \
        '$1 == module && $2 == entry { print $3; exit }' "$NORMALIZED_CLASS_OVERRIDES"
}

is_included_class() {
    local module="$1"
    local entry="$2"
    local prefixes="$3"
    local action
    [[ "$entry" == *.class ]] || return 1
    [[ "$entry" != META-INF/* ]] || return 1
    [[ "$entry" != *Test* ]] || return 1
    [[ "$entry" != *\$DefaultImpls.class ]] || return 1
    [[ "$entry" != *\$WhenMappings.class ]] || return 1
    entry_matches_prefixes "$entry" "$prefixes" || return 1
    action="$(configured_class_action "$module" "$entry")"
    [[ "$action" != "exclude" ]] || return 1
    if [[ "$entry" == *Kt.class ]]; then
        [[ "$action" == "include" ]] || die "Unclassified Kotlin facade [$module]: $entry"
    fi
    return 0
}

resolve_jar() {
    local module="$1"
    local jar_glob="$2"
    local matches=()
    local candidate
    shopt -s nullglob
    matches=("$ARTIFACTS_DIR"/$jar_glob)
    shopt -u nullglob
    local selected=()
    for candidate in "${matches[@]}"; do
        [[ -f "$candidate" ]] || continue
        [[ "$candidate" == *-sources.jar || "$candidate" == *-javadoc.jar ]] && continue
        selected+=("$candidate")
    done
    [[ ${#selected[@]} -gt 0 ]] || die "Missing jar for module $module matching $jar_glob"
    [[ ${#selected[@]} -eq 1 ]] || die "Expected one jar for module $module matching $jar_glob, found ${#selected[@]}"
    printf '%s\n' "${selected[0]}"
}

dump_jar_symbols() {
    local module="$1"
    local jar_file="$2"
    local prefixes="$3"
    local entry class_name class_dump javap_classpath
    javap_classpath="$jar_file"
    if [[ -n "$RUNTIME_CLASSPATH" ]]; then
        javap_classpath="$javap_classpath$CLASSPATH_SEPARATOR$RUNTIME_CLASSPATH"
    fi
    while IFS= read -r entry; do
        is_included_class "$module" "$entry" "$prefixes" || continue
        class_name="${entry%.class}"
        class_name="${class_name//\//.}"
        class_dump="$(mktemp "$WORK_DIR/javap.XXXXXX")"
        if ! javap -classpath "$javap_classpath" -protected -s "$class_name" >"$class_dump"; then
            rm -f "$class_dump"
            die "Unable to inspect ABI class $class_name from $jar_file"
        fi
        awk -v class_name="$class_name" '
                function compact(value) {
                    gsub(/[[:space:]]+/, " ", value)
                    sub(/^ /, "", value)
                    sub(/ $/, "", value)
                    return value
                }
                function flush() {
                    if (declaration != "" && descriptor != "") {
                        print class_name "#" compact(declaration) "|" compact(descriptor)
                    }
                    declaration = ""
                    descriptor = ""
                }
                !class_header_seen && /(^| )(class|interface|enum|record)( |<)/ {
                    class_header_seen = 1
                    if ($0 ~ /^public /) {
                        public_class = 1
                        print class_name "#class|" compact($0)
                    }
                    next
                }
                public_class && /^  (public|protected) / {
                    flush()
                    declaration = $0
                    next
                }
                public_class && declaration != "" && /^    descriptor: / {
                    descriptor = $0
                    flush()
                }
            ' "$class_dump"
        rm -f "$class_dump"
    done < <(jar tf "$jar_file" | LC_ALL=C sort)
}

dump_module() {
    local module="$1"
    local jar_glob="$2"
    local prefixes="$3"
    local output_file="$4"
    local jar_file configured_entry class_name
    jar_file="$(resolve_jar "$module" "$jar_glob")"
    validate_classification_for_module "$module" "$jar_file" "$prefixes"
    dump_jar_symbols "$module" "$jar_file" "$prefixes" | LC_ALL=C sort -u >"$output_file"
    [[ -s "$output_file" ]] || die "No public ABI symbols found for module $module"
    while IFS=$'\t' read -r _ configured_entry _ _; do
        class_name="${configured_entry%.class}"
        class_name="${class_name//\//.}"
        grep -Fq "$class_name#" "$output_file" || \
            die "Configured included class has no public ABI [$module]: $configured_entry"
    done < <(awk -F '\t' -v module="$module" '$1 == module && $3 == "include"' "$NORMALIZED_CLASS_OVERRIDES")
}

validate_manifest() {
    [[ -f "$MANIFEST" ]] || die "Manifest does not exist: $MANIFEST"
    [[ -s "$MANIFEST" ]] || die "Manifest is empty: $MANIFEST"
}

validate_class_overrides() {
    [[ -f "$CLASS_OVERRIDES" ]] || die "Class overrides do not exist: $CLASS_OVERRIDES"
    : >"$NORMALIZED_CLASS_OVERRIDES"
    local module entry action reason extra manifest_prefixes duplicate
    while IFS=$'\t' read -r module entry action reason extra || [[ -n "${module:-}" ]]; do
        [[ -z "${module:-}" || "$module" == \#* ]] && continue
        [[ -n "${entry:-}" && -n "${action:-}" && -n "${reason:-}" && -z "${extra:-}" ]] || \
            die "Invalid class override row for module $module"
        case "$action" in
            include|exclude) ;;
            *) die "Invalid class override action [$module/$entry]: $action" ;;
        esac
        case "$action:$reason" in
            include:retained-public-facade | \
            exclude:approved-filter-context-removal | \
            exclude:synthetic-private-helper | \
            exclude:kotlin-internal) ;;
            *) die "Invalid class override reason [$module/$entry]: $action/$reason" ;;
        esac
        [[ "$entry" == *.class && "$entry" != /* && "$entry" != *../* ]] || \
            die "Invalid configured class entry [$module]: $entry"
        manifest_prefixes="$(awk -F '\t' -v module="$module" '$1 == module { print $3 }' "$MANIFEST")"
        [[ -n "$manifest_prefixes" && "$manifest_prefixes" != *$'\n'* ]] || \
            die "Configured class references unknown or duplicate module: $module"
        entry_matches_prefixes "$entry" "$manifest_prefixes" || \
            die "Configured class is outside module prefixes [$module]: $entry"
        printf '%s\t%s\t%s\t%s\n' "$module" "$entry" "$action" "$reason" >>"$NORMALIZED_CLASS_OVERRIDES"
    done <"$CLASS_OVERRIDES"
    duplicate="$(cut -f1,2 "$NORMALIZED_CLASS_OVERRIDES" | LC_ALL=C sort | uniq -d | head -n 1)"
    [[ -z "$duplicate" ]] || die "Duplicate configured class: ${duplicate//$'\t'/ }"
}

validate_classification_for_module() {
    local module="$1"
    local jar_file="$2"
    local prefixes="$3"
    local jar_entries="$WORK_DIR/$module.jar-entries"
    local configured_entry action current_entry
    jar tf "$jar_file" | LC_ALL=C sort -u >"$jar_entries"
    while IFS=$'\t' read -r _ configured_entry action _; do
        grep -Fxq "$configured_entry" "$jar_entries" && continue
        if [[ "$action" == "include" ]]; then
            die "Configured included class is absent [$module]: $configured_entry"
        fi
        if [[ "$COMMAND" == "dump" ]]; then
            die "Configured excluded class is absent [$module]: $configured_entry"
        fi
    done < <(awk -F '\t' -v module="$module" '$1 == module' "$NORMALIZED_CLASS_OVERRIDES")
    while IFS= read -r current_entry; do
        [[ "$current_entry" == *Kt.class ]] || continue
        entry_matches_prefixes "$current_entry" "$prefixes" || continue
        action="$(configured_class_action "$module" "$current_entry")"
        [[ -n "$action" ]] || die "Unclassified Kotlin facade [$module]: $current_entry"
    done <"$jar_entries"
}

validate_allowlist() {
    [[ -f "$ALLOWLIST" ]] || die "Allowlist does not exist: $ALLOWLIST"
    local all_baselines="$1"
    local symbol
    while IFS= read -r symbol || [[ -n "$symbol" ]]; do
        [[ -z "$symbol" || "$symbol" == \#* ]] && continue
        grep -Fxq "$symbol" "$all_baselines" || die "Allowlist symbol is absent from baseline: $symbol"
    done <"$ALLOWLIST"
}

COMMAND="${1:-}"
[[ -n "$COMMAND" ]] || {
    usage
    exit 1
}
shift

MANIFEST="$DEFAULT_MANIFEST"
ARTIFACTS_DIR="$DEFAULT_ARTIFACTS_DIR"
BASELINE_DIR="$DEFAULT_BASELINE_DIR"
ALLOWLIST="$DEFAULT_ALLOWLIST"
CLASS_OVERRIDES=""
RUNTIME_CLASSPATH=""
CLASSPATH_SEPARATOR=":"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --manifest)
            MANIFEST="$2"
            shift 2
            ;;
        --artifacts-dir)
            ARTIFACTS_DIR="$2"
            shift 2
            ;;
        --baseline-dir)
            BASELINE_DIR="$2"
            shift 2
            ;;
        --allowlist)
            ALLOWLIST="$2"
            shift 2
            ;;
        --class-overrides)
            CLASS_OVERRIDES="$2"
            shift 2
            ;;
        --runtime-classpath)
            RUNTIME_CLASSPATH="$2"
            shift 2
            ;;
        --classpath-separator)
            CLASSPATH_SEPARATOR="$2"
            shift 2
            ;;
        *)
            usage
            die "Unknown argument: $1"
            ;;
    esac
done

case "$CLASSPATH_SEPARATOR" in
    :|';') ;;
    *) die "Invalid classpath separator: $CLASSPATH_SEPARATOR" ;;
esac

case "$COMMAND" in
    dump|check) ;;
    *)
        usage
        die "Unsupported command: $COMMAND"
        ;;
esac

require_jdk_tools
validate_manifest
if [[ -z "$CLASS_OVERRIDES" ]]; then
    CLASS_OVERRIDES="$(dirname "$MANIFEST")/class-overrides.tsv"
fi
[[ -f "$ALLOWLIST" ]] || die "Allowlist does not exist: $ALLOWLIST"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/query-api-abi.XXXXXX")"
cleanup() {
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

NORMALIZED_CLASS_OVERRIDES="$WORK_DIR/class-overrides.normalized.tsv"
validate_class_overrides

all_baselines="$WORK_DIR/all-baselines"
: >"$all_baselines"

while IFS=$'\t' read -r module jar_glob prefixes extra || [[ -n "${module:-}" ]]; do
    [[ -z "${module:-}" || "$module" == \#* ]] && continue
    [[ -n "${jar_glob:-}" && -n "${prefixes:-}" && -z "${extra:-}" ]] || die "Invalid manifest row for module $module"
    current="$WORK_DIR/$module.current"
    dump_module "$module" "$jar_glob" "$prefixes" "$current"
    baseline="$BASELINE_DIR/$module-8.x.baseline"
    if [[ "$COMMAND" == "dump" ]]; then
        mkdir -p "$BASELINE_DIR"
        temporary_baseline="$BASELINE_DIR/.$module-8.x.baseline.tmp.$$"
        cp "$current" "$temporary_baseline"
        mv "$temporary_baseline" "$baseline"
        echo "Wrote baseline: $baseline"
    else
        [[ -s "$baseline" ]] || die "Baseline is missing or empty for module $module: $baseline"
        cat "$baseline" >>"$all_baselines"
        missing="$WORK_DIR/$module.missing"
        comm -23 "$baseline" "$current" >"$missing"
        if [[ -s "$missing" ]]; then
            unresolved="$WORK_DIR/$module.unresolved"
            if [[ -s "$ALLOWLIST" ]]; then
                grep -Fvx -f "$ALLOWLIST" "$missing" >"$unresolved" || true
            else
                cp "$missing" "$unresolved"
            fi
            if [[ -s "$unresolved" ]]; then
                while IFS= read -r symbol; do
                    echo "Unapproved removed ABI symbol [$module]: $symbol" >&2
                done <"$unresolved"
                exit 1
            fi
        fi
        echo "Checked module: $module"
    fi
done <"$MANIFEST"

if [[ "$COMMAND" == "check" ]]; then
    [[ -s "$all_baselines" ]] || die "No baselines were read from $BASELINE_DIR"
    validate_allowlist "$all_baselines"
fi

echo "Query API ABI $COMMAND completed successfully"
