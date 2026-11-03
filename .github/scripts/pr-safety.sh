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

# A maintainer applies this label by hand, after a review confirms the removal
# is deliberate. It lifts the 10% rule and nothing else.
ALLOW_LARGE_REMOVAL_LABEL=allow-large-removal

check_safety() {
  local dependency_pr=$1
  local removed_files=$2
  local changed_files=$3
  local base_files=$4
  local inspected_files=$5
  local allow_large_removal=${6:-false}

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
  if (( removed_files * 10 > base_files )); then
    if [[ $allow_large_removal == true ]]; then
      printf '::warning::PR removes more than 10%% of the repository (%s removed; base=%s); allowed by the %s label.\n' \
        "$removed_files" "$base_files" "$ALLOW_LARGE_REMOVAL_LABEL"
      write_summary "$removed_files" "$base_files"
      return 0
    fi
    printf '::error::PR removes more than 10%% of the repository (%s removed; base=%s). A reviewed, deliberate removal is allowed by the %s label.\n' \
      "$removed_files" "$base_files" "$ALLOW_LARGE_REMOVAL_LABEL" >&2
    return 1
  fi
}

write_summary() {
  local removed_files=$1
  local base_files=$2

  [[ -n ${GITHUB_STEP_SUMMARY:-} ]] || return 0
  printf '### PR Safety: large removal allowed\n\nThis PR removes %s of %s tracked files, more than 10%% of the repository. The `%s` label allows it; every other PR Safety rule still applies.\n' \
    "$removed_files" "$base_files" "$ALLOW_LARGE_REMOVAL_LABEL" >>"$GITHUB_STEP_SUMMARY"
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

# A PR over the 10% rule with the label passes, warns and writes the job summary.
expect_large_removal_allowed() {
  local name=$1
  local removed_files=$2
  local base_files=$3

  local summary output
  summary=$(mktemp)
  if ! output=$(GITHUB_STEP_SUMMARY=$summary \
      check_safety false "$removed_files" "$removed_files" "$base_files" "$removed_files" true 2>&1) \
    || [[ $output != '::warning::'*"($removed_files removed; base=$base_files)"*"$ALLOW_LARGE_REMOVAL_LABEL"* ]] \
    || ! grep -qF "removes $removed_files of $base_files tracked files" "$summary" \
    || ! grep -qF "\`$ALLOW_LARGE_REMOVAL_LABEL\`" "$summary"; then
    printf 'FAIL: %s\n' "$name" >&2
    rm -f "$summary"
    return 1
  fi
  rm -f "$summary"
}

# A PR under the 10% rule passes in silence, label or not.
expect_silent_pass() {
  local name=$1
  shift

  local summary output
  summary=$(mktemp)
  if ! output=$(GITHUB_STEP_SUMMARY=$summary check_safety "$@" 2>&1) || [[ -n $output || -s $summary ]]; then
    printf 'FAIL: %s\n' "$name" >&2
    rm -f "$summary"
    return 1
  fi
  rm -f "$summary"
}

if [[ ${1:-} == '--self-test' ]]; then
  # The job's own summary is not a place for test output.
  unset GITHUB_STEP_SUMMARY
  failures=0
  expect_exit 'normal dependency update passes' 0 check_safety true 0 2 2834 2 || ((failures += 1))
  expect_exit 'dependency update cannot delete files' 1 check_safety true 1 2 2834 2 || ((failures += 1))
  expect_exit 'ordinary PR cannot remove over ten percent of the repository' 1 check_safety false 284 284 2834 284 || ((failures += 1))
  expect_exit 'ordinary small deletion passes' 0 check_safety false 1 1 2834 1 || ((failures += 1))
  expect_exit 'base growth without removals passes' 0 check_safety false 0 1 3200 1 || ((failures += 1))
  expect_exit 'PRs beyond GitHub file inspection limit fail closed' 1 check_safety false 0 3001 2834 3000 || ((failures += 1))
  expect_exit 'an empty base tree fails closed' 1 check_safety false 0 0 0 0 || ((failures += 1))
  expect_exit 'incomplete PR file pages fail closed' 1 check_safety false 0 2 2834 1 || ((failures += 1))
  expect_exit 'the label is off unless the PR has it' 1 check_safety false 2860 2860 9682 2860 false || ((failures += 1))
  expect_large_removal_allowed 'the label allows a deliberate removal over ten percent' 2860 9682 || ((failures += 1))
  expect_silent_pass 'the label says nothing about a small deletion' false 1 1 2834 1 true || ((failures += 1))
  expect_silent_pass 'a small deletion without the label says nothing' false 1 1 2834 1 false || ((failures += 1))
  expect_exit 'the label does not let a dependency update delete files' 1 check_safety true 1 2 2834 2 true || ((failures += 1))
  expect_exit 'the label does not let a dependency update delete over ten percent' 1 check_safety true 2860 2860 9682 2860 true || ((failures += 1))
  expect_exit 'the label does not lift the file inspection limit' 1 check_safety false 2860 3001 9682 3000 true || ((failures += 1))
  expect_exit 'the label does not accept incomplete PR file pages' 1 check_safety false 2860 2860 9682 1000 true || ((failures += 1))
  expect_exit 'the label does not accept an empty base tree' 1 check_safety false 5 5 0 5 true || ((failures += 1))
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
changed_files=$(jq -r '.changed_files' <<<"$pr")
dependency_pr=$(jq -r \
  '(.head.ref | startswith("renovate/")) or any(.labels[]?; .name == "dependencies")' \
  <<<"$pr")
allow_large_removal=$(jq -r --arg label "$ALLOW_LARGE_REMOVAL_LABEL" \
  'any(.labels[]?; .name == $label)' <<<"$pr")
pr_files=$(gh api --paginate --slurp \
  "repos/${GH_REPO}/pulls/${PR_NUMBER}/files?per_page=100")
inspected_files=$(jq '[.[][]] | length' <<<"$pr_files")
removed_files=$(jq '[.[][] | select(.status == "removed")] | length' <<<"$pr_files")
base_files=$(count_tree_files "$base_sha")

printf 'PR safety: dependency=%s, changed=%s, removed=%s, base=%s, %s=%s\n' \
  "$dependency_pr" "$changed_files" "$removed_files" "$base_files" \
  "$ALLOW_LARGE_REMOVAL_LABEL" "$allow_large_removal"
check_safety "$dependency_pr" "$removed_files" "$changed_files" "$base_files" "$inspected_files" \
  "$allow_large_removal"
