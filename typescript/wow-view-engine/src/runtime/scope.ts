/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * What an injected scope filter does to admission, for both runtimes.
 *
 * A host's condition is judged merged into the config it narrows — that is the
 * only way the depth and node budgets can hold — but it is not part of that
 * config, and the two runtimes have to tell the findings apart. This is where
 * that reading lives.
 */

import type { DataViewConfig, FilterTree, Issue } from '../model/index.js';
import { isFilterGroup, isSimpleTree, mergeFilters } from '../filter/index.js';

/** Stable identity for "nothing was refused", so a reader can compare. */
export const NO_REFUSAL: Issue[] = [];

/**
 * A config as it would run: the scope filter ANDed after its own conditions.
 *
 * A root that is not a group is admission's to report as it stands; merging
 * would turn it into a condition, or lose it.
 */
export function withScopeFilter<C extends DataViewConfig>(
  config: C,
  scope: FilterTree | null,
): C {
  if (!scope || !isFilterGroup(config.filter)) return config;
  return { ...config, filter: mergeFilters(config.filter, scope) };
}

/**
 * Findings on a config judged with its scope, read as findings on the config.
 *
 * Two things the merge does must not leak into the issues. The `filterMode`
 * warning judges what the editor can show, which is the config's own tree;
 * the merged tree is never simple, so the warning is dropped when the draft
 * itself is simple. And a root that is not `and` rides in the merged tree
 * as its first child, so a finding at `['children', 0, …]` is a finding at
 * `[…]` of the draft, and is addressed so — every reader of `issues` reads
 * a path against the draft.
 */
export function withoutScopeModeWarning(
  issues: Issue[],
  config: DataViewConfig,
  scope: FilterTree | null,
): Issue[] {
  if (!scope || !isFilterGroup(config.filter)) return issues;
  const nested = config.filter.op !== 'and';
  const simple = isSimpleTree(config.filter);
  return issues.flatMap(found => {
    if (simple && found.code === 'config.filterMode.not-simple') return [];
    if (nested && found.path[0] === 'children' && found.path[1] === 0)
      return [{ ...found, path: found.path.slice(2) }];
    return [found];
  });
}

/**
 * What an injected scope is refused for: the errors admitting the config with
 * it raises that the config does not raise on its own.
 *
 * The merged findings hold both — the host's condition and whatever the saved
 * view already carried — and only the first kind is a refusal (D17-5). A view
 * that needs fixing is not fixed by leaving the host's condition out, so its
 * own errors keep being the view's; and a host told "this view must be fixed"
 * has nothing it can do about somebody else's saved config, so a refusal must
 * name the one thing the host can take back.
 *
 * Paths survive the merge — the scope is appended after the config's own
 * conditions, and `withoutScopeModeWarning` puts a nested root's back — so a
 * finding is matched by code and path, and one for one: a config carrying an
 * error twice is not answered by a merged tree raising it three times.
 */
export function scopeRefusal(own: Issue[], merged: Issue[]): Issue[] {
  const carried = own.filter(isError).map(identity);
  const added = merged.filter(isError).filter(found => {
    const at = carried.indexOf(identity(found));
    if (at < 0) return true;
    // Answered by one the config raises on its own, and that one only.
    carried.splice(at, 1);
    return false;
  });
  return added.length > 0 ? added : NO_REFUSAL;
}

/**
 * Whether two refusals say the same thing.
 *
 * Admission builds fresh issues every time, so a runtime that kept every
 * answer would report a change on every injection — and a host passing a
 * condition object built in render would then never stop re-rendering. The
 * refusal on hand is kept while it says the same, and only a different one
 * is a change worth telling anybody about.
 */
export function sameRefusal(
  left: readonly Issue[],
  right: readonly Issue[],
): boolean {
  return (
    left.length === right.length &&
    left.every((found, at) => identity(found) === identity(right[at]))
  );
}

function isError(found: Issue): boolean {
  return found.severity === 'error';
}

function identity(found: Issue): string {
  return `${found.code}\u0000${found.path.join('\u0000')}`;
}
