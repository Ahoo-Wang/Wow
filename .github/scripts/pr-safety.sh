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

set -euo pipefail

check_safety() {
  local dependency_pr=$1
  local removed_files=$2
  local changed_files=$3
  local base_files=$4
  local head_files=$5

  if [[ $dependency_pr == true && $removed_files -gt 0 ]]; then
    printf '::error::Dependency PRs must not delete tracked files (%s deleted).\n' "$removed_files" >&2
    return 1
  fi
  if (( changed_files > 3000 )); then
    printf '::error::PR changes %s files; GitHub only exposes the first 3000 files for inspection.\n' "$changed_files" >&2
    return 1
  fi
  if (( base_files == 0 )); then
    printf '::error::Base tree unexpectedly contains no tracked files.\n' >&2
    return 1
  fi
  if (( removed_files * 10 > base_files || head_files * 10 < base_files * 9 )); then
    printf '::error::PR removes more than 10%% of the repository (%s removed; base=%s, head=%s).\n' \
      "$removed_files" "$base_files" "$head_files" >&2
    return 1
  fi
}

run_case() {
  local name=$1
  local expected=$2
  shift 2

  local actual
  if check_safety "$@" >/dev/null 2>&1; then
    actual=0
  else
    actual=$?
  fi
  if (( actual != expected )); then
    printf 'FAIL: %s (expected %s, got %s)\n' "$name" "$expected" "$actual" >&2
    return 1
  fi
}

if [[ ${1:-} == '--self-test' ]]; then
  failures=0
  run_case 'normal dependency update passes' 0 true 0 2 2834 2834 || ((failures += 1))
  run_case 'dependency update cannot delete files' 1 true 1 2 2834 2833 || ((failures += 1))
  run_case 'ordinary PR cannot remove over ten percent of the repository' 1 false 284 284 2834 2550 || ((failures += 1))
  run_case 'ordinary small deletion passes' 0 false 1 1 2834 2833 || ((failures += 1))
  run_case 'PRs beyond GitHub file inspection limit fail closed' 1 false 0 3001 2834 5835 || ((failures += 1))
  run_case 'an empty base tree fails closed' 1 false 0 0 0 1 || ((failures += 1))
  (( failures == 0 )) || exit 1
  printf 'PR safety self-test passed.\n'
  exit 0
fi

: "${GH_REPO:?GH_REPO is required}"
: "${PR_NUMBER:?PR_NUMBER is required}"

count_tree_files() {
  local response
  response=$(gh api "repos/${GH_REPO}/git/trees/$1?recursive=1")
  if [[ $(jq -r '.truncated' <<<"$response") == true ]]; then
    printf '::error::GitHub truncated tree %s; refusing to evaluate an incomplete repository.\n' "$1" >&2
    return 1
  fi
  jq -r '[.tree[] | select(.type == "blob")] | length' <<<"$response"
}

pr=$(gh api "repos/${GH_REPO}/pulls/${PR_NUMBER}")
base_sha=$(jq -r '.base.sha' <<<"$pr")
head_sha=$(jq -r '.head.sha' <<<"$pr")
changed_files=$(jq -r '.changed_files' <<<"$pr")
dependency_pr=$(jq -r \
  '(.head.ref | startswith("renovate/")) or any(.labels[]?; .name == "dependencies")' \
  <<<"$pr")
removed_files=$(gh api --paginate --slurp \
  "repos/${GH_REPO}/pulls/${PR_NUMBER}/files?per_page=100" |
  jq '[.[][] | select(.status == "removed")] | length')
base_files=$(count_tree_files "$base_sha")
head_files=$(count_tree_files "$head_sha")

printf 'PR safety: dependency=%s, changed=%s, removed=%s, base=%s, head=%s\n' \
  "$dependency_pr" "$changed_files" "$removed_files" "$base_files" "$head_files"
check_safety "$dependency_pr" "$removed_files" "$changed_files" "$base_files" "$head_files"
