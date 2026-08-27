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
  local inspected_files=$6

  if [[ $dependency_pr == true && $removed_files -gt 0 ]]; then
    printf '::error::Dependency PRs must not delete tracked files (%s deleted).\n' "$removed_files" >&2
    return 1
  fi
  if (( changed_files > 3000 )); then
    printf '::error::PR changes %s files; GitHub only exposes the first 3000 files for inspection.\n' "$changed_files" >&2
    return 1
  fi
  if (( inspected_files != changed_files )); then
    printf '::error::GitHub returned %s of %s changed files; refusing to inspect incomplete PR data.\n' \
      "$inspected_files" "$changed_files" >&2
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

count_tree_files_response() {
  local tree_sha=$1
  local response=$2

  if ! jq -e '.truncated == false and (.tree | type == "array")' >/dev/null <<<"$response"; then
    printf '::error::GitHub returned an incomplete tree for %s.\n' "$tree_sha" >&2
    return 1
  fi
  jq -r '[.tree[] | select(.type == "blob")] | length' <<<"$response"
}

expect_exit() {
  local name=$1
  local expected=$2
  shift 2

  local actual
  if "$@" >/dev/null 2>&1; then
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
  expect_exit 'normal dependency update passes' 0 check_safety true 0 2 2834 2834 2 || ((failures += 1))
  expect_exit 'dependency update cannot delete files' 1 check_safety true 1 2 2834 2833 2 || ((failures += 1))
  expect_exit 'ordinary PR cannot remove over ten percent of the repository' 1 check_safety false 284 284 2834 2550 284 || ((failures += 1))
  expect_exit 'ordinary small deletion passes' 0 check_safety false 1 1 2834 2833 1 || ((failures += 1))
  expect_exit 'PRs beyond GitHub file inspection limit fail closed' 1 check_safety false 0 3001 2834 5835 3000 || ((failures += 1))
  expect_exit 'an empty base tree fails closed' 1 check_safety false 0 0 0 1 0 || ((failures += 1))
  expect_exit 'incomplete PR file pages fail closed' 1 check_safety false 0 2 2834 2834 1 || ((failures += 1))
  expect_exit 'complete tree response passes' 0 count_tree_files_response test-tree '{"truncated":false,"tree":[]}' || ((failures += 1))
  expect_exit 'missing truncated flag fails closed' 1 count_tree_files_response test-tree '{"tree":[]}' || ((failures += 1))
  (( failures == 0 )) || exit 1
  printf 'PR safety self-test passed.\n'
  exit 0
fi

: "${GH_REPO:?GH_REPO is required}"
: "${PR_NUMBER:?PR_NUMBER is required}"

count_tree_files() {
  local response
  response=$(gh api "repos/${GH_REPO}/git/trees/$1?recursive=1")
  count_tree_files_response "$1" "$response"
}

pr=$(gh api "repos/${GH_REPO}/pulls/${PR_NUMBER}")
base_sha=$(jq -r '.base.sha' <<<"$pr")
head_sha=$(jq -r '.head.sha' <<<"$pr")
changed_files=$(jq -r '.changed_files' <<<"$pr")
dependency_pr=$(jq -r \
  '(.head.ref | startswith("renovate/")) or any(.labels[]?; .name == "dependencies")' \
  <<<"$pr")
pr_files=$(gh api --paginate --slurp \
  "repos/${GH_REPO}/pulls/${PR_NUMBER}/files?per_page=100")
inspected_files=$(jq '[.[][]] | length' <<<"$pr_files")
removed_files=$(jq '[.[][] | select(.status == "removed")] | length' <<<"$pr_files")
base_files=$(count_tree_files "$base_sha")
head_files=$(count_tree_files "$head_sha")

printf 'PR safety: dependency=%s, changed=%s, removed=%s, base=%s, head=%s\n' \
  "$dependency_pr" "$changed_files" "$removed_files" "$base_files" "$head_files"
check_safety "$dependency_pr" "$removed_files" "$changed_files" "$base_files" "$head_files" "$inspected_files"
