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
 * What a view's config uses that its source no longer admits (capabilities.md
 * Q2), and taking it out.
 *
 * The findings are the ones admission raises against the definition as the
 * source's descriptor narrowed it and does not raise against the definition
 * as declared: the config was fine, the deployment is what changed. The
 * view waits to be fixed, as for any error, and nothing is sent; this is the
 * one-click fix 「移除不可用的条件」 offers.
 */

import {
  without,
  type AnalysisViewConfig,
  type DataViewConfig,
  type FilterTree,
  type Issue,
  type IssuePath,
} from '../../model/index.js';
import {
  isEmptyFilter,
  isFilterGroup,
  isFilterLeaf,
  nodeAt,
  removeConditionAt,
  sameFilterTree,
  updateAt,
  walkFilter,
  type FilterPath,
} from '../../filter/index.js';

/** The errors of `effective` that `declared` does not have. */
export function unavailableIssues(
  effective: readonly Issue[],
  declared: readonly Issue[],
): Issue[] {
  const known = new Set(declared.filter(isError).map(found => keyOf(found)));
  return effective.filter(
    found =>
      isError(found) &&
      !known.has(keyOf(found)) &&
      // A view with no condition where the source wants one uses nothing
      // it lacks: it has not asked yet, and says so as its own state (Q3).
      found.code !== 'record.filter.required',
  );
}

/**
 * The config with the first finding that can be taken out taken out, or
 * `null` when none of them can: a condition goes (a filter's own or a
 * metric's), a sort entry goes, 「只保留」 goes whole, a dimension keeps
 * its field but loses the missing-value group or the filled gaps. A
 * dimension or a metric the source cannot compute stays — taking it out
 * would change the question, not trim it — and is left to the reader.
 */
export function withoutFirstUnavailable<C extends DataViewConfig>(
  config: C,
  issues: readonly Issue[],
): C | null {
  for (const found of issues) {
    const next = without1(config, found);
    if (next) return next;
  }
  return null;
}

/** Enough for any config a person wrote; a bound on a loop, not a budget. */
const MAX_REMOVALS = 256;

/**
 * The config with every finding that can be taken out taken out, one at a
 * time and judged again after each (`judge`): a condition taken out moves
 * the paths of the ones after it, and may take another finding with it.
 * The config itself when nothing could go.
 */
export function withoutUnavailable<C extends DataViewConfig>(
  config: C,
  judge: (config: C) => readonly Issue[],
): C {
  let current = config;
  for (let step = 0; step < MAX_REMOVALS; step += 1) {
    const next = withoutFirstUnavailable(current, judge(current));
    if (!next) break;
    current = next;
  }
  return current;
}

function without1<C extends DataViewConfig>(config: C, found: Issue): C | null {
  const [head, index, member] = found.path;
  if (head === 'children') {
    const filter = removedAt(config.filter, found.path);
    return filter ? { ...config, filter } : null;
  }
  if (head === 'sort' && typeof index === 'number') {
    const sort = (config.sort as unknown[]).filter((_, i) => i !== index);
    return { ...config, sort };
  }
  if (config.kind !== 'analysis') return null;
  if (head === 'having' && config.having !== undefined) {
    const next: AnalysisViewConfig = { ...config };
    delete next.having;
    return next as C;
  }
  if (head === 'groups' && typeof index === 'number') {
    const group = config.groups[index];
    if (!group || (member !== 'missingKey' && member !== 'dense')) return null;
    const groups = config.groups.map((entry, i) =>
      i === index ? without(entry as never, member) : entry,
    );
    return { ...config, groups };
  }
  if (head === 'metrics' && typeof index === 'number' && member === 'filter') {
    const metric = config.metrics[index];
    if (!metric || !('filter' in metric) || !metric.filter) return null;
    const filter = trimmed(metric.filter, found);
    if (!filter) return null;
    const metrics = config.metrics.map((entry, i) =>
      i === index ? { ...entry, filter } : entry,
    );
    return { ...config, metrics };
  }
  return null;
}

/**
 * A metric's condition less what the finding is about: the node it points
 * into, or every condition on the field it names.
 */
function trimmed(tree: FilterTree, found: Issue): FilterTree | null {
  const inner = found.path.slice(3);
  if (inner[0] === 'children') return removedAt(tree, inner);
  const field = found.params?.field;
  if (typeof field !== 'string') return null;
  const paths: FilterPath[] = [];
  for (const visit of walkFilter(tree))
    if (isFilterLeaf(visit.node) && visit.node.field === field)
      paths.push(
        visit.path.filter((step): step is number => typeof step === 'number'),
      );
  if (paths.length === 0) return null;
  // The deepest and last first, so an earlier path still points where it did.
  return changed(
    tree,
    paths.reverse().reduce((next, path) => removeConditionAt(next, path), tree),
  );
}

/**
 * One tree an issue path passes through, and where in it: the filter
 * itself, then the predicate of each element match the path goes on into.
 */
interface Level {
  tree: FilterTree;
  at: FilterPath;
}

/**
 * The tree without the node an issue path points into, or `null` when the
 * path points at nothing that can go — so a caller never takes an unchanged
 * tree for progress. A path that goes on past a leaf goes into the leaf's
 * predicate (an element match's, #3608): a kind that validates a nested
 * tree reports its findings under the leaf's own path, and the step into
 * `value` is not written out. An element match left with no condition asks
 * nothing of its elements and goes with it.
 */
function removedAt(tree: FilterTree, path: IssuePath): FilterTree | null {
  const levels = levelsOf(tree, path);
  let next: FilterTree | null = null;
  for (let i = levels.length - 1; i >= 0; i -= 1) {
    const { tree: current, at } = levels[i];
    if (next === null) {
      if (at.length === 0) continue;
      next = removeConditionAt(current, at);
    } else if (isEmptyFilter(next)) {
      next = removeConditionAt(current, at);
    } else {
      const value = next;
      next = updateAt(current, at, leaf => ({ ...leaf, value }) as never);
    }
  }
  return next && changed(tree, next);
}

/** The trees an issue path passes through, outermost first. */
function levelsOf(tree: FilterTree, path: IssuePath): Level[] {
  const levels: Level[] = [{ tree, at: [] }];
  for (let i = 0; i + 1 < path.length && path[i] === 'children';) {
    const index = path[i + 1];
    if (typeof index !== 'number') break;
    const level = levels[levels.length - 1];
    const node = nodeAt(level.tree, level.at);
    if (isFilterGroup(node)) {
      level.at = [...level.at, index];
      i += 2;
    } else if (isFilterLeaf(node) && isFilterGroup(node.value)) {
      levels.push({ tree: node.value, at: [] });
    } else break;
  }
  return levels;
}

/** `after`, or `null` when it says what `before` did. */
function changed(before: FilterTree, after: FilterTree): FilterTree | null {
  return sameFilterTree(before, after) ? null : after;
}

function isError(found: Issue): boolean {
  return found.severity === 'error';
}

function keyOf(found: Issue): string {
  return `${found.code} ${JSON.stringify(found.path)}`;
}
