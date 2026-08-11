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
    echo "Usage: $0 dump|check [--manifest FILE] [--artifacts-dir DIR] [--baseline-dir DIR] [--allowlist FILE]" >&2
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

require_jdk_tools() {
    command -v javap >/dev/null 2>&1 || die "Missing required JDK tool: javap"
    command -v jar >/dev/null 2>&1 || die "Missing required JDK tool: jar"
}

is_included_class() {
    local entry="$1"
    local prefixes="$2"
    local prefix
    [[ "$entry" == *.class ]] || return 1
    [[ "$entry" != META-INF/* ]] || return 1
    [[ "$entry" != *Test* ]] || return 1
    [[ "$entry" != *\$DefaultImpls.class ]] || return 1
    [[ "$entry" != *\$WhenMappings.class ]] || return 1
    [[ "$entry" != *Kt.class ]] || return 1
    IFS=';' read -r -a prefix_list <<<"$prefixes"
    for prefix in "${prefix_list[@]}"; do
        [[ "$entry" == "$prefix"* ]] && return 0
    done
    return 1
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
    local jar_file="$1"
    local prefixes="$2"
    local entry class_name
    while IFS= read -r entry; do
        is_included_class "$entry" "$prefixes" || continue
        class_name="${entry%.class}"
        class_name="${class_name//\//.}"
        javap -classpath "$jar_file" -public -s "$class_name" |
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
                /^public ((abstract|final) )*(class|interface|enum|record) / {
                    print class_name "#class"
                    next
                }
                /^  public / {
                    flush()
                    declaration = $0
                    next
                }
                declaration != "" && /^    descriptor: / {
                    descriptor = $0
                    flush()
                }
            '
    done < <(jar tf "$jar_file" | LC_ALL=C sort)
}

dump_module() {
    local module="$1"
    local jar_glob="$2"
    local prefixes="$3"
    local output_file="$4"
    local jar_file
    jar_file="$(resolve_jar "$module" "$jar_glob")"
    dump_jar_symbols "$jar_file" "$prefixes" | LC_ALL=C sort -u >"$output_file"
    [[ -s "$output_file" ]] || die "No public ABI symbols found for module $module"
}

validate_manifest() {
    [[ -f "$MANIFEST" ]] || die "Manifest does not exist: $MANIFEST"
    [[ -s "$MANIFEST" ]] || die "Manifest is empty: $MANIFEST"
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
        *)
            usage
            die "Unknown argument: $1"
            ;;
    esac
done

case "$COMMAND" in
    dump|check) ;;
    *)
        usage
        die "Unsupported command: $COMMAND"
        ;;
esac

require_jdk_tools
validate_manifest
[[ -f "$ALLOWLIST" ]] || die "Allowlist does not exist: $ALLOWLIST"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/query-api-abi.XXXXXX")"
cleanup() {
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

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
